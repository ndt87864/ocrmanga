package com.example.ocrmanga.data.ocr.models

import android.graphics.Rect

/**
 * Merged text block with metadata
 */
data class MergedTextBlock(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
    val regionId: Int,              // Which region this belongs to
    val lines: List<OcrLine>        // Generic OCR lines
) {
    /**
     * Get center point of the block
     */
    fun centerX(): Int = bounds.centerX()
    fun centerY(): Int = bounds.centerY()

    /**
     * Check if this block overlaps with another
     */
    fun overlaps(other: MergedTextBlock): Boolean {
        return bounds.intersect(other.bounds)
    }
}
