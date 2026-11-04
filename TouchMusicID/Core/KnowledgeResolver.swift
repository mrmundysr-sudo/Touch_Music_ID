import Foundation

/// Conservative entity resolution. The rule is: assert nothing unless a checkable signal supports it.
///  - Artist: Wikidata item whose label/alias equals the name AND that is demonstrably a musical entity.
///    Exactly one such item => verified. Several => ambiguous (user chooses). None => notFound. Any failure => lookupFailed (never notFound).
///  - Song: Wikidata item whose label equals the title, that is a musical work, and whose performer matches the
///    recognised artist. Falls back to a Wikipedia article only if its text names the artist.
/// Facts shown come from Wikidata statements or the verbatim Wikipedia extract, each with a source.
public final class KnowledgeResolver {
    private let api: WikiApi
    public init(api: WikiApi) { self.api = api }

    /// Artist and song are resolved independently. A failure in one becomes `.lookupFailed` for that card only;
    /// it is never reported as `.notFound`. If both fail, `WikiError.lookupFailed` is thrown.
    public func resolve(_ song: Song, chosenArtistQid: String? = nil) async throws -> (artist: Lookup, song: Lookup) {
        let artist = try await guarded { try await self.resolveArtist(song.artist, chosenQid: chosenArtistQid) }
        let songLookup = try await guarded { try await self.resolveSong(song, artist: artist) }
        if artist.status == .lookupFailed && songLookup.status == .lookupFailed {
            throw WikiError.lookupFailed(artist.failure ?? .unknown)
        }
        return (artist, songLookup)
    }

    private func guarded(_ block: () async throws -> Lookup) async throws -> Lookup {
        do {
            return try await block()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return Lookup(status: .lookupFailed, failure: failureReason(for: error))
        }
    }

    // MARK: Artist

    public func resolveArtist(_ name: String, chosenQid: String? = nil) async throws -> Lookup {
        if name.trimmingCharacters(in: .whitespaces).isEmpty { return Lookup(status: .notFound) }
        let ids: [String]
        if let q = chosenQid { ids = [q] } else { ids = try await api.searchEntities(name).map { $0.id } }
        if ids.isEmpty { return Lookup(status: .notFound) }
        let entities = try await api.entities(ids)
        let qualified = ids.compactMap { entities[$0] }
            .filter { chosenQid != nil || nameMatches($0, name) }
            .filter { isMusicalEntity($0) }
        if qualified.isEmpty { return Lookup(status: .notFound) }
        if qualified.count > 1 {
            return Lookup(status: .ambiguous, candidates: qualified.map { Candidate(qid: $0.id, label: $0.label ?? name, description: $0.description) })
        }
        let e = qualified[0]
        return Lookup(status: .verified, entity: try await buildEntity(e, basis: artistBasis(e), isArtist: true))
    }

    func nameMatches(_ e: WikidataEntity, _ name: String) -> Bool {
        let target = normalizeText(name)
        return e.names.contains { normalizeText($0) == target }
    }

    func isMusicalEntity(_ e: WikidataEntity) -> Bool {
        if e.has(Self.pMusicbrainzArtist) || e.has(Self.pSpotifyArtist) || e.has(Self.pAllmusicArtist) { return true }
        let isHuman = e.refs(Self.pInstanceOf).contains(Self.qHuman)
        if isHuman && e.refs(Self.pOccupation).contains(where: { Self.musicOccupations.contains($0) }) { return true }
        return e.refs(Self.pInstanceOf).contains(where: { Self.musicGroupClasses.contains($0) })
    }

    private func artistBasis(_ e: WikidataEntity) -> String {
        e.has(Self.pMusicbrainzArtist)
            ? "Wikidata item whose name matches exactly and which carries a MusicBrainz artist ID."
            : "Wikidata item whose name matches exactly and which is a musician or musical group."
    }

    // MARK: Song

    public func resolveSong(_ song: Song, artist: Lookup) async throws -> Lookup {
        let hits = try await api.searchEntities(song.title, limit: 8)
        var entities: [String: WikidataEntity] = [:]
        if !hits.isEmpty { entities = try await api.entities(hits.map { $0.id }) }
        let works = hits.compactMap { entities[$0.id] }.filter { nameMatches($0, song.title) && isMusicalWork($0) }
        let performerLabels = try await labels(for: works.flatMap { $0.refs(Self.pPerformer) })
        let artistQid = artist.entity?.qid
        let withArtist = works.filter { w in
            w.refs(Self.pPerformer).contains { p in
                if let q = artistQid, p == q { return true }
                if let l = performerLabels[p] { return normalizeText(l) == normalizeText(song.artist) }
                return false
            }
        }
        var chosen: WikidataEntity?
        if withArtist.count == 1 { chosen = withArtist[0] }
        else if withArtist.count > 1 {
            let withWiki = withArtist.filter { $0.enwikiTitle != nil }
            if withWiki.count == 1 { chosen = withWiki[0] }
        }
        if let c = chosen {
            return Lookup(status: .verified, entity: try await buildEntity(
                c, basis: "Wikidata musical work with a matching title and performer.", isArtist: false, songArtist: song.artist))
        }
        if withArtist.count > 1 { return Lookup(status: .ambiguous) }
        return try await wikipediaOnlySong(song)
    }

    func isMusicalWork(_ e: WikidataEntity) -> Bool {
        e.refs(Self.pInstanceOf).contains(where: { Self.musicWorkClasses.contains($0) }) || e.has(Self.pPerformer)
    }

    private func wikipediaOnlySong(_ song: Song) async throws -> Lookup {
        let title = normalizeText(song.title)
        let artist = normalizeText(song.artist)
        let hits = try await api.searchArticles("\"\(song.title)\" \(song.artist) song", limit: 5)
        for hit in hits {
            if !normalizeText(hit.title).contains(title) { continue }
            guard let s = try await api.summary(hit.title) else { continue }
            if s.isDisambiguation { continue }
            let text = normalizeText((s.description ?? "") + " " + (s.extract ?? ""))
            if !text.contains(artist) { continue }
            return Lookup(status: .verified, entity: EntityKnowledge(
                qid: nil, name: s.title, description: s.description, extract: s.extract,
                wikipediaURL: s.url, wikidataURL: nil, facts: [],
                basis: "Wikipedia article whose title contains the song title and whose text names the artist. No Wikidata record was confirmed."))
        }
        return Lookup(status: .notFound)
    }

    // MARK: Building entities

    private func labels(for ids: [String]) async throws -> [String: String] {
        if ids.isEmpty { return [:] }
        var out: [String: String] = [:]
        for (k, v) in try await api.entities(ids, full: false) { if let l = v.label { out[k] = l } }
        return out
    }

    private func buildEntity(_ e: WikidataEntity, basis: String, isArtist: Bool, songArtist: String? = nil) async throws -> EntityKnowledge {
        var seen = Set<String>()
        let refIds = Self.factRefProps.flatMap { e.refs($0) }.filter { seen.insert($0).inserted }
        let labelMap = try await labels(for: refIds)
        let wdUrl = "https://www.wikidata.org/wiki/\(e.id)"
        var facts: [Fact] = []

        func refFact(_ label: String, _ prop: String, max: Int = 4) {
            var uniq: [String] = []
            for id in e.refs(prop) { if let l = labelMap[id], !uniq.contains(l) { uniq.append(l) } }
            let v = Array(uniq.prefix(max))
            if !v.isEmpty { facts.append(Fact(label: label, value: v.joined(separator: ", "), source: .wikidata, url: wdUrl)) }
        }
        func timeFact(_ label: String, _ prop: String) {
            if let t = e.firstTime(prop), let f = WikiParsers.formatTime(iso: t.iso, precision: t.precision) {
                facts.append(Fact(label: label, value: f, source: .wikidata, url: wdUrl))
            }
        }
        if isArtist {
            timeFact("Born", Self.pBirth); timeFact("Formed", Self.pInception); timeFact("Died", Self.pDeath); timeFact("Disbanded", Self.pDissolved)
            refFact("Origin", Self.pBirthplace, max: 1); refFact("Formed in", Self.pFormationLocation, max: 1)
            refFact("Occupation", Self.pOccupation); refFact("Genre", Self.pGenre); refFact("Record label", Self.pLabel, max: 3)
        } else {
            timeFact("Released", Self.pPublication); refFact("Album / release", Self.pPartOf, max: 2); refFact("Genre", Self.pGenre)
            refFact("Record label", Self.pLabel, max: 3); refFact("Producer", Self.pProducer, max: 3); refFact("Composer", Self.pComposer, max: 3)
            refFact("Lyricist", Self.pLyricist, max: 3)
        }
        // A person's birthplace and "formed in" are often the same place; show it once.
        let origin = facts.first(where: { $0.label == "Origin" })?.value
        facts.removeAll { $0.label == "Formed in" && $0.value == origin }
        var summary: ArticleSummary?
        if let t = e.enwikiTitle, let s = try await api.summary(t), !s.isDisambiguation { summary = s }
        return EntityKnowledge(
            qid: e.id,
            name: e.label ?? summary?.title ?? songArtist ?? "",
            description: e.description ?? summary?.description,
            extract: summary?.extract,
            wikipediaURL: summary?.url,
            wikidataURL: wdUrl,
            facts: facts,
            basis: basis)
    }

    static let pInstanceOf = "P31", pOccupation = "P106", pGenre = "P136"
    static let pPerformer = "P175", pPartOf = "P361", pLabel = "P264"
    static let pProducer = "P162", pComposer = "P86", pLyricist = "P676"
    static let pPublication = "P577", pBirth = "P569", pDeath = "P570"
    static let pInception = "P571", pDissolved = "P576"
    static let pBirthplace = "P19", pFormationLocation = "P740"
    static let pMusicbrainzArtist = "P434", pSpotifyArtist = "P1902", pAllmusicArtist = "P1728"
    static let qHuman = "Q5"
    static let factRefProps = [pOccupation, pGenre, pPartOf, pLabel, pProducer, pComposer, pLyricist, pBirthplace, pFormationLocation]

    /// singer, musician, composer, songwriter, record producer, rapper, DJ, guitarist, pianist, conductor, drummer, bassist.
    static let musicOccupations: Set<String> = ["Q177220", "Q639669", "Q36834", "Q753110", "Q183945", "Q2252262", "Q130857",
                                                "Q855091", "Q486748", "Q158852", "Q386854", "Q584301", "Q488205", "Q806349"]
    /// musical group, band, rock band, musical duo, musical ensemble, vocal group, girl group, boy band.
    static let musicGroupClasses: Set<String> = ["Q215380", "Q5741069", "Q56816954", "Q2088357", "Q1043271", "Q641066", "Q216337", "Q16010345"]
    /// song, single, musical work, musical composition, album, music track.
    static let musicWorkClasses: Set<String> = ["Q7366", "Q134556", "Q2188189", "Q105543609", "Q482994", "Q4176309", "Q108352648"]
}
