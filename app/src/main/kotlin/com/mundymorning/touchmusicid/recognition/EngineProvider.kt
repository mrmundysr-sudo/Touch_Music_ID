package com.mundymorning.touchmusicid.recognition

/**
 * Chooses the engine for the current configuration.
 *
 * In this build the ShazamKit adapter is NOT linked (Apple distributes the Android SDK as an AAR behind a
 * developer login; see docs/SHAZAMKIT_SETUP.md), so [realEngine] resolves to an [UnavailableEngine] that
 * says exactly what is missing. When the AAR is added, a `RecognitionEngine` implementation is returned here
 * and nothing else in the app changes.
 */
class EngineProvider(
    private val tokenSource: TokenSource,
    private val sdkLinked: () -> Boolean = ::isShazamKitOnClasspath,
    private val realEngineFactory: ((TokenSource) -> RecognitionEngine)? = null,
    private val demo: RecognitionEngine = DemoEngine(),
) {
    fun realEngine(): RecognitionEngine {
        if (!sdkLinked() || realEngineFactory == null) return UnavailableEngine(UnavailableReason.SDK_NOT_LINKED)
        if (!tokenSource.isConfigured) return UnavailableEngine(UnavailableReason.TOKEN_SERVICE_NOT_CONFIGURED)
        return realEngineFactory.invoke(tokenSource)
    }

    /** Demo only when the user explicitly enabled it; a ready real engine always wins. */
    fun current(demoEnabled: Boolean): RecognitionEngine {
        val real = realEngine()
        return if (real.availability is EngineAvailability.Ready) real else if (demoEnabled) demo else real
    }

    val tokenServiceConfigured: Boolean get() = tokenSource.isConfigured
}

fun isShazamKitOnClasspath(): Boolean =
    runCatching { Class.forName("com.shazam.shazamkit.ShazamKit") }.isSuccess
