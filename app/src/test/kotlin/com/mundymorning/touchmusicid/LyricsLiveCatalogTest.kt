package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * INFORMATIONAL live check, opt-in (LIVE_LYRICS=1), run by the CI job "Lyrics live catalogue".
 * Calls the real iTunes, MusicBrainz and LRCLIB endpoints (identified User-Agent, serialized, spaced) for well-known songs to
 * verify response shapes and to measure how often a correct, synchronized, plausible record is found with and without a
 * duration. Output lines are prefixed LYRICSLIVE. Nothing here is asserted: availability is data, not a pass/fail.
 */
class LyricsLiveCatalogTest {
    private val enabled get() = System.getenv("LIVE_LYRICS") == "1"

    private val songs = listOf(
        "Bohemian Rhapsody" to "Queen", "Billie Jean" to "Michael Jackson", "Rolling in the Deep" to "Adele",
        "Smells Like Teen Spirit" to "Nirvana", "Shake It Off" to "Taylor Swift", "Last Night" to "Morgan Wallen",
        "Circles" to "Post Malone", "bad guy" to "Billie Eilish", "Blinding Lights" to "The Weeknd",
        "Get Lucky" to "Daft Punk", "Hotel California" to "Eagles", "Lose Yourself" to "Eminem",
    )

    private fun describe(r: LyricsLookup): String = when (r) {
        is LyricsLookup.Found -> {
            val rec = r.best.record
            val doc = LrcParser.parse(rec.synced)
            "found #${rec.id} \"${rec.trackName}\" by ${rec.artistName} synced=${!rec.synced.isNullOrBlank()} lines=${doc.lines.size} last=${doc.lines.lastOrNull()?.timeMs?.div(1000)}s " +
                "dur=${rec.durationSec} score=${"%.2f".format(java.util.Locale.ROOT, r.best.score)} drift=${r.best.durationGapSec} requests=${r.requests}"
        }
        is LyricsLookup.NotFound -> "NOT_FOUND (${r.ranked.size} rejected: ${r.ranked.take(2).joinToString("/") { it.rejectedReason.orEmpty() }})"
        is LyricsLookup.Failed -> "FAILED ${r.kind} ${r.message}"
    }

    @Test fun catalogue() = runBlocking {
        assumeTrue("LIVE_LYRICS not set", enabled)
        val itunes = ITunesClient(LabHttp.itunes())
        val mb = MusicBrainzClient(LabHttp.musicBrainz())
        val lrclib = LrclibClient(LabHttp.lrclib())
        println("LYRICSLIVE ua=${LabHttp.USER_AGENT}")
        var found = 0; var foundSynced = 0; var plausible = 0; var foundNoDur = 0; var sameChoice = 0
        for ((title, artist) in songs) {
            var id: String? = null; var dur: Long? = null; var album: String? = null; var name: String? = null
            val s = itunes.searchFirst("$artist $title")
            when (s) {
                is MetaResult.Found -> { id = s.meta.trackId; dur = s.meta.durationMs; album = s.meta.album; name = s.meta.trackName }
                MetaResult.NotFound -> println("LYRICSLIVE   iTunes search: no result")
                is MetaResult.Failed -> println("LYRICSLIVE   iTunes search FAILED ${s.kind} ${s.message}")
            }
            val l = id?.let { itunes.lookupById(it) }
            val lookupDur = (l as? MetaResult.Found)?.meta?.durationMs
            val isrc = mb.searchIsrc(title, artist)
            val m = isrc?.let { mb.byIsrc(it) }
            val mbDur = (m as? MetaResult.Found)?.meta?.durationMs
            println("LYRICSLIVE | $artist - $title | iTunes id=$id dur=$dur lookupDur=$lookupDur album=$album | MB isrc=$isrc dur=$mbDur ${(m as? MetaResult.Found)?.meta?.note}")

            val withDur = lrclib.lookup(TrackQuery(name ?: title, artist, album, (lookupDur ?: dur)?.let { it / 1000.0 }))
            val noDur = lrclib.lookup(TrackQuery(title, artist))
            println("LYRICSLIVE   with duration: ${describe(withDur)}")
            println("LYRICSLIVE   no duration:   ${describe(noDur)}")
            if (withDur is LyricsLookup.Found) {
                found++
                if (!withDur.best.record.synced.isNullOrBlank()) foundSynced++
                if (LyricsSanity.syncedLooksPlausible(LrcParser.parse(withDur.best.record.synced), lookupDur ?: dur)) plausible++
            }
            if (noDur is LyricsLookup.Found) foundNoDur++
            if (withDur is LyricsLookup.Found && noDur is LyricsLookup.Found && withDur.best.record.id == noDur.best.record.id) sameChoice++
        }
        println("LYRICSLIVE negative: " + describe(lrclib.lookup(TrackQuery("Zzxqv Wuthering Qq Nonexistent", "Nobody Atall"))))
        println("LYRICSLIVE SUMMARY songs=${songs.size} foundWithDuration=$found syncedWithDuration=$foundSynced plausibleTimestamps=$plausible foundWithoutDuration=$foundNoDur sameChoiceBoth=$sameChoice")
    }
}
