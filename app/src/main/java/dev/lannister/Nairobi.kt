package dev.lannister

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.sin

/**
 * Everything that depends on Nairobi local time (Africa/Nairobi, UTC+3, no daylight saving).
 * The functions that take minutes-since-midnight are pure, so the boundary logic can be tested without Android.
 */
object Nairobi {
    const val ZONE = "Africa/Nairobi"
    const val DEFAULT_DAY_START = 6 * 60 + 30 // 06:30, about Nairobi's sunrise
    const val DEFAULT_NIGHT_START = 18 * 60 + 30 // 18:30, about its sunset

    enum class Part { MORNING, AFTERNOON, EVENING, NIGHT }

    private val zone: TimeZone get() = TimeZone.getTimeZone(ZONE)

    /** Minutes since midnight in Nairobi right now. */
    fun minutesNow(nowMillis: Long = System.currentTimeMillis()): Int {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = nowMillis
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Morning 05:00-11:59, afternoon 12:00-16:59, evening 17:00-20:59, night 21:00-04:59. */
    fun part(minutes: Int): Part = when (minutes) {
        in 5 * 60 until 12 * 60 -> Part.MORNING
        in 12 * 60 until 17 * 60 -> Part.AFTERNOON
        in 17 * 60 until 21 * 60 -> Part.EVENING
        else -> Part.NIGHT
    }

    /** True from [nightStart] until [dayStart] the next morning. Works when the night starts after midnight too. */
    fun isNight(minutes: Int, dayStart: Int = DEFAULT_DAY_START, nightStart: Int = DEFAULT_NIGHT_START): Boolean {
        if (dayStart == nightStart) return false
        return if (dayStart < nightStart) minutes < dayStart || minutes >= nightStart
        else minutes in nightStart until dayStart
    }

    fun isNight(prefs: SharedPreferences, nowMillis: Long = System.currentTimeMillis()) =
        isNight(minutesNow(nowMillis), prefs.getInt("nbo_day", DEFAULT_DAY_START), prefs.getInt("nbo_night", DEFAULT_NIGHT_START))

    fun auto(prefs: SharedPreferences) = prefs.getBoolean("nbo_auto", true)

    fun clock(minutes: Int) = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

    // ---------- the drawn skyline ----------
    // Not a photograph: a painted Nairobi skyline used until you add photos of your own (see README).
    // Rendered once per use, then handled like any other wallpaper bitmap, so it fades like one too.

    private fun sky(night: Boolean, w: Int, h: Int, ground: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val hh = h.toFloat()
        val ww = w.toFloat()
        // Sky: pale blue into a hazy horizon by day, deep navy into a warm glow by night. The top stays calm for text.
        val top = if (night) 0xFF070B1A.toInt() else 0xFFBFD8F0.toInt()
        val mid = if (night) 0xFF121C3A.toInt() else 0xFFDDE9F5.toInt()
        val low = if (night) 0xFF3A2F45.toInt() else 0xFFF3F2EC.toInt()
        p.shader = LinearGradient(0f, 0f, 0f, hh * 0.72f, intArrayOf(top, mid, low), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, ww, hh, p)
        p.shader = null
        if (night) {
            // Stars and a moon, high and to the right.
            var seed = 7L
            fun rnd(): Float { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return seed / 2147483647f }
            p.color = Color.WHITE
            repeat(110) {
                p.alpha = 70 + (rnd() * 150).toInt()
                c.drawCircle(rnd() * ww, rnd() * hh * 0.42f, 0.8f + rnd() * 1.6f, p)
            }
            p.alpha = 255
            p.shader = RadialGradient(ww * 0.8f, hh * 0.16f, hh * 0.17f, 0x55DCE6FF, 0, Shader.TileMode.CLAMP)
            c.drawCircle(ww * 0.8f, hh * 0.16f, hh * 0.17f, p)
            p.shader = null
            p.color = 0xFFF2F0E4.toInt()
            c.drawCircle(ww * 0.8f, hh * 0.16f, hh * 0.034f, p)
        } else {
            p.shader = RadialGradient(ww * 0.78f, hh * 0.2f, hh * 0.2f, 0x66FFF6D8, 0, Shader.TileMode.CLAMP)
            c.drawCircle(ww * 0.78f, hh * 0.2f, hh * 0.2f, p)
            p.shader = null
        }
        // Skyline layers, far to near. x/w/h are fractions of the canvas; the base line sits at 74% height.
        val base = hh * 0.74f
        fun layer(color: Int, windows: Int, lit: Boolean, build: (Path) -> Unit) {
            val path = Path().also(build)
            p.color = color
            c.drawPath(path, p)
            if (lit && night) drawWindows(c, path, windows, ww, hh)
        }
        val far = if (night) 0xFF1B2342.toInt() else 0xFFB4C4D6.toInt()
        val mids = if (night) 0xFF10172D.toInt() else 0xFF8DA0B6.toInt()
        val near = if (night) 0xFF080C1B.toInt() else 0xFF5F7088.toInt()
        fun block(path: Path, x: Float, wf: Float, hf: Float) = path.addRect(ww * x, base - hh * hf, ww * (x + wf), base, Path.Direction.CW)
        layer(far, 90, false) { pa ->
            var x = 0.0f
            var i = 0
            while (x < 1f) {
                val wd = 0.035f + 0.02f * ((i * 7) % 3)
                block(pa, x, wd, 0.12f + 0.05f * ((i * 5) % 4))
                x += wd + 0.004f; i++
            }
        }
        layer(mids, 150, true) { pa ->
            // Mixed mid-rise blocks with a few taller towers.
            val specs = listOf(
                0.02f to 0.19f, 0.06f to 0.27f, 0.10f to 0.22f, 0.15f to 0.32f, 0.20f to 0.24f, 0.25f to 0.29f,
                0.33f to 0.26f, 0.40f to 0.35f, 0.46f to 0.28f, 0.58f to 0.30f, 0.64f to 0.25f, 0.70f to 0.37f,
                0.77f to 0.27f, 0.83f to 0.31f, 0.90f to 0.24f, 0.95f to 0.28f,
            )
            for ((x, ht) in specs) block(pa, x, 0.04f, ht)
        }
        // Landmarks, drawn from the Nairobi CBD silhouette: KICC's tower with its round helipad roof, a slim
        // spired tower, and a stepped office block. Simplified shapes, not to scale.
        layer(near, 220, true) { pa ->
            val kx = ww * 0.52f
            val kw = ww * 0.036f
            pa.addRect(kx - kw / 2, base - hh * 0.46f, kx + kw / 2, base, Path.Direction.CW) // KICC shaft
            pa.addOval(kx - kw * 0.95f, base - hh * 0.50f, kx + kw * 0.95f, base - hh * 0.455f, Path.Direction.CW) // roof disc
            pa.moveTo(kx - kw * 0.12f, base - hh * 0.50f); pa.lineTo(kx, base - hh * 0.58f); pa.lineTo(kx + kw * 0.12f, base - hh * 0.50f); pa.close() // mast
            block(pa, 0.30f, 0.045f, 0.40f)
            pa.moveTo(ww * 0.302f, base - hh * 0.40f); pa.lineTo(ww * 0.3225f, base - hh * 0.50f); pa.lineTo(ww * 0.343f, base - hh * 0.40f); pa.close() // spire
            block(pa, 0.62f, 0.05f, 0.33f)
            block(pa, 0.635f, 0.03f, 0.39f)
            block(pa, 0.18f, 0.05f, 0.30f)
            block(pa, 0.08f, 0.04f, 0.23f)
            block(pa, 0.74f, 0.06f, 0.28f)
            block(pa, 0.86f, 0.045f, 0.34f)
            block(pa, 0.0f, 0.06f, 0.20f)
            block(pa, 0.94f, 0.06f, 0.22f)
            block(pa, 0.43f, 0.05f, 0.26f)
        }
        // Foreground road line and trees' hint: a low dark strip, then haze into the home ground colour.
        p.color = if (night) 0xFF05070F.toInt() else 0xFF45546A.toInt()
        c.drawRect(0f, base, ww, hh, p)
        val g = ground and 0x00FFFFFF
        p.shader = LinearGradient(0f, hh * 0.66f, 0f, hh * 0.9f, g, g or 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, hh * 0.66f, ww, hh, p)
        p.shader = null
        if (night) { // a faint warm haze of city light along the horizon
            p.shader = LinearGradient(0f, hh * 0.52f, 0f, base, 0, 0x30FFB45A, Shader.TileMode.CLAMP)
            c.drawRect(0f, hh * 0.52f, ww, base, p)
            p.shader = null
        }
        return bmp
    }

    private fun drawWindows(c: Canvas, path: Path, count: Int, ww: Float, hh: Float) {
        val b = android.graphics.RectF()
        path.computeBounds(b, true)
        val clip = Path(path)
        c.save()
        c.clipPath(clip)
        val p = Paint()
        var seed = count.toLong()
        fun rnd(): Float { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return seed / 2147483647f }
        val stepX = ww * 0.0075f
        val stepY = hh * 0.013f
        var y = b.top + stepY
        while (y < b.bottom - stepY) {
            var x = b.left + stepX
            while (x < b.right - stepX) {
                if (rnd() < 0.34f) {
                    p.color = if (rnd() < 0.8f) 0xFFFFD58A.toInt() else 0xFFBFE3FF.toInt()
                    p.alpha = 120 + (sin(x + y) * 40 + 60).toInt().coerceIn(0, 135)
                    c.drawRect(x, y, x + stepX * 0.55f, y + stepY * 0.55f, p)
                }
                x += stepX
            }
            y += stepY
        }
        c.restore()
    }

    /** The drawn skyline for [night], fading into [ground] at the bottom like the bundled wallpapers do. */
    fun skyline(night: Boolean, ground: Int, w: Int = 1920, h: Int = 1080): Bitmap = sky(night, w, h, ground)
}
