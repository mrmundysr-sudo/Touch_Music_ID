package com.mundymorning.touchmusicid.recognition

/** Used when Apple's AAR is not in app/libs: there is no real backend, so the app reports "SDK not linked". */
object ShazamKitBindings {
    fun backend(): MatchBackend? = null
}
