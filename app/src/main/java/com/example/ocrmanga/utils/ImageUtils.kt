package com.example.ocrmanga.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.TextBlockInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Utilities cho các thao tác với images
 */
object ImageUtils {

    private const val TAG = "ImageUtils"
    
    /**
     * Get image dimensions without loading the full bitmap
     */
    fun getImageDimensions(context: Context, uri: android.net.Uri): Pair<Int, Int> {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
            }
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting image dimensions: $uri", e)
            Pair(1280, 1808) // Fallback
        }
    }

    /**
     * Load bitmap từ image_id trong database
     */
    fun loadImageBitmap(context: Context, imageId: Long): Bitmap? {
        return try {
            val databaseHelper = DatabaseHelper(context)

            // Lấy imageUri từ DB - query bảng images
            val db = databaseHelper.readableDatabase
            val cursor = db.query(
                "images",
                arrayOf("image_uri"),
                "image_id = ?",
                arrayOf(imageId.toString()),
                null, null, null
            )

            val imageUri = if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                cursor.close()
                null
            }
            cursor.close()

            if (imageUri != null) {
                // Load từ content resolver hoặc file
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(imageUri)
                    if (bitmap != null) {
                        return bitmap
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load from file: $imageUri", e)
                }

                // Try loading từ content resolver nếu là content:// URI
                if (imageUri.startsWith("content://")) {
                    val inputStream = context.contentResolver.openInputStream(android.net.Uri.parse(imageUri))
                        ?: return null
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    return bitmap
                }
            }

            // Fallback: Không load được bitmap -> return null
            // OverlayOptimizer sẽ chạy mà không cần bitmap
            Log.w(TAG, "Could not load bitmap for imageId=$imageId (uri=$imageUri)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image bitmap for imageId=$imageId", e)
            null
        }
    }

    /**
     * Decode bitmap từ bytes
     */
    fun decodeBitmapFromBytes(bytes: ByteArray): Bitmap? {
        return try {
            val stream = ByteArrayInputStream(bytes)
            BitmapFactory.decodeStream(stream)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap from bytes", e)
            null
        }
    }

    /**
     * Decode bitmap từ URI với bitmap pool
     */
    fun decodeBitmapWithPool(context: android.content.Context, uri: android.net.Uri, pool: com.example.ocrmanga.utils.BitmapPool): Bitmap? {
        // Try get from pool first
        val poolBitmap = pool.get(uri.toString(), 0, 0)
        if (poolBitmap != null) {
            return poolBitmap
        }

        // Decode từ source
        return try {
            val dimensions = getImageDimensions(context, uri)
            val (w, h) = dimensions

            // Decode với size tối ưu
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                inSampleSize = calculateInSampleSize(w, h, 1080, 1920)
            }

            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            if (bitmap != null) {
                // Decode resized version
                val resizedBitmap = if (options.inSampleSize > 1) {
                    val scaledW = w / options.inSampleSize
                    val scaledH = h / options.inSampleSize
                    android.graphics.Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
                } else {
                    bitmap
                }

                // Put vào pool
                pool.put(uri.toString(), resizedBitmap)
                resizedBitmap
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap with pool: $uri", e)
            null
        }
    }

    /**
     * Calculate inSampleSize cho bitmap decode
     */
    fun calculateInSampleSize(originalWidth: Int, originalHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1

        if (originalHeight > reqHeight || originalWidth > reqWidth) {
            val halfHeight = originalHeight / 2
            val halfWidth = originalWidth / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Resize bitmap để giảm memory usage
     */
    fun resizeBitmap(
        bitmap: Bitmap,
        maxWidth: Int = 1080,
        maxHeight: Int = 1920
    ): Bitmap {
        val ratio = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        if (ratio >= 1f) return bitmap

        val newWidth = (bitmap.width * ratio).toInt()
        val newHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Compress bitmap sang JPEG
     */
    fun compressBitmapToJpeg(
        bitmap: Bitmap,
        quality: Int = 85
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Get image dimensions từ bitmap
     */
    fun getBitmapDimensions(bitmap: Bitmap): Pair<Int, Int> {
        return Pair(bitmap.width, bitmap.height)
    }

    /**
     * Check if text blocks overlap significantly
     */
    fun checkOverlappingBlocks(
        blocks: List<TextBlockInfo>,
        threshold: Float = 0.3f
    ): List<Pair<TextBlockInfo, TextBlockInfo>> {
        val overlaps = mutableListOf<Pair<TextBlockInfo, TextBlockInfo>>()

        for (i in blocks.indices) {
            for (j in i + 1 until blocks.size) {
                val block1 = blocks[i]
                val block2 = blocks[j]

                val intersection = Rect(
                    kotlin.math.max(block1.bounds.left, block2.bounds.left),
                    kotlin.math.max(block1.bounds.top, block2.bounds.top),
                    kotlin.math.min(block1.bounds.right, block2.bounds.right),
                    kotlin.math.min(block1.bounds.bottom, block2.bounds.bottom)
                )

                if (intersection.width() > 0 && intersection.height() > 0) {
                    val area1 = block1.bounds.width() * block1.bounds.height()
                    val area2 = block2.bounds.width() * block2.bounds.height()
                    val intersectionArea = intersection.width() * intersection.height()

                    val overlap1 = intersectionArea.toFloat() / area1
                    val overlap2 = intersectionArea.toFloat() / area2

                    if (maxOf(overlap1, overlap2) > threshold) {
                        overlaps.add(block1 to block2)
                    }
                }
            }
        }

        return overlaps
    }
}
