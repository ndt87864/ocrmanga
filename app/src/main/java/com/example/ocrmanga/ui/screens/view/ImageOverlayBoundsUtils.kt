

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

fun DrawScope.drawTranslucentOverlay(
    rect: Rect,
    backgroundType: com.example.ocrmanga.data.models.BackgroundType,
    averageBackgroundColor: Int?,
    originalTextColor: Int? = null,
    shapeType: Int = 0
) {
    when (backgroundType) {
        com.example.ocrmanga.data.models.BackgroundType.WHITE -> {
            // Nền trắng - sử dụng màu trắng bình thường
            val overlayColor = Color.White
            when (shapeType) {
                1 -> drawOval(
                    color = overlayColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                )
                else -> drawRect(
                    color = overlayColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                )
            }
        }
        com.example.ocrmanga.data.models.BackgroundType.COLORED -> {
            // Nền có màu - sử dụng màu nền gốc làm overlay
            if (averageBackgroundColor != null) {
                val overlayColor = Color(averageBackgroundColor)
                
                // Vẽ overlay với màu nền gốc, độ mờ vừa đủ để che text cũ
                when (shapeType) {
                    1 -> drawOval(
                        color = overlayColor.copy(alpha = 0.95f),
                        topLeft = Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                    )
                    else -> drawRoundRect(
                        color = overlayColor.copy(alpha = 0.95f),
                        topLeft = Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f)
                    )
                }
            } else {
                // Fallback - màu trắng với độ mờ cao
                val overlayColor = Color.White.copy(alpha = 0.9f)
                when (shapeType) {
                    1 -> drawOval(
                        color = overlayColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                    )
                    else -> drawRoundRect(
                        color = overlayColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f)
                    )
                }
            }
        }
        com.example.ocrmanga.data.models.BackgroundType.TRANSPARENT -> {
            // Nền trong suốt - sử dụng màu trắng với độ mờ cao
            val overlayColor = Color.White.copy(alpha = 0.95f)
            when (shapeType) {
                1 -> drawOval(
                    color = overlayColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                )
                else -> drawRect(
                    color = overlayColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                )
            }
        }
    }
}

/**
 * Compute default text color (black or white) based on overlay or average background color brightness.
 * If both are null, returns black.
 */
fun computeDefaultTextColor(overlayColor: Int?, averageBackgroundColor: Int? = null): Int {
    val base = overlayColor ?: averageBackgroundColor
    if (base == null) return 0xFF000000.toInt()
    val opaque = base or 0xFF000000.toInt()
    val lum = androidx.core.graphics.ColorUtils.calculateLuminance(opaque)
    return if (lum <= 0.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
}

/**
 * Đo kích thước thực tế của text block (width x height) tại fontSize cho trước.
 * Trả về Pair(measuredWidth, measuredHeight) tính bằng pixel.
 * Dùng để xác định kích thước overlay theo text thay vì theo bounds gốc.
 */
fun measureTextActualSize(
    text: String,
    fontSize: Float,
    maxWidth: Float,
    isVertical: Boolean,
    context: Context? = null,
    fontFamilyName: String? = null,
    lineSpacing: Float = 1.0f,
    shapeType: Int = 0,
    boldness: Float = 1.0f
): Pair<Float, Float> {
    if (text.isBlank() || fontSize <= 0f) return Pair(0f, 0f)

    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.LEFT
        context?.let { ctx ->
            getCachedTypeface(ctx, fontFamilyName)?.let { this.typeface = it }
        }
        
        if (boldness > 1.0f) {
            this.style = android.graphics.Paint.Style.FILL_AND_STROKE
            this.strokeWidth = (boldness - 1.0f) * 2.0f
        }
    }

    // Với oval, thu hẹp vùng wrap (82% width) để cân đối trong ellipse
    val wrapWidth = if (shapeType == 1) maxWidth * 0.82f else maxWidth * 0.95f
    val lines = wrapText(text, wrapWidth, fontSize, context, fontFamilyName, boldness)
    val fontMetrics = paint.fontMetrics
    val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * lineSpacing

    val measuredWidth = lines.maxOfOrNull { line ->
        paint.measureText(line)
    } ?: 0f

    val measuredHeight = lines.size * lineHeight

    return if (isVertical) {
        // Vertical: width/height swap
        Pair(measuredHeight, measuredWidth)
    } else {
        Pair(measuredWidth, measuredHeight)
    }
}

/**
 * Tính toán outer và inner bounds cho windowed overlay approach.
 *
 * @param originalBounds Bounds gốc từ OCR (đã scale về canvas coordinates)
 * @param text Text cần render
 * @param baseFontSize Font size cơ bản (sẽ được tính lại optimal)
 * @param isVertical Text có vertical không
 * @param context Android context
 * @param fontFamilyName Font family name
 * @param lineSpacing Line spacing multiplier
 * @param shapeType 0=rectangle, 1=oval
 * @param overlayInsetHorizontal User inset horizontal
 * @param overlayInsetVertical User inset vertical
 * @param boldness Boldness factor
 * @return Quad(outerBounds, innerBounds, optimalFontSize, textMeasuredSize)
 */
data class WindowedOverlayResult(
    val outerBounds: androidx.compose.ui.geometry.Rect,
    val innerBounds: androidx.compose.ui.geometry.Rect,
    val optimalFontSize: Float,
    val textMeasuredSize: Pair<Float, Float>
)

fun calculateWindowedOverlayBounds(
    originalBounds: androidx.compose.ui.geometry.Rect,
    text: String,
    baseFontSize: Float,
    isVertical: Boolean,
    context: Context,
    fontFamilyName: String?,
    lineSpacing: Float,
    shapeType: Int,
    overlayInsetHorizontal: Float,
    overlayInsetVertical: Float,
    horizontalPadding: Float = 0f,
    verticalPadding: Float = 0f,
    boldness: Float = 1.0f,
    originalFontSize: Float? = null,
    isSolidBubble: Boolean = false
): WindowedOverlayResult {
    // Check cache first
    val cacheKey = generateCacheKey(originalBounds, text, baseFontSize, isVertical, fontFamilyName, lineSpacing, shapeType, overlayInsetHorizontal, overlayInsetVertical, originalFontSize, isSolidBubble)
    synchronized(boundsCacheLock) {
        windowedBoundsCache[cacheKey]?.let { cached ->
            return cached
        }
    }

    // 1. Outer bounds = FULL original bounds (KHÔNG áp dụng user insets)
    var currentOuterBounds = originalBounds

    var currentOverlayInsetH = overlayInsetHorizontal
    var currentOverlayInsetV = overlayInsetVertical

    // 2. Inner bounds = outer bounds - user insets
    // Đây là vùng bôi trắng thực tế sẽ được vẽ
    var currentInnerBounds = androidx.compose.ui.geometry.Rect(
        currentOuterBounds.left + currentOverlayInsetH,
        currentOuterBounds.top + currentOverlayInsetV,
        currentOuterBounds.right - currentOverlayInsetH,
        currentOuterBounds.bottom - currentOverlayInsetV
    ).takeIf { it.width > 0 && it.height > 0 } ?: currentOuterBounds

    // 3. Tính toán vùng vẽ văn bản dựa trên OUTER bounds để text không bị ảnh hưởng bởi inset
    var textAreaWidth = currentOuterBounds.width
    var textAreaHeight = currentOuterBounds.height

    var optimalFontSize = calculateOptimalFontSize(
        text = text,
        width = textAreaWidth,
        height = textAreaHeight,
        minFontSize = (baseFontSize * 0.1f).coerceAtLeast(4f), // Cho phép co nhỏ tối đa để vừa vùng chứa
        maxFontSize = baseFontSize * 3f,
        shapeType = shapeType,
        context = context,
        fontFamilyName = fontFamilyName,
        lineSpacing = lineSpacing,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        boldness = boldness
    )

    // Expand bounding box dynamically if optimalFontSize < minTargetSize
    // Bỏ logic while loop tự động giãn bounds ở UI layer vì đã chuyển logic scale (originalFontSize và min 15f) vào OverlayOptimizer.kt
    // Giúp data nhất quán giữa Log, Database và UI.
    val minLimit = 6f
    val targetMinFontSize = if (originalFontSize != null && originalFontSize > minLimit) {
        maxOf(minLimit, originalFontSize)
    } else {
        minLimit
    }

    if (optimalFontSize < targetMinFontSize) {
        // Ưu tiên tuyệt đối việc text nằm lọt trong overlay để chống tràn viền
        optimalFontSize = maxOf(optimalFontSize, minLimit)
    }

    // 4. Measure text size với optimal font size
    val (textMeasuredW, textMeasuredH) = measureTextActualSize(
        text = text,
        fontSize = optimalFontSize,
        maxWidth = textAreaWidth,
        isVertical = isVertical,
        context = context,
        fontFamilyName = fontFamilyName,
        lineSpacing = lineSpacing,
        shapeType = shapeType,
        boldness = boldness
    )

    val result = WindowedOverlayResult(currentOuterBounds, currentInnerBounds, optimalFontSize, Pair(textMeasuredW, textMeasuredH))

    // Store in cache
    synchronized(boundsCacheLock) {
        windowedBoundsCache[cacheKey] = result
    }

    return result
}

/**
 * Kiểm tra xem blockA có hoàn toàn chứa blockB hay không.
 */
fun doesBlockContain(blockA: TextBlockInfo, blockB: TextBlockInfo): Boolean {
    val rectA = blockA.bounds
    val rectB = blockB.bounds

    // 1. Kiểm tra nhanh bằng hình chữ nhật
    if (!rectA.contains(rectB)) return false

    // 2. Nếu blockA là hình chữ nhật, nó đã chứa blockB
    if (blockA.shapeType == 0) return true

    // 3. Nếu blockA là oval, kiểm tra xem 4 góc của blockB có nằm trong oval của blockA không
    val composeRectA = androidx.compose.ui.geometry.Rect(rectA.left.toFloat(), rectA.top.toFloat(), rectA.right.toFloat(), rectA.bottom.toFloat())
    val cornersB = listOf(
        androidx.compose.ui.geometry.Offset(rectB.left.toFloat(), rectB.top.toFloat()),
        androidx.compose.ui.geometry.Offset(rectB.right.toFloat(), rectB.top.toFloat()),
        androidx.compose.ui.geometry.Offset(rectB.left.toFloat(), rectB.bottom.toFloat()),
        androidx.compose.ui.geometry.Offset(rectB.right.toFloat(), rectB.bottom.toFloat())
    )

    return cornersB.all { isPointInBlock(composeRectA, blockA.shapeType, it) }
}

/**
 * Kiểm tra xem hai block có thực sự giao nhau hay không,
 * tính đến cả hình dạng (chữ nhật hoặc oval).
 */
fun doBlocksIntersect(blockA: TextBlockInfo, blockB: TextBlockInfo): Boolean {
    val rectA = blockA.bounds
    val rectB = blockB.bounds

    // 1. Kiểm tra nhanh bằng hình chữ nhật bao quanh
    if (!android.graphics.Rect.intersects(rectA, rectB)) return false

    // 2. Nếu cả hai là hình chữ nhật, chúng giao nhau
    if (blockA.shapeType == 0 && blockB.shapeType == 0) return true

    // 3. Nếu ít nhất một cái là hình oval, cần kiểm tra kỹ hơn.
    // Sử dụng "hình thoi" nội tiếp (các trung điểm cạnh) để kiểm tra va chạm.
    val composeRectA = androidx.compose.ui.geometry.Rect(rectA.left.toFloat(), rectA.top.toFloat(), rectA.right.toFloat(), rectA.bottom.toFloat())
    val composeRectB = androidx.compose.ui.geometry.Rect(rectB.left.toFloat(), rectB.top.toFloat(), rectB.right.toFloat(), rectB.bottom.toFloat())

    // Kiểm tra các điểm đặc trưng của blockA có nằm trong blockB không
    val pointsA = listOf(
        androidx.compose.ui.geometry.Offset(rectA.centerX().toFloat(), rectA.top.toFloat()),
        androidx.compose.ui.geometry.Offset(rectA.centerX().toFloat(), rectA.bottom.toFloat()),
        androidx.compose.ui.geometry.Offset(rectA.left.toFloat(), rectA.centerY().toFloat()),
        androidx.compose.ui.geometry.Offset(rectA.right.toFloat(), rectA.centerY().toFloat()),
        androidx.compose.ui.geometry.Offset(rectA.centerX().toFloat(), rectA.centerY().toFloat())
    )
    if (pointsA.any { isPointInBlock(composeRectB, blockB.shapeType, it) }) return true

    // Kiểm tra các điểm đặc trưng của blockB có nằm trong blockA không
    val pointsB = listOf(
        androidx.compose.ui.geometry.Offset(rectB.centerX().toFloat(), rectB.top.toFloat()),
        androidx.compose.ui.geometry.Offset(rectB.centerX().toFloat(), rectB.bottom.toFloat()),
        androidx.compose.ui.geometry.Offset(rectB.left.toFloat(), rectB.centerY().toFloat()),
        androidx.compose.ui.geometry.Offset(rectB.right.toFloat(), rectB.centerY().toFloat()),
        androidx.compose.ui.geometry.Offset(rectB.centerX().toFloat(), rectB.centerY().toFloat())
    )
    if (pointsB.any { isPointInBlock(composeRectA, blockA.shapeType, it) }) return true

    return false
}

/**
 * Kiểm tra một điểm có nằm trong vùng của block hay không,
 * hỗ trợ cả hình chữ nhật và hình oval.
 */
fun isPointInBlock(rect: Rect, shapeType: Int, pos: Offset): Boolean {
    return if (shapeType == 1) { // Oval/Ellipse
        val rx = rect.width / 2f
        val ry = rect.height / 2f
        if (rx <= 0f || ry <= 0f) return false

        val centerX = rect.left + rx
        val centerY = rect.top + ry

        val dx = pos.x - centerX
        val dy = pos.y - centerY

        // Sử dụng công thức ellipse: (dx^2 / rx^2) + (dy^2 / ry^2) <= 1.0
        (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry) <= 1.0f
    } else {
        // Mặc định là hình chữ nhật
        rect.contains(pos)
    }
}
