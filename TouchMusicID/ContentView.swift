import SwiftUI
import ShazamKit

struct ContentView: View {
    @StateObject private var shazam = ShazamService()
    @State private var selectedTab = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let background = Color(red: 0.043, green: 0.059, blue: 0.102) // #0B0F1A
    private let primary = Color(red: 0.369, green: 0.918, blue: 0.831)   // #5EEAD4 teal
    private let textHigh = Color(red: 0.953, green: 0.965, blue: 1.0)    // #F3F6FF
    private let textMedium = Color(red: 0.706, green: 0.745, blue: 0.847) // #B4BED8

    var body: some View {
        ZStack {
            // Dynamic background: blurred album art or solid dark
            if let art = shazam.artwork, !reduceMotion {
                Image(uiImage: art)
                    .resizable()
                    .scaledToFill()
                    .blur(radius: 40)
                    .overlay(Color.black.opacity(0.55))
                    .ignoresSafeArea()
            } else {
                background.ignoresSafeArea()
            }

            VStack(spacing: 16) {
                // Header
                HStack(spacing: 10) {
                    Image(systemName: "music.note")
                        .font(.title2)
                        .foregroundColor(primary)
                    Text("Touch Music ID")
                        .font(.title2.weight(.bold))
                        .foregroundColor(textHigh)
                }
                .padding(.top, 8)

                // Album artwork
                ZStack {
                    RoundedRectangle(cornerRadius: 20)
                        .fill(primary.opacity(0.15))
                        .frame(width: 220, height: 220)
                    if let art = shazam.artwork {
                        Image(uiImage: art)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 220, height: 220)
                            .clipShape(RoundedRectangle(cornerRadius: 20))
                            .shadow(color: primary.opacity(0.3), radius: 16)
                    } else {
                        Image(systemName: "music.note")
                            .font(.system(size: 72))
                            .foregroundColor(textMedium)
                    }
                }

                VStack(spacing: 4) {
                    Text(shazam.title)
                        .font(.title3.weight(.semibold))
                        .foregroundColor(textHigh)
                        .multilineTextAlignment(.center)
                    Text(shazam.artist)
                        .font(.body)
                        .foregroundColor(textMedium)
                        .multilineTextAlignment(.center)
                }

                if let error = shazam.errorMessage {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(Color(red: 1, green: 0.42, blue: 0.42))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                // Listen button
                Button(action: {
                    Task { await shazam.startListening() }
                }) {
                    Label(shazam.isListening ? "Listening…" : "Identify Song", systemImage: shazam.isListening ? "waveform" : "mic")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .tint(primary)
                .disabled(shazam.isListening)
                .padding(.horizontal, 24)

                // Service links when we have a match
                if shazam.title != "Touch Music ID" && shazam.artist != "Tap to identify" {
                    HStack(spacing: 12) {
                        LinkButton(title: "Apple Music", url: shazam.appleMusicURL ?? appleMusicSearchURL)
                        LinkButton(title: "Spotify", url: spotifySearchURL)
                        LinkButton(title: "YouTube", url: youtubeSearchURL)
                    }
                    .padding(.horizontal)
                }

                // Permanent result tabs
                WikiTabsView(selectedTab: $selectedTab, title: shazam.title, artist: shazam.artist)
                    .frame(maxHeight: 220)
            }
            .padding(.vertical, 12)
        }
        .preferredColorScheme(.dark)
    }

    private var appleMusicSearchURL: URL? {
        let q = "\(shazam.title) \(shazam.artist)".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        return URL(string: "https://music.apple.com/search?term=\(q)")
    }
    private var spotifySearchURL: URL? {
        let q = "\(shazam.title) \(shazam.artist)".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        return URL(string: "https://open.spotify.com/search/\(q)")
    }
    private var youtubeSearchURL: URL? {
        let q = "\(shazam.title) \(shazam.artist)".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        return URL(string: "https://www.youtube.com/results?search_query=\(q)")
    }
}

struct LinkButton: View {
    let title: String
    let url: URL?
    var body: some View {
        if let url {
            Link(destination: url) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.white.opacity(0.12))
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .foregroundColor(.white)
        }
    }
}

#Preview {
    ContentView()
}
