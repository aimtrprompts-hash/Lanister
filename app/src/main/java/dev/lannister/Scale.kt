package dev.lannister

import android.content.res.Resources

/**
 * The layouts are drawn in dp for a 960 x 540 dp canvas: a 1080p screen at 2x density. Fire OS reports other
 * densities (the same 1920 px wide screen can be 1440 dp or more), which would make everything look small with big
 * empty margins. This makes the screen width always mean 960 dp, so the home screen looks the same on any TV.
 */
internal fun fitDensity(res: Resources) {
    val dm = res.displayMetrics
    val density = dm.widthPixels / 960f
    if (density <= 0f || kotlin.math.abs(density - dm.density) < 0.01f) return
    dm.density = density
    dm.densityDpi = (density * 160).toInt()
    dm.scaledDensity = density * res.configuration.fontScale
}
