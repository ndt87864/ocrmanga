package com.example.ocrmanga.data.ocr

import android.graphics.Rect
import com.example.ocrmanga.utils.AppLogger
import com.example.ocrmanga.data.ocr.models.*
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Merges text blocks based on region structure
 * Simplified approach compared to old proximity-based merging
 */
class RegionBasedMerger(
    private val config: MergingConfig = MergingConfig()
) {
    companion object {
        private const val TAG = "RegionBasedMerger"
    }

    /**
     * Merge blocks from multiple regions
     */
    fun mergeBlocks(input: MergingInput): MergedResult {
        //Log.d(TAG, "Merging blocks from ${input.ocrResults.size} regions")

        // Filter low confidence blocks
        val filtered = filterLowConfidenceBlocks(input)

        // Order regions by reading order
        val orderedRegions = orderRegions(filtered.ocrResults, input.orientation)

        // Process each region
        val allMergedBlocks = orderedRegions.flatMapIndexed { regionIndex, result ->
            // Sort blocks within region
            val sorted = sortBlocks(result.blocks, input.orientation)

            // Merge adjacent blocks
            mergeAdjacentBlocks(sorted, regionIndex, input.orientation)
        }

        // Determine reading order
        val readingOrder = determineReadingOrder(allMergedBlocks)

        //Log.d(TAG, "Merged into ${allMergedBlocks.size} blocks")

        return MergedResult(allMergedBlocks, readingOrder)
    }

    /**
     * Filter out blocks with low confidence
     */
    private fun filterLowConfidenceBlocks(input: MergingInput): MergingInput {
        val filtered = input.ocrResults.map { result ->
            val filteredBlocks = result.blocks.filter { block ->
                val confidence = block.lines.mapNotNull { it.confidence }.average().toFloat()
                confidence >= config.minBlockConfidence
            }

            result.copy(blocks = filteredBlocks)
        }

        return input.copy(ocrResults = filtered)
    }

    /**
     * Order regions by reading order
     */
    private fun orderRegions(
        results: List<RegionOcrResult>,
        orientation: TextOrientation
    ): List<RegionOcrResult> {
        return when (config.regionOrderingStrategy) {
            RegionOrderingStrategy.SPATIAL -> {
                orderRegionsSpatially(results, orientation)
            }
            RegionOrderingStrategy.CONFIDENCE -> {
                results.sortedByDescending { it.confidence }
            }
            RegionOrderingStrategy.SIZE -> {
                results.sortedByDescending {
                    it.region.bounds.width() * it.region.bounds.height()
                }
            }
        }
    }

    /**
     * Order regions spatially based on orientation
     */
    private fun orderRegionsSpatially(
        results: List<RegionOcrResult>,
        orientation: TextOrientation
    ): List<RegionOcrResult> {
        return when (orientation) {
            TextOrientation.VERTICAL -> {
                // Right to left for manga
                results.sortedByDescending { it.region.bounds.right }
            }
            TextOrientation.HORIZONTAL -> {
                // Top to bottom, left to right
                results.sortedWith(
                    compareBy(
                        { it.region.bounds.top },
                        { it.region.bounds.left }
                    )
                )
            }
        }
    }

    /**
     * Sort blocks within a region based on orientation
     */
    private fun sortBlocks(
        blocks: List<OcrBlock>,
        orientation: TextOrientation
    ): List<OcrBlock> {
        return when (orientation) {
            TextOrientation.VERTICAL -> sortVertical(blocks)
            TextOrientation.HORIZONTAL -> sortHorizontal(blocks)
        }
    }

    /**
     * Sort blocks for vertical text (manga)
     * Reading order: Right → Left, Top → Bottom
     */
    private fun sortVertical(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.isEmpty()) return blocks

        // Group into columns (right to left)
        val columns = mutableListOf<MutableList<OcrBlock>>()
        val sorted = blocks.sortedByDescending { it.bounds.right }

        for (block in sorted) {
            val blockX = block.bounds.centerX()
            val blockWidth = block.bounds.width()

            // Find column this block belongs to
            val column = columns.find { col ->
                val avgX = col.map { it.bounds.centerX() }.average()
                abs(avgX - blockX) < blockWidth * 0.5f
            }

            if (column != null) {
                column.add(block)
            } else {
                columns.add(mutableListOf(block))
            }
        }

        // Sort blocks within each column (top to bottom)
        return columns.flatMap { column ->
            column.sortedBy { it.bounds.top }
        }
    }

    /**
     * Sort blocks for horizontal text
     * Reading order: Left → Right, Top → Bottom
     */
    private fun sortHorizontal(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.isEmpty()) return blocks

        // Group into rows (top to bottom)
        val rows = mutableListOf<MutableList<OcrBlock>>()
        val sorted = blocks.sortedBy { it.bounds.top }

        for (block in sorted) {
            val blockY = block.bounds.centerY()
            val blockHeight = block.bounds.height()

            // Find row this block belongs to
            val row = rows.find { r ->
                val avgY = r.map { it.bounds.centerY() }.average()
                abs(avgY - blockY) < blockHeight * 0.5f
            }

            if (row != null) {
                row.add(block)
            } else {
                rows.add(mutableListOf(block))
            }
        }

        // Sort blocks within each row (left to right)
        return rows.flatMap { row ->
            row.sortedBy { it.bounds.left }
        }
    }

    /**
     * Merge adjacent blocks in the same region
     */
    private fun mergeAdjacentBlocks(
        blocks: List<OcrBlock>,
        regionId: Int,
        orientation: TextOrientation
    ): List<MergedTextBlock> {
        if (blocks.isEmpty()) return emptyList()
        if (blocks.size == 1) {
            return listOf(blocks.first().toMergedBlock(regionId))
        }

        val merged = mutableListOf<MergedTextBlock>()
        var current = blocks.first().toMergedBlock(regionId)

        for (i in 1 until blocks.size) {
            val next = blocks[i]

            if (areAdjacent(current, next, orientation)) {
                // Merge blocks
                current = MergedTextBlock(
                    text = current.text + "\n" + next.text,
                    bounds = unionBounds(current.bounds, next.bounds),
                    confidence = (current.confidence + next.confidence) / 2,
                    regionId = regionId,
                    lines = current.lines + next.lines
                )
            } else {
                // Start new block
                merged.add(current)
                current = next.toMergedBlock(regionId)
            }
        }

        // Add last block
        merged.add(current)

        return merged
    }

    /**
     * Check if two blocks are adjacent and should be merged
     */
    private fun areAdjacent(
        block1: MergedTextBlock,
        block2: OcrBlock,
        orientation: TextOrientation
    ): Boolean {
        val bounds2 = block2.bounds

        // Check for overlap (should NOT merge)
        if (block1.bounds.intersect(bounds2)) {
            return false
        }

        val avgHeight = (block1.bounds.height() + bounds2.height()) / 2
        val avgWidth = (block1.bounds.width() + bounds2.width()) / 2

        return when (orientation) {
            TextOrientation.VERTICAL -> {
                // Same column: horizontal distance < threshold
                val horizontalDist = abs(block1.centerX() - bounds2.centerX())
                val verticalGap = verticalGapBetween(block1.bounds, bounds2)

                horizontalDist < avgWidth * config.verticalProximityRatio &&
                        verticalGap < avgHeight * config.verticalGapRatio
            }
            TextOrientation.HORIZONTAL -> {
                // Same row: vertical distance < threshold
                val verticalDist = abs(block1.centerY() - bounds2.centerY())
                val horizontalGap = horizontalGapBetween(block1.bounds, bounds2)

                verticalDist < avgHeight * config.horizontalProximityRatio &&
                        horizontalGap < avgWidth * config.horizontalGapRatio
            }
        }
    }

    /**
     * Calculate vertical gap between two rectangles
     */
    private fun verticalGapBetween(rect1: Rect, rect2: Rect): Int {
        return when {
            rect1.bottom < rect2.top -> rect2.top - rect1.bottom
            rect2.bottom < rect1.top -> rect1.top - rect2.bottom
            else -> 0  // Overlapping
        }
    }

    /**
     * Calculate horizontal gap between two rectangles
     */
    private fun horizontalGapBetween(rect1: Rect, rect2: Rect): Int {
        return when {
            rect1.right < rect2.left -> rect2.left - rect1.right
            rect2.right < rect1.left -> rect1.left - rect2.right
            else -> 0  // Overlapping
        }
    }

    /**
     * Calculate union of two rectangles
     */
    private fun unionBounds(rect1: Rect, rect2: Rect): Rect {
        return Rect(
            min(rect1.left, rect2.left),
            min(rect1.top, rect2.top),
            max(rect1.right, rect2.right),
            max(rect1.bottom, rect2.bottom)
        )
    }

    /**
     * Convert OcrBlock to MergedTextBlock
     */
    private fun OcrBlock.toMergedBlock(regionId: Int): MergedTextBlock {
        return MergedTextBlock(
            text = this.text,
            bounds = this.bounds,
            confidence = this.confidence,
            regionId = regionId,
            lines = this.lines
        )
    }

    /**
     * Determine reading order indices for merged blocks
     */
    private fun determineReadingOrder(blocks: List<MergedTextBlock>): List<Int> {
        // Blocks are already in correct order from processing
        // Just return sequential indices
        return blocks.indices.toList()
    }
}
