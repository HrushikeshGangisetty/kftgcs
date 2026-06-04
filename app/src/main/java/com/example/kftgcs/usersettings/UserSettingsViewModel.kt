package com.example.kftgcs.usersettings

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSettings(
    val fontSize: FontSizeOption = FontSizeOption.MEDIUM,
    val textColor: Color = UserSettingsManager.DEFAULT_TEXT_COLOR,
    val dronePathColor: Color = UserSettingsManager.DEFAULT_DRONE_PATH_COLOR
)

class UserSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = application.applicationContext

    private val _settings = MutableStateFlow(
        UserSettings(
            fontSize = UserSettingsManager.loadFontSize(ctx),
            textColor = UserSettingsManager.loadTextColor(ctx),
            dronePathColor = UserSettingsManager.loadDronePathColor(ctx)
        )
    )
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    fun setFontSize(option: FontSizeOption) {
        UserSettingsManager.saveFontSize(ctx, option)
        _settings.value = _settings.value.copy(fontSize = option)
    }

    fun setTextColor(color: Color) {
        UserSettingsManager.saveTextColor(ctx, color)
        _settings.value = _settings.value.copy(textColor = color)
    }

    fun setDronePathColor(color: Color) {
        UserSettingsManager.saveDronePathColor(ctx, color)
        _settings.value = _settings.value.copy(dronePathColor = color)
    }
}

