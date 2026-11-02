package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.knowledge.ClaimValue
import com.mundymorning.touchmusicid.knowledge.WikiParsers
import org.junit.Assert.*
import org.junit.Test

class WikiParsersTest {
    @Test fun parsesSummary() {
        val s = WikiParsers.parseSummary(summaryJson("Queen (band)", "Queen are a British rock band.", "British rock band"))!!
        assertEquals("Queen (band)", s.title)
        assertEquals("British rock band", s.description)
        assertFalse(s.isDisambiguation)
        assertEquals("https://en.wikipedia.org/wiki/Queen_(band)", s.url)
    }

    @Test fun flagsDisambiguationPages() {
        assertTrue(WikiParsers.parseSummary(summaryJson("Prince", "Prince may refer to:", type = "disambiguation"))!!.isDisambiguation)
    }

    @Test fun malformedJsonIsNullNotACrash() {
        assertNull(WikiParsers.parseSummary("<html>"))
        assertTrue(WikiParsers.parseSearch("nope").isEmpty())
        assertTrue(WikiParsers.parseEntities("nope").isEmpty())
        assertTrue(WikiParsers.parseEntitySearch("").isEmpty())
    }

    @Test fun stripsHtmlFromSearchSnippets() {
        val hits = WikiParsers.parseSearch(wpSearchJson("A", "B"))
        assertEquals(listOf("A", "B"), hits.map { it.title })
        assertEquals("x", hits[0].snippet)
    }

    @Test fun parsesEntityClaimsAndSitelinks() {
        val json = entitiesJson(
            entityJson(
                "Q1", "Queen", "British rock band", aliases = listOf("Queen band"),
                claims = mapOf(
                    "P31" to listOf("Q5741069"), "P434" to listOf("0383dadf-2a4e-4d10-a46a-e9e041da8eb3"),
                    "P571" to listOf(ClaimValue.Time("+1970-01-01T00:00:00Z", 9)),
                ),
                enwiki = "Queen (band)",
            ),
        )
        val e = WikiParsers.parseEntities(json)["Q1"]!!
        assertEquals(listOf("Q5741069"), e.refs("P31"))
        assertTrue(e.has("P434"))
        assertEquals("Queen (band)", e.enwikiTitle)
        assertEquals(listOf("Queen", "Queen band"), e.names)
        assertEquals("1970", WikiParsers.formatTime(e.firstTime("P571")!!))
    }

    @Test fun skipsMissingEntities() {
        assertTrue(WikiParsers.parseEntities("""{"entities":{"Q9":{"id":"Q9","missing":""}}}""").isEmpty())
    }

    @Test fun formatsWikidataTimesByPrecision() {
        assertEquals("1946-09-05", WikiParsers.formatTime(ClaimValue.Time("+1946-09-05T00:00:00Z", 11)))
        assertEquals("1946-09", WikiParsers.formatTime(ClaimValue.Time("+1946-09-00T00:00:00Z", 10)))
        assertEquals("1946", WikiParsers.formatTime(ClaimValue.Time("+1946-00-00T00:00:00Z", 9)))
        assertEquals("44 BCE", WikiParsers.formatTime(ClaimValue.Time("-0044-00-00T00:00:00Z", 9)))
        assertNull(WikiParsers.formatTime(ClaimValue.Time("garbage", 11)))
    }
}
