package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.ocrmanga.data.models.RecognitionResult
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.ui.screens.view.analyzeBackgroundAndTextColor
import com.example.ocrmanga.ui.screens.view.analyzeColorsAndBorder
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

open class TranslationRepositoryOcrRecognition(application: Application) : TranslationRepositoryOcrHelpers(application) {

    protected suspend fun recognizeTextAllScales(
        bitmap: Bitmap,
        rotationDegrees: Int,
        forceScript: String? = null
    ): List<Pair<Float, String>> = withContext(Dispatchers.IO) {
        // Optimized scale factors for manga/comic text - more diverse range
        val scaleFactors = listOf(0.85f, 1.0f, 1.15f, 1.35f)
        // Different enhancement modes for better coverage
        val enhanceModes = listOf(0, 1, 2) // standard, high contrast, soft contrast
        
        val recognizers = when (forceScript) {
            "zh" -> listOf(chineseRecognizer)
            "ja" -> listOf(japaneseRecognizer, chineseRecognizer) // Cả 2 để bắt Kanji tốt hơn
            "ko" -> listOf(koreanRecognizer)
            "en", "es" -> listOf(latinRecognizer)
            else -> listOf(chineseRecognizer, japaneseRecognizer, koreanRecognizer, latinRecognizer)
        }
        
        val allResults = mutableListOf<Pair<Float, String>>()
        val seenTexts = mutableSetOf<String>() // Để tránh trùng lặp
        
        // Thu thập kết quả từ tất cả các scale và enhancement modes
        scaleFactors.forEach { scale ->
            // Chỉ dùng 1 enhance mode cho mỗi scale để tăng tốc
            val enhanceMode = when {
                scale < 1.0f -> 2 // Soft contrast cho scale nhỏ
                scale > 1.2f -> 1 // High contrast cho scale lớn
                else -> 0 // Standard cho scale trung bình
            }
            
            recognizers.forEach { recognizer ->
                var preprocessedBitmap: Bitmap? = null
                try {
                    val (preBitmap, _) = preprocessImage(bitmap, scale, enhanceMode)
                    preprocessedBitmap = preBitmap
                    val scaledInputImage = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
                    val result = recognizer.process(scaledInputImage).await()
                    
                    if (result.text.isNotEmpty()) {
                        // Áp dụng post-processing để sửa lỗi OCR (bao gồm lọc CJK cho Latin mode)
                        val processedText = postProcessOCRText(result.text, forceScript)
                        
                        // Với Latin mode: kiểm tra thêm, nếu text vẫn chứa nhiều CJK -> bỏ qua
                        val isLatinForAllScales = forceScript == "en" || forceScript == "es"
                        val cleanedText = if (isLatinForAllScales) {
                            val cjkRemain = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]")
                                .findAll(processedText).count()
                            val totalNonSpace = processedText.count { !it.isWhitespace() }
                            if (totalNonSpace > 0 && cjkRemain.toFloat() / totalNonSpace > 0.3f) {
                                "" // Quá nhiều CJK trong kết quả Latin -> bỏ
                            } else {
                                processedText
                            }
                        } else {
                            processedText
                        }
                        
                        // Chỉ thêm nếu text có ý nghĩa và chưa có
                        val normalizedText = cleanedText.trim().lowercase()
                        if (cleanedText.isNotBlank() && !seenTexts.contains(normalizedText)) {
                            allResults.add(Pair(scale, cleanedText))
                            seenTexts.add(normalizedText)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TranslationRepository", "OCR failed for scale $scale, enhance=$enhanceMode, recognizer ${recognizer.javaClass.simpleName}", e)
                } finally {
                    preprocessedBitmap?.recycle()
                }
            }
        }
        
        return@withContext allResults
    }

    // recognizeText mới: cho phép chỉ quét preview hoặc ép loại recognizer
    protected suspend fun recognizeText(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onlyPreview: Boolean = false,
        forceScript: String? = null,
        skipSort: Boolean = false
    ): Pair<String, List<TextBlockInfo>> = withContext(Dispatchers.IO) {
        // Optimized scale factors for manga/comic text recognition
        // Using more diverse scales to catch text at different sizes
        val scaleFactors = if (onlyPreview) listOf(0.9f, 1.1f) else listOf(0.85f, 1.0f, 1.15f, 1.3f)
        val recognizers = when (forceScript) {
            "zh" -> listOf(chineseRecognizer)
            "ja" -> listOf(japaneseRecognizer, chineseRecognizer) // Cả 2 recognizer để bắt Kanji tốt hơn
            "ko" -> listOf(koreanRecognizer)
            // Treat Spanish ("es") the same as English (Latin script)
            "en", "es" -> listOf(latinRecognizer)
            else -> listOf(chineseRecognizer, japaneseRecognizer, koreanRecognizer, latinRecognizer)
        }
        val deferredResults = scaleFactors.flatMap { scale ->
            recognizers.map { recognizer ->
                async {
                    var preprocessedBitmap: Bitmap? = null
                    try {
                        // Dùng enhance mode khác nhau theo scale để phát hiện text trên nền phức tạp
                        val enhanceMode = when {
                            scale < 1.0f -> 2  // Soft contrast cho scale nhỏ
                            scale > 1.2f -> 1  // High contrast cho scale lớn (tốt cho text trên nền tối)
                            else -> 0          // Standard cho scale trung bình
                        }
                        val (preBitmap, _) = preprocessImage(bitmap, scale, enhanceMode)
                        preprocessedBitmap = preBitmap
                        val scaledInputImage = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
                        val result = recognizer.process(scaledInputImage).await()
                        val elements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                        val confidence = if (elements.isEmpty()) 0.0 else elements.sumOf { it.confidence.toDouble() } / elements.size
                        val textLength = result.text.length
                        val fontSizes = result.textBlocks.flatMap { block ->
                            block.lines.flatMap { line ->
                                line.elements.mapNotNull { element ->
                                    element.boundingBox?.height()?.toFloat()?.div(scale)
                                }
                            }
                        }
                        val avgFontSize = if (fontSizes.isNotEmpty()) fontSizes.average().toFloat() else 16f
//                        //log.i(
//                            "TranslationRepository",
//                            "Kết quả quét với scaleFactor=$scale, recognizer=${recognizer.javaClass.simpleName}: " +
//                                    "textLength=$textLength, averageConfidence=$confidence, avgFontSize=$avgFontSize, text=${result.text.take(100)}[...]"
//                        )
                        RecognitionResult(scale, recognizer, result, avgFontSize)
                    } catch (e: Exception) {
                        Log.e("TranslationRepository", "Nhận diện thất bại cho scale $scale và recognizer ${recognizer.javaClass.simpleName}", e)
                        null
                    } finally {
                        preprocessedBitmap?.recycle()
                    }
                }
            }
        }
        val results = deferredResults.awaitAll().filterNotNull()
        if (results.isEmpty()) {
            Log.e("TranslationRepository", "Tất cả nhận diện đều thất bại")
            throw Exception("Không thể nhận diện văn bản trong hình ảnh")
        }
        // Group results by recognizer để dùng cho kiểm tra lỗi Chinese
        val groupedByRecognizer = results.groupBy { it.recognizer }
        // Tìm script mong muốn dựa trên forceScript hoặc đoán từ text
        val isLatinForBest = forceScript == "en" || forceScript == "es"
        val scriptPattern = when (forceScript) {
            "zh" -> Regex("[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF]") // Chinese
            "ja" -> Regex("[\u3040-\u309F\u30A0-\u30FF]") // Japanese
            "ko" -> Regex("[\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F]") // Korean
            "en", "es" -> Regex("[A-Za-z]") // Latin characters
            else -> Regex("[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F]")
        }
        val cjkPenaltyPattern = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]")
        // Chọn bestResult: ưu tiên confidence, text dài, nhiều block, nhiều ký tự script mong muốn
        val bestResult = results.maxByOrNull { result ->
            val elements = result.textResult.textBlocks.flatMap { b -> b.lines }.flatMap { l -> l.elements }
            val confidence = if (elements.isEmpty()) 0.0 else elements.sumOf { e -> e.confidence.toDouble() } / elements.size
            val length = result.textResult.text.length
            val blockCount = result.textResult.textBlocks.size
            val scriptCharCount = scriptPattern.findAll(result.textResult.text).count()
            // Với Latin mode: trừ điểm nếu kết quả chứa nhiều CJK
            val cjkPenalty = if (isLatinForBest) {
                val cjkCount = cjkPenaltyPattern.findAll(result.textResult.text).count()
                cjkCount * 0.5 // Mỗi ký tự CJK bị trừ 0.5 điểm
            } else 0.0
            // Ưu tiên: confidence * 2 + length/100 + blockCount*0.5 + scriptCharCount*0.2 - cjkPenalty
            (confidence * 2.0) + (length / 100.0) + (blockCount * 0.5) + (scriptCharCount * 0.2) - cjkPenalty
        }
        if (bestResult == null) {
            Log.e("TranslationRepository", "Không tìm thấy kết quả tốt nhất")
            throw Exception("Không có kết quả nhận diện văn bản")
        }
        val bestScaleFactor = bestResult.scale
        val bestTextResult = bestResult.textResult
        val bestAvgFontSize = bestResult.avgFontSize
        //log.i("TranslationRepository", "Chọn scaleFactor tốt nhất: $bestScaleFactor với recognizer ${bestResult.recognizer.javaClass.simpleName}, avgFontSize=$bestAvgFontSize")

        // Additional validation: Check for common errors in Chinese text
        val bestText = bestTextResult.text
        val hasCommonErrors = bestText.contains("地") && !bestText.contains("她") // "地" often mistaken for "她"
        if (hasCommonErrors) {
            Log.w("TranslationRepository", "Phát hiện lỗi ngữ pháp trong kết quả tốt nhất: $bestText")
            val alternativeResult = groupedByRecognizer.values.flatten()
                .filter { result -> result != bestResult && result.textResult.text.contains("她") }
                .maxByOrNull { result ->
                    val elements = result.textResult.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                    val averageConfidence = if (elements.isEmpty()) 0.0 else elements.sumOf { e -> e.confidence.toDouble() } / elements.size
                    averageConfidence
                }
            if (alternativeResult != null) {
                //log.i("TranslationRepository", "Chuyển sang kết quả thay thế với scaleFactor=${alternativeResult.scale}")
                val alternativeTextResult = alternativeResult.textResult
                val alternativeAvgFontSize = alternativeResult.avgFontSize
                val alternativeScaleFactor = alternativeResult.scale
                //log.i("TranslationRepository", "Kết quả thay thế: scaleFactor=$alternativeScaleFactor, avgFontSize=$alternativeAvgFontSize")

                val cjkPattern = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]") 
                val isLatinModeAlt = forceScript == "en" || forceScript == "es"
                val textBlocks = alternativeTextResult.textBlocks.flatMap { block ->
                    // Kiểm tra block cha có chứa CJK không - nếu có thì ưu tiên giữ tất cả lines
                    val blockHasCJK = cjkPattern.containsMatchIn(block.text)
                    
                    // Nếu đang quét Latin mà block chủ yếu là CJK -> bỏ qua toàn bộ block
                    if (isLatinModeAlt && blockHasCJK) {
                        val totalChars = block.text.count { !it.isWhitespace() }
                        val cjkChars = cjkPattern.findAll(block.text).count()
                        if (totalChars > 0 && cjkChars.toFloat() / totalChars > 0.5f) {
                            return@flatMap emptyList<TextBlockInfo>()
                        }
                    }
                    
                    block.lines.mapNotNull { line ->
                        val bounds = line.boundingBox ?: Rect()
                        val scaledBounds = Rect(
                            (bounds.left / alternativeScaleFactor).toInt(),
                            (bounds.top / alternativeScaleFactor).toInt(),
                            (bounds.right / alternativeScaleFactor).toInt(),
                            (bounds.bottom / alternativeScaleFactor).toInt()
                        )
                        
                        // Calculate average confidence for this line
                        val elements = line.elements
                        val lineConfidence = if (elements.isNotEmpty()) {
                            elements.sumOf { it.confidence.toDouble() }.toFloat() / elements.size
                        } else {
                            0f
                        }
                        
                        val fontSizes = elements.mapNotNull { element ->
                            element.boundingBox?.height()?.toFloat()?.div(alternativeScaleFactor)
                        }
                        val fontSize = if (fontSizes.isNotEmpty()) {
                            fontSizes.sorted()[fontSizes.size / 2].coerceAtMost(alternativeAvgFontSize * 1.2f)
                        } else {
                            alternativeAvgFontSize
                        }
                        // Áp dụng post-processing để sửa lỗi OCR (ví dụ: し -> L cho Latin script)
                        val processedText = postProcessOCRText(line.text, forceScript)
                        
                        // Context-aware noise filtering:
                        // - Lines chứa CJK: luôn giữ (text hợp lệ trong manga)
                        // - Lines trong block CJK nhưng không chứa CJK: chỉ lọc nếu rõ ràng là noise
                        // - Lines không liên quan CJK: áp dụng bộ lọc noise đầy đủ
                        val lineHasCJK = cjkPattern.containsMatchIn(processedText)
                        val cjkSingleNoise = setOf("ー", "丨", "丶")
                        val shouldFilter = when {
                            processedText.isBlank() -> true
                            lineHasCJK -> processedText.trim() in cjkSingleNoise && scaledBounds.width() * scaledBounds.height() < MIN_BLOCK_AREA
                            blockHasCJK -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                            else -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                        }
                        if (shouldFilter) {
                            null
                        } else {
                            val wordCount = processedText.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                            // Phân tích màu nền và màu text
                            val colorRes = analyzeColorsAndBorder(bitmap, scaledBounds)
                            // DISABLED: Container classification (OpenCV compatibility issue)
                            val containerInfo = detectContainerInfo(bitmap, scaledBounds, bitmap.width, bitmap.height)
                            TextBlockInfo(
                                text = processedText,
                                originalText = processedText,
                                bounds = scaledBounds,
                                fontSize = fontSize,
                                originalFontSize = fontSize,
                                wordCountsPerLine = listOf(wordCount),
                                originalImageWidth = bitmap.width,
                                originalImageHeight = bitmap.height,
                                backgroundType = colorRes.backgroundType,
                                averageBackgroundColor = colorRes.backgroundColor,
                                originalTextColor = colorRes.textColor,
                                customBorderColor = colorRes.borderColor,
                                borderThickness = colorRes.borderThickness,
                                containerInfo = containerInfo
                            )
                        }
                    }
                }

                val clusters = groupBlocksIntoClusters(textBlocks)
                val normalizedTextBlocks = clusters.flatMap { cluster ->
                    val fontSizes = cluster.map { it.fontSize }
                    if (fontSizes.isNotEmpty()) {
                        val medianFontSize = fontSizes.sorted()[fontSizes.size / 2]
                        cluster.map { block ->
                            if (kotlin.math.abs(block.fontSize - medianFontSize) > medianFontSize * 0.3f) {
                                block.copy(fontSize = medianFontSize)
                            } else {
                                block
                            }
                        }
                    } else {
                        cluster
                    }
                }

                // Nếu skipSort, trả về raw lines không merge (dùng cho rotation strategy)
                if (skipSort) {
                    val fullText = normalizedTextBlocks.joinToString("\n") { it.text }
                    return@withContext fullText to normalizedTextBlocks
                }

                val isVertical = determineTextOrientation(normalizedTextBlocks, alternativeTextResult.text)
                val processedTextBlocks = if (isVertical) {
                    sortVerticalTextBlocks(normalizedTextBlocks, bitmap)
                } else {
                    sortHorizontalTextBlocks(normalizedTextBlocks, bitmap)
                }

                processedTextBlocks.forEachIndexed { index, block ->
                    try {
                        val origColorHex = block.originalTextColor?.let { String.format("#%08X", it) } ?: "null"
                        val avgBgHex = block.averageBackgroundColor?.let { String.format("#%08X", it) } ?: "null"
                        Log.i("TranslationRepository", "[OCR] Block #$index: text='${block.text.take(40)}', bounds=${block.bounds.left},${block.bounds.top},${block.bounds.right},${block.bounds.bottom}, fontSize=${block.fontSize}, originalFontSize=${block.originalFontSize}, originalColor=$origColorHex, avgBg=$avgBgHex")
                    } catch (_: Exception) { }
                }

                val fullText = processedTextBlocks.joinToString("\n") { it.text }
                //log.i("TranslationRepository", "Hướng văn bản: ${if (isVertical) "Dọc" else "Ngang"}, Toàn bộ văn bản: $fullText")
                return@withContext fullText to processedTextBlocks
            }
        }

        // Process text blocks with font size normalization and noise filtering
        val cjkPatternMain = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]")
        val isLatinMode = forceScript == "en" || forceScript == "es"
        val textBlocks = bestTextResult.textBlocks.flatMap { block ->
            // Kiểm tra block cha có chứa CJK không - nếu có thì ưu tiên giữ tất cả lines
            val blockHasCJK = cjkPatternMain.containsMatchIn(block.text)
            
            // Nếu đang quét Latin mà block chủ yếu là CJK -> bỏ qua toàn bộ block
            if (isLatinMode && blockHasCJK) {
                val totalChars = block.text.count { !it.isWhitespace() }
                val cjkChars = cjkPatternMain.findAll(block.text).count()
                // Nếu >50% ký tự là CJK -> skip block này khi đang ở Latin mode
                if (totalChars > 0 && cjkChars.toFloat() / totalChars > 0.5f) {
                    return@flatMap emptyList<TextBlockInfo>()
                }
            }
            
            block.lines.mapNotNull { line ->
                val bounds = line.boundingBox ?: Rect()
                val scaledBounds = Rect(
                    (bounds.left / bestScaleFactor).toInt(),
                    (bounds.top / bestScaleFactor).toInt(),
                    (bounds.right / bestScaleFactor).toInt(),
                    (bounds.bottom / bestScaleFactor).toInt()
                )
                
                // Calculate average confidence for this line
                val elements = line.elements
                val lineConfidence = if (elements.isNotEmpty()) {
                    elements.sumOf { it.confidence.toDouble() }.toFloat() / elements.size
                } else {
                    0f
                }
                
                val fontSizes = elements.mapNotNull { it.boundingBox?.height()?.toFloat()?.div(bestScaleFactor) }
                val fontSize = if (fontSizes.isNotEmpty()) {
                    fontSizes.sorted()[fontSizes.size / 2].coerceAtMost(bestAvgFontSize * 1.2f)
                } else {
                    bestAvgFontSize
                }
                
                // Áp dụng post-processing để sửa lỗi OCR (ví dụ: し -> L cho Latin script)
                val processedText = postProcessOCRText(line.text, forceScript)
                
                // Context-aware noise filtering:
                // - Lines chứa CJK: luôn giữ (text hợp lệ trong manga)
                // - Lines trong block CJK nhưng không chứa CJK: chỉ lọc nếu rõ ràng là noise
                // - Lines không liên quan CJK: áp dụng bộ lọc noise đầy đủ
                val lineHasCJK = cjkPatternMain.containsMatchIn(processedText)
                val cjkSingleNoise = setOf("ー", "丨", "丶")
                val shouldFilter = when {
                    processedText.isBlank() -> true
                    lineHasCJK -> processedText.trim() in cjkSingleNoise && scaledBounds.width() * scaledBounds.height() < MIN_BLOCK_AREA
                    blockHasCJK -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                    else -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                }
                if (shouldFilter) {
                    null // Skip this block
                } else {
                    val wordCount = processedText.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                    // Phân tích màu nền và màu text
                    val colorRes = analyzeColorsAndBorder(bitmap, scaledBounds)
                    // DISABLED: Container classification
                    val containerInfo = detectContainerInfo(bitmap, scaledBounds, bitmap.width, bitmap.height)
                    TextBlockInfo(
                        text = processedText,
                        originalText = processedText,
                        bounds = scaledBounds,
                        fontSize = fontSize,
                        wordCountsPerLine = listOf(wordCount),
                        originalImageWidth = bitmap.width,
                        originalImageHeight = bitmap.height,
                        backgroundType = colorRes.backgroundType,
                        averageBackgroundColor = colorRes.backgroundColor,
                        originalTextColor = colorRes.textColor,
                        customBorderColor = colorRes.borderColor,
                        borderThickness = colorRes.borderThickness,
                        containerInfo = containerInfo
                    )
                }
            }
        }

        // === MULTI-SCALE FUSION ===
        // Thu thập text blocks bổ sung từ các scale khác
        // Text nhỏ hoặc trên nền phức tạp có thể chỉ được phát hiện ở scale khác
        // Cho phép cross-fusion giữa Japanese và Chinese recognizer (chia sẻ Kanji)
        // Nhưng KHÔNG merge từ Korean/Latin recognizer khi quét CJK (tránh garbage)
        val mergedTextBlocks = textBlocks.toMutableList()
        val cjkRecognizers = setOf(japaneseRecognizer, chineseRecognizer)
        val otherResults = if (onlyPreview) emptyList() else results.filter { result ->
            result != bestResult && (
                result.recognizer == bestResult.recognizer ||
                // Cho phép cross-fusion giữa Japanese và Chinese recognizer
                (result.recognizer in cjkRecognizers && bestResult.recognizer in cjkRecognizers)
            )
        }

        for (otherResult in otherResults) {
            val otherScaleFactor = otherResult.scale
            val otherTextResult = otherResult.textResult
            val otherAvgFontSize = otherResult.avgFontSize

            val otherBlocks = otherTextResult.textBlocks.flatMap { block ->
                val blockHasCJK = cjkPatternMain.containsMatchIn(block.text)

                if (isLatinMode && blockHasCJK) {
                    val totalChars = block.text.count { !it.isWhitespace() }
                    val cjkChars = cjkPatternMain.findAll(block.text).count()
                    if (totalChars > 0 && cjkChars.toFloat() / totalChars > 0.5f) {
                        return@flatMap emptyList<TextBlockInfo>()
                    }
                }

                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox ?: Rect()
                    val scaledBounds = Rect(
                        (bounds.left / otherScaleFactor).toInt(),
                        (bounds.top / otherScaleFactor).toInt(),
                        (bounds.right / otherScaleFactor).toInt(),
                        (bounds.bottom / otherScaleFactor).toInt()
                    )

                    val elements = line.elements
                    val lineConfidence = if (elements.isNotEmpty()) {
                        elements.sumOf { it.confidence.toDouble() }.toFloat() / elements.size
                    } else { 0f }

                    val fontSizes = elements.mapNotNull { it.boundingBox?.height()?.toFloat()?.div(otherScaleFactor) }
                    val fontSize = if (fontSizes.isNotEmpty()) {
                        fontSizes.sorted()[fontSizes.size / 2].coerceAtMost(otherAvgFontSize * 1.2f)
                    } else { otherAvgFontSize }

                    val processedText = postProcessOCRText(line.text, forceScript)

                    val lineHasCJK = cjkPatternMain.containsMatchIn(processedText)
                    val cjkSingleNoise = setOf("ー", "丨", "丶")
                    val shouldFilter = when {
                        processedText.isBlank() -> true
                        lineHasCJK -> processedText.trim() in cjkSingleNoise && scaledBounds.width() * scaledBounds.height() < MIN_BLOCK_AREA
                        blockHasCJK -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                        else -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                    }

                    if (shouldFilter) null
                    else {
                        val wordCount = processedText.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        val colorRes = analyzeColorsAndBorder(bitmap, scaledBounds)
                        // Phân loại container type (TẠM THỜI TẮT)
                        val containerInfo = detectContainerInfo(bitmap, scaledBounds, bitmap.width, bitmap.height)
                        TextBlockInfo(
                            text = processedText,
                            originalText = processedText,
                            bounds = scaledBounds,
                            fontSize = fontSize,
                            wordCountsPerLine = listOf(wordCount),
                            originalImageWidth = bitmap.width,
                            originalImageHeight = bitmap.height,
                            backgroundType = colorRes.backgroundType,
                            averageBackgroundColor = colorRes.backgroundColor,
                            originalTextColor = colorRes.textColor,
                            customBorderColor = colorRes.borderColor,
                            borderThickness = colorRes.borderThickness,
                            containerInfo = containerInfo
                        )
                    }
                }
            }

            // Thêm blocks mới không overlap đáng kể với blocks hiện có
            for (newBlock in otherBlocks) {
                val hasSignificantOverlap = mergedTextBlocks.any { existing ->
                    val overlapLeft = maxOf(existing.bounds.left, newBlock.bounds.left)
                    val overlapTop = maxOf(existing.bounds.top, newBlock.bounds.top)
                    val overlapRight = minOf(existing.bounds.right, newBlock.bounds.right)
                    val overlapBottom = minOf(existing.bounds.bottom, newBlock.bounds.bottom)
                    val overlapArea = maxOf(0, overlapRight - overlapLeft) * maxOf(0, overlapBottom - overlapTop)
                    val newBlockArea = newBlock.bounds.width() * newBlock.bounds.height()
                    val existingArea = existing.bounds.width() * existing.bounds.height()
                    val smallerArea = minOf(newBlockArea, existingArea).coerceAtLeast(1)
                    overlapArea.toFloat() / smallerArea > 0.4f
                }
                if (!hasSignificantOverlap) {
                    mergedTextBlocks.add(newBlock)
                    Log.i("TranslationRepository", "[MULTI-SCALE] Added block from scale=${otherResult.scale}: '${newBlock.text}'")
                }
            }
        }

        if (mergedTextBlocks.size > textBlocks.size) {
            Log.i("TranslationRepository", "[MULTI-SCALE] Total blocks: ${textBlocks.size} (best) + ${mergedTextBlocks.size - textBlocks.size} (other scales) = ${mergedTextBlocks.size}")
        }

        // === RAW BITMAP SCAN ===
        // Quét thêm trên ảnh gốc (không grayscale/contrast) để bắt text mà preprocessing phá hủy
        // VD: text trên nền phức tạp, text màu nhạt, SFX manga, text trên nền tối
        if (!onlyPreview) {
            val rawBlocksBefore = mergedTextBlocks.size
            // Dùng lại danh sách recognizers đã xác định ở trên (có đúng type TextRecognizer)
            for (rawRecognizer in recognizers) {
                try {
                    val rawInputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
                    val rawResult = rawRecognizer.process(rawInputImage).await()

                    for (rawBlock in rawResult.textBlocks) {
                        val rawBlockHasCJK = cjkPatternMain.containsMatchIn(rawBlock.text)
                        if (isLatinMode && rawBlockHasCJK) {
                            val totalCharsRaw = rawBlock.text.count { c -> !c.isWhitespace() }
                            val cjkCharsRaw = cjkPatternMain.findAll(rawBlock.text).count()
                            if (totalCharsRaw > 0 && cjkCharsRaw.toFloat() / totalCharsRaw > 0.5f) continue
                        }

                        for (rawLine in rawBlock.lines) {
                            val rawBounds = rawLine.boundingBox ?: continue
                            val rawElements = rawLine.elements
                            val rawLineConfidence = if (rawElements.isNotEmpty()) {
                                rawElements.sumOf { el -> el.confidence.toDouble() }.toFloat() / rawElements.size
                            } else { 0f }

                            val rawFontSizes = rawElements.mapNotNull { el -> el.boundingBox?.height()?.toFloat() }
                            val rawFontSize = if (rawFontSizes.isNotEmpty()) {
                                rawFontSizes.sorted()[rawFontSizes.size / 2]
                            } else { bestAvgFontSize }

                            val rawProcessedText = postProcessOCRText(rawLine.text, forceScript)
                            val rawLineHasCJK = cjkPatternMain.containsMatchIn(rawProcessedText)
                            val rawCjkSingleNoise = setOf("ー", "丨", "丶")
                            val rawShouldFilter = when {
                                rawProcessedText.isBlank() -> true
                                rawLineHasCJK -> rawProcessedText.trim() in rawCjkSingleNoise && rawBounds.width() * rawBounds.height() < MIN_BLOCK_AREA
                                rawBlockHasCJK -> isNoiseBlock(rawProcessedText, rawBounds, rawLineConfidence)
                                else -> isNoiseBlock(rawProcessedText, rawBounds, rawLineConfidence)
                            }
                            if (rawShouldFilter) continue

                            // Kiểm tra overlap với blocks hiện có
                            val rawHasOverlap = mergedTextBlocks.any { existing ->
                                val oLeft = maxOf(existing.bounds.left, rawBounds.left)
                                val oTop = maxOf(existing.bounds.top, rawBounds.top)
                                val oRight = minOf(existing.bounds.right, rawBounds.right)
                                val oBottom = minOf(existing.bounds.bottom, rawBounds.bottom)
                                val oArea = maxOf(0, oRight - oLeft) * maxOf(0, oBottom - oTop)
                                val smaller = minOf(
                                    rawBounds.width() * rawBounds.height(),
                                    existing.bounds.width() * existing.bounds.height()
                                ).coerceAtLeast(1)
                                oArea.toFloat() / smaller > 0.4f
                            }
                            if (!rawHasOverlap) {
                                val rawWordCount = rawProcessedText.split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.size
                                val colorRes = analyzeColorsAndBorder(bitmap, rawBounds)
                                // DISABLED: Container classification
                                val rawContainerInfo = detectContainerInfo(bitmap, rawBounds, bitmap.width, bitmap.height)
                                mergedTextBlocks.add(TextBlockInfo(
                                    text = rawProcessedText,
                                    originalText = rawProcessedText,
                                    bounds = rawBounds,
                                    fontSize = rawFontSize,
                                    originalFontSize = rawFontSize,
                                    wordCountsPerLine = listOf(rawWordCount),
                                    originalImageWidth = bitmap.width,
                                    originalImageHeight = bitmap.height,
                                    backgroundType = colorRes.backgroundType,
                                    averageBackgroundColor = colorRes.backgroundColor,
                                    originalTextColor = colorRes.textColor,
                                    customBorderColor = colorRes.borderColor,
                                    borderThickness = colorRes.borderThickness,
                                    containerInfo = rawContainerInfo
                                ))
                                Log.i("TranslationRepository", "[RAW-SCAN] Added block: '${rawProcessedText}' bounds=$rawBounds")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "[RAW-SCAN] Failed: ${e.message}")
                }
            }
            if (mergedTextBlocks.size > rawBlocksBefore) {
                Log.i("TranslationRepository", "[RAW-SCAN] Added ${mergedTextBlocks.size - rawBlocksBefore} new blocks from raw bitmap scan")
            }
        }

        // Check for font size consistency within clusters
        val clusters = groupBlocksIntoClusters(mergedTextBlocks)
        val normalizedTextBlocks = clusters.flatMap { cluster ->
            val fontSizes = cluster.map { it.fontSize }
            if (fontSizes.isNotEmpty()) {
                val medianFontSize = fontSizes.sorted()[fontSizes.size / 2]
                cluster.map { block ->
                    if (abs(block.fontSize - medianFontSize) > medianFontSize * 0.3f) {
                        block.copy(fontSize = medianFontSize)
                    } else {
                        block
                    }
                }
            } else {
                cluster
            }
        }

        // Nếu skipSort, trả về raw lines không merge (dùng cho rotation strategy)
        if (skipSort) {
            val fullText = normalizedTextBlocks.joinToString("\n") { it.text }
            return@withContext fullText to normalizedTextBlocks
        }

        val isVertical = determineTextOrientation(normalizedTextBlocks, bestTextResult.text)
        val processedTextBlocks = if (isVertical) {
            sortVerticalTextBlocks(normalizedTextBlocks, bitmap)
        } else {
            sortHorizontalTextBlocks(normalizedTextBlocks, bitmap)
        }

        processedTextBlocks.forEachIndexed { index, block ->
            //log.i("TranslationRepository", "Khối #$index: text=${block.text}, left=${block.bounds.left}, top=${block.bounds.top}, bottom=${block.bounds.bottom}, fontSize=${block.fontSize}")
        }

        val fullText = processedTextBlocks.joinToString("\n") { it.text }
        //log.i("TranslationRepository", "Hướng văn bản: ${if (isVertical) "Dọc" else "Ngang"}, Toàn bộ văn bản: $fullText")
        fullText to processedTextBlocks
    }

    // Nhóm các text block thành các khung thoại (bubble) dựa trên vị trí và khoảng cách, kiểm tra overlap dọc đủ lớn và không ghép nếu lệch trục quá xa
}
