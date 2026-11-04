import Foundation

/// Minimal models for on-demand lyrics. No permanent lyrics library.
public struct TrackQuery: Equatable {
    public var title: String
    public var artist: String
    public var album: String?
    public var durationSec: Double?
    /// Optional identifiers from the recognition result, used only to look up duration/album so the right recording is chosen.
    public var appleMusicId: String?
    public var isrc: String?
    public init(title: String, artist: String, album: String? = nil, durationSec: Double? = nil,
                appleMusicId: String? = nil, isrc: String? = nil) {
        self.title = title; self.artist = artist; self.album = album; self.durationSec = durationSec
        self.appleMusicId = appleMusicId; self.isrc = isrc
    }
}

public struct LrcLine: Equatable {
    public let timeMs: Int
    public let text: String
    public init(timeMs: Int, text: String) { self.timeMs = timeMs; self.text = text }
}

public struct LyricsResult: Equatable {
    public enum Origin: Equatable { case online, local }
    public var plain: String?
    public var syncedLines: [LrcLine]
    public var source: Origin
    public var instrumental: Bool
    /// Track length when known (used to sanity-check synchronized timestamps).
    public var durationMs: Int?
    /// Human-readable note about which record was chosen and why synchronization was or wasn't kept.
    public var info: String

    public init(plain: String?, syncedLines: [LrcLine], source: Origin, instrumental: Bool = false,
                durationMs: Int? = nil, info: String = "") {
        self.plain = plain; self.syncedLines = syncedLines; self.source = source; self.instrumental = instrumental
        self.durationMs = durationMs; self.info = info
    }
    public var displayText: String { plain ?? syncedLines.map { $0.text }.joined(separator: "\n") }
    /// True when there are timestamped lines that can drive Follow along.
    public var canFollow: Bool { !syncedLines.isEmpty && !instrumental }
}

public enum LyricsUiState: Equatable {
    case idle
    case loading
    case ready(LyricsResult)
    case unavailable(String)
    /// Online lyrics are off (or not allowed in this build) and nothing was imported locally.
    case disabled
}

public struct LrcDoc: Equatable {
    public var lines: [LrcLine]
    public var offsetMs: Int = 0
    public var meta: [String: String] = [:]
    public var isEmpty: Bool { lines.isEmpty }
}

/*
 * Approach (parse every timestamp on a line, apply an [offset:] tag, sort because repeated choruses can be out of order,
 * binary-search the active line) follows the open-source LiveLyrics project (https://github.com/Nurzhigit-dev/LiveLyrics, MIT),
 * as ported on Android. Independent Swift implementation.
 */
public enum LrcParser {
    private static let timeTag = try! NSRegularExpression(pattern: #"\[(\d{1,4}):([0-5]?\d)(?:[.:](\d{1,3}))?\]"#)
    private static let metaTag = try! NSRegularExpression(pattern: #"^\[([A-Za-z#]+):([^\]]*)\]\s*$"#)
    private static let wordTag = try! NSRegularExpression(pattern: #"<\d{1,4}:[0-5]?\d(?:[.:]\d{1,3})?>"#)

    /// Never fails: empty or malformed input yields an empty document.
    public static func parseDoc(_ raw: String?) -> LrcDoc {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return LrcDoc(lines: []) }
        var lines: [LrcLine] = []
        var meta: [String: String] = [:]
        var offset = 0
        for row in raw.components(separatedBy: .newlines) {
            let s = row.trimmingCharacters(in: .whitespaces)
            if s.isEmpty { continue }
            let ns = s as NSString
            let full = NSRange(location: 0, length: ns.length)
            let tags = timeTag.matches(in: s, range: full)
            if tags.isEmpty {
                if let m = metaTag.firstMatch(in: s, range: full) {
                    let key = ns.substring(with: m.range(at: 1)).lowercased()
                    let value = ns.substring(with: m.range(at: 2)).trimmingCharacters(in: .whitespaces)
                    if key == "offset" { offset = Int(value) ?? 0 } else { meta[key] = value }
                }
                continue
            }
            let last = tags[tags.count - 1]
            let rest = ns.substring(from: last.range.location + last.range.length)
            let text = wordTag.stringByReplacingMatches(in: rest, range: NSRange(location: 0, length: (rest as NSString).length), withTemplate: "")
                .trimmingCharacters(in: .whitespaces)
            for t in tags {
                guard let minutes = Int(ns.substring(with: t.range(at: 1))), let seconds = Int(ns.substring(with: t.range(at: 2))) else { continue }
                var ms = 0
                if t.range(at: 3).location != NSNotFound {
                    ms = Int(String((ns.substring(with: t.range(at: 3)) + "000").prefix(3))) ?? 0
                }
                lines.append(LrcLine(timeMs: minutes * 60_000 + seconds * 1000 + ms, text: text))
            }
        }
        // A positive [offset:] makes lyrics appear earlier (same convention as LiveLyrics). Stable sort: ties keep file order.
        let shifted = lines.map { LrcLine(timeMs: max(0, $0.timeMs - offset), text: $0.text) }
        let sorted = shifted.enumerated().sorted { l, r in
            l.element.timeMs != r.element.timeMs ? l.element.timeMs < r.element.timeMs : l.offset < r.offset
        }.map { $0.element }
        return LrcDoc(lines: sorted, offsetMs: offset, meta: meta)
    }

    /// Timed lines with text (blank gap lines dropped), offset applied.
    public static func parse(_ lrc: String) -> [LrcLine] {
        parseDoc(lrc).lines.filter { !$0.text.isEmpty }
    }

    /// Index of the last line whose start is at or before `posMs`; -1 before the first line.
    public static func findActiveIndex(_ lines: [LrcLine], posMs: Int) -> Int {
        var lo = 0, hi = lines.count - 1, found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if lines[mid].timeMs <= posMs { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return found
    }
}

/// Sanity checks that decide whether synchronized lyrics are trustworthy enough to follow, or a plain sheet should be shown.
public enum LyricsSanity {
    /// False when the timestamps cannot belong to this recording: no lines, or the last line starts well after the track ends.
    /// Unknown duration gives the benefit of the doubt.
    public static func syncedLooksPlausible(_ doc: LrcDoc, durationMs: Int?) -> Bool {
        guard let last = doc.lines.last else { return false }
        guard let d = durationMs, d > 0 else { return true }
        return last.timeMs <= d + 5_000
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
        byKey[songKey] = LyricsResult(plain: plain, syncedLines: lines, source: .local,
                                      info: lines.isEmpty ? "Imported text has no timestamps, so it can't follow along." : "Imported LRC file")
    }
    public func get(_ songKey: String) -> LyricsResult? { lock.lock(); defer { lock.unlock() }; return byKey[songKey] }
    public func clear() { lock.lock(); defer { lock.unlock() }; byKey.removeAll() }
}

public enum LyricsLookup {
    case found(best: ScoredCandidate, ranked: [ScoredCandidate], requests: Int)
    case notFound(ranked: [ScoredCandidate], requests: Int)
    case failed(kind: String, message: String)
}

/// LRCLIB (https://lrclib.net) client. Requests are strictly sequential, identified by User-Agent, and spaced by the injected
/// fetcher. Used only when the user has enabled online lyrics and the build allows it.
/// Round 1: exact /get (when album and duration are known), then /search by fields, then /search by free text.
/// Round 2 only when nothing good (score >= 0.8 with synchronized lyrics) was found.
public final class LrclibClient {
    private let http: HTTPFetcher
    private let base: String
    private let pause: (Int) async -> Void

    public init(http: HTTPFetcher, base: String = "https://lrclib.net/api",
                pause: @escaping (Int) async -> Void = { ms in try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }) {
        self.http = http; self.base = base; self.pause = pause
    }

    public func lookup(_ q: TrackQuery) async -> LyricsLookup {
        var gathered: [LrclibRecord] = []
        var requests = 0
        var failures = 0
        var lastFailure: LabApiFailure?

        func attempt(_ block: () async throws -> [LrclibRecord]) async {
            requests += 1
            do { gathered += try await block() }
            catch let f as LabApiFailure { failures += 1; lastFailure = f }
            catch { failures += 1; lastFailure = LabApiFailure(kind: "NETWORK", message: error.localizedDescription) }
        }

        let cleaned = LyricsMatcher.cleanTitle(q.title)
        let primary = LyricsMatcher.primaryArtist(q.artist)

        if let d = q.durationSec, let album = q.album, !album.isEmpty {
            await attempt { [self] in
                if let r = try await getExact(cleaned, primary, album, d) { return [r] }
                return []
            }
        }
        await attempt { [self] in try await search("track_name=\(enc(cleaned))&artist_name=\(enc(primary))") }
        for a in LyricsMatcher.searchArtists(q.artist) {
            await attempt { [self] in try await search("q=\(enc("\(a) \(cleaned)"))") }
        }

        var ranked = LyricsMatcher.rank(q, gathered)
        var best = LyricsMatcher.best(ranked)
        if best == nil || (best?.score ?? 0) < 0.8 || (best?.record.synced ?? "").isEmpty {
            await attempt { [self] in try await search("q=\(enc(cleaned))") }
            ranked = LyricsMatcher.rank(q, gathered)
            best = LyricsMatcher.best(ranked)
        }
        if let b = best { return .found(best: b, ranked: ranked, requests: requests) }
        // Some requests failed and none of the rest produced a match: we cannot claim the lyrics don't exist.
        if failures > 0 { return .failed(kind: lastFailure?.kind ?? "UNKNOWN", message: lastFailure?.message ?? "lookup failed") }
        return .notFound(ranked: ranked, requests: requests)
    }

    private func getExact(_ title: String, _ artist: String, _ album: String, _ durationSec: Double) async throws -> LrclibRecord? {
        let url = "\(base)/get?track_name=\(enc(title))&artist_name=\(enc(artist))&album_name=\(enc(album))&duration=\(Int(durationSec.rounded()))"
        guard let r = try await LabHttp.get(http, url, pause: pause) else { return nil }
        return Self.parseRecord(try LabHttp.object(r.body))
    }

    private func search(_ query: String) async throws -> [LrclibRecord] {
        guard let r = try await LabHttp.get(http, "\(base)/search?\(query)", pause: pause) else { return [] }
        return try LabHttp.array(r.body).compactMap { ($0 as? [String: Any]).flatMap(Self.parseRecord) }
    }

    static func parseRecord(_ o: [String: Any]) -> LrclibRecord? {
        guard let id = (o["id"] as? NSNumber)?.int64Value else { return nil }
        func str(_ k: String) -> String? {
            guard let s = o[k] as? String, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return s
        }
        return LrclibRecord(id: id, trackName: str("trackName") ?? str("name"), artistName: str("artistName"),
                            albumName: str("albumName"), durationSec: (o["duration"] as? NSNumber)?.doubleValue,
                            instrumental: (o["instrumental"] as? Bool) ?? false, plain: str("plainLyrics"), synced: str("syncedLyrics"))
    }

    private func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

/// Coordinates locally imported LRC (always available) and optional on-demand online lyrics.
/// Online is allowed only when `onlineAllowedInThisBuild` is true (debug and TestFlight builds); the public App Store build keeps it
/// disabled pending licensing review. Independently removable: nothing else depends on it.
public final class LyricsRepository {
    private let local: LocalLrcStore
    private let client: LrclibClient
    private let itunes: ITunesClient
    private let musicBrainz: MusicBrainzClient
    public let onlineAllowedInThisBuild: Bool
    private let onlineEnabled: () -> Bool

    /// `itunesHTTP` / `musicBrainzHTTP` default to `http` (tests); the app passes separately spaced fetchers.
    public init(local: LocalLrcStore, http: HTTPFetcher, itunesHTTP: HTTPFetcher? = nil, musicBrainzHTTP: HTTPFetcher? = nil,
                onlineAllowedInThisBuild: Bool, onlineEnabled: @escaping () -> Bool) {
        self.local = local
        self.client = LrclibClient(http: http)
        self.itunes = ITunesClient(http: itunesHTTP ?? http)
        self.musicBrainz = MusicBrainzClient(http: musicBrainzHTTP ?? http)
        self.onlineAllowedInThisBuild = onlineAllowedInThisBuild
        self.onlineEnabled = onlineEnabled
    }

    public func load(songKey: String, query: TrackQuery) async -> LyricsUiState {
        if let l = local.get(songKey) { return .ready(l) }
        if !onlineAllowedInThisBuild || !onlineEnabled() { return .disabled }

        // Duration and album make the right recording much more likely (and let us reject timestamps that can't fit it).
        var q = query
        var durationMs = q.durationSec.map { Int($0 * 1000) }
        if let id = q.appleMusicId, case .found(let m) = await itunes.lookupById(id) {
            durationMs = m.durationMs ?? durationMs
            q.album = m.album ?? q.album
        }
        if (durationMs == nil || q.album == nil), let isrc = q.isrc, case .found(let m) = await musicBrainz.byIsrc(isrc) {
            durationMs = durationMs ?? m.durationMs
            q.album = q.album ?? m.album
        }
        q.durationSec = durationMs.map { Double($0) / 1000 }

        switch await client.lookup(q) {
        case .failed:
            return .unavailable("Could not load lyrics. Check your connection and try again.")
        case .notFound:
            return .unavailable("No lyrics found for this track")
        case .found(let best, _, let requests):
            let rec = best.record
            var info = "LRCLIB #\(rec.id) \"\(rec.trackName ?? "")\" score=\(String(format: "%.2f", best.score))"
            if let g = best.durationGapSec { info += String(format: " drift=%.1fs", g) }
            info += " requests=\(requests)"
            if rec.instrumental {
                return .ready(LyricsResult(plain: nil, syncedLines: [], source: .online, instrumental: true, durationMs: durationMs, info: info))
            }
            let doc = LrcParser.parseDoc(rec.synced)
            let lines = doc.lines.filter { !$0.text.isEmpty }
            if LyricsSanity.syncedLooksPlausible(doc, durationMs: durationMs), !lines.isEmpty {
                return .ready(LyricsResult(plain: rec.plain ?? lines.map { $0.text }.joined(separator: "\n"),
                                           syncedLines: lines, source: .online, durationMs: durationMs, info: info))
            }
            if rec.plain != nil || !lines.isEmpty {
                let why = doc.isEmpty ? "no synchronized lyrics for this recording"
                                      : "synchronized lyrics rejected (timestamps run past the track end)"
                return .ready(LyricsResult(plain: rec.plain ?? lines.map { $0.text }.joined(separator: "\n"),
                                           syncedLines: [], source: .online, durationMs: durationMs, info: "\(info); \(why)"))
            }
            return .unavailable("LRCLIB has this song but no lyrics for it")
        }
    }

    public func importLocal(songKey: String, lrcText: String) { local.put(songKey, lrcText: lrcText) }
}
