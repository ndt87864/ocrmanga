package com.example.ocrmanga.utils

import android.graphics.Bitmap
import android.util.Log
import java.util.LinkedHashMap

/**
 * Bitmap Pool - Cache raw bitmap để tái sử dụng khi scroll
 * Giống hệ thống cache của Gallery app
 */
class BitmapPool(
    private val maxSize: Int = 100 * 1024 * 1024, // 100MB total cache
    private val maxBitmaps: Int = 32, // Tối đa 32 bitmap
    private val maxBitmapSize: Int = 8 * 1024 * 1024 // Tối đa 8MB per bitmap
) {
    private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private val lock = Any()

    companion object {
        private const val TAG = "BitmapPool"
        @Volatile private var instance: BitmapPool? = null

        fun getInstance(): BitmapPool {
            return instance ?: synchronized(this) {
                instance ?: BitmapPool().also { instance = it }
            }
        }

        /**
         * Clean all cached bitmaps - nên gọi khi app foreground/background
         */
        fun clearAll() {
            synchronized(BitmapPool) {
                getInstance().clear()
            }
        }

        /**
         * Get estimated memory usage
         */
        fun getMemoryUsage(): Long {
            return synchronized(BitmapPool) {
                getInstance().estimateMemoryUsage()
            }
        }
    }

    /**
     * Get bitmap từ cache
     */
    fun get(uri: String, width: Int, height: Int): Bitmap? {
        if (width == 0 || height == 0) return null

        return synchronized(lock) {
            val cached = cache[uri]
            if (cached != null) {
                // Log.d(TAG, "BitmapPool hit: $uri (${cached.width}x${cached.height})")
                cached
            } else {
                null
            }
        }
    }

    /**
     * Put bitmap vào cache
     */
    fun put(uri: String, bitmap: Bitmap): Boolean {
        if (bitmap.width == 0 || bitmap.height == 0) return false

        // Skip nếu bitmap quá lớn
        if (bitmap.byteCount > maxBitmapSize) {
            Log.w(TAG, "Bitmap too large to cache: ${bitmap.width}x${bitmap.height} (${bitmap.byteCount} bytes)")
            return false
        }

        return synchronized(lock) {
            try {
                // Remove eldest nếu exceeded
                while (cache.size >= maxBitmaps) {
                    val eldest = cache.entries.iterator().next()
                    eldest.value.recycle()
                    cache.remove(eldest.key)
                }

                // Recycle cũ trước khi thêm mới
                cache[uri]?.recycle()

                // Add mới
                cache[uri] = bitmap
                // Log.d(TAG, "BitmapPool put: $uri (${bitmap.width}x${bitmap.height}) - ${cache.size} bitmaps")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error putting bitmap to pool: $uri", e)
                false
            }
        }
    }

    /**
     * Remove bitmap khỏi cache
     */
    fun remove(uri: String) {
        synchronized(lock) {
            cache[uri]?.recycle()
            cache.remove(uri)
        }
    }

    /**
     * Clear all cached bitmaps
     */
    fun clear() {
        synchronized(lock) {
            cache.values.forEach { it.recycle() }
            cache.clear()
            Log.d(TAG, "BitmapPool cleared")
        }
    }

    /**
     * Estimate memory usage
     */
    fun estimateMemoryUsage(): Long {
        synchronized(lock) {
            return cache.values.sumOf { it.byteCount.toLong() }
        }
    }

    /**
     * Get cache stats
     */
    fun getStats(): CacheStats {
        synchronized(lock) {
            val totalBytes = cache.values.sumOf { it.byteCount.toLong() }
            return CacheStats(
                bitmapCount = cache.size,
                memoryUsageBytes = totalBytes,
                memoryUsageMB = totalBytes.toFloat() / (1024 * 1024)
            )
        }
    }

    data class CacheStats(
        val bitmapCount: Int,
        val memoryUsageBytes: Long,
        val memoryUsageMB: Float
    )
}
