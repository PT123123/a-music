package com.amusic.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.amusic.MainApplication
import com.amusic.player.PlayerController
import com.amusic.ui.library.ArtistDetailScreen
import com.amusic.ui.library.LibraryScreen
import com.amusic.ui.library.PlaylistDetailScreen
import com.amusic.ui.mine.FavoritesScreen
import com.amusic.ui.mine.MineScreen
import com.amusic.ui.mine.SettingsScreen
import com.amusic.ui.online.DiscoverScreen
import com.amusic.ui.player.MiniPlayer
import com.amusic.ui.player.NowPlayingScreen
import com.amusic.ui.trash.TrashScreen
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.Surface
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.paletteById

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val state by PlayerController.state.collectAsState()
    val navBackStackEntry by nav.currentBackStackEntryAsState()
    val route = navBackStackEntry?.destination?.route ?: Routes.MUSIC_HALL
    val showMini = state.current != null && route != Routes.NOW_PLAYING

    // Accent colour is a user setting, so it has to be read here and pushed down
    // through a CompositionLocal for every screen to pick up.
    val app = LocalContext.current.applicationContext as MainApplication
    val accentId by app.settings.accentId.collectAsState()
    val accent = paletteById(accentId)

    CompositionLocalProvider(LocalAccent provides accent) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavHost(nav, startDestination = Routes.MUSIC_HALL) {
                    composable(Routes.MUSIC_HALL) { LibraryScreen(nav) }
                    composable(Routes.DISCOVER) { DiscoverScreen(nav) }
                    composable(Routes.MINE) { MineScreen(nav) }
                    composable(Routes.ARTIST) { back ->
                        val name = java.net.URLDecoder.decode(back.arguments?.getString("name") ?: "", "UTF-8")
                        ArtistDetailScreen(nav, name)
                    }
                    composable(Routes.PLAYLIST) { back ->
                        val id = back.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                        PlaylistDetailScreen(nav, id)
                    }
                    composable(Routes.NOW_PLAYING) { NowPlayingScreen(nav) }
                    composable(Routes.FAVORITES) { FavoritesScreen(nav) }
                    composable(Routes.SETTINGS) { SettingsScreen(nav) }
                    composable(Routes.TRASH) { TrashScreen(nav) }
                }
            }

            if (showMini) {
                MiniPlayer(nav, Modifier.fillMaxWidth())
            }

            BottomBar(nav, route)
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String) {
    val accent = LocalAccent.current
    NavigationBar(containerColor = Surface, contentColor = TextPrimary) {
        val items = listOf(
            Routes.MUSIC_HALL to (Icons.Filled.LibraryMusic to "音乐馆"),
            Routes.DISCOVER to (Icons.Filled.Explore to "发现"),
            Routes.MINE to (Icons.Filled.Person to "我的"),
        )
        items.forEach { (r, iconAndLabel) ->
            val (icon, label) = iconAndLabel
            val selected = route == r
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(r) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accent.accent,
                    selectedTextColor = accent.accent,
                    unselectedIconColor = TextPrimary,
                    unselectedTextColor = TextPrimary,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
