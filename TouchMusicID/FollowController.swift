import Foundation
import SwiftUI
import UIKit
import AVFoundation

/// Follow along: listens through the microphone, works out where in the song the music is, and highlights the matching lyric line.
/// Orchestrates ShazamKit fixes -> position estimates -> sync policy -> active line. Ported from the Android Lyrics Lab.
/// Silence detection is a heuristic for "playback paused" and is labelled as such.
@MainActor
final class FollowController: ObservableObject {
    private enum Keys {
        static let nudge = "follow_nudge_ms"
        static let estimator = "follow_estimator"
        static let autoScroll = "follow_autoscroll"
    }
    private static let silenceRms = 0.0015
    private static let silenceHoldMs = 4_000.0
    private static let soundResumeMs = 300.0
    /// A fresh ShazamKit session is started this often so position fixes keep arriving.
    private static let sessionWindowSeconds: UInt64 = 25

    @Published private(set) var running = false { didSet { UIApplication.shared.isIdleTimerDisabled = running } }
    @Published private(set) var status = "Idle"
    @Published private(set) var activeIndex = -1
    @Published private(set) var positionSec = -1
    @Published private(set) var syncState: SyncState = .searching
    @Published private(set) var confidence = 0.0
    @Published private(set) var diag = ""
    @Published private(set) var micDenied = false
    @Published private(set) var nudgeMs: Int
    @Published private(set) var estimator: Estimator
    @Published var autoScroll: Bool { didSet { defaults.set(autoScroll, forKey: Keys.autoScroll) } }

    private let defaults: UserDefaults
    private let sync = SyncController()
    private var probe: FollowProbe?
    private var song: Song?
    private var lines: [LrcLine] = []
    private var tickTask: Task<Void, Never>?
    private var rotateTask: Task<Void, Never>?
    private var startTask: Task<Void, Never>?
    private var lastRaw: RawFix?
    private var lastEst: Estimates?
    private var otherSong: String?
    private var quietStart: Double?
    private var soundStart: Double?
    private var lastRms = 0.0

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let nudge = defaults.integer(forKey: Keys.nudge)
        nudgeMs = nudge
        estimator = Estimator(rawValue: defaults.string(forKey: Keys.estimator) ?? "") ?? .b
        autoScroll = defaults.object(forKey: Keys.autoScroll) as? Bool ?? true
        sync.clock.nudgeMs = Double(nudge)
    }

    // MARK: Controls

    func start(song: Song, lines: [LrcLine]) {
        guard !running, startTask == nil, !lines.isEmpty else { return }
        startTask = Task { [weak self] in
            await self?.begin(song: song, lines: lines)
            self?.startTask = nil
        }
    }

    private func begin(song: Song, lines: [LrcLine]) async {
        micDenied = false
        guard await micAllowed() else {
            micDenied = true
            status = "Microphone access is needed to follow along."
            return
        }
        self.song = song
        self.lines = lines
        sync.reset()
        otherSong = nil
        quietStart = nil; soundStart = nil
        activeIndex = -1; positionSec = -1; confidence = 0

        let probe = FollowProbe { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
        do { try probe.start() } catch {
            status = "Couldn't use the microphone. Close other apps that are recording and try again."
            return
        }
        self.probe = probe
        running = true
        status = "Listening… play the song near the phone."
        tickTask = Task { [weak self] in await self?.tickLoop() }
        rotateTask = Task { [weak self] in await self?.rotateLoop() }
    }

    func stop() {
        startTask?.cancel(); startTask = nil
        tickTask?.cancel(); tickTask = nil
        rotateTask?.cancel(); rotateTask = nil
        probe?.stop(); probe = nil
        let wasRunning = running
        running = false
        activeIndex = -1
        positionSec = -1
        confidence = 0
        syncState = .searching
        if wasRunning { status = "Stopped" }
    }

    /// Take a fresh recognition window and replace the anchor with it (no damping).
    func resync() {
        guard running else { return }
        sync.requireFreshFix()
        probe?.restartSession()
        status = "Re-syncing…"
    }

    func setEstimator(_ e: Estimator) {
        guard e != estimator else { return }
        estimator = e
        defaults.set(e.rawValue, forKey: Keys.estimator)
        // Re-anchor from the most recent fix with the newly chosen assumption, so the three can be compared live.
        if let raw = lastRaw, let pos = lastEst?.pick(e), song != nil {
            sync.requireFreshFix()
            sync.onFix(PositionFix(songMs: pos, atMono: raw.readMonoMs, rate: Positioning.rate(raw.frequencySkew), tag: e.rawValue))
        }
    }

    func nudge(_ deltaMs: Int) {
        sync.nudge(Double(deltaMs))
        nudgeMs = Int(sync.clock.nudgeMs)
        defaults.set(nudgeMs, forKey: Keys.nudge)
    }

    func resetNudge() {
        sync.clock.nudgeMs = 0
        nudgeMs = 0
        defaults.set(0, forKey: Keys.nudge)
    }

    // MARK: Events

    private func handle(_ event: ProbeEvent) {
        guard running else { return }
        switch event {
        case .audio(let mono, let rms): onAudio(mono, rms)
        case .fix(let raw): onFix(raw)
        case .noMatch: break
        case .failed(let message): status = "Problem: \(message)"
        }
    }

    private func onAudio(_ mono: Double, _ rms: Double) {
        lastRms = rms
        // Heuristic only: sustained quiet may be a pause, or just a quiet passage of the song.
        if rms < Self.silenceRms {
            if quietStart == nil { quietStart = mono }
            soundStart = nil
            if let q = quietStart, mono - q >= Self.silenceHoldMs, sync.state == .following { sync.onSilenceStart(sinceMono: q) }
        } else {
            quietStart = nil
            if sync.state == .hold {
                if soundStart == nil { soundStart = mono }
                if let s = soundStart, mono - s >= Self.soundResumeMs {
                    sync.onSoundResume(nowMono: mono)
                    soundStart = nil
                    probe?.restartSession()   // ask for a fresh measurement now that the music is back
                }
            } else {
                soundStart = nil
            }
        }
    }

    private func onFix(_ raw: RawFix) {
        guard let song else { return }
        guard LyricsMatcher.sameTrack(titleA: raw.title, artistA: raw.artist, titleB: song.title, artistB: song.artist) else {
            otherSong = raw.title
            return
        }
        otherSong = nil
        let est = Positioning.estimate(raw)
        lastRaw = raw
        lastEst = est
        guard let pos = est.pick(estimator) else { return }
        sync.onFix(PositionFix(songMs: pos, atMono: raw.readMonoMs, rate: Positioning.rate(raw.frequencySkew), tag: estimator.rawValue))
    }

    // MARK: Loops

    private func rotateLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: Self.sessionWindowSeconds * 1_000_000_000)
            if Task.isCancelled { return }
            probe?.restartSession()
        }
    }

    private func tickLoop() async {
        var n = 0
        while !Task.isCancelled {
            let now = FollowProbe.nowMono()
            sync.evaluate(now)
            let pos = sync.clock.positionMs(now)
            let idx = pos.map { LrcParser.findActiveIndex(lines, posMs: Int($0)) } ?? -1
            if idx != activeIndex { activeIndex = idx }
            let sec = pos.map { Int($0 / 1000) } ?? -1
            if sec != positionSec { positionSec = sec }
            if sync.state != syncState { syncState = sync.state }
            let conf = (sync.confidence(now) * 20).rounded() / 20
            if conf != confidence { confidence = conf }
            let text = statusText()
            if text != status { status = text }
            if n % 5 == 0 { diag = diagText(now, pos) }
            n += 1
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    private func statusText() -> String {
        if let o = otherSong { return "Hearing “\(o)”, which isn't this song." }
        switch sync.state {
        case .searching: return "Listening… waiting for a match."
        case .following: return "Following along"
        case .hold: return "Paused (it went quiet). Will resume when the music does."
        case .unreliable: return "Lost the place. Listening for a fresh match…"
        }
    }

    private func diagText(_ now: Double, _ pos: Double?) -> String {
        let ago = sync.lastVerifiedMono.map { String(format: "%.0f", (now - $0) / 1000) } ?? "-"
        return "pos=\(pos.map { Int($0) } ?? -1) ms · \(sync.state.rawValue) · conf=\(String(format: "%.2f", sync.confidence(now))) · last fix \(ago)s ago · est \(estimator.rawValue) · nudge \(nudgeMs) · mic \(String(format: "%.4f", lastRms))"
    }

    // MARK: Microphone

    private func micAllowed() async -> Bool {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: return true
        case .denied: return false
        default:
            return await withCheckedContinuation { c in
                AVAudioSession.sharedInstance().requestRecordPermission { c.resume(returning: $0) }
            }
        }
    }
}
