package com.amusic.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.amusic.ui.player.PlayQueueScreen
import com.amusic.ui.recommend.SimilarSongsScreen
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
                    composable(Routes.PLAY_QUEUE) { PlayQueueScreen(nav) }
                    composable(Routes.SIMILAR) { back ->
                        val id = back.arguments?.getString("songId")?.toLongOrNull() ?: return@composable
                        SimilarSongsScreen(nav, id)
                    }
                }
            }

            if (showMini) {
                MiniPlayer(nav, Modifier.fillMaxWidth())
            }

            // The player screen owns the full height: no mini player above and the tab bar
            // slides away, so nothing sits under the lyrics/controls.
            AnimatedVisibility(
                visible = route != Routes.NOW_PLAYING,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                BottomBar(nav, route)
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String) {
    val accent = LocalAccent.current
    // Compact custom tab bar: M3's NavigationBar reserves 80dp plus a fat indicator,
    // which dwarfs the content it sits under. 52dp with a small icon + 10sp label
    // reads just as well.
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val items = listOf(
            Routes.MUSIC_HALL to (Icons.Filled.LibraryMusic to "音乐馆"),
            Routes.DISCOVER to (Icons.Filled.Explore to "发现"),
            Routes.MINE to (Icons.Filled.Person to "我的"),
        )
        items.forEach { (r, iconAndLabel) ->
            val (icon, label) = iconAndLabel
            val selected = route == r
            val tint = if (selected) accent.accent else TextPrimary
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        nav.navigate(r) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(2.dp))
                Text(label, color = tint, fontSize = 10.sp)
            }
        }
    }
}
