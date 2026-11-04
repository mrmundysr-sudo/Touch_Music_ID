import Foundation

/// Minimal models for on-demand lyrics. No permanent lyrics library.
public struct TrackQuery: Equatable {
    public var title: String
    public var artist: String
    public var album: String?
    public var durationSec: Double?
    public init(title: String, artist: String, album: String? = nil, durationSec: Double? = nil) {
        self.title = title; self.artist = artist; self.album = album; self.durationSec = durationSec
    }
}

public struct LrcLine: Equatable { public let timeMs: Int; public let text: String }

public struct LyricsResult: Equatable {
    public enum Origin: Equatable { case online, local }
    public var plain: String?
    public var syncedLines: [LrcLine]
    public var source: Origin
    public var instrumental: Bool
    public init(plain: String?, syncedLines: [LrcLine], source: Origin, instrumental: Bool = false) {
        self.plain = plain; self.syncedLines = syncedLines; self.source = source; self.instrumental = instrumental
    }
    public var displayText: String { plain ?? syncedLines.map { $0.text }.joined(separator: "\n") }
}

public enum LyricsUiState: Equatable {
    case idle
    case loading
    case ready(LyricsResult)
    case unavailable(String)
    /// Online lyrics are off (or not allowed in this build) and nothing was imported locally.
    case disabled
}

public enum LrcParser {
    private static let line = try! NSRegularExpression(pattern: #"\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]\s*(.*)"#)

    public static func parse(_ lrc: String) -> [LrcLine] {
        var out: [LrcLine] = []
        for raw in lrc.components(separatedBy: .newlines) {
            let range = NSRange(raw.startIndex..., in: raw)
            guard let m = line.firstMatch(in: raw, range: range) else { continue }
            func g(_ i: Int) -> String {
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : (Range(r, in: raw).map { String(raw[$0]) } ?? "")
            }
            guard let min = Int(g(1)), let sec = Int(g(2)) else { continue }
            let fracText = g(3)
            let frac = fracText.isEmpty ? 0 : Int(String((fracText + "000").prefix(3))) ?? 0
            let text = g(4).trimmingCharacters(in: .whitespaces)
            if text.isEmpty { continue }
            out.append(LrcLine(timeMs: min * 60_000 + sec * 1_000 + frac, text: text))
        }
        return out.sorted { $0.timeMs < $1.timeMs }
    }
}

/// Holds user-imported LRC content for the current session only. No permanent downloaded lyrics library.
public final class LocalLrcStore {
    private var byKey: [String: LyricsResult] = [:]
    private let lock = NSLock()
    public init() {}

    public func put(_ songKey: String, lrcText: String) {
        let lines = LrcParser.parse(lrcText)
        // Plain text with no timestamps is still useful: keep it as plain lyrics.
        let plain = lines.isEmpty ? lrcText.trimmingCharacters(in: .whitespacesAndNewlines) : lines.map { $0.text }.joined(separator: "\n")
        lock.lock(); defer { lock.unlock() }
        byKey[songKey] = LyricsResult(plain: plain, syncedLines: lines, source: .local)
    }
    public func get(_ songKey: String) -> LyricsResult? { lock.lock(); defer { lock.unlock() }; return byKey[songKey] }
    public func clear() { lock.lock(); defer { lock.unlock() }; byKey.removeAll() }
}

/// On-demand LRCLIB client. Sequential requests only, identifies the app, no permanent storage.
/// Used only when the user has enabled online lyrics and the build allows it.
public final class LrclibClient {
    private let http: HTTPFetcher
    private let base: String
    private let pause: (Int) async -> Void

    public init(http: HTTPFetcher, base: String = "https://lrclib.net/api",
                pause: @escaping (Int) async -> Void = { ms in try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }) {
        self.http = http; self.base = base; self.pause = pause
    }

    public func lookup(_ q: TrackQuery) async throws -> LyricsResult? {
        let title = q.title.trimmingCharacters(in: .whitespaces)
        let primaryArtist = Self.primaryArtist(q.artist)
        if let d = q.durationSec, let album = q.album, !album.isEmpty {
            if let r = try await getExact(title, primaryArtist, album, d) { return r }
            await pause(300)
        }
        if let r = try await search("track_name=\(enc(title))&artist_name=\(enc(primaryArtist))").first { return r }
        await pause(300)
        return try await search("q=\(enc("\(primaryArtist) \(title)"))").first
    }

    static func primaryArtist(_ artist: String) -> String {
        var cut = artist
        for sep in [",", "&", " feat.", " ft.", " feat ", " ft "] {
            if let r = cut.range(of: sep, options: .caseInsensitive) { cut = String(cut[..<r.lowerBound]) }
        }
        let t = cut.trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? artist : t
    }

    private func getExact(_ title: String, _ artist: String, _ album: String, _ duration: Double) async throws -> LyricsResult? {
        let url = "\(base)/get?track_name=\(enc(title))&artist_name=\(enc(artist))&album_name=\(enc(album))&duration=\(Int(duration))"
        guard let u = URL(string: url) else { return nil }
        let r = try await http.get(u)
        guard (200...299).contains(r.code) else { return nil }
        guard let o = (try? JSONSerialization.jsonObject(with: r.body)) as? [String: Any] else { return nil }
        return Self.parseObject(o)
    }

    private func search(_ query: String) async throws -> [LyricsResult] {
        guard let u = URL(string: "\(base)/search?\(query)") else { return [] }
        let r = try await http.get(u)
        guard (200...299).contains(r.code) else { return [] }
        guard let arr = (try? JSONSerialization.jsonObject(with: r.body)) as? [[String: Any]] else { return [] }
        return arr.compactMap(Self.parseObject)
    }

    static func parseObject(_ o: [String: Any]) -> LyricsResult? {
        let plain = (o["plainLyrics"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let synced = (o["syncedLyrics"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let instrumental = (o["instrumental"] as? Bool) ?? false
        if plain == nil && synced == nil && !instrumental { return nil }
        return LyricsResult(plain: plain, syncedLines: synced.map(LrcParser.parse) ?? [], source: .online, instrumental: instrumental)
    }

    private func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

/// Coordinates locally imported LRC (always available) and optional on-demand online lyrics.
/// Online is allowed only when `onlineAllowedInThisBuild` is true (debug builds), mirroring Android: the public release keeps it
/// disabled pending licensing review. Independently removable: nothing else depends on it.
public final class LyricsRepository {
    private let local: LocalLrcStore
    private let client: LrclibClient
    public let onlineAllowedInThisBuild: Bool
    private let onlineEnabled: () -> Bool

    public init(local: LocalLrcStore, http: HTTPFetcher, onlineAllowedInThisBuild: Bool, onlineEnabled: @escaping () -> Bool) {
        self.local = local
        self.client = LrclibClient(http: http)
        self.onlineAllowedInThisBuild = onlineAllowedInThisBuild
        self.onlineEnabled = onlineEnabled
    }

    public func load(songKey: String, query: TrackQuery) async -> LyricsUiState {
        if let l = local.get(songKey) { return .ready(l) }
        if !onlineAllowedInThisBuild || !onlineEnabled() { return .disabled }
        do {
            if let r = try await client.lookup(query) { return .ready(r) }
            return .unavailable("No lyrics found for this track")
        } catch {
            return .unavailable("Could not load lyrics. Check your connection and try again.")
        }
    }

    public func importLocal(songKey: String, lrcText: String) { local.put(songKey, lrcText: lrcText) }
}
