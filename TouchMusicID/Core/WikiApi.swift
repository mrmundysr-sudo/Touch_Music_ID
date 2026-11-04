import Foundation

/// Errors that mean "the lookup could not be completed". None of them is ever read as "no results".
public enum WikiError: Error, Equatable {
    case rateLimited(retryAfter: Int?)
    case server(code: Int, retryAfter: Int?)
    /// MediaWiki/Wikibase answered HTTP 200 with an `error` object. It is a FAILURE, not "no results".
    case api(code: String, retryAfter: Int?)
    /// The body was not JSON, or a field every valid response has was missing.
    case malformed(String)
    case lookupFailed(FailureReason)
    case network
}

public func failureReason(for error: Error) -> FailureReason {
    switch error {
    case let e as WikiError:
        switch e {
        case .lookupFailed(let r): return r
        case .rateLimited: return .rateLimited
        case .server, .api, .malformed: return .server
        case .network: return .offline
        }
    case is URLError: return .offline
    default: return .offline
    }
}

/// Thin, polite client over the official Wikipedia and Wikidata endpoints.
/// Every response is validated: HTTP status, JSON syntax, an `error` object under HTTP 200, and the required top-level field.
/// An empty but well-formed result (for example `"search": []`) is the ONLY thing reported as "nothing found".
/// Transient failures are retried a bounded number of times, honouring Retry-After (capped).
public final class WikiApi {
    private let http: HTTPFetcher
    private let wikipediaHost: String
    private let wikidataHost: String
    private let retries: Int
    private let backoffMillis: [Int]
    private let maxRetryAfterMillis: Int
    private let pause: (Int) async -> Void

    public static let transientApiCodes: Set<String> = ["cirrussearch-too-busy-error", "maxlag", "ratelimited", "readonly", "cirrussearch-backend-error"]

    public init(http: HTTPFetcher,
                wikipediaHost: String = "en.wikipedia.org",
                wikidataHost: String = "www.wikidata.org",
                retries: Int = 2,
                backoffMillis: [Int] = [400, 1000],
                maxRetryAfterMillis: Int = 5000,
                pause: @escaping (Int) async -> Void = { ms in try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }) {
        self.http = http
        self.wikipediaHost = wikipediaHost
        self.wikidataHost = wikidataHost
        self.retries = retries
        self.backoffMillis = backoffMillis
        self.maxRetryAfterMillis = maxRetryAfterMillis
        self.pause = pause
    }

    public func summary(_ title: String) async throws -> ArticleSummary? {
        let path = enc(title.replacingOccurrences(of: " ", with: "_"))
        let url = "https://\(wikipediaHost)/api/rest_v1/page/summary/\(path)?redirect=true"
        return try await withRetry { [self] () async throws -> ArticleSummary? in
            let r = try await fetch(url)
            if r.code == 404 { return nil }
            return WikiParsers.parseSummary(try validated(try checked(r), required: "title"))
        }
    }

    public func searchArticles(_ query: String, limit: Int = 5) async throws -> [SearchHit] {
        let url = "https://\(wikipediaHost)/w/api.php?action=query&list=search&srsearch=\(enc(query))" +
            "&srlimit=\(limit)&srnamespace=0&srprop=snippet&format=json&formatversion=2&utf8=1"
        return WikiParsers.parseSearch(try await getBody(url, required: "query"))
    }

    public func searchEntities(_ name: String, limit: Int = 7) async throws -> [EntityHit] {
        let url = "https://\(wikidataHost)/w/api.php?action=wbsearchentities&search=\(enc(name))" +
            "&language=en&uselang=en&type=item&limit=\(limit)&format=json"
        return WikiParsers.parseEntitySearch(try await getBody(url, required: "search"))
    }

    /// Languages are `en` AND `mul`: Wikidata stores names that are the same in every language only as a `mul` label.
    public func entities(_ ids: [String], full: Bool = true) async throws -> [String: WikidataEntity] {
        if ids.isEmpty { return [:] }
        var seen = Set<String>()
        let unique = ids.filter { seen.insert($0).inserted }.prefix(50)
        let props = full ? "labels|descriptions|aliases|claims|sitelinks" : "labels"
        let url = "https://\(wikidataHost)/w/api.php?action=wbgetentities&ids=\(unique.joined(separator: "%7C"))" +
            "&props=\(enc(props))&languages=en%7Cmul&sitefilter=enwiki&format=json"
        return WikiParsers.parseEntities(try await getBody(url, required: "entities"))
    }

    // MARK: validation + retry

    private func fetch(_ urlString: String) async throws -> HTTPResponse {
        guard let url = URL(string: urlString) else { throw WikiError.malformed("bad url") }
        return try await http.get(url)
    }

    private func getBody(_ url: String, required: String) async throws -> Data {
        try await withRetry { [self] () async throws -> Data in try validated(try checked(try await fetch(url)), required: required) }
    }

    private func checked(_ r: HTTPResponse) throws -> HTTPResponse {
        if r.code == 429 { throw WikiError.rateLimited(retryAfter: r.retryAfterSeconds) }
        if !(200...299).contains(r.code) { throw WikiError.server(code: r.code, retryAfter: r.retryAfterSeconds) }
        return r
    }

    private func validated(_ r: HTTPResponse, required: String) throws -> Data {
        guard let o = (try? JSONSerialization.jsonObject(with: r.body)) as? [String: Any] else {
            throw WikiError.malformed("body is not JSON")
        }
        if let err = o["error"] {
            let dict = err as? [String: Any]
            let code = (dict?["code"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "unknown"
            throw WikiError.api(code: code, retryAfter: r.retryAfterSeconds)
        }
        if o[required] == nil { throw WikiError.malformed("missing \"\(required)\"") }
        return r.body
    }

    private func withRetry<T>(_ block: () async throws -> T) async throws -> T {
        var attempt = 0
        while true {
            do {
                return try await block()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if attempt >= retries || !isTransient(error) { throw error }
                guard let wait = waitFor(error, attempt: attempt) else { throw error }
                await pause(wait)
                attempt += 1
            }
        }
    }

    private func isTransient(_ e: Error) -> Bool {
        switch e {
        case let w as WikiError:
            switch w {
            case .rateLimited, .malformed: return true
            case .server(let code, _): return (500...599).contains(code)
            case .api(let code, _): return Self.transientApiCodes.contains(code) || code.hasPrefix("internal_api_error")
            case .lookupFailed, .network: return false
            }
        case is URLError: return false // already slow / offline: retrying only delays the message
        default: return true
        }
    }

    /// Milliseconds to wait before the next attempt, or nil when a server-requested wait is too long to be worth it.
    private func waitFor(_ e: Error, attempt: Int) -> Int? {
        var retryAfter: Int?
        if let w = e as? WikiError {
            switch w {
            case .rateLimited(let r): retryAfter = r
            case .server(_, let r): retryAfter = r
            case .api(_, let r): retryAfter = r
            default: break
            }
        }
        if let r = retryAfter {
            let ms = max(r, 0) * 1000
            return ms > maxRetryAfterMillis ? nil : ms
        }
        let base = attempt < backoffMillis.count ? backoffMillis[attempt] : (backoffMillis.last ?? 0)
        return min(base, maxRetryAfterMillis)
    }

    private func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}
