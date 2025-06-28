package com.example.ocrmanga.data.models
data class RecognitionResult(
    val scale: Float,
    val recognizer: Any,
    val textResult: com.google.mlkit.vision.text.Text,
    val avgFontSize: Float
)