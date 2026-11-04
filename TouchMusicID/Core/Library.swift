import Foundation

/// History + favorites, stored as JSON in Application Support. Local to the device: no account, no sync, no analytics.
/// Favorites are independent of history, so clearing history keeps them.
public final class LibraryStore {
    private struct FavoriteEntry: Codable { var song: Song; var source: RecognitionSource; var addedAt: Date }
    private struct HistoryEntry: Codable { var id: UUID; var song: Song; var source: RecognitionSource; var at: Date }
    private struct Payload: Codable { var history: [HistoryEntry] = []; var favorites: [FavoriteEntry] = [] }

    private let url: URL?
    private var data = Payload()
    private let clock: () -> Date
    private let lock = NSLock()

    /// Pass `nil` for an in-memory store (tests, previews).
    public init(url: URL?, clock: @escaping () -> Date = Date.init) {
        self.url = url
        self.clock = clock
        if let url, let d = try? Data(contentsOf: url), let p = try? JSONDecoder().decode(Payload.self, from: d) { data = p }
    }

    private func save() {
        guard let url else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let d = try? JSONEncoder().encode(data) { try? d.write(to: url, options: .atomic) }
    }

    /// Newest first.
    public func history() -> [LibrarySong] {
        lock.lock(); defer { lock.unlock() }
        return data.history.sorted { $0.at > $1.at }.map { LibrarySong(song: $0.song, source: $0.source, timestamp: $0.at, historyId: $0.id) }
    }

    public func favorites() -> [LibrarySong] {
        lock.lock(); defer { lock.unlock() }
        return data.favorites.sorted { $0.addedAt > $1.addedAt }.map { LibrarySong(song: $0.song, source: $0.source, timestamp: $0.addedAt) }
    }

    @discardableResult
    public func record(_ song: Song, source: RecognitionSource) -> UUID {
        lock.lock(); defer { lock.unlock() }
        let e = HistoryEntry(id: UUID(), song: song, source: source, at: clock())
        data.history.append(e)
        save()
        return e.id
    }

    /// Looks a song up by key in favorites first, then the newest history entry.
    public func find(_ key: String) -> LibrarySong? {
        lock.lock(); defer { lock.unlock() }
        if let f = data.favorites.first(where: { $0.song.key == key }) { return LibrarySong(song: f.song, source: f.source, timestamp: f.addedAt) }
        if let h = data.history.filter({ $0.song.key == key }).max(by: { $0.at < $1.at }) {
            return LibrarySong(song: h.song, source: h.source, timestamp: h.at, historyId: h.id)
        }
        return nil
    }

    public func isFavorite(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return data.favorites.contains { $0.song.key == key }
    }

    public func setFavorite(_ item: LibrarySong, _ favorite: Bool) {
        lock.lock(); defer { lock.unlock() }
        data.favorites.removeAll { $0.song.key == item.song.key }
        if favorite { data.favorites.append(FavoriteEntry(song: item.song, source: item.source, addedAt: clock())) }
        save()
    }

    public func deleteHistory(_ id: UUID) { lock.lock(); defer { lock.unlock() }; data.history.removeAll { $0.id == id }; save() }
    public func clearHistory() { lock.lock(); defer { lock.unlock() }; data.history.removeAll(); save() }
    public func clearDemoData() {
        lock.lock(); defer { lock.unlock() }
        data.history.removeAll { $0.source == .demo }
        data.favorites.removeAll { $0.source == .demo }
        save()
    }
}
