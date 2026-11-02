package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.*
import org.junit.Assert.*
import org.junit.Test

class LyricsMatcherTest {
    private fun rec(
        id: Long, title: String, artist: String, album: String? = null, dur: Double? = 354.0,
        synced: String? = "[00:01.00]x", plain: String? = "x", instrumental: Boolean = false,
    ) = LrclibRecord(id, title, artist, album, dur, instrumental, plain, synced)

    private val q = TrackQuery("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354.0)

    @Test fun cleanTitleRemovesDecorationButKeepsVersionWords() {
        assertEquals("Bohemian Rhapsody", LyricsMatcher.cleanTitle("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("Song", LyricsMatcher.cleanTitle("Song (Remastered 2009)"))
        assertEquals("Song", LyricsMatcher.cleanTitle("Song (feat. Someone)"))
        assertEquals("Heroes", LyricsMatcher.cleanTitle("Heroes - 2017 Remaster"))
        assertEquals("Song (Live at Wembley)", LyricsMatcher.cleanTitle("Song (Live at Wembley)"))
        assertEquals("Song - Remix", LyricsMatcher.cleanTitle("Song - Remix"))
        assertEquals("(Remastered)", LyricsMatcher.cleanTitle("(Remastered)"))
    }

    @Test fun versionWordsComeFromTheRawTitle() {
        assertEquals(setOf("live"), LyricsMatcher.versionWords("Song (Live)"))
        assertEquals(setOf("remix"), LyricsMatcher.versionWords("Song - Remix"))
        assertEquals(emptySet<String>(), LyricsMatcher.versionWords("Song - Radio Edit"))
        assertEquals(emptySet<String>(), LyricsMatcher.versionWords("Alive")) // whole words only
    }

    @Test fun theSameRecordingMatches() {
        val s = LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera", 354.5))
        assertNull(s.rejectedReason); assertTrue(s.score > 1.0)
    }

    @Test fun aRemasteredTitleStillMatchesTheStudioRecord() {
        val s = LyricsMatcher.score(q.copy(title = "Bohemian Rhapsody - Remastered 2011"), rec(1, "Bohemian Rhapsody", "Queen"))
        assertNull(s.rejectedReason)
    }

    @Test fun liveRemixAndOtherVersionsAreRejectedInBothDirections() {
        val hey = TrackQuery("Hey Jude", "The Beatles", null, 431.0)
        assertTrue(LyricsMatcher.score(hey, rec(1, "Hey Jude (Live)", "The Beatles", dur = 431.0)).rejectedReason!!.startsWith("version mismatch"))
        assertTrue(LyricsMatcher.score(hey.copy(title = "Hey Jude (Live)"), rec(1, "Hey Jude", "The Beatles", dur = 431.0)).rejectedReason!!.startsWith("version mismatch"))
        assertNotNull(LyricsMatcher.score(hey, rec(1, "Hey Jude - Remix", "The Beatles", dur = 431.0)).rejectedReason)
        assertNotNull(LyricsMatcher.score(hey, rec(1, "Hey Jude (Karaoke Version)", "The Beatles", dur = 431.0)).rejectedReason)
        assertNull("both live is fine", LyricsMatcher.score(hey.copy(title = "Hey Jude (Live)"), rec(1, "Hey Jude (Live)", "The Beatles", dur = 431.0)).rejectedReason)
    }

    @Test fun aDifferentArtistIsRejectedButFeaturedOrJoinedNamesMatch() {
        assertNotNull(LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Queen Latifah")).rejectedReason)
        assertNotNull(LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Panic! at the Disco")).rejectedReason)
        val funk = TrackQuery("Uptown Funk", "Mark Ronson, Bruno Mars", null, 270.0)
        assertNull(LyricsMatcher.score(funk, rec(1, "Uptown Funk", "Mark Ronson", dur = 270.0)).rejectedReason)
        assertNull(LyricsMatcher.score(funk, rec(1, "Uptown Funk (feat. Bruno Mars)", "Mark Ronson & Bruno Mars", dur = 270.0)).rejectedReason)
        val sg = TrackQuery("The Sound of Silence", "Simon & Garfunkel", null, 185.0)
        assertNull(LyricsMatcher.score(sg, rec(1, "The Sound of Silence", "Simon & Garfunkel", dur = 185.0)).rejectedReason)
    }

    @Test fun artistsWithPunctuationAreNotMangled() {
        assertEquals("AC/DC", LyricsMatcher.primaryArtist("AC/DC"))
        assertEquals("Florence + the Machine", LyricsMatcher.primaryArtist("Florence + the Machine"))
        assertEquals("Post Malone", LyricsMatcher.primaryArtist("Post Malone feat. Swae Lee"))
        assertEquals(listOf("Simon & Garfunkel", "Simon"), LyricsMatcher.searchArtists("Simon & Garfunkel"))
        assertEquals(listOf("Queen"), LyricsMatcher.searchArtists("Queen"))
        assertEquals(1.0, LyricsMatcher.artistSimilarity("Beyoncé", "Beyonce"), 0.0)
    }

    @Test fun durationDriftIsRejectedAboveTenSecondsAndRanksCloserOnesHigher() {
        assertNotNull(LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Queen", dur = 400.0)).rejectedReason)
        val near = LyricsMatcher.score(q, rec(1, "Bohemian Rhapsody", "Queen", dur = 354.5))
        val mid = LyricsMatcher.score(q, rec(2, "Bohemian Rhapsody", "Queen", dur = 358.0))
        val far = LyricsMatcher.score(q, rec(3, "Bohemian Rhapsody", "Queen", dur = 362.0))
        assertTrue(near.score > mid.score && mid.score > far.score)
        assertEquals("unknown duration is neutral, not a rejection", null, LyricsMatcher.score(q.copy(durationSec = null), rec(4, "Bohemian Rhapsody", "Queen", dur = 500.0)).rejectedReason)
    }

    @Test fun synchronizedBeatsPlainAndInstrumentalIsPenalised() {
        val ranked = LyricsMatcher.rank(q, listOf(
            rec(1, "Bohemian Rhapsody", "Queen", synced = null),
            rec(2, "Bohemian Rhapsody", "Queen", instrumental = true),
            rec(3, "Bohemian Rhapsody", "Queen"),
        ))
        assertEquals(listOf(3L, 1L, 2L), ranked.map { it.record.id })
    }

    @Test fun rankPutsRejectedLastAndBestIgnoresThem() {
        val ranked = LyricsMatcher.rank(q, listOf(rec(1, "Other Song", "Someone"), rec(2, "Bohemian Rhapsody", "Queen"), rec(2, "Bohemian Rhapsody", "Queen")))
        assertEquals("duplicates by id are collapsed", 2, ranked.size)
        assertEquals(2L, ranked.first().record.id)
        assertEquals(2L, LyricsMatcher.best(ranked)!!.record.id)
        assertNull(LyricsMatcher.best(LyricsMatcher.rank(q, listOf(rec(1, "Other Song", "Someone")))))
    }

    @Test fun diceHandlesEdges() {
        assertEquals(0.0, LyricsMatcher.dice("", "abc"), 0.0)
        assertEquals(1.0, LyricsMatcher.dice("abc", "abc"), 0.0)
        assertEquals(0.0, LyricsMatcher.dice("a", "b"), 0.0)
        assertTrue(LyricsMatcher.dice("night", "nacht") < 0.5)
    }
}
