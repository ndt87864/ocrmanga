package com.example.ocrmanga.data.translation

import android.app.Application
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.ApiKeyInfo
import com.example.ocrmanga.utils.AppLogger as Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Trình quản lý Pool API Key và Quota
 * Tương tự AccountManager của antigravity-claude-proxy
 */
class ApiKeyPoolManager(private val application: Application) {
    private val databaseHelper = DatabaseHelper(application)
    private val apiKeysCache = ConcurrentHashMap<String, MutableList<ApiKeyInfo>>()

    companion object {
        private const val TAG = "ApiKeyPoolManager"
        private const val LOW_QUOTA_THRESHOLD = 0.10 // 10%
        private const val CRITICAL_QUOTA_THRESHOLD = 0.05 // 5%
        private const val MAX_CONSECUTIVE_FAILURES = 3
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
        }
        Log.i(TAG, "Đã tải ${allKeys.size} API keys từ database.")
    }

    /**
     * Chọn key tốt nhất dựa trên health score và quota
     */
    fun selectBestKey(type: String): ApiKeyInfo? {
        val keys = apiKeysCache[type] ?: return null
        val now = System.currentTimeMillis()

        // Lọc các key khả dụng
        val availableKeys = keys.filter { key ->
            key.isActive &&
            (key.rateLimitReset == 0L || now > key.rateLimitReset) &&
            key.consecutiveFailures < MAX_CONSECUTIVE_FAILURES &&
            key.remainingQuota > CRITICAL_QUOTA_THRESHOLD
        }

        if (availableKeys.isEmpty()) {
            Log.w(TAG, "Không có API key $type nào khả dụng!")
            return null
        }

        // Tính toán score cho từng key (tương tự quota-tracker.js)
        // Score cao nhất được chọn
        return availableKeys.maxByOrNull { calculateScore(it) }
    }

    private fun calculateScore(key: ApiKeyInfo): Double {
        var score = key.remainingQuota * 100.0

        // Phạt nếu có lỗi liên tiếp
        if (key.consecutiveFailures > 0) {
            score *= (1.0 - (key.consecutiveFailures.toDouble() / MAX_CONSECUTIVE_FAILURES))
        }

        // Ưu tiên các key chưa dùng lâu hơn (simple load balancing)
        val timeSinceLastCheck = System.currentTimeMillis() - key.lastChecked
        if (timeSinceLastCheck > 300000) { // 5 phút
            score += 10.0
        }

        return score
    }

    fun notifySuccess(apiKey: String) {
        updateKeyState(apiKey) { current ->
            current.copy(
                consecutiveFailures = 0,
                lastChecked = System.currentTimeMillis()
            )
        }
    }

    fun notifyFailure(apiKey: String) {
        updateKeyState(apiKey) { current ->
            current.copy(
                consecutiveFailures = current.consecutiveFailures + 1,
                lastChecked = System.currentTimeMillis()
            )
        }
    }

    fun notifyRateLimit(apiKey: String, resetInMs: Long) {
        updateKeyState(apiKey) { current ->
            current.copy(
                rateLimitReset = System.currentTimeMillis() + resetInMs,
                lastChecked = System.currentTimeMillis()
            )
        }
    }

    fun notifyQuota(apiKey: String, remainingFraction: Double) {
        updateKeyState(apiKey) { current ->
            current.copy(
                remainingQuota = remainingFraction,
                lastChecked = System.currentTimeMillis()
            )
        }
    }

    private fun updateKeyState(apiKey: String, updateFn: (ApiKeyInfo) -> ApiKeyInfo) {
        apiKeysCache.values.forEach { keys ->
            val index = keys.indexOfFirst { it.value == apiKey }
            if (index != -1) {
                val updated = updateFn(keys[index])
                keys[index] = updated

                // Đồng bộ xuống DB
                databaseHelper.updateApiKeyStats(
                    apiKey = updated.value,
                    remainingQuota = updated.remainingQuota,
                    consecutiveFailures = updated.consecutiveFailures,
                    rateLimitReset = updated.rateLimitReset
                )
            }
        }
    }
}
