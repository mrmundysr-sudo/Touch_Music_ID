import SwiftUI

struct HistoryView: View {
    @EnvironmentObject private var model: AppModel
    @State private var confirmClear = false

    var body: some View {
        LibraryList(
            items: model.history,
            emptyTitle: "Nothing identified yet",
            emptyMessage: "Songs you identify appear here, saved only on this phone.",
            emptyIcon: "clock.arrow.circlepath",
            onDelete: { item in if let id = item.historyId { model.deleteHistory(id) } }
        )
        .navigationTitle("History")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if !model.history.isEmpty { Button("Clear all") { confirmClear = true } }
            }
        }
        .alert("Clear history?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) { model.clearHistory() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes every identification from this phone. Favorites are kept.")
        }
        .tmiScreen()
    }
}

struct FavoritesView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        LibraryList(
            items: model.favorites,
            emptyTitle: "No favorites yet",
            emptyMessage: "Tap the heart on a song to keep it here, even if you clear your history.",
            emptyIcon: "heart",
            onDelete: { item in model.toggleFavorite(item) }
        )
        .navigationTitle("Favorites")
        .tmiScreen()
    }
}

private struct LibraryList: View {
    let items: [LibrarySong]
    let emptyTitle: String
    let emptyMessage: String
    let emptyIcon: String
    let onDelete: (LibrarySong) -> Void

    var body: some View {
        if items.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: emptyIcon).font(.system(size: 44)).foregroundColor(Tmi.textLow)
                Text(emptyTitle).font(.headline).foregroundColor(Tmi.textHigh)
                Text(emptyMessage).font(.subheadline).foregroundColor(Tmi.textMedium).multilineTextAlignment(.center)
            }
            .padding(Tmi.screenPadding)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(items) { item in
                    NavigationLink(value: item.song.key) { SongTile(item: item) }
                        .listRowBackground(Tmi.surface.opacity(0.72))
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) { onDelete(item) } label: { Label("Delete", systemImage: "trash") }
                        }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }
}

private struct SongTile: View {
    let item: LibrarySong
    var body: some View {
        HStack(spacing: 12) {
            AlbumArtView(url: item.song.artworkURL, size: 56, radius: 12)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.song.title).font(.headline).foregroundColor(Tmi.textHigh).lineLimit(1)
                Text(item.song.artist).font(.subheadline).foregroundColor(Tmi.textMedium).lineLimit(1)
                HStack(spacing: 8) {
                    Text(item.timestamp.formatted(date: .abbreviated, time: .omitted)).font(.caption).foregroundColor(Tmi.textLow)
                    if item.source == .demo { Pill(text: "Demo", color: Tmi.warm) }
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
