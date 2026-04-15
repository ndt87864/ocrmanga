package com.example.ocrmanga.data.ocr.models

import android.graphics.Bitmap

/**
 * Represents a text region with its mask and processed bitmaps
 */
data class MaskedRegion(
    val region: TextRegion,          // Original region info
    val mask: Bitmap?,               // Binary mask (ALPHA_8)
    val maskedBitmap: Bitmap,        // Bitmap with mask applied
    val croppedBitmap: Bitmap        // Cropped to region bounds
) {
    /**
     * Release all bitmaps to free memory
     */
    fun recycle() {
        mask?.recycle()
        maskedBitmap.recycle()
        croppedBitmap.recycle()
    }
}
