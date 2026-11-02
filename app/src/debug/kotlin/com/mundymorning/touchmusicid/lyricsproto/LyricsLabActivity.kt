package com.mundymorning.touchmusicid.lyricsproto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mundymorning.touchmusicid.design.TouchMusicIdTheme

/**
 * DEBUG-ONLY entry point (second launcher icon, "Lyrics Lab"). Lives in the debug source set, so release builds contain none of it.
 * The microphone is used only while this screen is in the foreground and Start is active; it is released in onStop.
 */
class LyricsLabActivity : ComponentActivity() {
    private lateinit var controller: LyricsLabController

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = LyricsLabController.create(applicationContext, lifecycleScope)
        setContent {
            TouchMusicIdTheme {
                LyricsLabScreen(controller, onStart = ::startWithPermission)
            }
        }
    }

    private fun startWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStop() {
        controller.stop() // never keep listening in the background
        super.onStop()
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}
