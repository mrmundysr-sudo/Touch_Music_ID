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
import com.mundymorning.touchmusicid.net.UrlConnectionFetcher
import com.mundymorning.touchmusicid.recognition.EngineProvider
import com.mundymorning.touchmusicid.recognition.RemoteTokenSource

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(app: Application) {
    private val http = UrlConnectionFetcher()
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "touch_music_id.db").build()
    val library = LibraryRepository(db.dao())
    val settings = SettingsStore(app)
    val knowledge = KnowledgeRepository(KnowledgeResolver(WikiApi(http)), RoomKnowledgeCache(db.dao()))
    val knowledgeCache = RoomKnowledgeCache(db.dao())
    val engines = EngineProvider(RemoteTokenSource(BuildConfig.TOKEN_SERVICE_URL, http))
}

class TouchMusicIdApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
