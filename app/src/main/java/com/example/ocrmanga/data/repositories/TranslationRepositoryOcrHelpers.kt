package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.ui.screens.view.analyzeColorsAndBorder
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

open class TranslationRepositoryOcrHelpers(application: Application) : TranslationRepositoryOcrOrientation(application) {

    protected fun assignSpeechBubblesToBlocks(textBlocks: List<TextBlockInfo>): List<TextBlockInfo> {
        if (textBlocks.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<TextBlockInfo>>()
        // Tăng threshold và IoU để tách các bubble riêng biệt tốt hơn
        val iouThreshold = 0.4f
        fun iou(a: Rect, b: Rect): Float {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val right = minOf(a.right, b.right)
            val bottom = minOf(a.bottom, b.bottom)
            val intersection = maxOf(0, right - left) * maxOf(0, bottom - top)
            val union = a.width() * a.height() + b.width() * b.height() - intersection
            return if (union > 0) intersection.toFloat() / union else 0f
        }
        fun verticalOverlap(a: Rect, b: Rect): Int {
            val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            return maxOf(0, overlap)
        }
        fun isVerticalOverlapEnough(a: Rect, b: Rect): Boolean {
            val overlap = verticalOverlap(a, b)
            val minHeight = minOf(a.height(), b.height())
            return overlap >= minHeight / 2 // tăng yêu cầu overlap dọc
        }
        fun isTooFarVertical(a: Rect, b: Rect): Boolean {
            val aHeight = a.height()
            val bHeight = b.height()
            val verticalGap1 = b.top - a.bottom
            val verticalGap2 = a.top - b.bottom
            return (verticalGap1 > aHeight / 2) || (verticalGap2 > bHeight / 2)
        }
        textBlocks.forEach { block ->
            var assigned = false
            for (cluster in clusters) {
                val last = cluster.last()
                val isColorDiff = isTextColorDifferent(block.originalTextColor, last.originalTextColor)
                if (isColorDiff) {
                    continue
                }
                // Tính khoảng trắng ngang thực sự giữa block và last
                val hGap = when {
                    block.bounds.right <= last.bounds.left -> last.bounds.left - block.bounds.right
                    block.bounds.left >= last.bounds.right -> block.bounds.left - last.bounds.right
                    else -> 0
                }
                val vOverlap = verticalOverlap(block.bounds, last.bounds)
                val minHgt = minOf(block.bounds.height(), last.bounds.height()).coerceAtLeast(1)
                // Các cột dọc liền kề trong cùng speech bubble có x-gap nhỏ (< 15px) và
                // overlap dọc lớn. IoU = 0 vì không chồng x-range → cần check riêng.
                // Cap 15px để không nhầm với các bubble khác nhau (gap thường ≥ 30px).
                val isHorizontallyAdjacentColumn = hGap <= 15 && vOverlap >= minHgt * 0.3f
                if ((iou(block.bounds, last.bounds) > iouThreshold && isVerticalOverlapEnough(block.bounds, last.bounds) && !isTooFarVertical(block.bounds, last.bounds)) || isHorizontallyAdjacentColumn) {
                    cluster.add(block)
                    assigned = true
                    break
                }
            }
            if (!assigned) clusters.add(mutableListOf(block))
        }
        // Với mỗi cluster, tính bounding box bao ngoài (bubbleBounds)
        val clusterBounds = clusters.map { cluster ->
            cluster.fold(Rect(cluster[0].bounds)) { acc, block ->
                acc.union(block.bounds)
                acc
            }
        }
        // Gán bubbleId cho từng block
        val result = mutableListOf<TextBlockInfo>()
        clusters.forEachIndexed { idx, cluster ->
            val bubble = clusterBounds[idx]
            cluster.forEach { block ->
                result.add(block.copy(
                    polygon = null,
                    bounds = block.bounds,
                    fontSize = block.fontSize,
                    isVertical = block.isVertical,
                    wordCountsPerLine = block.wordCountsPerLine,
                    originalImageWidth = block.originalImageWidth,
                    originalImageHeight = block.originalImageHeight,
                    bubbleId = idx // Gán bubbleId
                ))
            }
        }
        return result
    }

    protected fun groupBlocksIntoClusters(textBlocks: List<TextBlockInfo>): List<List<TextBlockInfo>> {
        val clusters = mutableListOf<MutableList<TextBlockInfo>>()
        // Giảm threshold để tránh merge nhầm các cụm xa nhau
        val verticalThreshold = 35
        val horizontalThreshold = 60

        textBlocks.forEach { block ->
            var assigned = false
            for (cluster in clusters) {
                if (cluster.any { other ->
                        val xDistance = minOf(
                            abs(block.bounds.left - other.bounds.right),
                            abs(other.bounds.left - block.bounds.right)
                        )
                        val yDistance = minOf(
                            abs(block.bounds.top - other.bounds.bottom),
                            abs(other.bounds.top - block.bounds.bottom)
                        )
                        val isColorDiff = isTextColorDifferent(block.originalTextColor, other.originalTextColor)
                        xDistance <= horizontalThreshold && yDistance <= verticalThreshold && !isColorDiff
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
        return clusters
    }

    protected fun sortHorizontalTextBlocks(textBlocks: List<TextBlockInfo>, bitmap: Bitmap?): List<TextBlockInfo> {
        return TextBlockSorter.sortHorizontalTextBlocks(
            textBlocks = textBlocks,
            bitmap = bitmap,
            isTextColorDifferent = ::isTextColorDifferent,
            analyzeColorsAndBorder = ::analyzeColorsAndBorder,
            detectContainerInfo = ::detectContainerInfo
        )
    }
    protected fun sortVerticalTextBlocks(textBlocks: List<TextBlockInfo>, bitmap: Bitmap?): List<TextBlockInfo> {
        return TextBlockSorter.sortVerticalTextBlocks(
            textBlocks = textBlocks,
            bitmap = bitmap,
            isTextColorDifferent = ::isTextColorDifferent,
            analyzeColorsAndBorder = ::analyzeColorsAndBorder,
            detectContainerInfo = ::detectContainerInfo
        )
    }
    protected fun determineTextOrientation(textBlocks: List<TextBlockInfo>, fullText: String): Boolean {
        val sampleText = fullText.take(100)
        val chinesePattern = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF]")
        val japanesePattern = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
        val koreanPattern = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]")

        val isChineseJapaneseOrKorean = chinesePattern.containsMatchIn(sampleText) ||
                japanesePattern.containsMatchIn(sampleText) ||
                koreanPattern.containsMatchIn(sampleText)

        if (isChineseJapaneseOrKorean && textBlocks.isNotEmpty()) {
            val verticalCount = textBlocks.count { block ->
                val bounds = block.bounds
                bounds.height() > bounds.width() * 1.5
            }
            val totalBlocks = textBlocks.size
            return verticalCount > totalBlocks * 0.6
        }
        return false
    }

    protected fun adjustBoundsForTranslatedText(text: String, originalBounds: Rect, fontSize: Float, scaleFactor: Float): Rect {
        val charWidthEstimate = fontSize * 0.6f
        val textWidth = (text.length * charWidthEstimate).toInt()
        val left = originalBounds.left
        val top = originalBounds.top
        val right = (left + textWidth).coerceAtMost(originalBounds.right)
        val height = originalBounds.height().coerceAtLeast(fontSize.toInt())
        val bottom = top + height
        return Rect(left, top, right, bottom)
    }

    protected fun calculateAdjustedFontSize(
        translatedText: String,
        originalText: String,
        originalBounds: Rect,
        originalFontSize: Float,
        isVertical: Boolean = false
    ): Float {
        // Tính số dòng trong văn bản dịch
        val translatedLines = translatedText.split("\n")
        val originalLines = originalText.split("\n")
        
        val availableWidth = originalBounds.width().toFloat()
        val availableHeight = originalBounds.height().toFloat()
        
        // Xử lý văn bản DỌC (vertical)
        if (isVertical) {
            // Đối với văn bản dọc:
            // - Số lượng ký tự (độ dài văn bản) ảnh hưởng đến HEIGHT (chiều cao)
            // - Chiều rộng thường chỉ có 1 ký tự
            
            // Loại bỏ ký tự xuống dòng vì chúng không được hiển thị trong vertical text
            val translatedCharsNoNewline = translatedText.replace("\n", "").length
            val originalCharsNoNewline = originalText.replace("\n", "").length
            
            // Tính tỷ lệ số ký tự
            val charRatio = if (originalCharsNoNewline > 0) {
                translatedCharsNoNewline.toFloat() / originalCharsNoNewline
            } else {
                1.0f
            }
            
            // Tính fontSize dựa trên HEIGHT (số ký tự chồng lên nhau)
            // line spacing = 1.15 cho vertical text (conservative để đảm bảo vừa)
            val lineSpacing = 1.1f
            val estimatedHeight = translatedCharsNoNewline * originalFontSize * lineSpacing

            val heightScale = if (estimatedHeight > availableHeight) {
                availableHeight / estimatedHeight
            } else {
                1.0f
            }
            
            
            // Đối với vertical, chiều rộng ít khi là vấn đề (thường chỉ 1 ký tự)
            // Nhưng vẫn cần kiểm tra xem fontSize có quá lớn không
            val charWidthEstimate = originalFontSize * 0.5f
            val estimatedWidth = charWidthEstimate // 1 ký tự trên mỗi "dòng"
            val widthScale = if (estimatedWidth > availableWidth) {
                availableWidth / estimatedWidth
            } else {
                1.0f
            }
            
            // Chọn scale nhỏ hơn để đảm bảo vừa
            val finalScale = minOf(widthScale, heightScale, 1.0f)
            
            // Tăng giới hạn tối thiểu fontSize lên 30% fontSize gốc cho vertical để text không quá to
            val minFontSize = maxOf(originalFontSize * 0.3f, 6f)
            val newFontSize = (originalFontSize * finalScale).coerceAtLeast(minFontSize)
            
            return newFontSize
        }
        
        // Xử lý văn bản NGANG (horizontal) - logic cũ
        // Tính độ dài trung bình mỗi dòng
        val avgTranslatedLineLength = if (translatedLines.isNotEmpty()) {
            translatedLines.sumOf { it.length }.toFloat() / translatedLines.size
        } else {
            translatedText.length.toFloat()
        }
        
        val avgOriginalLineLength = if (originalLines.isNotEmpty()) {
            originalLines.sumOf { it.length }.toFloat() / originalLines.size
        } else {
            originalText.length.toFloat()
        }
        
        // Tính tỷ lệ độ dài văn bản
        val lengthRatio = if (avgOriginalLineLength > 0) {
            avgTranslatedLineLength / avgOriginalLineLength
        } else {
            1.0f
        }
        
        // Tính tỷ lệ số dòng
        val lineRatio = if (originalLines.size > 0) {
            translatedLines.size.toFloat() / originalLines.size
        } else {
            1.0f
        }
        
        // Tính fontSize dựa trên chiều rộng
        val charWidthEstimate = originalFontSize * 0.6f
        val estimatedWidth = avgTranslatedLineLength * charWidthEstimate
        val widthScale = if (estimatedWidth > availableWidth) {
            availableWidth / estimatedWidth
        } else {
            1.0f
        }
        
        // Tính fontSize dựa trên chiều cao (số dòng)
        val estimatedHeight = translatedLines.size * originalFontSize * 1.1f // 1.2 là line spacing
        val heightScale = if (estimatedHeight > availableHeight) {
            availableHeight / estimatedHeight
        } else {
            1.0f
        }
        
        // Chọn scale nhỏ hơn để đảm bảo vừa cả width và height
        val finalScale = minOf(widthScale, heightScale, 1.0f)
        
    // Giới hạn tối thiểu fontSize bằng 30% fontSize gốc (nhưng không dưới 6f) để text không bị quá nhỏ mà vẫn vừa khung
    val minFontSize = maxOf(originalFontSize * 0.3f, 6f)
    val newFontSize = (originalFontSize * finalScale).coerceAtLeast(minFontSize)
//        Log.i("TranslationRepository", "[FONT-ADJUST-HORIZONTAL] Original: '${originalText.take(30)}...', " +
//            "Translated: '${translatedText.take(30)}...', " +
//            "lengthRatio=$lengthRatio, lineRatio=$lineRatio, " +
//            "widthScale=$widthScale, heightScale=$heightScale, " +
//            "originalFontSize=$originalFontSize, newFontSize=$newFontSize (min: $minFontSize)")
    return newFontSize
    }

    // Merge các block theo bubbleId, chỉ merge block thực sự cùng dòng (ngang) hoặc cùng cột (dọc) trong từng bubble
    protected fun mergeBlocksByBubble(blocks: List<TextBlockInfo>, bitmap: Bitmap?): List<TextBlockInfo> {
        return TextBlockBubbleMerger.mergeBlocksByBubble(
            blocks = blocks,
            bitmap = bitmap,
            isTextColorDifferent = ::isTextColorDifferent,
            analyzeColorsAndBorder = ::analyzeColorsAndBorder,
            detectContainerInfo = ::detectContainerInfo
        )
    }

    protected fun isTextColorDifferent(color1: Int?, color2: Int?): Boolean {
        if (color1 == color2) return false
        if (color1 == null || color2 == null) return false

        val r1 = (color1 shr 16) and 0xFF
        val g1 = (color1 shr 8) and 0xFF
        val b1 = color1 and 0xFF

        val r2 = (color2 shr 16) and 0xFF
        val g2 = (color2 shr 8) and 0xFF
        val b2 = color2 and 0xFF

        val max1 = maxOf(r1, g1, b1)
        val min1 = minOf(r1, g1, b1)
        val max2 = maxOf(r2, g2, b2)
        val min2 = minOf(r2, g2, b2)
        val isChrom1 = (max1 - min1) > 35
        val isChrom2 = (max2 - min2) > 35

        val dist = kotlin.math.sqrt(
            ((r1 - r2) * (r1 - r2) +
                (g1 - g2) * (g1 - g2) +
                (b1 - b2) * (b1 - b2)).toDouble()
        )

        if (isChrom1 != isChrom2) {
            return dist > 60.0
        }
        return dist > 75.0
    }
}
