package com.amusic.ui.mine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.amusic.BuildConfig
import com.amusic.MainApplication
import com.amusic.data.prefs.PlayerStyle
import com.amusic.player.DesktopLyrics
import com.amusic.ui.components.ChoicePill
import com.amusic.ui.components.DetailRow
import com.amusic.ui.components.EqualizerPanel
import com.amusic.ui.components.HairLine
import com.amusic.ui.theme.AuroraBackground
import com.amusic.ui.theme.LocalAccent
import com.amusic.ui.theme.Palettes
import com.amusic.ui.theme.TextPrimary
import com.amusic.ui.theme.TextSecondary

/** Settings: player layout, lyrics surfaces, equalizer, accent colour and library stats. */
@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val settings = app.settings
    val style by settings.playerStyle.collectAsState()
    val accentId by settings.accentId.collectAsState()
    val sleepMinutes by settings.sleepMinutes.collectAsState()
    val lyricsInNotification by settings.lyricsInNotification.collectAsState()
    val desktopLyrics by settings.desktopLyrics.collectAsState()

    val songCount by app.repository.songs.collectAsState(initial = emptyList())
    val favCount by app.repository.favoriteSongs.collectAsState(initial = emptyList())
    val trashCount by app.repository.trash.collectAsState(initial = emptyList())

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                }
                Text("设置", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SectionTitle("歌词")
                ToggleRow(
                    title = "状态栏 / 锁屏歌词",
                    subtitle = "把正在唱的那句写进播放通知，锁屏上也能看到",
                    checked = lyricsInNotification,
                    onCheckedChange = { settings.setLyricsInNotification(it) },
                )
                ToggleRow(
                    title = "桌面歌词",
                    subtitle = if (desktopLyrics && !DesktopLyrics.canShow(context))
                        "已开启，但还缺「显示在其他应用上层」权限，点一下去授权"
                    else
                        "悬浮在所有应用最上层，可拖动",
                    checked = desktopLyrics,
                    onCheckedChange = { on ->
                        settings.setDesktopLyrics(on)
                        if (on) ensureOverlayPermission(context)
                    },
                )

                Spacer(Modifier.padding(top = 8.dp))
                HairLine()

                SectionTitle("均衡器")
                EqualizerPanel(settings)

                Spacer(Modifier.padding(top = 8.dp))
                HairLine()

                SectionTitle("播放器样式")
                PlayerStyle.values().forEach { s ->
                    ChoicePill(
                        text = s.label,
                        selected = s == style,
                        subtitle = s.hint,
                        onClick = { settings.setPlayerStyle(s) },
                    )
                }

                Spacer(Modifier.padding(top = 8.dp))
                HairLine()

                SectionTitle("主题配色")
                Palettes.forEach { p ->
                    ChoicePill(
                        text = p.label,
                        selected = p.id == accentId,
                        trailing = if (p.id == accentId) "使用中" else null,
                        onClick = { settings.setAccentId(p.id) },
                    )
                }

                Spacer(Modifier.padding(top = 8.dp))
                HairLine()

                SectionTitle("定时播放")
                Text(
                    "默认时长 $sleepMinutes 分钟；播放页的 ⏱ 按钮可随时开启、修改或关闭定时。",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth()) {
                    listOf(15, 30, 45, 60).forEach { m ->
                        Column(Modifier.weight(1f)) {
                            ChoicePill(
                                text = "$m 分",
                                selected = sleepMinutes == m,
                                onClick = { settings.setSleepMinutes(m) },
                            )
                        }
                    }
                }

                Spacer(Modifier.padding(top = 8.dp))
                HairLine()

                SectionTitle("曲库")
                DetailRow("歌曲总数", "${songCount.size} 首")
                DetailRow("已收藏", "${favCount.size} 首", highlight = true)
                DetailRow("回收站", if (trashCount.isEmpty()) "空" else "${trashCount.size} 首")

                Spacer(Modifier.padding(top = 8.dp))
                HairLine()

                SectionTitle("关于")
                DetailRow("版本", BuildConfig.VERSION_NAME)
                DetailRow("播放内核", "libmpv（JNI 绑定，纯音频管线）")
                DetailRow("均衡器", "mpv `af` → ffmpeg equalizer（8 段）")
                DetailRow("歌词来源", "同名 .lrc 旁挂文件优先，其次联网匹配 QQ 音乐歌词")

                Spacer(Modifier.padding(top = 24.dp))
            }

            Spacer(Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (checked) accent.accent else TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent.onAccent,
                checkedTrackColor = accent.accent,
            ),
        )
    }
}

/**
 * Send the user to the "display over other apps" page when they switch desktop lyrics on
 * without the grant. Android has no runtime-request dialog for this permission.
 */
private fun ensureOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    if (Settings.canDrawOverlays(context)) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = LocalAccent.current.accent,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}
