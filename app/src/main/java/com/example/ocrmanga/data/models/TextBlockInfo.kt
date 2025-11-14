package com.example.ocrmanga.data.models

import android.graphics.Path
import android.graphics.Rect

// Thêm bubbleId để phân biệt các block thuộc các khung thoại khác nhau

data class TextBlockInfo(
    val fontFamily: String = "mto_comic_1", // Font chữ mặc định cho dịch
    val text: String,
    val bounds: Rect,
    val fontSize: Float,
        val lineSpacing: Float = if (fontFamily == "mto_comic_1") 1.1f else if (fontFamily == "mto_augie") 2f else 1.0f, // Khoảng cách dòng (multiplier), comic_1 = 1.09, mặc định = 1.0
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
    val originalTextColor: Int? = null, // Màu của văn bản gốc
    // Custom color properties from TranslationEditor
    val customOverlayColor: Int? = null, // Màu overlay tùy chỉnh
    val customTextColor: Int? = null, // Màu text tùy chỉnh
    val overlayAlpha: Float = 1.0f, // Độ trong suốt overlay (0.0 - 1.0)
    val textBoldness: Float = 1.0f, // Độ đậm text (0.5 - 2.0)
    val overlaySaturation: Float = 1.0f, // Độ bão hòa overlay (0.0 - 2.0)
    val textSaturation: Float = 1.0f, // Độ bão hòa text (0.0 - 2.0)
    val customBorderColor: Int? = null, // Màu viền chữ tùy chỉnh
    val borderThickness: Float = 0.0f, // Độ dày viền chữ (0.0 - 5.0)
    val borderAlpha: Float = 1.0f // Độ trong suốt viền chữ (0.0 - 1.0)
    ,
    // Shadow properties for rendered text
    val customShadowColor: Int? = null,
    val shadowAlpha: Float = 1.0f,
    val shadowRadius: Float = 0.0f,
    // Flag để kiểm soát việc áp dụng logic chống chồng lấn
    // true: áp dụng merge overlapping (khi translation từ OCR)
    // false: không áp dụng merge (khi edit hoặc di chuyển manual)
    val applyMerge: Boolean = true
)

enum class BackgroundType {
    WHITE,      // Nền trắng - sử dụng bôi đen bình thường
    COLORED,    // Nền có màu - sử dụng overlay bán trong suốt
    TRANSPARENT // Nền trong suốt - sử dụng overlay với độ mờ cao
}