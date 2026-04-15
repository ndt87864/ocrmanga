package com.example.ocrmanga.data.ocr.models

/**
 * Input for block merging operation
 */
data class MergingInput(
    val ocrResults: List<RegionOcrResult>,
    val orientation: TextOrientation,
    val config: MergingConfig
)

/**
 * OCR result for a single region
 */
data class RegionOcrResult(
    val region: TextRegion,
    val blocks: List<OcrBlock>,
    val confidence: Float
)

/**
 * Text orientation
 */
enum class TextOrientation {
    VERTICAL,      // Japanese/Chinese manga (right to left)
    HORIZONTAL     // Latin text, Korean
}

/**
 * Configuration for block merging
 */
data class MergingConfig(
    // Proximity thresholds (as ratio of text size)
    val verticalProximityRatio: Float = 0.3f,
    val horizontalProximityRatio: Float = 0.3f,
    val verticalGapRatio: Float = 1.5f,
    val horizontalGapRatio: Float = 1.5f,

    // Minimum confidence to include block
    val minBlockConfidence: Float = 0.45f,

    // Region ordering strategy
    val regionOrderingStrategy: RegionOrderingStrategy = RegionOrderingStrategy.SPATIAL
) {
    init {
        require(verticalProximityRatio > 0) { "Vertical proximity ratio must be positive" }
        require(horizontalProximityRatio > 0) { "Horizontal proximity ratio must be positive" }
        require(minBlockConfidence in 0f..1f) { "Min confidence must be 0-1" }
    }
}

enum class RegionOrderingStrategy {
    SPATIAL,        // Based on position (default)
    CONFIDENCE,     // Based on OCR confidence
    SIZE            // Based on region size
}

/**
 * Result of block merging operation
 */
data class MergedResult(
    val blocks: List<MergedTextBlock>,
    val readingOrder: List<Int>     // Indices in reading order
) {
    /**
     * Get blocks in reading order
     */
    fun getOrderedBlocks(): List<MergedTextBlock> {
        return readingOrder.map { blocks[it] }
    }

    /**
     * Get all text concatenated in reading order
     */
    fun getAllText(): String {
        return getOrderedBlocks().joinToString("\n") { it.text }
    }
}
