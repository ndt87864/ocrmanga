package com.example.ocrmanga.data.models

enum class TranslationMode {
    OFFLINE,      // Dịch ngoại tuyến
    ONLINE,       // Dịch trực tuyến
    OFF,          // Tắt dịch
    GEMINI,       // Dịch bằng Gemini API
    MISTRAL,      // Dịch bằng Mistral API
    NVIDIA_GLM5,  // Dịch bằng NVIDIA NIM (GLM-5)
    NVIDIA_QWEN;  // Dịch bằng NVIDIA NIM (Qwen 3.5)

    fun getDisplayName(): String = when (this) {
        OFFLINE -> "Dịch ngoại tuyến"
        ONLINE -> "Dịch trực tuyến"
        OFF -> "Tắt"
        GEMINI -> "Gemini AI"
        MISTRAL -> "Mistral AI"
        NVIDIA_GLM5 -> "NVIDIA GLM-5"
        NVIDIA_QWEN -> "NVIDIA Qwen 3.5"
    }
}
