package dev.lannister

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * Three small bars that bounce while music plays. Decorative: Android does not give a launcher the audio levels of
 * another app, so this is not audio-reactive. It draws only while [active] is set and the view is on screen, at
 * about 20 frames a second, and posts nothing otherwise.
 */
class Equalizer(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var running = false

    var color: Int = 0xFF888888.toInt()
        set(v) { field = v; invalidate() }

    /** Set from the player state and the "Equalizer" setting. */
    var active: Boolean = false
        set(v) { field = v; sync() }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 50L)
        }
    }

    private fun sync() {
        val want = active && isAttachedToWindow && windowVisibility == VISIBLE && isShown
        if (want == running) { invalidate(); return }
        running = want
        removeCallbacks(frame)
        if (want) post(frame)
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(frame); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }
    override fun onVisibilityChanged(changed: View, visibility: Int) { super.onVisibilityChanged(changed, visibility); if (isAttachedToWindow) sync() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.12f
        val bar = (w - gap * 2) / 3f
        val t = SystemClock.uptimeMillis() / 1000f
        paint.color = color
        for (i in 0 until 3) {
            // Paused: three short, still bars. Playing: each bar moves at its own pace.
            val level = if (running) 0.28f + 0.72f * (0.5f + 0.5f * sin(t * (5.1f + i * 1.7f) + i * 1.3f)) else 0.3f
            val x = i * (bar + gap)
            rect.set(x, h * (1f - level), x + bar, h)
            canvas.drawRoundRect(rect, bar / 2, bar / 2, paint)
        }
    }
}
