package dev.lannister

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.random.Random
import org.json.JSONObject

/** Photos shipped inside the app (assets/screensaver). Credits are in assets/screensaver/CREDITS.txt. */
internal fun bundledPhotos(c: Context): List<String> =
    c.assets.list("screensaver").orEmpty().filter { it.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") } }.sorted()

/**
 * The Lannister screensaver: slow, full-screen photos with a big light clock and the city bottom-left, and what is
 * playing (Spotify, Amazon Music...) bottom-right while something plays. Picked in Lannister settings, which also
 * restore the previous screensaver when it is off. Shows a folder of your photos instead when one is chosen there ("ss_folder").
 * Left and Right step through the photos; any other key leaves to the launcher's home screen.
 */
class LannisterDream : DreamService() {
    private var show: Show? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true // we handle keys ourselves (see dispatchKeyEvent)
        isFullscreen = true
        show = Show(this).also { setContentView(it.view) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        show?.start()
    }

    override fun onDreamingStopped() {
        show?.stop()
        super.onDreamingStopped()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.action == KeyEvent.ACTION_DOWN) show?.step(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            return true
        }
        if (e.action == KeyEvent.ACTION_UP) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        show?.stop()
        super.onDetachedFromWindow()
    }
}

/**
 * Plain Views on purpose: ImageViews with property animations are drawn by the GPU on Android 7.1, where a
 * SurfaceView hardware canvas is not available (that call needs Android 8). Sizes are in 1080p units scaled to the screen.
 * Two ImageViews take turns: the next photo fades in over the last one while both drift (slow zoom and pan).
 */
private class Show(private val context: Context) {
    private class Pic(val load: () -> Bitmap?)

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val u = context.resources.displayMetrics.heightPixels / 1080f
    private fun px(v: Float) = (v * u).toInt()
    private fun textPx(tv: TextView, v: Float) = tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, v * u)

    private val skyMode = prefs.getString("ss_folder", null) == SKY
    private val pictures = if (skyMode) emptyList() else pictures()
    private var index = 0
    private var failures = 0
    private var front = 0 // which of [slides] is on top
    @Volatile private var running = false

    private val slides = arrayOf(ImageView(context), ImageView(context))
    private val place = TextView(context)
    private val clock = TextClock(context)
    private val now = LinearLayout(context)
    private val nowArt = ImageView(context)
    private val nowTitle = TextView(context)
    private val nowArtist = TextView(context)
    private val overlay = FrameLayout(context)
    private val nowPlaying = NowPlaying(context) { title, artist, art, playing -> showNow(title, artist, art, playing) }

    val view = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        val layer = FrameLayout(context)
        slides.forEach {
            it.scaleType = ImageView.ScaleType.CENTER_CROP
            it.alpha = 0f
            layer.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        addView(layer, FrameLayout.LayoutParams(-1, -1))
        if (skyMode) addView(SkyView(context), FrameLayout.LayoutParams(-1, -1)) // animated day and night instead of photos
        // Soft dark shade behind the text, so it reads on bright photos too.
        val scrim = View(context)
        scrim.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xB3000000.toInt()))
        addView(scrim, FrameLayout.LayoutParams(-1, px(520f), Gravity.BOTTOM))
        addView(this@Show.overlay, FrameLayout.LayoutParams(-1, -1)) // not View.getOverlay()
        buildOverlay()
    }

    private fun buildOverlay() {
        val shadow = { tv: TextView -> tv.setShadowLayer(px(10f).toFloat(), 0f, px(2f).toFloat(), 0x99000000.toInt()) }

        // Bottom left: the city, then the time. Light and large, like a lock screen.
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        place.apply {
            textPx(this, 28f)
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.18f
            shadow(this)
        }
        clock.apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textPx(this, 168f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
            letterSpacing = -0.02f
            shadow(this)
        }
        left.addView(place)
        left.addView(clock)
        overlay.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(px(96f), 0, 0, px(72f)) })

        // Bottom right: what is playing. Hidden until something plays.
        now.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f
        }
        nowArt.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x33FFFFFF)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, px(16f).toFloat())
            }
            clipToOutline = true
            visibility = View.GONE
        }
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        nowTitle.apply {
            textPx(this, 34f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = px(620f)
            shadow(this)
        }
        nowArtist.apply {
            textPx(this, 27f)
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = px(620f)
            shadow(this)
        }
        text.addView(nowTitle)
        text.addView(nowArtist)
        now.addView(nowArt, LinearLayout.LayoutParams(px(104f), px(104f)).apply { marginEnd = px(24f) })
        now.addView(text)
        overlay.addView(now, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(96f), px(88f)) })
    }

    /** A folder's photos in shuffled order if one is chosen and still has photos, else the bundled ones. */
    private fun pictures(): List<Pic> {
        val photos = try {
            prefs.getString("ss_folder", null)?.let { folderPhotos(context.contentResolver, it) }.orEmpty()
        } catch (e: Exception) { // photo access taken away
            emptyList()
        }
        if (photos.isNotEmpty()) return photos.shuffled().map { id ->
            val uri = ContentUris.withAppendedId(PHOTOS, id)
            Pic { fitWallpaper { context.contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") } }
        }
        return bundledPhotos(context).shuffled().map { name -> Pic { decodeAsset(name) } }
    }

    /** Full quality up to about 2000 px wide: enough for a 1080p screen, and a Fire TV keeps only a few in memory. */
    private fun decodeAsset(name: String): Bitmap? {
        val path = "screensaver/$name"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 2000) sample *= 2
        return context.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    fun start() {
        if (running) return
        running = true
        if (prefs.getBoolean("ss_now", true)) {
            NowPlaying.enable(context) // no-op without the adb grant
            nowPlaying.start()
        }
        showPlace()
        if (skyMode) refreshForecast()
        drift()
        next(first = true)
    }

    fun stop() {
        if (!running) return
        running = false
        ui.removeCallbacksAndMessages(null)
        slides.forEach { it.animate().cancel() }
        overlay.animate().cancel()
        nowPlaying.stop()
        io.shutdownNow()
    }

    fun step(by: Int) {
        if (!running || pictures.isEmpty()) return
        ui.removeCallbacks(advance)
        index = (index + by + pictures.size) % pictures.size
        next(first = false)
    }

    private val advance = Runnable { step(1) }

    private fun next(first: Boolean) {
        if (pictures.isEmpty()) return
        val pic = pictures[index]
        io.execute {
            val bmp = try {
                pic.load()
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            ui.post {
                if (!running) return@post
                if (bmp == null) { // unreadable photo: skip it, but never spin if none can be read
                    if (++failures <= pictures.size) ui.post { index = (index + 1) % pictures.size; next(first) }
                    return@post
                }
                failures = 0
                present(bmp, first)
                ui.postDelayed(advance, HOLD_MS)
            }
        }
    }

    /** The new photo fades in on the idle view while the old one fades out; both keep drifting. */
    private fun present(bmp: Bitmap, first: Boolean) {
        val incoming = slides[1 - front]
        val outgoing = slides[front]
        incoming.animate().cancel()
        incoming.setImageBitmap(bmp)
        val fade = if (first) FIRST_FADE_MS else FADE_MS

        // Zoom between 1.10 and 1.22 and pan at most 4.5%: the picture always covers the screen, so no black edges show.
        val zoomIn = Random.nextBoolean()
        val s0 = if (zoomIn) 1.10f else 1.22f
        val s1 = if (zoomIn) 1.22f else 1.10f
        val w = view.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val h = view.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val x0 = (Random.nextFloat() * 2 - 1) * 0.045f * w
        val y0 = (Random.nextFloat() * 2 - 1) * 0.045f * h
        val x1 = (Random.nextFloat() * 2 - 1) * 0.045f * w
        val y1 = (Random.nextFloat() * 2 - 1) * 0.045f * h
        incoming.scaleX = s0
        incoming.scaleY = s0
        incoming.translationX = x0
        incoming.translationY = y0
        incoming.alpha = 0f
        incoming.bringToFront()
        incoming.animate().scaleX(s1).scaleY(s1).translationX(x1).translationY(y1)
            .setDuration(HOLD_MS + 2 * fade).setInterpolator(LinearInterpolator()).start()
        ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).setDuration(fade).start()
        ObjectAnimator.ofFloat(outgoing, View.ALPHA, outgoing.alpha, 0f).setDuration(fade).apply {
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Only if it is still the idle view (stepping quickly may have reused it): drop its photo.
                    if (slides[front] !== outgoing && outgoing.alpha == 0f) {
                        outgoing.animate().cancel()
                        outgoing.setImageDrawable(null)
                    }
                }
            })
            start()
        }
        front = 1 - front
    }

    // ---------- now playing ----------

    private fun showNow(title: String?, artist: String?, art: Bitmap?, playing: Boolean) {
        if (title == null) {
            now.animate().alpha(0f).setDuration(600).start()
            return
        }
        nowTitle.text = title
        nowArtist.text = artist.orEmpty()
        nowArt.setImageBitmap(art)
        nowArt.visibility = if (art == null) View.GONE else View.VISIBLE
        now.animate().alpha(if (playing) 1f else 0.55f).setDuration(600).start()
    }

    // ---------- location ----------
    // The city is whatever Weather location is set to, or else found from the public IP (ipwho.is), and kept for 6 hours.
    // A TV has no GPS. Nothing is sent unless "Show location on screensaver" is on.

    private fun showPlace() {
        if (!prefs.getBoolean("ss_location", true)) {
            place.visibility = View.GONE
            return
        }
        showPlaceText()
        val manual = prefs.getBoolean("geo_manual", false)
        val stale = System.currentTimeMillis() - prefs.getLong("geo_time", 0) > 6 * 3600_000L
        if (manual || (prefs.contains("geo_city") && !stale)) return
        io.execute {
            try {
                val geo = getJson("https://ipwho.is/?fields=success,city,country,latitude,longitude")
                if (geo.optBoolean("success")) prefs.edit()
                    .putString("geo_city", geo.getString("city"))
                    .putString("geo_where", geo.optString("country"))
                    .putFloat("geo_lat", geo.getDouble("latitude").toFloat())
                    .putFloat("geo_lon", geo.getDouble("longitude").toFloat())
                    .putLong("geo_time", System.currentTimeMillis()).apply()
                ui.post { if (running) showPlaceText() }
            } catch (e: Exception) {
                android.util.Log.w("Lannister", "location failed ($e)")
            }
        }
    }

    /** The sky follows the weather: refresh it if it is old (the launcher does this too while it is on screen). */
    private fun refreshForecast() {
        if (System.currentTimeMillis() - prefs.getLong("wx_time", 0) < 25 * 60_000L) return
        io.execute {
            try {
                fetchForecast(prefs)
            } catch (e: Exception) {
                android.util.Log.w("Lannister", "forecast failed ($e)")
            }
        }
    }

    private fun showPlaceText() {
        val text = listOfNotNull(prefs.getString("geo_city", null), prefs.getString("geo_where", null)?.takeIf { it.isNotEmpty() })
            .joinToString(", ").uppercase()
        place.text = text
        place.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        return try {
            JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            c.disconnect()
        }
    }

    /** Moves the text a little every minute, so a TV that sits on the screensaver all night never holds one spot. */
    private fun drift() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (!running) return
                overlay.animate().translationX((Random.nextFloat() * 2 - 1) * px(28f)).translationY((Random.nextFloat() * 2 - 1) * px(20f))
                    .setDuration(6000).start()
                ui.postDelayed(this, 60_000L)
            }
        }, 60_000L)
    }

    private companion object {
        const val HOLD_MS = 45_000L
        const val FADE_MS = 3_000L
        const val FIRST_FADE_MS = 800L
    }
}
