package com.example.ocrmanga.data.ocr.models

/**
 * Configuration for text region detection
 */
data class DetectionConfig(
    // Preprocessing
    val gaussianKernelSize: Int = 5,
    val adaptiveBlockSize: Int = 11,
    val adaptiveC: Double = 2.0,

    // Edge detection
    val cannyThreshold1: Double = 50.0,
    val cannyThreshold2: Double = 150.0,

    // Morphological operations
    val dilationKernel: Int = 3,
    val dilationIterations: Int = 2,
    val closingKernel: Int = 5,

    // Region filtering
    val minRegionArea: Int = 500,
    val maxRegionAreaRatio: Float = 0.5f,
    val minAspectRatio: Float = 0.1f,
    val maxAspectRatio: Float = 10.0f,
    val minEdgeDensity: Float = 0.05f,
    val minSolidity: Float = 0.5f,

    // Region merging
    val mergeDistanceThreshold: Int = 20,
    val mergeIouThreshold: Float = 0.3f
) {
    init {
        require(gaussianKernelSize % 2 == 1) { "Gaussian kernel size must be odd" }
        require(adaptiveBlockSize % 2 == 1) { "Adaptive block size must be odd" }
        require(minRegionArea > 0) { "Min region area must be positive" }
        require(maxRegionAreaRatio in 0f..1f) { "Max region area ratio must be 0-1" }
    }
}
