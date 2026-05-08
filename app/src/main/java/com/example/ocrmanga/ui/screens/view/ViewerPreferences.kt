package com.example.ocrmanga.ui.screens.view

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("ocrmanga_prefs")

object ViewerPreferences {
    private val KEY_VIEW_MODE = stringPreferencesKey("viewer_view_mode")

    suspend fun saveViewMode(context: Context, mode: ViewMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIEW_MODE] = mode.name
        }
    }

    fun viewModeFlow(context: Context): Flow<ViewMode> {
        return context.dataStore.data.map { prefs ->
            val raw = prefs[KEY_VIEW_MODE] ?: ViewMode.VERTICAL.name
            try { ViewMode.valueOf(raw) } catch (_: Exception) { ViewMode.VERTICAL }
        }
    }
}
