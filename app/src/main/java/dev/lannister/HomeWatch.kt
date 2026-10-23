package dev.lannister

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * The Home button on a Fire TV. Fire OS 6 does not let apps take the Home key or the default home role, and its own
 * accessibility manager does not pass the key on. What Fire OS does write to the system log is a line for every
 * home screen it starts. This service reads that log (the one-time adb grant of READ_LOGS allows it, and it only
 * ever looks at lines about starting a home screen) and opens Lannister over the Amazon home screen straight away.
 * This is the same technique as the Launcher Manager app. The Amazon home screen can show for a moment first.
 *
 * Start it with [start]; it keeps running, and [Boot] starts it again after a restart of the TV.
 */
class HomeWatch : Service() {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var alive = false
    @Volatile private var proc: Process? = null
    private var lastOpened = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 26) { // Fire OS 6: a foreground service is not killed when memory is short
            @Suppress("DEPRECATION")
            startForeground(1, Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Lannister").build())
        }
        if (!alive) {
            alive = true
            Thread({ watch() }, "home-watch").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        proc?.destroy()
        super.onDestroy()
    }

    private fun watch() {
        while (alive) {
            try {
                // Only ActivityManager's information lines; -T 1 starts at the newest line, so old Home presses do not replay.
                val p = ProcessBuilder("logcat", "-v", "brief", "-b", "main", "-T", "1", "ActivityManager:I", "*:S")
                    .redirectErrorStream(true).start()
                proc = p
                p.inputStream.bufferedReader().use { r ->
                    while (alive) {
                        val line = r.readLine() ?: break
                        if (isHomeStart(line)) ui.post { goHome() }
                    }
                }
                p.destroy()
            } catch (e: Exception) {
                android.util.Log.w("Lannister", "home watch ($e)")
            }
            if (alive) SystemClock.sleep(3000)
        }
    }

    /** "START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] ... cmp=com.amazon.tv.launcher/...}" */
    private fun isHomeStart(line: String) =
        line.contains("START") && line.contains("android.intent.category.HOME") && !line.contains("dev.lannister")

    private fun goHome() {
        val prefs = getSharedPreferences("home", MODE_PRIVATE)
        if (prefs.getBoolean("home_off", false) || HomeOverride.stockHomeAllowed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpened < 1200) return // our own start, or a double event
        lastOpened = now
        // Marked as Home so the setup check can tell it from Lannister being opened from the apps list.
        startActivity(Intent(this, MainActivity::class.java).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val READ_LOGS = "android.permission.READ_LOGS"

        /** Starts the watcher if it may read the log (adb grant) and the Home button is not switched off. */
        fun start(c: Context) {
            if (c.checkSelfPermission(READ_LOGS) != PackageManager.PERMISSION_GRANTED) return
            if (c.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("home_off", false)) return
            try {
                c.startService(Intent(c, HomeWatch::class.java))
            } catch (e: Exception) {
                android.util.Log.w("Lannister", "cannot start home watch ($e)")
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, HomeWatch::class.java))
        }

        fun granted(c: Context) = c.checkSelfPermission(READ_LOGS) == PackageManager.PERMISSION_GRANTED
    }
}

/** After the TV restarts, so Home works before Lannister is opened. */
class Boot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = HomeWatch.start(c)
}
