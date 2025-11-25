package com.example.ocrmanga.data.models

/**
 * Enum đại diện cho các trạng thái dịch của một ảnh
 */
enum class TranslationStatus {
    SCANNING,       // Đang quét ảnh (OCR)
    TRANSLATING,    // Đang dịch văn bản
    DISTRIBUTING,   // Đang phân phối bản dịch trở lại tọa độ
    COMPLETED,      // Dịch hoàn tất
    IDLE            // Không có hoạt động dịch
}
