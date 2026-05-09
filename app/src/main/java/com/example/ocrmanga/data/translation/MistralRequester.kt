package com.example.ocrmanga.data.translation

import android.app.Application
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Lớp hỗ trợ thực hiện các yêu cầu đến Mistral AI API (Phiên bản đơn giản hóa)
 * Cơ chế: Khi gặp lỗi 429, tự động chuyển key đó sang dùng model "mistral-medium-2508"
 */
class MistralRequester(
    private val application: Application,
    private val poolManager: ApiKeyPoolManager,
    private val httpClient: OkHttpClient
) {
    private val gson = Gson()
    private val mistralApiUrl = "https://api.mistral.ai/v1/chat/completions"

    // Map lưu trữ model đang được gán cho từng API key
    private val keyToModelMap = ConcurrentHashMap<String, String>()

    companion object {
        private const val TAG = "MistralRequester"
        const val DEFAULT_MODEL = "mistral-large-latest"
        const val FALLBACK_MODEL = "mistral-medium-2508"
    }

    /**
     * Thực hiện gửi yêu cầu Chat Completion đến Mistral.
     */
    suspend fun executeChatCompletion(
        messages: List<Map<String, Any>>,
        model: String = DEFAULT_MODEL,
        temperature: Double = 0.7,
        top_p: Double = 1.0,
        top_k: Int = 40,
        max_tokens: Int = 2048,
        frequency_penalty: Double = 0.0,
        presence_penalty: Double = 0.0,
        responseFormat: String? = null,
        randomSeed: Int? = null,
        apiKeyOverride: String? = null
    ): MistralResponse? {
        val apiKey = apiKeyOverride ?: poolManager.selectBestKey("mistral")?.value ?: run {
            Log.w(TAG, "Không tìm thấy API key Mistral khả dụng")
            return null
        }

        // Lấy model hiện tại cho key này (mặc định là model truyền vào, thường là large)
        val currentModel = keyToModelMap[apiKey] ?: model

        val bodyMap = mutableMapOf<String, Any>(
            "model" to currentModel,
            "messages" to messages,
            "temperature" to temperature,
            "top_p" to top_p,
            "top_k" to top_k,
            "max_tokens" to max_tokens,
            "frequency_penalty" to frequency_penalty,
            "presence_penalty" to presence_penalty,
            "safe_prompt" to false
        )

        if (responseFormat != null) {
            bodyMap["response_format"] = mapOf("type" to responseFormat)
        }
        if (randomSeed != null) {
            bodyMap["random_seed"] = randomSeed
        }

        val requestBodyJson = gson.toJson(bodyMap)
        val request = Request.Builder()
            .url(mistralApiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                if (response.code == 429) {
                    Log.w(TAG, "Key ${apiKey.take(10)}... bị 429. Chuyển sang model $FALLBACK_MODEL")
                    keyToModelMap[apiKey] = FALLBACK_MODEL
                }
                return null
            }

            parseSuccessfulResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kết nối đến Mistral API", e)
            null
        }
    }

    private fun parseSuccessfulResponse(body: String?): MistralResponse? {
        if (body == null) return null
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val choices = json["choices"]?.asJsonArray
            if (choices == null || choices.size() == 0) return null

            val choice = choices[0].asJsonObject
            val message = choice.getAsJsonObject("message")
            val content = message["content"]?.asString ?: ""
            val finishReason = choice["finish_reason"]?.asString ?: "unknown"

            MistralResponse(content, finishReason)
        } catch (e: Exception) {
            null
        }
    }

    data class MistralResponse(
        val content: String,
        val finishReason: String
    )

    suspend fun checkQuota(apiKey: String): Boolean {
        // Dummy check in simplified version
        return true
    }
}
