package com.mundymorning.touchmusicid.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundymorning.touchmusicid.BuildConfig
import com.mundymorning.touchmusicid.design.GlassCard
import com.mundymorning.touchmusicid.design.TmiButton
import com.mundymorning.touchmusicid.design.TmiColors
import com.mundymorning.touchmusicid.design.TmiDims
import com.mundymorning.touchmusicid.knowledge.ATTRIBUTION_WIKIDATA
import com.mundymorning.touchmusicid.knowledge.ATTRIBUTION_WIKIPEDIA

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenHelp: () -> Unit) {
    val demo by vm.demoMode.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val status = vm.recognitionStatus()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TmiDims.ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(TmiDims.Gap),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })

        GlassCard {
            Section("Recognition")
            StatusRow("Apple ShazamKit SDK", if (status.sdkLinked) "Linked" else "Not linked in this build", status.sdkLinked)
            StatusRow("Developer-token service", if (status.tokenServiceConfigured) "Configured" else "Not configured", status.tokenServiceConfigured)
            StatusRow("Live recognition", if (status.realRecognitionAvailable) "Available" else "Unavailable", status.realRecognitionAvailable)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo mode", style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh)
                    Text(
                        "Returns example songs so you can try the discovery experience. It does not listen and does not identify anything.",
                        style = MaterialTheme.typography.bodySmall, color = TmiColors.TextMedium,
                    )
                }
                Switch(
                    checked = demo, onCheckedChange = vm::setDemoMode,
                    modifier = Modifier.semantics { stateDescription = if (demo) "On" else "Off" },
                    colors = SwitchDefaults.colors(checkedTrackColor = TmiColors.Primary, checkedThumbColor = TmiColors.OnPrimary),
                )
            }
            TmiButton("Setup guide & what's blocked", onOpenHelp, Modifier.fillMaxWidth(), primary = false)
        }

        GlassCard {
            Section("Privacy")
            Text(
                "• The microphone is used only while you've tapped Listen, and stops when you cancel, leave the app, or a match arrives.\n" +
                    "• Raw audio is never saved or uploaded by this app.\n" +
                    "• History and favorites live only on this phone. No account, no analytics, no ads.\n" +
                    "• Looking up a song sends only its title and artist names to Wikipedia and Wikidata.",
                style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
            )
        }

        GlassCard {
            Section("Your data")
            TmiButton("Clear demo results", { vm.clearDemoData(); toast(ctx, "Demo results removed") }, Modifier.fillMaxWidth(), primary = false)
            TmiButton("Clear saved Wikipedia facts", { vm.clearKnowledgeCache(); toast(ctx, "Cached facts cleared") }, Modifier.fillMaxWidth(), primary = false)
            TmiButton("Clear all history", { vm.clearHistory(); toast(ctx, "History cleared (favorites kept)") }, Modifier.fillMaxWidth(), primary = false)
        }

        GlassCard {
            Section("About")
            Text("Touch Music ID ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextHigh)
            Text("Mundy Morning Studios. Unofficial debug build.", style = MaterialTheme.typography.bodySmall, color = TmiColors.TextMedium)
            Text(ATTRIBUTION_WIKIPEDIA, style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow)
            Text(ATTRIBUTION_WIKIDATA, style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow)
            Text(
                "Song recognition is designed around Apple's ShazamKit. Touch Music ID is not affiliated with or endorsed by Apple, Shazam, " +
                    "Spotify, YouTube or the Wikimedia Foundation. Names are used only to describe where links lead.",
                style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
private fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })

@Composable
private fun StatusRow(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (good) TmiColors.Success else TmiColors.Warm)
    }
}
