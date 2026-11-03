import SwiftUI

struct WikiTabsView: View {
    @Binding var selectedTab: Int
    let title: String
    let artist: String

    private let tabs = ["Artist Wiki", "Song Wiki", "Lyrics"]
    private let textHigh = Color(red: 0.953, green: 0.965, blue: 1.0)
    private let textMedium = Color(red: 0.706, green: 0.745, blue: 0.847)

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedTab) {
                ForEach(0..<tabs.count, id: \.self) { i in
                    Text(tabs[i]).tag(i)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    switch selectedTab {
                    case 0:
                        Text("Artist Wiki")
                            .font(.headline)
                            .foregroundColor(textHigh)
                        Text("Information about \(artist.isEmpty ? "this artist" : artist) will appear here once identification completes and sources are available.")
                            .font(.subheadline)
                            .foregroundColor(textMedium)
                        Text("Wikipedia and other references are loaded when a match is found and the network is available.")
                            .font(.caption)
                            .foregroundColor(textMedium.opacity(0.8))
                    case 1:
                        Text("Song Wiki")
                            .font(.headline)
                            .foregroundColor(textHigh)
                        Text("Details about \"\(title.isEmpty ? "this song" : title)\" will appear here after a successful identification.")
                            .font(.subheadline)
                            .foregroundColor(textMedium)
                        Text("Song facts and related information show when sources respond.")
                            .font(.caption)
                            .foregroundColor(textMedium.opacity(0.8))
                    default:
                        Text("Lyrics")
                            .font(.headline)
                            .foregroundColor(textHigh)
                        Text("Lyrics for \"\(title.isEmpty ? "this song" : title)\" will load here after a successful match when available.")
                            .font(.subheadline)
                            .foregroundColor(textMedium)
                        Text("Synchronized or plain lyrics appear when the service can supply them.")
                            .font(.caption)
                            .foregroundColor(textMedium.opacity(0.8))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .background(Color.white.opacity(0.06))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16)
        }
    }
}
