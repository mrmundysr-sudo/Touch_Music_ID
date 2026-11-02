package com.mundymorning.touchmusicid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.mundymorning.touchmusicid.data.LibrarySong
import com.mundymorning.touchmusicid.design.AlbumArtBox
import com.mundymorning.touchmusicid.design.AssetSlot
import com.mundymorning.touchmusicid.design.CompactNotice
import com.mundymorning.touchmusicid.design.GlassCard
import com.mundymorning.touchmusicid.design.Pill
import com.mundymorning.touchmusicid.design.TmiButton
import com.mundymorning.touchmusicid.design.TmiColors
import com.mundymorning.touchmusicid.design.TmiDims
import com.mundymorning.touchmusicid.design.TmiIcons
import com.mundymorning.touchmusicid.domain.RecognitionSource
import com.mundymorning.touchmusicid.knowledge.ATTRIBUTION_WIKIDATA
import com.mundymorning.touchmusicid.knowledge.ATTRIBUTION_WIKIPEDIA
import com.mundymorning.touchmusicid.knowledge.EntityKnowledge
import com.mundymorning.touchmusicid.knowledge.Fact
import com.mundymorning.touchmusicid.knowledge.Freshness
import com.mundymorning.touchmusicid.knowledge.KnowledgeBundle
import com.mundymorning.touchmusicid.knowledge.KnowledgeResult
import com.mundymorning.touchmusicid.knowledge.Lookup
import com.mundymorning.touchmusicid.knowledge.MatchStatus
import com.mundymorning.touchmusicid.links.ExternalLinks
import com.mundymorning.touchmusicid.links.Service
import java.text.DateFormat
import java.util.Date

@Composable
fun ResultScreen(vm: MainViewModel, songKey: String, onBack: () -> Unit) {
    LaunchedEffect(songKey) { vm.openSong(songKey) }
    val ui by vm.result.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val current = ui?.takeIf { it.item.song.key == songKey }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(TmiDims.MinTouch)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TmiColors.TextHigh) }
            Text("Song", style = MaterialTheme.typography.titleLarge, color = TmiColors.TextHigh, modifier = Modifier.weight(1f).semantics { heading() })
            if (current != null) {
                val isFav = favorites.orEmpty().any { it.song.key == songKey }
                IconButton({ vm.toggleFavorite(current.item, isFav) }, Modifier.size(TmiDims.MinTouch)) {
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (isFav) "Remove from favorites" else "Add to favorites",
                        tint = if (isFav) TmiColors.Error else TmiColors.TextHigh,
                    )
                }
                IconButton({
                    if (!ExternalLinks.share(ctx, current.item.song)) Toast.makeText(ctx, "No app available to share with", Toast.LENGTH_SHORT).show()
                }, Modifier.size(TmiDims.MinTouch)) { Icon(Icons.Filled.Share, "Share", tint = TmiColors.TextHigh) }
            }
        }

        if (current == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TmiColors.Primary) }
            return@Column
        }

        val song = current.item.song
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = TmiDims.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(TmiDims.Gap),
        ) {
            item { Header(current.item) }
            item { LinkRow(current.item) }
            when (val k = current.knowledge) {
                KnowledgeUi.Loading -> item { LoadingCard() }
                is KnowledgeUi.Error -> item { ErrorCard(k.kind, onRetry = vm::retryKnowledge) }
                is KnowledgeUi.Ready -> {
                    if (k.freshness == Freshness.CACHE_STALE_OFFLINE) item { OfflineBanner(k.bundle.fetchedAtMillis, vm::retryKnowledge) }
                    item { LookupSection("About the artist", song.artist, k.bundle.artist, vm::chooseArtist, vm::retryKnowledge) }
                    item { LookupSection("About the song", song.title, k.bundle.song, null, vm::retryKnowledge) }
                    item { SourcesCard(k.bundle) }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun Header(item: LibrarySong) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AlbumArtBox(item.song)
        Spacer(Modifier.height(8.dp))
        Text(item.song.title, style = MaterialTheme.typography.headlineMedium, color = TmiColors.TextHigh, textAlign = TextAlign.Center)
        Text(item.song.artist, style = MaterialTheme.typography.titleMedium, color = TmiColors.Primary, textAlign = TextAlign.Center)
        item.song.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium, textAlign = TextAlign.Center) }
        if (item.source == RecognitionSource.DEMO) {
            Pill("Demo result · not real recognition", TmiColors.Warm)
        }
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.timestamp)),
            style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow,
        )
    }
}

@Composable
private fun LinkRow(item: LibrarySong) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Listen on", style = MaterialTheme.typography.labelSmall, color = TmiColors.TextLow)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Service.entries.forEach { svc ->
                TmiButton(svc.label, {
                    if (svc == Service.SPOTIFY) {
                        // Spotify often ignores the query, so make pasting it one gesture away.
                        ExternalLinks.copySearchText(ctx, item.song)?.let {
                            Toast.makeText(ctx, "Copied “$it”. If Spotify's search is empty, paste it.", Toast.LENGTH_LONG).show()
                        }
                    }
                    val opened = if (svc == Service.SPOTIFY) ExternalLinks.openSpotify(ctx, item.song)
                    else ExternalLinks.open(ctx, ExternalLinks.url(svc, item.song))
                    if (!opened) {
                        Toast.makeText(ctx, "Couldn't open ${svc.label}", Toast.LENGTH_SHORT).show()
                    }
                }, Modifier.weight(1f), primary = false, compact = true)
            }
        }
        Text(
            "Links open a search for this song. Availability depends on each service.",
            style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow,
        )
    }
}

@Composable
private fun LoadingCard() = GlassCard {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), color = TmiColors.Primary, strokeWidth = 2.5.dp)
        Text("Looking up verified facts on Wikipedia and Wikidata…", style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium)
    }
}

@Composable
private fun ErrorCard(kind: KnowledgeResult.Kind, onRetry: () -> Unit) = GlassCard {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Warning, null, tint = TmiColors.Warm)
        Text(
            when (kind) {
                KnowledgeResult.Kind.OFFLINE -> "You're offline"
                KnowledgeResult.Kind.RATE_LIMITED -> "Wikipedia asked us to slow down"
                KnowledgeResult.Kind.SERVER -> "Wikipedia isn't responding"
                KnowledgeResult.Kind.UNKNOWN -> "Couldn't load facts"
            },
            style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh,
        )
    }
    Text(
        "The song details above are saved. Facts will load when the connection is back.",
        style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
    )
    TmiButton("Retry", onRetry, Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh)
}

@Composable
private fun OfflineBanner(fetchedAt: Long, onRetry: () -> Unit) = GlassCard {
    Text(
        "Showing saved facts from ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(fetchedAt))} because Wikipedia couldn't be reached.",
        style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
    )
    TmiButton("Refresh", onRetry, Modifier.fillMaxWidth(), primary = false, icon = Icons.Filled.Refresh)
}

@Composable
private fun LookupSection(title: String, subject: String, lookup: Lookup, onChoose: ((String) -> Unit)?, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    when (lookup.status) {
        MatchStatus.VERIFIED -> EntityCard(title, lookup.entity!!)
        MatchStatus.AMBIGUOUS -> GlassCard {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })
            if (lookup.candidates.isEmpty()) {
                Text(
                    "More than one record matches “$subject”, so nothing is shown rather than guessing.",
                    style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
                )
            } else {
                Text(
                    "Several musicians or groups are called “$subject”. Pick the right one. Nothing is shown until you do.",
                    style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium,
                )
                lookup.candidates.forEach { c ->
                    TmiButton(
                        listOfNotNull(c.label, c.description).joinToString(" · "),
                        { onChoose?.invoke(c.qid) }, Modifier.fillMaxWidth(), primary = false, enabled = onChoose != null,
                    )
                }
            }
        }
        MatchStatus.NOT_FOUND -> GlassCard {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })
            CompactNotice(
                AssetSlot.EMPTY_NO_ARTICLE, Icons.Filled.Search,
                "No verified article for “$subject”",
                "We checked, and no Wikipedia or Wikidata entry passed our name and type checks. Nothing here is guessed.",
            ) {
                TmiButton("Search Wikipedia", { ExternalLinks.open(ctx, ExternalLinks.wikipediaSearchUrl(subject)) }, Modifier.weight(1f), primary = false, compact = true, icon = Icons.Filled.Search)
                TmiButton("Check again", onRetry, Modifier.weight(1f), primary = false, compact = true, icon = Icons.Filled.Refresh)
            }
        }
        MatchStatus.LOOKUP_FAILED -> GlassCard {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })
            CompactNotice(
                null, Icons.Filled.Warning,
                when (lookup.failure) {
                    KnowledgeResult.Kind.OFFLINE -> "You're offline"
                    KnowledgeResult.Kind.RATE_LIMITED -> "Wikipedia asked us to slow down"
                    KnowledgeResult.Kind.SERVER -> "Wikipedia isn't responding"
                    else -> "Couldn't check right now"
                },
                "This says nothing about whether “$subject” has an article. Try again in a moment.",
            ) {
                TmiButton("Retry", onRetry, Modifier.weight(1f), compact = true, icon = Icons.Filled.Refresh)
            }
        }
    }
}

@Composable
private fun EntityCard(title: String, e: EntityKnowledge) {
    val ctx = LocalContext.current
    GlassCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })
        Text(e.name, style = MaterialTheme.typography.titleLarge, color = TmiColors.Primary)
        e.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium) }
        if (e.extract != null) {
            Text(e.extract, style = MaterialTheme.typography.bodyLarge, color = TmiColors.TextHigh)
            Pill("From Wikipedia", TmiColors.Accent)
        } else {
            Text("No Wikipedia article is linked to this record.", style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextLow)
        }
        if (e.facts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            e.facts.forEach { FactRow(it) }
        }
        Text("How we matched this: ${e.basis}", style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow)
        e.wikipediaUrl?.let {
            TmiButton("Read on Wikipedia", { ExternalLinks.open(ctx, it) }, Modifier.fillMaxWidth(), primary = false, icon = TmiIcons.OpenInNew)
        }
    }
}

@Composable
private fun FactRow(f: Fact) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(f.label, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextLow, modifier = Modifier.weight(0.38f))
        Column(Modifier.weight(0.62f)) {
            Text(f.value, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextHigh)
            Text(f.source.label, style = MaterialTheme.typography.labelSmall, color = TmiColors.Accent)
        }
    }
}

@Composable
private fun SourcesCard(bundle: KnowledgeBundle) {
    val ctx = LocalContext.current
    val sources = bundle.sources()
    GlassCard {
        Text("Sources & attribution", style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, modifier = Modifier.semantics { heading() })
        if (sources.isEmpty()) {
            Text("No sources were used because nothing could be verified.", style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium)
        }
        sources.forEach { s ->
            TmiButton(s.label, { ExternalLinks.open(ctx, s.url) }, Modifier.fillMaxWidth(), primary = false, icon = TmiIcons.OpenInNew)
        }
        Text(ATTRIBUTION_WIKIPEDIA, style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow)
        Text(ATTRIBUTION_WIKIDATA, style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow)
        Text(
            "Looked up ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(bundle.fetchedAtMillis))}. " +
                "Wikipedia and Wikidata are projects of the Wikimedia Foundation; Touch Music ID is not affiliated with them.",
            style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow,
        )
    }
}
