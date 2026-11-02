package com.mundymorning.touchmusicid.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mundymorning.touchmusicid.TouchMusicIdApp
import com.mundymorning.touchmusicid.data.LibrarySong
import com.mundymorning.touchmusicid.knowledge.Freshness
import com.mundymorning.touchmusicid.knowledge.KnowledgeBundle
import com.mundymorning.touchmusicid.knowledge.KnowledgeResult
import com.mundymorning.touchmusicid.recognition.EngineAvailability
import com.mundymorning.touchmusicid.recognition.ListenState
import com.mundymorning.touchmusicid.recognition.RecognitionController
import com.mundymorning.touchmusicid.recognition.UnavailableReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface KnowledgeUi {
    data object Loading : KnowledgeUi
    data class Ready(val bundle: KnowledgeBundle, val freshness: Freshness) : KnowledgeUi
    data class Error(val kind: KnowledgeResult.Kind) : KnowledgeUi
}

data class ResultUi(val item: LibrarySong, val knowledge: KnowledgeUi)

/** What the platform tells the user about recognition right now (shown on Listen, Settings and Help). */
data class RecognitionStatus(
    val sdkLinked: Boolean,
    val tokenServiceConfigured: Boolean,
    val realRecognitionAvailable: Boolean,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as TouchMusicIdApp).container
    private val library = container.library

    val demoMode = container.settings.demoMode

    val controller = RecognitionController(
        scope = viewModelScope,
        engine = { container.engines.current(container.settings.demoMode.value) },
        hasMicPermission = {
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
    )
    val listenState: StateFlow<ListenState> = controller.state

    val history = library.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val favorites = library.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val openResultEvents = Channel<String>(Channel.BUFFERED)
    /** One-shot: song key to navigate to after a successful match. */
    val openResult = openResultEvents.receiveAsFlow()

    private val _result = MutableStateFlow<ResultUi?>(null)
    val result: StateFlow<ResultUi?> = _result.asStateFlow()
    private var knowledgeJob: Job? = null

    init {
        viewModelScope.launch {
            controller.state.collect { s ->
                if (s is ListenState.Matched) {
                    library.record(s.result)
                    openResultEvents.send(s.result.song.key)
                    controller.reset()
                }
            }
        }
    }

    fun recognitionStatus(): RecognitionStatus {
        val real = container.engines.realEngine()
        return RecognitionStatus(
            sdkLinked = (real.availability as? EngineAvailability.Unavailable)?.reason != UnavailableReason.SDK_NOT_LINKED,
            tokenServiceConfigured = container.engines.tokenServiceConfigured,
            realRecognitionAvailable = real.availability is EngineAvailability.Ready,
        )
    }

    // ---- Listening ----
    fun onListenTapped() {
        when (controller.state.value) {
            is ListenState.Listening, is ListenState.Processing -> controller.cancel()
            else -> controller.start()
        }
    }
    fun onPermissionResult(granted: Boolean, permanentlyDenied: Boolean) = controller.onPermissionResult(granted, permanentlyDenied)
    fun cancelListening() = controller.cancel()
    fun dismissListenMessage() = controller.reset()

    // ---- Result / knowledge ----
    fun openSong(key: String) {
        knowledgeJob?.cancel()
        knowledgeJob = viewModelScope.launch {
            val item = library.find(key) ?: run { _result.value = null; return@launch }
            _result.value = ResultUi(item, KnowledgeUi.Loading)
            load(item, force = false)
        }
    }

    fun retryKnowledge() {
        val current = _result.value ?: return
        knowledgeJob?.cancel()
        knowledgeJob = viewModelScope.launch {
            _result.value = current.copy(knowledge = KnowledgeUi.Loading)
            load(current.item, force = true)
        }
    }

    fun chooseArtist(qid: String) {
        val current = _result.value ?: return
        knowledgeJob?.cancel()
        knowledgeJob = viewModelScope.launch {
            _result.value = current.copy(knowledge = KnowledgeUi.Loading)
            applyKnowledge(current.item, container.knowledge.chooseArtist(current.item.song, qid))
        }
    }

    private suspend fun load(item: LibrarySong, force: Boolean) =
        applyKnowledge(item, container.knowledge.load(item.song, force))

    private fun applyKnowledge(item: LibrarySong, r: KnowledgeResult) {
        val ui = when (r) {
            is KnowledgeResult.Found -> KnowledgeUi.Ready(r.bundle, r.freshness)
            is KnowledgeResult.Failed -> KnowledgeUi.Error(r.kind)
        }
        if (_result.value?.item?.song?.key == item.song.key) _result.value = ResultUi(item, ui)
    }

    fun toggleFavorite(item: LibrarySong, currentlyFavorite: Boolean) {
        viewModelScope.launch { library.setFavorite(item, !currentlyFavorite) }
    }

    // ---- History / data ----
    fun deleteHistory(id: Long) { viewModelScope.launch { library.deleteHistory(id) } }
    fun clearHistory() { viewModelScope.launch { library.clearHistory() } }
    fun clearDemoData() { viewModelScope.launch { library.clearDemoData() } }
    fun clearKnowledgeCache() { viewModelScope.launch { container.knowledgeCache.clear() } }
    fun setDemoMode(enabled: Boolean) = container.settings.setDemoMode(enabled)
}
