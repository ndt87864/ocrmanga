package com.example.ocrmanga.data.models

enum class TranslationMode {
    OFFLINE,      // Dịch ngoại tuyến
    ONLINE,       // Dịch trực tuyến
    OFF,          // Tắt dịch
    GEMINI,       // Dịch bằng Gemini API
    MISTRAL;      // Dịch bằng Mistral API

    fun getDisplayName(): String = when (this) {
        OFFLINE -> "Dịch ngoại tuyến"
        ONLINE -> "Dịch trực tuyến"
        OFF -> "Tắt"
        GEMINI -> "Gemini AI"
        MISTRAL -> "Mistral AI"
    }
}
