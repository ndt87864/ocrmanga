package com.example.ocrmanga.data.models

import android.graphics.Path
import android.graphics.Rect

// Thêm bubbleId để phân biệt các block thuộc các khung thoại khác nhau

data class TextBlockInfo(
    val text: String,
    val bounds: Rect,
    val fontSize: Float,
    val isVertical: Boolean = false,
    val polygon: Path? = null,
    val wordCountsPerLine: List<Int>? = null, // New field to store word counts per line
    val originalImageWidth: Int? = null, // Width of the image when OCR was performed
    val originalImageHeight: Int? = null, // Height of the image when OCR was performed
    val bubbleId: Int? = null // ID của khung thoại (bubble) mà block này thuộc về
)