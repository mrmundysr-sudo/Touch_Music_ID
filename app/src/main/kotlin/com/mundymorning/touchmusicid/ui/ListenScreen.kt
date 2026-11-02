package com.mundymorning.touchmusicid.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundymorning.touchmusicid.design.AssetSlot
import com.mundymorning.touchmusicid.design.ButtonPhase
import com.mundymorning.touchmusicid.design.GlassCard
import com.mundymorning.touchmusicid.design.ListenButton
import com.mundymorning.touchmusicid.design.Pill
import com.mundymorning.touchmusicid.design.SlotImage
import com.mundymorning.touchmusicid.design.TmiButton
import com.mundymorning.touchmusicid.design.TmiColors
import com.mundymorning.touchmusicid.design.TmiDims
import com.mundymorning.touchmusicid.design.TmiIcons
import com.mundymorning.touchmusicid.design.Waveform
import com.mundymorning.touchmusicid.recognition.FailureKind
import com.mundymorning.touchmusicid.recognition.ListenState
import com.mundymorning.touchmusicid.recognition.UnavailableReason

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun ListenScreen(vm: MainViewModel, onOpenHelp: () -> Unit) {
    val state by vm.listenState.collectAsStateWithLifecycle()
    val demo by vm.demoMode.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = ctx.findActivity()
        val permanent = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        vm.onPermissionResult(granted, permanent)
    }
    LaunchedEffect(state) { if (state is ListenState.NeedsPermission) permission.launch(Manifest.permission.RECORD_AUDIO) }

    val status = vm.recognitionStatus()
    val phase = when (state) {
        is ListenState.Listening -> ButtonPhase.LISTENING
        is ListenState.Processing -> ButtonPhase.PROCESSING
        else -> ButtonPhase.IDLE
    }
    val isDemoRun = (state as? ListenState.Listening)?.demo ?: (state as? ListenState.Processing)?.demo ?: false

    val headline = when (state) {
        is ListenState.Listening -> "Listening…"
        is ListenState.Processing -> "Identifying…"
        is ListenState.NeedsPermission -> "Microphone access"
        else -> "Tap to listen"
    }
    val sub = when {
        isDemoRun -> "Demo mode: nothing is recorded and this is an example result."
        state is ListenState.Listening -> "Hold your phone near the music."
        state is ListenState.Processing -> "Matching what we heard."
        state is ListenState.NeedsPermission -> "Choose “While using the app” to continue."
        demo && !status.realRecognitionAvailable -> "Demo mode is on. Results are examples, not real recognition."
        else -> "Identify a song, then explore its story."
    }

    // When a status card appears below the fold, bring it (and its buttons) into view.
    val scroll = rememberScrollState()
    val showsCard = state is ListenState.Unavailable || state is ListenState.PermissionDenied ||
        state is ListenState.NoMatch || state is ListenState.Error
    LaunchedEffect(state) {
        if (showsCard) { delay(80); scroll.scrollTo(scroll.maxValue) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = TmiDims.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        SlotImage(AssetSlot.LOGO, Modifier.height(64.dp), ContentScale.Fit) {
            Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(TmiIcons.MusicNote, null, Modifier.size(30.dp), tint = TmiColors.Primary)
                Text("Touch Music ID", style = MaterialTheme.typography.headlineMedium, color = TmiColors.TextHigh)
            }
        }
        Spacer(Modifier.height(10.dp))
        ModePill(realAvailable = status.realRecognitionAvailable, demo = demo)
        Spacer(Modifier.weight(1f, fill = true).height(8.dp))

        Text(
            headline, style = MaterialTheme.typography.headlineLarge, color = TmiColors.TextHigh, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))

        ListenButton(phase, onClick = vm::onListenTapped)
        Waveform(active = phase != ButtonPhase.IDLE, Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(16.dp))

        StatusCard(state, vm, onOpenHelp)
        Spacer(Modifier.weight(1f, fill = true).height(24.dp))
    }
}

@Composable
private fun ModePill(realAvailable: Boolean, demo: Boolean) {
    when {
        realAvailable -> Pill("Live recognition", TmiColors.Success)
        demo -> Pill("Demo mode · examples only", TmiColors.Warm)
        else -> Pill("Recognition not configured", TmiColors.TextMedium)
    }
}

@Composable
private fun StatusCard(state: ListenState, vm: MainViewModel, onOpenHelp: () -> Unit) {
    val ctx = LocalContext.current
    when (val s = state) {
        is ListenState.Unavailable -> GlassCard {
            Text("Recognition isn't set up yet", style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh)
            Text(
                when (s.reason) {
                    UnavailableReason.SDK_NOT_LINKED ->
                        "This build doesn't include Apple's ShazamKit SDK, so it can't identify music yet. " +
                            "Nothing is faked: until it's linked, the Listen button can't produce a real match."
                    UnavailableReason.TOKEN_SERVICE_NOT_CONFIGURED ->
                        "ShazamKit is present but no token service is configured, so Apple can't authorise requests."
                },
                style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
            )
            Text(
                "You can still try the full experience with Demo mode: example songs, real Wikipedia and Wikidata lookups.",
                style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TmiButton("Try demo mode", { vm.setDemoMode(true); vm.dismissListenMessage(); vm.onListenTapped() }, Modifier.weight(1f), compact = true)
                TmiButton("Setup guide", { vm.dismissListenMessage(); onOpenHelp() }, Modifier.weight(1f), primary = false, compact = true)
            }
        }
        is ListenState.PermissionDenied -> GlassCard {
            Text("Microphone is off", style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh)
            Text(
                "Touch Music ID only listens when you tap the button, and never keeps the audio. Without the microphone it can't identify songs.",
                style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
            )
            if (s.permanent) {
                TmiButton("Open app settings", {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                }, Modifier.fillMaxWidth())
            } else {
                TmiButton("Try again", { vm.dismissListenMessage(); vm.onListenTapped() }, Modifier.fillMaxWidth())
            }
        }
        is ListenState.NoMatch -> GlassCard {
            Text("No match this time", style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh)
            Text(
                "Move closer to the speaker, reduce background noise, and let the song play for a few seconds.",
                style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
            )
            TmiButton("Listen again", { vm.dismissListenMessage(); vm.onListenTapped() }, Modifier.fillMaxWidth())
        }
        is ListenState.Error -> GlassCard {
            Text(
                when (s.kind) {
                    FailureKind.NETWORK -> "No connection"
                    FailureKind.TIMEOUT -> "That took too long"
                    FailureKind.AUDIO -> "Couldn't use the microphone"
                    FailureKind.TOKEN -> "Authorisation problem"
                    FailureKind.UNKNOWN -> "Something went wrong"
                },
                style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh,
            )
            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium)
            TmiButton("Try again", { vm.dismissListenMessage(); vm.onListenTapped() }, Modifier.fillMaxWidth())
        }
        else -> Unit
    }
}
