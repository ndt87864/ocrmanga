package com.example.ocrmanga.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ocrmanga.ui.theme.ThemePreferences
import com.example.ocrmanga.ui.theme.ThemeVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ThemeState(
    val themeVariant: ThemeVariant = ThemeVariant.DEFAULT_PURPLE,
    val isDarkMode: Boolean = false,
    val isDynamicColorEnabled: Boolean = true,
    val customPrimaryColor: String? = null,
    val useCustomColor: Boolean = false,
    val defaultTranslationFont: String = "mto_comic_2",
    val defaultLineSpacing: Float = 1.0f,
    val defaultTextBoldness: Float = 1.0f
)

class ThemeSettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val themePreferences = ThemePreferences(application)
    
    private val _themeState = MutableStateFlow(ThemeState())
    val themeState = _themeState.asStateFlow()
    
    init {
        viewModelScope.launch {
            combine(
                themePreferences.themeVariant,
                themePreferences.isDarkMode,
                themePreferences.isDynamicColorEnabled,
                themePreferences.customPrimaryColor,
                themePreferences.useCustomColor,
                themePreferences.defaultTranslationFont,
                themePreferences.defaultLineSpacing,
                themePreferences.defaultTextBoldness
            ) { values ->
                ThemeState(
                    themeVariant = values[0] as ThemeVariant,
                    isDarkMode = values[1] as Boolean,
                    isDynamicColorEnabled = values[2] as Boolean,
                    customPrimaryColor = values[3] as String?,
                    useCustomColor = values[4] as Boolean,
                    defaultTranslationFont = values[5] as String,
                    defaultLineSpacing = values[6] as Float,
                    defaultTextBoldness = values[7] as Float
                )
            }.collect { newState ->
                _themeState.value = newState
            }
        }
    }
    
    suspend fun setThemeVariant(variant: ThemeVariant) {
        themePreferences.setThemeVariant(variant)
    }
    
    suspend fun setDarkMode(enabled: Boolean) {
        themePreferences.setDarkMode(enabled)
    }
    
    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        themePreferences.setDynamicColorEnabled(enabled)
    }
    
    suspend fun setCustomPrimaryColor(colorHex: String?) {
        themePreferences.setCustomPrimaryColor(colorHex)
    }
    
    suspend fun setUseCustomColor(enabled: Boolean) {
        themePreferences.setUseCustomColor(enabled)
    }
    
    suspend fun setDefaultTranslationFont(fontFamily: String) {
        themePreferences.setDefaultTranslationFont(fontFamily)
    }
    
    suspend fun setDefaultLineSpacing(lineSpacing: Float) {
        themePreferences.setDefaultLineSpacing(lineSpacing)
    }
    
    suspend fun setDefaultTextBoldness(textBoldness: Float) {
        themePreferences.setDefaultTextBoldness(textBoldness)
    }
}