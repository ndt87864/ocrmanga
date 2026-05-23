package com.example.ocrmanga.data.models

enum class TranslationMode {
    OFFLINE,      // Dịch ngoại tuyến
    ONLINE,       // Dịch trực tuyến
    OFF,          // Tắt dịch
    GEMINI,       // Dịch bằng Gemini API
    MISTRAL,      // Dịch bằng Mistral API
    ZAI,          // Dịch bằng Z.AI API (GLM-4.7-flash)
    OCRMANGA,     // Dịch bằng OCRMANGA API (Vercel Custom Proxy)
    OCR,          // Chỉ thực hiện OCR, không dịch
    EXTERNAL;     // Sử dụng bản dịch ngoài (JSON)

    fun getDisplayName(): String = when (this) {
        OFFLINE -> "Dịch ngoại tuyến"
        ONLINE -> "Dịch trực tuyến"
        OFF -> "Tắt"
        GEMINI -> "Gemini AI"
        MISTRAL -> "Mistral AI"
        ZAI -> "Z.AI (GLM-4)"
        OCRMANGA -> "OCR Manga"
        OCR -> "Chỉ OCR"
        EXTERNAL -> "Bản dịch ngoài"
    }
}
