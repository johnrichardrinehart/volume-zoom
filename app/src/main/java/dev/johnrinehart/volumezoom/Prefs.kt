package dev.johnrinehart.volumezoom

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FILE = "settings"
    const val MAX_DB = "max_attenuation_db"
    const val WINDOW_MS = "double_tap_window_ms"
    const val HUD = "show_hud"

    const val STEPS = 10
    const val DEFAULT_MAX_DB = 30
    const val DEFAULT_WINDOW_MS = 350
    val MAX_DB_RANGE = 10..60
    val WINDOW_RANGE = 200..800

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun settings(p: SharedPreferences) = ZoomController.Settings(
        steps = STEPS,
        maxAttenuationDb = p.getInt(MAX_DB, DEFAULT_MAX_DB).toFloat(),
        doubleTapWindowMs = p.getInt(WINDOW_MS, DEFAULT_WINDOW_MS).toLong(),
    )

    fun hud(p: SharedPreferences) = p.getBoolean(HUD, true)
}
