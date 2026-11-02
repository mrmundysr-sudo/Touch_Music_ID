package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.knowledge.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression for artists whose Wikidata record has only a language-neutral (`mul`) label, so an English-only request returned no
 * label and the exact-name check failed (Beyoncé, Post Malone, Kendrick Lamar, The Weeknd, Bad Bunny in the live diagnostic).
 * Fixtures mirror the real response shape; live truth is checked by the CI diagnostic job.
 */
class ArtistLabelResolutionTest {
    private fun musician(id: String, label: String, lang: String, vararg extra: Pair<String, List<Any>>, enwiki: String? = label, desc: String = "singer") =
        entityJson(id, label, desc, claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q177220", "Q753110"), "P434" to listOf("mb"), "P1902" to listOf("sp")) + extra.toMap(), enwiki = enwiki, labelLanguage = lang)

    private fun world(name: String, real: org.json.JSONObject, vararg decoys: org.json.JSONObject): FakeWiki {
        val all = listOf(real) + decoys
        return FakeWiki(
            db = all.associateBy { it.getString("id") },
            entitySearch = mapOf(name to searchJson(*all.map { Triple(it.getString("id"), name, null as String?) }.toTypedArray())),
        )
    }

    private val albumDecoy = entityJson("Q900", "Decoy", null, claims = mapOf("P31" to listOf("Q482994")), enwiki = null)
    private val discographyDecoy = entityJson("Q901", "Decoy discography", null, claims = mapOf("P31" to listOf("Q104635718")))

    private suspend fun resolve(w: FakeWiki, name: String) = KnowledgeResolver(WikiApi(w.http, pause = {})).resolveArtist(name)

    // Stage names / formats from the live run, all with only a language-neutral label.
    private val names = listOf(
        "Beyoncé", "Post Malone", "Kendrick Lamar", "The Weeknd", "Bad Bunny", "Morgan Wallen", "P!nk", "AC/DC", "Sinéad O'Connor",
        "deadmau5", "Jay-Z", "Guns N' Roses", "Panic! at the Disco", "Mötley Crüe", "will.i.am", "Florence + the Machine", "Kesha",
    )

    @Test fun languageNeutralOnlyLabelsResolveToTheRealArtist() = runTest {
        for ((i, n) in names.withIndex()) {
            val real = musician("Q${100 + i}", n, "mul")
            val r = resolve(world(n, real, albumDecoy, discographyDecoy), n)
            assertEquals("$n", MatchStatus.VERIFIED, r.status)
            assertEquals(n, "Q${100 + i}", r.entity!!.qid)
            assertEquals(n, r.entity!!.name)
        }
    }

    @Test fun englishLabelStillWorksAndWinsOverTheNeutralOne() = runTest {
        val both = entityJson("Q1", "Morgan Wallen", "singer", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q177220")), labelLanguage = "en").also {
            it.getJSONObject("labels").put("mul", org.json.JSONObject().put("language", "mul").put("value", "Something Else"))
        }
        assertEquals(MatchStatus.VERIFIED, resolve(world("Morgan Wallen", both), "Morgan Wallen").status)
        assertEquals("en label is used when present; mul is only a fallback", MatchStatus.NOT_FOUND, resolve(world("Something Else", both), "Something Else").status)
    }

    @Test fun diacriticAndPunctuationVariantsOfTheNameStillMatchExactly() = runTest {
        for ((query, label) in listOf("Beyonce" to "Beyoncé", "AC-DC" to "AC/DC", "Sinead O'Connor" to "Sinéad O'Connor", "Panic at the Disco" to "Panic! at the Disco")) {
            val r = resolve(world(query, musician("Q1", label, "mul")), query)
            assertEquals("$query ~ $label", MatchStatus.VERIFIED, r.status)
        }
    }

    // ---- exact-name verification is NOT weakened ----
    @Test fun aDifferentNameInTheNeutralLabelIsRejected() = runTest {
        assertEquals(MatchStatus.NOT_FOUND, resolve(world("Beyoncé", musician("Q1", "Beyoncé Knowles-Carter", "mul")), "Beyoncé").status)
        assertEquals(MatchStatus.NOT_FOUND, resolve(world("Queen", musician("Q1", "Queen Latifah", "mul")), "Queen").status)
    }

    @Test fun aNamesakeWhoIsNotAMusicianIsRejectedEvenWithAMatchingNeutralLabel() = runTest {
        val politician = entityJson("Q7", "Morgan Wallen", "politician", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q82955")), enwiki = "Morgan Wallen (politician)", labelLanguage = "mul")
        assertEquals(MatchStatus.NOT_FOUND, resolve(world("Morgan Wallen", politician), "Morgan Wallen").status)
    }

    @Test fun twoMusiciansWithTheSameNeutralLabelAreAmbiguousNotGuessed() = runTest {
        val a = musician("Q1", "Prince", "mul"); val b = musician("Q2", "Prince", "mul", desc = "Dutch singer")
        val r = resolve(world("Prince", a, b), "Prince")
        assertEquals(MatchStatus.AMBIGUOUS, r.status); assertEquals(2, r.candidates.size)
    }

    @Test fun anAlbumOrDiscographyWithTheSameNameIsNotAnArtist() = runTest {
        val album = entityJson("Q5", "Beyoncé", null, claims = mapOf("P31" to listOf("Q482994")), enwiki = "Beyoncé (album)", labelLanguage = "mul")
        assertEquals(MatchStatus.NOT_FOUND, resolve(world("Beyoncé", album), "Beyoncé").status)
    }

    // ---- parser ----
    @Test fun parserUsesEnglishThenNeutralForLabelsAndMergesAliases() {
        val json = """{"entities":{
          "Q1":{"id":"Q1","labels":{"mul":{"language":"mul","value":"Post Malone"}},"aliases":{"mul":[{"language":"mul","value":"Austin Post"}],"en":[{"language":"en","value":"Posty"}]},"claims":{}},
          "Q2":{"id":"Q2","labels":{"en":{"language":"en","value":"Queen"},"mul":{"language":"mul","value":"ignored"}},"claims":{}},
          "Q3":{"id":"Q3","labels":{},"claims":{}}}}"""
        val m = WikiParsers.parseEntities(json)
        assertEquals("Post Malone", m.getValue("Q1").label)
        assertEquals(listOf("Posty", "Austin Post"), m.getValue("Q1").aliases)
        assertEquals("Queen", m.getValue("Q2").label)
        assertNull(m.getValue("Q3").label)
    }
}
