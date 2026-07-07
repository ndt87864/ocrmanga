

package com.example.ocrmanga.ui.screens.view

import com.example.ocrmanga.utils.AppLogger as Log

// import android.content.Context (removed duplicate)
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

import androidx.compose.ui.geometry.Offset
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
import com.example.ocrmanga.data.models.TextAlignMode
import java.io.IOException
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min
import android.content.Context
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

// Image and text region utilities extracted from ViewerScreen.kt

// Resolve font file name from a logical font family name used in the app

fun computeEditModeFontSize(block: com.example.ocrmanga.data.models.TextBlockInfo, editedFontSize: Float?): Float {
    val baseSize = editedFontSize ?: block.fontSize
    return if (block.isVertical) (baseSize / 3f).coerceAtLeast(8f) else baseSize
}

fun drawTextOnCanvas(drawScope: DrawScope,
    text: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    color: Color,
    fontSize: Float,
    isVertical: Boolean,
    boldness: Float = 1.0f, // Độ đậm của text (0.5 - 2.0)
    context: Context,
    fontFamilyName: String? = null,
    borderColor: Color? = null, // Màu viền chữ
    borderThickness: Float = 0.0f, // Độ dày viền (0.0 - 5.0)
    borderAlpha: Float = 1.0f, // Độ trong suốt của viền (0.0 - 1.0),
    editMode: Boolean = false, // Nếu true, không giới hạn font size bởi overlay
    shapeType: Int = 0, // 0 = rectangle, 1 = oval
    lineSpacing: Float = 1.0f, // Khoảng cách dòng, multiplier (1.0 = bình thường)
    shadowColor: Color? = null, // Màu đổ bóng chữ
    shadowAlpha: Float = 1.0f, // Alpha multiplier for shadow (0.0 - 1.0)
    shadowRadius: Float = 0f, // Blur radius in px for shadow; 0 = use default proportional radius
    textAlign: com.example.ocrmanga.data.models.TextAlignMode = com.example.ocrmanga.data.models.TextAlignMode.CENTER,
    textGradientColors: List<Int>? = null,
    textGradientOffsets: List<Float>? = null,
    textGradientType: Int = 0,
    precomputedWrappedText: String? = null,
    precomputedOptimalFontSize: Float? = null
) {
    val whenAligned = textAlign

    // Tạo paint cho viền text (nếu có yêu cầu viền)
    val borderPaint = if (borderColor != null && borderThickness != 0f) {
        androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            this.color = borderColor.copy(alpha = borderAlpha).toArgb()
            this.textSize = fontSize
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = if (borderThickness < 0f) 2f else borderThickness // Will be updated later if < 0f

            // Use cached Typeface to avoid repeated asset loads
            try {
                getCachedTypeface(context, fontFamilyName)?.let { this.typeface = it }
            } catch (_: Exception) { }
        }
    } else null

    val gradientColorsArr = textGradientColors?.toIntArray()
    val gradientPositionsArr = textGradientOffsets?.toFloatArray()

    // Tạo paint cho text chính
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        if (gradientColorsArr == null || gradientColorsArr.size < 2) {
            this.color = color.toArgb()
        }
        // Shader sẽ được gán cho từng ký tự trong vòng lặp vẽ nếu có gradient
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.CENTER // Đổi từ LEFT sang CENTER để căn giữa
        // Use cached Typeface to avoid repeated asset loads
        try {
            getCachedTypeface(context, fontFamilyName)?.let { this.typeface = it }
        } catch (_: Exception) { }
        // Điều chỉnh stroke width để tạo hiệu ứng đậm nhạt
        if (boldness > 1.0f) {
            this.style = android.graphics.Paint.Style.FILL_AND_STROKE
            this.strokeWidth = (boldness - 1.0f) * 2.0f
        } else if (boldness < 1.0f) {
            // Làm nhạt bằng cách giảm alpha
            val alpha = (255 * boldness).toInt().coerceIn(50, 255)
            this.alpha = alpha
        }
    }
    // Prepare a separate shadow paint so shadow is drawn behind border/text and remains outside border edges
    val shadowPaint = if (shadowColor != null) {
        androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            val finalShadow = shadowColor.copy(alpha = shadowAlpha)
            // Use the shadow color for the paint so setShadowLayer produces a visible cast
            // The main text will be drawn afterwards, covering the glyph interior, leaving
            // the blurred shadow visible around the glyph edges.
            this.color = finalShadow.toArgb()
            this.textSize = fontSize
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.style = android.graphics.Paint.Style.FILL
            // Use cached Typeface to match main text
            try { getCachedTypeface(context, fontFamilyName)?.let { this.typeface = it } } catch (_: Exception) { }
            try {
                // If user provided an explicit radius use it, otherwise fall back to proportional radius
                val radius = if (shadowRadius > 0f) shadowRadius else (fontSize * 0.14f).coerceAtLeast(1f)
                val dx = (fontSize * 0.04f)
                val dy = (fontSize * 0.04f)
                // set shadow color on the layer; paint color remains transparent so glyph fill is not colored
                this.setShadowLayer(radius, dx, dy, finalShadow.toArgb())
            } catch (_: Exception) { }
        }
    } else null

    val (wrappedText, optimalFontSize) = if (precomputedWrappedText != null && precomputedOptimalFontSize != null) {
        precomputedWrappedText to precomputedOptimalFontSize
    } else {
        adjustWhiteoutBounds(
            text = text,
            initialWidth = width,
            initialHeight = height,
            fontSize = fontSize,
            isVertical = isVertical,
            context = context,
            fontFamilyName = fontFamilyName,
            shapeType = shapeType,
            lineSpacing = lineSpacing,
            boldness = boldness
        )
    }
    // Use textAlign to affect drawing positions (default CENTER behavior)
    // textAlign will be applied below when drawing each line.
    paint.textSize = optimalFontSize
    borderPaint?.textSize = optimalFontSize
    if (borderThickness < 0f) {
        borderPaint?.strokeWidth = optimalFontSize / 6f
    }
    // Ensure shadow paint scales when final font size is adjusted
    shadowPaint?.let { sp ->
        try {
            sp.textSize = optimalFontSize
            val radius = if (shadowRadius > 0f) shadowRadius else (optimalFontSize * 0.14f).coerceAtLeast(1f)
            val dx = (optimalFontSize * 0.04f)
            val dy = (optimalFontSize * 0.04f)
            val finalShadow = (shadowColor?.copy(alpha = shadowAlpha) ?: Color.Black.copy(alpha = shadowAlpha))
            sp.setShadowLayer(radius, dx, dy, finalShadow.toArgb())
        } catch (_: Exception) { }
    }
    val lines = wrappedText.split("\n")
    val fontMetrics = paint.fontMetrics
    val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * lineSpacing

    drawScope.drawIntoCanvas { canvas ->
        if (isVertical) {
            var currentX = x + width - lineHeight
            for (line in lines) {
                if (line.isNotBlank() && currentX >= x) {
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.translate(currentX, y)
                    canvas.nativeCanvas.rotate(90f)
                    val lineWidth = paint.measureText(line)
                    val centeredY = (height - lineWidth) / 2
                    // Draw shadow first so it appears outside the border and text
                    shadowPaint?.let {
                        canvas.nativeCanvas.drawText(line, centeredY, -fontMetrics.ascent, it)
                    }
                    borderPaint?.let {
                        canvas.nativeCanvas.drawText(line, centeredY, -fontMetrics.ascent, it)
                    }
                    if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                        drawTextPerCharacter(
                            canvas.nativeCanvas, line, centeredY, -fontMetrics.ascent, paint,
                            gradientColorsArr, gradientPositionsArr, textGradientType, fontMetrics
                        )
                    } else {
                        canvas.nativeCanvas.drawText(line, centeredY, -fontMetrics.ascent, paint)
                    }
                    canvas.nativeCanvas.restore()
                    currentX -= lineHeight
                }
            }
        } else {
            // Center the block of lines vertically within the overlay with equal top/bottom margins
            val totalTextHeight = lines.size * lineHeight
            // Calculate margin to center text block vertically (equal spacing top and bottom)
            val verticalMargin = (height - totalTextHeight) / 2f
            // Start Y position: top of overlay + vertical margin - ascent to position baseline correctly
            val startY = y + verticalMargin - fontMetrics.ascent

            var currentY = startY
            for ((index, line) in lines.withIndex()) {
                if (line.isNotBlank()) {
                    val centerX = x + width / 2
                    when (whenAligned) {
                        TextAlignMode.LEFT -> {
                            // left-align inside box with small padding
                            paint.textAlign = android.graphics.Paint.Align.LEFT
                            borderPaint?.textAlign = android.graphics.Paint.Align.LEFT
                            shadowPaint?.textAlign = android.graphics.Paint.Align.LEFT
                            val leftX = x + 4f
                            shadowPaint?.let { canvas.nativeCanvas.drawText(line, leftX, currentY, it) }
                            borderPaint?.let { canvas.nativeCanvas.drawText(line, leftX, currentY, it) }
                            if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                                drawTextPerCharacter(
                                    canvas.nativeCanvas, line, leftX, currentY, paint,
                                    gradientColorsArr, gradientPositionsArr, textGradientType, fontMetrics
                                )
                            } else {
                                canvas.nativeCanvas.drawText(line, leftX, currentY, paint)
                            }
                        }
                        TextAlignMode.CENTER -> {
                            paint.textAlign = android.graphics.Paint.Align.CENTER
                            borderPaint?.textAlign = android.graphics.Paint.Align.CENTER
                            shadowPaint?.textAlign = android.graphics.Paint.Align.CENTER
                            shadowPaint?.let { canvas.nativeCanvas.drawText(line, centerX, currentY, it) }
                            borderPaint?.let { canvas.nativeCanvas.drawText(line, centerX, currentY, it) }
                            if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                                drawTextPerCharacter(
                                    canvas.nativeCanvas, line, centerX, currentY, paint,
                                    gradientColorsArr, gradientPositionsArr, textGradientType, fontMetrics
                                )
                            } else {
                                canvas.nativeCanvas.drawText(line, centerX, currentY, paint)
                            }
                        }
                    }
                }
                currentY += lineHeight
            }

        }
    }
}

fun drawTextPerCharacter(
    canvas: android.graphics.Canvas,
    text: String,
    tx: Float,
    ty: Float,
    paint: android.graphics.Paint,
    colors: IntArray,
    positions: FloatArray?,
    gradientType: Int,
    fontMetrics: android.graphics.Paint.FontMetrics
) {
    if (text.isEmpty()) return
    val originalAlign = paint.textAlign
    
    // Calculate local starting x based on alignment
    val totalWidth = paint.measureText(text)
    val startX = when (originalAlign) {
        android.graphics.Paint.Align.LEFT -> tx
        android.graphics.Paint.Align.CENTER -> tx - totalWidth / 2f
        android.graphics.Paint.Align.RIGHT -> tx - totalWidth
        else -> tx
    }
    
    // Draw character by character
    paint.textAlign = android.graphics.Paint.Align.LEFT
    var currentX = startX
    
    for (char in text) {
        val s = char.toString()
        val charWidth = paint.measureText(s)
        
        // Define character boundaries
        val top = ty + fontMetrics.ascent
        val bottom = ty + fontMetrics.descent
        
        // Apply gradient shader for this character specifically
        paint.shader = when (gradientType) {
            0 -> android.graphics.LinearGradient( // Top-Down
                currentX, top, currentX, bottom,
                colors, positions, android.graphics.Shader.TileMode.CLAMP
            )
            1 -> android.graphics.LinearGradient( // Left-Right
                currentX, ty, currentX + charWidth, ty,
                colors, positions, android.graphics.Shader.TileMode.CLAMP
            )
            2 -> android.graphics.LinearGradient( // Diagonal (TL-BR)
                currentX, top, currentX + charWidth, bottom,
                colors, positions, android.graphics.Shader.TileMode.CLAMP
            )
            3 -> android.graphics.LinearGradient( // Diagonal (TR-BL)
                currentX + charWidth, top, currentX, bottom,
                colors, positions, android.graphics.Shader.TileMode.CLAMP
            )
            else -> null
        }
        
        canvas.drawText(s, currentX, ty, paint)
        currentX += charWidth
    }
    
    // Restore alignment and clear shader
    paint.textAlign = originalAlign
    paint.shader = null
}

fun adjustWhiteoutBounds(
    text: String,
    initialWidth: Float,
    initialHeight: Float,
    fontSize: Float,
    isVertical: Boolean,
    context: Context? = null,
    fontFamilyName: String? = null,
    shapeType: Int = 0,
    lineSpacing: Float = 1.0f, // Khoảng cách dòng multiplier
    boldness: Float = 1.0f
): Pair<String, Float> {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textAlign = android.graphics.Paint.Align.LEFT
        this.textSize = fontSize
        context?.let { ctx ->
            getCachedTypeface(ctx, fontFamilyName)?.let { this.typeface = it }
        }
    }

    val effectiveWidth = if (isVertical) initialHeight else initialWidth
    val effectiveHeight = if (isVertical) initialWidth else initialHeight

    // Safe padding to keep text away from overlay edges. Slightly conservative to avoid clipping.
    val safePadding = 4f.coerceAtMost(fontSize * 0.5f)

    val availableWidth = (effectiveWidth - safePadding * 2f).coerceAtLeast(1f)
    val availableHeight = (effectiveHeight - safePadding * 2f).coerceAtLeast(1f)

    // Compute an optimal font size that fits into the available area. Do not allow it
    // to grow beyond the provided fontSize (we only want to shrink when overflowing).
    // Ensure maxFontSize is at least minFontSize to avoid invalid range.
    val minSize = 6f
    val maxSize = fontSize.coerceAtLeast(minSize)
    val optimal = calculateOptimalFontSize(
        text = text,
        width = effectiveWidth,
        height = effectiveHeight,
        minFontSize = minSize,
        maxFontSize = maxSize,
        shapeType = shapeType,
        context = context,
        fontFamilyName = fontFamilyName,
        extraSizeAllowance = 0f, // No allowance here to be strict
        horizontalPadding = safePadding,
        verticalPadding = safePadding,
        lineSpacing = lineSpacing,
        boldness = boldness
    )

    // Now wrap the text using the computed font size so measurements align with rendering.
    // Use the same width scale as calculateOptimalFontSize
    val finalWidthScale = if (shapeType == 1) 0.75f else 0.95f
    val wrappedLines = wrapText(text, availableWidth * finalWidthScale, optimal, context, fontFamilyName, boldness)
    return wrappedLines.joinToString("\n") to optimal
}

 fun wrapText(text: String, width: Float, fontSize: Float, context: Context? = null, fontFamilyName: String? = null, boldness: Float = 1.0f): List<String> {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.LEFT
        // Use cached Typeface for measuring text width/bounds
        context?.let { ctx -> getCachedTypeface(ctx, fontFamilyName)?.let { this.typeface = it } }
        
        if (boldness > 1.0f) {
            this.style = android.graphics.Paint.Style.FILL_AND_STROKE
            this.strokeWidth = (boldness - 1.0f) * 2.0f
        }
    }

    val lines = mutableListOf<String>()
    val normalizedText = text.replace(Regex("\\s+"), " ").trim()
    val words = normalizedText.split(" ").filter { it.isNotBlank() }
    var currentLine = StringBuilder()

    for (word in words) {
        val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
        val lineWidth = paint.measureText(testLine)
        
        if (lineWidth <= width || currentLine.isEmpty()) {
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
