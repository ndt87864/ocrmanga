package com.example.ocrmanga.data.ocr.models

/**
 * Configuration for preprocessing
 */
data class PreprocessingConfig(
    // Denoising
    val denoisingMethod: DenoisingMethod = DenoisingMethod.GAUSSIAN,
    val gaussianKernel: Int = 3,
    val bilateralD: Int = 5,
    val bilateralSigmaColor: Double = 75.0,
    val bilateralSigmaSpace: Double = 75.0,

    // CLAHE
    val useCLAHE: Boolean = true,
    val claheClipLimit: Double = 2.0,
    val claheTileSize: Int = 8,

    // Adaptive Thresholding
    val useAdaptiveThreshold: Boolean = true,
    val adaptiveMethod: AdaptiveMethod = AdaptiveMethod.GAUSSIAN,
    val adaptiveBlockSize: Int = 11,
    val adaptiveC: Double = 2.0,

    // Morphological Operations
    val useMorphology: Boolean = true,
    val openingKernel: Int = 2,
    val closingKernel: Int = 3,

    // Region-specific configs
    val regionSpecificConfig: Map<RegionType, PreprocessingConfig> = emptyMap()
) {
    init {
        require(gaussianKernel % 2 == 1) { "Gaussian kernel must be odd" }
        require(adaptiveBlockSize % 2 == 1) { "Adaptive block size must be odd" }
        require(claheClipLimit > 0) { "CLAHE clip limit must be positive" }
        require(claheTileSize > 0) { "CLAHE tile size must be positive" }
    }
}

enum class DenoisingMethod {
    NONE,
    GAUSSIAN,
    BILATERAL
}

enum class AdaptiveMethod {
    GAUSSIAN,
    MEAN
}
