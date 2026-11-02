package com.mundymorning.touchmusicid.recognition

import com.mundymorning.touchmusicid.domain.RecognizedSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface ListenState {
    data object Idle : ListenState
    /** Waiting for the OS microphone-permission dialog. */
    data object NeedsPermission : ListenState
    data class PermissionDenied(val permanent: Boolean) : ListenState
    data class Listening(val demo: Boolean) : ListenState
    data class Processing(val demo: Boolean) : ListenState
    data class Matched(val result: RecognizedSong) : ListenState
    data object NoMatch : ListenState
    data class Error(val kind: FailureKind, val message: String) : ListenState
    data class Unavailable(val reason: UnavailableReason) : ListenState
}

/**
 * The recognition state machine. Pure Kotlin so it is unit-testable.
 * Guarantees: one session at a time (double taps ignored), cancellation releases the engine,
 * a hard timeout, and microphone permission is only requested for engines that need it.
 */
class RecognitionController(
    private val scope: CoroutineScope,
    private val engine: () -> RecognitionEngine,
    private val hasMicPermission: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 20_000,
) {
    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    val state: StateFlow<ListenState> = _state.asStateFlow()
    private var job: Job? = null

    private val busy: Boolean
        get() = _state.value is ListenState.Listening || _state.value is ListenState.Processing

    fun start() {
        if (busy) return
        val e = engine()
        val availability = e.availability
        if (availability is EngineAvailability.Unavailable) {
            _state.value = ListenState.Unavailable(availability.reason)
            return
        }
        if (e.requiresMicrophone && !hasMicPermission()) {
            _state.value = ListenState.NeedsPermission
            return
        }
        run(e)
    }

    /** Call with the outcome of the OS permission dialog. Ignored unless we asked for it. */
    fun onPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (_state.value != ListenState.NeedsPermission) return
        if (granted) start() else _state.value = ListenState.PermissionDenied(permanentlyDenied)
    }

    /** Stops capture immediately (also call when the app leaves the foreground). */
    fun cancel() {
        job?.cancel()
        job = null
        if (busy || _state.value == ListenState.NeedsPermission) _state.value = ListenState.Idle
    }

    fun reset() {
        if (!busy) _state.value = ListenState.Idle
    }

    private fun run(e: RecognitionEngine) {
        val demo = e.source == com.mundymorning.touchmusicid.domain.RecognitionSource.DEMO
        _state.value = ListenState.Listening(demo)
        job = scope.launch {
            val result = try {
                withTimeout(timeoutMillis) {
                    e.recognize { phase ->
                        _state.value = when (phase) {
                            EnginePhase.LISTENING -> ListenState.Listening(demo)
                            EnginePhase.PROCESSING -> ListenState.Processing(demo)
                        }
                    }
                }
            } catch (t: TimeoutCancellationException) {
                EngineResult.Failure(FailureKind.TIMEOUT, "Listening took too long. Please try again.")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                EngineResult.Failure(FailureKind.UNKNOWN, t.message ?: "Unexpected error")
            }
            _state.value = when (result) {
                is EngineResult.Match -> ListenState.Matched(RecognizedSong(result.song, e.source, clock()))
                EngineResult.NoMatch -> ListenState.NoMatch
                is EngineResult.Failure -> ListenState.Error(result.kind, result.message)
            }
        }
    }
}
