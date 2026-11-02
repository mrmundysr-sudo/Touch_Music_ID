package com.mundymorning.touchmusicid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mundymorning.touchmusicid.design.GlassCard
import com.mundymorning.touchmusicid.design.TmiColors
import com.mundymorning.touchmusicid.design.TmiDims

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(TmiDims.MinTouch)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TmiColors.TextHigh) }
            Text("Setup guide", style = MaterialTheme.typography.titleLarge, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TmiDims.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(TmiDims.Gap),
        ) {
            Card(
                "What works today",
                "• Wikipedia and Wikidata lookups with sources, caching and offline fallback\n" +
                    "• History and favorites saved on this phone\n" +
                    "• Apple Music, Spotify and YouTube search links, and sharing\n" +
                    "• Demo mode, which returns labelled example songs",
            )
            Card(
                "What is blocked, and why",
                "Real song recognition. It needs two things this build does not have:\n" +
                    "1. Apple's ShazamKit for Android SDK. Apple distributes it as an AAR download from the developer site, so it is not bundled here.\n" +
                    "2. A developer token. Apple's ShazamKit documentation says the token is created from a media identifier and private key made in your Apple Developer account, then signed on a server you control. Apple's page doesn't say whether a free Apple ID is enough, so check that in Apple's developer portal. The key must never be placed in the app.\n\n" +
                    "Until both exist, the Listen button reports “not set up” and never invents a match.",
            )
            Card(
                "To switch on live recognition",
                "1. In your Apple Developer account, create a media identifier and private key (Apple: “Create a media identifier and private key”).\n" +
                    "2. Deploy a small token service that signs short-lived tokens with that key (reference design in docs/TOKEN_SERVICE.md).\n" +
                    "3. Download Apple's ShazamKit Android AAR into app/libs and add the engine described in docs/SHAZAMKIT_SETUP.md.\n" +
                    "4. Build with -PTOKEN_SERVICE_URL=https://your-service.example/token\n" +
                    "5. Test on a real phone with real music before trusting accuracy.",
            )
            Card(
                "How facts are checked",
                "An artist is shown only if exactly one Wikidata record has the same name and is a musician or group. " +
                    "A song is shown only if its title and performer both match. If several records fit, you choose. If none fit, we say so instead of guessing.",
            )
        }
    }
}

@Composable
private fun Card(title: String, body: String) = GlassCard {
    Text(title, style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })
    Text(body, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium)
}
