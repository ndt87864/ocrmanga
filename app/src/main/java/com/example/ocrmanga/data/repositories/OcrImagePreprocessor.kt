package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.example.ocrmanga.utils.AppLogger

object OcrImagePreprocessor {
    private const val TAG = "OcrImagePreprocessor"

    fun preprocessImage(
        application: Application,
        preprocessor: com.example.ocrmanga.data.ocr.AdvancedPreprocessor,
        bitmap: Bitmap,
        scaleFactor: Float,
        enhanceMode: Int
    ): Pair<Bitmap, Float> {
        if (bitmap.isRecycled) {
            throw IllegalArgumentException("Source bitmap is already recycled")
        }

        val newWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(32)
        val newHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(32)

        val upscaledBitmap: Bitmap = if (newWidth == bitmap.width && newHeight == bitmap.height) {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalArgumentException("Failed to copy bitmap - source may be recycled")
        } else {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }

        if (enhanceMode == 3) {
            val opencvResult = preprocessor.preprocess(upscaledBitmap)
            if (upscaledBitmap !== bitmap) upscaledBitmap.recycle()
            return Pair(opencvResult.bitmap, scaleFactor)
        }

        val grayscaleBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        val colorFilter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = colorFilter
        canvas.drawBitmap(upscaledBitmap, 0f, 0f, paint)

        if (upscaledBitmap !== bitmap) {
            upscaledBitmap.recycle()
        }

        val contrastBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val contrastCanvas = Canvas(contrastBitmap)
        val contrastPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val contrastMatrix = when (enhanceMode) {
            1 -> {
                ColorMatrix().apply {
                    set(floatArrayOf(
                        1.8f, 0f, 0f, 0f, -60f,
                        0f, 1.8f, 0f, 0f, -60f,
                        0f, 0f, 1.8f, 0f, -60f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
            }
            2 -> {
                ColorMatrix().apply {
                    set(floatArrayOf(
                        1.3f, 0f, 0f, 0f, -30f,
                        0f, 1.3f, 0f, 0f, -30f,
                        0f, 0f, 1.3f, 0f, -30f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
            }
            else -> {
                ColorMatrix().apply {
                    set(floatArrayOf(
                        1.5f, 0f, 0f, 0f, -50f,
                        0f, 1.5f, 0f, 0f, -50f,
                        0f, 0f, 1.5f, 0f, -50f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
            }
        }

        val contrastFilter = ColorMatrixColorFilter(contrastMatrix)
        contrastPaint.colorFilter = contrastFilter
        contrastCanvas.drawBitmap(grayscaleBitmap, 0f, 0f, contrastPaint)
        grayscaleBitmap.recycle()

        return Pair(contrastBitmap, scaleFactor)
    }
}
