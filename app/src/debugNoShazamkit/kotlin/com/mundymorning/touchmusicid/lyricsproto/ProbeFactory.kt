package com.mundymorning.touchmusicid.lyricsproto

import com.mundymorning.touchmusicid.recognition.TokenSource

/** Used when Apple's AAR is not in app/libs: the Lab can still be opened but cannot recognise music. */
object ProbeFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(tokens: TokenSource, capture: LabAudioCapture): MatchProbe? = null
}
