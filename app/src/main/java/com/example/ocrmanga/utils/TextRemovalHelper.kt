package com.example.ocrmanga.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.ml.LamaInpainter
import com.example.ocrmanga.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Utility class to remove text from images using LaMa ONNX inpainting.
 */
object TextRemovalHelper {

    private const val TAG = "TextRemovalHelper"

    /**
     * Initialize the inpainting engine (call once at app startup).
     * Kept as initializePython() for backward compatibility with OCRMangaApplication.
     */
    fun initializePython(context: Context) {
        LamaInpainter.initialize(context.applicationContext)
        //Log.d(TAG, "LamaInpainter initialized")
    }

    /**
     * Remove text from image using OCR-detected text blocks.
     *
     * @param context App context
     * @param imageUri Uri of the source image
     * @param blocks List of TextBlockInfo with text region coordinates
     * @param onProgress Optional callback for progress updates (e.g. "Xóa điểm ảnh...")
     * @return Uri of the inpainted image, or null on failure
     */
    suspend fun removeTextFromImage(
        context: Context,
        imageUri: Uri,
        blocks: List<TextBlockInfo>,
        onProgress: ((String) -> Unit)? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke("Khởi động AI...")
            LamaInpainter.initialize(context.applicationContext)

            onProgress?.invoke("Tải ảnh gốc...")
            val bitmap = decodeBitmapFromUri(context, imageUri)
            if (bitmap == null) {
                AppLogger.e(TAG, "Cannot decode bitmap from Uri: $imageUri")
                return@withContext null
            }

            onProgress?.invoke("Phân tích vùng text... (${blocks.size} vùng)")
            val inpaintBlocks = blocks.map {
                LamaInpainter.InpaintBlock(
                    it.bounds, it.shapeType,
                    it.overlayInsetHorizontal, it.overlayInsetVertical
                )
            }

            val resultBitmap = LamaInpainter.inpaintBlocks(bitmap, inpaintBlocks, onProgress)
            bitmap.recycle()

            if (resultBitmap == null) {
                AppLogger.e(TAG, "Inpainting returned null")
                return@withContext null
            }

            onProgress?.invoke("Lưu kết quả...")
            val outputFile = File(context.cacheDir, "inpainted_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            resultBitmap.recycle()

            return@withContext Uri.fromFile(outputFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error removing text from image", e)
            return@withContext null
        }
    }

    /**
     * Remove text from image using a brush-drawn mask.
     *
     * @param context App context
     * @param imageUri Uri of the source image
     * @param maskBitmap Mask bitmap (white/non-black pixels = areas to inpaint)
     * @param onProgress Optional callback for progress updates
     * @return Uri of the inpainted image, or null on failure
     */
    suspend fun removeTextWithMask(
        context: Context,
        imageUri: Uri,
        maskBitmap: Bitmap,
        onProgress: ((String) -> Unit)? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke("Khởi động AI...")
            LamaInpainter.initialize(context.applicationContext)

            onProgress?.invoke("Tải ảnh gốc...")
            val bitmap = decodeBitmapFromUri(context, imageUri)
            if (bitmap == null) {
                AppLogger.e(TAG, "Cannot decode bitmap from Uri: $imageUri")
                return@withContext null
            }

            onProgress?.invoke("Phân tích vùng chọn...")
            val resultBitmap = LamaInpainter.inpaintWithMask(bitmap, maskBitmap, onProgress)
            bitmap.recycle()

            if (resultBitmap == null) {
                AppLogger.e(TAG, "Mask inpainting returned null")
                return@withContext null
            }

            onProgress?.invoke("Lưu kết quả...")
            val outputFile = File(context.cacheDir, "inpainted_mask_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            resultBitmap.recycle()

            return@withContext Uri.fromFile(outputFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error removing text with mask", e)
            return@withContext null
        }
    }

    /**
     * Decode a Bitmap directly from a content Uri.
     * Đảm bảo decode với ARGB_8888 để giữ nguyên màu sắc.
     */
    private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error decoding bitmap from Uri", e)
            null
        }
    }

    /**
     * Create a preview bitmap showing the original image with mask overlay.
     * Highlighted regions (text blocks) are drawn with a semi-transparent red overlay
     * and a red border to clearly indicate which areas will be inpainted.
     *
     * @param context App context
     * @param imageUri Uri of the source image
     * @param blocks List of TextBlockInfo with text region coordinates
     * @return Bitmap with mask overlay, or null on failure
     */
    suspend fun createMaskPreview(
        context: Context,
        imageUri: Uri,
        blocks: List<TextBlockInfo>
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeBitmapFromUri(context, imageUri)
            if (bitmap == null) {
                AppLogger.e(TAG, "Cannot decode bitmap for preview: $imageUri")
                return@withContext null
            }

            val preview = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            bitmap.recycle()
            val canvas = Canvas(preview)
            val padding = 10 // Same as LamaInpainter.MASK_PADDING

            // Semi-transparent red fill for masked regions
            val fillPaint = Paint().apply {
                color = Color.argb(80, 255, 60, 60)
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            // Red border for masked regions
            val strokePaint = Paint().apply {
                color = Color.argb(200, 255, 40, 40)
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
            }

            for (block in blocks) {
                // Use full OCR bounds (NOT the overlay-inset area) to highlight original text
                val ob = block.bounds
                if (ob.width() <= 0 || ob.height() <= 0) continue

                // Expand by 4% to ensure complete text coverage
                val expandH = (ob.width() * 0.04f).toInt().coerceAtLeast(padding)
                val expandV = (ob.height() * 0.04f).toInt().coerceAtLeast(padding)

                val padded = Rect(
                    (ob.left - expandH).coerceAtLeast(0),
                    (ob.top - expandV).coerceAtLeast(0),
                    (ob.right + expandH).coerceAtMost(preview.width),
                    (ob.bottom + expandV).coerceAtMost(preview.height)
                )

                val rectF = RectF(padded)
                if (block.shapeType == 1) {
                    canvas.drawOval(rectF, fillPaint)
                    canvas.drawOval(rectF, strokePaint)
                } else {
                    canvas.drawRect(rectF, fillPaint)
                    canvas.drawRect(rectF, strokePaint)
                }
            }

            //AppLogger.d(TAG, "Mask preview created: ${blocks.size} regions highlighted")
            return@withContext preview
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error creating mask preview", e)
            return@withContext null
        }
    }
}
