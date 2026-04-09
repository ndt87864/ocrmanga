package com.example.ocrmanga.data.translation

import android.content.Context
import com.example.ocrmanga.data.api.NvidiaTranslationService
import com.example.ocrmanga.data.models.*
import com.example.ocrmanga.data.prompt.PromptBuilder
import com.example.ocrmanga.data.prompt.PromptLoader
import com.example.ocrmanga.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Service dịch thuật tối ưu với:
 * - Prompt templates từ file .md
 * - Structured JSON output
 * - Fallback parsing khi JSON fail
 * - Confidence scoring
 */
class OptimizedTranslationService(
    private val context: Context,
    private val nvidiaService: NvidiaTranslationService
) {
    private val promptLoader = PromptLoader(context)
    private val gson = Gson()

    companion object {
        private const val TAG = "OptimizedTranslation"
    }

    /**
     * Dịch với GLM5 sử dụng prompt template v2
     */
    suspend fun translateWithGLM5(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        apiKey: String,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false
    ): TranslationResult? {
        if (textBlocks.isEmpty() || ocrResults.isEmpty()) return null

        // Load prompt templates
        val systemTemplate = promptLoader.loadPrompt("translator_v2.md")
        val userTemplate = promptLoader.loadPrompt("translator_user_prompt_v2.md")

        if (systemTemplate == null || userTemplate == null) {
            AppLogger.e(TAG, "Failed to load prompt templates")
            return null
        }

        // Build prompts
        val systemPrompt = systemTemplate.content
        val userPrompt = PromptBuilder.buildTranslationUserPrompt(
            template = userTemplate,
            ocrResults = ocrResults,
            textBlocks = textBlocks,
            previousTranslation = previousTranslation,
            isAncientMode = isAncientMode
        )

        // Thêm JSON schema instruction
        val enhancedUserPrompt = """
$userPrompt

CRITICAL: Return ONLY valid JSON. No markdown, no explanation, no text outside JSON.
        """.trimIndent()

        AppLogger.i(TAG, "[GLM5-V2] Sending request (${textBlocks.size} blocks)...")

        // Call API (reuse existing NvidiaTranslationService)
        val rawResponse = nvidiaService.translateWithGLM5(
            textBlocks = textBlocks,
            ocrResults = ocrResults,
            apiKey = apiKey,
            previousTranslation = previousTranslation,
            isAncientMode = isAncientMode
        )

        if (rawResponse == null) {
            AppLogger.e(TAG, "[GLM5-V2] API call failed")
            return null
        }

        // Parse response
        return parseTranslationResponse(rawResponse, textBlocks.size)
    }

    /**
     * Parse JSON response với fallback
     */
    private fun parseTranslationResponse(
        rawResponse: List<String?>,
        expectedBlockCount: Int
    ): TranslationResult {
        // Nếu response đã là list string (legacy format), convert sang TranslationResult
        val translations = rawResponse.mapIndexed { index, text ->
            text ?: ""
        }

        return TranslationResult(
            translations = translations,
            confidence = calculateAverageConfidence(translations),
            metadata = TranslationResultMetadata(
                totalBlocks = expectedBlockCount,
                successfulBlocks = translations.count { it.isNotBlank() },
                failedBlocks = translations.count { it.isBlank() },
                parseMethod = "legacy"
            )
        )
    }

    /**
     * Parse structured JSON response
     */
    fun parseStructuredResponse(
        jsonString: String,
        expectedBlockCount: Int
    ): TranslationResult? {
        return try {
            // Làm sạch JSON (loại bỏ markdown code blocks nếu có)
            val cleanJson = cleanJsonString(jsonString)

            val response = gson.fromJson(cleanJson, TranslationResponse::class.java)

            if (response.blocks.isEmpty()) {
                AppLogger.w(TAG, "Empty blocks in JSON response")
                return null
            }

            // Validate block numbers
            val translations = Array<String?>(expectedBlockCount) { null }
            var totalConfidence = 0f
            var confidenceCount = 0

            for (block in response.blocks) {
                val index = block.blockNumber - 1
                if (index in 0 until expectedBlockCount) {
                    translations[index] = block.translation

                    block.confidence?.let {
                        totalConfidence += it
                        confidenceCount++
                    }

                    if (block.notes != null) {
                        AppLogger.i(TAG, "Block #${block.blockNumber} note: ${block.notes}")
                    }
                }
            }

            val avgConfidence = if (confidenceCount > 0) totalConfidence / confidenceCount else null

            TranslationResult(
                translations = translations.map { it ?: "" },
                confidence = avgConfidence,
                metadata = TranslationResultMetadata(
                    totalBlocks = expectedBlockCount,
                    successfulBlocks = translations.count { !it.isNullOrBlank() },
                    failedBlocks = translations.count { it.isNullOrBlank() },
                    parseMethod = "structured_json",
                    pronounPair = response.metadata?.pronounPair
                )
            )
        } catch (e: JsonSyntaxException) {
            AppLogger.e(TAG, "JSON parse error: ${e.message}", e)
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unexpected error parsing JSON: ${e.message}", e)
            null
        }
    }

    /**
     * Parse review response
     */
    fun parseReviewResponse(
        jsonString: String,
        expectedBlockCount: Int
    ): ReviewResult? {
        return try {
            val cleanJson = cleanJsonString(jsonString)
            val response = gson.fromJson(cleanJson, ReviewResponse::class.java)

            if (response.reviews.isEmpty()) {
                AppLogger.w(TAG, "Empty reviews in JSON response")
                return null
            }

            val rejections = mutableMapOf<Int, String>()

            for (review in response.reviews) {
                if (review.status.equals("REJECT", ignoreCase = true)) {
                    val index = review.blockNumber - 1
                    if (index in 0 until expectedBlockCount) {
                        rejections[index] = review.reason ?: "Cần cải thiện"
                    }
                }

                AppLogger.i(TAG, "Block #${review.blockNumber}: ${review.status} (score: ${review.score ?: "N/A"})")
            }

            ReviewResult(
                allApproved = rejections.isEmpty(),
                rejections = rejections,
                overallQuality = response.summary?.overallQuality
            )
        } catch (e: JsonSyntaxException) {
            AppLogger.e(TAG, "JSON parse error in review: ${e.message}", e)
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unexpected error parsing review JSON: ${e.message}", e)
            null
        }
    }

    /**
     * Làm sạch JSON string (loại bỏ markdown code blocks)
     */
    private fun cleanJsonString(json: String): String {
        var cleaned = json.trim()

        // Loại bỏ markdown code blocks
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.removePrefix("```json").trim()
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```").trim()
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.removeSuffix("```").trim()
        }

        return cleaned
    }

    /**
     * Calculate average confidence từ translations
     */
    private fun calculateAverageConfidence(translations: List<String>): Float? {
        // Heuristic: độ dài trung bình, tỷ lệ blocks thành công
        val successCount = translations.count { it.isNotBlank() }
        val totalCount = translations.size

        if (totalCount == 0) return null

        val successRate = successCount.toFloat() / totalCount

        // Confidence = success rate * 0.9 (để không bao giờ đạt 1.0)
        return successRate * 0.9f
    }

    /**
     * Fallback parser khi JSON parsing fail
     */
    fun parseFallback(
        rawText: String,
        expectedBlockCount: Int
    ): TranslationResult? {
        val translations = mutableListOf<String>()
        val blockPattern = Regex("""^\*{0,2}[Bb]lock\s*#?(\d+)\**[:.)]\**\s*(.*)$""")

        val lines = rawText.trim().split("\n")
        val blockMap = mutableMapOf<Int, String>()

        var i = 0
        while (i < lines.size) {
            val match = blockPattern.find(lines[i].trim())
            if (match != null) {
                val blockNumber = match.groupValues[1].toIntOrNull() ?: continue
                val blockIndex = blockNumber - 1
                var translation = match.groupValues[2].trim()

                // Đọc các dòng tiếp theo nếu có
                var j = i + 1
                val blockLines = mutableListOf<String>()
                if (translation.isNotEmpty()) blockLines.add(translation)

                while (j < lines.size && !blockPattern.matches(lines[j].trim())) {
                    if (lines[j].trim().isNotEmpty()) {
                        blockLines.add(lines[j].trim())
                    }
                    j++
                }
                i = j - 1

                translation = blockLines.lastOrNull()?.replace("**", "")?.trim() ?: ""
                if (blockIndex >= 0) {
                    blockMap[blockIndex] = translation
                }
            }
            i++
        }

        // Convert map to list
        val result = List(expectedBlockCount) { index ->
            blockMap[index] ?: ""
        }

        return TranslationResult(
            translations = result,
            confidence = calculateAverageConfidence(result),
            metadata = TranslationResultMetadata(
                totalBlocks = expectedBlockCount,
                successfulBlocks = result.count { it.isNotBlank() },
                failedBlocks = result.count { it.isBlank() },
                parseMethod = "fallback_regex"
            )
        )
    }
}

/**
 * Result wrapper với metadata
 */
data class TranslationResult(
    val translations: List<String>,
    val confidence: Float? = null,
    val metadata: TranslationResultMetadata
)

data class TranslationResultMetadata(
    val totalBlocks: Int,
    val successfulBlocks: Int,
    val failedBlocks: Int,
    val parseMethod: String, // "structured_json", "fallback_regex", "legacy"
    val pronounPair: String? = null
)

