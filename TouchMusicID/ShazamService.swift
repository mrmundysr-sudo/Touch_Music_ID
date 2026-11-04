import Foundation
import ShazamKit
import AVFoundation

/// What the Listen screen shows. Mirrors Android's ListenState.
enum ListenState: Equatable {
    case idle
    case listening
    case processing
    /// Microphone permission was refused. `canAskAgain` is false once iOS will no longer show the prompt.
    case needsPermission(canAskAgain: Bool)
    case noMatch
    case failed(FailureKind, String)
}

/// Demo songs: well-known tracks so the Wikipedia/Wikidata experience can be exercised end to end.
/// Every result from demo mode is tagged `.demo` and labelled in the UI. It never listens and never identifies anything.
enum DemoSongs {
    static let all: [Song] = [
        Song(title: "Bohemian Rhapsody", artist: "Queen", album: "A Night at the Opera"),
        Song(title: "Billie Jean", artist: "Michael Jackson", album: "Thriller"),
        Song(title: "Hey Jude", artist: "The Beatles"),
        Song(title: "Rolling in the Deep", artist: "Adele", album: "21"),
        Song(title: "Purple Rain", artist: "Prince", album: "Purple Rain"),
        Song(title: "Smells Like Teen Spirit", artist: "Nirvana", album: "Nevermind"),
    ]
}

/// Listens with ShazamKit (or returns a labelled demo song) and reports a match once through `onMatch`.
/// The microphone is used only between a tap and a match / cancel / timeout / backgrounding. Raw audio is never stored.
@MainActor
final class RecognitionService: NSObject, ObservableObject, SHSessionDelegate {
    @Published private(set) var state: ListenState = .idle

    /// Called on the main actor with each completed recognition.
    var onMatch: ((Song, RecognitionSource) -> Void)?

    private let timeoutSeconds: UInt64 = 20
    private var session: SHSession?
    private var audioEngine: AVAudioEngine?
    private var timeoutTask: Task<Void, Never>?
    private var demoTask: Task<Void, Never>?

    var isBusy: Bool { state == .listening || state == .processing }

    // MARK: Public

    func tapped(demo: Bool) {
        if isBusy { cancel() } else { Task { await start(demo: demo) } }
    }

    func start(demo: Bool) async {
        guard !isBusy else { return }
        if demo { runDemo(); return }

        switch AVAudioSession.sharedInstance().recordPermission {
        case .denied:
            state = .needsPermission(canAskAgain: false)
            return
        case .undetermined:
            let granted = await requestMicrophonePermission()
            if !granted { state = .needsPermission(canAskAgain: false); return }
        default:
            break
        }

        do {
            try configureAudioSession()
            let s = SHSession()
            s.delegate = self
            session = s
            try startAudioEngine(feeding: s)
            state = .listening
            startTimeout()
        } catch {
            teardown()
            state = .failed(.audio, "Couldn't use the microphone. Close other apps that are recording and try again.")
        }
    }

    func cancel() {
        teardown()
        state = .idle
    }

    func reset() {
        if !isBusy { state = .idle }
    }

    // MARK: Demo

    private func runDemo() {
        state = .listening
        demoTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            guard !Task.isCancelled else { return }
            self?.state = .processing
            try? await Task.sleep(nanoseconds: 900_000_000)
            guard !Task.isCancelled, let self else { return }
            let song = DemoSongs.all.randomElement() ?? DemoSongs.all[0]
            self.state = .idle
            self.onMatch?(song, .demo)
        }
    }

    // MARK: Audio

    private func requestMicrophonePermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    private func configureAudioSession() throws {
        let s = AVAudioSession.sharedInstance()
        try s.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
        try s.setActive(true)
    }

    private func startAudioEngine(feeding session: SHSession) throws {
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        Self.installTap(on: input, format: format, session: session)
        engine.prepare()
        try engine.start()
        audioEngine = engine
    }

    /// Built in a nonisolated context on purpose: the tap block runs on the audio thread, so it must not inherit main-actor isolation.
    private nonisolated static func installTap(on input: AVAudioInputNode, format: AVAudioFormat, session: SHSession) {
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { buffer, time in
            session.matchStreamingBuffer(buffer, at: time)
        }
    }

    private func startTimeout() {
        timeoutTask?.cancel()
        let seconds = timeoutSeconds
        timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            guard !Task.isCancelled, let self, self.isBusy else { return }
            self.teardown()
            self.state = .failed(.timeout, "Listening took too long. Please try again.")
        }
    }

    /// Stops capture and releases the microphone. Safe to call repeatedly.
    private func teardown() {
        timeoutTask?.cancel(); timeoutTask = nil
        demoTask?.cancel(); demoTask = nil
        if let engine = audioEngine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        audioEngine = nil
        session?.delegate = nil
        session = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: SHSessionDelegate

    nonisolated func session(_ session: SHSession, didFind match: SHMatch) {
        guard let item = match.mediaItems.first else { return }
        let song = Song(
            title: item.title ?? "Unknown title",
            artist: item.artist ?? "Unknown artist",
            album: nil,
            appleMusicURL: item.appleMusicURL?.absoluteString,
            webURL: item.webURL?.absoluteString,
            isrc: item.isrc,
            artworkURL: item.artworkURL?.absoluteString,
            appleMusicId: item.appleMusicID
        )
        Task { @MainActor in
            guard self.isBusy else { return }
            self.teardown()
            self.state = .idle
            self.onMatch?(song, .shazamkit)
        }
    }

    nonisolated func session(_ session: SHSession, didNotFindMatchFor signature: SHSignature, error: Error?) {
        let failure: ListenState
        if error is URLError {
            failure = .failed(.network, "No connection. Song matching needs the internet.")
        } else if error != nil {
            failure = .failed(.unknown, "Something went wrong while matching. Please try again.")
        } else {
            failure = .noMatch
        }
        Task { @MainActor in
            guard self.isBusy else { return }
            self.teardown()
            self.state = failure
        }
    }
}
