package com.example.ocrmanga.data.translation

import android.app.Application
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.ApiKeyInfo
import com.example.ocrmanga.utils.AppLogger as Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trình quản lý Pool API Key (Phiên bản đơn giản hóa)
 * Loại bỏ hoàn toàn logic theo dõi quota và reset time.
 */
class ApiKeyPoolManager(private val application: Application) {
    private val databaseHelper = DatabaseHelper(application)
    private val apiKeysCache = ConcurrentHashMap<String, MutableList<ApiKeyInfo>>()
    private val lastSelectionIndex = ConcurrentHashMap<String, AtomicInteger>()

    companion object {
        private const val TAG = "ApiKeyPoolManager"
    }

    init {
        refreshKeys()
    }

    fun refreshKeys() {
        val allKeys = databaseHelper.getAllApiKeysWithStats()
        val grouped = allKeys.groupBy { it.type }
        apiKeysCache.clear()
        grouped.forEach { (type, keys) ->
            apiKeysCache[type] = keys.toMutableList()
            lastSelectionIndex.putIfAbsent(type, AtomicInteger(0))
        }
        //Log.i(TAG, "Đã tải ${allKeys.size} API keys từ database.")
    }

    /**
     * Chọn key tiếp theo theo cơ chế Round Robin đơn giản
     */
    fun selectBestKey(type: String): ApiKeyInfo? {
        val keys = apiKeysCache[type]?.filter { it.isActive } ?: return null
        if (keys.isEmpty()) {
            Log.w(TAG, "Không có API key $type nào khả dụng!")
            return null
        }

        val index = lastSelectionIndex[type]?.getAndIncrement() ?: 0
        return keys[index % keys.size]
    }
}
