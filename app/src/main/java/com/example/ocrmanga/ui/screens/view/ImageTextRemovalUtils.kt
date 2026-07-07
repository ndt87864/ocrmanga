

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
    val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
    val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
    val right = rect.right.toInt().coerceIn(0, bitmap.width - 1)
    val bottom = rect.bottom.toInt().coerceIn(0, bitmap.height - 1)
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
fun shrinkOverlappingBoxes(blocks: List<com.example.ocrmanga.data.models.TextBlockInfo>): List<com.example.ocrmanga.data.models.TextBlockInfo> {
    val result = blocks.map { it.copy() }.toMutableList()
    for (i in result.indices) {
        val blockA = result[i]
        for (j in result.indices) {
            if (i == j) continue
            val blockB = result[j]
            if (doBlocksIntersect(blockA, blockB)) {
                val boxA = blockA.bounds
                val boxB = blockB.bounds
                val intersect = android.graphics.Rect(
                    maxOf(boxA.left, boxB.left),
                    maxOf(boxA.top, boxB.top),
                    minOf(boxA.right, boxB.right),
                    minOf(boxA.bottom, boxB.bottom)
                )
                val areaA = (boxA.width() * boxA.height()).toFloat()
                val areaIntersect = (intersect.width() * intersect.height()).toFloat()
                if (areaA > 0 && areaIntersect / areaA > 0.15f) {
                    val shrinkLeft = if (intersect.left == boxA.left) intersect.width() / 2 else 0
                    val shrinkRight = if (intersect.right == boxA.right) intersect.width() / 2 else 0
                    val shrinkTop = if (intersect.top == boxA.top) intersect.height() / 2 else 0
                    val shrinkBottom = if (intersect.bottom == boxA.bottom) intersect.height() / 2 else 0
                    result[i] = result[i].copy(
                        bounds = android.graphics.Rect(
                            boxA.left + shrinkLeft,
                            boxA.top + shrinkTop,
                            boxA.right - shrinkRight,
                            boxA.bottom - shrinkBottom
                        )
                    )
                }
            }
        }
    }
    return result
}

fun splitNonOverlappingBoxes(blocks: List<TextBlockInfo>): List<TextBlockInfo> {
    val result = mutableListOf<TextBlockInfo>()
    val used = BooleanArray(blocks.size)
    for (i in blocks.indices) {
        val blockA = blocks[i]
        var boxA = android.graphics.Rect(blockA.bounds)
        var keep = true
        for (j in blocks.indices) {
            if (i == j) continue
            val blockB = blocks[j]
            if (doBlocksIntersect(blockA, blockB)) {
                if (doesBlockContain(blockB, blockA)) {
                    keep = false
                    break
                }
                val boxB = blockB.bounds
                val intersect = android.graphics.Rect(
                    maxOf(boxA.left, boxB.left),
                    maxOf(boxA.top, boxB.top),
                    minOf(boxA.right, boxB.right),
                    minOf(boxA.bottom, boxB.bottom)
                )
                if (intersect.width() > 0 && intersect.height() > 0) {
                    if (intersect.right == boxA.right) boxA.right = intersect.left
                    if (intersect.left == boxA.left) boxA.left = intersect.right
                    if (intersect.bottom == boxA.bottom) boxA.bottom = intersect.top
                    if (intersect.top == boxA.top) boxA.top = intersect.bottom
                }
            }
        }
        if (keep && boxA.width() > 0 && boxA.height() > 0) {
            result.add(blocks[i].copy(bounds = android.graphics.Rect(boxA)))
        }
    }
    return result
}
// Khi vẽ whiteout/text, chỉ vẽ trong vùng giao với vùng nền
// Sử dụng trong drawWithCache/onDrawBehind:
// val bgRect = findBackgroundRegion(rect, bitmap)
// val limitedRect = limitRectToBackground(rect, bgRect)
// drawRect(..., topLeft = Offset(limitedRect.left, limitedRect.top), size = Size(limitedRect.width, limitedRect.height))

