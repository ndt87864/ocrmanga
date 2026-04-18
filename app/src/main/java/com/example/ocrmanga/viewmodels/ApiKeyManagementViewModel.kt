package com.example.ocrmanga.viewmodels
import android.content.Context
import com.example.ocrmanga.utils.AppLogger as Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.ocrmanga.data.database.DatabaseHelper

import android.content.SharedPreferences
// Data class to represent an API key with quota info
data class ApiKey(
    val key: String,
    val type: String = "default",
    val createdDate: String,
    val updatedDate: String,
    var isActive: Boolean,
    val remainingQuota: Double = 1.0,
    val consecutiveFailures: Int = 0,
    val rateLimitReset: Long = 0,
    val lastChecked: Long = 0
)


class ApiKeyManagementViewModel(private val context: Context) : ViewModel() {
    private val prefs: SharedPreferences = context.getSharedPreferences("api_key_prefs", Context.MODE_PRIVATE)

    fun getDefaultKeyType(): String {
        return prefs.getString("default_key_type", "gemini") ?: "gemini"
    }

    fun setDefaultKeyType(type: String) {
        prefs.edit().putString("default_key_type", type).apply()
    }
    // Sửa API key theo key và type cũ
    fun editApiKeyWithType(oldKey: String, oldType: String, newKey: String, newType: String) {
        databaseHelper.updateApiKeyWithType(oldKey, oldType, newKey, newType)
        loadApiKeysFromDatabase()
    }

    // Xóa API key theo key và type
    fun deleteApiKeyWithType(key: String, type: String) {
        databaseHelper.deleteApiKeyWithType(key, type)
        loadApiKeysFromDatabase()
    }

    private val databaseHelper = DatabaseHelper(context)

    // State list to hold API keys
    val apiKeys: SnapshotStateList<ApiKey> = mutableStateListOf()

    init {
        loadApiKeysFromDatabase()
    }

    // Function to load API keys from the database
    private fun loadApiKeysFromDatabase() {
        val keys = databaseHelper.getAllApiKeysWithStats()
        apiKeys.clear()
        apiKeys.addAll(keys.map { info ->
            ApiKey(
                key = info.value,
                type = info.type,
                createdDate = "2025-07-15",
                updatedDate = "2025-07-15",
                isActive = info.isActive,
                remainingQuota = info.remainingQuota,
                consecutiveFailures = info.consecutiveFailures,
                rateLimitReset = info.rateLimitReset,
                lastChecked = info.lastChecked
            )
        })
    }

    // Function to add a new API key
    // Khi thêm key mới, luôn lấy type đang hiển thị (type truyền vào từ UI) để setDefaultKeyType
    /**
     * Thêm API key mới. Nếu type khác với loại đang hiển thị, trả về true để UI tự động chuyển sang loại đó.
     * @param newKey key mới
     * @param displayType loại key đang hiển thị trên UI
     * @param currentDisplayType loại key hiện tại trên UI
     * @return true nếu cần chuyển UI sang loại key vừa thêm
     */
    fun addApiKey(newKey: String, displayType: String = "default", currentDisplayType: String = displayType): Boolean {
        if (newKey.isNotBlank()) {
            databaseHelper.insertApiKey(newKey, displayType)
            setDefaultKeyType(displayType)
            loadApiKeysByType(displayType)
            return displayType != currentDisplayType
        }
        return false
    }

    // Hàm load danh sách apiKeys theo type
    fun loadApiKeysByType(type: String) {
        val keys = databaseHelper.getAllApiKeysWithStats().filter { it.type == type }
        apiKeys.clear()
        apiKeys.addAll(keys.map { info ->
            ApiKey(
                key = info.value,
                type = info.type,
                createdDate = "2025-07-15",
                updatedDate = "2025-07-15",
                isActive = info.isActive,
                remainingQuota = info.remainingQuota,
                consecutiveFailures = info.consecutiveFailures,
                rateLimitReset = info.rateLimitReset,
                lastChecked = info.lastChecked
            )
        })
    }

    // Function to edit an existing API key
    fun editApiKey(apiKey: ApiKey, newKey: String, newType: String? = null) {
        val index = apiKeys.indexOf(apiKey)
        if (index != -1 && newKey.isNotBlank()) {
            databaseHelper.updateApiKey(apiKey.key, newKey, newType)
            apiKeys[index] = ApiKey(newKey, type = newType ?: apiKey.type, createdDate = apiKey.createdDate, updatedDate = "2025-07-15", isActive = apiKey.isActive)
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