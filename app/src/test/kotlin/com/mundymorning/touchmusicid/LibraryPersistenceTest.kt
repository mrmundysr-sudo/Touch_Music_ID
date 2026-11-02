package com.mundymorning.touchmusicid

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundymorning.touchmusicid.data.*
import com.mundymorning.touchmusicid.domain.RecognitionSource
import com.mundymorning.touchmusicid.domain.RecognizedSong
import com.mundymorning.touchmusicid.domain.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryPersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LibraryRepository
    private val a = Song("Hey Jude", "The Beatles", "Hey Jude")
    private val b = Song("Purple Rain", "Prince")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = LibraryRepository(db.dao(), clock = { 500L })
    }

    @After fun tearDown() = db.close()

    @Test fun historyIsNewestFirstAndKeepsAllFields() = runTest {
        repo.record(RecognizedSong(a.copy(appleMusicUrl = "https://music.apple.com/x"), RecognitionSource.SHAZAMKIT, 100))
        repo.record(RecognizedSong(b, RecognitionSource.DEMO, 200))
        val h = repo.history.first()
        assertEquals(listOf("Purple Rain", "Hey Jude"), h.map { it.song.title })
        assertEquals(RecognitionSource.DEMO, h[0].source)
        assertEquals("https://music.apple.com/x", h[1].song.appleMusicUrl)
        assertEquals("Hey Jude", h[1].song.album)
    }

    @Test fun repeatedIdentificationsAreEachRecorded() = runTest {
        repeat(3) { repo.record(RecognizedSong(a, RecognitionSource.DEMO, 100L + it)) }
        assertEquals(3, repo.history.first().size)
    }

    @Test fun deleteSingleEntryAndClearAll() = runTest {
        val id = repo.record(RecognizedSong(a, RecognitionSource.DEMO, 1))
        repo.record(RecognizedSong(b, RecognitionSource.DEMO, 2))
        repo.deleteHistory(id)
        assertEquals(listOf("Purple Rain"), repo.history.first().map { it.song.title })
        repo.clearHistory()
        assertTrue(repo.history.first().isEmpty())
    }

    @Test fun favoritesSurviveClearingHistory() = runTest {
        repo.record(RecognizedSong(a, RecognitionSource.SHAZAMKIT, 1))
        repo.setFavorite(LibrarySong(a, RecognitionSource.SHAZAMKIT, 1), true)
        repo.clearHistory()
        assertEquals(listOf("Hey Jude"), repo.favorites.first().map { it.song.title })
        assertTrue(repo.isFavorite(a.key))
    }

    @Test fun unfavoriteAndIdempotentFavorite() = runTest {
        val item = LibrarySong(a, RecognitionSource.DEMO, 1)
        repo.setFavorite(item, true); repo.setFavorite(item, true)
        assertEquals(1, repo.favorites.first().size)
        repo.setFavorite(item, false)
        assertFalse(repo.isFavorite(a.key))
    }

    @Test fun findPrefersFavoriteThenLatestHistory() = runTest {
        repo.record(RecognizedSong(a, RecognitionSource.SHAZAMKIT, 1))
        repo.record(RecognizedSong(a, RecognitionSource.SHAZAMKIT, 9))
        assertEquals(9L, repo.find(a.key)!!.timestamp)
        assertNull(repo.find("missing||key"))
    }

    @Test fun clearDemoDataKeepsRealResults() = runTest {
        repo.record(RecognizedSong(a, RecognitionSource.SHAZAMKIT, 1))
        repo.record(RecognizedSong(b, RecognitionSource.DEMO, 2))
        repo.setFavorite(LibrarySong(b, RecognitionSource.DEMO, 2), true)
        repo.clearDemoData()
        assertEquals(listOf("Hey Jude"), repo.history.first().map { it.song.title })
        assertTrue(repo.favorites.first().isEmpty())
    }

    @Test fun knowledgeCachePersistsAndClears() = runTest {
        val cache = RoomKnowledgeCache(db.dao())
        cache.put("k", "payload", 7)
        assertEquals("payload" to 7L, cache.get("k"))
        cache.put("k", "newer", 8)
        assertEquals("newer" to 8L, cache.get("k"))
        cache.clear()
        assertNull(cache.get("k"))
    }
}
