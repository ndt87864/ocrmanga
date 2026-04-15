package com.example.ocrmanga.data.ocr.models

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Represents a detected text region in an image
 */
data class TextRegion(
    val bounds: Rect,           // Bounding box
    val confidence: Float,      // Detection confidence (0.0-1.0)
    val mask: Bitmap?,          // Optional binary mask
    val type: RegionType        // Region classification
) {
    /**
     * Calculate area of the region
     */
    fun area(): Int = bounds.width() * bounds.height()

    /**
     * Check if this region overlaps with another
     */
    fun overlaps(other: TextRegion): Boolean {
        return bounds.intersect(other.bounds)
    }

    /**
     * Calculate IoU with another region
     */
    fun iou(other: TextRegion): Float {
        val intersection = Rect(bounds).apply {
            intersect(other.bounds)
        }

        if (intersection.isEmpty) return 0f

        val intersectionArea = intersection.width() * intersection.height()
        val unionArea = area() + other.area() - intersectionArea

        return intersectionArea.toFloat() / unionArea
    }
}

/**
 * Types of text regions
 */
enum class RegionType {
    BUBBLE,      // Speech bubble
    SFX,         // Sound effects
    NARRATION,   // Narration box
    UNKNOWN      // Unclassified
}
