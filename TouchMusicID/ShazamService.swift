import Foundation
import ShazamKit
import AVFoundation
import UIKit
import SwiftUI

@MainActor
final class ShazamService: NSObject, ObservableObject, SHSessionDelegate {
    @Published var title: String = "Touch Music ID"
    @Published var artist: String = "Tap to identify"
    @Published var artwork: UIImage?
    @Published var accentColor: Color = .blue
    @Published var isListening = false
    @Published var errorMessage: String?

    private var session: SHSession?
    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?

    override init() {
        super.init()
        session = SHSession()
        session?.delegate = self
    }

    func startListening() async {
        errorMessage = nil
        guard await requestMicrophonePermission() else {
            errorMessage = "Microphone access is required to identify songs."
            return
        }

        do {
            try configureAudioSession()
            try startAudioEngine()
            isListening = true
        } catch {
            errorMessage = "Could not start listening: \(error.localizedDescription)"
            isListening = false
        }
    }

    func stopListening() {
        audioEngine?.stop()
        inputNode?.removeTap(onBus: 0)
        isListening = false
    }

    private func requestMicrophonePermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
        try session.setActive(true)
    }

    private func startAudioEngine() throws {
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)

        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            self?.session?.matchStreamingBuffer(buffer, at: nil)
        }

        try engine.start()
        self.audioEngine = engine
        self.inputNode = input
    }

    // MARK: - SHSessionDelegate

    nonisolated func session(_ session: SHSession, didFind match: SHMatch) {
        Task { @MainActor in
            self.stopListening()
            guard let item = match.mediaItems.first else { return }
            self.title = item.title ?? "Unknown Title"
            self.artist = item.artist ?? "Unknown Artist"
            self.errorMessage = nil

            if let artworkURL = item.artworkURL {
                await self.loadArtwork(from: artworkURL)
            }
        }
    }

    nonisolated func session(_ session: SHSession, didNotFindMatchFor signature: SHSignature, error: Error?) {
        Task { @MainActor in
            self.stopListening()
            self.errorMessage = error?.localizedDescription ?? "No match found. Try again closer to the speaker."
        }
    }

    private func loadArtwork(from url: URL) async {
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            if let image = UIImage(data: data) {
                self.artwork = image
                self.accentColor = extractAccent(from: image)
            }
        } catch {
            // Artwork is optional; keep the gradient fallback
        }
    }

    private func extractAccent(from image: UIImage) -> Color {
        // Simple fallback accent; real Palette extraction can be added later
        Color(red: 0.3, green: 0.6, blue: 1.0)
    }
}
