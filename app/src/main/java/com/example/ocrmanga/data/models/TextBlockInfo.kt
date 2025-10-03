package com.example.ocrmanga.data.models

import android.graphics.Path
import android.graphics.Rect

// Thêm bubbleId để phân biệt các block thuộc các khung thoại khác nhau

data class TextBlockInfo(
    val text: String,
    val bounds: Rect,
    val fontSize: Float,
    val isVertical: Boolean = false,
    val rotation: Float? = null,
    val polygon: Path? = null,
    val wordCountsPerLine: List<Int>? = null, // New field to store word counts per line
    val originalImageWidth: Int? = null, // Width of the image when OCR was performed
    val originalImageHeight: Int? = null, // Height of the image when OCR was performed
    val bubbleId: Int? = null, // ID của khung thoại (bubble) mà block này thuộc về
    val shapeType: Int = 0, // 0 = rectangle (default), 1 = oval
    val backgroundType: BackgroundType = BackgroundType.WHITE, // Loại nền của văn bản gốc
    val averageBackgroundColor: Int? = null, // Màu nền trung bình nếu không phải nền trắng
    val originalTextColor: Int? = null // Màu của văn bản gốc
)

enum class BackgroundType {
    WHITE,      // Nền trắng - sử dụng bôi đen bình thường
    COLORED,    // Nền có màu - sử dụng overlay bán trong suốt
    TRANSPARENT // Nền trong suốt - sử dụng overlay với độ mờ cao
}