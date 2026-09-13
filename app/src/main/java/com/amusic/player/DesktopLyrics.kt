package com.amusic.player

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Floating "desktop lyrics" window — the two-line overlay that sits on top of every other app,
 * QQ-Music style. Implemented with plain framework views rather than Compose: it lives outside
 * any Activity, has no recomposition needs, and a TextView is far cheaper for a window that is
 * redrawn on every lyric line.
 *
 * The window is added by [PlaybackService] (it needs a long-lived owner) and needs
 * `SYSTEM_ALERT_WINDOW`; callers should gate on [canShow] and send the user to the system
 * settings page when it returns false.
 */
object DesktopLyrics {

    private const val TAG = "DesktopLyrics"

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var primary: TextView? = null
    private var secondary: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = root != null

    /** True when the overlay permission is granted (older devices never needed it). */
    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(context: Context) {
        if (isShowing) return
        if (!canShow(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — desktop lyrics stay hidden")
            return
        }
        val ctx = context.applicationContext
        runCatching {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val p = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 17f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                setShadowLayer(6f, 0f, 1f, Color.BLACK)
            }
            val s = TextView(ctx).apply {
                setTextColor(0xFFDDDDDD.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                setShadowLayer(6f, 0f, 1f, Color.BLACK)
                visibility = View.GONE
            }
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 18).toFloat()
                    setColor(0x99101010.toInt())
                    setStroke(dp(ctx, 1), 0x33FFFFFF)
                }
                addView(p)
                addView(s)
                setOnTouchListener(dragListener(wm))
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(ctx, 96)
            }

            // Keep long lines from stretching past the screen edges.
            val maxPx = (ctx.resources.displayMetrics.widthPixels * 0.92f).roundToInt()
            p.maxWidth = maxPx
            s.maxWidth = maxPx

            wm.addView(container, lp)

            windowManager = wm
            root = container
            primary = p
            secondary = s
            params = lp
        }.onFailure { Log.w(TAG, "could not add overlay window", it) }
    }

    fun hide() {
        val wm = windowManager ?: return
        val v = root ?: return
        runCatching { wm.removeViewImmediate(v) }
        windowManager = null
        root = null
        primary = null
        secondary = null
        params = null
    }

    /** Push the current lyric line. [translation] is hidden when blank. */
    fun update(line: String?, translation: String?) {
        val p = primary ?: return
        p.text = line?.takeIf { it.isNotBlank() } ?: "♪"
        val s = secondary
        if (s != null) {
            val t = translation?.takeIf { it.isNotBlank() }
            s.text = t.orEmpty()
            s.visibility = if (t == null) View.GONE else View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ internals

    /** Drag-to-move; the initial touch offset is kept so the window doesn't jump. */
    private fun dragListener(wm: WindowManager): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        return View.OnTouchListener { _, ev ->
            val lp = params ?: return@OnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (ev.rawX - downX).roundToInt()
                    lp.y = startY + (ev.rawY - downY).roundToInt()
                    runCatching { wm.updateViewLayout(root, lp) }
                    true
                }

                else -> false
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
