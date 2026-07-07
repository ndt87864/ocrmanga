package com.example.ocrmanga.data.repositories

import android.app.Application
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.utils.AppLogger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

open class TranslationRepositoryAiFallback(application: Application) : TranslationRepositoryCerebras(application) {

    suspend fun translateWithAiFallback(
        initialMode: TranslationMode,
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        sourceLanguage: String,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false
    ): Pair<TranslationMode, List<String>> {
        // Step 1: Xác định chuỗi ưu tiên các dịch vụ dịch AI hoạt động dựa trên lựa chọn ban đầu
        val modeSequence = when (initialMode) {
            TranslationMode.GEMINI -> listOf(TranslationMode.GEMINI, TranslationMode.CEREBRAS, TranslationMode.OCRMANGA, TranslationMode.MISTRAL, TranslationMode.ZAI)
            TranslationMode.MISTRAL -> listOf(TranslationMode.MISTRAL, TranslationMode.CEREBRAS, TranslationMode.OCRMANGA, TranslationMode.GEMINI, TranslationMode.ZAI)
            TranslationMode.ZAI -> listOf(TranslationMode.ZAI, TranslationMode.CEREBRAS, TranslationMode.OCRMANGA, TranslationMode.GEMINI, TranslationMode.MISTRAL)
            TranslationMode.OCRMANGA -> listOf(TranslationMode.OCRMANGA, TranslationMode.CEREBRAS, TranslationMode.GEMINI, TranslationMode.MISTRAL, TranslationMode.ZAI)
            TranslationMode.CEREBRAS -> listOf(TranslationMode.CEREBRAS, TranslationMode.OCRMANGA, TranslationMode.GEMINI, TranslationMode.MISTRAL, TranslationMode.ZAI)
            else -> listOf(TranslationMode.GEMINI, TranslationMode.CEREBRAS, TranslationMode.OCRMANGA, TranslationMode.MISTRAL, TranslationMode.ZAI)
        }

        // Lọc danh sách dịch vụ, chỉ giữ các dịch vụ thực sự có API Key cấu hình
        val activeSequence = modeSequence.filter { mode ->
            when (mode) {
                TranslationMode.GEMINI -> hasGeminiApiKeys()
                TranslationMode.MISTRAL -> hasMistralApiKeys()
                TranslationMode.ZAI -> hasZAiApiKeys()
                TranslationMode.OCRMANGA -> hasOcrMangaApiKeys()
                TranslationMode.CEREBRAS -> hasCerebrasApiKeys()
                else -> false
            }
        }

        Log.i("TranslationRepository", "[AI-FALLBACK] Chuỗi dịch vụ AI khả dụng: ${activeSequence.map { it.name }}")

        // Thử lần lượt các dịch vụ dịch AI khả dụng
        for (mode in activeSequence) {
            val providerName = when (mode) {
                TranslationMode.GEMINI -> "Gemini"
                TranslationMode.MISTRAL -> "Mistral"
                TranslationMode.ZAI -> "Z.AI"
                TranslationMode.OCRMANGA -> "OCR Manga"
                TranslationMode.CEREBRAS -> "Cerebras AI"
                else -> ""
            }

            val serviceType = when (mode) {
                TranslationMode.GEMINI -> "gemini"
                TranslationMode.MISTRAL -> "mistral"
                TranslationMode.ZAI -> "zai"
                TranslationMode.OCRMANGA -> "ocrmanga"
                TranslationMode.CEREBRAS -> "cerebras"
                else -> ""
            }

            val activeKeys = poolManager.getActiveKeys(serviceType)
            if (activeKeys.isEmpty()) {
                Log.w("TranslationRepository", "[AI-FALLBACK] Không có API Key hoạt động nào cho dịch vụ $providerName")
                continue
            }

            Log.i("TranslationRepository", "[AI-FALLBACK] Tìm thấy ${activeKeys.size} API Keys cho dịch vụ $providerName")

            var hasSuccess = false
            var successResult: List<String>? = null

            // Vòng lặp ngoài: Duyệt qua từng API Key trong danh sách hoạt động
            for ((keyIdx, apiKeyInfo) in activeKeys.withIndex()) {
                val apiKeyVal = apiKeyInfo.value
                val apiKeyPrefix = apiKeyVal.take(10)

                // Lấy danh sách các model của dịch vụ đó
                val defaultModels = when (mode) {
                    TranslationMode.GEMINI -> geminiModels
                    TranslationMode.MISTRAL -> mistralModels
                    TranslationMode.ZAI -> zAiModels
                    TranslationMode.OCRMANGA -> ocrMangaModels
                    TranslationMode.CEREBRAS -> cerebrasModels
                    else -> emptyList()
                }

                // Lọc các model được cho phép bởi API Key hiện tại
                val allowedModels = defaultModels.filter { apiKeyInfo.isModelAllowed(it) }
                    .ifEmpty { defaultModels }

                Log.i("TranslationRepository", "[AI-FALLBACK] Key [#${keyIdx + 1}]: prefix=$apiKeyPrefix... | Thử các model: $allowedModels")

                // Vòng lặp trong: Duyệt qua từng Model được cấu hình cho Key hiện tại
                for ((modelIdx, model) in allowedModels.withIndex()) {
                    Log.i("TranslationRepository", "[AI-FALLBACK] Đang thử dịch bằng $providerName | Key: $apiKeyPrefix... | Model: $model")

                    try {
                        val result = when (mode) {
                            TranslationMode.GEMINI -> translateWithGeminiMultiScale(
                                textBlocks = textBlocks,
                                ocrResults = ocrResults,
                                sourceLang = sourceLanguage,
                                targetLang = "vi",
                                previousTranslation = previousTranslation,
                                isAncientMode = isAncientMode,
                                modelOverride = model,
                                apiKeyOverride = apiKeyVal
                            )
                            TranslationMode.MISTRAL -> translateWithMistralMultiScale(
                                textBlocks = textBlocks,
                                ocrResults = ocrResults,
                                sourceLang = sourceLanguage,
                                targetLang = "vi",
                                previousTranslation = previousTranslation,
                                isAncientMode = isAncientMode,
                                modelOverride = model,
                                apiKeyOverride = apiKeyVal
                            )
                            TranslationMode.ZAI -> translateWithZAiMultiScale(
                                textBlocks = textBlocks,
                                ocrResults = ocrResults,
                                sourceLang = sourceLanguage,
                                targetLang = "vi",
                                previousTranslation = previousTranslation,
                                isAncientMode = isAncientMode,
                                modelOverride = model,
                                apiKeyOverride = apiKeyVal
                            )
                            TranslationMode.OCRMANGA -> translateWithOcrMangaMultiScale(
                                textBlocks = textBlocks,
                                ocrResults = ocrResults,
                                sourceLang = sourceLanguage,
                                targetLang = "vi",
                                previousTranslation = previousTranslation,
                                isAncientMode = isAncientMode,
                                modelOverride = model,
                                apiKeyOverride = apiKeyVal
                            )
                            TranslationMode.CEREBRAS -> translateWithCerebrasMultiScale(
                                textBlocks = textBlocks,
                                ocrResults = ocrResults,
                                sourceLang = sourceLanguage,
                                targetLang = "vi",
                                previousTranslation = previousTranslation,
                                isAncientMode = isAncientMode,
                                modelOverride = model,
                                apiKeyOverride = apiKeyVal
                            )
                            else -> null
                        }

                        if (!result.isNullOrEmpty() && result.size == textBlocks.size) {
                            Log.i("TranslationRepository", "[AI-FALLBACK] Dịch thành công bằng $providerName | Key: $apiKeyPrefix... | Model: $model")
                            successResult = result
                            hasSuccess = true
                            break // Thoát vòng lặp models
                        } else {
                            Log.w("TranslationRepository", "[AI-FALLBACK] Kết quả dịch bằng $providerName | Key: $apiKeyPrefix... | Model: $model rỗng hoặc không khớp block count.")
                        }
                    } catch (e: Exception) {
                        Log.e("TranslationRepository", "[AI-FALLBACK] Lỗi dịch bằng $providerName | Key: $apiKeyPrefix... | Model: $model | Exception: ${e.message}")
                    }

                    // Thông báo Toast chuyển model
                    if (modelIdx < allowedModels.size - 1) {
                        val nextModel = allowedModels[modelIdx + 1]
                        Log.w("TranslationRepository", "[AI-FALLBACK] Đổi sang model khác của $providerName: $nextModel")
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                application,
                                "Lỗi dịch bằng $providerName ($model). Đang chuyển sang model $nextModel...",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                if (hasSuccess) {
                    break // Thoát vòng lặp keys
                }

                // Nếu còn key khác trong pool và key này bị lỗi hoàn toàn, hiển thị Toast chuyển API key tiếp theo
                if (keyIdx < activeKeys.size - 1) {
                    val nextKeyPrefix = activeKeys[keyIdx + 1].value.take(10)
                    Log.w("TranslationRepository", "[AI-FALLBACK] Key $apiKeyPrefix... bị lỗi tất cả model. Chuyển sang API Key tiếp theo: $nextKeyPrefix...")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            application,
                            "API Key hiện tại của $providerName lỗi. Đang đổi sang API Key khác trong pool...",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            if (hasSuccess && successResult != null) {
                return Pair(mode, successResult)
            }

            // Nếu đã thử hết các key của dịch vụ hiện tại và vẫn thất bại, hiển thị Toast báo chuyển dịch vụ tiếp theo
            val currentModeIndexInActive = activeSequence.indexOf(mode)
            if (currentModeIndexInActive < activeSequence.size - 1) {
                val nextProvider = when (activeSequence[currentModeIndexInActive + 1]) {
                    TranslationMode.GEMINI -> "Gemini"
                    TranslationMode.MISTRAL -> "Mistral"
                    TranslationMode.ZAI -> "Z.AI"
                    TranslationMode.OCRMANGA -> "OCR Manga"
                    TranslationMode.CEREBRAS -> "Cerebras AI"
                    else -> "AI khác"
                }
                Log.w("TranslationRepository", "[AI-FALLBACK] Tất cả key/model của $providerName đều lỗi. Chuyển dịch vụ sang $nextProvider...")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        application,
                        "Tất cả key và model của $providerName đều lỗi. Đang đổi sang nhà cung cấp khác ($nextProvider)...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        Log.e("TranslationRepository", "[AI-FALLBACK] Toàn bộ hệ thống dịch AI đều gặp sự cố. Kích hoạt dịch trực tuyến khẩn cấp...")
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                application,
                "Tất cả dịch vụ dịch AI đều lỗi. Đang kích hoạt dịch trực tuyến khẩn cấp (Google)...",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        val emergencyResults = coroutineScope {
            textBlocks.map { block ->
                async(Dispatchers.IO) {
                    translateTextOnline(block.text, sourceLanguage)
                }
            }.awaitAll()
        }
        return Pair(TranslationMode.ONLINE, emergencyResults)
    }

}
