package com.example.ocrmanga.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

class ThemePreferences(private val context: Context) {
    
    companion object {
        private val THEME_VARIANT_KEY = stringPreferencesKey("theme_variant")
        private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
        private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
        private val CUSTOM_PRIMARY_COLOR_KEY = stringPreferencesKey("custom_primary_color")
        private val USE_CUSTOM_COLOR_KEY = booleanPreferencesKey("use_custom_color")
        private val DEFAULT_TRANSLATION_FONT_KEY = stringPreferencesKey("default_translation_font")
    }
    
    val themeVariant: Flow<ThemeVariant> = context.dataStore.data.map { preferences ->
        val variantName = preferences[THEME_VARIANT_KEY] ?: ThemeVariant.DEFAULT_PURPLE.name
        try {
            ThemeVariant.valueOf(variantName)
        } catch (e: IllegalArgumentException) {
            ThemeVariant.DEFAULT_PURPLE
        }
    }
    
    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DARK_MODE_KEY] ?: false
    }
    
    val isDynamicColorEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DYNAMIC_COLOR_KEY] ?: true
    }
    
    val customPrimaryColor: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[CUSTOM_PRIMARY_COLOR_KEY]
    }
    
    val useCustomColor: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USE_CUSTOM_COLOR_KEY] ?: false
    }
    
    val defaultTranslationFont: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_TRANSLATION_FONT_KEY] ?: "mto_comic_2"
    }
    
    suspend fun setThemeVariant(variant: ThemeVariant) {
        context.dataStore.edit { preferences ->
            preferences[THEME_VARIANT_KEY] = variant.name
        }
    }
    
    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DARK_MODE_KEY] = enabled
        }
    }
    
    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR_KEY] = enabled
        }
    }
    
    suspend fun setCustomPrimaryColor(colorHex: String?) {
        context.dataStore.edit { preferences ->
            if (colorHex != null) {
                preferences[CUSTOM_PRIMARY_COLOR_KEY] = colorHex
            } else {
                preferences.remove(CUSTOM_PRIMARY_COLOR_KEY)
            }
        }
    }
    
    suspend fun setUseCustomColor(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_CUSTOM_COLOR_KEY] = enabled
        }
    }
    
    suspend fun setDefaultTranslationFont(fontFamily: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_TRANSLATION_FONT_KEY] = fontFamily
        }
    }
}