package com.example.ocrmanga.data.models

enum class TranslationMode {
    OFFLINE,  // Dịch ngoại tuyến
    ONLINE,   // Dịch trực tuyến
    OFF,      // Tắt dịch
    GEMINI    // Dịch bằng Gemini API,
}
