package com.example.ocrmanga.data.ocr.models

import android.graphics.Rect

/**
 * Generic OCR text element
 */
data class OcrElement(
    val text: String,
    val bounds: Rect,
    val confidence: Float = 1.0f
)

/**
 * Generic OCR text line
 */
data class OcrLine(
    val text: String,
    val bounds: Rect,
    val elements: List<OcrElement> = emptyList(),
    val confidence: Float = 1.0f
)

/**
 * Generic OCR text block
 */
data class OcrBlock(
    val text: String,
    val bounds: Rect,
    val lines: List<OcrLine> = emptyList(),
    val confidence: Float = 1.0f
)
