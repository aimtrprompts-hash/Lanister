package dev.lannister

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Current weather and today's sunrise and sunset from Open-Meteo (free, no key), for the home screen text and the
 * Live sky. Needs "geo_lat" and "geo_lon" in [prefs]. Blocks: call it off the main thread. Returns true if prefs changed.
 * Sunrise and sunset are kept as minutes after midnight in the city's own time zone ("wx_rise", "wx_set").
 */
internal fun fetchForecast(prefs: SharedPreferences, force: Boolean = false): Boolean {
    if (!prefs.contains("geo_lat")) return false
    if (!force && System.currentTimeMillis() - prefs.getLong("wx_time", 0) < 25 * 60_000L) return false
    val c = URL("https://api.open-meteo.com/v1/forecast?latitude=${prefs.getFloat("geo_lat", 0f)}" +
        "&longitude=${prefs.getFloat("geo_lon", 0f)}&current=temperature_2m,weather_code" +
        "&daily=sunrise,sunset&forecast_days=1&timezone=auto").openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    try {
        val json = JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
        val now = json.getJSONObject("current")
        val edit = prefs.edit()
            .putFloat("wx_temp", now.getDouble("temperature_2m").toFloat())
            .putInt("wx_code", now.getInt("weather_code"))
            .putLong("wx_time", System.currentTimeMillis())
        try {
            val daily = json.getJSONObject("daily")
            edit.putInt("wx_rise", minutesOfDay(daily.getJSONArray("sunrise").getString(0)))
            edit.putInt("wx_set", minutesOfDay(daily.getJSONArray("sunset").getString(0)))
        } catch (e: Exception) { // polar day or night: no sunrise; the sky uses its default hours
            edit.remove("wx_rise").remove("wx_set")
        }
        edit.apply()
        return true
    } finally {
        c.disconnect()
    }
}

/** "2026-10-23T05:58" -> 358 */
private fun minutesOfDay(iso: String): Int {
    val t = iso.substringAfter('T')
    return t.substringBefore(':').toInt() * 60 + t.substringAfter(':').take(2).toInt()
}
