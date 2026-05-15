package com.example.ocrmanga.ui.screens.view

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("ocrmanga_prefs")

object ViewerPreferences {
    private val KEY_VIEW_MODE = stringPreferencesKey("viewer_view_mode")
    private val KEY_OCR_PREF_REMEMBER = booleanPreferencesKey("ocr_pref_remember")
    private val KEY_OCR_PREF_REUSE = booleanPreferencesKey("ocr_pref_reuse")
    
    // Tutorial flags
    private val KEY_TUTORIAL_GALLERY = booleanPreferencesKey("tutorial_gallery_done")
    private val KEY_TUTORIAL_VIEWER = booleanPreferencesKey("tutorial_viewer_done")
    private val KEY_TUTORIAL_API = booleanPreferencesKey("tutorial_api_done")
    private val KEY_TUTORIAL_EDIT_MODE = booleanPreferencesKey("tutorial_edit_mode_done")

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

    suspend fun saveOcrPreference(context: Context, remember: Boolean, reuse: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OCR_PREF_REMEMBER] = remember
            prefs[KEY_OCR_PREF_REUSE] = reuse
        }
    }

    fun ocrPreferenceFlow(context: Context): Flow<Pair<Boolean, Boolean>> {
        return context.dataStore.data.map { prefs ->
            val remember = prefs[KEY_OCR_PREF_REMEMBER] ?: false
            val reuse = prefs[KEY_OCR_PREF_REUSE] ?: true
            remember to reuse
        }
    }

    // Tutorial helpers
    suspend fun setTutorialDone(context: Context, key: String) {
        val prefKey = when(key) {
            "gallery" -> KEY_TUTORIAL_GALLERY
            "viewer" -> KEY_TUTORIAL_VIEWER
            "api" -> KEY_TUTORIAL_API
            "edit_mode" -> KEY_TUTORIAL_EDIT_MODE
            else -> return
        }
        context.dataStore.edit { it[prefKey] = true }
    }

    fun isTutorialDoneFlow(context: Context, key: String): Flow<Boolean> {
        val prefKey = when(key) {
            "gallery" -> KEY_TUTORIAL_GALLERY
            "viewer" -> KEY_TUTORIAL_VIEWER
            "api" -> KEY_TUTORIAL_API
            "edit_mode" -> KEY_TUTORIAL_EDIT_MODE
            else -> return kotlinx.coroutines.flow.flowOf(true)
        }
        return context.dataStore.data.map { it[prefKey] ?: false }
    }
    
    suspend fun resetAllTutorials(context: Context) {
        context.dataStore.edit {
            it[KEY_TUTORIAL_GALLERY] = false
            it[KEY_TUTORIAL_VIEWER] = false
            it[KEY_TUTORIAL_API] = false
            it[KEY_TUTORIAL_EDIT_MODE] = false
        }
    }
}
