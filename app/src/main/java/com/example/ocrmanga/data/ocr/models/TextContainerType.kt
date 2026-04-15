package com.example.ocrmanga.data.ocr.models

/**
 * Phân loại các loại container chứa text (overlaytext) trong manga
 */
enum class TextContainerType {
    /**
     * Bong bóng chat (Speech bubble)
     * Thường có dạng oval/ellipse, đám mây, hoặc hình dạng bất kỳ có viền rõ rệt bao quanh text.
     */
    SPEECH_BUBBLE,

    /**
     * Khung thoại (Speech frame / Rectangular box)
     * Các hộp thoại có dạng hình chữ nhật rõ rệt (có thể có bo góc).
     */
    SPEECH_FRAME,

    /**
     * Bong bóng và thoại bán trong suốt (Semi-transparent)
     * Text nằm trên một nền không có viền rõ rệt nhưng có độ mờ/opacity hoặc gradient (background bị làm mờ để chữ nổi lên).
     */
    SEMI_TRANSPARENT,

    /**
     * Text hiển thị thẳng lên nền (Free text / No container)
     * Text được viết trực tiếp lên nét vẽ manga, không có bất kỳ container hay đánh bóng nền nào bao quanh.
     */
    FREE_TEXT,

    /**
     * Không xác định được loại container cụ thể
     */
    UNKNOWN
}

/**
 * Thông tin chi tiết về container chứa text
 */
data class TextContainerInfo(
    val type: TextContainerType,
    val confidence: Float,              // Độ tin cậy của phân loại (0.0-1.0)
    val hasStrongBorder: Boolean,       // Có viền rõ ràng không
    val borderThickness: Float,         // Độ dày viền ước tính (pixels)
    val backgroundOpacity: Float,       // Độ mờ đục của nền (0.0=trong suốt, 1.0=đục)
    val circularity: Double,            // Độ tròn (0.0-1.0, 1.0=hình tròn hoàn hảo)
    val aspectRatio: Double,            // Tỷ lệ width/height
    val cornerCount: Int,               // Số góc phát hiện được
    val averageBackgroundColor: Int? = null, // Màu nền trung bình (nếu có)
    val containerBounds: android.graphics.Rect? = null // Tọa độ thực tế của container (nếu phát hiện được)
) {
    /**
     * Kiểm tra xem container có phải dạng speech bubble không
     */
    fun isSpeechBubble(): Boolean {
        return (type == TextContainerType.SPEECH_BUBBLE || type == TextContainerType.SPEECH_FRAME) && hasStrongBorder
    }

    /**
     * Kiểm tra xem có cần overlay để che nền không
     */
    fun needsOverlay(): Boolean {
        return type != TextContainerType.FREE_TEXT && backgroundOpacity > 0.3f
    }
}
