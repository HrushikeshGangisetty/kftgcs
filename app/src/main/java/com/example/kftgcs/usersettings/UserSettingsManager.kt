package com.example.kftgcs.usersettings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

object UserSettingsManager {

    private const val PREFS_NAME = "user_settings"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_TEXT_COLOR = "text_color"
    private const val KEY_DRONE_PATH_COLOR = "drone_path_color"
    private const val KEY_RADAR_CAUTION_M = "radar_caution_m"
    private const val KEY_RADAR_CRITICAL_M = "radar_critical_m"
    private const val KEY_RADAR_THRESHOLDS_OVERRIDDEN = "radar_thresholds_overridden"

    // Defaults
    val DEFAULT_TEXT_COLOR = Color.White
    val DEFAULT_DRONE_PATH_COLOR = Color.Red
    val DEFAULT_FONT_SIZE = FontSizeOption.MEDIUM
    // Proximity-radar colour bands (metres). Used until the vehicle seeds AVOID_DIST_MAX / AVOID_MARGIN
    // (unless the pilot has set a local override).
    const val DEFAULT_RADAR_CAUTION_M = 8.0f
    const val DEFAULT_RADAR_CRITICAL_M = 3.0f

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Font Size ─────────────────────────────────────────────────────────────
    fun saveFontSize(context: Context, option: FontSizeOption) {
        prefs(context).edit().putString(KEY_FONT_SIZE, option.name).apply()
    }

    fun loadFontSize(context: Context): FontSizeOption {
        val name = prefs(context).getString(KEY_FONT_SIZE, FontSizeOption.MEDIUM.name)
        return FontSizeOption.entries.firstOrNull { it.name == name } ?: FontSizeOption.MEDIUM
    }

    // ── Text Color ────────────────────────────────────────────────────────────
    fun saveTextColor(context: Context, color: Color) {
        prefs(context).edit().putInt(KEY_TEXT_COLOR, color.toArgb()).apply()
    }

    fun loadTextColor(context: Context): Color {
        val argb = prefs(context).getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR.toArgb())
        return Color(argb)
    }

    // ── Drone Path Color ──────────────────────────────────────────────────────
    fun saveDronePathColor(context: Context, color: Color) {
        prefs(context).edit().putInt(KEY_DRONE_PATH_COLOR, color.toArgb()).apply()
    }

    fun loadDronePathColor(context: Context): Color {
        val argb = prefs(context).getInt(KEY_DRONE_PATH_COLOR, DEFAULT_DRONE_PATH_COLOR.toArgb())
        return Color(argb)
    }

    // ── Proximity-radar thresholds (hybrid: vehicle-seeded, pilot-overridable) ──
    /** True once the pilot has explicitly set thresholds locally; blocks vehicle re-seeding. */
    fun isRadarThresholdsOverridden(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RADAR_THRESHOLDS_OVERRIDDEN, false)

    fun loadRadarCautionMeters(context: Context): Float =
        prefs(context).getFloat(KEY_RADAR_CAUTION_M, DEFAULT_RADAR_CAUTION_M)

    fun loadRadarCriticalMeters(context: Context): Float =
        prefs(context).getFloat(KEY_RADAR_CRITICAL_M, DEFAULT_RADAR_CRITICAL_M)

    /** Persist a pilot override and mark thresholds as user-set. */
    fun saveRadarThresholdsOverride(context: Context, cautionM: Float, criticalM: Float) {
        prefs(context).edit()
            .putFloat(KEY_RADAR_CAUTION_M, cautionM)
            .putFloat(KEY_RADAR_CRITICAL_M, criticalM)
            .putBoolean(KEY_RADAR_THRESHOLDS_OVERRIDDEN, true)
            .apply()
    }

    /** Persist values seeded from the vehicle without flagging them as a pilot override. */
    fun saveRadarThresholdsFromVehicle(context: Context, cautionM: Float, criticalM: Float) {
        prefs(context).edit()
            .putFloat(KEY_RADAR_CAUTION_M, cautionM)
            .putFloat(KEY_RADAR_CRITICAL_M, criticalM)
            .apply()
    }

    /** Clear the override so the next vehicle connection re-seeds the thresholds. */
    fun clearRadarThresholdsOverride(context: Context) {
        prefs(context).edit().putBoolean(KEY_RADAR_THRESHOLDS_OVERRIDDEN, false).apply()
    }
}

enum class FontSizeOption(val label: String, val scaleFactor: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1.0f),   // neutral default — leaves the app at its native size
    LARGE("Large", 1.2f)
}

