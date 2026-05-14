package com.example.ocrmanga.utils

import android.content.Context
import android.util.Log
import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * MemoryManager - Quản lý bộ nhớ app để tránh OutOfMemory
 * Clean up cache khi app bị kill hoặc memory thấp
 */
object MemoryManager : ComponentCallbacks2 {
    private const val TAG = "MemoryManager"
    private var isAppInBackground = false

    fun init(context: Context) {
        context.registerComponentCallbacks(this)
        Log.d(TAG, "MemoryManager initialized")
    }

    override fun onTrimMemory(level: Int) {
        Log.d(TAG, "onTrimMemory called with level: $level")
        trimMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        Log.w(TAG, "onLowMemory called! Cleaning all caches.")
        BitmapPool.clearAll()
    }


    /**
     * Called when app goes to background
     */
    fun onAppBackgrounded() {
        isAppInBackground = true
        Log.d(TAG, "App went to background")
    }

    /**
     * Called when app comes to foreground
     */
    fun onAppForegrounded() {
        isAppInBackground = false
        Log.d(TAG, "App came to foreground")
    }

    /**
     * Trim memory - Clean up caches
     */
    fun trimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // Process đang chạy nhưng memory rất thấp hoặc sắp bị kill - Clean tất cả
                Log.w(TAG, "Critical memory pressure (level $level). Cleaning all caches.")
                BitmapPool.clearAll()
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // App ở background hoặc UI ẩn - Clean bitmap pool để giải phóng memory
                Log.d(TAG, "Moderate memory pressure (level $level). Cleaning bitmap pool.")
                BitmapPool.clearAll()
            }
        }
    }

    /**
     * Get memory usage stats
     */
    fun getMemoryStats(): MemoryStats {
        return MemoryStats(
            bitmapPoolUsage = BitmapPool.getMemoryUsage(),
            totalHeapSize = Runtime.getRuntime().totalMemory(),
            freeHeapSize = Runtime.getRuntime().freeMemory(),
            usedHeapSize = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            maxHeapSize = Runtime.getRuntime().maxMemory()
        )
    }

    data class MemoryStats(
        val bitmapPoolUsage: Long,
        val totalHeapSize: Long,
        val freeHeapSize: Long,
        val usedHeapSize: Long,
        val maxHeapSize: Long
    )
}
