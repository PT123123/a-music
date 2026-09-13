package com.amusic.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * An accent palette = the app's "theme colour" knob. [accent] drives buttons,
 * selected tabs, sliders and the favourite heart; the three `glow*` colours feed the
 * animated aurora background so the whole app changes mood together.
 */
data class AccentPalette(
    val id: String,
    val label: String,
    val accent: Color,
    val onAccent: Color,
    val glowA: Color,
    val glowB: Color,
    val glowC: Color,
)

val Palettes: List<AccentPalette> = listOf(
    AccentPalette(
        id = "QQ_GREEN",
        label = "QQ 绿",
        accent = QQGreen,
        onAccent = Color(0xFF07120C),
        glowA = Color(0xFF1FC76B),
        glowB = Color(0xFF14806B),
        glowC = Color(0xFF2A6BFF),
    ),
    AccentPalette(
        id = "OCEAN",
        label = "海洋蓝",
        accent = Color(0xFF3AA0FF),
        onAccent = Color(0xFF04101C),
        glowA = Color(0xFF3AA0FF),
        glowB = Color(0xFF6C4CFF),
        glowC = Color(0xFF00D2C6),
    ),
    AccentPalette(
        id = "SUNSET",
        label = "日落橙",
        accent = Color(0xFFFF7A45),
        onAccent = Color(0xFF1C0C05),
        glowA = Color(0xFFFF7A45),
        glowB = Color(0xFFFFC14D),
        glowC = Color(0xFFE0457B),
    ),
    AccentPalette(
        id = "VIOLET",
        label = "星空紫",
        accent = Color(0xFFB07CFF),
        onAccent = Color(0xFF120A20),
        glowA = Color(0xFFB07CFF),
        glowB = Color(0xFF6C4CFF),
        glowC = Color(0xFFFF6FA5),
    ),
    AccentPalette(
        id = "ROSE",
        label = "樱粉",
        accent = Color(0xFFFF6FA5),
        onAccent = Color(0xFF200811),
        glowA = Color(0xFFFF6FA5),
        glowB = Color(0xFFFF9ED1),
        glowC = Color(0xFF7A5CFF),
    ),
)

fun paletteById(id: String?): AccentPalette =
    Palettes.firstOrNull { it.id == id } ?: Palettes.first()

val LocalAccent = staticCompositionLocalOf { Palettes.first() }

/** Derive a per-song colour triple from a title, so every track gets its own mood. */
fun songGlow(palette: AccentPalette, seed: String): Triple<Color, Color, Color> {
    var h = 0
    for (c in seed) h = h * 31 + c.code
    val base = ((h % 360) + 360) % 360
    fun shade(degOffset: Int, sat: Float, value: Float, alpha: Float): Color {
        val hsv = floatArrayOf(
            ((base + degOffset) % 360 + 360) % 360f,
            sat,
            value,
        )
        return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = alpha)
    }
    return Triple(
        shade(0, 0.72f, 0.85f, 0.55f),
        shade(38, 0.62f, 0.62f, 0.45f),
        shade(-52, 0.58f, 0.75f, 0.35f),
    )
}

/**
 * Animated "aurora" backdrop: three slowly drifting radial gradients over a dark base.
 * Everything is drawn on a Canvas, so no bitmaps and no extra dependencies.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    seed: String = "",
    palette: AccentPalette = LocalAccent.current,
    intensity: Float = 1f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val glows = if (seed.isEmpty()) {
        Triple(
            palette.glowA.copy(alpha = 0.50f),
            palette.glowB.copy(alpha = 0.40f),
            palette.glowC.copy(alpha = 0.30f),
        )
    } else {
        songGlow(palette, seed)
    }

    val transition = rememberInfiniteTransition(label = "aurora")
    // NOTE: keep this as a State and read it inside the Canvas draw block. Reading it in
    // the composable body (e.g. `by transition.animateFloat(...)`) would recompose this
    // whole subtree — including the caller's content — on every animation frame.
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Box(modifier.background(Background)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val theta = phase.value * TWO_PI
            drawBlob(w * (0.22f + 0.16f * sin(theta)), h * (0.16f + 0.12f * cos(theta * 0.7f)), w * 0.95f, glows.first, intensity)
            drawBlob(w * (0.85f + 0.14f * cos(theta * 0.8f)), h * (0.34f + 0.16f * sin(theta * 0.6f)), w * 1.05f, glows.second, intensity)
            drawBlob(w * (0.45f + 0.20f * sin(theta * 1.2f + 1.6f)), h * (0.90f + 0.10f * cos(theta * 0.9f)), w * 1.10f, glows.third, intensity)
            // Subtle vertical vignette so text stays readable at the edges.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.30f),
                    0.35f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.45f),
                ),
                size = size,
            )
        }
        content()
    }
}

private fun DrawScope.drawBlob(cx: Float, cy: Float, r: Float, color: Color, intensity: Float) {
    val center = Offset(cx, cy)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = (color.alpha * intensity).coerceIn(0f, 1f)), Color.Transparent),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

private const val TWO_PI = 6.2831855f

/** Small helper: a soft vertical scrim used on top of blurred artwork. */
fun scrimBrush(): Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.25f),
    0.45f to Color.Black.copy(alpha = 0.45f),
    1f to Color.Black.copy(alpha = 0.72f),
)

/** Tint filter used to keep the backdrop artwork muted. */
val MutedFilter: ColorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.35f))

/** Absolute value helper kept for readability in layout maths. */
internal fun fabs(v: Float): Float = abs(v)
