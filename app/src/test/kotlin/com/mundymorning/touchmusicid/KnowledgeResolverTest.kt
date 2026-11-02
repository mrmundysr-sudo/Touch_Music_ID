package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.knowledge.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KnowledgeResolverTest {
    private val queen = entityJson(
        "Q15862", "Queen", "British rock band",
        claims = mapOf(
            "P31" to listOf("Q5741069"), "P434" to listOf("mbid"), "P136" to listOf("Q11399"),
            "P571" to listOf(com.mundymorning.touchmusicid.knowledge.ClaimValue.Time("+1970-00-00T00:00:00Z", 9)),
            "P740" to listOf("Q84"),
        ),
        enwiki = "Queen (band)",
    )
    private val rock = entityJson("Q11399", "rock music")
    private val london = entityJson("Q84", "London")
    private val chessQueen = entityJson("Q5", "Queen", "chess piece", claims = mapOf("P31" to listOf("Q1")))
    private val bohemian = entityJson(
        "Q187655", "Bohemian Rhapsody", "1975 song by Queen",
        claims = mapOf(
            "P31" to listOf("Q7366"), "P175" to listOf("Q15862"),
            "P577" to listOf(com.mundymorning.touchmusicid.knowledge.ClaimValue.Time("+1975-10-31T00:00:00Z", 11)),
            "P361" to listOf("Q207"),
        ),
        enwiki = "Bohemian Rhapsody",
    )
    private val album = entityJson("Q207", "A Night at the Opera")

    private fun wiki(extra: Map<String, JSONObjectAlias> = emptyMap(), summaries: Map<String, String> = emptyMap(), articleSearch: String = wpSearchJson()) =
        FakeWiki(
            db = listOf(queen, rock, london, chessQueen, bohemian, album).associateBy { it.getString("id") } + extra,
            entitySearch = mapOf(
                "Queen" to searchJson(Triple("Q5", "Queen", "chess piece"), Triple("Q15862", "Queen", "British rock band")),
                "Bohemian Rhapsody" to searchJson(Triple("Q187655", "Bohemian Rhapsody", "1975 song by Queen")),
            ),
            summaries = mapOf(
                "Queen_(band)" to summaryJson("Queen (band)", "Queen are a British rock band formed in London in 1970.", "British rock band"),
                "Bohemian_Rhapsody" to summaryJson("Bohemian Rhapsody", "\"Bohemian Rhapsody\" is a song by the British rock band Queen."),
            ) + summaries,
            articleSearch = articleSearch,
        )

    @Test fun verifiesArtistOnlyWhenNameMatchesAndItIsMusical() = runTest {
        val r = KnowledgeResolver(WikiApi(wiki().http)).resolveArtist("Queen")
        assertEquals(MatchStatus.VERIFIED, r.status)
        val e = r.entity!!
        assertEquals("Q15862", e.qid)
        assertTrue(e.extract!!.startsWith("Queen are a British rock band"))
        assertEquals("https://en.wikipedia.org/wiki/Queen_(band)", e.wikipediaUrl)
        assertEquals("https://www.wikidata.org/wiki/Q15862", e.wikidataUrl)
        val facts = e.facts.associate { it.label to it.value }
        assertEquals("1970", facts["Formed"]); assertEquals("rock music", facts["Genre"]); assertEquals("London", facts["Formed in"])
        assertTrue(e.facts.all { it.source == FactSource.WIKIDATA && it.url != null })
        assertTrue(e.basis.contains("MusicBrainz"))
    }

    @Test fun nonMusicalNamesakeIsRejected() = runTest {
        val w = wiki()
        val r = KnowledgeResolver(WikiApi(w.http)).resolveArtist("Queen")
        assertFalse("chess piece must not qualify", r.entity?.qid == "Q5")
    }

    @Test fun twoMusicalNamesakesAreAmbiguousAndAssertNothing() = runTest {
        val other = entityJson("Q99", "Queen", "American singer", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q177220")))
        val w = FakeWiki(
            db = mapOf("Q15862" to queen, "Q99" to other),
            entitySearch = mapOf("Queen" to searchJson(Triple("Q15862", "Queen", null), Triple("Q99", "Queen", null))),
        )
        val r = KnowledgeResolver(WikiApi(w.http)).resolveArtist("Queen")
        assertEquals(MatchStatus.AMBIGUOUS, r.status)
        assertNull(r.entity)
        assertEquals(setOf("Q15862", "Q99"), r.candidates.map { it.qid }.toSet())
    }

    @Test fun userChoiceResolvesAmbiguity() = runTest {
        val w = wiki()
        val r = KnowledgeResolver(WikiApi(w.http)).resolveArtist("Queen", chosenQid = "Q15862")
        assertEquals(MatchStatus.VERIFIED, r.status)
    }

    @Test fun humanWithMusicOccupationQualifiesWithoutMusicBrainz() = runTest {
        val p = entityJson("Q7", "Adele", "English singer", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q177220")))
        val w = FakeWiki(mapOf("Q7" to p), mapOf("adele" to searchJson(Triple("Q7", "Adele", null))))
        assertEquals(MatchStatus.VERIFIED, KnowledgeResolver(WikiApi(w.http)).resolveArtist("adele").status)
    }

    @Test fun nameMustMatchExactlyNotJustPrefix() = runTest {
        val p = entityJson("Q8", "Queen Latifah", "rapper", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q2252262")))
        val w = FakeWiki(mapOf("Q8" to p), mapOf("Queen" to searchJson(Triple("Q8", "Queen Latifah", null))))
        assertEquals(MatchStatus.NOT_FOUND, KnowledgeResolver(WikiApi(w.http)).resolveArtist("Queen").status)
    }

    @Test fun songVerifiedByTitleAndPerformer() = runTest {
        val w = wiki()
        val resolver = KnowledgeResolver(WikiApi(w.http))
        val (artist, song) = resolver.resolve(Song("Bohemian Rhapsody", "Queen"))
        assertEquals(MatchStatus.VERIFIED, artist.status)
        assertEquals(MatchStatus.VERIFIED, song.status)
        val facts = song.entity!!.facts.associate { it.label to it.value }
        assertEquals("1975-10-31", facts["Released"])
        assertEquals("A Night at the Opera", facts["Album / release"])
        assertTrue(song.entity!!.basis.contains("performer"))
    }

    @Test fun songWithDifferentPerformerIsNotAccepted() = runTest {
        // Same title, but performed by someone else: must not attach Queen's article to another artist's recognition.
        val w = wiki(articleSearch = wpSearchJson())
        val (_, song) = KnowledgeResolver(WikiApi(w.http)).resolve(Song("Bohemian Rhapsody", "Some Cover Band"))
        assertEquals(MatchStatus.NOT_FOUND, song.status)
        assertNull(song.entity)
    }

    @Test fun wikipediaFallbackRequiresArtistInArticleText() = runTest {
        val w = FakeWiki(
            db = emptyMap(), entitySearch = emptyMap(),
            summaries = mapOf(
                "Obscure_Song" to summaryJson("Obscure Song", "\"Obscure Song\" is a 1999 single by The Testers.", "1999 single"),
                "Obscure_Song_(other)" to summaryJson("Obscure Song (other)", "A different thing by nobody relevant."),
            ),
            articleSearch = wpSearchJson("Obscure Song (other)", "Obscure Song"),
        )
        val r = KnowledgeResolver(WikiApi(w.http))
        val ok = r.resolveSong(Song("Obscure Song", "The Testers"), Lookup(MatchStatus.NOT_FOUND))
        assertEquals(MatchStatus.VERIFIED, ok.status)
        assertNull("no wikidata record, so no qid is claimed", ok.entity!!.qid)
        val wrongArtist = r.resolveSong(Song("Obscure Song", "Totally Else"), Lookup(MatchStatus.NOT_FOUND))
        assertEquals(MatchStatus.NOT_FOUND, wrongArtist.status)
    }

    @Test fun disambiguationPagesAreNeverUsed() = runTest {
        val w = FakeWiki(
            db = emptyMap(), entitySearch = emptyMap(),
            summaries = mapOf("Hey" to summaryJson("Hey", "Hey by Nobody may refer to:", type = "disambiguation")),
            articleSearch = wpSearchJson("Hey"),
        )
        val r = KnowledgeResolver(WikiApi(w.http)).resolveSong(Song("Hey", "Nobody"), Lookup(MatchStatus.NOT_FOUND))
        assertEquals(MatchStatus.NOT_FOUND, r.status)
    }

    @Test fun sameBirthplaceAndFormationPlaceIsShownOnce() = runTest {
        val p = entityJson(
            "Q7542", "Prince", "American musician",
            claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q177220"), "P19" to listOf("Q84"), "P740" to listOf("Q84")),
        )
        val w = FakeWiki(mapOf("Q7542" to p, "Q84" to london), mapOf("Prince" to searchJson(Triple("Q7542", "Prince", null))))
        val facts = KnowledgeResolver(WikiApi(w.http)).resolveArtist("Prince").entity!!.facts
        assertEquals(1, facts.count { it.value == "London" })
        assertTrue(facts.any { it.label == "Origin" })
    }

    @Test fun blankArtistAssertsNothing() = runTest {
        assertEquals(MatchStatus.NOT_FOUND, KnowledgeResolver(WikiApi(wiki().http)).resolveArtist("  ").status)
    }
}

private typealias JSONObjectAlias = org.json.JSONObject
