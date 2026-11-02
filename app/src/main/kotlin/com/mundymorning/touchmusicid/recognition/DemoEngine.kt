package com.mundymorning.touchmusicid.recognition

import com.mundymorning.touchmusicid.domain.RecognitionSource
import com.mundymorning.touchmusicid.domain.Song
import kotlinx.coroutines.delay

/**
 * DEMO MODE. Returns representative, synthetic results after a short pause. It does NOT listen to the
 * microphone and does NOT identify anything. Every result it produces is tagged RecognitionSource.DEMO
 * and shown with a visible "Demo" label. The songs are well-known tracks so the Wikipedia/Wikidata
 * discovery experience can be exercised end to end.
 */
class DemoEngine(
    private val listenMillis: Long = 2200,
    private val processMillis: Long = 900,
    private val songs: List<Song> = DEMO_SONGS,
    private val pick: (Int) -> Int = { (System.nanoTime() % it).toInt().coerceAtLeast(0) },
) : RecognitionEngine {
    override val source = RecognitionSource.DEMO
    override val requiresMicrophone = false
    override val availability: EngineAvailability = EngineAvailability.Ready

    override suspend fun recognize(onPhase: (EnginePhase) -> Unit): EngineResult {
        onPhase(EnginePhase.LISTENING)
        delay(listenMillis)
        onPhase(EnginePhase.PROCESSING)
        delay(processMillis)
        return EngineResult.Match(songs[pick(songs.size)])
    }

    companion object {
        val DEMO_SONGS = listOf(
            Song("Bohemian Rhapsody", "Queen", "A Night at the Opera"),
            Song("Billie Jean", "Michael Jackson", "Thriller"),
            Song("Hey Jude", "The Beatles"),
            Song("Rolling in the Deep", "Adele", "21"),
            Song("Purple Rain", "Prince", "Purple Rain"),
            Song("Smells Like Teen Spirit", "Nirvana", "Nevermind"),
        )
    }
}
