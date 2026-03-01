package com.example.ocrmanga.data.models

enum class TranslationMode {
    OFFLINE,  // Dịch ngoại tuyến
    ONLINE,   // Dịch trực tuyến
    OFF,      // Tắt dịch
    GEMINI,   // Dịch bằng Gemini API
    MISTRAL,  // Dịch bằng Mistral API
    NVIDIA_GLM5 // Dịch bằng NVIDIA NIM (GLM-5)
}
