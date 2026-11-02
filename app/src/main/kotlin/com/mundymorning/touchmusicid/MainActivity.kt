package com.mundymorning.touchmusicid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mundymorning.touchmusicid.design.TouchMusicIdTheme
import com.mundymorning.touchmusicid.ui.MainViewModel
import com.mundymorning.touchmusicid.ui.TouchMusicIdRoot

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TouchMusicIdTheme { TouchMusicIdRoot(vm) } }
    }
}
