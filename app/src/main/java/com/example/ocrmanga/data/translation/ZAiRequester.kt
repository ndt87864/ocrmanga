package com.example.ocrmanga.data.translation

import android.app.Application
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

class ZAiRequester(
    private val application: Application,
    private val poolManager: ApiKeyPoolManager,
    private val httpClient: OkHttpClient
) {
    private val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
    private val zAiApiUrl = "https://api.z.ai/api/paas/v4/chat/completions"

    // --- CẤU HÌNH CLIENT CHUYÊN DỤNG CHO MẠNG CHẬM ---
    private val robustClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 1. Tăng Timeout lên mức cao nhất (5 phút) để chịu đựng mạng yếu
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS) // 5 phút
            .writeTimeout(60, TimeUnit.SECONDS)

            // 2. QUAN TRỌNG: Ép dùng HTTP/1.1 để tránh lỗi HTTP/2 Stream Timeout
            // HTTP/1.1 tuy cũ nhưng ổn định hơn khi mạng bị lag
            .protocols(listOf(Protocol.HTTP_1_1))

            // 3. Cơ chế Retry (Thử lại) nếu mạng lag
            .retryOnConnectionFailure(true)

            // 4. Connection Pool mới hoàn toàn
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MINUTES))

            // 5. Log để debug
            .addInterceptor(LoggingInterceptor())
            .build()
    }

    class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            //Log.d("ZAiNetwork", ">>> Gửi request: ${request.url}")
            val startTime = System.nanoTime()
            try {
                val response = chain.proceed(request)
                val elapsed = (System.nanoTime() - startTime) / 1e6
                //Log.d("ZAiNetwork", "<<< Nhận phản hồi: ${response.code} (${elapsed}ms)")
                return response
            } catch (e: Exception) {
                val elapsed = (System.nanoTime() - startTime) / 1e6
                Log.e("ZAiNetwork", "XXX Lỗi sau ${elapsed}ms: ${e.message}")
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "ZAiRequester"
        const val DEFAULT_MODEL = "glm-4.7-flash"
    }

    suspend fun executeChatCompletion(
        messages: List<Map<String, Any>>,
        model: String = DEFAULT_MODEL,
        temperature: Double = 1.0,
        max_tokens: Int = 4096,
        apiKeyOverride: String? = null
    ): ZAiResponse? = withContext(Dispatchers.IO) {
        val rawKey = apiKeyOverride ?: poolManager.selectBestKey("zai")?.value ?: run {
            Log.w(TAG, "Không tìm thấy API key Z.AI khả dụng")
            return@withContext null
        }
        val apiKey = rawKey.trim()

        val bodyMap = mapOf(
            "model" to model,
            "messages" to messages,
            "max_tokens" to max_tokens,
            "temperature" to temperature,
            "top_p" to 0.9 // Mặc định dùng 0.9 cho ổn định
        )
        val rawJsonBody = gson.toJson(bodyMap)

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = rawJsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(zAiApiUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()

        try {
            //Log.i(TAG, "[Z.AI-REQUEST] Bắt đầu gọi API...")

            robustClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                //Log.i(TAG, "[Z.AI-RESPONSE] Code: $responseCode")

                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Lỗi API Z.AI ($responseCode): $responseBody")
                    return@withContext null
                }

                val result = parseSuccessfulResponse(responseBody)
                val text = result?.content ?: ""
                val analysisText = Regex("\\[ANALYSIS\\][\\s\\S]*?(\\[END ANALYSIS\\]|\\[/ANALYSIS\\])").find(text)?.value
                    ?: Regex("\\[ANALYSIS\\][\\s\\S]*?(?=\\n\\s*(?:\\*\\*)?Block #1)").find(text)?.value
                    ?: "Không tìm thấy [ANALYSIS]"
                val translationResult = text.replace(analysisText, "").trim()
                //Log.d(TAG, "[DEBUG-RESULT] $analysisText")
                //Log.d(TAG, "KẾT QUẢ DỊCH:\n$translationResult")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kết nối Z.AI: ${e.message}", e)
            return@withContext null
        }
    }

    private fun parseSuccessfulResponse(body: String?): ZAiResponse? {
        if (body == null) return null
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val choices = json["choices"]?.asJsonArray
            if (choices == null || choices.size() == 0) return null

            val choice = choices[0].asJsonObject
            val message = choice.getAsJsonObject("message")
            val content = message["content"]?.asString ?: ""

            val reasoning = message["reasoning_content"]?.asString
            if (!reasoning.isNullOrEmpty()) {
                //Log.i(TAG, "[Z.AI-REASONING] Model đã suy luận: ${reasoning.take(100)}...")
            }

            val finishReason = choice["finish_reason"]?.asString ?: "unknown"

            ZAiResponse(content, finishReason)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi parse response Z.AI", e)
            null
        }
    }

    data class ZAiResponse(
        val content: String,
        val finishReason: String
    )
}