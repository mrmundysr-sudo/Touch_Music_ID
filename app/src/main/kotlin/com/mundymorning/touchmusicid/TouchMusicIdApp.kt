package com.mundymorning.touchmusicid

import android.app.Application
import androidx.room.Room
import com.mundymorning.touchmusicid.data.AppDatabase
import com.mundymorning.touchmusicid.data.LibraryRepository
import com.mundymorning.touchmusicid.data.RoomKnowledgeCache
import com.mundymorning.touchmusicid.data.SettingsStore
import com.mundymorning.touchmusicid.knowledge.KnowledgeRepository
import com.mundymorning.touchmusicid.knowledge.KnowledgeResolver
import com.mundymorning.touchmusicid.knowledge.WikiApi
import com.mundymorning.touchmusicid.net.HttpFetcher
import com.mundymorning.touchmusicid.net.UrlConnectionFetcher
import com.mundymorning.touchmusicid.recognition.EngineProvider
import com.mundymorning.touchmusicid.recognition.RemoteTokenSource

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(
    app: Application,
    http: HttpFetcher = UrlConnectionFetcher(),
    inMemoryDatabase: Boolean = false,
    engineProvider: EngineProvider? = null,
) {
    private val db = if (inMemoryDatabase) {
        Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    } else {
        Room.databaseBuilder(app, AppDatabase::class.java, "touch_music_id.db").build()
    }
    val library = LibraryRepository(db.dao())
    val settings = SettingsStore(app)
    val knowledge = KnowledgeRepository(KnowledgeResolver(WikiApi(http)), RoomKnowledgeCache(db.dao()))
    val knowledgeCache = RoomKnowledgeCache(db.dao())
    val engines = engineProvider ?: EngineProvider(RemoteTokenSource(BuildConfig.TOKEN_SERVICE_URL, http))
}

class TouchMusicIdApp : Application() {
    /** Replaceable in tests only (before the first activity launches). */
    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
