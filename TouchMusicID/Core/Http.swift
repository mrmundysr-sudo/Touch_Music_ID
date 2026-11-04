import Foundation

public struct HTTPResponse {
    public let code: Int
    public let body: Data
    public let retryAfterSeconds: Int?
    public init(code: Int, body: Data, retryAfterSeconds: Int? = nil) {
        self.code = code
        self.body = body
        self.retryAfterSeconds = retryAfterSeconds
    }
    public var text: String { String(data: body, encoding: .utf8) ?? "" }
}

/// Injectable network boundary so the clients are unit-testable without a network.
public protocol HTTPFetcher {
    func get(_ url: URL) async throws -> HTTPResponse
}

/// Polite fetcher: identifies the app, short timeout, no cookies, no credentials.
public struct URLSessionFetcher: HTTPFetcher {
    private let session: URLSession
    public static let userAgent = LabHttp.userAgent

    public init(timeout: TimeInterval = 12) {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout * 2
        cfg.httpCookieStorage = nil
        session = URLSession(configuration: cfg)
    }

    public func get(_ url: URL) async throws -> HTTPResponse {
        var req = URLRequest(url: url)
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, resp) = try await session.data(for: req)
        let http = resp as? HTTPURLResponse
        let retry = http?.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0) }
        return HTTPResponse(code: http?.statusCode ?? 0, body: data, retryAfterSeconds: retry)
    }
}
