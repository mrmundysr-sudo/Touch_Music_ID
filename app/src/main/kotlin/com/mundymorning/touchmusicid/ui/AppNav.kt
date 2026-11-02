package com.mundymorning.touchmusicid.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mundymorning.touchmusicid.design.AssetSlot
import com.mundymorning.touchmusicid.design.SlotBackground
import com.mundymorning.touchmusicid.design.SlotImage
import com.mundymorning.touchmusicid.design.TmiColors
import com.mundymorning.touchmusicid.design.TmiDims
import com.mundymorning.touchmusicid.design.TmiIcons

private object Routes {
    const val LISTEN = "listen"
    const val HISTORY = "history"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val HELP = "help"
    const val RESULT = "result/{key}"
    fun result(key: String) = "result/${Uri.encode(key)}"
}

private data class Tab(val route: String, val label: String, val off: AssetSlot, val on: AssetSlot, val icon: ImageVector, val iconOn: ImageVector)

private val tabs = listOf(
    Tab(Routes.LISTEN, "Listen", AssetSlot.NAV_LISTEN, AssetSlot.NAV_LISTEN_ON, TmiIcons.Mic, TmiIcons.Mic),
    Tab(Routes.HISTORY, "History", AssetSlot.NAV_HISTORY, AssetSlot.NAV_HISTORY_ON, TmiIcons.History, TmiIcons.History),
    Tab(Routes.FAVORITES, "Favorites", AssetSlot.NAV_FAVORITES, AssetSlot.NAV_FAVORITES_ON, Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
    Tab(Routes.SETTINGS, "Settings", AssetSlot.NAV_SETTINGS, AssetSlot.NAV_SETTINGS_ON, Icons.Filled.Settings, Icons.Filled.Settings),
)

@Composable
fun TouchMusicIdRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = tabs.any { it.route == route }

    LaunchedEffect(Unit) { vm.openResult.collect { key -> nav.navigate(Routes.result(key)) } }

    // Release the microphone whenever the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.cancelListening() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val bg = when (route) {
        Routes.LISTEN -> AssetSlot.BG_LISTEN
        Routes.RESULT -> AssetSlot.BG_RESULT
        Routes.HISTORY, Routes.FAVORITES -> AssetSlot.BG_LIBRARY
        else -> AssetSlot.BG_SETTINGS
    }

    Box(Modifier.fillMaxSize()) {
        SlotBackground(bg)
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (topLevel) BottomNav(route) { target ->
                    nav.navigate(target) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
        ) { pad ->
            val content = Modifier.padding(pad).statusBarsPadding().let { if (topLevel) it else it.navigationBarsPadding() }
            NavHost(nav, startDestination = Routes.LISTEN, modifier = content) {
                composable(Routes.LISTEN) { ListenScreen(vm, onOpenHelp = { nav.navigate(Routes.HELP) }) }
                composable(Routes.HISTORY) { HistoryScreen(vm) { nav.navigate(Routes.result(it)) } }
                composable(Routes.FAVORITES) { FavoritesScreen(vm) { nav.navigate(Routes.result(it)) } }
                composable(Routes.SETTINGS) { SettingsScreen(vm, onOpenHelp = { nav.navigate(Routes.HELP) }) }
                composable(Routes.HELP) { HelpScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.RESULT, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
                    ResultScreen(vm, Uri.decode(entry.arguments?.getString("key").orEmpty()), onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

@Composable
private fun BottomNav(current: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(TmiColors.BackgroundDeep.copy(alpha = 0.94f)).navigationBarsPadding().height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.route == current
            Column(
                Modifier.weight(1f).height(68.dp).selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab.route) }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                val tint = if (selected) TmiColors.Primary else TmiColors.TextLow
                SlotImage(if (selected) tab.on else tab.off, Modifier.size(26.dp), ContentScale.Fit) {
                    Icon(if (selected) tab.iconOn else tab.icon, null, Modifier.size(26.dp), tint = tint)
                }
                Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
