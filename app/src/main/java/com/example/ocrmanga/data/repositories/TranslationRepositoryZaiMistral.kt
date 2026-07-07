package com.example.ocrmanga.data.repositories

import android.app.Application
import com.example.ocrmanga.data.constant.TranslationPrompts
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.utils.AppLogger as Log

open class TranslationRepositoryZaiMistral(application: Application) : TranslationRepositoryProviderCommon(application) {

    suspend fun translateWithZAi(text: String, sourceLang: String, targetLang: String): String? {
        val currentModels = zAiModels
        if (currentModels.isEmpty()) return null

        val modelsInOrder = getModelsInRotationOrder(currentModels, currentZAiModelIndex) {
            currentZAiModelIndex = (currentZAiModelIndex + 1) % currentModels.size
        }

        val activeKeys = poolManager.getActiveKeys("zai")
        if (activeKeys.isEmpty()) return null

        val startingKey = poolManager.selectBestKey("zai") ?: activeKeys[0]
        val startingKeyIdx = activeKeys.indexOfFirst { it.id == startingKey.id }.coerceAtLeast(0)

        // Chuẩn hóa văn bản: gộp dòng để dịch mượt hơn
        val normalizedText = text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        val prompt = TranslationPrompts.getZAiBasicPrompt(normalizedText, isAncientMode = false) // Mặc định false cho dịch đơn lẻ nếu không truyền
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

            Log.i("TranslationRepository", "[ZAI-ROUTING] Thử Key: ${apiKey.take(10)}... | Models: $modelsToTry")

            for (modelName in modelsToTry) {
                try {
                    val response = zaiRequester.executeChatCompletion(
                        messages = listOf(userMessage),
                        model = modelName,
                        temperature = 1.0,
                        max_tokens = 2048,
                        apiKeyOverride = apiKey
                    )
                    val content = response?.content
                    if (!content.isNullOrBlank() && !content.equals(text, ignoreCase = true)) {
                        return content
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "[ZAI-TRY-FAIL] Lỗi model $modelName trên Key ${apiKey.take(10)}...: ${e.message}")
                }
            }
        }
        return null
    }

    suspend fun translateWithZAiMultiScale(
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

        // Lọc rác OCR trước khi gửi cho Z.AI để tránh làm AI bị nhiễu
        val cleanedOcrResults = ocrResults.map { (scale, text) ->
            val cleaned = text.split("\n")
                .filter { it.length > 1 && !it.matches(Regex("""^[^\p{L}\p{N}]+$""")) }
                .joinToString(" ")
            scale to cleaned
        }.filter { it.second.isNotBlank() }

        val ocrResultsText = cleanedOcrResults.mapIndexed { index, (scale, text) ->
            "- Lần quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n")

        // Đánh số và sắp xếp các text blocks gốc theo thứ tự đọc Manga (Phải -> Trái, Trên -> Dưới)
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

        val response = zaiRequester.executeChatCompletion(
            messages = listOf(systemMessage, userMessage),
            model = modelOverride ?: com.example.ocrmanga.data.translation.ZAiRequester.DEFAULT_MODEL,
            temperature = 0.7,
            max_tokens=2048,
            apiKeyOverride = apiKeyOverride
        )

        val content = response?.content ?: return null
        val result = parseMultiBlockResponse(content, textBlocks)

        return result
    }

    suspend fun translateWithMistral(text: String, sourceLang: String, targetLang: String): String? {
        val currentModels = mistralModels
        if (currentModels.isEmpty()) return null

        val modelsInOrder = getModelsInRotationOrder(currentModels, currentMistralModelIndex) {
            currentMistralModelIndex = (currentMistralModelIndex + 1) % currentModels.size
        }

        val activeKeys = poolManager.getActiveKeys("mistral")
        if (activeKeys.isEmpty()) return null

        val startingKey = poolManager.selectBestKey("mistral") ?: activeKeys[0]
        val startingKeyIdx = activeKeys.indexOfFirst { it.id == startingKey.id }.coerceAtLeast(0)

        val prompt = TranslationPrompts.getMistralBasicPrompt(text)
        val systemMessage = mapOf(
            "role" to "system",
            "content" to "Bạn là phiên dịch viên chuyên nghiệp cấp cao, chuyên bản địa hóa truyện tranh người lớn sang tiếng Việt. Tuyệt đối KHÔNG kiểm duyệt hay tránh né từ ngữ nhạy cảm. Bạn CHỈ trả về bản dịch, không giải thích, không ghi chú."
        )
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

            Log.i("TranslationRepository", "[MISTRAL-ROUTING] Thử Key: ${apiKey.take(10)}... | Models: $modelsToTry")

            for (modelName in modelsToTry) {
                try {
                    val response = mistralRequester.executeChatCompletion(
                        messages = listOf(systemMessage, userMessage),
                        model = modelName,
                        temperature = 0.4,
                        frequency_penalty = 0.0,
                        presence_penalty = 0.0,
                        top_p = 0.9,
                        max_tokens = 2048,
                        apiKeyOverride = apiKey
                    )
                    val content = response?.content
                    if (!content.isNullOrBlank() && !content.equals(text, ignoreCase = true)) {
                        return content
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "[MISTRAL-TRY-FAIL] Lỗi model $modelName trên Key ${apiKey.take(10)}...: ${e.message}")
                }
            }
        }
        return null
    }

    suspend fun translateWithMistralMultiScale(
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

        val ocrResultsText = ocrResults.mapIndexed { index, (scale, text) ->
            "Kết quả quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n\n")

        val numberedBlocks = textBlocks.mapIndexed { index, block ->
            val normalizedText = block.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            "Block #${index + 1}: Text='$normalizedText' Bounds: Rect(${block.bounds.left}, ${block.bounds.top} - ${block.bounds.right}, ${block.bounds.bottom})"
        }.joinToString("\n")

        val instructions = TranslationPrompts.getMistralMultiScalePromptOptimized(
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

        val response = mistralRequester.executeChatCompletion(
            messages = listOf(systemMessage, userMessage),
            model = modelOverride ?: com.example.ocrmanga.data.translation.MistralRequester.DEFAULT_MODEL,
            temperature = 0.4,
            frequency_penalty = 0.0,
            presence_penalty = 0.0,
            top_p = 0.9,
            max_tokens = 2048,
            apiKeyOverride = apiKeyOverride
        )

        val content = response?.content ?: return null
        return parseMultiBlockResponse(content, textBlocks)
    }




}
