package com.example.ocrmanga.data.repositories

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.utils.AppLogger
import kotlin.math.abs

object TextBlockBubbleMerger {
    private const val TAG = "TextBlockBubbleMerger"

    fun mergeBlocksByBubble(
        blocks: List<TextBlockInfo>,
        bitmap: Bitmap?,
        isTextColorDifferent: (Int?, Int?) -> Boolean,
        analyzeColorsAndBorder: (Bitmap?, Rect) -> com.example.ocrmanga.ui.screens.view.ColorAnalysisResult,
        detectContainerInfo: (Bitmap?, Rect, Int, Int) -> com.example.ocrmanga.data.ocr.models.TextContainerInfo?
    ): List<TextBlockInfo> {
        if (blocks.isEmpty()) return emptyList()
        val grouped = blocks.groupBy { it.bubbleId ?: -1 }
        val merged = mutableListOf<TextBlockInfo>()
        for ((bubbleId, bubbleBlocks) in grouped) {
            if (bubbleBlocks.size == 1) {
                merged.add(bubbleBlocks.first())
                continue
            }
            val isVertical = bubbleBlocks.first().isVertical
            val sorted = if (isVertical) {
                bubbleBlocks.sortedWith(compareByDescending<TextBlockInfo> { it.bounds.left }.thenBy { it.bounds.top })
            } else {
                bubbleBlocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            }
            val groups = mutableListOf<MutableList<TextBlockInfo>>()
            val threshold = if (isVertical) 0.3 else 0.2
            
            for (block in sorted) {
                var assigned = false
                for (group in groups) {
                    val ref = group.first()
                    if (isTextColorDifferent(block.originalTextColor, ref.originalTextColor)) {
                        continue
                    }
                    if (isVertical) {
                        val leftDiff = abs(block.bounds.left - ref.bounds.left)
                        val avgWidth = (block.bounds.width() + ref.bounds.width()) / 2f
                        
                        val lastBlock = group.last()
                        val verticalGap = block.bounds.top - lastBlock.bounds.bottom
                        val avgHeight = (block.bounds.height() + lastBlock.bounds.height()) / 2f
                        
                        val leftThreshold = avgWidth * threshold
                        val gapThreshold = avgHeight * 1.5f
                        
                        val groupRight = group.maxOf { it.bounds.right }
                        val groupLeft = group.minOf { it.bounds.left }
                        val horizontalGapToGroup = when {
                            block.bounds.right <= groupLeft -> groupLeft - block.bounds.right
                            block.bounds.left >= groupRight -> block.bounds.left - groupRight
                            else -> 0
                        }
                        val groupTop = group.minOf { it.bounds.top }
                        val groupBottom = group.maxOf { it.bounds.bottom }
                        val verticalOverlap = minOf(block.bounds.bottom, groupBottom) - maxOf(block.bounds.top, groupTop)
                        val minBlockHeight = minOf(block.bounds.height(), groupBottom - groupTop).coerceAtLeast(1)
                        val isAdjacentColumn = horizontalGapToGroup <= avgWidth * 0.8f &&
                            verticalOverlap >= minBlockHeight * 0.3f
                        
                        if ((leftDiff < leftThreshold && verticalGap < gapThreshold) || isAdjacentColumn) {
                            group.add(block)
                            assigned = true
                            break
                        }
                    } else {
                        val topDiff = abs(block.bounds.top - ref.bounds.top)
                        val avgHeight = (block.bounds.height() + ref.bounds.height()) / 2f

                        val lastBlock = group.last()
                        val verticalGap = block.bounds.top - lastBlock.bounds.bottom

                        if (topDiff < avgHeight * threshold || (verticalGap > 0 && verticalGap < avgHeight * 1.0f)) {
                            val groupBounds = group.drop(1).fold(Rect(group.first().bounds)) { acc, b ->
                                acc.union(b.bounds)
                                acc
                            }
                            val xOverlap = block.bounds.left <= groupBounds.right && block.bounds.right >= groupBounds.left
                            val avgWidth = (block.bounds.width() + ref.bounds.width()) / 2f
                            val hGap = if (block.bounds.left > groupBounds.right) block.bounds.left - groupBounds.right else 0

                            if (xOverlap || hGap <= avgWidth * 1.5f) {
                                group.add(block)
                                assigned = true
                                break
                            }
                        }
                    }
                }
                if (!assigned) {
                    groups.add(mutableListOf(block))
                }
            }
            
            for (group in groups) {
                if (group.size == 1) {
                    merged.add(group.first())
                } else {
                    val mergedText = group.joinToString(if (isVertical) " " else "\n") { it.text }
                    val mergedBounds = group.drop(1).fold(Rect(group.first().bounds)) { acc, block ->
                        acc.union(block.bounds)
                        acc
                    }
                    val minFontSize = group.minOf { it.fontSize }
                    val firstBlock = group.first()
                    val colorRes = if (bitmap != null) {
                        analyzeColorsAndBorder(bitmap, mergedBounds)
                    } else {
                        com.example.ocrmanga.ui.screens.view.ColorAnalysisResult(
                            firstBlock.backgroundType,
                            firstBlock.averageBackgroundColor,
                            firstBlock.originalTextColor,
                            firstBlock.customBorderColor,
                            firstBlock.borderThickness
                        )
                    }
                    val containerInfo = firstBlock.containerInfo ?: detectContainerInfo(bitmap, mergedBounds, (bitmap?.width ?: 0), (bitmap?.height ?: 0))
                    val mergedOriginalText = group.mapNotNull { it.originalText }.joinToString("\n").ifBlank { mergedText }
                    merged.add(
                        TextBlockInfo(
                            text = mergedText,
                            originalText = mergedOriginalText,
                            bounds = mergedBounds,
                            fontSize = minFontSize,
                            originalFontSize = group.maxOfOrNull { it.originalFontSize ?: it.fontSize } ?: minFontSize,
                            isVertical = isVertical,
                            wordCountsPerLine = null,
                            originalImageWidth = group.first().originalImageWidth,
                            originalImageHeight = group.first().originalImageHeight,
                            bubbleId = bubbleId,
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
        val hasVertical = merged.any { it.isVertical }
        return if (hasVertical) {
            val sortedByTop = merged.sortedBy { it.bounds.top }
            if (sortedByTop.isEmpty()) return merged
            
            val bands = mutableListOf<MutableList<TextBlockInfo>>()
            var currentBand = mutableListOf(sortedByTop.first())
            var bandBottom = sortedByTop.first().bounds.bottom
            
            for (block in sortedByTop.drop(1)) {
                val overlapWithBand = block.bounds.top < bandBottom
                val avgHeight = (block.bounds.height() + currentBand.last().bounds.height()) / 2
                val closeEnough = (block.bounds.top - bandBottom) < avgHeight
                
                if (overlapWithBand || closeEnough) {
                    currentBand.add(block)
                    bandBottom = maxOf(bandBottom, block.bounds.bottom)
                } else {
                    bands.add(currentBand)
                    currentBand = mutableListOf(block)
                    bandBottom = block.bounds.bottom
                }
            }
            if (currentBand.isNotEmpty()) bands.add(currentBand)

            bands.flatMap { band ->
                band.sortedByDescending { it.bounds.left }
            }.map { it.copy(isVertical = false) }
        } else {
            merged
        }
    }
}
