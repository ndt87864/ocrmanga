package com.example.ocrmanga.data.api

import android.util.Log
import com.example.ocrmanga.data.constant.AppConfig
import com.example.ocrmanga.data.constant.TranslationPrompts
import com.example.ocrmanga.data.models.TextBlockInfo
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.ocrmanga.utils.AppLogger

class NvidiaTranslationService(private val httpClient: OkHttpClient) {
    private val nvidiaApiUrl = "${AppConfig.NVIDIA_BASE_URL}/chat/completions"
    private val gson = Gson()
    private val TAG = "NvidiaTranslationService"

    private fun getBaseRequestJson(
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
            "extra_body" to mapOf(
                "chat_template_kwargs" to chatTemplateKwargs
            ),
            "stream" to false
        )
        return gson.toJson(bodyMap)
    }

    suspend fun translateWithGLM5(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false
    ): List<String?>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null
        
        val prompt = buildPrompt(textBlocks, ocrResults, previousTranslation, isAncientMode)
        val systemPrompt = TranslationPrompts.TRANSLATOR_SYSTEM_PROMPT.trimIndent() + "\n\nOutput format: STRICTLY 'Block #N: <translation>' per line. No notes, no intro."
        
        AppLogger.i(TAG, "[GLM5] Đang gửi yêu cầu dịch (${textBlocks.size} blocks)...")
        
        val requestBody = getBaseRequestJson(
            model = "z-ai/glm5",
            systemPrompt = systemPrompt,
            userPrompt = prompt,
            temperature = 1.0,
            topP = 1.0,
            maxTokens = 16384,
            chatTemplateKwargs = mapOf(
                "enable_thinking" to false,
                "clear_thinking" to false
            )
        )

        return executeRequest(requestBody, "GLM5", textBlocks.size)
    }

    suspend fun translateWithQwen(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false
    ): List<String?>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null
        
        val prompt = buildPrompt(textBlocks, ocrResults, previousTranslation, isAncientMode)
        val systemPrompt = TranslationPrompts.TRANSLATOR_SYSTEM_PROMPT.trimIndent() + "\n\nOutput format: STRICTLY 'Block #N: <translation>' per line. No notes, no intro."
        
        AppLogger.i(TAG, "[Qwen] Đang gửi yêu cầu dịch (${textBlocks.size} blocks)...")
        
        val requestBody = getBaseRequestJson(
            model = "qwen/qwen3.5-397b-a17b",
            systemPrompt = systemPrompt,
            userPrompt = prompt,
            temperature = 0.60,
            topP = 0.95,
            maxTokens = 16384,
            chatTemplateKwargs = mapOf(
                "enable_thinking" to true
            )
        )
        
        // Cập nhật lại executeRequest để hỗ trợ modelLabel Qwen
        return executeRequest(requestBody, "Qwen", textBlocks.size)
    }

    private fun buildPrompt(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false
    ): String {
        val previousContextText = if (!previousTranslation.isNullOrEmpty()) {
            TranslationPrompts.getPreviousContextText(previousTranslation)
        } else ""
        
        val ocrResultsText = ocrResults.mapIndexed { index, (scale, text) ->
            "Kết quả quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n\n")
        
        val numberedBlocks = textBlocks.mapIndexed { index, block ->
            "Block #${index + 1}: ${block.text}"
        }.joinToString("\n")
        
        return TranslationPrompts.getMistralMultiScalePrompt(
            ocrResultsText = ocrResultsText,
            numberedBlocks = numberedBlocks,
            blockCount = textBlocks.size,
            previousContextText = previousContextText,
            isAncientMode = isAncientMode
        )
    }

    private suspend fun executeRequest(requestBody: String, modelLabel: String, blocksSize: Int): List<String?>? {
        val request = Request.Builder()
            .url(nvidiaApiUrl)
            .addHeader("Authorization", "Bearer ${AppConfig.NVIDIA_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()
                    AppLogger.e(TAG, "[$modelLabel-ERROR] Lỗi: ${resp.code} ${resp.message} | Body: $errBody")
                    return@use null
                }
                resp.body?.string()
            }
            
            if (body == null) return null
            
            val json = JsonParser.parseString(body).asJsonObject
            val choices = json["choices"]?.asJsonArray
            val message = choices?.get(0)?.asJsonObject?.getAsJsonObject("message")
            
            // Log reasoning nếu có (AI đang suy nghĩ)
            val reasoning = message?.get("reasoning_content")?.let { if (it.isJsonNull) null else it.asString }
            if (!reasoning.isNullOrBlank()) {
                AppLogger.i(TAG, "[$modelLabel-THINKING] AI đang suy nghĩ:\n$reasoning")
            }
            
            val contentElement = message?.get("content")
            val content = if (contentElement != null && !contentElement.isJsonNull) contentElement.asString else null
            if (content.isNullOrBlank()) return null
            
            parseContent(content, blocksSize)
        } catch (e: Exception) {
            AppLogger.e(TAG, "[$modelLabel-EXCEPTION] Lỗi: ${e.message}", e)
            null
        }
    }

    private fun parseContent(content: String, blocksSize: Int): List<String?>? {
        val translatedBlocksMap = mutableMapOf<Int, String>()
        val lines = content.trim().split("\n")
        val blockPattern = Regex("""^\*{0,2}[Bb]lock\s*#?(\d+)\**[:.)]\**\s*(.*)$""")
        
        var i = 0
        while (i < lines.size) {
            val match = blockPattern.find(lines[i].trim())
            if (match != null) {
                val blockNumber = match.groupValues[1].toInt()
                val blockIndex = blockNumber - 1
                var translation = match.groupValues[2].trim()
                
                var j = i + 1
                val blockLines = mutableListOf<String>()
                if (translation.isNotEmpty()) blockLines.add(translation)
                while (j < lines.size && !blockPattern.matches(lines[j].trim())) {
                    if (lines[j].trim().isNotEmpty()) blockLines.add(lines[j].trim())
                    j++
                }
                i = j - 1
                
                translation = blockLines.lastOrNull()?.replace("**", "")?.replace("*", "")?.trim() ?: ""
                if (blockIndex >= 0) translatedBlocksMap[blockIndex] = translation
            }
            i++
        }

        return List(blocksSize) { index ->
            translatedBlocksMap[index]
        }
    }
}
