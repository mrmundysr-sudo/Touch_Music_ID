import Foundation

public enum FactSource: String, Codable, Equatable {
    case wikipedia
    case wikidata
    public var label: String { self == .wikipedia ? "Wikipedia" : "Wikidata" }
}

/// A single sourced statement. Nothing is shown without one of these or a quoted Wikipedia extract.
public struct Fact: Codable, Equatable {
    public var label: String
    public var value: String
    public var source: FactSource
    public var url: String?
    public init(label: String, value: String, source: FactSource, url: String?) {
        self.label = label; self.value = value; self.source = source; self.url = url
    }
}

public struct SourceLink: Equatable {
    public let label: String
    public let url: String
}

public enum MatchStatus: String, Codable, Equatable {
    /// The lookup could not be completed. Says NOTHING about whether an article exists. Never cached.
    case lookupFailed
    /// Exactly one candidate passed all checks.
    case verified
    /// More than one plausible candidate; the user must choose. Nothing is asserted.
    case ambiguous
    /// The lookup completed and no candidate passed the checks. A genuine, checked "not found".
    case notFound
}

public enum FailureReason: String, Codable, Equatable {
    case offline, rateLimited, server, unknown
}

public struct Candidate: Codable, Equatable {
    public var qid: String
    public var label: String
    public var description: String?
    public init(qid: String, label: String, description: String?) {
        self.qid = qid; self.label = label; self.description = description
    }
}

public struct EntityKnowledge: Codable, Equatable {
    public var qid: String?
    public var name: String
    public var description: String?
    /// Verbatim Wikipedia lead extract (CC BY-SA 4.0). Never paraphrased by the app.
    public var extract: String?
    public var wikipediaURL: String?
    public var wikidataURL: String?
    public var facts: [Fact]
    /// Human-readable reason this entity was accepted.
    public var basis: String
    public init(qid: String?, name: String, description: String?, extract: String?, wikipediaURL: String?,
                wikidataURL: String?, facts: [Fact], basis: String) {
        self.qid = qid; self.name = name; self.description = description; self.extract = extract
        self.wikipediaURL = wikipediaURL; self.wikidataURL = wikidataURL; self.facts = facts; self.basis = basis
    }
}

public struct Lookup: Codable, Equatable {
    public var status: MatchStatus
    public var entity: EntityKnowledge?
    public var candidates: [Candidate]
    /// Set only when status is `.lookupFailed`.
    public var failure: FailureReason?
    public init(status: MatchStatus, entity: EntityKnowledge? = nil, candidates: [Candidate] = [], failure: FailureReason? = nil) {
        self.status = status; self.entity = entity; self.candidates = candidates; self.failure = failure
    }
}

public struct KnowledgeBundle: Codable, Equatable {
    public var artist: Lookup
    public var song: Lookup
    public var fetchedAt: Date
    public init(artist: Lookup, song: Lookup, fetchedAt: Date) {
        self.artist = artist; self.song = song; self.fetchedAt = fetchedAt
    }

    public func sources() -> [SourceLink] {
        var out: [SourceLink] = []
        for e in [artist.entity, song.entity].compactMap({ $0 }) {
            if let u = e.wikipediaURL { out.append(SourceLink(label: "Wikipedia: \(e.name)", url: u)) }
            if let u = e.wikidataURL { out.append(SourceLink(label: "Wikidata: \(e.name)", url: u)) }
        }
        var seen = Set<String>()
        return out.filter { seen.insert($0.url).inserted }
    }
}

public enum Freshness: Equatable { case live, cacheFresh, cacheStaleOffline }

public enum KnowledgeResult: Equatable {
    case found(KnowledgeBundle, Freshness)
    case failed(FailureReason)
}

public let attributionWikipedia =
    "Text from Wikipedia is available under the Creative Commons Attribution-ShareAlike 4.0 license; " +
    "see each article's page history for authors."
public let attributionWikidata = "Structured data from Wikidata is available under CC0."
