import Foundation

public protocol KnowledgeCache {
    func get(_ key: String) async -> (payload: Data, fetchedAt: Date)?
    func put(_ key: String, payload: Data, fetchedAt: Date) async
    func clear() async
}

public actor InMemoryKnowledgeCache: KnowledgeCache {
    private var map: [String: (Data, Date)] = [:]
    public init() {}
    public func get(_ key: String) -> (payload: Data, fetchedAt: Date)? { map[key].map { ($0.0, $0.1) } }
    public func put(_ key: String, payload: Data, fetchedAt: Date) { map[key] = (payload, fetchedAt) }
    public func clear() { map.removeAll() }
}

/// File-backed cache (Application Support). Holds only public Wikipedia/Wikidata facts and the user's ambiguity choices.
public actor FileKnowledgeCache: KnowledgeCache {
    private struct Entry: Codable { var payload: Data; var fetchedAt: Date }
    private let url: URL
    private var map: [String: Entry]?

    public init(url: URL) { self.url = url }

    private func load() -> [String: Entry] {
        if let m = map { return m }
        let loaded = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode([String: Entry].self, from: $0) } ?? [:]
        map = loaded
        return loaded
    }

    private func save(_ m: [String: Entry]) {
        map = m
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let d = try? JSONEncoder().encode(m) { try? d.write(to: url, options: .atomic) }
    }

    public func get(_ key: String) -> (payload: Data, fetchedAt: Date)? { load()[key].map { ($0.payload, $0.fetchedAt) } }
    public func put(_ key: String, payload: Data, fetchedAt: Date) {
        var m = load(); m[key] = Entry(payload: payload, fetchedAt: fetchedAt); save(m)
    }
    public func clear() { save([:]) }
}

/// Cache-first knowledge lookup with graceful offline behaviour.
///  - Fresh cache hit: no network.
///  - Otherwise fetch live and store.
///  - Network failure with an older cached copy: return the stale copy, flagged so the UI can say so.
///  - A failed lookup (in whole or in part) is NEVER cached: only completed answers are stored.
public final class KnowledgeRepository {
    public static let hour: TimeInterval = 3600
    public static let day: TimeInterval = 24 * hour

    private let resolver: KnowledgeResolver
    private let cache: KnowledgeCache
    private let clock: () -> Date
    private let freshSeconds: TimeInterval
    /// How long a checked notFound / ambiguous result is reused. Short, so a wrong "no match" heals itself within the hour.
    private let negativeFreshSeconds: TimeInterval

    public init(resolver: KnowledgeResolver, cache: KnowledgeCache, clock: @escaping () -> Date = Date.init,
                freshSeconds: TimeInterval = 7 * KnowledgeRepository.day,
                negativeFreshSeconds: TimeInterval = KnowledgeRepository.hour) {
        self.resolver = resolver
        self.cache = cache
        self.clock = clock
        self.freshSeconds = freshSeconds
        self.negativeFreshSeconds = negativeFreshSeconds
    }

    public func load(_ song: Song, forceRefresh: Bool = false) async -> KnowledgeResult {
        let key = cacheKey(song)
        var cached: (bundle: KnowledgeBundle, at: Date)?
        if let hit = await cache.get(key), let b = try? JSONDecoder().decode(KnowledgeBundle.self, from: hit.payload) {
            cached = (b, hit.fetchedAt)
        }
        if let c = cached, !forceRefresh {
            let both = c.bundle.artist.status == .verified && c.bundle.song.status == .verified
            let ttl = both ? freshSeconds : negativeFreshSeconds
            if clock().timeIntervalSince(c.at) < ttl { return .found(c.bundle, .cacheFresh) }
        }
        return await fetch(song, key: key, stale: cached?.bundle, chosen: await chosenArtistQid(song))
    }

    /// The user resolved an ambiguous artist. The choice is remembered for this artist name.
    public func chooseArtist(_ song: Song, qid: String) async -> KnowledgeResult {
        await cache.put(choiceKey(song), payload: Data(qid.utf8), fetchedAt: clock())
        return await fetch(song, key: cacheKey(song), stale: nil, chosen: qid)
    }

    public func clearCache() async { await cache.clear() }

    private func chosenArtistQid(_ song: Song) async -> String? {
        guard let hit = await cache.get(choiceKey(song)) else { return nil }
        return String(data: hit.payload, encoding: .utf8)
    }

    private func fetch(_ song: Song, key: String, stale: KnowledgeBundle?, chosen: String?) async -> KnowledgeResult {
        do {
            let (artist, songLookup) = try await resolver.resolve(song, chosenArtistQid: chosen)
            let bundle = KnowledgeBundle(artist: artist, song: songLookup, fetchedAt: clock())
            if artist.status == .lookupFailed || songLookup.status == .lookupFailed {
                // Partial failure: show what we have, keep the failed card retryable, and cache nothing.
                if let s = stale { return .found(s, .cacheStaleOffline) }
                return .found(bundle, .live)
            }
            if let data = try? JSONEncoder().encode(bundle) { await cache.put(key, payload: data, fetchedAt: bundle.fetchedAt) }
            return .found(bundle, .live)
        } catch is CancellationError {
            return .failed(.unknown)
        } catch {
            if let s = stale { return .found(s, .cacheStaleOffline) }
            return .failed(failureReason(for: error))
        }
    }

    // Version bump (k2 -> k3) discards every earlier cached result.
    private func cacheKey(_ song: Song) -> String { "k3|" + song.key }
    private func choiceKey(_ song: Song) -> String { "choice|" + normalizeText(song.artist) }
}
