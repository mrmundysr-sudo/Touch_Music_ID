import Foundation
@testable import TouchMusicIDCore

/// Scripted network: the handler decides every response; requests are recorded.
final class FakeHTTP: HTTPFetcher {
    var requests: [String] = []
    var handler: (URL) throws -> HTTPResponse
    init(_ handler: @escaping (URL) throws -> HTTPResponse = { _ in HTTPResponse(code: 200, body: Data("{}".utf8)) }) { self.handler = handler }
    func get(_ url: URL) async throws -> HTTPResponse {
        requests.append(url.absoluteString)
        return try handler(url)
    }
}

func json(_ s: String, code: Int = 200, retryAfter: Int? = nil) -> HTTPResponse {
    HTTPResponse(code: code, body: Data(s.utf8), retryAfterSeconds: retryAfter)
}

/// A Wikidata entity JSON fragment.
func entityJSON(id: String, label: String, claims: String = "{}", enwiki: String? = nil, description: String = "") -> String {
    let sitelinks = enwiki.map { #","sitelinks":{"enwiki":{"title":"\#($0)"}}"# } ?? ""
    return #""\#(id)":{"id":"\#(id)","labels":{"en":{"value":"\#(label)"}},"descriptions":{"en":{"value":"\#(description)"}},"claims":\#(claims)\#(sitelinks)}"#
}

func claimRef(_ prop: String, _ qid: String) -> String {
    #""\#(prop)":[{"rank":"normal","mainsnak":{"datavalue":{"type":"wikibase-entityid","value":{"id":"\#(qid)"}}}}]"#
}

func entitiesBody(_ parts: [String]) -> String { #"{"entities":{\#(parts.joined(separator: ","))}}"# }
func searchBody(_ ids: [(String, String)]) -> String {
    let items = ids.map { #"{"id":"\#($0.0)","label":"\#($0.1)"}"# }.joined(separator: ",")
    return #"{"search":[\#(items)]}"#
}

let noPause: (Int) async -> Void = { _ in }
