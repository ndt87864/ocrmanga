package com.example.ocrmanga.data.repositories

import android.app.Application
import com.example.ocrmanga.data.constant.TranslationPrompts
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.utils.AppLogger as Log

open class TranslationRepositoryCerebras(application: Application) : TranslationRepositoryOcrManga(application) {

    protected fun getCurrentCerebrasModel(): String {
        val models = cerebrasModels
        return if (models.isNotEmpty()) models[0] else com.example.ocrmanga.data.translation.CerebrasRequester.DEFAULT_MODEL
    }

    suspend fun translateWithCerebras(text: String, sourceLang: String, targetLang: String, modelOverride: String? = null): String? {
        val currentModels = cerebrasModels
        if (currentModels.isEmpty()) return null

        val modelsInOrder = if (modelOverride != null) {
            listOf(modelOverride)
        } else {
            getModelsInRotationOrder(currentModels, currentCerebrasModelIndex) {
                currentCerebrasModelIndex = (currentCerebrasModelIndex + 1) % currentModels.size
            }
        }

        val activeKeys = poolManager.getActiveKeys("cerebras")
        if (activeKeys.isEmpty()) return null

        val startingKey = poolManager.selectBestKey("cerebras") ?: activeKeys[0]
        val startingKeyIdx = activeKeys.indexOfFirst { it.id == startingKey.id }.coerceAtLeast(0)

        val normalizedText = text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        val prompt = TranslationPrompts.getZAiBasicPrompt(normalizedText, isAncientMode = false)
        val userMessage = mapOf("role" to "user", "content" to prompt)

        val maxKeysToTry = activeKeys.size.coerceAtMost(6)
        for (kOffset in 0 until maxKeysToTry) {
            val apiKeyInfo = activeKeys[(startingKeyIdx + kOffset) % activeKeys.size]
            val apiKey = apiKeyInfo.value

            val allowedModels = apiKeyInfo.allowedModels.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { currentModels.contains(it) }
                .ifEmpty { currentModels }

            val modelsToTry = modelsInOrder.filter { allowedModels.contains(it) }
                .ifEmpty { allowedModels }

            Log.i("TranslationRepository", "[CEREBRAS-ROUTING] Thử Key: ${apiKey.take(10)}... | Models: $modelsToTry")

            for (modelName in modelsToTry) {
                try {
                    val response = cerebrasRequester.executeChatCompletion(
                        messages = listOf(userMessage),
                        model = modelName,
                        temperature = 1.0,
                        apiKeyOverride = apiKey
                    )
                    val content = response?.content
                    if (!content.isNullOrBlank() && !content.equals(text, ignoreCase = true)) {
                        return content
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "[CEREBRAS-TRY-FAIL] Lỗi model $modelName trên Key ${apiKey.take(10)}...: ${e.message}")
                }
            }
        }
        return null
    }

    suspend fun translateWithCerebrasMultiScale(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        sourceLang: String,
        targetLang: String,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false,
        skipDetailedLogs: Boolean = false,
        modelOverride: String? = null,
        apiKeyOverride: String? = null
    ): List<String>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null

        val previousContextText = if (!previousTranslation.isNullOrEmpty()) {
            TranslationPrompts.getPreviousContextText(previousTranslation)
        } else ""

        val cleanedOcrResults = ocrResults.map { (scale, text) ->
            val cleaned = text.split("\n")
                .filter { it.length > 1 && !it.matches(Regex("""^[^\p{L}\p{N}]+$""")) }
                .joinToString(" ")
            scale to cleaned
        }.filter { it.second.isNotBlank() }

        val ocrResultsText = cleanedOcrResults.mapIndexed { index, (scale, text) ->
            "- Lần quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n")

        val numberedBlocks = textBlocks.mapIndexed { index, block ->
            val normalizedText = block.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            "Block #${index + 1}: Text='$normalizedText' Bounds: Rect(${block.bounds.left}, ${block.bounds.top} - ${block.bounds.right}, ${block.bounds.bottom})"
        }.joinToString("\n")

        val instructions = TranslationPrompts.getZAiMultiScalePrompt(
            ocrResultsText = "DỮ LIỆU ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
            numberedBlocks = "DANH SÁCH ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
            blockCount = textBlocks.size,
            previousContextText = previousContextText,
            isAncientMode = isAncientMode
        )

        val systemMessage = mapOf(
            "role" to "system",
            "content" to instructions
        )

        val dataContent = """
            === DỮ LIỆU OCR THAM KHẢO ===
            $ocrResultsText

            === DANH SÁCH CẦN DỊCH ===
            $numberedBlocks
        """.trimIndent()

        val userMessage = mapOf("role" to "user", "content" to dataContent)

        val response = cerebrasRequester.executeChatCompletion(
            messages = listOf(systemMessage, userMessage),
            model = modelOverride ?: com.example.ocrmanga.data.translation.CerebrasRequester.DEFAULT_MODEL,
            temperature = 1.0,
            apiKeyOverride = apiKeyOverride
        )

        val content = response?.content ?: return null
        return parseMultiBlockResponse(content, textBlocks)
    }
}
