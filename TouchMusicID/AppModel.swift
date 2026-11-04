import Foundation
import SwiftUI
import UIKit

enum KnowledgeUi: Equatable {
    case loading
    case ready(KnowledgeBundle, Freshness)
    case error(FailureReason)
}

struct ResultUi: Equatable {
    var item: LibrarySong
    var knowledge: KnowledgeUi
    var lyrics: LyricsUiState = .idle
}

/// App-wide state: settings, library, the open result, and the recognition service. Mirrors Android's MainViewModel.
@MainActor
final class AppModel: ObservableObject {
    private enum Keys {
        static let demo = "demo_mode"
        static let onlineLyrics = "enable_online_lyrics"
    }

    // Services
    let recognizer = RecognitionService()
    private let library: LibraryStore
    private let knowledge: KnowledgeRepository
    private let lyricsRepo: LyricsRepository
    private let defaults: UserDefaults

    // Settings
    @Published var demoMode: Bool { didSet { defaults.set(demoMode, forKey: Keys.demo) } }
    @Published var enableOnlineLyrics: Bool { didSet { defaults.set(enableOnlineLyrics, forKey: Keys.onlineLyrics) } }

    /// Online lyrics are allowed only in debug builds, matching Android. The public/TestFlight build keeps them off pending licensing review.
    var onlineLyricsAllowedInThisBuild: Bool { lyricsRepo.onlineAllowedInThisBuild }

    // Library
    @Published private(set) var history: [LibrarySong] = []
    @Published private(set) var favorites: [LibrarySong] = []

    // Result
    @Published private(set) var result: ResultUi?
    @Published private(set) var artwork: UIImage?
    @Published private(set) var accents: ArtworkAccents = .default
    @Published var listenPath: [String] = []

    private var knowledgeTask: Task<Void, Never>?
    private var lyricsTask: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = support.appendingPathComponent("TouchMusicID", isDirectory: true)
        library = LibraryStore(url: dir.appendingPathComponent("library.json"))

        let http = URLSessionFetcher()
        knowledge = KnowledgeRepository(
            resolver: KnowledgeResolver(api: WikiApi(http: http)),
            cache: FileKnowledgeCache(url: dir.appendingPathComponent("knowledge-cache.json")))

        let demo = defaults.bool(forKey: Keys.demo)
        let online = defaults.bool(forKey: Keys.onlineLyrics)
        _demoMode = Published(initialValue: demo)
        _enableOnlineLyrics = Published(initialValue: online)

        let allowOnline = Self.onlineLyricsAllowed()
        let defaultsRef = defaults
        lyricsRepo = LyricsRepository(local: LocalLrcStore(), http: http, onlineAllowedInThisBuild: allowOnline,
                                      onlineEnabled: { defaultsRef.bool(forKey: Keys.onlineLyrics) })

        recognizer.onMatch = { [weak self] song, source in self?.matched(song, source) }
        refreshLibrary()
    }

    /// Online lyrics (LRCLIB) are allowed in debug builds and in TestFlight beta builds, so they can be tried on a phone.
    /// A public App Store build keeps them off pending the licensing review (same gate as Android's release build).
    private static func onlineLyricsAllowed() -> Bool {
        #if DEBUG
        return true
        #else
        // TestFlight installs carry a sandbox receipt; App Store installs carry a production receipt.
        return Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
        #endif
    }

    // MARK: Listening

    func onListenTapped() { recognizer.tapped(demo: demoMode) }
    func cancelListening() { recognizer.cancel() }
    func dismissListenMessage() { recognizer.reset() }

    private func matched(_ song: Song, _ source: RecognitionSource) {
        library.record(song, source: source)
        refreshLibrary()
        listenPath.append(song.key)
    }

    // MARK: Result / knowledge

    func openSong(_ key: String) {
        knowledgeTask?.cancel()
        guard let item = library.find(key) else { result = nil; return }
        result = ResultUi(item: item, knowledge: .loading, lyrics: .idle)
        loadArtwork(for: item.song)
        knowledgeTask = Task { [weak self] in
            guard let self else { return }
            let r = await self.knowledge.load(item.song)
            self.applyKnowledge(item, r)
        }
    }

    func closeResult() {
        knowledgeTask?.cancel(); lyricsTask?.cancel(); artworkTask?.cancel()
        result = nil
        artwork = nil
        accents = .default
    }

    func retryKnowledge() {
        guard let current = result else { return }
        knowledgeTask?.cancel()
        result?.knowledge = .loading
        knowledgeTask = Task { [weak self] in
            guard let self else { return }
            let r = await self.knowledge.load(current.item.song, forceRefresh: true)
            self.applyKnowledge(current.item, r)
        }
    }

    func chooseArtist(_ qid: String) {
        guard let current = result else { return }
        knowledgeTask?.cancel()
        result?.knowledge = .loading
        knowledgeTask = Task { [weak self] in
            guard let self else { return }
            let r = await self.knowledge.chooseArtist(current.item.song, qid: qid)
            self.applyKnowledge(current.item, r)
        }
    }

    private func applyKnowledge(_ item: LibrarySong, _ r: KnowledgeResult) {
        guard result?.item.song.key == item.song.key else { return }
        switch r {
        case .found(let bundle, let freshness): result?.knowledge = .ready(bundle, freshness)
        case .failed(let reason): result?.knowledge = .error(reason)
        }
    }

    private func loadArtwork(for song: Song) {
        artworkTask?.cancel()
        artwork = nil
        accents = .default
        artworkTask = Task { [weak self] in
            let img = await ArtworkLoader.shared.image(for: song.artworkURL)
            guard !Task.isCancelled, let self, self.result?.item.song.key == song.key else { return }
            self.artwork = img
            withAnimation(.easeInOut(duration: 0.28)) { self.accents = ArtworkAccents.extract(from: img) }
        }
    }

    // MARK: Lyrics

    func loadLyrics() {
        guard let current = result else { return }
        lyricsTask?.cancel()
        result?.lyrics = .loading
        lyricsTask = Task { [weak self] in
            guard let self else { return }
            let s = current.item.song
            let state = await self.lyricsRepo.load(songKey: s.key, query: TrackQuery(title: s.title, artist: s.artist, album: s.album))
            if self.result?.item.song.key == s.key { self.result?.lyrics = state }
        }
    }

    func importLocalLrc(_ text: String) {
        guard let current = result else { return }
        lyricsRepo.importLocal(songKey: current.item.song.key, lrcText: text)
        loadLyrics()
    }

    // MARK: Library

    private func refreshLibrary() {
        history = library.history()
        favorites = library.favorites()
    }

    func isFavorite(_ key: String) -> Bool { favorites.contains { $0.song.key == key } }

    func toggleFavorite(_ item: LibrarySong) {
        library.setFavorite(item, !isFavorite(item.song.key))
        refreshLibrary()
    }

    func deleteHistory(_ id: UUID) { library.deleteHistory(id); refreshLibrary() }
    func clearHistory() { library.clearHistory(); refreshLibrary() }
    func clearDemoData() { library.clearDemoData(); refreshLibrary() }
    func clearKnowledgeCache() { Task { await knowledge.clearCache() } }
}
