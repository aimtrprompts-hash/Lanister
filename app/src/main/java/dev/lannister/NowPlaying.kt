package dev.lannister

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Only exists so Android lets the app see the active media sessions. It does not read notifications.
 * Switched on with [NowPlaying.enable] (needs the one-time adb grant) or in Android's notification-access settings.
 */
class NowPlayingListener : NotificationListenerService()

/**
 * Watches the system's active media sessions (Spotify, Amazon Music, YouTube Music...) and reports the one that is
 * playing, or else the last one that was paused. Calls [onChange] with a null title when nothing is playing.
 * Create and use it on the main thread: MediaController callbacks arrive on the thread that registered them.
 */
class NowPlaying(context: Context, private val onChange: (title: String?, artist: String?, art: Bitmap?, playing: Boolean) -> Unit) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private var current: MediaController? = null
    private var started = false

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            detach()
            onChange(null, null, null, false)
        }
    }

    // ---- transport controls, for the home screen widget. Only what the app's own session says it supports. ----

    private fun actions(): Long = current?.playbackState?.actions ?: 0L
    private fun isPlaying() = current?.playbackState?.state == PlaybackState.STATE_PLAYING

    val canPrevious get() = actions() and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
    val canNext get() = actions() and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
    val canToggle get() = actions() and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L

    fun previous() { if (canPrevious) current?.transportControls?.skipToPrevious() }
    fun next() { if (canNext) current?.transportControls?.skipToNext() }
    fun toggle() {
        val c = current ?: return
        if (isPlaying()) c.transportControls.pause() else c.transportControls.play()
    }

    /** False when notification access has not been turned on yet. */
    fun start(): Boolean {
        if (started) return true
        started = try {
            manager.addOnActiveSessionsChangedListener(sessions, listener)
            pick(manager.getActiveSessions(listener))
            true
        } catch (e: SecurityException) {
            false
        }
        return started
    }

    fun stop() {
        if (started) try {
            manager.removeOnActiveSessionsChangedListener(sessions)
        } catch (e: Exception) {
        }
        detach()
        started = false
    }

    private fun pick(list: List<MediaController>?) {
        var best: MediaController? = null
        for (c in list.orEmpty()) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                best = c
                break
            }
            if (best == null && c.metadata != null) best = c
        }
        val now = current
        if (best != null && now != null && best.sessionToken == now.sessionToken) return publish()
        detach()
        current = best
        best?.registerCallback(callback)
        publish()
    }

    private fun detach() {
        current?.unregisterCallback(callback)
        current = null
    }

    private fun publish() {
        val c = current ?: return onChange(null, null, null, false)
        val meta = c.metadata ?: return onChange(null, null, null, false)
        val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val live = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING
        val title = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE)
        if (!live || title == null) return onChange(null, null, null, false)
        val artist = text(meta, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val art = bitmap(meta, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        onChange(title, artist, art, state == PlaybackState.STATE_PLAYING)
    }

    private fun text(m: MediaMetadata, vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> m.getString(k)?.takeIf { it.isNotEmpty() } }

    private fun bitmap(m: MediaMetadata, vararg keys: String): Bitmap? = keys.firstNotNullOfOrNull { k -> m.getBitmap(k) }

    companion object {
        private const val SETTING = "enabled_notification_listeners"
        private const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"

        private fun flat(context: Context) = ComponentName(context, NowPlayingListener::class.java).flattenToString()

        /** Compared as components, as Settings may store the short form. */
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, NowPlayingListener::class.java)
            return Settings.Secure.getString(context.contentResolver, SETTING).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Turns notification access on for this app. Needs the adb-granted WRITE_SECURE_SETTINGS; false without it. */
        fun enable(context: Context): Boolean {
            if (context.checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return false
            if (enabled(context)) return true
            val now = Settings.Secure.getString(context.contentResolver, SETTING).orEmpty()
            return try {
                Settings.Secure.putString(context.contentResolver, SETTING, if (now.isEmpty()) flat(context) else "$now:${flat(context)}")
                true
            } catch (e: SecurityException) {
                false
            }
        }

        fun disable(context: Context) {
            if (context.checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
            val me = ComponentName(context, NowPlayingListener::class.java)
            val now = Settings.Secure.getString(context.contentResolver, SETTING).orEmpty()
            try {
                Settings.Secure.putString(context.contentResolver, SETTING,
                    now.split(':').filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != me }.joinToString(":"))
            } catch (e: SecurityException) {
            }
        }
    }
}
