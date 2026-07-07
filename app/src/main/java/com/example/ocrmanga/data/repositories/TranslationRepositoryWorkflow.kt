package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

open class TranslationRepositoryWorkflow(application: Application) : TranslationRepositoryOcrRotation(application) {

    // Hàm dịch lại 1 ảnh, trả về Pair<text dịch, list block dịch>
    suspend fun translateImage(
        imageUri: Uri,
        mode: TranslationMode,
        onStatusUpdate: ((com.example.ocrmanga.data.models.TranslationStatus) -> Unit)? = null,
        previousTranslation: List<TextBlockInfo>? = null, // Bản dịch của ảnh trước để tham khảo
        isAncientMode: Boolean = false,
        reuseExistingBlocks: List<TextBlockInfo>? = null // Nếu không null, skip OCR và dùng lại các block đã có (vị trí, text gốc)
    ): Pair<String, List<TextBlockInfo>> {
        val (translatedText, translatedBlocks, _) = recognizeAndTranslateText(imageUri, mode, onStatusUpdate, previousTranslation, isAncientMode, reuseExistingBlocks = reuseExistingBlocks)
        return Pair(translatedText, translatedBlocks)
    }

    suspend fun recognizeTextRegionsForRemoval(imageUri: Uri): List<TextBlockInfo> = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            bitmap = MediaStore.Images.Media.getBitmap(application.contentResolver, imageUri)
            val rotationDegrees = getRotationDegrees(imageUri)
            val scaleFactors = listOf(1.0f, 1.3f)
            val recognizers = listOf(chineseRecognizer, japaneseRecognizer, koreanRecognizer, latinRecognizer)
            val detectedBlocks = mutableListOf<TextBlockInfo>()

            for (scale in scaleFactors) {
                for (recognizer in recognizers) {
                    var preprocessedBitmap: Bitmap? = null
                    try {
                        val enhanceMode = if (scale > 1.0f) 1 else 0
                        val (preBitmap, _) = preprocessImage(bitmap, scale, enhanceMode)
                        preprocessedBitmap = preBitmap
                        val inputImage = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
                        val result = recognizer.process(inputImage).await()

                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                for (element in line.elements) {
                                    val bounds = element.boundingBox ?: continue
                                    val scaledBounds = Rect(
                                        (bounds.left / scale).toInt().coerceIn(0, bitmap.width),
                                        (bounds.top / scale).toInt().coerceIn(0, bitmap.height),
                                        (bounds.right / scale).toInt().coerceIn(0, bitmap.width),
                                        (bounds.bottom / scale).toInt().coerceIn(0, bitmap.height)
                                    )
                                    val text = element.text.trim()
                                    if (text.isBlank() || scaledBounds.width() <= 1 || scaledBounds.height() <= 1) continue

                                    detectedBlocks.add(
                                        TextBlockInfo(
                                            text = text,
                                            originalText = text,
                                            bounds = scaledBounds,
                                            fontSize = scaledBounds.height().toFloat(),
                                            originalFontSize = scaledBounds.height().toFloat(),
                                            wordCountsPerLine = listOf(1),
                                            originalImageWidth = bitmap.width,
                                            originalImageHeight = bitmap.height,
                                            applyMerge = false
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("TranslationRepository", "[TEXT-REMOVAL-OCR] Element OCR failed scale=$scale", e)
                    } finally {
                        preprocessedBitmap?.recycle()
                    }
                }
            }

            val resultBlocks = detectedBlocks.sortedByDescending { it.bounds.width() * it.bounds.height() }
                .fold(mutableListOf<TextBlockInfo>()) { kept, candidate ->
                    val isDuplicate = kept.any { existing ->
                        val overlapLeft = maxOf(existing.bounds.left, candidate.bounds.left)
                        val overlapTop = maxOf(existing.bounds.top, candidate.bounds.top)
                        val overlapRight = minOf(existing.bounds.right, candidate.bounds.right)
                        val overlapBottom = minOf(existing.bounds.bottom, candidate.bounds.bottom)
                        val overlapArea = maxOf(0, overlapRight - overlapLeft) * maxOf(0, overlapBottom - overlapTop)
                        val candidateArea = (candidate.bounds.width() * candidate.bounds.height()).coerceAtLeast(1)
                        overlapArea.toFloat() / candidateArea > 0.5f
                    }
                    if (!isDuplicate) kept.add(candidate)
                    kept
                }
                .sortedWith(compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left })

            Log.i("TranslationRepository", "[TEXT-REMOVAL-OCR] Detected ${resultBlocks.size} element regions for $imageUri")
            resultBlocks
        } finally {
            bitmap?.recycle()
        }
    }

    suspend fun recognizeAndTranslateText(
        imageUri: Uri,
        mode: TranslationMode,
        onStatusUpdate: ((com.example.ocrmanga.data.models.TranslationStatus) -> Unit)? = null,
        previousTranslation: List<TextBlockInfo>? = null, // Bản dịch của ảnh trước để tham khảo
        isAncientMode: Boolean = false,
        reuseExistingBlocks: List<TextBlockInfo>? = null, // Nếu không null, dùng lại blocks đã có (vị trí, text gốc) thay vì OCR mới
        onOcrCompleted: (suspend (List<TextBlockInfo>) -> Unit)? = null // Callback khi vừa OCR xong (trước khi dịch)
    ): Triple<String, List<TextBlockInfo>, String> = withContext(Dispatchers.IO) {
        val rotationDegrees = getRotationDegrees(imageUri)
        Log.i("TranslationRepository", "[PIPELINE-START] uri=$imageUri, mode=$mode, rotation=$rotationDegrees")
        
        if (mode == TranslationMode.OFF) {
            return@withContext Triple("", emptyList(), "zh")
        }

        // Kiểm tra sớm API keys khả dụng
        if (mode == TranslationMode.GEMINI && !hasGeminiApiKeys()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "Không có API key Gemini. Vui lòng thêm ít nhất một API key Gemini trong cài đặt để dùng tính năng dịch Gemini.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext Triple("", emptyList(), "zh")
        }

        if (mode == TranslationMode.MISTRAL && !hasMistralApiKeys()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "Không có API key Mistral. Vui lòng thêm ít nhất một API key Mistral trong cài đặt để dùng tính năng dịch Mistral.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext Triple("", emptyList(), "zh")
        }

        if (mode == TranslationMode.ZAI && !hasZAiApiKeys()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "Không có API key Z.AI. Vui lòng thêm ít nhất một API key Z.AI trong cài đặt để dùng tính năng dịch Z.AI.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext Triple("", emptyList(), "zh")
        }

        if (mode == TranslationMode.OCRMANGA && !hasOcrMangaApiKeys()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "Không có API key OCR Manga. Vui lòng thêm ít nhất một API key OCR Manga trong cài đặt để dùng tính năng dịch OCR Manga.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext Triple("", emptyList(), "zh")
        }

        if (mode == TranslationMode.CEREBRAS && !hasCerebrasApiKeys()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "Không có API key Cerebras AI. Vui lòng thêm ít nhất một API key Cerebras AI trong cài đặt để dùng tính năng dịch Cerebras AI.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext Triple("", emptyList(), "zh")
        }

        val cacheKey = "$imageUri-$mode"
        cache[cacheKey]?.let {
            lastTranslationSession.add(Pair(imageUri, Pair("(cache)", it.first)))
            return@withContext Triple(it.first, it.second, detectLanguage(it.first) ?: "zh")
        }

        var bitmap: Bitmap? = null
        val textBlocksToTranslate: List<TextBlockInfo>
        val ocrResultsToUse: List<Pair<Float, String>>
        var sourceLanguage: String = "zh"
        val isReuse = !reuseExistingBlocks.isNullOrEmpty()
        var fullText = ""

        try {
            if (isReuse) {
                // Nhánh 1: Dùng OCR cũ lấy từ DB
                Log.i("TranslationRepository", "[REUSE-OCR] Sử dụng ${reuseExistingBlocks!!.size} blocks OCR cũ từ DB, bỏ qua quét mới")
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.TRANSLATING)
                }
                
                val sourceTexts = reuseExistingBlocks.map { it.originalText ?: it.text }
                val joinedSourceText = sourceTexts.joinToString("\n")
                fullText = joinedSourceText
                sourceLanguage = detectLanguage(joinedSourceText) ?: "zh"
                
                // Chuẩn hóa text blocks
                textBlocksToTranslate = reuseExistingBlocks.map { b ->
                    b.copy(text = b.originalText ?: b.text)
                }
                ocrResultsToUse = listOf(Pair(1.0f, joinedSourceText))
            } else {
                // Nhánh 2: Tiến hành quét OCR mới từ ảnh gốc
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.SCANNING)
                }
                
                bitmap = MediaStore.Images.Media.getBitmap(application.contentResolver, imageUri)
                
                // Phát hiện ngôn ngữ preview từ ảnh
                val previewText = try {
                    val (previewText, _) = recognizeText(bitmap, rotationDegrees, onlyPreview = true)
                    previewText
                } catch (e: Exception) {
                    ""
                }
                val detectedScript = detectLanguage(previewText) ?: "zh"
                
                val useRotationStrategy = detectedScript in listOf("ja", "zh", "ko")
                val (rawText, textBlocks) = if (useRotationStrategy) {
                    Log.i("TranslationRepository", "[ROTATION] Sử dụng chiến dịch xoay ảnh cho script: $detectedScript")
                    recognizeTextWithRotationStrategy(bitmap, rotationDegrees, forceScript = detectedScript)
                } else {
                    recognizeText(bitmap, rotationDegrees, forceScript = detectedScript)
                }
                
                Log.i("TranslationRepository", "===== KẾT QUẢ QUÉT OCR MỚI (${textBlocks.size} blocks) =====")
                textBlocks.forEachIndexed { index, block ->
                    val textColorHex = block.originalTextColor?.let { String.format("#%08X", it) } ?: "null"
                    val overlayColorHex = block.averageBackgroundColor?.let { String.format("#%08X", it) } ?: "null"
                    val containerTypeStr = block.containerInfo?.type?.name ?: "UNKNOWN"
                    Log.i("TranslationRepository", "[OCR-BLOCK] #$index: Text='${block.text}' Bounds: ${block.bounds} Color: Text=$textColorHex, Bg=$overlayColorHex")
                }
                Log.i("TranslationRepository", "================================================")
                
                fullText = rawText
                if (fullText.isEmpty()) {
                    Log.w("TranslationRepository", "Không nhận diện được văn bản trong $imageUri")
                    lastTranslationSession.add(Pair(imageUri, Pair("", "")))
                    return@withContext Triple("", emptyList(), "zh")
                }
                
                sourceLanguage = detectLanguage(fullText) ?: "zh"
                onOcrCompleted?.invoke(textBlocks)
                
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.TRANSLATING)
                }
                
                val allOcrResults = recognizeTextAllScales(bitmap, rotationDegrees, forceScript = detectedScript)
                if (allOcrResults.isEmpty()) {
                    Log.w("TranslationRepository", "Không có kết quả OCR nào từ các scale")
                    lastTranslationSession.add(Pair(imageUri, Pair("", "")))
                    return@withContext Triple("", emptyList(), "zh")
                }
                
                val blocksWithBubble = assignSpeechBubblesToBlocks(textBlocks)
                val mergedBlocks = mergeBlocksByBubble(blocksWithBubble, bitmap!!)
                
                textBlocksToTranslate = mergedBlocks
                ocrResultsToUse = allOcrResults
            }

            // Nếu chỉ quét chế độ OCR thì trả về luôn không dịch
            if (mode == TranslationMode.OCR || mode == TranslationMode.EXTERNAL) {
                return@withContext Triple(fullText, textBlocksToTranslate, sourceLanguage)
            }

            // Thực hiện dịch với cơ chế dự phòng tự động (translateWithAiFallback)
            val (finalModeUsed, finalTranslatedTexts) = translateWithAiFallback(
                initialMode = mode,
                textBlocks = textBlocksToTranslate,
                ocrResults = ocrResultsToUse,
                sourceLanguage = sourceLanguage,
                previousTranslation = previousTranslation,
                isAncientMode = isAncientMode
            )

            // Thông báo: đang phân phối bản dịch trở lại tọa độ
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.DISTRIBUTING)
            }

            val defaultSettings = getDefaultFontSettings()
            val blocks = mutableListOf<TextBlockInfo>()
            
            textBlocksToTranslate.forEachIndexed { index, block ->
                val translatedTextForBlock = finalTranslatedTexts.getOrNull(index) ?: block.text
                val naturalText = postProcessTranslation(translatedTextForBlock)
                
                val isVertical = block.isVertical
                val reformattedText = if (!isVertical && block.wordCountsPerLine != null) {
                    val words = naturalText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val wordCounts = block.wordCountsPerLine
                    val reformattedLines = mutableListOf<String>()
                    var wordIndex = 0
                    for (wordCount in wordCounts) {
                        if (wordIndex >= words.size) break
                        val lineWords = words.subList(wordIndex, minOf(wordIndex + wordCount, words.size))
                        reformattedLines.add(lineWords.joinToString(" "))
                        wordIndex += wordCount
                    }
                    val maxWordsPerLine = wordCounts.lastOrNull() ?: 5
                    while (wordIndex < words.size) {
                        val remainingWords = words.subList(wordIndex, minOf(wordIndex + maxWordsPerLine, words.size))
                        reformattedLines.add(remainingWords.joinToString(" "))
                        wordIndex += maxWordsPerLine
                    }
                    reformattedLines.joinToString("\n")
                } else {
                    naturalText
                }

                // Tính toán fontSize mới để vừa với overlay
                val adjustedFontSize = calculateAdjustedFontSize(
                    reformattedText,
                    block.text,
                    block.bounds,
                    block.fontSize,
                    isVertical
                )

                // Nếu là reuse block (OCR cũ), tuyệt đối giữ nguyên tọa độ gốc của block
                val newBounds = if (isReuse) {
                    android.graphics.Rect(block.bounds)
                } else {
                    adjustBoundsForTranslatedText(reformattedText, block.bounds, adjustedFontSize, 1.0f)
                }

                val newBlock = block.copy(
                    text = reformattedText,
                    originalText = block.text,
                    bounds = newBounds,
                    fontSize = adjustedFontSize,
                    fontFamily = defaultSettings["fontFamily"] as? String ?: "Default",
                    lineSpacing = defaultSettings["lineSpacing"] as? Float ?: 1.0f,
                    textBoldness = defaultSettings["textBoldness"] as? Float ?: 1.0f,
                    overlayAlpha = defaultSettings["overlayAlpha"] as? Float ?: 0.8f,
                    overlaySaturation = defaultSettings["overlayBrightness"] as? Float ?: 1.0f,
                    customBorderColor = block.customBorderColor ?: (defaultSettings["borderColor"] as? String)?.let { android.graphics.Color.parseColor(it) },
                    borderThickness = if (block.borderThickness > 0f) block.borderThickness else (defaultSettings["borderThickness"] as? Float ?: 2.0f),
                    customTextColor = block.originalTextColor,
                    applyMerge = !isReuse
                )
                
                Log.i("TranslationRepository", "[TRANS-AI-FALLBACK] Mode=$finalModeUsed Block #${index + 1}:")
                Log.i("TranslationRepository", "    + Input : '${block.text}'")
                Log.i("TranslationRepository", "    + Output: '$reformattedText'")
                
                blocks.add(newBlock)
            }
            
            var resultText = blocks.joinToString("\n") { it.text }
            var translatedBlocks = blocks
            
            // Xử lý kiểm tra kết quả cuối tiếng Việt (chỉ cho luồng quét OCR mới)
            if (!isReuse && detectLanguage(resultText) != "vi" && bitmap != null) {
                Log.w("TranslationRepository", "Kết quả dịch chưa chuẩn tiếng Việt, thử quét lại 1 lần khẩn cấp...")
                val (rawText2, textBlocks2) = recognizeText(bitmap, rotationDegrees)
                val blocks2 = mutableListOf<TextBlockInfo>()
                val blocksWithBubble2 = assignSpeechBubblesToBlocks(textBlocks2)
                val mergedBlocks2 = mergeBlocksByBubble(blocksWithBubble2, bitmap)
                for (block in mergedBlocks2) {
                    var translatedText = when (mode) {
                        TranslationMode.OFFLINE -> translateTextOffline(block.text, sourceLanguage)
                        TranslationMode.ONLINE -> translateTextOnline(block.text, sourceLanguage)
                        TranslationMode.GEMINI -> translateTextWithGemini(block.text, sourceLanguage)
                        TranslationMode.MISTRAL -> translateWithMistral(block.text, sourceLanguage, "vi") ?: ""
                        TranslationMode.ZAI -> translateWithZAi(block.text, sourceLanguage, "vi") ?: ""
                        TranslationMode.OCRMANGA -> translateWithOcrManga(block.text, sourceLanguage, "vi") ?: ""
                        else -> block.text
                    }
                    val detectedAfterTranslation = detectLanguage(translatedText) ?: "vi"
                    if (detectedAfterTranslation != "vi" && mode != TranslationMode.OFF) {
                        translatedText = when (mode) {
                            TranslationMode.OFFLINE -> translateTextOffline(translatedText, detectedAfterTranslation)
                            TranslationMode.ONLINE -> translateTextOnline(translatedText, detectedAfterTranslation)
                            TranslationMode.GEMINI -> translateTextWithGemini(translatedText, detectedAfterTranslation)
                            TranslationMode.MISTRAL -> translateWithMistral(translatedText, detectedAfterTranslation, "vi") ?: translatedText
                            TranslationMode.ZAI -> translateWithZAi(translatedText, detectedAfterTranslation, "vi") ?: translatedText
                            TranslationMode.OCRMANGA -> translateWithOcrManga(translatedText, detectedAfterTranslation, "vi") ?: translatedText
                            else -> translatedText
                        }
                    }
                    val naturalText = postProcessTranslation(translatedText)
                    val isVertical = block.isVertical
                    val reformattedText = if (!isVertical && block.wordCountsPerLine != null) {
                        val words = naturalText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val wordCounts = block.wordCountsPerLine
                        val reformattedLines = mutableListOf<String>()
                        var wordIndex = 0
                        val safeWordCounts = wordCounts ?: emptyList<Int>()
                        for (i in 0 until safeWordCounts.size) {
                            val wordCount = safeWordCounts[i]
                            if (wordIndex >= words.size) break
                            val nextIdx = minOf(wordIndex + wordCount, words.size)
                            val lineWords = words.subList(wordIndex, nextIdx)
                            reformattedLines.add(lineWords.joinToString(" "))
                            wordIndex = nextIdx
                        }
                        val maxWordsPerLine = safeWordCounts.lastOrNull() ?: 5
                        while (wordIndex < words.size) {
                            val nextIdx = minOf(wordIndex + maxWordsPerLine, words.size)
                            val remainingWords = words.subList(wordIndex, nextIdx)
                            reformattedLines.add(remainingWords.joinToString(" "))
                            wordIndex = nextIdx
                        }
                        reformattedLines.joinToString("\n")
                    } else {
                        naturalText
                    }
                    val newBounds = adjustBoundsForTranslatedText(reformattedText, block.bounds, block.fontSize, 1.0f)
                    blocks2.add(block.copy(
                        text = reformattedText,
                        originalText = block.text,
                        bounds = newBounds,
                        fontFamily = defaultSettings["fontFamily"] as? String ?: "Default",
                        lineSpacing = defaultSettings["lineSpacing"] as? Float ?: 1.0f,
                        textBoldness = defaultSettings["textBoldness"] as? Float ?: 1.0f,
                        overlayAlpha = defaultSettings["overlayAlpha"] as? Float ?: 0.8f,
                        overlaySaturation = defaultSettings["overlayBrightness"] as? Float ?: 1.0f,
                        customBorderColor = (defaultSettings["borderColor"] as? String)?.let { android.graphics.Color.parseColor(it) },
                        borderThickness = defaultSettings["borderThickness"] as? Float ?: 2.0f,
                        customTextColor = block.originalTextColor,
                        applyMerge = true
                    ))
                }
                val resultText2 = blocks2.joinToString("\n") { it.text }
                if (detectLanguage(resultText2) == "vi") {
                    resultText = resultText2
                    translatedBlocks = blocks2
                }
            }

            // Kiểm tra và sửa các block chưa được dịch ra tiếng Việt
            val finalBlocks = translatedBlocks.map { block ->
                val lang = detectLanguage(block.text) ?: ""
                if (lang != "vi" && mode != TranslationMode.OFF) {
                    val retryText = when (mode) {
                        TranslationMode.OFFLINE -> translateTextOnline(block.text, sourceLanguage)
                        TranslationMode.ONLINE -> translateTextWithGemini(block.text, sourceLanguage)
                        TranslationMode.GEMINI -> translateTextOffline(block.text, sourceLanguage)
                        TranslationMode.MISTRAL -> translateWithMistral(block.text, sourceLanguage, "vi") ?: block.text
                        TranslationMode.ZAI -> translateWithZAi(block.text, sourceLanguage, "vi") ?: block.text
                        else -> block.text
                    }
                    val retryLang = detectLanguage(retryText) ?: ""
                    if (retryLang == "vi") {
                        block.copy(text = postProcessTranslation(retryText), originalText = block.originalText ?: block.text, applyMerge = !isReuse)
                    } else {
                        block.copy(originalText = block.originalText ?: block.text, applyMerge = !isReuse)
                    }
                } else {
                    block.copy(applyMerge = !isReuse)
                }
            }
            
            val normalizedColorsBlocks = normalizeBlockColors(finalBlocks)
            val finalResultText = normalizedColorsBlocks.joinToString("\n") { it.text }
            lastTranslationSession.add(Pair(imageUri, Pair(fullText, finalResultText)))
            cache[cacheKey] = finalResultText to normalizedColorsBlocks
            return@withContext Triple(finalResultText, normalizedColorsBlocks, sourceLanguage)
        } catch (e: IOException) {
            Log.e("TranslationRepository", "Lỗi IO với $imageUri", e)
            lastTranslationSession.add(Pair(imageUri, Pair(fullText, "")))
            return@withContext Triple("", emptyList(), "zh")
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Lỗi xử lý $imageUri", e)
            lastTranslationSession.add(Pair(imageUri, Pair(fullText, "")))
            return@withContext Triple("", emptyList(), "zh")
        } finally {
            bitmap?.recycle()
            bitmap = null
            System.gc()
        }
    }

}
