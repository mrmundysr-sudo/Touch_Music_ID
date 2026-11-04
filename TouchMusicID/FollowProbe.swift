import Foundation
import AVFoundation
import ShazamKit

/// One microphone + ShazamKit streaming capture for Follow along. The audio engine runs for the whole follow session; the
/// ShazamKit session can be replaced (`restartSession`) to get a fresh match without a gap in capture. Raw audio is never stored.
/// Reports `ProbeEvent`s through `sink` (called from audio/ShazamKit threads; the receiver hops to the main actor).
final class FollowProbe: NSObject, SHSessionDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var engine: AVAudioEngine?
    private var session: SHSession?
    private var sessionId = 0
    private var sessionStartMono = 0.0
    private var lastFedEndMono = 0.0
    private var lastAudioEmit = 0.0
    private var rmsSum = 0.0
    private var rmsCount = 0
    private let sink: @Sendable (ProbeEvent) -> Void

    init(sink: @escaping @Sendable (ProbeEvent) -> Void) {
        self.sink = sink
        super.init()
    }

    /// Monotonic milliseconds (system uptime). Never wall-clock.
    static func nowMono() -> Double { ProcessInfo.processInfo.systemUptime * 1000 }

    func start() throws {
        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
        try audio.setActive(true)

        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        restartSession()
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, time in
            self?.onBuffer(buffer, time)
        }
        engine.prepare()
        try engine.start()
        lock.lock(); self.engine = engine; lock.unlock()
    }

    /// Starts a fresh ShazamKit session (new signature). Audio capture continues uninterrupted.
    func restartSession() {
        let s = SHSession()
        s.delegate = self
        lock.lock()
        sessionId += 1
        sessionStartMono = Self.nowMono()
        session = s
        lock.unlock()
    }

    /// Stops capture and releases the microphone. Safe to call repeatedly.
    func stop() {
        lock.lock()
        let e = engine
        engine = nil
        session?.delegate = nil
        session = nil
        lock.unlock()
        if let e {
            e.inputNode.removeTap(onBus: 0)
            e.stop()
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func onBuffer(_ buffer: AVAudioPCMBuffer, _ time: AVAudioTime) {
        let now = Self.nowMono()
        var rms = 0.0
        if let channel = buffer.floatChannelData?[0] {
            let n = Int(buffer.frameLength)
            var sum: Float = 0
            for i in 0..<n { sum += channel[i] * channel[i] }
            if n > 0 { rms = Double((sum / Float(n)).squareRoot()) }
        }
        lock.lock()
        // The buffer is delivered right after it was captured, so its end is taken as "now" (error is tens of ms).
        lastFedEndMono = now
        let s = session
        rmsSum += rms
        rmsCount += 1
        var emit: Double?
        if now - lastAudioEmit >= 100 {
            emit = rmsSum / Double(max(rmsCount, 1))
            rmsSum = 0; rmsCount = 0; lastAudioEmit = now
        }
        lock.unlock()
        s?.matchStreamingBuffer(buffer, at: time)
        if let emit { sink(.audio(monoMs: now, rms: emit)) }
    }

    // MARK: SHSessionDelegate

    func session(_ session: SHSession, didFind match: SHMatch) {
        let now = Self.nowMono()
        lock.lock()
        guard session === self.session else { lock.unlock(); return }   // a replaced session's late answer
        let id = sessionId, start = sessionStartMono, fed = lastFedEndMono
        lock.unlock()
        guard let item = match.mediaItems.first else { return }
        let raw = RawFix(
            sessionId: id,
            title: item.title ?? "",
            artist: item.artist ?? "",
            isrc: item.isrc,
            appleMusicId: item.appleMusicID,
            matchOffsetMs: item.matchOffset * 1000,
            predictedMs: item.predictedCurrentMatchOffset * 1000,
            frequencySkew: Double(item.frequencySkew),
            sigDurationMs: match.querySignature.duration * 1000,
            readMonoMs: now,
            lastFedEndMono: fed,
            sessionStartMono: start)
        sink(.fix(raw))
    }

    func session(_ session: SHSession, didNotFindMatchFor signature: SHSignature, error: Error?) {
        lock.lock()
        let current = session === self.session
        let id = sessionId
        lock.unlock()
        guard current else { return }
        if let error, !(error is CancellationError) {
            sink(.failed(error.localizedDescription))
        } else {
            sink(.noMatch(sessionId: id))
        }
    }
}
