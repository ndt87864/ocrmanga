package com.example.ocrmanga.data.models

data class ApiKeyInfo(
    val id: Int,
    val value: String,
    val type: String,
    val isActive: Boolean,
    val remainingQuota: Double,
    val lastChecked: Long,
    val consecutiveFailures: Int,
    val rateLimitReset: Long
)
