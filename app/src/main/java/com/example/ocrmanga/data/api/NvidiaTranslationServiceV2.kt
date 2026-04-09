package com.example.ocrmanga.data.api

import android.content.Context
import android.util.Log
import com.example.ocrmanga.data.constant.AppConfig
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.prompt.PromptBuilder
import com.example.ocrmanga.data.prompt.PromptLoader
import com.example.ocrmanga.data.translation.OptimizedTranslationService
import com.example.ocrmanga.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Nvidia Translation Service V2 - Sử dụng prompt templates và structured output
 */
class NvidiaTranslationServiceV2(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private val nvidiaApiUrl = "${AppConfig.NVIDIA_BASE_URL}/chat/completions"
    private val gson = Gson()
    private val promptLoader = PromptLoader(context)
    private val optimizedService = OptimizedTranslationService(context, NvidiaTranslationService(httpClient))

    companion object {
        private const val TAG = "NvidiaTranslationV2"
    }

    /**
     * Dịch với GLM5 sử dụng prompt template v2 và structured JSON output
     */
    suspend fun translateWithGLM5(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        apiKey: String,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false
    ): List<String?>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null

        // Load prompt templates
        val systemTemplate = promptLoader.loadPrompt("translator_v2.md") ?: run {
            AppLogger.e(TAG, "Failed to load system prompt template")
            return null
        }

        val userTemplate = promptLoader.loadPrompt("translator_user_prompt_v2.md") ?: run {
            AppLogger.e(TAG, "Failed to load user prompt template")
            return null
        }

        // Build prompts
        val systemPrompt = systemTemplate.content + "\n\nCRITICAL: You MUST return valid JSON only. No markdown, no explanation."
        val userPrompt = PromptBuilder.buildTranslationUserPrompt(
            template = userTemplate,
            ocrResults = ocrResults,
            textBlocks = textBlocks,
            previousTranslation = previousTranslation,
            isAncientMode = isAncientMode
        )

        AppLogger.i(TAG, "[GLM5-V2] Sending request (${textBlocks.size} blocks)...")
        AppLogger.d(TAG, "[GLM5-V2] Prompt version: ${systemTemplate.config.version}")

        // Build request body
        val requestBody = buildRequestJson(
            model = "z-ai/glm5",
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = systemTemplate.config.temperature,
            topP = systemTemplate.config.topP,
            maxTokens = systemTemplate.config.maxTokens,
            chatTemplateKwargs = mapOf(
                "enable_thinking" to false,
                "clear_thinking" to false
            )
        )

        // Execute request
        val rawResponse = executeRequest(requestBody, "GLM5-V2", apiKey)
        if (rawResponse == null) {
            AppLogger.e(TAG, "[GLM5-V2] API call failed")
            return null
        }

        // Parse response với structured JSON parser
        return parseResponse(rawResponse, textBlocks.size)
    }

    /**
     * Dịch với Qwen sử dụng prompt template v2
     */
    suspend fun translateWithQwen(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        apiKey: String,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false
    ): List<String?>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null

        val systemTemplate = promptLoader.loadPrompt("translator_v2.md") ?: return null
        val userTemplate = promptLoader.loadPrompt("translator_user_prompt_v2.md") ?: return null

        val systemPrompt = systemTemplate.content + "\n\nCRITICAL: You MUST return valid JSON only."
        val userPrompt = PromptBuilder.buildTranslationUserPrompt(
            template = userTemplate,
            ocrResults = ocrResults,
            textBlocks = textBlocks,
            previousTranslation = previousTranslation,
            isAncientMode = isAncientMode
        )

        AppLogger.i(TAG, "[Qwen-V2] Sending request (${textBlocks.size} blocks)...")

        val requestBody = buildRequestJson(
            model = "qwen/qwen3.5-397b-a17b",
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = 0.60,
            topP = 0.95,
            maxTokens = systemTemplate.config.maxTokens,
            chatTemplateKwargs = mapOf("enable_thinking" to true)
        )

        val rawResponse = executeRequest(requestBody, "Qwen-V2", apiKey)
        return if (rawResponse != null) parseResponse(rawResponse, textBlocks.size) else null
    }

    /**
     * Build request JSON
     */
    private fun buildRequestJson(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        topP: Double,
        maxTokens: Int,
        chatTemplateKwargs: Map<String, Any>
    ): String {
        val systemMessage = mapOf("role" to "system", "content" to systemPrompt)
        val userMessage = mapOf("role" to "user", "content" to userPrompt)

        val bodyMap = mapOf(
            "model" to model,
            "messages" to listOf(systemMessage, userMessage),
            "temperature" to temperature,
            "top_p" to topP,
            "max_tokens" to maxTokens,
            "extra_body" to mapOf("chat_template_kwargs" to chatTemplateKwargs),
            "stream" to false
        )
        return gson.toJson(bodyMap)
    }

    /**
     * Execute API request
     */
    private suspend fun executeRequest(
        requestBody: String,
        modelLabel: String,
        apiKey: String
    ): String? {
        val request = Request.Builder()
            .url(nvidiaApiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()
                    AppLogger.e(TAG, "[$modelLabel-ERROR] ${resp.code} ${resp.message} | Body: $errBody")
                    return@use null
                }
                resp.body?.string()
            }

            if (body == null) return null

            val json = JsonParser.parseString(body).asJsonObject
            val choices = json["choices"]?.asJsonArray
            val message = choices?.get(0)?.asJsonObject?.getAsJsonObject("message")

            // Log reasoning nếu có
            val reasoning = message?.get("reasoning_content")?.let {
                if (it.isJsonNull) null else it.asString
            }
            if (!reasoning.isNullOrBlank()) {
                AppLogger.i(TAG, "[$modelLabel-THINKING] AI reasoning:\n$reasoning")
            }

            val contentElement = message?.get("content")
            val content = if (contentElement != null && !contentElement.isJsonNull) {
                contentElement.asString
            } else null

            content
        } catch (e: Exception) {
            AppLogger.e(TAG, "[$modelLabel-EXCEPTION] ${e.message}", e)
            null
        }
    }

    /**
     * Parse response với structured JSON + fallback
     */
    private fun parseResponse(rawContent: String, expectedBlockCount: Int): List<String?>? {
        // Thử parse JSON trước
        val structuredResult = optimizedService.parseStructuredResponse(rawContent, expectedBlockCount)
        if (structuredResult != null) {
            AppLogger.i(TAG, "[PARSE] Structured JSON success (confidence: ${structuredResult.confidence ?: "N/A"})")
            return structuredResult.translations
        }

        // Fallback: parse bằng regex
        AppLogger.w(TAG, "[PARSE] JSON failed, trying fallback regex parser...")
        val fallbackResult = optimizedService.parseFallback(rawContent, expectedBlockCount)
        if (fallbackResult != null) {
            AppLogger.i(TAG, "[PARSE] Fallback success (${fallbackResult.metadata.successfulBlocks}/${expectedBlockCount} blocks)")
            return fallbackResult.translations
        }

        AppLogger.e(TAG, "[PARSE] All parsing methods failed")
        return null
    }
}
