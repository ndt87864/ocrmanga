package com.example.ocrmanga.viewmodels

import android.content.Context
import com.example.ocrmanga.utils.AppLogger as Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.ocrmanga.data.database.DatabaseHelper
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Data class to represent an API key (Simplified)
data class ApiKey(
    val key: String,
    val type: String = "default",
    val createdDate: String,
    val updatedDate: String,
    var isActive: Boolean,
    val allowedModels: String = ""
) {
    fun isModelAllowed(model: String): Boolean {
        if (allowedModels.isBlank()) return true
        return allowedModels.split(",").map { it.trim() }.filter { it.isNotEmpty() }.contains(model)
    }
}

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
                isActive = info.isActive,
                allowedModels = info.allowedModels
            )
        })
    }

    // --- AVAILABLE MODELS GLOBAL CONFIG ---

    // Lấy danh sách mô hình khả dụng chung cho provider type
    fun getAvailableModels(type: String): List<String> {
        val defaultModels = when (type.lowercase()) {
            "gemini" -> "gemini-2.5-flash,gemini-1.5-flash,gemini-1.5-pro,gemini-2.5-pro"
            "mistral" -> "mistral-large-latest,mistral-medium-2508,open-mixtral-8x22b,mistral-small-latest"
            "zai" -> "glm-4.7-flash,glm-4-plus,glm-4-flash"
            "ocrmanga" -> "kr/claude-sonnet-4.5,kr/glm-5,cc/claude-opus-4.7,gh/claude-sonnet-4.6"
            else -> ""
        }
        val raw = prefs.getString("${type}_available_models", defaultModels) ?: defaultModels
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Lấy danh sách lịch sử mô hình từng tồn tại (hoặc mặc định) để hiển thị thêm lại nhanh
    fun getHistoryModels(type: String): List<String> {
        val defaultModels = when (type.lowercase()) {
            "gemini" -> "gemini-2.5-flash,gemini-1.5-flash,gemini-1.5-pro,gemini-2.5-pro"
            "mistral" -> "mistral-large-latest,mistral-medium-2508,open-mixtral-8x22b,mistral-small-latest"
            "zai" -> "glm-4.7-flash,glm-4-plus,glm-4-flash"
            "ocrmanga" -> "kr/claude-sonnet-4.5,kr/glm-5,cc/claude-opus-4.7,gh/claude-sonnet-4.6"
            else -> ""
        }
        val raw = prefs.getString("${type}_history_models", defaultModels) ?: defaultModels
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Thêm mô hình mới vào danh sách khả dụng
    fun addAvailableModel(type: String, model: String) {
        val currentModels = getAvailableModels(type).toMutableList()
        val historyModels = getHistoryModels(type).toMutableList()
        val trimmedModel = model.trim()
        if (trimmedModel.isNotEmpty()) {
            if (!currentModels.contains(trimmedModel)) {
                currentModels.add(trimmedModel)
                prefs.edit().putString("${type}_available_models", currentModels.joinToString(",")).apply()
            }
            if (!historyModels.contains(trimmedModel)) {
                historyModels.add(trimmedModel)
                prefs.edit().putString("${type}_history_models", historyModels.joinToString(",")).apply()
            }
            // Tải lại dữ liệu cho UI
            loadApiKeysFromDatabase()
        }
    }

    // Xóa mô hình khỏi danh sách khả dụng
    fun removeAvailableModel(type: String, model: String) {
        val currentModels = getAvailableModels(type).toMutableList()
        val trimmedModel = model.trim()
        if (currentModels.contains(trimmedModel)) {
            currentModels.remove(trimmedModel)
            prefs.edit().putString("${type}_available_models", currentModels.joinToString(",")).apply()
            // Tải lại dữ liệu cho UI
            loadApiKeysFromDatabase()
        }
    }

    fun addApiKey(newKey: String, displayType: String = "default", currentDisplayType: String = displayType): Boolean {
        if (newKey.isNotBlank()) {
            val defaultAllowedModels = getAvailableModels(displayType).joinToString(",")
            databaseHelper.insertApiKey(newKey, displayType, defaultAllowedModels)
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
                isActive = info.isActive,
                allowedModels = info.allowedModels
            )
        })
    }

    fun editApiKey(apiKey: ApiKey, newKey: String, newType: String? = null) {
        val index = apiKeys.indexOf(apiKey)
        if (index != -1 && newKey.isNotBlank()) {
            databaseHelper.updateApiKey(apiKey.key, newKey, newType)
            apiKeys[index] = ApiKey(
                key = newKey, 
                type = newType ?: apiKey.type, 
                createdDate = apiKey.createdDate, 
                updatedDate = "2025-07-15", 
                isActive = apiKey.isActive,
                allowedModels = apiKey.allowedModels
            )
        }
    }

    fun updateAllowedModels(apiKey: ApiKey, allowedModels: String) {
        val index = apiKeys.indexOf(apiKey)
        if (index != -1) {
            databaseHelper.updateApiKeyAllowedModels(apiKey.key, allowedModels)
            apiKeys[index] = apiKey.copy(allowedModels = allowedModels)
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

    fun deleteApiKeys(cardIds: List<String>) {
        cardIds.forEach { cardId ->
            val parts = cardId.split(":")
            if (parts.isNotEmpty()) {
                val key = parts[0]
                databaseHelper.deleteApiKey(key)
            }
        }
        loadApiKeysFromDatabase()
    }

    // --- TEST CONNECTION METHODS ---

    fun testConnection(apiKey: String, type: String, model: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = when (type.lowercase()) {
                    "gemini" -> testGeminiConnection(apiKey, model)
                    "mistral" -> testMistralConnection(apiKey, model)
                    "zai" -> testZaiConnection(apiKey, model)
                    "ocrmanga" -> testOcrMangaConnection(apiKey, model)
                    else -> Pair(false, "Loại API không hợp lệ")
                }
                withContext(Dispatchers.Main) {
                    onResult(result.first, result.second)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Lỗi không xác định")
                }
            }
        }
    }

    private suspend fun testGeminiConnection(apiKey: String, model: String): Pair<Boolean, String> {
        return try {
            val generativeModel = com.google.ai.client.generativeai.GenerativeModel(
                modelName = model,
                apiKey = apiKey,
                generationConfig = com.google.ai.client.generativeai.type.generationConfig {
                    maxOutputTokens = 5
                }
            )
            val response = generativeModel.generateContent("test")
            if (!response.text.isNullOrBlank()) {
                Pair(true, "Kết nối thành công!")
            } else {
                Pair(false, "Không có phản hồi từ Gemini")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Lỗi kết nối Gemini"
            Pair(false, msg)
        }
    }

    private suspend fun testMistralConnection(apiKey: String, model: String): Pair<Boolean, String> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val gson = com.google.gson.Gson()
            val bodyMap = mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "user", "content" to "test")),
                "max_tokens" to 5
            )
            val requestBody = gson.toJson(bodyMap).toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://api.mistral.ai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
            
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string()
                if (response.isSuccessful && bodyStr != null) {
                    Pair(true, "Kết nối thành công!")
                } else {
                    val errorMsg = try {
                        val errorJson = com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
                        errorJson.getAsJsonObject("error")?.get("message")?.asString ?: bodyStr
                    } catch (ex: Exception) {
                        bodyStr
                    }
                    Pair(false, "Lỗi (${response.code}): $errorMsg")
                }
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Lỗi kết nối Mistral")
        }
    }

    private suspend fun testZaiConnection(apiKey: String, model: String): Pair<Boolean, String> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val gson = com.google.gson.Gson()
            val bodyMap = mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "user", "content" to "test")),
                "max_tokens" to 5
            )
            val requestBody = gson.toJson(bodyMap).toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://api.z.ai/api/paas/v4/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
            
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string()
                if (response.isSuccessful && bodyStr != null) {
                    Pair(true, "Kết nối thành công!")
                } else {
                    val errorMsg = try {
                        val errorJson = com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
                        errorJson.getAsJsonObject("error")?.get("message")?.asString ?: bodyStr
                    } catch (ex: Exception) {
                        bodyStr
                    }
                    Pair(false, "Lỗi (${response.code}): $errorMsg")
                }
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Lỗi kết nối Z.AI")
        }
    }

    private suspend fun testOcrMangaConnection(apiKey: String, model: String): Pair<Boolean, String> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val gson = com.google.gson.Gson()
            val bodyMap = mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "user", "content" to "test")),
                "max_tokens" to 5
            )
            val requestBody = gson.toJson(bodyMap).toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://ocrmanga-ai.vercel.app/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
            
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string()
                if (response.isSuccessful && bodyStr != null) {
                    Pair(true, "Kết nối thành công!")
                } else {
                    val errorMsg = try {
                        val errorJson = com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
                        errorJson.getAsJsonObject("error")?.get("message")?.asString ?: bodyStr
                    } catch (ex: Exception) {
                        bodyStr
                    }
                    Pair(false, "Lỗi (${response.code}): $errorMsg")
                }
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Lỗi kết nối OCR Manga")
        }
    }
}
