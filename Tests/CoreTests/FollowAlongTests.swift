import XCTest
@testable import TouchMusicIDCore

// MARK: LRC documents

final class LrcDocTests: XCTestCase {
    func testOffsetMultipleTimestampsWordTagsAndSort() {
        let raw = "[ar:Someone]\n[offset:500]\n[00:10.00][00:30.00]chorus\n[00:05.00]<00:05.00>verse <00:06.00>one\n"
        let doc = LrcParser.parseDoc(raw)
        XCTAssertEqual(doc.offsetMs, 500)
        XCTAssertEqual(doc.meta["ar"], "Someone")
        XCTAssertEqual(doc.lines, [
            LrcLine(timeMs: 4500, text: "verse one"),
            LrcLine(timeMs: 9500, text: "chorus"),
            LrcLine(timeMs: 29500, text: "chorus"),
        ])
    }

    func testMalformedAndEmptyInputGiveEmptyDocs() {
        XCTAssertTrue(LrcParser.parseDoc(nil).isEmpty)
        XCTAssertTrue(LrcParser.parseDoc("   \n").isEmpty)
        XCTAssertTrue(LrcParser.parseDoc("no timestamps here").isEmpty)
    }

    func testFindActiveIndexIsLastLineAtOrBeforePosition() {
        let lines = LrcParser.parse("[01:20.00]a\n[01:24.00]b\n[01:28.00]c")
        XCTAssertEqual(LrcParser.findActiveIndex(lines, posMs: 79_999), -1)
        XCTAssertEqual(LrcParser.findActiveIndex(lines, posMs: 80_000), 0)
        XCTAssertEqual(LrcParser.findActiveIndex(lines, posMs: 85_412), 1)
        XCTAssertEqual(LrcParser.findActiveIndex(lines, posMs: 999_999), 2)
    }

    func testSanityRejectsTimestampsPastTheTrackEnd() {
        let doc = LrcParser.parseDoc("[00:05.00]a\n[05:00.00]late")
        XCTAssertTrue(LyricsSanity.syncedLooksPlausible(doc, durationMs: nil))
        XCTAssertTrue(LyricsSanity.syncedLooksPlausible(doc, durationMs: 300_000))
        XCTAssertFalse(LyricsSanity.syncedLooksPlausible(doc, durationMs: 200_000))
        XCTAssertFalse(LyricsSanity.syncedLooksPlausible(LrcDoc(lines: []), durationMs: nil))
    }
}

// MARK: Clock and sync policy (ported from Android's SyncControllerTest)

final class SyncControllerTests: XCTestCase {
    private func fix(_ song: Double, _ at: Double, rate: Double = 1.0) -> PositionFix { PositionFix(songMs: song, atMono: at, rate: rate) }

    /// Exact matches that leave the early-lock window and put the clock at exactly 40_000 ms at mono 40_000.
    private func exitEarlyPhase(_ c: SyncController) {
        for t in [0.0, 10_000, 20_000, 30_000, 40_000] { c.onFix(fix(t, t)) }
    }

    func testIdentifiedHalfwayThroughStartsAtTheMeasuredPosition() {
        let c = SyncController()
        XCTAssertEqual(c.onFix(fix(83_412, 1_000)).kind, "INIT")
        XCTAssertEqual(c.state, .following)
        XCTAssertEqual(c.clock.positionMs(1_000)!, 83_412, accuracy: 0.001)
        XCTAssertEqual(c.clock.positionMs(3_000)!, 85_412, accuracy: 0.001)
        let lines = LrcParser.parse("[01:20.00]a\n[01:24.00]b\n[01:28.00]c")
        XCTAssertEqual(LrcParser.findActiveIndex(lines, posMs: Int(c.clock.positionMs(3_000)!)), 1)
    }

    func testSmallDisagreementsAreIgnoredAndMediumOnesHalved() {
        let c = SyncController(); exitEarlyPhase(c)
        XCTAssertEqual(c.onFix(fix(50_100, 50_000)).kind, "AGREE")
        XCTAssertEqual(c.clock.positionMs(50_000)!, 50_000, accuracy: 0.001)
        let e = c.onFix(fix(51_000, 50_000))
        XCTAssertEqual(e.kind, "HALF")
        XCTAssertEqual(e.appliedMs, 500, accuracy: 0.001)
        XCTAssertEqual(c.clock.positionMs(50_000)!, 50_500, accuracy: 0.001)
    }

    func testALargeJumpNeedsASecondConsistentMeasurement() {
        let c = SyncController(); exitEarlyPhase(c)
        XCTAssertEqual(c.onFix(fix(70_000, 50_000)).kind, "PENDING")
        XCTAssertEqual(c.clock.positionMs(50_000)!, 50_000, accuracy: 0.001, "must not move on a single outlier")
        XCTAssertEqual(c.onFix(fix(78_300, 58_000)).kind, "JUMP")
        XCTAssertEqual(c.clock.positionMs(58_000)!, 78_300, accuracy: 0.001)
        XCTAssertEqual(c.state, .following)
    }

    func testASingleFalseMatchFromARepeatedChorusIsDiscarded() {
        let c = SyncController(); exitEarlyPhase(c)
        XCTAssertEqual(c.onFix(fix(90_000, 50_000)).kind, "PENDING")
        XCTAssertEqual(c.onFix(fix(60_050, 60_000)).kind, "AGREE")
        XCTAssertFalse(c.hasPending)
        XCTAssertEqual(c.clock.positionMs(60_000)!, 60_000, accuracy: 0.001)
    }

    func testRepeatedContradictionsEndInUnreliableAndALaterGoodFixRecovers() {
        let c = SyncController(); exitEarlyPhase(c)
        c.onFix(fix(90_000, 50_000))   // pending
        c.onFix(fix(130_000, 60_000))  // contradicts the pending one
        c.onFix(fix(10_000, 70_000))   // contradicts again
        XCTAssertEqual(c.state, .unreliable)
        XCTAssertEqual(c.confidence(70_000), 0)
        XCTAssertEqual(c.onFix(fix(81_000, 80_000)).kind, "RECOVER")
        XCTAssertEqual(c.state, .following)
        XCTAssertEqual(c.clock.positionMs(80_000)!, 81_000, accuracy: 0.001)
    }

    func testSilenceHoldsThePositionAndSoundResumesFromThere() {
        let c = SyncController(); c.onFix(fix(10_000, 0))
        c.onSilenceStart(sinceMono: 5_000)
        XCTAssertEqual(c.state, .hold)
        XCTAssertEqual(c.clock.positionMs(9_000)!, 15_000, accuracy: 0.001)
        XCTAssertEqual(c.clock.positionMs(60_000)!, 15_000, accuracy: 0.001)
        XCTAssertEqual(c.onFix(fix(99_000, 9_500)).kind, "IGNORED_HOLD")
        c.onSoundResume(nowMono: 60_000)
        XCTAssertEqual(c.state, .following)
        XCTAssertEqual(c.clock.positionMs(60_500)!, 15_500, accuracy: 0.001)
        XCTAssertEqual(c.onFix(fix(120_000, 62_000)).kind, "RESUME")
        XCTAssertEqual(c.clock.positionMs(62_000)!, 120_000, accuracy: 0.001)
    }

    func testSilenceIsIgnoredBeforeAnythingIsTracked() {
        let c = SyncController(); c.onSilenceStart(sinceMono: 1_000)
        XCTAssertEqual(c.state, .searching)
    }

    func testVerificationGoesStale() {
        let c = SyncController(); c.onFix(fix(10_000, 0))
        c.evaluate(100_000); XCTAssertEqual(c.state, .following)
        c.evaluate(151_000); XCTAssertEqual(c.state, .unreliable)
        XCTAssertEqual(c.confidence(151_000), 0)
    }

    func testConfidenceGrowsDecaysAndDropsWithADisputedFix() {
        let c = SyncController(); c.onFix(fix(0, 0))
        let c1 = c.confidence(0)
        c.onFix(fix(10_000, 10_000)); c.onFix(fix(20_000, 20_000))
        let c3 = c.confidence(20_000)
        XCTAssertLessThan(c1, c3)
        XCTAssertLessThan(c.confidence(100_000), c3)
        c.onFix(fix(80_000, 21_000))
        XCTAssertLessThan(c.confidence(21_000), c3)
    }

    func testPlaybackSpeedFromSkewAndNudgeAndReset() {
        let c = SyncController(); c.onFix(fix(0, 0, rate: 1.05))
        XCTAssertEqual(c.clock.positionMs(10_000)!, 10_500, accuracy: 0.001)

        let d = SyncController(); d.onFix(fix(10_000, 0))
        d.nudge(250); XCTAssertEqual(d.clock.positionMs(0)!, 10_250, accuracy: 0.001)
        d.nudge(-100_000); XCTAssertEqual(d.clock.nudgeMs, -5_000, accuracy: 0.001)
        XCTAssertEqual(d.clock.positionMs(0)!, 5_000, accuracy: 0.001)
        d.reset(); XCTAssertEqual(d.state, .searching); XCTAssertNil(d.clock.positionMs(1))
    }

    func testRequireFreshFixReplacesTheAnchorWithoutDamping() {
        let c = SyncController(); c.onFix(fix(10_000, 0))
        c.requireFreshFix()
        XCTAssertEqual(c.onFix(fix(31_000, 20_000)).kind, "RESUME")
        XCTAssertEqual(c.clock.positionMs(20_000)!, 31_000, accuracy: 0.001)
    }

    func testClockPositionIsDerivedNotAccumulated() {
        let k = LyricClock(); k.setAnchor(songMs: 1_000, atMono: 500, rate: 1)
        XCTAssertEqual(k.positionMs(10_500), k.positionMs(10_500))
        XCTAssertEqual(k.positionMs(10_500)!, 11_000, accuracy: 0.001)
    }
}

// MARK: Positioning

final class PositioningTests: XCTestCase {
    private func raw(offset: Double? = 60_000, predicted: Double? = 63_000, sig: Double = 8_000) -> RawFix {
        RawFix(sessionId: 1, title: "t", artist: "a", isrc: nil, appleMusicId: nil, matchOffsetMs: offset, predictedMs: predicted,
               frequencySkew: nil, sigDurationMs: sig, readMonoMs: 20_000, lastFedEndMono: 19_900, sessionStartMono: 10_000)
    }

    func testThreeEstimatorsFollowTheirDocumentedFormulas() {
        let e = Positioning.estimate(raw())
        XCTAssertEqual(e.a!, 63_000, accuracy: 0.001)
        XCTAssertEqual(e.b!, 60_000 + 8_000 + 100, accuracy: 0.001)    // offset + signature + time since last chunk
        XCTAssertEqual(e.c!, 60_000 + 10_000, accuracy: 0.001)         // offset + read time - session start
        XCTAssertEqual(e.pick(.b), e.b)
    }

    func testMissingOrImplausibleValuesAreNil() {
        XCTAssertNil(Positioning.estimate(raw(offset: nil)).b)
        XCTAssertNil(Positioning.estimate(raw(predicted: 1.8e12)).a)
        XCTAssertNil(Positioning.estimate(raw(predicted: -5_000)).a)
        XCTAssertEqual(Positioning.estimate(raw(predicted: -500)).a, 0)
    }

    func testRateIgnoresTinyOrWildSkew() {
        XCTAssertEqual(Positioning.rate(nil), 1)
        XCTAssertEqual(Positioning.rate(0.001), 1)
        XCTAssertEqual(Positioning.rate(0.5), 1)
        XCTAssertEqual(Positioning.rate(0.05), 1.05, accuracy: 1e-9)
        XCTAssertEqual(Positioning.advance(atRead: 5_000, readMono: 100, nowMono: 400), 5_300)
    }
}

// MARK: Matching (ported from Android's LyricsMatcherTest)

final class LyricsMatcherTests: XCTestCase {
    private func rec(_ id: Int64, _ title: String, _ artist: String, album: String? = nil, dur: Double? = 354,
                     synced: String? = "[00:01.00]x", plain: String? = "x", instrumental: Bool = false) -> LrclibRecord {
        LrclibRecord(id: id, trackName: title, artistName: artist, albumName: album, durationSec: dur,
                     instrumental: instrumental, plain: plain, synced: synced)
    }
    private let q = TrackQuery(title: "Bohemian Rhapsody", artist: "Queen", album: "A Night at the Opera", durationSec: 354)

    func testCleanTitleRemovesDecorationButKeepsVersionWords() {
        XCTAssertEqual(LyricsMatcher.cleanTitle("Bohemian Rhapsody - Remastered 2011"), "Bohemian Rhapsody")
        XCTAssertEqual(LyricsMatcher.cleanTitle("Song (Remastered 2009)"), "Song")
        XCTAssertEqual(LyricsMatcher.cleanTitle("Song (feat. Someone)"), "Song")
        XCTAssertEqual(LyricsMatcher.cleanTitle("Heroes - 2017 Remaster"), "Heroes")
        XCTAssertEqual(LyricsMatcher.cleanTitle("Song (Live at Wembley)"), "Song (Live at Wembley)")
        XCTAssertEqual(LyricsMatcher.cleanTitle("Song - Remix"), "Song - Remix")
        XCTAssertEqual(LyricsMatcher.cleanTitle("(Remastered)"), "(Remastered)")
    }

    func testVersionWordsComeFromTheRawTitleAsWholeWords() {
        XCTAssertEqual(LyricsMatcher.versionWords("Song (Live)"), ["live"])
        XCTAssertEqual(LyricsMatcher.versionWords("Song - Remix"), ["remix"])
        XCTAssertEqual(LyricsMatcher.versionWords("Song - Radio Edit"), [])
        XCTAssertEqual(LyricsMatcher.versionWords("Alive"), [])
    }

    func testTheSameRecordingMatchesAndRemasteredTitlesStillMatch() {
        let s = LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Queen", album: "A Night at the Opera", dur: 354.5))
        XCTAssertNil(s.rejectedReason)
        XCTAssertGreaterThan(s.score, 1.0)
        var remastered = q; remastered.title = "Bohemian Rhapsody - Remastered 2011"
        XCTAssertNil(LyricsMatcher.score(remastered, rec(1, "Bohemian Rhapsody", "Queen")).rejectedReason)
    }

    func testLiveRemixAndOtherVersionsAreRejectedInBothDirections() {
        let hey = TrackQuery(title: "Hey Jude", artist: "The Beatles", durationSec: 431)
        XCTAssertTrue(LyricsMatcher.score(hey, rec(1, "Hey Jude (Live)", "The Beatles", dur: 431)).rejectedReason!.hasPrefix("version mismatch"))
        var live = hey; live.title = "Hey Jude (Live)"
        XCTAssertTrue(LyricsMatcher.score(live, rec(1, "Hey Jude", "The Beatles", dur: 431)).rejectedReason!.hasPrefix("version mismatch"))
        XCTAssertNotNil(LyricsMatcher.score(hey, rec(1, "Hey Jude - Remix", "The Beatles", dur: 431)).rejectedReason)
        XCTAssertNotNil(LyricsMatcher.score(hey, rec(1, "Hey Jude (Karaoke Version)", "The Beatles", dur: 431)).rejectedReason)
        XCTAssertNil(LyricsMatcher.score(live, rec(1, "Hey Jude (Live)", "The Beatles", dur: 431)).rejectedReason)
    }

    func testDifferentArtistsRejectedButFeaturedAndJoinedNamesMatch() {
        XCTAssertNotNil(LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Queen Latifah")).rejectedReason)
        XCTAssertNotNil(LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Panic! at the Disco")).rejectedReason)
        let funk = TrackQuery(title: "Uptown Funk", artist: "Mark Ronson, Bruno Mars", durationSec: 270)
        XCTAssertNil(LyricsMatcher.score(funk, rec(1, "Uptown Funk", "Mark Ronson", dur: 270)).rejectedReason)
        XCTAssertNil(LyricsMatcher.score(funk, rec(1, "Uptown Funk (feat. Bruno Mars)", "Mark Ronson & Bruno Mars", dur: 270)).rejectedReason)
        let sg = TrackQuery(title: "The Sound of Silence", artist: "Simon & Garfunkel", durationSec: 185)
        XCTAssertNil(LyricsMatcher.score(sg, rec(1, "The Sound of Silence", "Simon & Garfunkel", dur: 185)).rejectedReason)
    }

    func testArtistsWithPunctuationAreNotMangled() {
        XCTAssertEqual(LyricsMatcher.primaryArtist("AC/DC"), "AC/DC")
        XCTAssertEqual(LyricsMatcher.primaryArtist("Florence + the Machine"), "Florence + the Machine")
        XCTAssertEqual(LyricsMatcher.primaryArtist("Post Malone feat. Swae Lee"), "Post Malone")
        XCTAssertEqual(LyricsMatcher.searchArtists("Simon & Garfunkel"), ["Simon & Garfunkel", "Simon"])
        XCTAssertEqual(LyricsMatcher.searchArtists("Queen"), ["Queen"])
        XCTAssertEqual(LyricsMatcher.artistSimilarity("Beyoncé", "Beyonce"), 1.0)
    }

    func testDurationDriftAndRanking() {
        XCTAssertNotNil(LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Queen", dur: 400)).rejectedReason)
        let near = LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Queen", dur: 354.5))
        let mid = LyricsMatcher.score(q, rec(2, "Bohemian Rhapsody", "Queen", dur: 358))
        let far = LyricsMatcher.score(q, rec(3, "Bohemian Rhapsody", "Queen", dur: 362))
        XCTAssertTrue(near.score > mid.score && mid.score > far.score)
        var noDur = q; noDur.durationSec = nil
        XCTAssertNil(LyricsMatcher.score(noDur, rec(4, "Bohemian Rhapsody", "Queen", dur: 500)).rejectedReason)

        let ranked = LyricsMatcher.rank(q, [
            rec(1, "Bohemian Rhapsody", "Queen", synced: nil),
            rec(2, "Bohemian Rhapsody", "Queen", instrumental: true),
            rec(3, "Bohemian Rhapsody", "Queen"),
            rec(3, "Bohemian Rhapsody", "Queen"),
            rec(9, "Other Song", "Someone"),
        ])
        XCTAssertEqual(ranked.map { $0.record.id }, [3, 1, 2, 9])
        XCTAssertEqual(LyricsMatcher.best(ranked)?.record.id, 3)
        XCTAssertNil(LyricsMatcher.best(LyricsMatcher.rank(q, [rec(9, "Other Song", "Someone")])))
    }

    func testSameTrackDecidesWhetherTheHeardMusicIsTheOpenSong() {
        XCTAssertTrue(LyricsMatcher.sameTrack(titleA: "Wine Into Whiskey", artistA: "Tucker Wetmore", titleB: "Wine Into Whiskey", artistB: "Tucker Wetmore"))
        XCTAssertTrue(LyricsMatcher.sameTrack(titleA: "Bohemian Rhapsody - Remastered 2011", artistA: "Queen", titleB: "Bohemian Rhapsody", artistB: "Queen"))
        XCTAssertFalse(LyricsMatcher.sameTrack(titleA: "Hey Jude", artistA: "The Beatles", titleB: "Hey Jude (Live)", artistB: "The Beatles"))
        XCTAssertFalse(LyricsMatcher.sameTrack(titleA: "Hey Jude", artistA: "The Beatles", titleB: "Let It Be", artistB: "The Beatles"))
    }
}

// MARK: LRCLIB client, metadata and repository

final class LrclibFlowTests: XCTestCase {
    private func recordJSON(id: Int, title: String = "Song", artist: String = "Band", dur: Double = 200, synced: String? = "[00:05.00]a\n[00:10.00]b") -> String {
        let s = synced.map { #""syncedLyrics":"\#($0.replacingOccurrences(of: "\n", with: "\\n"))","# } ?? ""
        return #"{"id":\#(id),"trackName":"\#(title)","artistName":"\#(artist)","duration":\#(dur),\#(s)"plainLyrics":"plain text","instrumental":false}"#
    }

    func testFailuresAreNotReportedAsNotFound() async {
        let http = FakeHTTP { _ in json("{}", code: 500) }
        let r = await LrclibClient(http: http, pause: noPause).lookup(TrackQuery(title: "Song", artist: "Band"))
        guard case .failed(let kind, _) = r else { return XCTFail("expected failed, got \(r)") }
        XCTAssertEqual(kind, "SERVER")
    }

    func testPlain404sMeanNotFound() async {
        let http = FakeHTTP { _ in json("{}", code: 404) }
        let r = await LrclibClient(http: http, pause: noPause).lookup(TrackQuery(title: "Song", artist: "Band"))
        guard case .notFound = r else { return XCTFail("expected notFound, got \(r)") }
    }

    func testWrongVersionCandidatesAreNeverChosen() async {
        let body = "[" + recordJSON(id: 1, title: "Song (Live)") + "]"
        let r = await LrclibClient(http: FakeHTTP { _ in json(body) }, pause: noPause).lookup(TrackQuery(title: "Song", artist: "Band"))
        guard case .notFound(let ranked, _) = r else { return XCTFail("expected notFound, got \(r)") }
        XCTAssertTrue(ranked.first!.rejectedReason!.hasPrefix("version mismatch"))
    }

    func testMetadataParsers() {
        let ok = ITunesClient.parse(Data(#"{"resultCount":1,"results":[{"trackTimeMillis":200000,"collectionName":"Album X"}]}"#.utf8), source: "t")
        XCTAssertEqual(ok, .found(TrackMeta(durationMs: 200_000, album: "Album X", source: "t")))
        XCTAssertEqual(ITunesClient.parse(Data(#"{"resultCount":0,"results":[]}"#.utf8), source: "t"), .notFound)
        guard case .failed = ITunesClient.parse(Data("nope".utf8), source: "t") else { return XCTFail("expected failure") }
        guard case .failed = ITunesClient.parse(Data(#"{"results":[]}"#.utf8), source: "t") else { return XCTFail("missing resultCount is a failure") }

        let mb = MusicBrainzClient.parseIsrc(Data(#"{"recordings":[{"length":200000,"releases":[{"title":"Alb"}]},{"length":202000},{"length":199000}]}"#.utf8))
        guard case .found(let m) = mb else { return XCTFail("expected found") }
        XCTAssertEqual(m.durationMs, 200_000)   // median
        XCTAssertEqual(m.album, "Alb")
        XCTAssertEqual(MusicBrainzClient.parseIsrc(Data(#"{"recordings":[]}"#.utf8)), .notFound)
    }

    func testRepositoryUsesItunesDurationAndKeepsPlausibleSyncedLyrics() async {
        let body = "[" + recordJSON(id: 7) + "]"
        let http = FakeHTTP { url in
            let u = url.absoluteString
            if u.contains("itunes.apple.com") { return json(#"{"resultCount":1,"results":[{"trackTimeMillis":200000,"collectionName":"Album X"}]}"#) }
            if u.contains("/api/get") { return json("{}", code: 404) }
            return json(body)
        }
        let repo = LyricsRepository(local: LocalLrcStore(), http: http, onlineAllowedInThisBuild: true, onlineEnabled: { true })
        let state = await repo.load(songKey: "k", query: TrackQuery(title: "Song", artist: "Band", appleMusicId: "123"))
        guard case .ready(let r) = state else { return XCTFail("expected ready, got \(state)") }
        XCTAssertEqual(r.durationMs, 200_000)
        XCTAssertEqual(r.syncedLines.map { $0.text }, ["a", "b"])
        XCTAssertTrue(r.canFollow)
        XCTAssertTrue(http.requests.contains { $0.contains("album_name=Album%20X") && $0.contains("duration=200") })
    }

    func testRepositoryRejectsSyncedTimestampsThatRunPastTheTrackEnd() async {
        let body = "[" + recordJSON(id: 7, synced: "[00:05.00]a\n[05:00.00]late") + "]"
        let http = FakeHTTP { url in
            let u = url.absoluteString
            if u.contains("itunes.apple.com") { return json(#"{"resultCount":1,"results":[{"trackTimeMillis":200000}]}"#) }
            if u.contains("/api/get") { return json("{}", code: 404) }
            return json(body)
        }
        let repo = LyricsRepository(local: LocalLrcStore(), http: http, onlineAllowedInThisBuild: true, onlineEnabled: { true })
        let state = await repo.load(songKey: "k", query: TrackQuery(title: "Song", artist: "Band", appleMusicId: "123"))
        guard case .ready(let r) = state else { return XCTFail("expected ready, got \(state)") }
        XCTAssertFalse(r.canFollow)
        XCTAssertEqual(r.plain, "plain text")
        XCTAssertTrue(r.info.contains("rejected"))
    }

    func testSpacedFetcherKeepsRequestsApart() async throws {
        let http = FakeHTTP()
        let spaced = SpacedFetcher(http, minInterval: 0.3)
        let url = URL(string: "https://example.org/x")!
        let start = Date()
        _ = try await spaced.get(url)
        _ = try await spaced.get(url)
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(start), 0.25)
        XCTAssertEqual(http.requests.count, 2)
    }
}
