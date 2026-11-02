package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.RecognitionSource
import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.recognition.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionControllerTest {
    private val song = Song("T", "A")

    private class ScriptedEngine(
        override val requiresMicrophone: Boolean = true,
        override val source: RecognitionSource = RecognitionSource.SHAZAMKIT,
        val body: suspend (onPhase: (EnginePhase) -> Unit) -> EngineResult,
    ) : RecognitionEngine {
        var started = 0
        var cancelled = false
        override val availability: EngineAvailability = EngineAvailability.Ready
        override suspend fun recognize(onPhase: (EnginePhase) -> Unit): EngineResult {
            started++
            try { return body(onPhase) } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
        }
    }

    private fun TestScope.controller(engine: RecognitionEngine, mic: Boolean = true, timeout: Long = 20_000) =
        RecognitionController(this, { engine }, { mic }, clock = { 42L }, timeoutMillis = timeout)

    @Test fun unavailableEngineNeverProducesAMatch() = runTest {
        val c = controller(UnavailableEngine(UnavailableReason.SDK_NOT_LINKED))
        c.start(); advanceUntilIdle()
        assertEquals(ListenState.Unavailable(UnavailableReason.SDK_NOT_LINKED), c.state.value)
    }

    @Test fun requestsPermissionOnlyForMicEnginesThenRuns() = runTest {
        val e = ScriptedEngine { EngineResult.Match(song) }
        val c = controller(e, mic = false)
        c.start()
        assertEquals(ListenState.NeedsPermission, c.state.value)
        assertEquals(0, e.started)
        c.onPermissionResult(granted = true, permanentlyDenied = false); advanceUntilIdle()
        assertTrue(c.state.value is ListenState.Matched)
        assertEquals(1, e.started)
    }

    @Test fun deniedPermissionIsReportedAndNothingStarts() = runTest {
        val e = ScriptedEngine { EngineResult.Match(song) }
        val c = controller(e, mic = false)
        c.start(); c.onPermissionResult(false, permanentlyDenied = true); advanceUntilIdle()
        assertEquals(ListenState.PermissionDenied(true), c.state.value)
        assertEquals(0, e.started)
    }

    @Test fun permissionResultIsIgnoredIfNotRequested() = runTest {
        val c = controller(ScriptedEngine { EngineResult.NoMatch })
        c.onPermissionResult(true, false)
        assertEquals(ListenState.Idle, c.state.value)
    }

    @Test fun demoEngineNeedsNoMicAndIsTaggedDemo() = runTest {
        val c = controller(DemoEngine(listenMillis = 10, processMillis = 10, pick = { 0 }), mic = false)
        c.start(); advanceUntilIdle()
        val s = c.state.value as ListenState.Matched
        assertEquals(RecognitionSource.DEMO, s.result.source)
        assertEquals(42L, s.result.recognizedAtMillis)
    }

    @Test fun phasesProgressListeningToProcessingToResult() = runTest {
        val seen = mutableListOf<ListenState>()
        val e = ScriptedEngine(requiresMicrophone = false) { phase ->
            phase(EnginePhase.LISTENING); delay(100); phase(EnginePhase.PROCESSING); delay(100); EngineResult.NoMatch
        }
        val c = controller(e)
        c.start(); runCurrent2(this); seen += c.state.value
        advanceTimeBy(150); seen += c.state.value
        advanceUntilIdle(); seen += c.state.value
        assertEquals(listOf(ListenState.Listening(false), ListenState.Processing(false), ListenState.NoMatch), seen)
    }

    private fun runCurrent2(s: TestScope) = s.testScheduler.runCurrent()

    @Test fun doubleTapDoesNotStartTwoSessions() = runTest {
        val e = ScriptedEngine(requiresMicrophone = false) { delay(1000); EngineResult.NoMatch }
        val c = controller(e)
        c.start(); c.start(); c.start(); advanceUntilIdle()
        assertEquals(1, e.started)
    }

    @Test fun cancelStopsEngineAndReturnsToIdle() = runTest {
        val e = ScriptedEngine(requiresMicrophone = false) { awaitCancellation() }
        val c = controller(e)
        c.start(); runCurrent2(this)
        c.cancel(); advanceUntilIdle()
        assertTrue("engine must be cancelled so the mic is released", e.cancelled)
        assertEquals(ListenState.Idle, c.state.value)
    }

    @Test fun cancelWhileWaitingForPermissionReturnsToIdle() = runTest {
        val c = controller(ScriptedEngine { EngineResult.NoMatch }, mic = false)
        c.start(); c.cancel()
        assertEquals(ListenState.Idle, c.state.value)
    }

    @Test fun timeoutBecomesTimeoutError() = runTest {
        val e = ScriptedEngine(requiresMicrophone = false) { awaitCancellation() }
        val c = controller(e, timeout = 5_000)
        c.start(); advanceTimeBy(5_001); advanceUntilIdle()
        assertEquals(FailureKind.TIMEOUT, (c.state.value as ListenState.Error).kind)
        assertTrue(e.cancelled)
    }

    @Test fun engineExceptionBecomesErrorNotCrash() = runTest {
        val c = controller(ScriptedEngine(requiresMicrophone = false) { error("boom") })
        c.start(); advanceUntilIdle()
        assertEquals("boom", (c.state.value as ListenState.Error).message)
    }

    @Test fun failureResultIsSurfaced() = runTest {
        val c = controller(ScriptedEngine(requiresMicrophone = false) { EngineResult.Failure(FailureKind.TOKEN, "bad token") })
        c.start(); advanceUntilIdle()
        assertEquals(ListenState.Error(FailureKind.TOKEN, "bad token"), c.state.value)
    }

    @Test fun canListenAgainAfterResult() = runTest {
        val e = ScriptedEngine(requiresMicrophone = false) { EngineResult.NoMatch }
        val c = controller(e)
        c.start(); advanceUntilIdle(); c.start(); advanceUntilIdle()
        assertEquals(2, e.started)
    }

    // ---- EngineProvider ----
    private class FakeToken(override val isConfigured: Boolean) : TokenSource {
        override suspend fun token() = TokenResult.NotConfigured
    }

    @Test fun providerReportsSdkNotLinkedWithoutDemo() {
        val p = EngineProvider(FakeToken(false), sdkLinked = { false })
        val e = p.current(demoEnabled = false)
        assertEquals(EngineAvailability.Unavailable(UnavailableReason.SDK_NOT_LINKED), e.availability)
    }

    @Test fun providerUsesDemoOnlyWhenEnabled() {
        val p = EngineProvider(FakeToken(false), sdkLinked = { false })
        assertEquals(RecognitionSource.DEMO, p.current(true).source)
        assertTrue(p.current(false).availability is EngineAvailability.Unavailable)
    }

    @Test fun providerReportsTokenServiceMissingWhenSdkLinked() {
        val p = EngineProvider(FakeToken(false), sdkLinked = { true }, realEngineFactory = { error("unused") })
        assertEquals(EngineAvailability.Unavailable(UnavailableReason.TOKEN_SERVICE_NOT_CONFIGURED), p.realEngine().availability)
    }

    @Test fun readyRealEngineBeatsDemo() {
        val real = ScriptedEngine { EngineResult.NoMatch }
        val p = EngineProvider(FakeToken(true), sdkLinked = { true }, realEngineFactory = { real })
        assertSame(real, p.current(demoEnabled = true))
    }

    @Test fun shazamKitAbsentFromThisBuildClasspath() {
        assertFalse("This build must not link the SDK it cannot verify", isShazamKitOnClasspath())
    }
}
