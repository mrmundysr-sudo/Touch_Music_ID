import Foundation

/*
 * Synchronised-lyrics timing, ported from the Android Lyrics Lab (LyricClock, SyncController, Positioning).
 * Correction policy adapted from LiveLyrics (https://github.com/Nurzhigit-dev/LiveLyrics, MIT): ignore small differences,
 * halve medium ones, and require a second consistent measurement before a large jump (guards against a repeated chorus or a
 * false match). Silence holds the clock where the sound stopped and asks for a fresh fix on resume. Silence is only a
 * HEURISTIC for "paused": quiet passages also look like silence.
 * All times are monotonic milliseconds (system uptime), never wall-clock.
 */

/// Song position as a function of a monotonic clock. Position is DERIVED from the anchor on every read, never accumulated
/// (so a stalled UI thread cannot make it drift).
public final class LyricClock {
    public private(set) var hasAnchor = false
    public private(set) var anchorSongMs = 0.0
    public private(set) var anchorAtMono = 0.0
    public private(set) var rate = 1.0

    /// Persistent user timing correction (positive = lyrics later in the song, i.e. highlight lines sooner).
    public var nudgeMs = 0.0
    /// While set, the clock is parked here (raw position, without nudge).
    public var holdRawMs: Double?

    public init() {}

    public func setAnchor(songMs: Double, atMono: Double, rate: Double) {
        hasAnchor = true
        anchorSongMs = songMs
        anchorAtMono = atMono
        self.rate = rate
    }

    public func clear() {
        hasAnchor = false
        holdRawMs = nil
        anchorSongMs = 0
        anchorAtMono = 0
        rate = 1
    }

    /// Position the anchor says we are at, ignoring any hold.
    public func runningRawMs(_ nowMono: Double) -> Double? {
        hasAnchor ? anchorSongMs + (nowMono - anchorAtMono) * rate : nil
    }

    /// Raw position honouring a hold.
    public func rawMs(_ nowMono: Double) -> Double? { holdRawMs ?? runningRawMs(nowMono) }

    /// What the UI shows: raw position plus the user's nudge, never negative.
    public func positionMs(_ nowMono: Double) -> Double? { rawMs(nowMono).map { max(0, $0 + nudgeMs) } }
}

public enum SyncState: String, Equatable { case searching, following, hold, unreliable }

/// A measurement of the song position at a monotonic instant.
public struct PositionFix: Equatable {
    public var songMs: Double
    public var atMono: Double
    public var rate: Double
    public var tag: String
    public init(songMs: Double, atMono: Double, rate: Double = 1.0, tag: String = "") {
        self.songMs = songMs; self.atMono = atMono; self.rate = rate; self.tag = tag
    }
}

public struct SyncEvent: Equatable {
    public let atMono: Double
    public let kind: String
    public let diffMs: Double
    public let appliedMs: Double
    public let note: String
}

public final class SyncController {
    public struct Config {
        public var ignoreBelowMs = 250.0
        public var halfBelowMs = 2_500.0
        public var confirmToleranceMs = 1_500.0
        public var pendingWindowMs = 30_000.0
        public var staleMs = 150_000.0
        public var maxContradictions = 2
        public var confidenceTauMs = 90_000.0
        /// How many agrees after INIT before large jumps need a confirming second fix.
        public var earlyLockAgrees = 3
        public init() {}
    }

    private struct Pending { let diffMs: Double; let atMono: Double }

    public let clock: LyricClock
    private let cfg: Config

    public private(set) var state: SyncState = .searching
    public private(set) var lastVerifiedMono: Double?
    public private(set) var agreeCount = 0
    public private(set) var contradictions = 0
    public private(set) var events: [SyncEvent] = []

    private var pending: Pending?
    private var resumePending = false

    public var hasPending: Bool { pending != nil }

    public init(clock: LyricClock = LyricClock(), config: Config = Config()) {
        self.clock = clock
        self.cfg = config
    }

    public func reset() {
        clock.clear()
        state = .searching
        lastVerifiedMono = nil
        agreeCount = 0
        contradictions = 0
        pending = nil
        resumePending = false
    }

    /// The next fix replaces the anchor outright (used by Re-sync and after a pause).
    public func requireFreshFix() { resumePending = true }

    @discardableResult
    public func onFix(_ fix: PositionFix) -> SyncEvent {
        if !clock.hasAnchor {
            adopt(fix)
            state = .following
            agreeCount = 1
            contradictions = 0
            return log(fix.atMono, "INIT", 0, 0)
        }
        if state == .hold { return log(fix.atMono, "IGNORED_HOLD", 0, 0) }

        let running = clock.runningRawMs(fix.atMono) ?? fix.songMs
        let diff = fix.songMs - running

        if resumePending || state == .unreliable {
            let kind = state == .unreliable ? "RECOVER" : "RESUME"
            adopt(fix)
            resumePending = false
            state = .following
            pending = nil
            contradictions = 0
            agreeCount = 1
            return log(fix.atMono, kind, diff, diff)
        }

        let ad = abs(diff)

        // Early after lock-in, allow larger corrections without waiting for a second confirmation.
        if agreeCount < cfg.earlyLockAgrees && ad <= cfg.halfBelowMs * 2 {
            let shift = ad <= cfg.halfBelowMs ? diff / 2 : diff
            clock.setAnchor(songMs: running + shift, atMono: fix.atMono, rate: fix.rate)
            contradictions = 0
            pending = nil
            agreeCount += 1
            lastVerifiedMono = fix.atMono
            return log(fix.atMono, shift == diff ? "EARLY_JUMP" : "EARLY_HALF", diff, shift)
        }

        if ad <= cfg.ignoreBelowMs {
            agreeCount += 1
            contradictions = 0
            pending = nil
            lastVerifiedMono = fix.atMono
            return log(fix.atMono, "AGREE", diff, 0)
        }
        if ad <= cfg.halfBelowMs {
            let shift = diff / 2
            clock.setAnchor(songMs: running + shift, atMono: fix.atMono, rate: fix.rate)
            contradictions = 0
            pending = nil
            agreeCount += 1
            lastVerifiedMono = fix.atMono
            return log(fix.atMono, "HALF", diff, shift)
        }
        return large(fix, diff)
    }

    private func large(_ fix: PositionFix, _ diff: Double) -> SyncEvent {
        var p = pending
        if let q = p, fix.atMono - q.atMono > cfg.pendingWindowMs { p = nil }
        if let q = p, abs(diff - q.diffMs) <= cfg.confirmToleranceMs {
            adopt(fix)
            pending = nil
            contradictions = 0
            agreeCount = 1
            return log(fix.atMono, "JUMP", diff, diff)
        }
        if p != nil { contradictions += 1 }
        pending = Pending(diffMs: diff, atMono: fix.atMono)
        if contradictions >= cfg.maxContradictions { state = .unreliable }
        return log(fix.atMono, "PENDING", diff, 0, "contradictions=\(contradictions)")
    }

    private func adopt(_ fix: PositionFix) {
        clock.holdRawMs = nil
        clock.setAnchor(songMs: fix.songMs, atMono: fix.atMono, rate: fix.rate)
        lastVerifiedMono = fix.atMono
    }

    /// Sustained quiet began at `sinceMono`. Park the clock there.
    public func onSilenceStart(sinceMono: Double) {
        guard state == .following, clock.hasAnchor else { return }
        clock.holdRawMs = clock.runningRawMs(sinceMono)
        state = .hold
        log(sinceMono, "HOLD", 0, 0, "heuristic: sustained quiet")
    }

    /// Sound is back. Continue from the parked position and ask for a fresh measurement.
    public func onSoundResume(nowMono: Double) {
        guard state == .hold, let held = clock.holdRawMs else { return }
        clock.holdRawMs = nil
        clock.setAnchor(songMs: held, atMono: nowMono, rate: clock.rate)
        resumePending = true
        state = .following
        log(nowMono, "RESUME_PENDING", 0, 0, "continuing from \(Int(held)) ms")
    }

    /// Call regularly. Verification gets stale: with no recent fix the position can no longer be trusted.
    public func evaluate(_ nowMono: Double) {
        guard let v = lastVerifiedMono else { return }
        if state == .following && nowMono - v > cfg.staleMs {
            state = .unreliable
            log(nowMono, "STALE", 0, 0, "no verification for \(Int((nowMono - v) / 1000)) s")
        }
    }

    public func confidence(_ nowMono: Double) -> Double {
        if !clock.hasAnchor || state == .unreliable || state == .searching { return 0 }
        let base = min(1.0, 0.5 + 0.15 * Double(agreeCount))
        let age = max(0, nowMono - (lastVerifiedMono ?? nowMono))
        var c = base * exp(-age / cfg.confidenceTauMs)
        if pending != nil { c -= 0.25 }
        if state == .hold { c = min(c, 0.5) }
        return min(max(c, 0), 1)
    }

    public func nudge(_ deltaMs: Double) {
        clock.nudgeMs = min(max(clock.nudgeMs + deltaMs, -5_000), 5_000)
    }

    @discardableResult
    private func log(_ at: Double, _ kind: String, _ diff: Double, _ applied: Double, _ note: String = "") -> SyncEvent {
        let e = SyncEvent(atMono: at, kind: kind, diffMs: diff, appliedMs: applied, note: note)
        events.append(e)
        if events.count > 300 { events.removeFirst() }
        return e
    }
}

// MARK: Positioning

/// Everything ShazamKit reported for one match, plus our own monotonic clocks at the moment it arrived.
public struct RawFix: Equatable {
    public var sessionId: Int
    public var title: String
    public var artist: String
    public var isrc: String?
    public var appleMusicId: String?
    public var matchOffsetMs: Double?
    public var predictedMs: Double?
    public var frequencySkew: Double?
    public var sigDurationMs: Double
    /// Monotonic time the match was read.
    public var readMonoMs: Double
    /// Monotonic time the newest audio chunk fed to the session ended.
    public var lastFedEndMono: Double
    /// Monotonic time this session received its first audio.
    public var sessionStartMono: Double

    public init(sessionId: Int, title: String, artist: String, isrc: String?, appleMusicId: String?, matchOffsetMs: Double?,
                predictedMs: Double?, frequencySkew: Double?, sigDurationMs: Double, readMonoMs: Double,
                lastFedEndMono: Double, sessionStartMono: Double) {
        self.sessionId = sessionId; self.title = title; self.artist = artist; self.isrc = isrc; self.appleMusicId = appleMusicId
        self.matchOffsetMs = matchOffsetMs; self.predictedMs = predictedMs; self.frequencySkew = frequencySkew
        self.sigDurationMs = sigDurationMs; self.readMonoMs = readMonoMs; self.lastFedEndMono = lastFedEndMono
        self.sessionStartMono = sessionStartMono
    }
}

public enum Estimator: String, CaseIterable, Equatable { case a = "A", b = "B", c = "C" }

/// Song position AT THE INSTANT THE MATCH WAS READ, by three independent assumptions. Nil when not computable or implausible.
public struct Estimates: Equatable {
    public var a: Double?
    public var b: Double?
    public var c: Double?
    public func pick(_ e: Estimator) -> Double? {
        switch e { case .a: return a; case .b: return b; case .c: return c }
    }
}

public enum Positioning {
    private static let maxPlausibleMs = 30.0 * 60 * 1000

    /// A = the SDK's own predictedCurrentMatchOffset.
    /// B = matchOffset + signatureDuration + (time since the last chunk we fed).
    /// C = matchOffset + (read time - session start), assuming the signature began at the first chunk fed.
    /// B and C ignore frequencySkew by design. Comparing them on a real phone shows which assumption holds.
    public static func estimate(_ raw: RawFix) -> Estimates {
        let a = raw.predictedMs.flatMap(plausible)
        let b = raw.matchOffsetMs.flatMap { plausible($0 + raw.sigDurationMs + (raw.readMonoMs - raw.lastFedEndMono)) }
        let c = raw.matchOffsetMs.flatMap { plausible($0 + (raw.readMonoMs - raw.sessionStartMono)) }
        return Estimates(a: a, b: b, c: c)
    }

    private static func plausible(_ v: Double) -> Double? {
        (v.isNaN || v.isInfinite || v < -2_000 || v > maxPlausibleMs) ? nil : max(0, v)
    }

    /// Playback-speed factor from ShazamKit's frequency skew (0.1 = 10% higher pitch/speed); ignored when tiny.
    public static func rate(_ skew: Double?) -> Double {
        guard let s = skew, !s.isNaN, abs(s) >= 0.003, abs(s) <= 0.2 else { return 1 }
        return 1 + s
    }

    /// Position of a running estimate `nowMono` after it was read at `readMono`.
    public static func advance(atRead: Double, readMono: Double, nowMono: Double) -> Double { atRead + (nowMono - readMono) }
}

/// Events produced by a listening session. The probe never interprets them; the controller does.
public enum ProbeEvent: Equatable {
    case audio(monoMs: Double, rms: Double)
    case fix(RawFix)
    case noMatch(sessionId: Int)
    case failed(String)
}
