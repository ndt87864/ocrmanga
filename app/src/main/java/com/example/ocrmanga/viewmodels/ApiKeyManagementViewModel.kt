package com.example.ocrmanga.viewmodels
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.ocrmanga.data.database.DatabaseHelper

// Data class to represent an API key
data class ApiKey(
    val key: String,
    val createdDate: String,
    val updatedDate: String,
    var isActive: Boolean
)

class ApiKeyManagementViewModel(private val context: Context) : ViewModel() {

    private val databaseHelper = DatabaseHelper(context)

    // State list to hold API keys
    val apiKeys: SnapshotStateList<ApiKey> = mutableStateListOf()

    init {
        loadApiKeysFromDatabase()
    }

    // Function to load API keys from the database
    private fun loadApiKeysFromDatabase() {
        val keys = databaseHelper.getAllApiKeys()
        apiKeys.clear()
        apiKeys.addAll(keys.map {
            val isActive = databaseHelper.getApiKeyStatus(it) // Fetch isActive status from DB
            ApiKey(it, createdDate = "2025-07-15", updatedDate = "2025-07-15", isActive = isActive)
        })
    }

    // Function to add a new API key
    fun addApiKey(newKey: String) {
        if (newKey.isNotBlank()) {
            databaseHelper.insertApiKey(newKey)
            // Do not add the new key to the apiKeys list
        }
    }

    // Function to edit an existing API key
    fun editApiKey(apiKey: ApiKey, newKey: String) {
        val index = apiKeys.indexOf(apiKey)
        if (index != -1 && newKey.isNotBlank()) {
            databaseHelper.updateApiKey(apiKey.key, newKey)
            apiKeys[index] = ApiKey(newKey, createdDate = apiKey.createdDate, updatedDate = "2025-07-15", isActive = apiKey.isActive)
        }
    }

    // Function to delete an API key
    fun deleteApiKey(apiKey: ApiKey) {
        databaseHelper.deleteApiKey(apiKey.key)
        apiKeys.remove(apiKey)
    }

    // Function to toggle the status of an API key
    fun toggleApiKeyStatus(apiKey: ApiKey) {
        val index = apiKeys.indexOf(apiKey)
        if (index != -1) {
            val newStatus = if (apiKey.isActive) 0 else 1 // Toggle between 1 and 0
            databaseHelper.updateApiKeyStatus(apiKey.key, newStatus == 1)
            apiKeys[index] = apiKey.copy(isActive = newStatus == 1)
            Toast.makeText(context, "Trạng thái API Key đã được cập nhật thành công: ${if (newStatus == 1) "Hoạt động" else "Không hoạt động"}", Toast.LENGTH_SHORT).show()
            loadApiKeysFromDatabase() // Làm mới danh sách sau khi cập nhật trạng thái
        } else {
            Log.w("ApiKeyManagement", "Không tìm thấy API Key trong danh sách: ${apiKey.key}")
        }
    }

}