package dev.lannister

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * The compact music widget in the home screen's top bar. All of its content comes from [NowPlaying], that is from the
 * active MediaSession, and the buttons call that session's own transport controls. Nothing here is sample data.
 *
 * States: playing (equalizer moving, pause icon), paused (still bars, play icon), and unavailable, which is either
 * "access is off" (press the widget to turn it on) or "nothing playing" (no app has an active media session).
 * A control the playing app does not support is dimmed and skipped by the remote.
 *
 * Fire OS limits: only apps that publish a MediaSession can show up here (Spotify, Amazon Music, YouTube Music,
 * most podcast and music apps do; some video apps and games do not), and Android only lets a launcher see sessions
 * once notification access is on for it.
 */
class PlayerWidget(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val askAccess: () -> Unit,
) {
    private val root = activity.findViewById<View>(R.id.player)
    private val art = activity.findViewById<ImageView>(R.id.playerArt)
    private val title = activity.findViewById<TextView>(R.id.playerTitle)
    private val artist = activity.findViewById<TextView>(R.id.playerArtist)
    private val prev = activity.findViewById<ImageView>(R.id.playerPrev)
    private val play = activity.findViewById<ImageView>(R.id.playerPlay)
    private val next = activity.findViewById<ImageView>(R.id.playerNext)
    private val eq = Equalizer(activity).also { activity.findViewById<FrameLayout>(R.id.playerEq).addView(it, FrameLayout.LayoutParams(-1, -1)) }

    private var fg = 0xFF111214.toInt()
    private var lit = 0xFFFFFFFF.toInt()
    private var playing = false
    private var hasMedia = false
    private var accessOn = false
    private var resumed = false
    private var lastTitle: String? = null
    private var lastArt: Bitmap? = null

    private val np = NowPlaying(activity) { t, a, bmp, isPlaying -> show(t, a, bmp, isPlaying) }

    init {
        for (v in listOf(prev, play, next)) v.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            setOnFocusChangeListener { view, focused ->
                (view.background as GradientDrawable).setColor(if (focused) lit else 0)
                view.animate().scaleX(if (focused) 1.15f else 1f).scaleY(if (focused) 1.15f else 1f)
                    .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
            }
        }
        prev.setOnClickListener { np.previous() }
        next.setOnClickListener { np.next() }
        play.setOnClickListener { np.toggle() }
        // With access off or nothing playing, pressing the info (when focusable) turns access on.
        root.setOnClickListener { if (!accessOn) askAccess() }
        applyVisibility()
        render()
    }

    private fun enabled() = prefs.getBoolean("player", true)
    private fun eqOn() = prefs.getBoolean("player_eq", true)

    /** Settings changed (widget or equalizer toggled). */
    fun applyVisibility() {
        root.visibility = if (enabled()) View.VISIBLE else View.GONE
        if (!enabled()) pause() else if (resumed) resume()
        eq.active = resumed && enabled() && playing && eqOn()
    }

    fun recolor(foreground: Int, litRing: Int) {
        fg = foreground
        lit = litRing
        title.setTextColor(fg)
        artist.setTextColor(fg)
        for (v in listOf(prev, play, next)) v.setColorFilter(fg)
        eq.color = fg
        art.setBackgroundColor((fg and 0x00FFFFFF) or 0x22000000)
        render()
    }

    /** Home screen on screen: start listening. Safe to call repeatedly. */
    fun resume() {
        resumed = true
        if (!enabled()) return
        accessOn = np.start()
        if (!accessOn) show(null, null, null, false)
        render()
    }

    /** Home screen left: stop listening and stop the equalizer, so nothing runs in the background. */
    fun pause() {
        resumed = false
        np.stop()
        eq.active = false
    }

    private fun show(t: String?, a: String?, bmp: Bitmap?, isPlaying: Boolean) {
        val changed = t != lastTitle
        hasMedia = t != null
        playing = hasMedia && isPlaying
        val apply = {
            title.text = t ?: activity.getString(if (accessOn) R.string.np_unavailable else R.string.np_needs_access)
            artist.text = if (hasMedia) (a ?: if (!playing) activity.getString(R.string.np_paused) else "") else ""
            if (bmp != null) art.setImageBitmap(bmp) else art.setImageResource(R.drawable.ic_music)
            art.scaleType = if (bmp != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
            art.setColorFilter(if (bmp != null) 0 else fg)
            if (bmp == null) art.setPadding(dp(9), dp(9), dp(9), dp(9)) else art.setPadding(0, 0, 0, 0)
            artist.visibility = if (artist.text.isEmpty()) View.GONE else View.VISIBLE
            lastTitle = t
            lastArt = bmp
            render()
        }
        // A new track (or app) fades its text in; state changes within a track just update.
        if (changed && root.isShown) {
            title.animate().alpha(0f).setDuration(120).withEndAction {
                apply()
                title.animate().alpha(1f).setDuration(220).start()
            }.start()
        } else apply()
    }

    private fun render() {
        val live = hasMedia
        play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Dim and unfocusable when the session does not support the control, or there is no session.
        control(prev, live && np.canPrevious)
        control(next, live && np.canNext)
        control(play, live && np.canToggle)
        title.alpha = if (live) 1f else 0.6f
        art.alpha = if (live) 1f else 0.55f
        // The whole widget can be pressed to turn access on, but only while access is off.
        root.isFocusable = false
        root.isClickable = !accessOn
        eq.active = resumed && enabled() && playing && eqOn()
        eq.visibility = if (live && eqOn()) View.VISIBLE else View.GONE
        play.contentDescription = activity.getString(R.string.np_play_pause)
    }

    private fun control(v: ImageView, on: Boolean) {
        v.isFocusable = on
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.3f
    }

    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()

    /** Whether any transport button can take focus right now (so the top bar's Left key can reach it). */
    fun hasControls() = enabled() && root.visibility == View.VISIBLE && listOf(prev, play, next).any { it.isFocusable }

    /** True when access is off: the widget then offers a way to turn it on (a focusable title). */
    fun offerAccess(): Boolean = enabled() && !accessOn

    init {
        title.setOnClickListener { if (!accessOn) askAccess() }
    }
}
