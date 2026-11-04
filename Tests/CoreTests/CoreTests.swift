import XCTest
@testable import TouchMusicIDCore

final class ModelsTests: XCTestCase {
    func testNormalizeStripsDiacriticsPunctuationAndAmpersand() {
        XCTAssertEqual(normalizeText("Beyoncé"), "beyonce")
        XCTAssertEqual(normalizeText("  Guns N' Roses  "), "guns n roses")
        XCTAssertEqual(normalizeText("Simon & Garfunkel"), "simon and garfunkel")
        XCTAssertEqual(normalizeText("AC/DC"), "ac dc")
    }

    func testSongKeyIsStableAcrossCaseAndPunctuation() {
        XCTAssertEqual(songKey(title: "Hey Jude!", artist: "The Beatles"), songKey(title: "hey jude", artist: "the  beatles"))
        XCTAssertEqual(Song(title: "Purple Rain", artist: "Prince").key, "purple rain||prince")
    }
}

final class WikiParsersTests: XCTestCase {
    func testEntitiesFallBackToMulLabel() {
        let body = #"{"entities":{"Q1":{"id":"Q1","labels":{"mul":{"value":"Prince"}},"aliases":{"en":[{"value":"Prince Rogers Nelson"}]}}}}"#
        let e = WikiParsers.parseEntities(Data(body.utf8))["Q1"]
        XCTAssertEqual(e?.label, "Prince")
        XCTAssertEqual(e?.names, ["Prince", "Prince Rogers Nelson"])
    }

    func testDeprecatedAndMissingEntitiesAreDropped() {
        let body = #"{"entities":{"Q2":{"missing":""},"Q3":{"id":"Q3","claims":{"P31":[{"rank":"deprecated","mainsnak":{"datavalue":{"type":"wikibase-entityid","value":{"id":"Q5"}}}}]}}}}"#
        let all = WikiParsers.parseEntities(Data(body.utf8))
        XCTAssertNil(all["Q2"])
        XCTAssertFalse(all["Q3"]!.has("P31"))
    }

    func testFormatTimePrecision() {
        XCTAssertEqual(WikiParsers.formatTime(iso: "+1946-09-05T00:00:00Z", precision: 11), "1946-09-05")
        XCTAssertEqual(WikiParsers.formatTime(iso: "+1946-09-00T00:00:00Z", precision: 10), "1946-09")
        XCTAssertEqual(WikiParsers.formatTime(iso: "+1946-00-00T00:00:00Z", precision: 9), "1946")
        XCTAssertEqual(WikiParsers.formatTime(iso: "-0044-03-15T00:00:00Z", precision: 11), "44-03-15 BCE")
    }

    func testSummaryAndDisambiguation() {
        let body = #"{"type":"disambiguation","title":"Prince","extract":"x","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Prince"}}}"#
        let s = WikiParsers.parseSummary(Data(body.utf8))
        XCTAssertEqual(s?.isDisambiguation, true)
        XCTAssertEqual(s?.url, "https://en.wikipedia.org/wiki/Prince")
    }

    func testSearchStripsSnippetMarkup() {
        let body = #"{"query":{"search":[{"title":"Purple Rain (song)","snippet":"a <span class=\"x\">song</span>"}]}}"#
        XCTAssertEqual(WikiParsers.parseSearch(Data(body.utf8)).first?.snippet, "a song")
    }
}

final class WikiApiTests: XCTestCase {
    func testEmptySearchIsTheOnlyNothingFound() async throws {
        let api = WikiApi(http: FakeHTTP { _ in json(#"{"search":[]}"#) }, pause: noPause)
        let hits = try await api.searchEntities("nobody")
        XCTAssertTrue(hits.isEmpty)
    }

    func testErrorObjectUnderHttp200IsAFailureNotEmpty() async {
        let api = WikiApi(http: FakeHTTP { _ in json(#"{"error":{"code":"unknown_thing","info":"x"}}"#) }, pause: noPause)
        do { _ = try await api.searchEntities("x"); XCTFail("expected throw") }
        catch let e as WikiError { XCTAssertEqual(e, .api(code: "unknown_thing", retryAfter: nil)) }
        catch { XCTFail("wrong error \(error)") }
    }

    func testMissingRequiredFieldIsMalformedNotEmpty() async {
        let api = WikiApi(http: FakeHTTP { _ in json(#"{"other":1}"#) }, retries: 0, pause: noPause)
        do { _ = try await api.searchEntities("x"); XCTFail("expected throw") }
        catch let e as WikiError { XCTAssertEqual(e, .malformed("missing \"search\"")) }
        catch { XCTFail("wrong error \(error)") }
    }

    func testRateLimitIsRetriedThenSucceeds() async throws {
        var calls = 0
        let http = FakeHTTP { _ in
            calls += 1
            return calls == 1 ? json("{}", code: 429, retryAfter: 1) : json(#"{"search":[{"id":"Q1","label":"A"}]}"#)
        }
        let api = WikiApi(http: http, pause: noPause)
        let hits = try await api.searchEntities("a")
        XCTAssertEqual(hits.map { $0.id }, ["Q1"])
        XCTAssertEqual(calls, 2)
    }

    func testLongRetryAfterIsNotWaitedOut() async {
        let http = FakeHTTP { _ in json("{}", code: 429, retryAfter: 120) }
        let api = WikiApi(http: http, pause: noPause)
        do { _ = try await api.searchEntities("a"); XCTFail("expected throw") }
        catch let e as WikiError { XCTAssertEqual(e, .rateLimited(retryAfter: 120)) }
        catch { XCTFail("wrong error \(error)") }
        XCTAssertEqual(http.requests.count, 1)
    }

    func testSummary404IsNilAndOfflineIsNotRetried() async throws {
        let api = WikiApi(http: FakeHTTP { _ in json("{}", code: 404) }, pause: noPause)
        let s = try await api.summary("Nope")
        XCTAssertNil(s)

        let offline = FakeHTTP { _ in throw URLError(.notConnectedToInternet) }
        let api2 = WikiApi(http: offline, pause: noPause)
        do { _ = try await api2.searchEntities("x"); XCTFail("expected throw") } catch { XCTAssertTrue(error is URLError) }
        XCTAssertEqual(offline.requests.count, 1)
    }

    func testFailureReasonMapping() {
        XCTAssertEqual(failureReason(for: URLError(.timedOut)), .offline)
        XCTAssertEqual(failureReason(for: WikiError.rateLimited(retryAfter: nil)), .rateLimited)
        XCTAssertEqual(failureReason(for: WikiError.api(code: "x", retryAfter: nil)), .server)
        XCTAssertEqual(failureReason(for: WikiError.malformed("x")), .server)
    }
}

final class KnowledgeResolverTests: XCTestCase {
    private func musician(extra: String = "") -> String {
        "{" + [claimRef("P31", "Q5"), claimRef("P106", "Q177220"), extra].filter { !$0.isEmpty }.joined(separator: ",") + "}"
    }

    /// Routes by endpoint so one fake serves search + entities + summary.
    private func http(search: [(String, String)], entities: [String], songSearch: [(String, String)] = [], songEntities: [String] = []) -> FakeHTTP {
        FakeHTTP { url in
            let u = url.absoluteString
            if u.contains("wbsearchentities") {
                return u.contains("search=Queen") ? json(searchBody(search)) : json(searchBody(songSearch))
            }
            if u.contains("wbgetentities") {
                return u.contains("Q") && u.contains(search.first?.0 ?? "-") ? json(entitiesBody(entities)) : json(entitiesBody(songEntities))
            }
            if u.contains("/page/summary/") { return json("{}", code: 404) }
            if u.contains("list=search") { return json(#"{"query":{"search":[]}}"#) }
            return json("{}")
        }
    }

    func testSingleMusicalMatchIsVerified() async throws {
        let h = http(search: [("Q1", "Queen")], entities: [entityJSON(id: "Q1", label: "Queen", claims: musician(extra: claimRef("P434", "x")))])
        let r = KnowledgeResolver(api: WikiApi(http: h, pause: noPause))
        let a = try await r.resolveArtist("Queen")
        XCTAssertEqual(a.status, .verified)
        XCTAssertEqual(a.entity?.qid, "Q1")
    }

    func testNonMusicalNameMatchIsNotFoundNotGuessed() async throws {
        let h = http(search: [("Q1", "Queen")], entities: [entityJSON(id: "Q1", label: "Queen", claims: "{" + claimRef("P31", "Q5") + "}")])
        let r = KnowledgeResolver(api: WikiApi(http: h, pause: noPause))
        let a = try await r.resolveArtist("Queen")
        XCTAssertEqual(a.status, .notFound)
    }

    func testTwoMusicalMatchesAreAmbiguousAndAssertNothing() async throws {
        let h = http(search: [("Q1", "Queen"), ("Q2", "Queen")], entities: [
            entityJSON(id: "Q1", label: "Queen", claims: musician()),
            entityJSON(id: "Q2", label: "Queen", claims: musician()),
        ])
        let r = KnowledgeResolver(api: WikiApi(http: h, pause: noPause))
        let a = try await r.resolveArtist("Queen")
        XCTAssertEqual(a.status, .ambiguous)
        XCTAssertNil(a.entity)
        XCTAssertEqual(a.candidates.map { $0.qid }, ["Q1", "Q2"])
    }

    func testNetworkFailureIsLookupFailedNeverNotFound() async {
        let r = KnowledgeResolver(api: WikiApi(http: FakeHTTP { _ in throw URLError(.notConnectedToInternet) }, pause: noPause))
        do {
            _ = try await r.resolve(Song(title: "Bohemian Rhapsody", artist: "Queen"))
            XCTFail("both lookups failed, so resolve should throw")
        } catch let e as WikiError {
            XCTAssertEqual(e, .lookupFailed(.offline))
        } catch { XCTFail("wrong error \(error)") }
    }

    func testMusicalEntityRules() {
        let r = KnowledgeResolver(api: WikiApi(http: FakeHTTP(), pause: noPause))
        func entity(_ claims: [String: [ClaimValue]]) -> WikidataEntity {
            WikidataEntity(id: "Q9", label: "X", description: nil, aliases: [], claims: claims, enwikiTitle: nil)
        }
        XCTAssertTrue(r.isMusicalEntity(entity(["P434": [.text("mbid")]])))
        XCTAssertTrue(r.isMusicalEntity(entity(["P31": [.ref("Q5")], "P106": [.ref("Q177220")]])))
        XCTAssertTrue(r.isMusicalEntity(entity(["P31": [.ref("Q215380")]])))
        XCTAssertFalse(r.isMusicalEntity(entity(["P31": [.ref("Q5")]])))
        XCTAssertFalse(r.isMusicalEntity(entity([:])))
    }
}

final class KnowledgeRepositoryTests: XCTestCase {
    private func musicalBody() -> (search: String, entities: String) {
        let claims = "{" + claimRef("P31", "Q5") + "," + claimRef("P106", "Q177220") + "}"
        return (searchBody([("Q1", "Queen")]), entitiesBody([entityJSON(id: "Q1", label: "Queen", claims: claims)]))
    }

    private func makeRepo(_ http: FakeHTTP, cache: KnowledgeCache = InMemoryKnowledgeCache(), now: @escaping () -> Date = Date.init) -> KnowledgeRepository {
        KnowledgeRepository(resolver: KnowledgeResolver(api: WikiApi(http: http, retries: 0, pause: noPause)), cache: cache, clock: now)
    }

    func testCompletedAnswerIsCachedAndReusedWithoutNetwork() async {
        let (s, e) = musicalBody()
        let http = FakeHTTP { url in
            let u = url.absoluteString
            if u.contains("wbsearchentities") { return u.contains("search=Queen") ? json(s) : json(#"{"search":[]}"#) }
            if u.contains("wbgetentities") { return json(e) }
            if u.contains("list=search") { return json(#"{"query":{"search":[]}}"#) }
            return json("{}", code: 404)
        }
        let repo = makeRepo(http)
        let song = Song(title: "Somebody Else", artist: "Queen")
        guard case .found(_, .live) = await repo.load(song) else { return XCTFail("expected live result") }
        let calls = http.requests.count
        guard case .found(_, .cacheFresh) = await repo.load(song) else { return XCTFail("expected fresh cache hit") }
        XCTAssertEqual(http.requests.count, calls)
    }

    func testFailedLookupIsNeverCached() async {
        let http = FakeHTTP { _ in throw URLError(.notConnectedToInternet) }
        let cache = InMemoryKnowledgeCache()
        let repo = makeRepo(http, cache: cache)
        let song = Song(title: "X", artist: "Y")
        let r = await repo.load(song)
        XCTAssertEqual(r, .failed(.offline))
        let stored = await cache.get("k3|" + song.key)
        XCTAssertNil(stored)
    }

    func testOfflineFallsBackToStaleCopy() async {
        let (s, e) = musicalBody()
        var offline = false
        let http = FakeHTTP { url in
            if offline { throw URLError(.notConnectedToInternet) }
            let u = url.absoluteString
            if u.contains("wbsearchentities") { return u.contains("search=Queen") ? json(s) : json(#"{"search":[]}"#) }
            if u.contains("wbgetentities") { return json(e) }
            if u.contains("list=search") { return json(#"{"query":{"search":[]}}"#) }
            return json("{}", code: 404)
        }
        var clock = Date(timeIntervalSince1970: 1_000_000)
        let repo = makeRepo(http, now: { clock })
        let song = Song(title: "Somebody Else", artist: "Queen")
        _ = await repo.load(song)
        offline = true
        clock = clock.addingTimeInterval(30 * 24 * 3600) // well past both TTLs
        guard case .found(_, .cacheStaleOffline) = await repo.load(song) else { return XCTFail("expected stale copy") }
    }
}

final class LyricsTests: XCTestCase {
    func testLrcParserSortsAndSkipsEmptyAndHandlesFractions() {
        let lines = LrcParser.parse("[00:12.50] second\n[00:01.5]first\n[00:20.00]\nnot a line\n[01:00]minute")
        XCTAssertEqual(lines, [LrcLine(timeMs: 1500, text: "first"), LrcLine(timeMs: 12500, text: "second"), LrcLine(timeMs: 60000, text: "minute")])
    }

    func testPrimaryArtistCutsFeaturing() {
        XCTAssertEqual(LyricsMatcher.primaryArtist("Drake feat. Rihanna"), "Drake")
        XCTAssertEqual(LyricsMatcher.primaryArtist("Simon, Garfunkel"), "Simon")
        XCTAssertEqual(LyricsMatcher.primaryArtist("Queen"), "Queen")
    }

    func testLrclibRecordParsing() {
        XCTAssertEqual(LrclibClient.parseRecord(["id": 1, "instrumental": true])?.instrumental, true)
        XCTAssertNil(LrclibClient.parseRecord(["trackName": "no id"]))
        let r = LrclibClient.parseRecord(["id": 2, "plainLyrics": "", "syncedLyrics": " ", "duration": 200.5])
        XCTAssertNil(r?.plain)
        XCTAssertNil(r?.synced)
        XCTAssertEqual(r?.durationSec, 200.5)
    }

    func testOnlineIsNeverCalledWhenNotAllowedInThisBuild() async {
        let http = FakeHTTP()
        let repo = LyricsRepository(local: LocalLrcStore(), http: http, onlineAllowedInThisBuild: false, onlineEnabled: { true })
        let state = await repo.load(songKey: "k", query: TrackQuery(title: "t", artist: "a"))
        XCTAssertEqual(state, .disabled)
        XCTAssertTrue(http.requests.isEmpty)
    }

    func testOnlineIsNeverCalledWhenToggleOff() async {
        let http = FakeHTTP()
        let repo = LyricsRepository(local: LocalLrcStore(), http: http, onlineAllowedInThisBuild: true, onlineEnabled: { false })
        let state = await repo.load(songKey: "k", query: TrackQuery(title: "t", artist: "a"))
        XCTAssertEqual(state, .disabled)
        XCTAssertTrue(http.requests.isEmpty)
    }

    func testLocalImportWinsOverEverythingAndNeedsNoNetwork() async {
        let http = FakeHTTP()
        let repo = LyricsRepository(local: LocalLrcStore(), http: http, onlineAllowedInThisBuild: false, onlineEnabled: { false })
        repo.importLocal(songKey: "k", lrcText: "[00:01.00]hello")
        let state = await repo.load(songKey: "k", query: TrackQuery(title: "t", artist: "a"))
        guard case .ready(let r) = state else { return XCTFail("expected ready") }
        XCTAssertEqual(r.source, .local)
        XCTAssertEqual(r.displayText, "hello")
        XCTAssertTrue(http.requests.isEmpty)
    }

    func testOnlineLookupWhenAllowed() async {
        let body = #"[{"id":1,"trackName":"Song","artistName":"Band","duration":200,"plainLyrics":"la la","syncedLyrics":"[00:01.00]la la"}]"#
        let http = FakeHTTP { _ in json(body) }
        let repo = LyricsRepository(local: LocalLrcStore(), http: http, onlineAllowedInThisBuild: true, onlineEnabled: { true })
        let state = await repo.load(songKey: "k", query: TrackQuery(title: "Song", artist: "Band"))
        guard case .ready(let r) = state else { return XCTFail("expected ready, got \(state)") }
        XCTAssertEqual(r.source, .online)
        XCTAssertEqual(r.syncedLines.count, 1)
    }
}

final class ExternalLinksTests: XCTestCase {
    func testCanonicalAppleLinkUsedOnlyWhenHostAllowed() {
        var s = Song(title: "A", artist: "B", appleMusicURL: "https://music.apple.com/us/album/x")
        XCTAssertEqual(ExternalLinks.url(.appleMusic, for: s)?.host, "music.apple.com")
        XCTAssertTrue(ExternalLinks.url(.appleMusic, for: s)!.absoluteString.contains("/album/x"))
        s.appleMusicURL = "https://evil.example/phish"
        XCTAssertTrue(ExternalLinks.url(.appleMusic, for: s)!.absoluteString.hasPrefix("https://music.apple.com/search?term="))
        s.appleMusicURL = "http://music.apple.com/x"
        XCTAssertTrue(ExternalLinks.url(.appleMusic, for: s)!.absoluteString.contains("search?term="))
    }

    func testSearchUrlsEncodePunctuation() {
        let s = Song(title: "Rock & Roll", artist: "AC/DC")
        let u = ExternalLinks.url(.youtube, for: s)!.absoluteString
        XCTAssertEqual(u, "https://www.youtube.com/results?search_query=Rock%20%26%20Roll%20AC%2FDC")
        XCTAssertEqual(ExternalLinks.searchText(Song(title: "  A   B ", artist: "C")), "A B C")
    }

    func testSafeWebUrls() {
        XCTAssertTrue(ExternalLinks.isSafeWebURL("https://en.wikipedia.org/wiki/Queen_(band)"))
        XCTAssertTrue(ExternalLinks.isSafeWebURL("https://www.wikidata.org/wiki/Q1"))
        XCTAssertFalse(ExternalLinks.isSafeWebURL("http://en.wikipedia.org/wiki/X"))
        XCTAssertFalse(ExternalLinks.isSafeWebURL("https://wikipedia.org.evil.example/x"))
        XCTAssertFalse(ExternalLinks.isSafeWebURL("javascript:alert(1)"))
    }
}

final class LibraryStoreTests: XCTestCase {
    func testFavoritesSurviveClearingHistoryAndDemoCleanup() {
        let store = LibraryStore(url: nil)
        let real = Song(title: "Real", artist: "A")
        let demo = Song(title: "Demo", artist: "B")
        store.record(real, source: .shazamkit)
        store.record(demo, source: .demo)
        store.setFavorite(LibrarySong(song: real, source: .shazamkit, timestamp: Date()), true)
        store.setFavorite(LibrarySong(song: demo, source: .demo, timestamp: Date()), true)
        XCTAssertTrue(store.isFavorite(real.key))

        store.clearDemoData()
        XCTAssertEqual(store.history().map { $0.song.title }, ["Real"])
        XCTAssertEqual(store.favorites().map { $0.song.title }, ["Real"])

        store.clearHistory()
        XCTAssertTrue(store.history().isEmpty)
        XCTAssertEqual(store.favorites().count, 1)
        XCTAssertEqual(store.find(real.key)?.song.title, "Real")
    }

    func testDeleteOneHistoryEntryAndPersistence() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("tmi-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = LibraryStore(url: url)
        let id1 = store.record(Song(title: "One", artist: "A"), source: .shazamkit)
        store.record(Song(title: "Two", artist: "A"), source: .shazamkit)
        store.deleteHistory(id1)

        let reloaded = LibraryStore(url: url)
        XCTAssertEqual(reloaded.history().map { $0.song.title }, ["Two"])
    }
}
