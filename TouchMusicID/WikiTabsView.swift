import SwiftUI

struct WikiTabsView: View {
    @Binding var selectedTab: Int
    let title: String
    let artist: String

    private let tabs = ["Artist Wiki", "Song Wiki", "Lyrics"]

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedTab) {
                ForEach(0..<tabs.count, id: \.self) { i in
                    Text(tabs[i]).tag(i)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    switch selectedTab {
                    case 0:
                        Text("Artist Wiki")
                            .font(.headline)
                        Text("Information about \(artist) will appear here once identification completes.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    case 1:
                        Text("Song Wiki")
                            .font(.headline)
                        Text("Details about \"\(title)\" will appear here.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    default:
                        Text("Lyrics")
                            .font(.headline)
                        Text("Synchronized or plain lyrics will load here after a successful match.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
            .background(Color.white.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
        }
    }
}
