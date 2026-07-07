package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.ocr.models.TextContainerInfo
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

open class TranslationRepositoryOcrOrientation(application: Application) : TranslationRepositoryAiFallback(application) {

    protected fun preprocessImage(bitmap: Bitmap, scaleFactor: Float, enhanceMode: Int = 0): Pair<Bitmap, Float> {
        return OcrImagePreprocessor.preprocessImage(application, preprocessor, bitmap, scaleFactor, enhanceMode)
    }
    protected fun detectContainerInfo(bitmap: Bitmap?, bounds: Rect, imageWidth: Int, imageHeight: Int): TextContainerInfo? {
        if (bitmap == null || bitmap.isRecycled) return null
        return try {
            val result = bubbleDetector.detectBubble(bitmap, bounds, imageWidth, imageHeight)
            result.containerInfo?.takeIf { it.confidence >= 0.3f }
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Error detecting container", e)
            null
        }
    }

    enum class TextOrientation {
        HORIZONTAL,      // Văn bản ngang (trái sang phải)
        VERTICAL_RTL,    // Văn bản dọc (phải sang trái) - Kiểu manga Nhật
        VERTICAL_LTR     // Văn bản dọc (trái sang phải) - Kiểu Trung Quốc truyền thống
    }

    protected fun rotateImageForVerticalText(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }


    protected fun transformBoundsAfterRotation(
        bounds: Rect,
        originalWidth: Int,
        originalHeight: Int,
        rotationApplied: Int
    ): Rect {
        return when (rotationApplied) {
            90 -> {
                Rect(
                    bounds.top,                          // newLeft = top' 
                    originalHeight - bounds.right,       // newTop = H - right'
                    bounds.bottom,                       // newRight = bottom'
                    originalHeight - bounds.left         // newBottom = H - left'
                )
            }
            -90 -> {
                Rect(
                    originalWidth - bounds.bottom,       // newLeft = W - bottom'
                    bounds.left,                         // newTop = left'
                    originalWidth - bounds.top,          // newRight = W - top'
                    bounds.right                         // newBottom = right'
                )
            }
            else -> bounds
        }
    }
    protected fun transformBlocksAfterRotation(
        blocks: List<TextBlockInfo>,
        originalWidth: Int,
        originalHeight: Int,
        rotationApplied: Int
    ): List<TextBlockInfo> {
        return blocks.map { block ->
            val newBounds = transformBoundsAfterRotation(
                block.bounds,
                originalWidth,
                originalHeight,
                rotationApplied
            )
            block.copy(
                bounds = newBounds,
                originalImageWidth = originalWidth,
                originalImageHeight = originalHeight
            )
        }
    }

    protected fun detectTextOrientationAdvanced(bitmap: Bitmap): TextOrientation {
        // Thử quét nhanh với Japanese recognizer để detect orientation
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            // Sử dụng coroutine blocking vì đây là hàm private helper
            val textResult = kotlinx.coroutines.runBlocking {
                japaneseRecognizer.process(inputImage).await()
            }
            
            if (textResult.textBlocks.isEmpty()) return TextOrientation.HORIZONTAL
            
            // Tính aspect ratio của các text blocks
            var verticalBlockCount = 0
            var horizontalBlockCount = 0
            var totalBlocks = 0
            
            for (block in textResult.textBlocks) {
                val bounds = block.boundingBox ?: continue
                val width = bounds.width().toFloat()
                val height = bounds.height().toFloat()
                if (width <= 0 || height <= 0) continue
                
                totalBlocks++
                val aspectRatio = height / width
                
                if (aspectRatio > 1.8f) {
                    // Block cao hơn rộng nhiều => có thể là vertical text
                    verticalBlockCount++
                } else if (aspectRatio < 0.6f) {
                    // Block rộng hơn cao nhiều => horizontal text
                    horizontalBlockCount++
                }
            }
            
            // Nếu đa số blocks là vertical => văn bản dọc
            if (totalBlocks > 0 && verticalBlockCount > horizontalBlockCount && 
                verticalBlockCount >= totalBlocks * 0.4) {
                // Kiểm tra layout từ phải sang trái (đặc trưng manga Nhật)
                val sortedByRight = textResult.textBlocks
                    .mapNotNull { it.boundingBox }
                    .sortedByDescending { it.right }
                
                // Nếu blocks được sắp xếp từ phải sang trái => RTL
                return TextOrientation.VERTICAL_RTL
            }
            
        } catch (e: Exception) {
            Log.w("TranslationRepository", "Không thể detect text orientation: ${e.message}")
        }
        
        return TextOrientation.HORIZONTAL
    }

}
