import SwiftUI
import ShazamKit

struct ContentView: View {
    @StateObject private var shazam = ShazamService()
    @State private var selectedTab = 0

    var body: some View {
        ZStack {
            // Full-screen blurred / dynamic album-art background
            LinearGradient(
                colors: [shazam.accentColor.opacity(0.7), Color.black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 20) {
                // Prominent album artwork
                RoundedRectangle(cornerRadius: 16)
                    .fill(shazam.accentColor.opacity(0.35))
                    .frame(width: 260, height: 260)
                    .overlay(
                        Group {
                            if let art = shazam.artwork {
                                Image(uiImage: art)
                                    .resizable()
                                    .scaledToFill()
                            } else {
                                Image(systemName: "music.note")
                                    .font(.system(size: 80))
                                    .foregroundColor(.white.opacity(0.8))
                            }
                        }
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .shadow(color: shazam.accentColor.opacity(0.4), radius: 20)

                VStack(spacing: 4) {
                    Text(shazam.title)
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                    Text(shazam.artist)
                        .font(.body)
                        .foregroundColor(.white.opacity(0.8))
                }

                if let error = shazam.errorMessage {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Button(action: {
                    Task { await shazam.startListening() }
                }) {
                    Label(shazam.isListening ? "Listening…" : "Identify Song", systemImage: "waveform")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(shazam.accentColor)
                .disabled(shazam.isListening)
                .padding(.horizontal)

                // Permanent tabs
                WikiTabsView(selectedTab: $selectedTab, title: shazam.title, artist: shazam.artist)
                    .frame(height: 180)
            }
            .padding()
        }
    }
}

#Preview {
    ContentView()
}
