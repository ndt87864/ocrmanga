package com.example.ocrmanga.data.translation

import android.app.Application
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Lớp hỗ trợ thực hiện các yêu cầu đến Mistral AI API một cách tập trung.
 * Tối ưu hóa theo đặc tả OpenAPI và hỗ trợ xử lý lỗi chi tiết.
 */
class MistralRequester(
    private val application: Application,
    private val poolManager: ApiKeyPoolManager,
    private val httpClient: OkHttpClient
) {
    private val gson = Gson()
    private val mistralApiUrl = "https://api.mistral.ai/v1/chat/completions"

    companion object {
        private const val TAG = "MistralRequester"
        const val DEFAULT_MODEL = "mistral-large-latest"

        // Các model được khuyến nghị từ spec/free tier
        val SUPPORTED_MODELS = listOf(
            "mistral-large-latest",
            "mistral-medium-latest",
            "mistral-small-latest",
            "pixtral-large-latest",
            "magistral-medium-latest",
            "open-mistral-nemo"
        )
    }

    /**
     * Thực hiện gửi yêu cầu Chat Completion đến Mistral.
     * @param messages Danh sách các tin nhắn (role, content)
     * @param model Tên model sử dụng
     * @param temperature Độ sáng tạo (0.0 - 1.0, khuyến nghị 0.7 cho translation)
     * @return Kết quả trả về từ model hoặc null nếu lỗi
     */
    suspend fun executeChatCompletion(
        messages: List<Map<String, Any>>,
        model: String = DEFAULT_MODEL,
        temperature: Double = 0.7,
        top_p: Double = 1.0,
        max_tokens: Int = 2048,
        frequency_penalty: Double = 0.0,
        presence_penalty: Double = 0.0,
        responseFormat: String? = null, // "json_object" hoặc null
        randomSeed: Int? = null
    ): MistralResponse? {
        val apiKeyInfo = poolManager.selectBestKey("mistral") ?: run {
            Log.w(TAG, "Không tìm thấy API key Mistral khả dụng")
            return null
        }
        val apiKey = apiKeyInfo.value

        // Xây dựng request body theo đặc tả OpenAPI
        val bodyMap = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "temperature" to temperature,
            "top_p" to top_p,
            "max_tokens" to max_tokens,
            "frequency_penalty" to frequency_penalty,
            "presence_penalty" to presence_penalty,
            "safe_prompt" to false // Tuyệt đối không kiểm duyệt theo yêu cầu ứng dụng
        )

        // Bổ sung các tham số tùy chọn từ OpenAPI spec
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
                handleErrorResponse(apiKey, response.code, responseBody)
                return null
            }

            poolManager.notifySuccess(apiKey)
            parseSuccessfulResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kết nối đến Mistral API", e)
            poolManager.notifyFailure(apiKey)
            null
        }
    }

    private fun handleErrorResponse(apiKey: String, code: Int, body: String?) {
        val keyPrefix = apiKey.take(10)
        Log.e(TAG, "[MISTRAL-ERROR] API key: $keyPrefix... | Mã lỗi: $code")

        if (body != null) {
            try {
                val json = JsonParser.parseString(body).asJsonObject
                if (code == 422) {
                    // Phân tích chi tiết lỗi Validation (OpenAPI 422 Unprocessable Entity)
                    val detail = json["detail"]
                    Log.e(TAG, "[MISTRAL-422] Chi tiết lỗi validation: $detail")
                } else {
                    val message = json["message"]?.asString ?: "Không có thông báo lỗi"
                    Log.e(TAG, "[MISTRAL-ERROR-BODY] $message")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể parse nội dung lỗi JSON: $body")
            }
        }

        if (code == 429) {
            Log.w(TAG, "[MISTRAL-429] Key bị giới hạn tốc độ (Rate Limit)")
            poolManager.notifyRateLimit(apiKey, 60000)
        } else {
            poolManager.notifyFailure(apiKey)
        }
    }

    private fun parseSuccessfulResponse(body: String?): MistralResponse? {
        if (body == null) return null

        return try {
            val json = JsonParser.parseString(body).asJsonObject

            // Log usage
            val usage = json["usage"]?.asJsonObject
            if (usage != null) {
                val promptTokens = usage["prompt_tokens"]?.asInt ?: 0
                val completionTokens = usage["completion_tokens"]?.asInt ?: 0
                val totalTokens = usage["total_tokens"]?.asInt ?: 0
                Log.i(TAG, "[MISTRAL-USAGE] P: $promptTokens | C: $completionTokens | T: $totalTokens")
            }

            val choices = json["choices"]?.asJsonArray
            if (choices == null || choices.size() == 0) {
                Log.w(TAG, "API trả về mảng choices trống")
                return null
            }

            val choice = choices[0].asJsonObject
            val message = choice.getAsJsonObject("message")
            val content = message["content"]?.asString ?: ""
            val finishReason = choice["finish_reason"]?.asString ?: "unknown"

            MistralResponse(content, finishReason)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi parse thành công response từ Mistral", e)
            null
        }
    }

    data class MistralResponse(
        val content: String,
        val finishReason: String
    )
}
