package com.example.ocrmanga.utils

import androidx.compose.ui.geometry.Rect
import com.example.ocrmanga.data.models.TextBlockInfo
import kotlin.math.max
import kotlin.math.min

/**
 * Hợp nhất các vùng bôi trắng bị chồng lên nhau.
 * Nếu 2 vùng overlap, vùng nhỏ hơn sẽ được hợp nhất vào vùng lớn hơn,
 * bản dịch của vùng nhỏ sẽ xếp sau bản dịch của vùng lớn.
 */
fun mergeOverlappingRegions(
    regions: List<Triple<TextBlockInfo, Rect, Float>>,
    imageWidth: Float
): List<Triple<TextBlockInfo, Rect, Float>> {
    if (regions.isEmpty()) return emptyList()

    val sortedRegions = regions.sortedByDescending { it.second.width * it.second.height }.toMutableList()
    val merged = BooleanArray(sortedRegions.size) { false }
    val result = mutableListOf<Triple<TextBlockInfo, Rect, Float>>()

    fun isOverlap(a: Rect, b: Rect): Boolean {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    }

    for (i in sortedRegions.indices) {
        if (merged[i]) continue
        var (blockA, rectA, fontA) = sortedRegions[i]
        var mergedText = blockA.text
        var mergedRect = rectA
        var mergedFont = fontA

        for (j in i + 1 until sortedRegions.size) {
            if (merged[j]) continue
            val (blockB, rectB, fontB) = sortedRegions[j]
            if (isOverlap(mergedRect, rectB)) {
                // Hợp nhất vùng nhỏ vào vùng lớn (vùng lớn là mergedRect)
                mergedRect = Rect(
                    min(mergedRect.left, rectB.left),
                    min(mergedRect.top, rectB.top),
                    max(mergedRect.right, rectB.right),
                    max(mergedRect.bottom, rectB.bottom)
                )
                // Ghép text: text vùng lớn + text vùng nhỏ (sau)
                mergedText = mergedText + "\n" + blockB.text
                mergedFont = max(mergedFont, fontB)
                merged[j] = true
            }
        }
        result.add(
            Triple(
                blockA.copy(
                    text = mergedText,
                    bounds = android.graphics.Rect(
                        mergedRect.left.toInt(),
                        mergedRect.top.toInt(),
                        mergedRect.right.toInt(),
                        mergedRect.bottom.toInt()
                    ),
                    fontSize = mergedFont
                ),
                mergedRect,
                mergedFont
            )
        )
    }
    return result
}