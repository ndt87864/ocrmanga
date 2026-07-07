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

class CerebrasRequester(
    private val application: Application,
    private val poolManager: ApiKeyPoolManager,
    private val httpClient: OkHttpClient
) {
    private val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
    private val cerebrasApiUrl = "https://api.cerebras.ai/v1/chat/completions"

    private val robustClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MINUTES))
            .addInterceptor(LoggingInterceptor())
            .build()
    }

    class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startTime = System.nanoTime()
            try {
                val response = chain.proceed(request)
                return response
            } catch (e: Exception) {
                val elapsed = (System.nanoTime() - startTime) / 1e6
                Log.e("CerebrasNetwork", "XXX Lỗi sau ${elapsed}ms: ${e.message}")
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "CerebrasRequester"
        const val DEFAULT_MODEL = "gpt-oss-120b"
    }

    suspend fun executeChatCompletion(
        messages: List<Map<String, Any>>,
        model: String = DEFAULT_MODEL,
        temperature: Double = 1.0,
        max_tokens: Int = 32768,
        apiKeyOverride: String? = null
    ): CerebrasResponse? = withContext(Dispatchers.IO) {
        val rawKey = apiKeyOverride ?: poolManager.selectBestKey("cerebras", model)?.value ?: run {
            Log.w(TAG, "Không tìm thấy API key Cerebras khả dụng cho model $model")
            return@withContext null
        }
        val apiKey = rawKey.trim()

        val bodyMap = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "max_tokens" to max_tokens,
            "temperature" to temperature,
            "top_p" to 1.0,
            "stream" to false // We use non-stream internally for easier block collection in background translation
        )

        // reasoning_effort chỉ hỗ trợ/áp dụng cho một số model phù hợp, ta sẽ add vào request
        bodyMap["reasoning_effort"] = "medium"

        val rawJsonBody = gson.toJson(bodyMap)
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = rawJsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(cerebrasApiUrl)
            .post(body)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        try {
            robustClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Lỗi API Cerebras ($responseCode): $responseBody")
                    return@withContext null
                }

                return@withContext parseSuccessfulResponse(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kết nối Cerebras: ${e.message}", e)
            return@withContext null
        }
    }

    private fun parseSuccessfulResponse(body: String?): CerebrasResponse? {
        if (body == null) return null
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val choices = json["choices"]?.asJsonArray
            if (choices == null || choices.size() == 0) return null

            val choice = choices[0].asJsonObject
            val message = choice.getAsJsonObject("message")
            val content = message["content"]?.asString ?: ""
            val finishReason = choice["finish_reason"]?.asString ?: "unknown"

            CerebrasResponse(content, finishReason)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi parse response Cerebras", e)
            null
        }
    }

    data class CerebrasResponse(
        val content: String,
        val finishReason: String
    )
}
