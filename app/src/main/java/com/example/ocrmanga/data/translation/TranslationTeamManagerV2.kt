package com.example.ocrmanga.data.translation

import android.app.Application
import com.example.ocrmanga.data.api.NvidiaTranslationServiceV2
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.prompt.PromptBuilder
import com.example.ocrmanga.data.prompt.PromptLoader
import com.example.ocrmanga.utils.AppLogger
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.generationConfig
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.example.ocrmanga.data.models.ReviewResult

/**
 * Translation Team Manager V2 - Sử dụng prompt templates và structured output
 */
class TranslationTeamManagerV2(private val application: Application) {

    companion object {
        private const val TAG = "TeamManagerV2"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val promptLoader = PromptLoader(application)
    private val nvidiaServiceV2 = NvidiaTranslationServiceV2(application, httpClient)
    private val optimizedService = OptimizedTranslationService(
        context = application,
        nvidiaService = com.example.ocrmanga.data.api.NvidiaTranslationService(httpClient)
    )

    // API keys (giống V1)
    private var geminiApiKeys: List<String> = emptyList()
    private var mistralApiKeys: List<String> = emptyList()
    private var currentGeminiKeyIndex = 0
    private var currentMistralKeyIndex = 0

    init {
        loadApiKeys()
    }

    private fun loadApiKeys() {
        val databaseHelper = com.example.ocrmanga.data.database.DatabaseHelper(application)
        val allKeys = databaseHelper.getAllApiKeys()
        geminiApiKeys = allKeys.filter { it.second == "gemini" && it.first.isNotBlank() }.map { it.first }
        mistralApiKeys = allKeys.filter { it.second == "mistral" && it.first.isNotBlank() }.map { it.first }
    }

    private fun getNextGeminiKey(): String? {
        if (geminiApiKeys.isEmpty()) return null
        val key = geminiApiKeys[currentGeminiKeyIndex % geminiApiKeys.size]
        currentGeminiKeyIndex++
        return key
    }

    private fun getNextMistralKey(): String? {
        if (mistralApiKeys.isEmpty()) return null
        val key = mistralApiKeys[currentMistralKeyIndex % mistralApiKeys.size]
        currentMistralKeyIndex++
        return key
    }

    /**
     * Orchestrate review với prompt templates V2
     */
    suspend fun orchestrateReview(
        initialTranslations: List<String>,
        textBlocks: List<TextBlockInfo>,
        mode: TranslationMode,
        isAncientMode: Boolean = false
    ): List<String> {
        if (initialTranslations.isEmpty() || textBlocks.isEmpty()) return initialTranslations

        val maxReviewRounds = when (mode) {
            TranslationMode.MISTRAL -> 3
            else -> return initialTranslations
        }

        AppLogger.i(TAG, "═══ BẮT ĐẦU REVIEW V2 ═══")
        AppLogger.i(TAG, "Mode: $mode | Max rounds: $maxReviewRounds | Blocks: ${textBlocks.size}")

        val current = initialTranslations.toMutableList()
        val frozenBlocks = mutableSetOf<Int>()

        for (round in 1..maxReviewRounds) {
            AppLogger.i(TAG, "── Vòng review #$round/$maxReviewRounds ──")

            // Manager review
            val review = managerReviewV2(textBlocks, current, mode, isAncientMode, frozenBlocks)

            if (review == null) {
                AppLogger.w(TAG, "Review failed at round $round")
                break
            }

            if (review.allApproved) {
                AppLogger.i(TAG, "✓ All blocks approved at round $round (quality: ${review.overallQuality ?: "N/A"})")
                break
            }

            AppLogger.i(TAG, "✗ Need revision: ${review.rejections.size} blocks")

            // Translator revise
            for ((blockIndex, reason) in review.rejections) {
                if (blockIndex < 0 || blockIndex >= textBlocks.size) continue
                if (blockIndex in frozenBlocks) {
                    AppLogger.i(TAG, "[REVISION] Block #${blockIndex + 1}: FROZEN - skip")
                    continue
                }

                val originalText = textBlocks[blockIndex].text
                val currentTranslation = current[blockIndex]

                AppLogger.i(TAG, "[REVISION] Block #${blockIndex + 1}: '$originalText'")
                AppLogger.i(TAG, "[REVISION]   Old: '$currentTranslation'")
                AppLogger.i(TAG, "[REVISION]   Reason: $reason")

                val revised = translatorReviseV2(
                    originalText = originalText,
                    currentTranslation = currentTranslation,
                    managerFeedback = reason,
                    mode = mode,
                    isAncientMode = isAncientMode
                )

                if (revised != null && revised.isNotBlank() && revised != currentTranslation) {
                    if (isHallucinated(originalText, currentTranslation, revised)) {
                        AppLogger.w(TAG, "[REVISION]   HALLUCINATION detected - keep old")
                        frozenBlocks.add(blockIndex)
                    } else {
                        current[blockIndex] = revised
                        frozenBlocks.add(blockIndex)
                        AppLogger.i(TAG, "[REVISION]   New: '$revised'")
                    }
                } else {
                    AppLogger.w(TAG, "[REVISION]   No revision, keep old")
                    frozenBlocks.add(blockIndex)
                }
            }
        }

        AppLogger.i(TAG, "═══ KẾT THÚC REVIEW V2 ═══")
        return current
    }

    /**
     * Manager review với structured JSON output
     */
    private suspend fun managerReviewV2(
        textBlocks: List<TextBlockInfo>,
        translations: List<String>,
        mode: TranslationMode,
        isAncientMode: Boolean,
        frozenBlocks: Set<Int> = emptySet()
    ): ReviewResult? {
        // Load prompt templates
        val systemTemplate = promptLoader.loadPrompt("manager_v2.md") ?: run {
            AppLogger.e(TAG, "Failed to load manager system prompt")
            return null
        }

        val userTemplate = promptLoader.loadPrompt("manager_user_prompt_v2.md") ?: run {
            AppLogger.e(TAG, "Failed to load manager user prompt")
            return null
        }

        // Build prompts
        val systemPrompt = systemTemplate.content + "\n\nCRITICAL: Return ONLY valid JSON."
        val userPrompt = PromptBuilder.buildReviewUserPrompt(
            template = userTemplate,
            textBlocks = textBlocks,
            translations = translations,
            isAncientMode = isAncientMode
        )

        // Call API
        val rawResponse = when (mode) {
            TranslationMode.MISTRAL -> callMistralForReview(systemPrompt, userPrompt)
            TranslationMode.GEMINI -> callGeminiForReview("$systemPrompt\n\n$userPrompt")
            else -> null
        } ?: return null

        // Parse structured JSON
        return optimizedService.parseReviewResponse(rawResponse, textBlocks.size)
    }

    /**
     * Translator revise với prompt templates
     */
    private suspend fun translatorReviseV2(
        originalText: String,
        currentTranslation: String,
        managerFeedback: String,
        mode: TranslationMode,
        isAncientMode: Boolean
    ): String? {
        val systemTemplate = promptLoader.loadPrompt("translator_v2.md") ?: return null

        val ancientInstruction = if (isAncientMode) {
            "⚠️ CHẾ ĐỘ CỔ TRANG: Bắt buộc dùng văn phong Hán Việt."
        } else ""

        val revisionPrompt = """
Gốc: $originalText
Bản dịch hiện tại: $currentTranslation
Góp ý của Quản lý: $managerFeedback

$ancientInstruction

Hãy dịch lại câu trên để khắc phục lỗi. Chỉ trả về bản dịch mới, không giải thích.
        """.trimIndent()

        val response = when (mode) {
            TranslationMode.MISTRAL -> callMistralForRevision(systemTemplate.content, revisionPrompt)
            TranslationMode.GEMINI -> callGeminiForRevision("${systemTemplate.content}\n\n$revisionPrompt")
            else -> null
        } ?: return null

        return response.trim()
            .replace("**", "")
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
    }

    // API call helpers (giống V1 nhưng tách riêng cho review và revision)
    private suspend fun callMistralForReview(systemPrompt: String, userPrompt: String): String? {
        return callMistralAPI(systemPrompt, userPrompt, temperature = 0.3, maxTokens = 1024)
    }

    private suspend fun callMistralForRevision(systemPrompt: String, userPrompt: String): String? {
        return callMistralAPI(systemPrompt, userPrompt, temperature = 0.5, maxTokens = 512)
    }

    private suspend fun callMistralAPI(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? {
        val apiKey = getNextMistralKey() ?: return null

        val gson = com.google.gson.Gson()
        val bodyMap = mapOf(
            "model" to "mistral-medium-latest",
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to temperature,
            "top_p" to 0.9,
            "max_tokens" to maxTokens
        )
        val requestBody = gson.toJson(bodyMap)

        val request = okhttp3.Request.Builder()
            .url("https://api.mistral.ai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
            .build()

        return try {
            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.e(TAG, "[MISTRAL] Error: ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                json["choices"]?.asJsonArray
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[MISTRAL] Exception: ${e.message}", e)
            null
        }
    }

    private suspend fun callGeminiForReview(prompt: String): String? {
        return callGeminiAPI(prompt, temperature = 0.3f, maxTokens = 1024)
    }

    private suspend fun callGeminiForRevision(prompt: String): String? {
        return callGeminiAPI(prompt, temperature = 0.5f, maxTokens = 512)
    }

    private suspend fun callGeminiAPI(
        prompt: String,
        temperature: Float,
        maxTokens: Int
    ): String? {
        val apiKey = getNextGeminiKey() ?: return null

        return try {
            val safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
            )

            val config = generationConfig {
                this.temperature = temperature
                topP = 0.9f
                maxOutputTokens = maxTokens
            }

            val model = GenerativeModel(
                modelName = "gemini-flash-latest",
                apiKey = apiKey,
                safetySettings = safetySettings,
                generationConfig = config
            )

            val response = model.generateContent(prompt)
            response.text?.trim()
        } catch (e: Exception) {
            AppLogger.e(TAG, "[GEMINI] Exception: ${e.message}", e)
            null
        }
    }

    // Hallucination detection (giống V1)
    private fun isHallucinated(originalText: String, currentTranslation: String, revised: String): Boolean {
        val latinWordPattern = Regex("[A-Za-zÀ-ỹ]{3,}")
        val revisedLatinWords = latinWordPattern.findAll(revised)
            .map { it.value.lowercase() }
            .toSet()

        if (revisedLatinWords.isEmpty()) return false

        val existingLatinWords = latinWordPattern.findAll(currentTranslation)
            .map { it.value.lowercase() }
            .toSet()

        val originalLatinWords = latinWordPattern.findAll(originalText)
            .map { it.value.lowercase() }
            .toSet()

        val commonVietnameseWords = setOf(
            "tao", "mày", "tôi", "cậu", "mình", "anh", "chị", "em", "nó", "hắn", "nàng",
            "ngươi", "các", "hạ", "tại", "bổn", "lão", "bần", "đừng", "không", "hãy",
            "đã", "đang", "sẽ", "được", "bị", "cho", "làm", "nói", "nào", "đến", "đi",
            "lại", "cứu", "kêu", "gọi", "biết", "muốn", "thấy", "nghe", "nhìn", "cần",
            "phải", "nên", "còn", "hết", "xong", "rồi", "nhưng", "mà", "thì", "với",
            "của", "và", "hay", "hoặc", "nếu", "vì", "này", "đó", "kia", "ấy", "sao",
            "gì", "nào", "đâu", "bao", "à", "nhé", "nhỉ", "hả", "chứ", "ạ", "ơi",
            "vậy", "thế", "thôi"
        )

        val newLatinWords = revisedLatinWords - existingLatinWords - originalLatinWords - commonVietnameseWords

        if (newLatinWords.isNotEmpty()) {
            AppLogger.w(TAG, "[HALLUCINATION] New Latin words: $newLatinWords")
            return true
        }

        return false
    }
}
