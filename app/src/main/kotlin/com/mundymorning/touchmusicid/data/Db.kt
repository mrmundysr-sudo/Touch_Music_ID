package com.mundymorning.touchmusicid.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One identification event. Favorites are stored separately so clearing history never removes them. */
@Entity(tableName = "history", indices = [Index("songKey"), Index("recognizedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String?,
    val appleMusicUrl: String?,
    val webUrl: String?,
    val isrc: String?,
    val artworkUrl: String?,
    /** RecognitionSource name: SHAZAMKIT or DEMO. */
    val source: String,
    val recognizedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songKey: String,
    val title: String,
    val artist: String,
    val album: String?,
    val appleMusicUrl: String?,
    val webUrl: String?,
    val isrc: String?,
    val artworkUrl: String?,
    val source: String,
    val addedAt: Long,
)

@Entity(tableName = "knowledge_cache")
data class CacheEntity(@PrimaryKey val key: String, val payload: String, val fetchedAt: Long)

@Dao
interface LibraryDao {
    @Insert suspend fun insertHistory(e: HistoryEntity): Long
    @Query("SELECT * FROM history ORDER BY recognizedAt DESC, id DESC") fun history(): Flow<List<HistoryEntity>>
    @Query("SELECT * FROM history WHERE songKey = :key ORDER BY recognizedAt DESC, id DESC LIMIT 1") suspend fun latestHistory(key: String): HistoryEntity?
    @Query("DELETE FROM history WHERE id = :id") suspend fun deleteHistory(id: Long)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM history WHERE source = 'DEMO'") suspend fun clearDemoHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFavorite(e: FavoriteEntity)
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC") fun favorites(): Flow<List<FavoriteEntity>>
    @Query("SELECT * FROM favorites WHERE songKey = :key") suspend fun favorite(key: String): FavoriteEntity?
    @Query("DELETE FROM favorites WHERE songKey = :key") suspend fun deleteFavorite(key: String)
    @Query("DELETE FROM favorites WHERE source = 'DEMO'") suspend fun clearDemoFavorites()

    @Query("SELECT * FROM knowledge_cache WHERE `key` = :key") suspend fun cacheGet(key: String): CacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cachePut(e: CacheEntity)
    @Query("DELETE FROM knowledge_cache") suspend fun cacheClear()
}

@Database(entities = [HistoryEntity::class, FavoriteEntity::class, CacheEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
