import Foundation

/// A failed request. Never to be confused with "nothing found" (HTTP 404 / empty result).
public struct LabApiFailure: Error, Equatable {
    public let kind: String
    public let message: String
    public init(kind: String, message: String) { self.kind = kind; self.message = message }
}

/// Spaces requests to one service by a minimum interval (the free lyrics/metadata services ask for polite, sequential use).
public final class SpacedFetcher: HTTPFetcher {
    private actor Gate {
        private var last: Date?
        /// Reserves the next slot and returns how long to wait for it. Reserving before sleeping keeps concurrent callers apart.
        func reserve(_ interval: TimeInterval) -> TimeInterval {
            let now = Date()
            let slot = max(now, (last ?? .distantPast).addingTimeInterval(interval))
            last = slot
            return slot.timeIntervalSince(now)
        }
    }

    private let inner: HTTPFetcher
    private let minInterval: TimeInterval
    private let gate = Gate()

    public init(_ inner: HTTPFetcher, minInterval: TimeInterval) {
        self.inner = inner
        self.minInterval = minInterval
    }

    public func get(_ url: URL) async throws -> HTTPResponse {
        let wait = await gate.reserve(minInterval)
        if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
        return try await inner.get(url)
    }
}

public enum LabHttp {
    public static let userAgent = "TouchMusicID/0.1 (iOS; https://github.com/mrmundysr-sudo/Touch_Music_ID)"

    /// GET with one polite retry (honouring a short Retry-After). Returns nil for 404 ("not found", a completed answer).
    /// Throws `LabApiFailure` for everything else that is not a 2xx.
    public static func get(_ http: HTTPFetcher, _ urlString: String,
                           pause: (Int) async -> Void = { ms in try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }) async throws -> HTTPResponse? {
        guard let url = URL(string: urlString) else { throw LabApiFailure(kind: "MALFORMED", message: "bad url") }
        var attempt = 0
        while true {
            let r: HTTPResponse
            do {
                r = try await http.get(url)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if attempt == 0 { await pause(800); attempt += 1; continue }
                throw LabApiFailure(kind: "NETWORK", message: error.localizedDescription)
            }
            switch r.code {
            case 404:
                return nil
            case 200...299:
                return r
            case 429, 403, 500...599:
                if attempt == 0 {
                    let ms = min(max((r.retryAfterSeconds ?? 1) * 1000, 500), 3_000)
                    await pause(ms)
                    attempt += 1
                    continue
                }
                throw LabApiFailure(kind: (r.code == 429 || r.code == 403) ? "RATE_LIMITED" : "SERVER", message: "HTTP \(r.code)")
            default:
                throw LabApiFailure(kind: "SERVER", message: "HTTP \(r.code)")
            }
        }
    }

    public static func object(_ data: Data) throws -> [String: Any] {
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw LabApiFailure(kind: "MALFORMED", message: "response is not a JSON object")
        }
        return o
    }

    public static func array(_ data: Data) throws -> [Any] {
        guard let a = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else {
            throw LabApiFailure(kind: "MALFORMED", message: "response is not a JSON array")
        }
        return a
    }
}

/// Free track metadata used only to make lyric matching safer: duration (to tell edits and versions apart) and album.
public struct TrackMeta: Equatable {
    public var durationMs: Int?
    public var album: String?
    public var source: String
    public var note: String = ""
}

public enum MetaResult: Equatable {
    case found(TrackMeta)
    case notFound
    case failed(kind: String, message: String)
}

private func nonBlank(_ v: Any?) -> String? {
    guard let s = v as? String, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    return s
}

private func encode(_ s: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
}

/// Apple's free iTunes lookup. The Apple Music ID ShazamKit returns is the iTunes track id of the exact release Shazam matched.
public final class ITunesClient {
    private let http: HTTPFetcher
    private let pause: (Int) async -> Void
    public init(http: HTTPFetcher, pause: @escaping (Int) async -> Void = { ms in try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }) {
        self.http = http; self.pause = pause
    }

    public func lookupById(_ appleMusicId: String) async -> MetaResult {
        do {
            guard let r = try await LabHttp.get(http, "https://itunes.apple.com/lookup?id=\(encode(appleMusicId))", pause: pause) else { return .notFound }
            return Self.parse(r.body, source: "iTunes lookup")
        } catch let f as LabApiFailure {
            return .failed(kind: f.kind, message: f.message)
        } catch {
            return .failed(kind: "NETWORK", message: error.localizedDescription)
        }
    }

    static func parse(_ body: Data, source: String) -> MetaResult {
        let o: [String: Any]
        do { o = try LabHttp.object(body) } catch let f as LabApiFailure { return .failed(kind: f.kind, message: f.message) } catch { return .failed(kind: "MALFORMED", message: "") }
        guard let results = o["results"] as? [Any] else { return .failed(kind: "MALFORMED", message: "missing results") }
        if o["resultCount"] == nil { return .failed(kind: "MALFORMED", message: "missing resultCount") }
        guard let first = results.first as? [String: Any] else { return .notFound }
        var dur: Int?
        if let n = first["trackTimeMillis"] as? NSNumber, n.intValue > 0 { dur = n.intValue }
        return .found(TrackMeta(durationMs: dur, album: nonBlank(first["collectionName"]), source: source))
    }
}

/// MusicBrainz (free, open data). ISRC lookup returns the recordings sharing that ISRC, with their lengths and releases.
public final class MusicBrainzClient {
    private let http: HTTPFetcher
    private let pause: (Int) async -> Void
    public init(http: HTTPFetcher, pause: @escaping (Int) async -> Void = { ms in try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }) {
        self.http = http; self.pause = pause
    }

    public func byIsrc(_ isrc: String) async -> MetaResult {
        do {
            guard let r = try await LabHttp.get(http, "https://musicbrainz.org/ws/2/isrc/\(encode(isrc))?fmt=json&inc=releases", pause: pause) else { return .notFound }
            return Self.parseIsrc(r.body)
        } catch let f as LabApiFailure {
            return .failed(kind: f.kind, message: f.message)
        } catch {
            return .failed(kind: "NETWORK", message: error.localizedDescription)
        }
    }

    static func parseIsrc(_ body: Data) -> MetaResult {
        let o: [String: Any]
        do { o = try LabHttp.object(body) } catch let f as LabApiFailure { return .failed(kind: f.kind, message: f.message) } catch { return .failed(kind: "MALFORMED", message: "") }
        guard let recs = o["recordings"] as? [Any] else { return .failed(kind: "MALFORMED", message: "missing recordings") }
        if recs.isEmpty { return .notFound }
        var lengths: [Int] = []
        var album: String?
        for r in recs {
            guard let rec = r as? [String: Any] else { continue }
            if let n = rec["length"] as? NSNumber, n.intValue > 0 { lengths.append(n.intValue) }
            if album == nil, let rel = (rec["releases"] as? [Any])?.first as? [String: Any] { album = nonBlank(rel["title"]) }
        }
        let sorted = lengths.sorted()
        let median = sorted.isEmpty ? nil : sorted[sorted.count / 2]
        let note = "recordings=\(recs.count) lengths(ms)=\(sorted.map(String.init).joined(separator: "/"))"
        return .found(TrackMeta(durationMs: median, album: album, source: "MusicBrainz ISRC", note: note))
    }
}
