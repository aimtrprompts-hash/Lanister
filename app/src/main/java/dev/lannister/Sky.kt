package dev.lannister

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Wallpaper and screensaver choice for the animated day and night sky. */
internal const val SKY = "sky:Live sky"

/**
 * A live sky drawn with plain canvas calls (no images, so it costs almost no memory). It follows the real day: the
 * sky changes colour through dawn, day, dusk and night, the sun and moon cross it, and stars come out. The weather
 * Lannister already fetched ("wx_code", sunrise and sunset) adds clouds, rain, snow, fog or a storm.
 * "sky_fast" in the "home" prefs speeds the day up to two minutes, to see it move.
 * [light] washes the colours out so dark text stays readable on the light theme.
 */
class SkyView(context: Context, var light: Boolean = false) : View(context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val rnd = Random(11)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var u = 1f // one 1080p pixel, in screen pixels
    private var last = 0L
    private var lastBuild = 0L
    private val started = SystemClock.elapsedRealtime()
    private val startMinutes = realMinutes()

    // Scene, rebuilt about 8 times a second.
    private var skyShader: Shader? = null
    private var glowShader: Shader? = null
    private var fogShader: Shader? = null
    private var top = 0
    private var bottom = 0
    private var night = 0f
    private var grey = 0f
    private var sunX = 0f
    private var sunY = 0f
    private var bodyIsSun = true
    private var bodyAlpha = 1f
    private var bodyColor = Color.WHITE
    private var cloudColor = Color.WHITE
    private var farHill = 0
    private var nearHill = 0
    private var rain = 0
    private var snow = 0
    private var storm = false
    private var fog = false
    private var cloudCount = 3

    // Particles.
    private val starX = FloatArray(90) { rnd.nextFloat() }
    private val starY = FloatArray(90) { rnd.nextFloat() * 0.7f }
    private val starR = FloatArray(90) { 0.8f + rnd.nextFloat() * 1.8f }
    private val starP = FloatArray(90) { rnd.nextFloat() * 6.28f }
    private val cloudX = FloatArray(12) { rnd.nextFloat() }
    private val cloudY = FloatArray(12) { 0.08f + rnd.nextFloat() * 0.38f }
    private val cloudS = FloatArray(12) { 0.8f + rnd.nextFloat() * 1.3f }
    private val cloudV = FloatArray(12) { 6f + rnd.nextFloat() * 14f }
    private val dropX = FloatArray(170) { rnd.nextFloat() }
    private val dropY = FloatArray(170) { rnd.nextFloat() }
    private val dropV = FloatArray(170) { 0.8f + rnd.nextFloat() * 0.5f }
    private val flakeX = FloatArray(80) { rnd.nextFloat() }
    private val flakeY = FloatArray(80) { rnd.nextFloat() }
    private val flakeV = FloatArray(80) { 0.6f + rnd.nextFloat() * 0.8f }
    private val flakeR = FloatArray(80) { 2f + rnd.nextFloat() * 2.8f }
    private var clock = 0f
    private var flash = 0f
    private var nextFlash = 0L

    private val farRidge = Path()
    private val nearRidge = Path()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        u = h / 1080f
        ridge(farRidge, w, h, h * 0.80f, h * 0.05f, 2.1f, 0.7f)
        ridge(nearRidge, w, h, h * 0.89f, h * 0.035f, 3.4f, 2.2f)
        lastBuild = 0
    }

    private fun ridge(p: Path, w: Int, h: Int, base: Float, amp: Float, freq: Float, phase: Float) {
        p.reset()
        p.moveTo(0f, h.toFloat())
        var x = 0f
        while (x <= w + 16) {
            val t = x / w
            val y = base - amp * (0.55f * sin(t * freq * 6.28f + phase) + 0.3f * sin(t * freq * 13f + phase * 2) + 0.15f * sin(t * 31f + phase))
            p.lineTo(x, y)
            x += 16f
        }
        p.lineTo(w.toFloat(), h.toFloat())
        p.close()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        if (visibility == VISIBLE) {
            last = SystemClock.uptimeMillis()
            invalidate() // the loop below only runs while drawing, so it needs a kick after being hidden
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            last = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    private fun realMinutes(): Float {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60f + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f
    }

    private fun minutes(): Float =
        if (prefs.getBoolean("sky_fast", false)) (startMinutes + (SystemClock.elapsedRealtime() - started) / 120_000f * 1440f) % 1440f
        else realMinutes()

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1 - k) + ((b shr s) and 255) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun greyOf(c: Int): Int {
        val l = (0.3f * ((c shr 16) and 255) + 0.59f * ((c shr 8) and 255) + 0.11f * (c and 255)).toInt()
        return (0xFF shl 24) or (l shl 16) or (l shl 8) or l
    }

    private fun rebuild(now: Long) {
        lastBuild = now
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val code = prefs.getInt("wx_code", 0)
        rain = when (code) { in 51..57 -> 50; in 61..67, in 80..82 -> 110; in 95..99 -> 170; else -> 0 }
        snow = if (code in 71..77 || code in 85..86) 80 else 0
        storm = code in 95..99
        fog = code == 45 || code == 48
        grey = when (code) {
            0 -> 0f; 1 -> 0.1f; 2 -> 0.3f; 3 -> 0.55f; 45, 48 -> 0.6f
            in 51..57 -> 0.55f; in 61..67, in 80..82 -> 0.7f; in 71..77, 85, 86 -> 0.6f; in 95..99 -> 0.85f
            else -> 0.2f
        }
        cloudCount = (2 + grey * 10).toInt().coerceIn(2, 12)

        var rise = prefs.getInt("wx_rise", 360)
        var set = prefs.getInt("wx_set", 1110)
        if (rise !in 90..720 || set !in 900..1350) { rise = 360; set = 1110 }
        val m = minutes()

        // Colour stops through the day: time, top colour, bottom colour, how night it is.
        val t = floatArrayOf(0f, rise - 60f, rise.toFloat(), rise + 75f, set - 75f, set.toFloat(), set + 60f, 1440f)
        val tops = intArrayOf(NIGHT_TOP, NIGHT_TOP, DAWN_TOP, DAY_TOP, DAY_TOP, DUSK_TOP, NIGHT_TOP, NIGHT_TOP)
        val bots = intArrayOf(NIGHT_BOT, NIGHT_BOT, DAWN_BOT, DAY_BOT, DAY_BOT, DUSK_BOT, NIGHT_BOT, NIGHT_BOT)
        val nights = floatArrayOf(1f, 1f, 0.35f, 0f, 0f, 0.35f, 1f, 1f)
        var i = 0
        while (i < t.size - 2 && m >= t[i + 1]) i++
        val k = ((m - t[i]) / max(1f, t[i + 1] - t[i])).coerceIn(0f, 1f)
        top = mix(tops[i], tops[i + 1], k)
        bottom = mix(bots[i], bots[i + 1], k)
        night = nights[i] + (nights[i + 1] - nights[i]) * k

        // Weather greys the sky out, and a storm darkens it.
        top = mix(top, greyOf(top), grey * 0.8f)
        bottom = mix(bottom, greyOf(bottom), grey * 0.8f)
        if (storm) {
            top = mix(top, Color.BLACK, 0.3f)
            bottom = mix(bottom, Color.BLACK, 0.25f)
        }
        if (light) {
            top = mix(top, LIGHT_WASH, 0.5f)
            bottom = mix(bottom, LIGHT_WASH, 0.5f)
        }
        skyShader = LinearGradient(0f, 0f, 0f, h * 0.85f, top, bottom, Shader.TileMode.CLAMP)

        // Sun by day, moon by night, each along an arc.
        val dayLen = (set - rise).toFloat()
        val sunUp = m >= rise - 20 && m <= set + 20
        bodyIsSun = sunUp
        val p = if (sunUp) (m - rise) / dayLen else {
            val nightLen = 1440f - dayLen - 40f
            ((m - (set + 20) + 1440f) % 1440f) / nightLen
        }
        sunX = w * (0.1f + 0.8f * p)
        sunY = h * 0.80f - sin(PI.toFloat() * p) * h * 0.52f
        val low = 1f - sin(PI.toFloat() * p.coerceIn(0f, 1f))
        bodyColor = if (sunUp) mix(0xFFFFF1C2.toInt(), 0xFFFF9A4D.toInt(), low * low) else 0xFFEEF0F8.toInt()
        bodyAlpha = (1f - grey * 0.85f).coerceIn(0.1f, 1f)
        val glowR = (if (sunUp) 360f else 220f) * u
        val glowC = (bodyColor and 0x00FFFFFF) or ((if (sunUp) 0x66 else 0x40) shl 24)
        glowShader = RadialGradient(sunX, sunY, glowR, glowC, bodyColor and 0x00FFFFFF, Shader.TileMode.CLAMP)

        val nightCloud = mix(0xFFFFFFFF.toInt(), 0xFF3C4770.toInt(), night)
        cloudColor = mix(nightCloud, mix(0xFF8A919C.toInt(), 0xFF2A3045.toInt(), night), grey)
        if (light) cloudColor = mix(cloudColor, LIGHT_WASH, 0.35f)

        if (light) {
            farHill = mix(bottom, 0xFFB9C4D2.toInt(), 0.5f)
            nearHill = mix(bottom, 0xFF9AA8BB.toInt(), 0.6f)
        } else {
            farHill = mix(bottom, 0xFF16223A.toInt(), 0.55f)
            nearHill = mix(bottom, 0xFF0A1020.toInt(), 0.78f)
        }
        val fogC = mix(mix(0xFFE6EAEE.toInt(), 0xFF2A3045.toInt(), night), if (light) LIGHT_WASH else 0xFF000000.toInt(), if (light) 0.4f else 0f)
        fogShader = LinearGradient(0f, h * 0.3f, 0f, h * 0.9f, fogC and 0x00FFFFFF, (fogC and 0x00FFFFFF) or (0xA0 shl 24), Shader.TileMode.CLAMP)
    }

    private fun advance(dt: Float, now: Long) {
        val w = width.toFloat()
        clock += dt
        for (i in 0 until 12) {
            cloudX[i] += cloudV[i] * u * dt / max(1f, w)
            if (cloudX[i] > 1.25f) cloudX[i] = -0.25f
        }
        for (i in 0 until rain) {
            dropY[i] += dropV[i] * 1.1f * dt
            if (dropY[i] > 1f) { dropY[i] = -0.05f; dropX[i] = rnd.nextFloat() }
        }
        for (i in 0 until snow) {
            flakeY[i] += flakeV[i] * 0.09f * dt
            if (flakeY[i] > 1f) { flakeY[i] = -0.03f; flakeX[i] = rnd.nextFloat() }
        }
        if (storm) {
            if (nextFlash == 0L) nextFlash = now + 3000 + rnd.nextInt(6000)
            if (now >= nextFlash) {
                flash = 0.75f
                nextFlash = now + 5000 + rnd.nextInt(8000)
            }
        } else nextFlash = 0L
        flash = max(0f, flash - 2.2f * dt)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = min(100L, max(0L, now - last)) / 1000f
        last = now
        if (now - lastBuild > 120 || skyShader == null) rebuild(now)
        advance(dt, now)
        val w = width.toFloat()
        val h = height.toFloat()
        val shader = skyShader ?: return

        paint.shader = shader
        paint.alpha = 255
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Stars.
        if (night > 0.05f && !light) {
            paint.color = Color.WHITE
            val base = night * (1f - grey)
            for (i in 0 until 90) {
                val tw = 0.55f + 0.45f * sin(clock * (0.8f + starR[i] * 0.5f) + starP[i])
                paint.alpha = (255 * base * tw).toInt().coerceIn(0, 255)
                c.drawCircle(starX[i] * w, starY[i] * h, starR[i] * u, paint)
            }
        }

        // Sun or moon, with its glow.
        if (sunY < h * 0.82f) {
            paint.shader = glowShader
            paint.alpha = (255 * bodyAlpha).toInt()
            c.drawCircle(sunX, sunY, (if (bodyIsSun) 360f else 220f) * u, paint)
            paint.shader = null
            paint.color = bodyColor
            paint.alpha = (255 * bodyAlpha).toInt()
            c.drawCircle(sunX, sunY, (if (bodyIsSun) 46f else 34f) * u, paint)
        }

        // Clouds.
        // Opaque, two-tone: translucent circles would show their overlaps as blotches.
        paint.alpha = 255
        val shade = mix(cloudColor, bottom, 0.3f)
        for (i in 0 until cloudCount) {
            paint.color = shade
            cloud(c, cloudX[i] * w, cloudY[i] * h + 9f * cloudS[i] * u, cloudS[i] * u)
            paint.color = cloudColor
            cloud(c, cloudX[i] * w, cloudY[i] * h, cloudS[i] * u * 0.96f)
        }

        if (fog) {
            paint.shader = fogShader
            paint.alpha = 255
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        paint.color = farHill
        paint.alpha = 255
        c.drawPath(farRidge, paint)
        paint.color = nearHill
        c.drawPath(nearRidge, paint)

        if (rain > 0) {
            paint.color = if (light) 0xFF5A6B80.toInt() else 0xFFCFDAF0.toInt()
            paint.alpha = if (light) 150 else 120
            paint.strokeWidth = 2f * u
            val len = 38f * u
            for (i in 0 until rain) {
                val x = dropX[i] * w
                val y = dropY[i] * h
                c.drawLine(x, y, x - len * 0.18f, y + len, paint)
            }
        }
        if (snow > 0) {
            paint.color = if (light) 0xFF9FB0C4.toInt() else Color.WHITE
            paint.alpha = 220
            for (i in 0 until snow) {
                val sway = sin(clock * 0.9f + i) * 26f * u
                c.drawCircle(flakeX[i] * w + sway, flakeY[i] * h, flakeR[i] * u, paint)
            }
        }
        if (flash > 0.01f) {
            paint.color = Color.WHITE
            paint.alpha = (255 * flash).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, w, h, paint)
        }
        postInvalidateDelayed(33)
    }

    private fun cloud(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawCircle(x, y, 38f * s, paint)
        c.drawCircle(x - 42f * s, y + 10f * s, 28f * s, paint)
        c.drawCircle(x + 44f * s, y + 12f * s, 30f * s, paint)
        c.drawCircle(x + 14f * s, y - 18f * s, 30f * s, paint)
        c.drawCircle(x - 16f * s, y - 10f * s, 26f * s, paint)
        c.drawRect(x - 60f * s, y + 6f * s, x + 66f * s, y + 40f * s, paint)
    }

    private companion object {
        const val NIGHT_TOP = 0xFF050914.toInt()
        const val NIGHT_BOT = 0xFF152043.toInt()
        const val DAWN_TOP = 0xFF34406E.toInt()
        const val DAWN_BOT = 0xFFF4A27A.toInt()
        const val DAY_TOP = 0xFF2F6FCB.toInt()
        const val DAY_BOT = 0xFFA6D2F2.toInt()
        const val DUSK_TOP = 0xFF2E2D6B.toInt()
        const val DUSK_BOT = 0xFFF0683F.toInt()
        const val LIGHT_WASH = 0xFFF3F5F7.toInt()
    }
}
