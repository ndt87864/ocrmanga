package com.example.ocrmanga.data.translation

import android.app.Application
import com.example.ocrmanga.utils.AppLogger as Log
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.ocrmanga.data.models.ReviewResult
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.example.ocrmanga.data.constant.TranslationPrompts
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit

/**
 * Hệ thống dịch thuật theo mô hình đội nhóm:
 *
 * - PHIÊN DỊCH VIÊN (Translator): Dịch từng block (đã thực hiện ở bước trước)
 * - QUẢN LÝ (Manager): Review toàn bộ bản dịch, đánh giá chất lượng
 *   → Nếu block nào chưa OK → yêu cầu phiên dịch viên dịch lại
 *   → Gemini: 1 vòng review | Mistral: 3 vòng review
 */
class TranslationTeamManager(private val application: Application) {

    companion object {
        private const val TAG = "TeamManager"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val databaseHelper = DatabaseHelper(application)
    private val poolManager = ApiKeyPoolManager(application)

    private val mistralApiUrl = "https://api.mistral.ai/v1/chat/completions"
    private val mistralModel = "mistral-medium-latest"
    private val geminiModel = "gemini-flash-latest"

    init {
        TranslationPrompts.initialize(application)
    }

    // ========================
    // ENTRY POINT
    // ========================

    /**
     * Nhận bản dịch ban đầu (từ TranslationRepository), thực hiện review & revision.
     *
     * @param initialTranslations Bản dịch đã có từ translateWithMistral/GeminiMultiScale
     * @param textBlocks Danh sách blocks gốc (text CJK)
     * @param mode GEMINI hoặc MISTRAL
     * @param isAncientMode Chế độ cổ trang
     * @return Bản dịch đã qua review (hoặc bản gốc nếu review thất bại)
     */
    suspend fun orchestrateReview(
        initialTranslations: List<String>,
        textBlocks: List<TextBlockInfo>,
        mode: TranslationMode,
        isAncientMode: Boolean = false
    ): List<String> {
        if (initialTranslations.isEmpty() || textBlocks.isEmpty()) return initialTranslations

        val maxReviewRounds = when (mode) {
            TranslationMode.MISTRAL -> 2 // Optimized: Reduce from 3 to 2
            else -> return initialTranslations
        }

        Log.i(TAG, "═══ BẮT ĐẦU REVIEW DỊCH THUẬT ═══")
        Log.i(TAG, "Mode: $mode | Max rounds: $maxReviewRounds | Blocks: ${textBlocks.size}")

        val current = initialTranslations.toMutableList()
        val frozenBlocks = mutableSetOf<Int>()
        var prevRejectionCount = Int.MAX_VALUE

        for (round in 1..maxReviewRounds) {
            Log.i(TAG, "── Vòng review #$round/$maxReviewRounds ──")

            // BƯỚC 1: Manager review
            val review = managerReview(textBlocks, current, mode, isAncientMode, frozenBlocks)

            if (review == null) {
                Log.w(TAG, "Review thất bại ở vòng $round")
                break
            }

            if (review.allApproved) {
                Log.i(TAG, "✓ Tất cả đã được duyệt ở vòng $round")
                break
            }

            val currentRejectionCount = review.rejections.size
            val approvedRatio = (textBlocks.size - currentRejectionCount).toFloat() / textBlocks.size

            // Early stopping: High approval and no improvement
            if (round > 1 && approvedRatio > 0.9f && currentRejectionCount >= prevRejectionCount) {
                Log.i(TAG, "(!) Dừng sớm: Đạt >90% Approved và không cải thiện thêm.")
                break
            }
            prevRejectionCount = currentRejectionCount

            // Early stopping: All rejections are already frozen (revised once)
            val allRejectionsFrozen = review.rejections.keys.all { it in frozenBlocks }
            if (allRejectionsFrozen && review.rejections.isNotEmpty()) {
                Log.i(TAG, "(!) Dừng sớm: Các block lỗi còn lại đã được sửa 1 lần.")
                break
            }

            Log.i(TAG, "✗ Yêu cầu sửa ${review.rejections.size} blocks: ${review.rejections.keys}")

            // BƯỚC 2: Translator revise
            for ((blockIndex, reason) in review.rejections) {
                if (blockIndex < 0 || blockIndex >= textBlocks.size) continue
                if (blockIndex in frozenBlocks) continue

                val originalText = textBlocks[blockIndex].text
                val currentTranslation = current[blockIndex]

                Log.i(TAG, "[REVISION] Block #${blockIndex + 1}: '$originalText'")
                Log.i(TAG, "[REVISION]   Bản dịch cũ: '$currentTranslation'")
                Log.i(TAG, "[REVISION]   Lý do sửa: $reason")

                val revised = translatorRevise(
                    originalText = originalText,
                    currentTranslation = currentTranslation,
                    managerFeedback = reason,
                    mode = mode,
                    isAncientMode = isAncientMode
                )

                if (revised != null && revised.isNotBlank() && revised != currentTranslation) {
                    // Validate: kiểm tra bản sửa có bịa thêm tên/từ Latin không có trong gốc
                    if (isHallucinated(originalText, currentTranslation, revised)) {
                        Log.w(TAG, "[REVISION]   ẢO GIÁC PHÁT HIỆN - giữ bản cũ: '$revised'")
                        frozenBlocks.add(blockIndex) // Freeze luôn vì Manager đang ảo
                    } else {
                        current[blockIndex] = revised
                        frozenBlocks.add(blockIndex) // Đã sửa 1 lần → freeze
                        Log.i(TAG, "[REVISION]   Bản dịch mới: '$revised'")
                    }
                } else {
                    Log.w(TAG, "[REVISION]   Không có bản sửa, giữ nguyên")
                    frozenBlocks.add(blockIndex) // Không sửa được → freeze
                }
            }
        }

        Log.i(TAG, "═══ KẾT THÚC REVIEW DỊCH THUẬT ═══")
        current.forEachIndexed { idx, text ->
            val changed = text != initialTranslations.getOrNull(idx)
            Log.i(TAG, "Block #${idx + 1}: ${if (changed) "[ĐÃ SỬA]" else "[GIỮ NGUYÊN]"} $text")
        }

        return current
    }

    // ========================
    // MANAGER - REVIEW
    // ========================

    private suspend fun managerReview(
        textBlocks: List<TextBlockInfo>,
        translations: List<String>,
        mode: TranslationMode,
        isAncientMode: Boolean,
        frozenBlocks: Set<Int> = emptySet()
    ): ReviewResult? {
        val reviewPrompt = TranslationPrompts.getReviewPrompt(textBlocks, translations, isAncientMode)

        val response = when (mode) {
            TranslationMode.MISTRAL -> callMistral(
                systemPrompt = TranslationPrompts.MANAGER_SYSTEM_PROMPT,
                userPrompt = reviewPrompt
            )
            TranslationMode.GEMINI -> callGemini(
                prompt = "${TranslationPrompts.MANAGER_SYSTEM_PROMPT}\n\n$reviewPrompt"
            )
            else -> null
        } ?: return null

        return parseReviewResult(response, textBlocks.size)
    }

    private fun parseReviewResult(response: String, blockCount: Int): ReviewResult {
        val rejections = mutableMapOf<Int, String>()
        
        // Regex để bắt Block #N: REJECT | Lý do: ...
        val rejectPattern = Regex("""Block\s*#(\d+)\s*:\s*REJECT\s*(?:\||\:)\s*(?:Lý do\s*\:)?\s*(.+)""", RegexOption.IGNORE_CASE)
        
        for (line in response.lines()) {
            val trimmed = line.trim()
            val match = rejectPattern.find(trimmed)
            if (match != null) {
                val blockNum = match.groupValues[1].toIntOrNull() ?: continue
                val reason = match.groupValues[2].trim()
                if (blockNum in 1..blockCount) {
                    rejections[blockNum - 1] = reason
                }
            }
        }

        Log.i(TAG, "[REVIEW-PARSE] Approved: ${blockCount - rejections.size}/$blockCount | Rejected: ${rejections.size}")
        return ReviewResult(
            allApproved = rejections.isEmpty(),
            rejections = rejections
        )
    }

    // ========================
    // TRANSLATOR - REVISION
    // ========================

    private suspend fun translatorRevise(
        originalText: String,
        currentTranslation: String,
        managerFeedback: String,
        mode: TranslationMode,
        isAncientMode: Boolean
    ): String? {
        val revisionPrompt = TranslationPrompts.getRevisePrompt(originalText, currentTranslation, managerFeedback, isAncientMode)

        val response = when (mode) {
            TranslationMode.MISTRAL -> callMistral(
                systemPrompt = TranslationPrompts.TRANSLATOR_SYSTEM_PROMPT,
                userPrompt = revisionPrompt
            )
            TranslationMode.GEMINI -> callGemini(
                prompt = "${TranslationPrompts.TRANSLATOR_SYSTEM_PROMPT}\n\n$revisionPrompt"
            )
            else -> null
        } ?: return null

        // Clean up response
        return response.trim()
            .replace("**", "")
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
    }

    // ========================
    // API CALL HELPERS
    // ========================

    private suspend fun callMistral(systemPrompt: String, userPrompt: String): String? {
        val apiKeyInfo = poolManager.selectBestKey("mistral") ?: run {
            Log.w(TAG, "Không có API key Mistral khả dụng")
            return null
        }
        val apiKey = apiKeyInfo.value

        val gson = Gson()
        val bodyMap = mapOf(
            "model" to mistralModel,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to 0.5,
            "top_p" to 0.9,
            "max_tokens" to 2048
        )
        val requestBody = gson.toJson(bodyMap)

        val request = okhttp3.Request.Builder()
            .url(mistralApiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "[MISTRAL] API error: ${resp.code} ${resp.message}")
                    if (resp.code == 429) {
                        poolManager.notifyRateLimit(apiKey, 60000) // Mặc định 1 phút nếu bị 429
                    } else {
                        poolManager.notifyFailure(apiKey)
                    }
                    return null
                }

                poolManager.notifySuccess(apiKey)
                val body = resp.body?.string() ?: return null
                val json = JsonParser.parseString(body).asJsonObject
                val content = json["choices"]?.asJsonArray
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
                content
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MISTRAL] Exception: ${e.message}", e)
            poolManager.notifyFailure(apiKey)
            null
        }
    }

    private suspend fun callGemini(prompt: String): String? {
        val apiKeyInfo = poolManager.selectBestKey("gemini") ?: run {
            Log.w(TAG, "Không có API key Gemini khả dụng")
            return null
        }
        val apiKey = apiKeyInfo.value

        return try {
            val safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
            )

            val config = generationConfig {
                temperature = 0.5f
                topP = 0.9f
                maxOutputTokens = 2048
            }

            val model = GenerativeModel(
                modelName = geminiModel,
                apiKey = apiKey,
                safetySettings = safetySettings,
                generationConfig = config
            )

            val response = model.generateContent(prompt)
            val result = response.text?.trim()
            if (result != null) {
                poolManager.notifySuccess(apiKey)
            } else {
                poolManager.notifyFailure(apiKey)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "[GEMINI] Exception: ${e.message}", e)
            if (e.message?.contains("429") == true || e.message?.contains("quota") == true) {
                poolManager.notifyRateLimit(apiKey, 60000)
            } else {
                poolManager.notifyFailure(apiKey)
            }
            null
        }
    }

    // ========================
    // HALLUCINATION DETECTION
    // ========================

    private fun isHallucinated(originalText: String, currentTranslation: String, revised: String): Boolean {
        // CHỈ phát hiện tên riêng viết hoa (ví dụ: Jack, Elena, Xiao...)
        // KHÔNG phát hiện từ tiếng Việt thông thường
        val properNounPattern = Regex("\\b[A-Z][a-z]{2,}\\b")

        val revisedProperNouns = properNounPattern.findAll(revised)
            .map { it.value }
            .toSet()

        if (revisedProperNouns.isEmpty()) return false

        // Tên riêng có trong bản dịch cũ hoặc gốc
        val existingProperNouns = properNounPattern.findAll(currentTranslation)
            .map { it.value }
            .toSet()

        val originalProperNouns = properNounPattern.findAll(originalText)
            .map { it.value }
            .toSet()

        // Tên riêng MỚI (không có trong gốc và bản cũ)
        val newProperNouns = revisedProperNouns - existingProperNouns - originalProperNouns

        if (newProperNouns.isNotEmpty()) {
            Log.w(TAG, "[HALLUCINATION] Tên riêng mới phát hiện: $newProperNouns")
            return true
        }

        return false
    }
}
