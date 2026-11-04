import Foundation

/// Pure JSON parsers for the Wikipedia REST/Action APIs and Wikidata. No I/O, so they are unit-tested directly.

public struct ArticleSummary: Equatable {
    public let title: String
    public let description: String?
    public let extract: String?
    public let url: String?
    public let isDisambiguation: Bool
}

public struct SearchHit: Equatable { public let title: String; public let snippet: String? }
public struct EntityHit: Equatable { public let id: String; public let label: String?; public let description: String? }

public enum ClaimValue: Equatable {
    case ref(String)
    case time(iso: String, precision: Int)
    case text(String)
}

public struct WikidataEntity: Equatable {
    public let id: String
    public let label: String?
    public let description: String?
    public let aliases: [String]
    public let claims: [String: [ClaimValue]]
    public let enwikiTitle: String?

    public func refs(_ property: String) -> [String] {
        (claims[property] ?? []).compactMap { if case .ref(let id) = $0 { return id } else { return nil } }
    }
    public func has(_ property: String) -> Bool { !(claims[property] ?? []).isEmpty }
    public func firstTime(_ property: String) -> (iso: String, precision: Int)? {
        for v in claims[property] ?? [] { if case .time(let iso, let p) = v { return (iso, p) } }
        return nil
    }
    public var names: [String] { (label.map { [$0] } ?? []) + aliases }
}

public enum WikiParsers {
    private static func object(_ data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func str(_ o: [String: Any]?, _ key: String) -> String? {
        guard let s = o?[key] as? String, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }

    public static func parseSummary(_ data: Data) -> ArticleSummary? {
        guard let o = object(data), let title = str(o, "title") else { return nil }
        let desktop = ((o["content_urls"] as? [String: Any])?["desktop"]) as? [String: Any]
        return ArticleSummary(
            title: title,
            description: str(o, "description"),
            extract: str(o, "extract"),
            url: str(desktop, "page"),
            isDisambiguation: (o["type"] as? String) == "disambiguation"
        )
    }

    public static func parseSearch(_ data: Data) -> [SearchHit] {
        guard let arr = ((object(data)?["query"] as? [String: Any])?["search"]) as? [[String: Any]] else { return [] }
        return arr.compactMap { h in
            guard let title = h["title"] as? String else { return nil }
            let snippet = str(h, "snippet")?.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            return SearchHit(title: title, snippet: snippet)
        }
    }

    public static func parseEntitySearch(_ data: Data) -> [EntityHit] {
        guard let arr = object(data)?["search"] as? [[String: Any]] else { return [] }
        return arr.compactMap { h in
            guard let id = h["id"] as? String else { return nil }
            return EntityHit(id: id, label: str(h, "label"), description: str(h, "description"))
        }
    }

    public static func parseEntities(_ data: Data) -> [String: WikidataEntity] {
        guard let ents = object(data)?["entities"] as? [String: Any] else { return [:] }
        var out: [String: WikidataEntity] = [:]
        for (id, raw) in ents {
            guard let e = raw as? [String: Any], e["missing"] == nil else { continue }
            let labels = e["labels"] as? [String: Any]
            // English label first; otherwise the language-neutral `mul` label (requested alongside `en`).
            let label = str(labels?["en"] as? [String: Any], "value") ?? str(labels?["mul"] as? [String: Any], "value")
            let desc = str((e["descriptions"] as? [String: Any])?["en"] as? [String: Any], "value")
            var aliases: [String] = []
            let aliasObj = e["aliases"] as? [String: Any]
            for lang in ["en", "mul"] {
                for a in (aliasObj?[lang] as? [[String: Any]]) ?? [] {
                    if let v = str(a, "value"), !aliases.contains(v) { aliases.append(v) }
                }
            }
            let enwiki = str(((e["sitelinks"] as? [String: Any])?["enwiki"]) as? [String: Any], "title")
            out[id] = WikidataEntity(id: id, label: label, description: desc, aliases: aliases,
                                     claims: parseClaims(e["claims"] as? [String: Any]), enwikiTitle: enwiki)
        }
        return out
    }

    private static func parseClaims(_ claims: [String: Any]?) -> [String: [ClaimValue]] {
        guard let claims else { return [:] }
        var out: [String: [ClaimValue]] = [:]
        for (prop, raw) in claims {
            guard let statements = raw as? [[String: Any]] else { continue }
            let values: [ClaimValue] = statements.compactMap { st in
                if (st["rank"] as? String) == "deprecated" { return nil }
                guard let dv = (st["mainsnak"] as? [String: Any])?["datavalue"] as? [String: Any] else { return nil }
                switch dv["type"] as? String {
                case "wikibase-entityid":
                    if let id = str(dv["value"] as? [String: Any], "id") { return .ref(id) }
                    return nil
                case "time":
                    guard let v = dv["value"] as? [String: Any] else { return nil }
                    return .time(iso: (v["time"] as? String) ?? "", precision: (v["precision"] as? Int) ?? 11)
                case "string":
                    if let s = str(dv, "value") { return .text(s) }
                    return nil
                default:
                    return nil
                }
            }
            if !values.isEmpty { out[prop] = values }
        }
        return out
    }

    /// "+1946-09-05T00:00:00Z" at precision 11 -> "1946-09-05"; precision 10 -> "1946-09"; 9 or less -> "1946".
    public static func formatTime(iso: String, precision: Int) -> String? {
        guard let re = try? NSRegularExpression(pattern: "^([+-])(\\d+)-(\\d{2})-(\\d{2})T"),
              let m = re.firstMatch(in: iso, range: NSRange(iso.startIndex..., in: iso)), m.numberOfRanges == 5 else { return nil }
        func g(_ i: Int) -> String { Range(m.range(at: i), in: iso).map { String(iso[$0]) } ?? "" }
        let sign = g(1), mo = g(3), d = g(4)
        var year = String(g(2).drop(while: { $0 == "0" }))
        if year.isEmpty { year = "0" }
        let core: String
        if precision >= 11 && mo != "00" && d != "00" { core = "\(year)-\(mo)-\(d)" }
        else if precision == 10 && mo != "00" { core = "\(year)-\(mo)" }
        else { core = year }
        return sign == "-" ? "\(core) BCE" : core
    }
}
