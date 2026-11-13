package dev.johnrinehart.volumezoom

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FILE = "settings"
    const val MAX_DB = "max_attenuation_db"
    const val HOLD_MS = "hold_ms"
    const val HUD = "show_hud"
    const val IDLE_KEYS_MEDIA = "idle_keys_media"

    const val STEPS = 10
    const val DEFAULT_MAX_DB = 30
    const val DEFAULT_HOLD_MS = 400
    val MAX_DB_RANGE = 10..60
    val HOLD_RANGE = 200..1000

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun settings(p: SharedPreferences) = ZoomController.Settings(
        steps = STEPS,
        maxAttenuationDb = p.getInt(MAX_DB, DEFAULT_MAX_DB).toFloat(),
        holdMs = p.getInt(HOLD_MS, DEFAULT_HOLD_MS).toLong(),
    )

    fun hud(p: SharedPreferences) = p.getBoolean(HUD, true)

    /** Pixel/AOSP default: with nothing playing, volume keys still adjust media. */
    fun idleKeysMedia(p: SharedPreferences) = p.getBoolean(IDLE_KEYS_MEDIA, true)
}
