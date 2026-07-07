package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.utils.AppLogger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class TranslationRepositoryOcrRotation(application: Application) : TranslationRepositoryOcrRecognition(application) {

    protected suspend fun recognizeTextWithRotationStrategy(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onlyPreview: Boolean = false,
        forceScript: String? = null
    ): Pair<String, List<TextBlockInfo>> = withContext(Dispatchers.IO) {
        // Detect orientation trước
        val detectedOrientation = detectTextOrientationAdvanced(bitmap)
        
        Log.i("TranslationRepository", "[ROTATION-STRATEGY] Detected orientation: $detectedOrientation")
        
        // Nếu là horizontal, quét bình thường
        if (detectedOrientation == TextOrientation.HORIZONTAL) {
            return@withContext recognizeText(bitmap, rotationDegrees, onlyPreview, forceScript)
        }
        
        // Nếu là vertical text, thử quét cả ảnh gốc và ảnh xoay
        // Sử dụng skipSort=true để lấy raw lines, sau đó merge với thứ tự RTL đúng
        val results = mutableListOf<Triple<String, List<TextBlockInfo>, Double>>()
        
        // 1. Quét ảnh gốc (skipSort để lấy raw lines)
        try {
            val (text, blocks) = recognizeText(bitmap, rotationDegrees, onlyPreview, forceScript, skipSort = true)
            val score = calculateOcrScore(text, blocks)
            results.add(Triple(text, blocks, score))
            Log.i("TranslationRepository", "[ROTATION-STRATEGY] Original: text length=${text.length}, blocks=${blocks.size}, score=$score")
        } catch (e: Exception) {
            Log.w("TranslationRepository", "[ROTATION-STRATEGY] Original scan failed: ${e.message}")
        }
        
        // 2. Quét ảnh xoay 90° CW (chuyển vertical thành horizontal)
        var rotated90: Bitmap? = null
        try {
            rotated90 = rotateImageForVerticalText(bitmap, 90)
            val (text90, blocks90) = recognizeText(rotated90, 0, onlyPreview, forceScript, skipSort = true)
            
            // Transform bounds về tọa độ gốc (đã xoay 90° CW)
            val transformedBlocks = transformBlocksAfterRotation(
                blocks90,
                bitmap.width,
                bitmap.height,
                90  // Góc đã xoay là 90° CW
            )
            
            val score = calculateOcrScore(text90, transformedBlocks)
            results.add(Triple(text90, transformedBlocks, score))
            Log.i("TranslationRepository", "[ROTATION-STRATEGY] Rotated 90°: text length=${text90.length}, blocks=${transformedBlocks.size}, score=$score")
        } catch (e: Exception) {
            Log.w("TranslationRepository", "[ROTATION-STRATEGY] Rotated 90° scan failed: ${e.message}")
        } finally {
            rotated90?.recycle()
        }
        
        // 3. Quét ảnh xoay -90° CCW (cho trường hợp đặc biệt)
        var rotatedMinus90: Bitmap? = null
        try {
            rotatedMinus90 = rotateImageForVerticalText(bitmap, -90)
            val (textMinus90, blocksMinus90) = recognizeText(rotatedMinus90, 0, onlyPreview, forceScript, skipSort = true)
            
            // Transform bounds về tọa độ gốc (đã xoay -90° CCW)
            val transformedBlocks = transformBlocksAfterRotation(
                blocksMinus90,
                bitmap.width,
                bitmap.height,
                -90  // Góc đã xoay là -90° CCW
            )
            
            val score = calculateOcrScore(textMinus90, transformedBlocks)
            results.add(Triple(textMinus90, transformedBlocks, score))
            Log.i("TranslationRepository", "[ROTATION-STRATEGY] Rotated -90°: text length=${textMinus90.length}, blocks=${transformedBlocks.size}, score=$score")
        } catch (e: Exception) {
            Log.w("TranslationRepository", "[ROTATION-STRATEGY] Rotated -90° scan failed: ${e.message}")
        } finally {
            rotatedMinus90?.recycle()
        }
        
        // Chọn kết quả tốt nhất dựa trên score
        val bestResult = results.maxByOrNull { it.third }
        
        if (bestResult != null) {
            Log.i("TranslationRepository", "[ROTATION-STRATEGY] Best result: score=${bestResult.third}, text length=${bestResult.first.length}")
            // Áp dụng sortVerticalTextBlocks trên raw lines với tọa độ gốc
            // để đảm bảo thứ tự merge đúng: phải → trái (RTL) cho văn bản dọc CJK
            val rawBlocks = bestResult.second
            val processedBlocks = sortVerticalTextBlocks(rawBlocks, bitmap)
            val sortedText = processedBlocks.joinToString("\n") { it.text }
            return@withContext Pair(sortedText, processedBlocks)
        }
        
        // Fallback: quét bình thường
        return@withContext recognizeText(bitmap, rotationDegrees, onlyPreview, forceScript)
    }

    protected fun calculateOcrScore(text: String, blocks: List<TextBlockInfo>): Double {
        if (text.isEmpty()) return 0.0
        
        // Đếm số ký tự Asian (CJK)
        val asianPattern = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]")
        val asianCharCount = asianPattern.findAll(text).count()
        
        // Đếm số ký tự Latin và số
        val alphaNumPattern = Regex("[a-zA-Z0-9]")
        val alphaNumCount = alphaNumPattern.findAll(text).count()
        
        // Đếm số ký tự là nhiễu
        val noiseChars = text.count { c ->
            c in setOf('|', '/', '\\', '-', '_', '.', ',', '\'', '`', '"', '○', '◯', '・')
        }
        
        // Score = ưu tiên ký tự Asian + độ dài text + số blocks hợp lệ
        val asianScore = asianCharCount * 2.5
        val alphaScore = alphaNumCount * 1.5
        val textLengthScore = text.length / 10.0
        
        // Chỉ tính blocks có nội dung có nghĩa
        val validBlocks = blocks.filter { block ->
            block.text.trim().length >= MIN_TEXT_LENGTH &&
            block.bounds.width() * block.bounds.height() >= MIN_BLOCK_AREA
        }
        val blockScore = validBlocks.size * 5.0
        
        // Penalty nặng cho noise characters
        val noisePenalty = noiseChars * 1.5
        
        // Penalty cho quá nhiều ký tự không hợp lệ
        val invalidChars = text.count { c ->
            !asianPattern.matches(c.toString()) && 
            !alphaNumPattern.matches(c.toString()) && 
            !c.isWhitespace() && 
            c !in ".,!?、。！？「」『』（）()\"'"
        }
        val invalidPenalty = invalidChars * 0.3
        
        // Bonus for text with good CJK density (typical for manga)
        val cjkDensity = if (text.isNotEmpty()) asianCharCount.toDouble() / text.length else 0.0
        val densityBonus = if (cjkDensity > 0.3) 10.0 else 0.0
        
        return asianScore + alphaScore + textLengthScore + blockScore + densityBonus - noisePenalty - invalidPenalty
    }
}
