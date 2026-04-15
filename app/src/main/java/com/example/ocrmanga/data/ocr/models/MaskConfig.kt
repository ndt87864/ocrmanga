package com.example.ocrmanga.data.ocr.models

/**
 * Configuration for mask generation
 */
data class MaskConfig(
    // Mask expansion
    val expandMask: Boolean = true,
    val expansionKernel: Int = 3,
    val expansionIterations: Int = 1,
    val smoothEdges: Boolean = true,
    val smoothKernel: Int = 3,

    // Cropping
    val cropPadding: Int = 10,
    val minCropWidth: Int = 32,
    val minCropHeight: Int = 32,

    // Optimization
    val reuseBuffers: Boolean = true,
    val maxConcurrent: Int = 4
) {
    init {
        require(expansionKernel % 2 == 1) { "Expansion kernel must be odd" }
        require(smoothKernel % 2 == 1) { "Smooth kernel must be odd" }
        require(cropPadding >= 0) { "Crop padding must be non-negative" }
        require(minCropWidth > 0 && minCropHeight > 0) { "Min crop size must be positive" }
        require(maxConcurrent > 0) { "Max concurrent must be positive" }
    }
}
