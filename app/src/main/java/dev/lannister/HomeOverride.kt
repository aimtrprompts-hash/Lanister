package dev.lannister

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes Home land on Lannister even though Fire OS (and Google TV) will not let another app be the default home.
 *
 * Two layers, so one still works if the other does not on a given Fire OS version:
 *  1. The Home key is taken before the system sees it ([onKeyEvent]) and Lannister opens directly.
 *  2. Whenever the stock launcher puts a window on screen anyway (Back out of the last app, the end of the
 *     screensaver...), Lannister is opened over it ([onAccessibilityEvent]). This is the fallback; the stock
 *     home screen can flash for a moment before Lannister covers it.
 *
 * Holding Home opens the stock home screen (Amazon's, or Google TV's dashboard) for as long as you stay in it:
 * [stockHomeAllowed] stops layer 2 from pulling you back until Lannister is shown again.
 */
class HomeOverride : AccessibilityService() {
    override fun onServiceConnected() {
        running = this
    }

    /** Turned off, from Lannister's switch or Android's Accessibility settings: the app mustn't turn it back on. */
    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return false
        // Decided on release, by how long it was held: some remotes do not reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime < LONG_PRESS_MS) goHome() else openStockHome()
        }
        return true
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != AMAZON_HOME && pkg != GOOGLE_TV) return
        if (stockHomeAllowed) return
        // Google TV's own dashboard (opened by holding Home) must stay usable.
        if (pkg == GOOGLE_TV && e.className?.contains("dashboard", ignoreCase = true) == true) return
        // Our own startActivity can produce a window event from the stock launcher a moment later: do not loop.
        if (SystemClock.elapsedRealtime() - lastOpened < DEBOUNCE_MS) return
        goHome()
    }

    // Marked as Home so the setup check can tell it from Lannister being opened from the apps list.
    private fun goHome() {
        lastOpened = SystemClock.elapsedRealtime()
        stockHomeAllowed = false
        startActivity(Intent(this, MainActivity::class.java).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Long Home: the stock launcher, left alone until Lannister is on screen again. */
    private fun openStockHome() {
        stockHomeAllowed = true
        lastOpened = SystemClock.elapsedRealtime()
        for (pkg in listOf(AMAZON_HOME, GOOGLE_TV)) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(home)
                return
            } catch (e: Exception) { // that launcher is not on this device: try the next
            }
        }
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Lannister's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set

        /** True while the stock home screen was opened on purpose (long Home). Cleared when Lannister shows. */
        @Volatile var stockHomeAllowed = false

        @Volatile private var lastOpened = 0L
        const val AMAZON_HOME = "com.amazon.tv.launcher"
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        private const val LONG_PRESS_MS = 500L
        private const val DEBOUNCE_MS = 800L
    }
}
