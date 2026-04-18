package com.example.ocrmanga.viewmodels

import android.content.Context
import com.example.ocrmanga.utils.AppLogger as Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.ocrmanga.data.database.DatabaseHelper
import android.content.SharedPreferences

// Data class to represent an API key (Simplified)
data class ApiKey(
    val key: String,
    val type: String = "default",
    val createdDate: String,
    val updatedDate: String,
    var isActive: Boolean
)

class ApiKeyManagementViewModel(private val context: Context) : ViewModel() {
    private val prefs: SharedPreferences = context.getSharedPreferences("api_key_prefs", Context.MODE_PRIVATE)

    fun getDefaultKeyType(): String {
        return prefs.getString("default_key_type", "gemini") ?: "gemini"
    }

    fun setDefaultKeyType(type: String) {
        prefs.edit().putString("default_key_type", type).apply()
    }

    private val databaseHelper = DatabaseHelper(context)

    // State list to hold API keys
    val apiKeys: SnapshotStateList<ApiKey> = mutableStateListOf()

    init {
        loadApiKeysFromDatabase()
    }

    fun refresh() {
        loadApiKeysFromDatabase()
    }

    private fun loadApiKeysFromDatabase() {
        val keys = databaseHelper.getAllApiKeysWithStats()
        apiKeys.clear()
        apiKeys.addAll(keys.map { info ->
            ApiKey(
                key = info.value,
                type = info.type,
                createdDate = "2025-07-15",
                updatedDate = "2025-07-15",
                isActive = info.isActive
            )
        })
    }

    fun addApiKey(newKey: String, displayType: String = "default", currentDisplayType: String = displayType): Boolean {
        if (newKey.isNotBlank()) {
            databaseHelper.insertApiKey(newKey, displayType)
            setDefaultKeyType(displayType)
            loadApiKeysByType(displayType)
            return displayType != currentDisplayType
        }
        return false
    }

    fun loadApiKeysByType(type: String) {
        val keys = databaseHelper.getAllApiKeysWithStats().filter { it.type == type }
        apiKeys.clear()
        apiKeys.addAll(keys.map { info ->
            ApiKey(
                key = info.value,
                type = info.type,
                createdDate = "2025-07-15",
                updatedDate = "2025-07-15",
                isActive = info.isActive
            )
        })
    }

    fun editApiKey(apiKey: ApiKey, newKey: String, newType: String? = null) {
        val index = apiKeys.indexOf(apiKey)
        if (index != -1 && newKey.isNotBlank()) {
            databaseHelper.updateApiKey(apiKey.key, newKey, newType)
            apiKeys[index] = ApiKey(newKey, type = newType ?: apiKey.type, createdDate = apiKey.createdDate, updatedDate = "2025-07-15", isActive = apiKey.isActive)
        }
    }

    fun deleteApiKey(apiKey: ApiKey) {
        databaseHelper.deleteApiKey(apiKey.key)
        apiKeys.remove(apiKey)
    }

    fun toggleApiKeyStatus(apiKey: ApiKey) {
        val index = apiKeys.indexOf(apiKey)
        if (index != -1) {
            val newStatus = if (apiKey.isActive) 0 else 1
            databaseHelper.updateApiKeyStatus(apiKey.key, newStatus == 1)
            apiKeys[index] = apiKey.copy(isActive = newStatus == 1)
            Toast.makeText(context, "Trạng thái API Key đã được cập nhật thành công", Toast.LENGTH_SHORT).show()
            loadApiKeysFromDatabase()
        }
    }
}
