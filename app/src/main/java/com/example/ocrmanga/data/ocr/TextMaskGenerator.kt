package com.example.ocrmanga.data.ocr

import android.graphics.*
import android.util.Log
import com.example.ocrmanga.data.ocr.models.MaskConfig
import com.example.ocrmanga.data.ocr.models.MaskedRegion
import com.example.ocrmanga.data.ocr.models.TextRegion
import kotlinx.coroutines.*
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Generates and applies masks for text regions
 */
class TextMaskGenerator(
    private val config: MaskConfig = MaskConfig()
) {
    companion object {
        private const val TAG = "TextMaskGenerator"
    }

    // Bitmap pool for reuse
    private val bitmapPool = mutableListOf<Bitmap>()

    /**
     * Generate masks for all regions (synchronous)
     */
    fun generateMasks(bitmap: Bitmap, regions: List<TextRegion>): List<MaskedRegion> {
        Log.d(TAG, "Generating masks for ${regions.size} regions")

        return regions.map { region ->
            generateMaskForRegion(bitmap, region)
        }
    }

    /**
     * Generate masks for all regions (asynchronous, parallel)
     */
    suspend fun generateMasksAsync(
        bitmap: Bitmap,
        regions: List<TextRegion>
    ): List<MaskedRegion> = coroutineScope {
        Log.d(TAG, "Generating masks asynchronously for ${regions.size} regions")

        // Process in batches to control memory
        regions.chunked(config.maxConcurrent).flatMap { batch ->
            batch.map { region ->
                async(Dispatchers.Default) {
                    generateMaskForRegion(bitmap, region)
                }
            }.awaitAll()
        }
    }

    /**
     * Generate complete MaskedRegion for a single region
     */
    private fun generateMaskForRegion(bitmap: Bitmap, region: TextRegion): MaskedRegion {
        try {
            // Check if region is too small
            if (region.bounds.width() < config.minCropWidth ||
                region.bounds.height() < config.minCropHeight) {
                Log.d(TAG, "Region too small, skipping masking")
                return MaskedRegion(
                    region = region,
                    mask = null,
                    maskedBitmap = bitmap,
                    croppedBitmap = cropToRegion(bitmap, region)
                )
            }

            // Step 1: Create binary mask
            val mask = createBinaryMask(bitmap.width, bitmap.height, region)

            // Step 2: Expand mask (optional)
            val expandedMask = if (config.expandMask) {
                val expanded = expandMask(mask)
                if (expanded != mask) mask.recycle()
                expanded
            } else {
                mask
            }

            // Step 3: Apply mask to bitmap
            val maskedBitmap = applyMask(bitmap, expandedMask)

            // Step 4: Crop to region
            val croppedBitmap = cropToRegion(maskedBitmap, region)

            return MaskedRegion(
                region = region,
                mask = expandedMask,
                maskedBitmap = maskedBitmap,
                croppedBitmap = croppedBitmap
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error generating mask for region", e)
            // Fallback: return without masking
            return MaskedRegion(
                region = region,
                mask = null,
                maskedBitmap = bitmap,
                croppedBitmap = cropToRegion(bitmap, region)
            )
        }
    }

    /**
     * Create binary mask for a region
     * White (255) = text area, Black (0) = background
     */
    private fun createBinaryMask(width: Int, height: Int, region: TextRegion): Bitmap {
        // Use ALPHA_8 for memory efficiency (1 byte per pixel)
        val mask = getBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)

        // Fill with black (transparent)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Draw white rectangle for text region
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(region.bounds, paint)

        return mask
    }

    /**
     * Expand mask to include text edges
     * Uses dilation and optional smoothing
     */
    private fun expandMask(mask: Bitmap): Bitmap {
        if (!config.expandMask) return mask
        if (!OpenCvInitializer.ensureInitialized()) {
            Log.w(TAG, "OpenCV unavailable, skipping mask expansion")
            return mask
        }

        try {
            // Convert to Mat
            val mat = Mat()
            Utils.bitmapToMat(mask, mat)

            // Dilation: expand white areas
            val dilated = Mat()
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(config.expansionKernel.toDouble(), config.expansionKernel.toDouble())
            )
            Imgproc.dilate(mat, dilated, kernel, org.opencv.core.Point(-1.0, -1.0), config.expansionIterations)

            // Optional: Gaussian blur for smooth edges
            val result = if (config.smoothEdges) {
                val smoothed = Mat()
                val blurSize = Size(config.smoothKernel.toDouble(), config.smoothKernel.toDouble())
                Imgproc.GaussianBlur(dilated, smoothed, blurSize, 0.0)
                dilated.release()
                smoothed
            } else {
                dilated
            }

            // Convert back to Bitmap
            val expandedMask = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
            Utils.matToBitmap(result, expandedMask)

            // Release resources
            mat.release()
            kernel.release()
            result.release()

            return expandedMask
        } catch (error: Throwable) {
            Log.e(TAG, "Error expanding mask", error)
            return mask
        }
    }

    /**
     * Apply mask to bitmap using PorterDuff compositing
     * Keeps only the masked area, rest becomes transparent
     */
    private fun applyMask(bitmap: Bitmap, mask: Bitmap): Bitmap {
        // Create result bitmap
        val result = getBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw original bitmap
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // Apply mask using DST_IN mode (keep only intersection)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(mask, 0f, 0f, paint)

        return result
    }

    /**
     * Crop bitmap to region bounds with padding
     */
    private fun cropToRegion(bitmap: Bitmap, region: TextRegion): Bitmap {
        val bounds = region.bounds

        // Calculate crop bounds with padding
        val left = (bounds.left - config.cropPadding).coerceAtLeast(0)
        val top = (bounds.top - config.cropPadding).coerceAtLeast(0)
        val right = (bounds.right + config.cropPadding).coerceAtMost(bitmap.width)
        val bottom = (bounds.bottom + config.cropPadding).coerceAtMost(bitmap.height)

        // Ensure minimum size
        val width = (right - left).coerceAtLeast(config.minCropWidth)
        val height = (bottom - top).coerceAtLeast(config.minCropHeight)

        // Adjust if exceeds bitmap bounds
        val finalRight = (left + width).coerceAtMost(bitmap.width)
        val finalBottom = (top + height).coerceAtMost(bitmap.height)
        val finalLeft = (finalRight - width).coerceAtLeast(0)
        val finalTop = (finalBottom - height).coerceAtLeast(0)

        return Bitmap.createBitmap(
            bitmap,
            finalLeft,
            finalTop,
            finalRight - finalLeft,
            finalBottom - finalTop
        )
    }

    /**
     * Get bitmap from pool or create new
     */
    private fun getBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        if (!this.config.reuseBuffers) {
            return Bitmap.createBitmap(width, height, config)
        }

        // Try to find reusable bitmap
        val reusable = bitmapPool.find {
            it.width == width && it.height == height && it.config == config
        }

        return if (reusable != null) {
            bitmapPool.remove(reusable)
            reusable.eraseColor(Color.TRANSPARENT)
            reusable
        } else {
            Bitmap.createBitmap(width, height, config)
        }
    }

    /**
     * Return bitmap to pool or recycle
     */
    private fun recycleBitmap(bitmap: Bitmap) {
        if (!config.reuseBuffers) {
            bitmap.recycle()
            return
        }

        if (bitmapPool.size < 10) {  // Max pool size
            bitmapPool.add(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    /**
     * Clear bitmap pool
     */
    fun clearPool() {
        bitmapPool.forEach { it.recycle() }
        bitmapPool.clear()
    }
}
