package com.example.ocrmanga.data.repositories

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.ocrmanga.data.models.TextBlockInfo
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object TextBlockSorter {

    fun sortHorizontalTextBlocks(
        textBlocks: List<TextBlockInfo>,
        bitmap: Bitmap?,
        isTextColorDifferent: (Int?, Int?) -> Boolean,
        analyzeColorsAndBorder: (Bitmap?, Rect) -> com.example.ocrmanga.ui.screens.view.ColorAnalysisResult,
        detectContainerInfo: (Bitmap?, Rect, Int, Int) -> com.example.ocrmanga.data.ocr.models.TextContainerInfo?
    ): List<TextBlockInfo> {
        if (textBlocks.isEmpty()) return emptyList()

        val sortedByTopThenLeft = textBlocks.sortedWith(
            compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left }
        )

        val topValues = sortedByTopThenLeft.map { it.bounds.top }
        val topGaps = topValues.zipWithNext { a, b -> b - a }.filter { it > 0 }
        val avgTopGap = if (topGaps.isNotEmpty()) topGaps.average().toInt() else 50
        val verticalThreshold = (avgTopGap * 0.4).toInt().coerceAtLeast(15)

        val avgFontSize = textBlocks.map { it.fontSize }.average().toFloat()
        val verticalProximityThreshold = minOf(avgTopGap, (avgFontSize * 1.0f).toInt()).coerceAtLeast(25)

        val rows = mutableListOf<MutableList<TextBlockInfo>>()
        var currentRow = mutableListOf<TextBlockInfo>()
        var lastTop = sortedByTopThenLeft.first().bounds.top

        for (block in sortedByTopThenLeft) {
            val currentTop = block.bounds.top
            val isColorDiff = isTextColorDifferent(block.originalTextColor, currentRow.firstOrNull()?.originalTextColor)
            if (currentTop - lastTop <= verticalThreshold && !isColorDiff) {
                currentRow.add(block)
            } else {
                if (currentRow.isNotEmpty()) {
                    rows.add(currentRow.sortedBy { it.bounds.left }.toMutableList())
                }
                currentRow = mutableListOf(block)
            }
            lastTop = currentTop
        }
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow.sortedBy { it.bounds.left }.toMutableList())
        }

        val clusters = mutableListOf<MutableList<TextBlockInfo>>()
        sortedByTopThenLeft.forEach { block ->
            var assigned = false
            for (cluster in clusters) {
                if (cluster.any { other ->
                        val xOverlap = block.bounds.left <= other.bounds.right && other.bounds.left <= block.bounds.right
                        val yOverlap = block.bounds.top <= other.bounds.bottom && other.bounds.top <= block.bounds.bottom
                        val yDistance = if (block.bounds.top > other.bounds.bottom) {
                            block.bounds.top - other.bounds.bottom
                        } else {
                            other.bounds.top - block.bounds.bottom
                        }
                        val yCloseEnough = yDistance <= verticalProximityThreshold
                        val isColorDiff = isTextColorDifferent(block.originalTextColor, other.originalTextColor)
                        xOverlap && (yOverlap || yCloseEnough) && !isColorDiff
                    }) {
                    cluster.add(block)
                    assigned = true
                    break
                }
            }
            if (!assigned) {
                clusters.add(mutableListOf(block))
            }
        }

        val secondMergeClusters = mutableListOf<MutableList<TextBlockInfo>>()
        val processedClusters = mutableSetOf<Int>()
        clusters.forEachIndexed { index, cluster ->
            if (index in processedClusters) return@forEachIndexed

            val mergedCluster = mutableListOf<TextBlockInfo>().apply { addAll(cluster) }
            processedClusters.add(index)

            for (otherIndex in (index + 1) until clusters.size) {
                if (otherIndex in processedClusters) continue

                val otherCluster = clusters[otherIndex]
                val clusterBottom = cluster.maxOf { it.bounds.bottom }
                val clusterTop = cluster.minOf { it.bounds.top }
                val otherTop = otherCluster.minOf { it.bounds.top }
                val otherBottom = otherCluster.maxOf { it.bounds.bottom }

                val verticalGap = if (otherTop > clusterBottom) {
                    otherTop - clusterBottom
                } else {
                    clusterTop - otherBottom
                }

                val clusterLeft = cluster.minOf { it.bounds.left }
                val clusterRight = cluster.maxOf { it.bounds.right }
                val otherLeft = otherCluster.minOf { it.bounds.left }
                val otherRight = otherCluster.maxOf { it.bounds.right }
                val horizontalOverlap = max(0, min(clusterRight, otherRight) - max(clusterLeft, otherLeft))
                val clusterWidth = clusterRight - clusterLeft
                val otherWidth = otherRight - otherLeft
                val overlapRatio = if (clusterWidth > 0 && otherWidth > 0) {
                    horizontalOverlap.toFloat() / min(clusterWidth, otherWidth)
                } else 0f

                val avgFontSizeVal = cluster.map { it.fontSize }.average().toFloat()
                val maxVerticalGap = (avgFontSizeVal * 1.5f).coerceAtMost(60f)

                val isCloseEnough = verticalGap <= maxVerticalGap && overlapRatio > 0.3f
                val isColorDiff = isTextColorDifferent(otherCluster.firstOrNull()?.originalTextColor, cluster.firstOrNull()?.originalTextColor)

                if (isCloseEnough && !isColorDiff) {
                    val clusterText = cluster.joinToString(" ") { it.text }
                    val otherText = otherCluster.joinToString(" ") { it.text }
                    val combinedText = "$clusterText $otherText"
                    if (isTextCoherent(combinedText)) {
                        mergedCluster.addAll(otherCluster)
                        processedClusters.add(otherIndex)
                    }
                }
            }
            secondMergeClusters.add(mergedCluster)
        }

        val mergedBlocks = mutableListOf<TextBlockInfo>()
        secondMergeClusters.forEachIndexed { clusterIndex, clusterBlocks ->
            if (clusterBlocks.isEmpty()) return@forEachIndexed

            val sortedBlocks = clusterBlocks.sortedWith(
                compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left }
            )
            val mergedText = StringBuilder()
            lateinit var mergedBounds: Rect
            var minFontSize = Float.MAX_VALUE
            var blockCount = 0

            sortedBlocks.forEach { block ->
                if (mergedText.isNotEmpty()) {
                    mergedText.append(" ")
                }
                mergedText.append(block.text)
                if (blockCount == 0) {
                    mergedBounds = Rect(block.bounds)
                } else {
                    mergedBounds.set(
                        min(mergedBounds.left, block.bounds.left),
                        min(mergedBounds.top, block.bounds.top),
                        max(mergedBounds.right, block.bounds.right),
                        max(mergedBounds.bottom, block.bounds.bottom)
                    )
                }
                minFontSize = min(minFontSize, block.fontSize)
                blockCount++
            }

            val colorRes = analyzeColorsAndBorder(bitmap, mergedBounds)
            val containerInfo = detectContainerInfo(bitmap, mergedBounds, (bitmap?.width ?: 0), (bitmap?.height ?: 0))

            val mergedOriginalText = sortedBlocks.joinToString("\n") { it.originalText ?: it.text }
            val mergedBlock = TextBlockInfo(
                text = mergedText.toString(),
                originalText = mergedOriginalText,
                bounds = Rect(mergedBounds),
                fontSize = minFontSize,
                originalFontSize = sortedBlocks.maxOfOrNull { it.originalFontSize ?: it.fontSize } ?: minFontSize,
                wordCountsPerLine = null,
                originalImageWidth = sortedBlocks.firstOrNull()?.originalImageWidth,
                originalImageHeight = sortedBlocks.firstOrNull()?.originalImageHeight,
                backgroundType = colorRes.backgroundType,
                averageBackgroundColor = colorRes.backgroundColor,
                originalTextColor = colorRes.textColor,
                customBorderColor = colorRes.borderColor,
                borderThickness = colorRes.borderThickness,
                containerInfo = containerInfo
            )

            mergedBlocks.add(mergedBlock)
        }

        return mergedBlocks.sortedWith(
            compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left }
        )
    }

    fun sortVerticalTextBlocks(
        textBlocks: List<TextBlockInfo>,
        bitmap: Bitmap?,
        isTextColorDifferent: (Int?, Int?) -> Boolean,
        analyzeColorsAndBorder: (Bitmap?, Rect) -> com.example.ocrmanga.ui.screens.view.ColorAnalysisResult,
        detectContainerInfo: (Bitmap?, Rect, Int, Int) -> com.example.ocrmanga.data.ocr.models.TextContainerInfo?
    ): List<TextBlockInfo> {
        if (textBlocks.isEmpty()) return emptyList()

        val sortedByLeft = textBlocks.sortedBy { it.bounds.left }
        val avgBlockWidth = sortedByLeft.map { it.bounds.width() }.average().toFloat().coerceAtLeast(10f)
        val interColumnGaps = sortedByLeft.zipWithNext { a, b ->
            (b.bounds.left - a.bounds.right).coerceAtLeast(0)
        }.filter { it >= 0 }
        
        val medianInterGap = if (interColumnGaps.isNotEmpty()) {
            val sorted = interColumnGaps.sorted()
            if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else 0
        val horizontalThreshold = (medianInterGap + avgBlockWidth).toInt().coerceAtLeast(20).coerceAtMost((avgBlockWidth * 2).toInt())

        val columns = mutableListOf<MutableList<TextBlockInfo>>()
        var currentColumn = mutableListOf(sortedByLeft.first())
        var lastRight = sortedByLeft.first().bounds.right

        for (block in sortedByLeft.drop(1)) {
            val interGap = (block.bounds.left - lastRight).coerceAtLeast(0)
            val isColorDiff = isTextColorDifferent(block.originalTextColor, currentColumn.first().originalTextColor)
            if (interGap <= horizontalThreshold && !isColorDiff) {
                currentColumn.add(block)
            } else {
                columns.add(currentColumn)
                currentColumn = mutableListOf(block)
            }
            lastRight = max(lastRight, block.bounds.right)
        }
        if (currentColumn.isNotEmpty()) {
            columns.add(currentColumn)
        }

        val mergedBlocks = mutableListOf<TextBlockInfo>()
        columns.forEach { columnBlocks ->
            val sortedByTop = columnBlocks.sortedBy { it.bounds.top }
            val topValues = sortedByTop.map { it.bounds.top }
            val topGaps = topValues.zipWithNext { a, b -> b - a }.filter { it > 0 }
            val medianTopGap = if (topGaps.isNotEmpty()) {
                val sorted = topGaps.sorted()
                if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            } else 100
            val avgBlockHeight = sortedByTop.map { it.bounds.height() }.average().toInt().coerceAtLeast(20)
            val verticalThreshold = (medianTopGap * 1.2).toInt().coerceAtLeast(50).coerceAtMost(avgBlockHeight * 2)

            val regions = mutableListOf<MutableList<TextBlockInfo>>()
            var currentRegion = mutableListOf(sortedByTop.first())
            var lastTop = sortedByTop.first().bounds.top
            var regionLeft = sortedByTop.first().bounds.left
            var regionTop = sortedByTop.first().bounds.top
            var regionRight = sortedByTop.first().bounds.right
            var regionBottom = sortedByTop.first().bounds.bottom

            for (block in sortedByTop.drop(1)) {
                val currentTop = block.bounds.top
                val overlapLeft = max(block.bounds.left, regionLeft)
                val overlapTop = max(block.bounds.top, regionTop)
                val overlapRight = min(block.bounds.right, regionRight)
                val overlapBottom = min(block.bounds.bottom, regionBottom)
                val overlapArea = if (overlapRight > overlapLeft && overlapBottom > overlapTop) {
                    (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
                } else 0
                val blockArea = block.bounds.width() * block.bounds.height()
                val regionArea = (regionRight - regionLeft) * (regionBottom - regionTop)
                val smallerArea = min(blockArea, regionArea).coerceAtLeast(1)
                val overlapRatio = overlapArea.toFloat() / smallerArea

                val isColorDiff = isTextColorDifferent(block.originalTextColor, currentRegion.first().originalTextColor)
                if ((currentTop - lastTop <= verticalThreshold || overlapRatio > 0.3f) && !isColorDiff) {
                    currentRegion.add(block)
                } else {
                    regions.add(currentRegion)
                    currentRegion = mutableListOf(block)
                    regionLeft = block.bounds.left
                    regionTop = block.bounds.top
                    regionRight = block.bounds.right
                    regionBottom = block.bounds.bottom
                }
                regionLeft = min(regionLeft, block.bounds.left)
                regionTop = min(regionTop, block.bounds.top)
                regionRight = max(regionRight, block.bounds.right)
                regionBottom = max(regionBottom, block.bounds.bottom)
                lastTop = currentTop
            }
            if (currentRegion.isNotEmpty()) {
                regions.add(currentRegion)
            }

            regions.forEach { regionBlocks ->
                val sortedBlocks = regionBlocks.sortedWith(
                    compareByDescending<TextBlockInfo> { it.bounds.left }
                        .thenBy { it.bounds.top }
                )

                val subGroups = mutableListOf<MutableList<TextBlockInfo>>()
                var currentSubGroup = mutableListOf(sortedBlocks.first())
                var subGroupMinLeft = sortedBlocks.first().bounds.left
                var subGroupMaxRight = sortedBlocks.first().bounds.right
                var subGroupMinTop = sortedBlocks.first().bounds.top
                var subGroupMaxBottom = sortedBlocks.first().bounds.bottom
                
                for (idx in 1 until sortedBlocks.size) {
                    val prev = sortedBlocks[idx - 1]
                    val curr = sortedBlocks[idx]
                    val colGap = (prev.bounds.left - curr.bounds.right).toFloat().coerceAtLeast(0f)
                    val gapToSubGroup = (subGroupMinLeft - curr.bounds.right).toFloat().coerceAtLeast(0f)
                    val noXOverlapWithSubGroup = curr.bounds.right <= subGroupMinLeft
                    val separateColumnThreshold = (avgBlockWidth * 0.5f).coerceAtLeast(20f)
                    val isSeparateColumn = noXOverlapWithSubGroup && gapToSubGroup > separateColumnThreshold
                    
                    val noHorizontalOverlap = curr.bounds.right <= subGroupMinLeft
                    val startsAtOrBelowSubGroup = curr.bounds.top >= subGroupMaxBottom - 15
                    val isDiagonallyStacked = noHorizontalOverlap && startsAtOrBelowSubGroup
                    
                    val hasSameColumnOverlap = curr.bounds.right > subGroupMinLeft && curr.bounds.left < subGroupMaxRight
                    val actualVerticalGap = when {
                        curr.bounds.bottom <= subGroupMinTop -> subGroupMinTop - curr.bounds.bottom
                        curr.bounds.top >= subGroupMaxBottom -> curr.bounds.top - subGroupMaxBottom
                        else -> 0
                    }
                    val isLargeVerticalGapSameColumn = hasSameColumnOverlap && actualVerticalGap > avgBlockHeight * 0.8f
                    val isColorDiff = isTextColorDifferent(curr.originalTextColor, currentSubGroup.first().originalTextColor)
                    
                    if (colGap > subGroupThreshold(avgBlockWidth) || isSeparateColumn || isDiagonallyStacked || isLargeVerticalGapSameColumn || isColorDiff) {
                        subGroups.add(currentSubGroup)
                        currentSubGroup = mutableListOf(curr)
                        subGroupMinLeft = curr.bounds.left
                        subGroupMaxRight = curr.bounds.right
                        subGroupMinTop = curr.bounds.top
                        subGroupMaxBottom = curr.bounds.bottom
                    } else {
                        currentSubGroup.add(curr)
                        subGroupMinLeft = min(subGroupMinLeft, curr.bounds.left)
                        subGroupMaxRight = max(subGroupMaxRight, curr.bounds.right)
                        subGroupMinTop = min(subGroupMinTop, curr.bounds.top)
                        subGroupMaxBottom = max(subGroupMaxBottom, curr.bounds.bottom)
                    }
                }
                if (currentSubGroup.isNotEmpty()) {
                    subGroups.add(currentSubGroup)
                }

                subGroups.forEach { subGroup ->
                    val finalSorted = subGroup.sortedBy { it.bounds.top }
                    val mergedText = StringBuilder()
                    lateinit var mergedBounds: Rect
                    var minFontSize = Float.MAX_VALUE
                    var blockCount = 0

                    finalSorted.forEach { block ->
                        if (mergedText.isNotEmpty()) {
                            mergedText.append("\n")
                        }
                        mergedText.append(block.text)
                        if (blockCount == 0) {
                            mergedBounds = Rect(block.bounds)
                        } else {
                            mergedBounds.set(
                                min(mergedBounds.left, block.bounds.left),
                                min(mergedBounds.top, block.bounds.top),
                                max(mergedBounds.right, block.bounds.right),
                                max(mergedBounds.bottom, block.bounds.bottom)
                            )
                        }
                        minFontSize = min(minFontSize, block.fontSize)
                        blockCount++
                    }

                    val colorRes = analyzeColorsAndBorder(bitmap, mergedBounds)
                    val containerInfo = detectContainerInfo(bitmap, mergedBounds, (bitmap?.width ?: 0), (bitmap?.height ?: 0))

                    val mergedOriginalText = finalSorted.joinToString("\n") { it.originalText ?: it.text }
                    mergedBlocks.add(
                        TextBlockInfo(
                            text = mergedText.toString(),
                            originalText = mergedOriginalText,
                            bounds = Rect(mergedBounds),
                            fontSize = minFontSize,
                            originalFontSize = finalSorted.maxOfOrNull { it.originalFontSize ?: it.fontSize } ?: minFontSize,
                            wordCountsPerLine = null,
                            originalImageWidth = finalSorted.firstOrNull()?.originalImageWidth,
                            originalImageHeight = finalSorted.firstOrNull()?.originalImageHeight,
                            isVertical = true,
                            backgroundType = colorRes.backgroundType,
                            averageBackgroundColor = colorRes.backgroundColor,
                            originalTextColor = colorRes.textColor,
                            customBorderColor = colorRes.borderColor,
                            borderThickness = colorRes.borderThickness,
                            containerInfo = containerInfo
                        )
                    )
                }
            }
        }

        return mergedBlocks.sortedWith(
            compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left }
        )
    }

    private fun subGroupThreshold(avgBlockWidth: Float): Float {
        return avgBlockWidth * 1.5f
    }

    private fun isTextCoherent(text: String): Boolean {
        return true
    }
}
