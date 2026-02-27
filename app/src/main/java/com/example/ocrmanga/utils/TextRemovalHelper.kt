package com.example.ocrmanga.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.ml.LamaInpainter
import com.example.ocrmanga.utils.AppLogger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Utility class to remove text from images using LaMa TFLite inpainting.
 */
object TextRemovalHelper {

    private const val TAG = "TextRemovalHelper"

    /**
     * Initialize the inpainting engine (call once at app startup).
     * Kept as initializePython() for backward compatibility with OCRMangaApplication.
     */
    fun initializePython(context: Context) {
        LamaInpainter.initialize(context.applicationContext)
        Log.d(TAG, "LamaInpainter initialized")
    }

    /**
     * Remove text from image using OCR-detected text blocks.
     *
     * @param context App context
     * @param imageUri Uri of the source image
     * @param blocks List of TextBlockInfo with text region coordinates
     * @return Uri of the inpainted image, or null on failure
     */
    suspend fun removeTextFromImage(
        context: Context,
        imageUri: Uri,
        blocks: List<TextBlockInfo>
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            LamaInpainter.initialize(context.applicationContext)

            val bitmap = decodeBitmapFromUri(context, imageUri)
            if (bitmap == null) {
                Log.e(TAG, "Cannot decode bitmap from Uri: $imageUri")
                return@withContext null
            }

            val rects = blocks.map { it.bounds }
            Log.d(TAG, "Removing text: ${rects.size} blocks, image ${bitmap.width}x${bitmap.height}")

            val resultBitmap = LamaInpainter.inpaintBlocks(bitmap, rects)
            bitmap.recycle()

            if (resultBitmap == null) {
                Log.e(TAG, "Inpainting returned null")
                return@withContext null
            }

            val outputFile = File(context.cacheDir, "inpainted_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            resultBitmap.recycle()

            Log.d(TAG, "Text removal complete: ${outputFile.absolutePath}")
            return@withContext Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing text from image", e)
            return@withContext null
        }
    }

    /**
     * Remove text from image using a brush-drawn mask.
     *
     * @param context App context
     * @param imageUri Uri of the source image
     * @param maskBitmap Mask bitmap (white/non-black pixels = areas to inpaint)
     * @return Uri of the inpainted image, or null on failure
     */
    suspend fun removeTextWithMask(
        context: Context,
        imageUri: Uri,
        maskBitmap: Bitmap
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            LamaInpainter.initialize(context.applicationContext)

            val bitmap = decodeBitmapFromUri(context, imageUri)
            if (bitmap == null) {
                Log.e(TAG, "Cannot decode bitmap from Uri: $imageUri")
                return@withContext null
            }

            Log.d(TAG, "Mask inpainting: image ${bitmap.width}x${bitmap.height}, mask ${maskBitmap.width}x${maskBitmap.height}")

            val resultBitmap = LamaInpainter.inpaintWithMask(bitmap, maskBitmap)
            bitmap.recycle()

            if (resultBitmap == null) {
                Log.e(TAG, "Mask inpainting returned null")
                return@withContext null
            }

            val outputFile = File(context.cacheDir, "inpainted_mask_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            resultBitmap.recycle()

            Log.d(TAG, "Mask text removal complete: ${outputFile.absolutePath}")
            return@withContext Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing text with mask", e)
            return@withContext null
        }
    }

    /**
     * Decode a Bitmap directly from a content Uri.
     */
    private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap from Uri", e)
            null
        }
    }
}
