package com.example.ocrmanga.data.ocr.models

import android.graphics.Bitmap

/**
 * Result of preprocessing operation
 */
data class PreprocessedResult(
    val bitmap: Bitmap,              // Preprocessed bitmap
    val method: PreprocessingMethod, // Method used
    val metrics: PreprocessingMetrics // Quality metrics
) {
    fun recycle() {
        bitmap.recycle()
    }
}

/**
 * Preprocessing method used
 */
enum class PreprocessingMethod {
    STANDARD,           // Standard pipeline
    ADAPTIVE,           // Adaptive based on image analysis
    REGION_SPECIFIC     // Customized for region type
}

/**
 * Quality metrics for preprocessed image
 */
data class PreprocessingMetrics(
    val contrast: Float,      // Contrast score (0-1)
    val sharpness: Float,     // Sharpness score (0-1)
    val noiseLevel: Float     // Noise level (0-1)
)
