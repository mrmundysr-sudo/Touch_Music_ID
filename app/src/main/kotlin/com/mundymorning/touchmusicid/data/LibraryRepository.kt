package com.mundymorning.touchmusicid.data

import com.mundymorning.touchmusicid.domain.RecognitionSource
import com.mundymorning.touchmusicid.domain.RecognizedSong
import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.knowledge.KnowledgeCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LibrarySong(
    val song: Song,
    val source: RecognitionSource,
    val timestamp: Long,
    val historyId: Long? = null,
)

class LibraryRepository(private val dao: LibraryDao, private val clock: () -> Long = System::currentTimeMillis) {

    val history: Flow<List<LibrarySong>> = dao.history().map { list -> list.map { it.toLibrary() } }
    val favorites: Flow<List<LibrarySong>> = dao.favorites().map { list -> list.map { it.toLibrary() } }

    suspend fun record(r: RecognizedSong): Long = dao.insertHistory(
        HistoryEntity(
            songKey = r.song.key, title = r.song.title, artist = r.song.artist, album = r.song.album,
            appleMusicUrl = r.song.appleMusicUrl, webUrl = r.song.webUrl, isrc = r.song.isrc, artworkUrl = r.song.artworkUrl,
            source = r.source.name, recognizedAt = r.recognizedAtMillis,
        ),
    )

    /** Looks a song up by key in favorites first, then the newest history entry. */
    suspend fun find(key: String): LibrarySong? = dao.favorite(key)?.toLibrary() ?: dao.latestHistory(key)?.toLibrary()

    suspend fun isFavorite(key: String): Boolean = dao.favorite(key) != null

    suspend fun setFavorite(item: LibrarySong, favorite: Boolean) {
        if (favorite) {
            val s = item.song
            dao.upsertFavorite(
                FavoriteEntity(
                    s.key, s.title, s.artist, s.album, s.appleMusicUrl, s.webUrl, s.isrc, s.artworkUrl,
                    item.source.name, clock(),
                ),
            )
        } else dao.deleteFavorite(item.song.key)
    }

    suspend fun deleteHistory(id: Long) = dao.deleteHistory(id)
    suspend fun clearHistory() = dao.clearHistory()
    suspend fun clearDemoData() { dao.clearDemoHistory(); dao.clearDemoFavorites() }
}

private fun HistoryEntity.toLibrary() = LibrarySong(
    Song(title, artist, album, appleMusicUrl, webUrl, isrc, artworkUrl),
    RecognitionSource.valueOf(source), recognizedAt, id,
)

private fun FavoriteEntity.toLibrary() = LibrarySong(
    Song(title, artist, album, appleMusicUrl, webUrl, isrc, artworkUrl),
    RecognitionSource.valueOf(source), addedAt,
)

class RoomKnowledgeCache(private val dao: LibraryDao) : KnowledgeCache {
    override suspend fun get(key: String) = dao.cacheGet(key)?.let { it.payload to it.fetchedAt }
    override suspend fun put(key: String, payload: String, fetchedAtMillis: Long) = dao.cachePut(CacheEntity(key, payload, fetchedAtMillis))
    override suspend fun clear() = dao.cacheClear()
}
