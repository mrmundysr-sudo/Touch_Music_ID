import SwiftUI
import UIKit

struct ListenView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ListenContent(recognizer: model.recognizer)
    }
}

/// Split out so the view observes the model's real recognition service.
private struct ListenContent: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var recognizer: RecognitionService
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    private var state: ListenState { recognizer.state }
    private var busy: Bool { state == .listening || state == .processing }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack(spacing: 10) {
                    Image(systemName: "music.note").font(.title2).foregroundColor(Tmi.primary)
                    Text("Touch Music ID").font(.title2.weight(.bold)).foregroundColor(Tmi.textHigh)
                        .accessibilityAddTraits(.isHeader)
                }
                .padding(.top, 8)

                modePill

                listenButton
                    .padding(.vertical, 12)

                Text(headline).font(.title3.weight(.semibold)).foregroundColor(Tmi.textHigh)
                Text(subline).font(.subheadline).foregroundColor(Tmi.textMedium).multilineTextAlignment(.center)

                messageCard
            }
            .padding(.horizontal, Tmi.screenPadding)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity)
        }
        .navigationBarTitleDisplayMode(.inline)
        .tmiScreen()
        .onAppear { pulse = true }
        .onDisappear { if busy { model.cancelListening() } }
    }

    private var isDemoRun: Bool { model.demoMode && busy }

    private var headline: String {
        switch state {
        case .listening: return "Listening…"
        case .processing: return "Identifying…"
        case .needsPermission: return "Microphone access"
        default: return "Tap to listen"
        }
    }

    private var subline: String {
        if isDemoRun { return "Demo mode: nothing is recorded and this is an example result." }
        switch state {
        case .listening: return "Hold your phone near the music."
        case .processing: return "Matching what we heard."
        case .needsPermission: return "Allow the microphone to identify songs."
        default:
            return model.demoMode ? "Demo mode is on. Results are examples, not real recognition."
                                  : "Identify a song, then explore its story."
        }
    }

    @ViewBuilder private var modePill: some View {
        if model.demoMode { Pill(text: "Demo mode · examples only", color: Tmi.warm) }
        else { Pill(text: "Live recognition · ShazamKit", color: Tmi.success) }
    }

    private var listenButton: some View {
        Button { model.onListenTapped() } label: {
            ZStack {
                Circle().fill(model.accents.primary.opacity(0.10)).frame(width: 270, height: 270)
                    .scaleEffect(reduceMotion ? 1 : (pulse ? 1.06 : 0.94))
                    .animation(reduceMotion ? Animation?.none : Animation.easeInOut(duration: busy ? 1.5 : 3.2).repeatForever(autoreverses: true), value: pulse)
                Circle().fill(model.accents.primary.opacity(0.22)).frame(width: 210, height: 210)
                Circle().fill(model.accents.primary).frame(width: 168, height: 168)
                    .shadow(color: model.accents.primary.opacity(0.45), radius: 24)
                Image(systemName: busy ? "waveform" : "mic.fill")
                    .font(.system(size: 56, weight: .semibold))
                    .foregroundColor(model.accents.onPrimary)
            }
            .frame(width: 270, height: 270)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(busy ? "Stop listening" : "Listen to identify a song")
        .accessibilityHint(busy ? "Cancels listening" : "Starts listening through the microphone")
    }

    @ViewBuilder private var messageCard: some View {
        switch state {
        case .needsPermission:
            GlassCard {
                SectionTitle("Microphone is off")
                Text("Touch Music ID only listens when you tap the button, and never keeps the audio. Without the microphone it can't identify songs.")
                    .font(.subheadline).foregroundColor(Tmi.textMedium)
                TmiButton(title: "Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                TmiButton(title: "Try demo mode instead", primary: false) {
                    model.demoMode = true; model.dismissListenMessage()
                }
            }
        case .noMatch:
            GlassCard {
                SectionTitle("No match this time")
                Text("Move closer to the speaker, reduce background noise, and let the song play for a few seconds.")
                    .font(.subheadline).foregroundColor(Tmi.textMedium)
                TmiButton(title: "Listen again") { model.dismissListenMessage(); model.onListenTapped() }
            }
        case .failed(let kind, let message):
            GlassCard {
                SectionTitle(failureTitle(kind))
                Text(message).font(.subheadline).foregroundColor(Tmi.textMedium)
                TmiButton(title: "Try again") { model.dismissListenMessage(); model.onListenTapped() }
            }
        default:
            EmptyView()
        }
    }

    private func failureTitle(_ kind: FailureKind) -> String {
        switch kind {
        case .network: return "No connection"
        case .timeout: return "That took too long"
        case .audio: return "Couldn't use the microphone"
        case .silence: return "Can't hear anything"
        case .token: return "Authorisation problem"
        case .unknown: return "Something went wrong"
        }
    }
}
