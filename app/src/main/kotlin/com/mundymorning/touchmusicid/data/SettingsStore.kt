package com.mundymorning.touchmusicid.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tiny SharedPreferences-backed settings. No identifiers, no secrets. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _demoMode = MutableStateFlow(prefs.getBoolean(KEY_DEMO, false))
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _enableOnlineLyrics = MutableStateFlow(prefs.getBoolean(KEY_ONLINE_LYRICS, false))
    val enableOnlineLyrics: StateFlow<Boolean> = _enableOnlineLyrics.asStateFlow()

    fun setDemoMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, enabled).apply()
        _demoMode.value = enabled
    }

    fun setEnableOnlineLyrics(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_LYRICS, enabled).apply()
        _enableOnlineLyrics.value = enabled
    }

    private companion object {
        const val KEY_DEMO = "demo_mode"
        const val KEY_ONLINE_LYRICS = "enable_online_lyrics"
    }
}
