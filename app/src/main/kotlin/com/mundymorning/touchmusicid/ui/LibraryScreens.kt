package com.mundymorning.touchmusicid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundymorning.touchmusicid.data.LibrarySong
import com.mundymorning.touchmusicid.design.AlbumArtBox
import com.mundymorning.touchmusicid.design.AssetSlot
import com.mundymorning.touchmusicid.design.EmptyState
import com.mundymorning.touchmusicid.design.Pill
import com.mundymorning.touchmusicid.design.TileBackground
import com.mundymorning.touchmusicid.design.TmiButton
import com.mundymorning.touchmusicid.design.TmiColors
import com.mundymorning.touchmusicid.design.TmiDims
import com.mundymorning.touchmusicid.domain.RecognitionSource
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val items by vm.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    LibraryList(
        title = "History", items = items,
        empty = { EmptyState(AssetSlot.EMPTY_HISTORY, "Nothing identified yet", "Songs you identify appear here, saved only on this phone.") },
        onOpen = onOpen,
        headerAction = if (items.orEmpty().isNotEmpty()) ({ TextButton({ confirmClear = true }) { Text("Clear all", color = TmiColors.Primary) } }) else null,
        trailing = { item ->
            IconButton({ item.historyId?.let(vm::deleteHistory) }, Modifier.size(TmiDims.MinTouch)) {
                Icon(Icons.Filled.Delete, "Delete ${item.song.title} from history", tint = TmiColors.TextMedium)
            }
        },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("This removes every identification from this phone. Favorites are kept.") },
        confirmButton = { TextButton({ vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
fun FavoritesScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val items by vm.favorites.collectAsStateWithLifecycle()
    LibraryList(
        title = "Favorites", items = items,
        empty = { EmptyState(AssetSlot.EMPTY_FAVORITES, "No favorites yet", "Tap the heart on a song to keep it here, even if you clear your history.") },
        onOpen = onOpen, headerAction = null,
        trailing = { item ->
            IconButton({ vm.toggleFavorite(item, true) }, Modifier.size(TmiDims.MinTouch)) {
                Icon(Icons.Filled.Favorite, "Remove ${item.song.title} from favorites", tint = TmiColors.Error)
            }
        },
    )
}

@Composable
private fun LibraryList(
    title: String, items: List<LibrarySong>?, empty: @Composable () -> Unit,
    onOpen: (String) -> Unit, headerAction: (@Composable () -> Unit)?, trailing: @Composable (LibrarySong) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = TmiDims.ScreenPadding, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = TmiColors.TextHigh, modifier = Modifier.weight(1f).semantics { heading() })
            headerAction?.invoke()
        }
        when {
            items == null -> Unit
            items.isEmpty() -> empty()
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = TmiDims.ScreenPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { "${it.historyId ?: "fav"}-${it.song.key}-${it.timestamp}" }) { item ->
                    SongTile(item, onClick = { onOpen(item.song.key) }, trailing = { trailing(item) })
                }
            }
        }
    }
}

@Composable
private fun SongTile(item: LibrarySong, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    TileBackground(Modifier.clickable(role = Role.Button, onClickLabel = "Open ${item.song.title}", onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AlbumArtBox(item.song, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.song.title, style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.song.artist, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.timestamp)),
                        style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow,
                    )
                    if (item.source == RecognitionSource.DEMO) Pill("Demo", TmiColors.Warm)
                }
            }
            trailing()
        }
    }
}
