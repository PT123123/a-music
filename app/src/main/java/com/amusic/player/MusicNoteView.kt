package com.amusic.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * Custom view that displays animated music notes with a wave/rhythm effect.
 * Used for Live Activity / Dynamic Island visualization.
 */
class MusicNoteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5790FF.toInt() // QQ Green accent color
        style = Paint.Style.FILL
    }
    
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x805790FF.toInt()
        style = Paint.Style.FILL
    }
    
    // Note positions and sizes
    private var notePositions = mutableListOf<NoteData>()
    
    // Wave animation phase
    private var wavePhase = 0f
    
    // Animation
    private var animator: ValueAnimator? = null
    private var isAnimating = false
    
    data class NoteData(
        var x: Float,
        var y: Float,
        var size: Float,
        var phase: Float,
        var speed: Float
    )
    
    init {
        // Generate initial note positions
        generateNotes()
    }
    
    private fun generateNotes() {
        notePositions.clear()
        val noteCount = 5
        val spacing = width.toFloat() / (noteCount + 1)
        
        for (i in 0 until noteCount) {
            notePositions.add(NoteData(
                x = spacing * (i + 1),
                y = height / 2f,
                size = 12f + (i % 3) * 4f,
                phase = i * 0.5f,
                speed = 0.8f + (i % 3) * 0.2f
            ))
        }
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            generateNotes()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width == 0 || height == 0) return
        
        val centerY = height / 2f
        val maxAmplitude = height * 0.35f
        
        // Draw wave background
        drawWave(canvas, centerY, maxAmplitude)
        
        // Draw animated notes
        for (note in notePositions) {
            val animOffset = sin((wavePhase + note.phase) * Math.PI.toFloat() * 2) * maxAmplitude
            val noteY = centerY + animOffset
            
            // Draw note body
            drawMusicNote(canvas, note.x, noteY, note.size)
        }
    }
    
    private fun drawWave(canvas: Canvas, centerY: Float, amplitude: Float) {
        val path = Path()
        val waveLength = width / 3f
        
        path.moveTo(0f, centerY)
        
        for (x in 0..width step 4) {
            val y = centerY + sin((x / waveLength + wavePhase) * Math.PI.toFloat() * 2) * (amplitude * 0.5f)
            path.lineTo(x.toFloat(), y)
        }
        
        path.lineTo(width.toFloat(), height.toFloat())
        path.lineTo(0f, height.toFloat())
        path.close()
        
        canvas.drawPath(path, wavePaint)
    }
    
    private fun drawMusicNote(canvas: Canvas, x: Float, y: Float, size: Float) {
        val paint = Paint(notePaint).apply {
            alpha = 255
        }
        
        // Draw a simple eighth note symbol
        val path = Path()
        
        // Note head (oval)
        path.addOval(
            x - size * 0.4f,
            y + size * 0.2f,
            x + size * 0.4f,
            y + size * 0.8f,
            Path.Direction.CW
        )
        
        // Stem
        path.moveTo(x + size * 0.3f, y + size * 0.6f)
        path.lineTo(x + size * 0.3f, y - size * 0.8f)
        
        // Flag
        path.quadTo(
            x + size * 0.3f, y - size * 0.4f,
            x - size * 0.1f, y - size * 0.2f
        )
        
        canvas.drawPath(path, paint)
        
        // Add glow effect
        paint.alpha = 100
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(x, y - size * 0.2f, size * 0.6f, paint)
        paint.style = Paint.Style.FILL
    }
    
    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                wavePhase = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    fun stopAnimation() {
        isAnimating = false
        animator?.cancel()
        animator = null
        invalidate()
    }
    
    fun setNoteColor(color: Int) {
        notePaint.color = color
        invalidate()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
