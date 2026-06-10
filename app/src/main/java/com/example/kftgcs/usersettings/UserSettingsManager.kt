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

    // Defaults
    val DEFAULT_TEXT_COLOR = Color.White
    val DEFAULT_DRONE_PATH_COLOR = Color.Red
    val DEFAULT_FONT_SIZE = FontSizeOption.MEDIUM

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
}

enum class FontSizeOption(val label: String, val scaleFactor: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1.0f),   // neutral default — leaves the app at its native size
    LARGE("Large", 1.2f)
}

