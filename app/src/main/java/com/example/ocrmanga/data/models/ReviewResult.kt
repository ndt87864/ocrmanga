package com.example.ocrmanga.data.models

/**
 * Review result từ Manager
 * Dùng chung cho cả V1 và V2
 */
data class ReviewResult(
    val allApproved: Boolean,
    val rejections: Map<Int, String>, // blockIndex -> lý do cần sửa
    val overallQuality: Float? = null // V2 thêm quality score
)
