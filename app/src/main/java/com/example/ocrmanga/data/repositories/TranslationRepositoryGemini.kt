package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.ocrmanga.data.constant.TranslationPrompts
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.ui.screens.view.analyzeColorsAndBorder
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.gson.JsonParser
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

open class TranslationRepositoryGemini(application: Application) : TranslationRepositoryZaiMistral(application) {

    protected suspend fun translateTextOffline(originalText: String, sourceLanguage: String): String = withContext(Dispatchers.IO) {
        if (originalText.isEmpty()) return@withContext ""
        if (sourceLanguage == "vi") return@withContext originalText
        try {
            val sourceLang = mapLanguageToMLKit(sourceLanguage)
            val translator = translators.getOrPut(sourceLang) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLang)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                    .build()
                Translation.getClient(options).also { translator ->
                    translator.downloadModelIfNeeded()
                        .addOnSuccessListener { Log.i("TranslationRepository", "Đã tải mô hình dịch cho $sourceLang") }
                        .addOnFailureListener { e -> Log.e("TranslationRepository", "Tải mô hình dịch cho $sourceLang thất bại", e) }
                }
            }
            translator.translate(originalText).await()
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Dịch ngoại tuyến thất bại cho văn bản: $originalText", e)
            originalText
        }
    }

    protected suspend fun translateTextOnline(originalText: String, sourceLanguage: String): String = withContext(Dispatchers.IO) {
        if (originalText.isEmpty()) return@withContext ""
        if (sourceLanguage == "vi") return@withContext originalText
        try {
            val encodedText = URLEncoder.encode(originalText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLanguage&tl=vi&dt=t&q=$encodedText"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val json = response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("TranslationRepository", "Yêu cầu dịch trực tuyến thất bại: ${resp.code}")
                    return@withContext originalText
                }
                resp.body?.string()
            } ?: return@withContext originalText
            val jsonArray = JsonParser.parseString(json).asJsonArray
            if (jsonArray.size() == 0) return@withContext originalText
            val translations = mutableListOf<String>()
            val sentencesArray = jsonArray[0].asJsonArray
            for (sentence in sentencesArray) {
                val translationArray = sentence.asJsonArray
                val translatedText = translationArray[0].asString
                translations.add(translatedText)
            }
            translations.joinToString("")
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Dịch trực tuyến thất bại cho văn bản: $originalText", e)
            originalText
        }
    }

    protected suspend fun translateTextWithGemini(originalText: String, sourceLanguage: String): String = withContext(Dispatchers.IO) {
        if (originalText.isEmpty()) return@withContext ""
        if (sourceLanguage == "vi") return@withContext originalText

        val currentModels = geminiModels
        if (currentModels.isEmpty()) return@withContext originalText

        val modelsInOrder = getModelsInRotationOrder(currentModels, currentGeminiModelIndex) {
            currentGeminiModelIndex = (currentGeminiModelIndex + 1) % currentModels.size
        }

        val activeKeys = poolManager.getActiveKeys("gemini")
        if (activeKeys.isEmpty()) return@withContext originalText

        val startingKey = poolManager.selectBestKey("gemini") ?: activeKeys[0]
        val startingKeyIdx = activeKeys.indexOfFirst { it.id == startingKey.id }.coerceAtLeast(0)

        var lastError: Exception? = null
        val triedKeys = mutableSetOf<String>()
        val maxKeysToTry = activeKeys.size.coerceAtMost(6)

        for (kOffset in 0 until maxKeysToTry) {
            val apiKeyInfo = activeKeys[(startingKeyIdx + kOffset) % activeKeys.size]
            val apiKey = apiKeyInfo.value
            triedKeys.add(apiKey)

            // Lấy danh sách model được phép của key này
            val allowedModels = apiKeyInfo.allowedModels.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { currentModels.contains(it) }
                .ifEmpty { currentModels }

            // Ưu tiên theo thứ tự xoay vòng của request
            val modelsToTry = modelsInOrder.filter { allowedModels.contains(it) }
                .ifEmpty { allowedModels }

            Log.i("TranslationRepository", "[GEMINI-ROUTING] Thử Key: ${apiKey.take(10)}... | Models: $modelsToTry")

            for (modelName in modelsToTry) {
                try {
                    val safetySettings = listOf(
                        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
                    )

                    val config = generationConfig {
                        temperature = 1.0f
                        topP = 1.0f
                        topK = 90
                        maxOutputTokens = 6000
                    }

                    val generativeModel = GenerativeModel(
                        modelName = modelName,
                        apiKey = apiKey,
                        safetySettings = safetySettings,
                        generationConfig = config
                    )

                    val prompt = TranslationPrompts.getMistralBasicPrompt(originalText)
                    val response = generativeModel.generateContent(prompt)
                    val content = response.text?.trim() ?: ""
                    val translatedText = content.trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                        .trim()

                    if (!translatedText.isEmpty() && !translatedText.equals(originalText, ignoreCase = true)) {
                        try {
                            val usage = response.usageMetadata
                            if (usage != null) {
                                Log.i("TranslationRepository", "[GEMINI-USAGE] Prompt: ${usage.promptTokenCount} | Completion: ${usage.candidatesTokenCount} | Total: ${usage.totalTokenCount} tokens")
                            }
                        } catch (e: Exception) {}
                        return@withContext translatedText
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.w("TranslationRepository", "[GEMINI-TRY-FAIL] Lỗi model $modelName trên Key ${apiKey.take(10)}...: ${e.javaClass.simpleName} - ${e.message}")
                }
            }
        }

        if (lastError != null) {
            Log.e("TranslationRepository", "[GEMINI-SUMMARY] Thử các key/model Gemini thất bại | Lỗi cuối: ${lastError.message} | Số keys đã thử: ${triedKeys.size}")
        }
        return@withContext originalText
    }

    suspend fun translateWithGeminiMultiScale(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        sourceLang: String,
        targetLang: String,
        previousTranslation: List<TextBlockInfo>? = null, // Bản dịch của ảnh trước để tham khảo
        isAncientMode: Boolean = false,
        skipDetailedLogs: Boolean = false,
        modelOverride: String? = null,
        apiKeyOverride: String? = null
    ): List<String>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null

        var lastError: Exception? = null
        val maxTries = 6 // Thử tối đa 6 lượt (kết hợp key và model)
        
        // Tạo context từ bản dịch ảnh trước (nếu có)
        val previousContextText = if (!previousTranslation.isNullOrEmpty()) {
            Log.i("TranslationRepository", "[GEMINI-PREV] Có bản dịch tham khảo với ${previousTranslation.size} blocks")
            
            // Phân tích và log ngôi xưng hô từ ảnh trước
            val allText = previousTranslation.joinToString(" ") { it.text.uppercase() }
            val pronouns = mutableListOf<String>()
            val hasToi = allText.contains(" TÔI ") || allText.contains("TÔI ")
            val hasMinh = allText.contains(" MÌNH ") || allText.contains("MÌNH ")
            val hasTao = allText.contains(" TAO ") || allText.contains("TAO ")
            val hasCau = allText.contains(" CẬU ") || allText.contains("CẬU ")
            val hasMay = allText.contains(" MÀY ") || allText.contains("MÀY ")
            val hasAnh = allText.contains(" ANH ") || allText.contains("ANH ")
            val hasEm = allText.contains(" EM ")
            
            if (hasToi) pronouns.add("TÔI")
            if (hasMinh) pronouns.add("MÌNH")
            if (hasTao) pronouns.add("TAO")
            if (hasCau) pronouns.add("CẬU")
            if (hasMay) pronouns.add("MÀY")
            if (hasAnh) pronouns.add("ANH")
            if (hasEm) pronouns.add("EM")
            
            // Xác định cặp ngôi chính
            val mainPair = when {
                hasToi && hasCau -> "TÔI-CẬU"
                hasMinh && hasCau -> "MÌNH-CẬU"
                hasTao && hasMay -> "TAO-MÀY"
                hasToi && hasAnh -> "TÔI-ANH"
                hasEm && hasAnh -> "EM-ANH"
                hasToi -> "TÔI"
                hasMinh -> "MÌNH"
                hasTao -> "TAO"
                else -> "không xác định"
            }
            
            Log.i("TranslationRepository", "[GEMINI-PREV] Đại từ phát hiện: ${pronouns.joinToString(", ")}")
            Log.i("TranslationRepository", "[GEMINI-PREV] Cặp ngôi xưng hô chính: $mainPair")
            
            TranslationPrompts.getPreviousContextText(previousTranslation)
        } else {
            Log.i("TranslationRepository", "[GEMINI-PREV] Không có bản dịch tham khảo")
            ""
        }
        
        // Tạo prompt với tất cả kết quả OCR từ các scale khác nhau
        val ocrResultsText = ocrResults.mapIndexed { index, (scale, text) ->
            "Kết quả quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n\n")
        
        // Đánh số các text blocks gốc (sử dụng index + 1 để khớp với logic parse 1-based)
        val numberedBlocks = textBlocks.mapIndexed { index, block ->
            val normalizedText = block.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            "Block #${index + 1}: Text='$normalizedText' Bounds: Rect(${block.bounds.left}, ${block.bounds.top} - ${block.bounds.right}, ${block.bounds.bottom})"
        }.joinToString("\n")
        
        var attempt = 0
        var skipped429 = 0
        val effectiveMaxTries = if (apiKeyOverride != null) 1 else maxTries
        while (attempt < effectiveMaxTries) {
            val modelName = modelOverride ?: getCurrentGeminiModel()
            val useKey = apiKeyOverride ?: (poolManager.selectBestKey("gemini", modelName)?.value ?: return null)

            try {
                val safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
                )
                
                val config = generationConfig {
                    temperature = 1.0f
                    topP = 1.0f
                    topK = 90
                    maxOutputTokens = 6000
                }

                val instructions = TranslationPrompts.getGeminiMultiScalePrompt(
                    ocrResultsText = "DỮ LIỆU ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
                    numberedBlocks = "DANH SÁCH ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
                    blockCount = textBlocks.size,
                    previousContextText = previousContextText,
                    isAncientMode = isAncientMode
                )

                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = useKey,
                    safetySettings = safetySettings,
                    generationConfig = config,
                    systemInstruction = content { text(instructions) }
                )

                val dataContent = """
                    === DỮ LIỆU OCR ===
                    $ocrResultsText

                    === BLOCKS CẦN DỊCH ===
                    $numberedBlocks
                """.trimIndent()
                
                val response = generativeModel.generateContent(dataContent)
                val content = response.text?.trim()

                if (content.isNullOrBlank()) {
                    Log.w("TranslationRepository", "[GEMINI] Response rỗng từ key, model $modelName")
                    continue
                }

                val analysisText = Regex("\\[ANALYSIS\\][\\s\\S]*?(\\[END ANALYSIS\\]|\\[/ANALYSIS\\])").find(content)?.value
                    ?: Regex("\\[ANALYSIS\\][\\s\\S]*?(?=\\n\\s*(?:\\*\\*)?Block #1)").find(content)?.value
                    ?: "Không tìm thấy [ANALYSIS]"
                val translationResult = content.replace(analysisText, "").trim()
                //Log.d("TranslationRepository", "[DEBUG-RESULT] $analysisText")
                //Log.d("TranslationRepository", "KẾT QUẢ DỊCH:\n$translationResult")

                // Báo cáo số token
                try {
                    val usage = response.usageMetadata
                    if (usage != null) {
                        Log.i("TranslationRepository", "[GEMINI-MULTI-USAGE] Prompt: ${usage.promptTokenCount} | Completion: ${usage.candidatesTokenCount} | Total: ${usage.totalTokenCount} tokens")
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "Không thể lấy token usage từ Gemini Multi-Scale: ${e.message}")
                }

                val translatedBlocks = parseMultiBlockResponse(content, textBlocks)

                return translatedBlocks
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                val keyPrefix = useKey.take(10)
                // Nếu là lỗi 429 hoặc throttling thì bỏ qua key này, không tăng attempt
                if (msg.contains("429") || msg.contains("too many requests") || msg.contains("throttl")) {
                    Log.w("TranslationRepository", "[GEMINI-MULTI-429] Key bị giới hạn tốc độ: ${keyPrefix}... | Model: $modelName | Lỗi: ${e.message} | Đã bỏ qua: ${skipped429 + 1}")
                    skipped429++
                    continue // thử key tiếp theo, không tăng attempt
                }
                lastError = e
                Log.e("TranslationRepository", "[GEMINI-MULTI-ERROR] Key bị lỗi: ${keyPrefix}... | Model: $modelName | Exception: ${e.javaClass.simpleName} - ${e.message} | Lần thử: ${attempt + 1}/$maxTries", e)
                attempt++ // chỉ tăng attempt nếu không phải lỗi 429/quota
            }
        }

        if (lastError != null) {
            val errorMessage = lastError.message
            Log.e("TranslationRepository", "[GEMINI-MULTI-SUMMARY] Tất cả key Gemini và ${geminiModels.size} model đều thất bại (multi-scale)")
            Log.e("TranslationRepository", "[GEMINI-MULTI-SUMMARY] Tổng lần thử: $attempt/$maxTries | Keys bị 429/quota: $skipped429 | Lỗi cuối: $errorMessage")
        }
        return null
    }
    
    protected suspend fun translateWithGemini(inputText: String): String? {
        val modelName = getCurrentGeminiModel()
        val apiKeyInfo = poolManager.selectBestKey("gemini", modelName) ?: run {
            Log.e("TranslationRepository", "No Gemini API keys available in pool.")
            return null
        }

        val apiKey = apiKeyInfo.value

        val client = GenerativeModel(
            modelName = modelName,
            apiKey = apiKey
        )

        val prompt = "Translate the following text: $inputText"

        return try {
            val response = client.generateContent(prompt)
            val content = response.text ?: ""

            val analysisText = Regex("\\[ANALYSIS\\][\\s\\S]*?(\\[END ANALYSIS\\]|\\[/ANALYSIS\\])").find(content)?.value
                ?: Regex("\\[ANALYSIS\\][\\s\\S]*?(?=\\n\\s*(?:\\*\\*)?Block #1)").find(content)?.value
                ?: "Không tìm thấy [ANALYSIS]"
            val translationResult = content.replace(analysisText, "").trim()
            //Log.d("TranslationRepository", "[DEBUG-RESULT] $analysisText")
            //Log.d("TranslationRepository", "KẾT QUẢ DỊCH:\n$translationResult")

            if (content.isNotEmpty()) {
                // Báo cáo số token
                try {
                    val usage = response.usageMetadata
                    if (usage != null) {
                        Log.i("TranslationRepository", "[GEMINI-USAGE] Prompt: ${usage.promptTokenCount} | Completion: ${usage.candidatesTokenCount} | Total: ${usage.totalTokenCount} tokens")
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "Không thể lấy token usage từ Gemini: ${e.message}")
                }
            }
            translationResult
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Error during translation: ${e.message}")
            null
        }
    }

    protected fun postProcessOCRText(text: String, detectedScript: String?): String {
        if (text.isBlank()) return text
        return cleanText(text, detectedScript ?: "unknown")
    }

    protected fun cleanText(text: String, detectedScript: String): String {
        return OcrNoiseLanguageHelper.cleanText(text, detectedScript)
    }
    protected fun isNoiseBlock(text: String, bounds: Rect, confidence: Float): Boolean {
        return OcrNoiseLanguageHelper.isNoiseBlock(
            text,
            bounds,
            confidence,
            MIN_BLOCK_AREA,
            MAX_SINGLE_CHAR_ASPECT_RATIO,
            MIN_OCR_CONFIDENCE
        )
    }
    protected fun postProcessTranslation(translatedText: String): String {
        var result = translatedText.trim()
        vietnameseImprovements.forEach { (old, new) -> result = result.replace(old, new, ignoreCase = true) }
        result = result.replace(" .", ".").replace(" ,", ",").replace(" !", "!").replace(" ?", "?")
        if (result.length < 20) {
            result = when {
                result.endsWith("là") -> "$result thế nào nhỉ?"
                result.contains("không") -> "$result đâu mà!"
                result.contains("có") -> "$result thật đấy!"
                else -> result
            }
        }
        return result
    }

    protected fun mapLanguageToMLKit(language: String): String {
        return OcrNoiseLanguageHelper.mapLanguageToMLKit(language)
    }
    protected fun detectLanguage(text: String): String? {
        return OcrNoiseLanguageHelper.detectLanguage(text)
    }
    protected fun getRotationDegrees(imageUri: Uri): Int {
        return try {
            val inputStream = application.contentResolver.openInputStream(imageUri)
            val exif = inputStream?.let { ExifInterface(it) }
            inputStream?.close()
            when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Không thể lấy góc xoay", e)
            0
        }
    }

    protected fun isTextCoherent(text: String): Boolean {
        val normalizedText = text.trim().replace(Regex("\\s+"), " ")
        if (normalizedText.isEmpty()) return false

        val words = normalizedText.split(" ")
        if (words.size < 3) return false

        val vietnameseContentWords = listOf(
            "là", "có", "được", "đi", "làm", "nói", "nghĩ", "biết", "thấy", "muốn", "cần",
            "người", "cái", "nhà", "điều", "thời gian", "công việc", "hôm nay", "tốt", "nhanh", "đẹp"
        )
        val hasContent = words.any { word ->
            vietnameseContentWords.any { contentWord -> word.contains(contentWord, ignoreCase = true) }
        }

        val isNotPunctuationOnly = normalizedText.any { it.isLetterOrDigit() }

        val incompleteEndings = listOf(" và", " nhưng", " hoặc", " vì", " nếu")
        val endsAbruptly = incompleteEndings.any { normalizedText.endsWith(it, ignoreCase = true) }

        return hasContent && isNotPunctuationOnly && !endsAbruptly
    }
}
