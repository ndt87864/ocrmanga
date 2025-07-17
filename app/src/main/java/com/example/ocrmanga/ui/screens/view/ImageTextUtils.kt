package com.example.ocrmanga.ui.screens.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import com.example.ocrmanga.data.models.TextBlockInfo
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

// Image and text region utilities extracted from ViewerScreen.kt

fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) {
            return options.outWidth to options.outHeight
        }
        throw IOException("Kích thước ảnh không hợp lệ")
    } catch (e: Exception) {
        Log.e("ViewerScreen", "Error getting image dimensions for $uri", e)
        return 1280 to 1808
    }
}

fun mergeOverlappingRegions(
    regions: List<Triple<TextBlockInfo, Rect, Float>>,
    imageWidth: Float
): List<Triple<TextBlockInfo, Rect, Float>> {
    if (regions.isEmpty()) return emptyList()

    val sortedRegions = regions.sortedWith { (blockA, rectA, _), (blockB, rectB, _) ->
        if (blockA.isVertical == blockB.isVertical && blockA.isVertical) {
            compareValuesBy(rectB, rectA, { it.right }, { it.top })
        } else {
            compareValuesBy(rectA, rectB, { it.top }, { it.left })
        }
    }.toMutableList()

    val resultRegions = mutableListOf<Triple<TextBlockInfo, Rect, Float>>()
    val processed = BooleanArray(sortedRegions.size) { false }

    fun isIntersect(a: Rect, b: Rect): Boolean {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    }

    fun isIntersectOrSameLine(a: Rect, b: Rect, isVertical: Boolean): Boolean {
        if (isVertical) {
            return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
        } else {
            val verticalOverlap = a.top < b.bottom && a.bottom > b.top
            val topDiff = kotlin.math.abs(a.top - b.top)
            val avgHeight = ((a.height + b.height) / 2f).coerceAtLeast(1f)
            val sameLine = topDiff < avgHeight * 0.2f
            return (a.left < b.right && a.right > b.left && verticalOverlap) || sameLine
        }
    }

    for (i in sortedRegions.indices) {
        if (processed[i]) continue
        var (currentBlock, currentRect, currentFontSize) = sortedRegions[i]
        processed[i] = true
        var merged = false

        for (j in sortedRegions.indices) {
            if (processed[j] || i == j) continue
            val (otherBlock, otherRect, otherFontSize) = sortedRegions[j]
            if (currentBlock.isVertical != otherBlock.isVertical) continue

            val isOverlap = currentRect.left < otherRect.right && currentRect.right > otherRect.left && currentRect.top < otherRect.bottom && currentRect.bottom > otherRect.top
            val isSameLine = if (!currentBlock.isVertical) {
                val topDiff = kotlin.math.abs(currentRect.top - otherRect.top)
                val avgHeight = ((currentRect.height + otherRect.height) / 2f).coerceAtLeast(1f)
                topDiff < avgHeight * 0.2f
            } else {
                false
            }

            if (isOverlap && !isSameLine) {
                val offset = if (!currentBlock.isVertical) currentRect.width * 0.1f + 2f else currentRect.height * 0.1f + 2f
                if (!currentBlock.isVertical) {
                    val newRect = Rect(
                        otherRect.left + offset,
                        otherRect.top,
                        otherRect.right + offset,
                        otherRect.bottom
                    )
                    sortedRegions[j] = Triple(
                        otherBlock.copy(
                            bounds = android.graphics.Rect(
                                (otherBlock.bounds.left + offset).toInt(),
                                otherBlock.bounds.top,
                                (otherBlock.bounds.right + offset).toInt(),
                                otherBlock.bounds.bottom
                            )
                        ),
                        newRect,
                        otherFontSize
                    )
                } else {
                    val newRect = Rect(
                        otherRect.left,
                        otherRect.top + offset,
                        otherRect.right,
                        otherRect.bottom + offset
                    )
                    sortedRegions[j] = Triple(
                        otherBlock.copy(
                            bounds = android.graphics.Rect(
                                otherBlock.bounds.left,
                                (otherBlock.bounds.top + offset).toInt(),
                                otherBlock.bounds.right,
                                (otherBlock.bounds.bottom + offset).toInt()
                            )
                        ),
                        newRect,
                        otherFontSize
                    )
                }
                continue
            }

            if (isIntersectOrSameLine(currentRect, otherRect, currentBlock.isVertical)) {
                if (regions.isNotEmpty() && !regions.first().first.isVertical) {
                    return regions
                }
                val mergedRect = Rect(
                    min(currentRect.left, otherRect.left),
                    min(currentRect.top, otherRect.top),
                    max(currentRect.right, otherRect.right),
                    max(currentRect.bottom, otherRect.bottom)
                )
                val mergedWidth = mergedRect.width
                val mergedHeight = mergedRect.height
                val minFontSize = min(currentFontSize, otherFontSize)

                val mergedText: String
                val primaryBlock: TextBlockInfo

                if (currentBlock.isVertical) {
                    val isCurrentRighter = currentRect.right > otherRect.right
                    val rightBlock = if (isCurrentRighter) currentBlock else otherBlock
                    val leftBlock = if (isCurrentRighter) otherBlock else currentBlock
                    mergedText = "${rightBlock.text} ${leftBlock.text}"
                    primaryBlock = rightBlock
                } else {
                    val isCurrentUpper = currentRect.top < otherRect.top
                    val upperBlock = if (isCurrentUpper) currentBlock else otherBlock
                    val lowerBlock = if (isCurrentUpper) otherBlock else currentBlock
                    mergedText = "${upperBlock.text}\n${lowerBlock.text}"
                    primaryBlock = upperBlock
                }

                val optimalFontSize = calculateOptimalFontSize(
                    text = mergedText,
                    width = if (currentBlock.isVertical) mergedHeight else mergedWidth,
                    height = if (currentBlock.isVertical) mergedWidth else mergedHeight,
                    minFontSize = minFontSize
                )

                currentBlock = primaryBlock.copy(
                    text = mergedText,
                    bounds = android.graphics.Rect(
                        mergedRect.left.toInt(),
                        mergedRect.top.toInt(),
                        mergedRect.right.toInt(),
                        mergedRect.bottom.toInt()
                    ),
                    fontSize = optimalFontSize
                )
                currentRect = mergedRect
                currentFontSize = optimalFontSize
                processed[j] = true
                merged = true
            }
        }

        resultRegions.add(Triple(currentBlock, currentRect, currentFontSize))
        if (merged) {
            processed[i] = false
            sortedRegions[i] = Triple(currentBlock, currentRect, currentFontSize)
        }
    }

    val finalRegions = resultRegions.toMutableList()
    var changed: Boolean
    var loopCount = 0
    var dynamicOffset = 16f
    do {
        changed = false
        for (i in finalRegions.indices) {
            val (blockA, rectA, fontSizeA) = finalRegions[i]
            val (wrappedTextA, fontSizeFixedA) = adjustWhiteoutBounds(blockA.text, rectA.width, rectA.height, fontSizeA, blockA.isVertical)
            val linesA = wrapText(wrappedTextA, rectA.width, fontSizeFixedA)
            val lineHeightA = fontSizeFixedA * 1.2f
            val textHeightA = linesA.size * lineHeightA
            val safeRectA = Rect(
                rectA.left - 12f,
                rectA.top - 12f,
                rectA.right + 12f,
                rectA.top + textHeightA + 12f
            )
            for (j in finalRegions.indices) {
                if (i == j) continue
                val (blockB, rectB, fontSizeB) = finalRegions[j]
                if (blockA.isVertical != blockB.isVertical) continue
                val (wrappedTextB, fontSizeFixedB) = adjustWhiteoutBounds(blockB.text, rectB.width, rectB.height, fontSizeB, blockB.isVertical)
                val linesB = wrapText(wrappedTextB, rectB.width, fontSizeFixedB)
                val lineHeightB = fontSizeFixedB * 1.2f
                val textHeightB = linesB.size * lineHeightB
                val safeRectB = Rect(
                    rectB.left - 12f,
                    rectB.top - 12f,
                    rectB.right + 12f,
                    rectB.top + textHeightB + 12f
                )
                val isOverlap = safeRectA.left < safeRectB.right && safeRectA.right > safeRectB.left && safeRectA.top < safeRectB.bottom && safeRectA.bottom > safeRectB.top
                if (isOverlap) {
                    val offset = dynamicOffset + max(safeRectA.height, safeRectB.height) * 0.2f
                    val tryDownRect = Rect(
                        rectB.left,
                        rectB.top + offset,
                        rectB.right,
                        rectB.bottom + offset
                    )
                    val tryDownSafe = Rect(
                        tryDownRect.left - 12f,
                        tryDownRect.top - 12f,
                        tryDownRect.right + 12f,
                        tryDownRect.top + textHeightB + 12f
                    )
                    val stillOverlap = safeRectA.left < tryDownSafe.right && safeRectA.right > tryDownSafe.left && safeRectA.top < tryDownSafe.bottom && safeRectA.bottom > tryDownSafe.top
                    val newRect = if (!stillOverlap) {
                        tryDownRect
                    } else {
                        Rect(
                            rectB.left + offset,
                            rectB.top,
                            rectB.right + offset,
                            rectB.bottom
                        )
                    }
                    finalRegions[j] = Triple(
                        blockB.copy(
                            bounds = android.graphics.Rect(
                                newRect.left.toInt(),
                                newRect.top.toInt(),
                                newRect.right.toInt(),
                                newRect.bottom.toInt(),
                            ),
                            fontSize = fontSizeFixedB
                        ),
                        newRect,
                        fontSizeFixedB
                    )
                    changed = true
                }
            }
        }
        loopCount++
        if (loopCount > 10 && changed) dynamicOffset *= 1.5f
    } while (changed && loopCount < 30)
    return finalRegions
}
fun calculateOptimalFontSize(
    text: String,
    width: Float,
    height: Float,
    minFontSize: Float = 12f,
    maxFontSize: Float = 100f
): Float {
    if (text.isBlank() || width <= 0 || height <= 0) return minFontSize

    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textAlign = android.graphics.Paint.Align.LEFT
    }

    var low = minFontSize
    var high = maxFontSize
    var optimalFontSize = minFontSize

    // Nhị phân để tìm fontSize lớn nhất mà text vẫn vừa vùng bôi trắng
    repeat(12) {
        val mid = (low + high) / 2
        paint.textSize = mid
        val wrappedLines = wrapText(text, width * 0.98f, mid)
        val fontMetrics = paint.fontMetrics
        val lineHeight = fontMetrics.descent - fontMetrics.ascent
        val textHeight = wrappedLines.size * lineHeight
        val maxLineWidth = wrappedLines.maxOfOrNull { line ->
            val bounds = android.graphics.Rect()
            paint.getTextBounds(line, 0, line.length, bounds)
            bounds.width().toFloat()
        } ?: 0f

        if (maxLineWidth <= width * 0.98f && textHeight <= height * 0.98f) {
            optimalFontSize = mid
            low = mid + 0.2f
        } else {
            high = mid - 0.2f
        }
    }

    // Đảm bảo không nhỏ hơn minFontSize và không lớn hơn maxFontSize
    return optimalFontSize.coerceIn(minFontSize, maxFontSize)
}

fun DrawScope.drawText(
    text: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    color: Color,
    fontSize: Float,
    isVertical: Boolean
) {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.color = color.toArgb()
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.LEFT
    }

    val lines = wrapText(text, width, fontSize)
    val fontMetrics = paint.fontMetrics
    val lineHeight = fontMetrics.descent - fontMetrics.ascent

    drawIntoCanvas { canvas ->
        if (isVertical) {
            var currentX = x + width - lineHeight
            for (line in lines) {
                if (line.isNotBlank() && currentX >= x) {
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.translate(currentX, y)
                    canvas.nativeCanvas.rotate(90f)
                    canvas.nativeCanvas.drawText(line, 0f, -fontMetrics.ascent, paint)
                    canvas.nativeCanvas.restore()
                    currentX -= lineHeight
                }
            }
        } else {
            var currentY = y - fontMetrics.ascent
            for (line in lines) {
                if (line.isNotBlank() && currentY + fontMetrics.descent <= y + height) {
                    canvas.nativeCanvas.drawText(line, x, currentY, paint)
                    currentY += lineHeight
                }
            }
        }
    }
}

 fun adjustWhiteoutBounds(
    text: String,
    initialWidth: Float,
    initialHeight: Float,
    fontSize: Float,
    isVertical: Boolean
): Pair<String, Float> {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textAlign = android.graphics.Paint.Align.LEFT
        this.textSize = fontSize
    }

    val effectiveWidth = if (isVertical) initialHeight else initialWidth
    val effectiveHeight = if (isVertical) initialWidth else initialHeight
    val wrappedLines = wrapText(text, effectiveWidth * 0.95f, fontSize)
    val fontMetrics = paint.fontMetrics
    val lineHeight = fontMetrics.descent - fontMetrics.ascent
    val textHeight = wrappedLines.size * lineHeight

    val finalFontSize = if (textHeight > effectiveHeight * 0.95f) {
        fontSize * (effectiveHeight * 0.95f / textHeight)
    } else {
        fontSize
    }

    paint.textSize = finalFontSize
    val finalWrappedLines = wrapText(text, effectiveWidth * 0.95f, finalFontSize)
    return finalWrappedLines.joinToString("\n") to finalFontSize
}

 fun wrapText(text: String, width: Float, fontSize: Float): List<String> {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.LEFT
    }

    val lines = mutableListOf<String>()
    val normalizedText = text.replace(Regex("\\s+"), " ").trim()
    val words = normalizedText.split(" ").filter { it.isNotBlank() }
    var currentLine = StringBuilder()

    for (word in words) {
        val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
        val bounds = android.graphics.Rect()
        paint.getTextBounds(testLine, 0, testLine.length, bounds)
        if (bounds.width().toFloat() <= width || currentLine.isEmpty()) {
            currentLine = StringBuilder(testLine)
        } else {
            if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
            currentLine = StringBuilder(word)
        }
    }
    if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
    return lines
}

 fun DrawScope.estimateBackgroundColor(rect: Rect): Color {
    // Phân tích vùng xung quanh để ước lượng màu nền phù hợp
    // Đối với manga, thường là màu trắng hoặc các tone màu sáng

    // Tạo một mẫu màu dựa trên vị trí trong ảnh
    val centerX = rect.center.x / size.width
    val centerY = rect.center.y / size.height

    // Phần lớn manga có nền trắng, nhưng có thể có vùng tối
    // Ước lượng dựa trên vị trí và kích thước vùng văn bản
    return when {
        // Vùng có khả năng là nền trắng (phần lớn manga)
        centerY < 0.8f && rect.width < size.width * 0.7f -> Color.White

        // Vùng có thể có nền xám nhạt (bubble speech, thought bubbles)
        rect.width < size.width * 0.4f && rect.height < size.height * 0.15f -> Color(0xFFF8F8F8)

        // Vùng lớn có thể cần màu nền phức tạp hơn
        rect.width > size.width * 0.5f -> {
            // Sử dụng gradient từ trắng đến xám nhạt
            val gray = (0.95f - (centerY * 0.1f)).coerceIn(0.85f, 0.98f)
            Color(gray, gray, gray, 1f)
        }

        // Mặc định là trắng với độ trong suốt nhẹ để hòa quyện
        else -> Color(0xFFFAFAFA)
    }
}

// Hàm thay thế để xóa văn bản gốc một cách thông minh hơn
 fun DrawScope.smartTextRemoval(rect: Rect, surroundingColor: Color? = null) {
    // Tạo hiệu ứng "content-aware fill" đơn giản
    val estimatedColor = surroundingColor ?: estimateBackgroundColor(rect)

    // Vẽ với gradient nhẹ để tự nhiên hơn
    val gradientColors = listOf(
        estimatedColor.copy(alpha = 0.95f),
        estimatedColor,
        estimatedColor.copy(alpha = 0.98f)
    )

    // Tạo hiệu ứng mờ dần ở viền để không có ranh giới rõ rệt
    val blurRadius = 2f
    val expandedRect = Rect(
        left = rect.left - blurRadius,
        top = rect.top - blurRadius,
        right = rect.right + blurRadius,
        bottom = rect.bottom + blurRadius
    )

    // Vẽ vùng xóa với hiệu ứng mềm mại
    drawRect(
        color = estimatedColor,
        topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
        size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
    )
}

// Hàm phân tích màu nền xung quanh vùng văn bản để tạo hiệu ứng xóa tự nhiên
fun DrawScope.advancedTextRemoval(rect: Rect, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Phân tích bối cảnh của vùng văn bản để xác định kiểu xóa phù hợp
    val contextInfo = analyzeTextContext(rect, originalImageWidth)

    when (contextInfo.type) {
        TextContextType.SPEECH_BUBBLE -> {
            // Xóa trong speech bubble - thường có nền trắng với viền
            drawRoundedTextRemoval(rect, Color.White, cornerRadius = 4f)
        }
        TextContextType.THOUGHT_BUBBLE -> {
            // Xóa trong thought bubble - nền xám nhạt
            drawRoundedTextRemoval(rect, Color(0xFFF5F5F5), cornerRadius = 6f)
        }
        TextContextType.NARRATIVE_BOX -> {
            // Hộp tường thuật - nền có thể có viền
            drawBoxTextRemoval(rect, Color.White, hasFrame = true)
        }
        TextContextType.SOUND_EFFECT -> {
            // Hiệu ứng âm thanh - xóa sạch hoàn toàn
            drawCleanRemoval(rect, Color.White)
        }
        TextContextType.BACKGROUND_TEXT -> {
            // Văn bản trên nền - ước lượng màu nền phức tạp
            drawContextAwareRemoval(rect)
        }
    }
}

 enum class TextContextType {
    SPEECH_BUBBLE,
    THOUGHT_BUBBLE,
    NARRATIVE_BOX,
    SOUND_EFFECT,
    BACKGROUND_TEXT
}

 data class TextContext(
    val type: TextContextType,
    val confidence: Float,
    val estimatedBackgroundColor: Color
)

 fun DrawScope.analyzeTextContext(rect: Rect, originalImageWidth: Float): TextContext {
    val rectWidth = rect.width
    val rectHeight = rect.height
    val aspectRatio = rectWidth / rectHeight
    val sizeRatio = (rectWidth * rectHeight) / (size.width * size.height)

    // Phân tích dựa trên kích thước và tỷ lệ
    return when {
        // Speech bubble: hình chữ nhật nhỏ-trung bình, tỷ lệ cân đối
        sizeRatio < 0.15f && aspectRatio in 0.3f..3.0f && rectWidth < size.width * 0.6f -> {
            TextContext(TextContextType.SPEECH_BUBBLE, 0.8f, Color.White)
        }

        // Thought bubble: tương tự speech bubble nhưng có thể nhỏ hơn
        sizeRatio < 0.1f && aspectRatio in 0.5f..2.0f -> {
            TextContext(TextContextType.THOUGHT_BUBBLE, 0.7f, Color(0xFFF8F8F8))
        }

        // Narrative box: hình chữ nhật dài, thường ở trên/dưới
        aspectRatio > 2.5f && (rect.top < size.height * 0.2f || rect.bottom > size.height * 0.8f) -> {
            TextContext(TextContextType.NARRATIVE_BOX, 0.9f, Color.White)
        }

        // Sound effect: kích thước lớn, có thể có hình dạng bất kỳ
        sizeRatio > 0.2f || rectWidth > size.width * 0.7f -> {
            TextContext(TextContextType.SOUND_EFFECT, 0.6f, Color.White)
        }

        // Background text: mặc định
        else -> {
            TextContext(TextContextType.BACKGROUND_TEXT, 0.5f, estimateBackgroundColor(rect))
        }
    }
}

 fun DrawScope.drawRoundedTextRemoval(rect: Rect, color: Color, cornerRadius: Float, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Scale lại nếu có thông tin kích thước gốc
    val (scaledLeft, scaledTop, scaledWidth, scaledHeight) = if (originalImageWidth > 0f && originalImageHeight > 0f) {
        val scaleX = size.width / originalImageWidth
        val scaleY = size.height / originalImageHeight
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        listOf(left, top, width, height)
    } else {
        listOf(rect.left, rect.top, rect.width, rect.height)
    }
    val roundRect = RoundRect(
        scaledLeft,
        scaledTop,
        scaledLeft + scaledWidth,
        scaledTop + scaledHeight,
        cornerRadius,
        cornerRadius
    )
    val path = Path().apply { addRoundRect(roundRect) }
    drawPath(path, color)
}

 fun DrawScope.drawBoxTextRemoval(rect: Rect, color: Color, hasFrame: Boolean, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Nếu có thông tin kích thước gốc, scale lại tọa độ cho đúng với canvas hiện tại
    val (scaledLeft, scaledTop, scaledWidth, scaledHeight) = if (originalImageWidth > 0f && originalImageHeight > 0f) {
        val scaleX = size.width / originalImageWidth
        val scaleY = size.height / originalImageHeight
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        listOf(left, top, width, height)
    } else {
        listOf(rect.left, rect.top, rect.width, rect.height)
    }
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(scaledLeft, scaledTop),
        size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight)
    )

    if (hasFrame) {
        // Vẽ viền nhẹ nếu cần
        drawRect(
            color = Color.Black.copy(alpha = 0.1f),
            topLeft = androidx.compose.ui.geometry.Offset(scaledLeft, scaledTop),
            size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight),
            style = Stroke(width = 1f)
        )
    }
}

 fun DrawScope.drawCleanRemoval(rect: Rect, color: Color, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Xóa hoàn toàn sạch sẽ, scale lại nếu có thông tin kích thước gốc
    val (scaledLeft, scaledTop, scaledWidth, scaledHeight) = if (originalImageWidth > 0f && originalImageHeight > 0f) {
        val scaleX = size.width / originalImageWidth
        val scaleY = size.height / originalImageHeight
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        listOf(left, top, width, height)
    } else {
        listOf(rect.left, rect.top, rect.width, rect.height)
    }
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(scaledLeft, scaledTop),
        size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight)
    )
}

 fun DrawScope.drawContextAwareRemoval(rect: Rect, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Phân tích xung quanh để tạo màu nền phù hợp
    val estimatedColor = estimateBackgroundColor(rect)
    // Scale lại nếu có thông tin kích thước gốc
    val (scaledLeft, scaledTop, scaledWidth, scaledHeight) = if (originalImageWidth > 0f && originalImageHeight > 0f) {
        val scaleX = size.width / originalImageWidth
        val scaleY = size.height / originalImageHeight
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        listOf(left, top, width, height)
    } else {
        listOf(rect.left, rect.top, rect.width, rect.height)
    }
    // Tạo gradient nhẹ để hòa quyện tự nhiên
    drawRect(
        color = estimatedColor,
        topLeft = androidx.compose.ui.geometry.Offset(scaledLeft, scaledTop),
        size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight)
    )
}

// Hàm tìm vùng nền đồng nhất quanh text (ví dụ: vùng trắng lớn nhất chứa text)
 fun findBackgroundRegion(rect: Rect, bitmap: Bitmap): Rect {
    // Lấy vùng lân cận quanh rect, kiểm tra màu nền đồng nhất (ví dụ: trắng)
    val margin = 8 // px
    val left = rect.left.toInt().coerceAtLeast(0)
    val top = rect.top.toInt().coerceAtLeast(0)
    val right = rect.right.toInt().coerceAtMost(bitmap.width - 1)
    val bottom = rect.bottom.toInt().coerceAtMost(bitmap.height - 1)
    val bgColor = bitmap.getPixel(left, top)
    var bgLeft = left
    var bgRight = right
    var bgTop = top
    var bgBottom = bottom
    // Mở rộng sang trái
    for (x in left downTo 0) {
        if (bitmap.getPixel(x, top) != bgColor) break
        bgLeft = x
    }
    // Mở rộng sang phải
    for (x in right until bitmap.width) {
        if (bitmap.getPixel(x, top) != bgColor) break
        bgRight = x
    }
    // Mở rộng lên trên
    for (y in top downTo 0) {
        if (bitmap.getPixel(left, y) != bgColor) break
        bgTop = y
    }
    // Mở rộng xuống dưới
    for (y in bottom until bitmap.height) {
        if (bitmap.getPixel(left, y) != bgColor) break
        bgBottom = y
    }
    return Rect(bgLeft.toFloat(), bgTop.toFloat(), bgRight.toFloat(), bgBottom.toFloat())
}

// Khi merge/shifting, giới hạn vùng whiteout và text trong vùng nền
 fun limitRectToBackground(rect: Rect, bgRect: Rect): Rect {
    return Rect(
        max(rect.left, bgRect.left),
        max(rect.top, bgRect.top),
        min(rect.right, bgRect.right),
        min(rect.bottom, bgRect.bottom)
    )
}

// Khi vẽ whiteout/text, chỉ vẽ trong vùng giao với vùng nền
// Sử dụng trong drawWithCache/onDrawBehind:
// val bgRect = findBackgroundRegion(rect, bitmap)
// val limitedRect = limitRectToBackground(rect, bgRect)
// drawRect(..., topLeft = Offset(limitedRect.left, limitedRect.top), size = Size(limitedRect.width, limitedRect.height))
