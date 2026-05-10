package com.example.ocrmanga.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.example.ocrmanga.data.models.BackgroundType
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.ocr.models.TextContainerType

/**
 * Tối ưu hiển thị overlay translation tự động.
 * 
 * Quy tắc đơn giản:
 * 1. Bubble/hộp thoại đơn sắc (trắng hoặc đen) với viền khác màu background:
 *    → Overlay đục, inset hợp lý để che hết text mà không lẹm viền.
 * 2. Mọi trường hợp khác:
 *    → Overlay trong suốt.
 */
object OverlayOptimizer {

    private const val TAG = "OverlayOptimizer"

    /**
     * Kết quả tối ưu overlay
     */
    data class OptimizationResult(
        val overlayInsetHorizontal: Float,
        val overlayInsetVertical: Float,
        val overlayAlpha: Float,
        val overlayColor: Int,
        val textColor: Int,
        val borderColor: Int? = null,
        val borderThickness: Float? = null,
        val needsTransparency: Boolean,
        val transparencyThreshold: Float,
        val confidence: Float,
        val containerType: ContainerType,
        val reason: String,
        val shapeType: Int, // 0=Rect, 1=Oval
        val newBounds: Rect? = null,
        val newFontSize: Float? = null
    )

    enum class ContainerType {
        BUBBLE_OVAL,
        BUBBLE_RECT,
        BLOCK_RECT,
        TEXT_ON_IMAGE,
        PANEL_BORDER,
        PANEL_BG,
        UNKNOWN
    }

    // ==================== PUBLIC API ====================

    /**
     * Analyze and optimize blocks in batch
     */
    fun analyzeAndOptimize(
        blocks: List<TextBlockInfo>,
        imageBitmap: Bitmap?,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<List<TextBlockInfo>, List<OptimizationResult>> {
        val results = blocks.mapIndexed { index, block ->
            optimizeBlock(block, index, imageBitmap, imageWidth, imageHeight)
        }
        val optimizedBlocks = blocks.mapIndexed { index, block ->
            applyResult(block, results[index], index)
        }
        return Pair(optimizedBlocks, results)
    }

    /**
     * Tối ưu tất cả blocks
     */
    fun optimizeBlocks(
        blocks: List<TextBlockInfo>,
        imageBitmap: Bitmap?,
        imageWidth: Int,
        imageHeight: Int
    ): List<OptimizationResult> {
        return blocks.mapIndexed { index, block ->
            optimizeBlock(block, index, imageBitmap, imageWidth, imageHeight)
        }
    }

    /**
     * Apply optimization to a single block
     */
    fun applyOptimization(
        block: TextBlockInfo,
        result: OptimizationResult
    ): TextBlockInfo {
        return applyResult(block, result)
    }

    // ==================== CORE LOGIC ====================

    /**
     * Logic tối ưu cho 1 block.
     *
     * Bước 1: Kiểm tra có phải bubble đơn sắc (trắng/đen) không?
     *   → Nếu CÓ: overlay đục, tính inset hợp lý
     *   → Nếu KHÔNG: overlay trong suốt
     */
    private fun optimizeBlock(
        block: TextBlockInfo,
        index: Int,
        imageBitmap: Bitmap?,
        imageWidth: Int,
        imageHeight: Int
    ): OptimizationResult {
        val isSolidBubble = isSolidColorBubble(block, imageBitmap)

        return if (isSolidBubble) {
            optimizeForSolidBubble(block, index, imageWidth, imageHeight, imageBitmap)
        } else {
            optimizeForTransparent(block, index, imageWidth, imageHeight)
        }
    }

    /**
     * Kiểm tra block có phải là bubble/hộp thoại đơn sắc trắng hoặc đen không (TH1).
     *
     * Logic mới (Pure Android Bitmap):
     * Quét các pixel viền ngoài (perimeter) bao quanh bounding box của chữ.
     * - Nếu vùng viền ngoài đa số là 1 màu trơn tĩnh lặng (độ nhiễu thấp) -> Nó đang nằm trong bong bóng thoại (TH1).
     * - Nếu vùng viền ngoài chứa nhiều màu lộn xộn, chuyển màu (gradient) -> Nó nằm trên artwork, áo quần (TH2).
     */
    private fun isSolidColorBubble(block: TextBlockInfo, bitmap: Bitmap?): Boolean {
        if (bitmap == null) return isSolidBackground(block) // Fallback nếu không có ảnh gốc

        try {
            val bounds = block.bounds
            // Lấy lề (margin) vừa đủ để quét phần nền bao quanh text, tránh đụng sát chữ
            val margin = (kotlin.math.min(bounds.width(), bounds.height()) * 0.15f).toInt().coerceIn(4, 12)

            val outerRect = Rect(
                (bounds.left - margin).coerceAtLeast(0),
                (bounds.top - margin).coerceAtLeast(0),
                (bounds.right + margin).coerceAtMost(bitmap.width - 1),
                (bounds.bottom + margin).coerceAtMost(bitmap.height - 1)
            )

            // Lấy mẫu các pixel nằm ở rìa của outerRect (khung hình chữ nhật rỗng)
            val perimeterPixels = mutableListOf<Int>()
            val step = 2 // Lấy mẫu mỗi 2 pixel để tăng tốc

            for (x in outerRect.left..outerRect.right step step) {
                perimeterPixels.add(bitmap.getPixel(x, outerRect.top))
                perimeterPixels.add(bitmap.getPixel(x, outerRect.bottom))
            }
            for (y in outerRect.top..outerRect.bottom step step) {
                perimeterPixels.add(bitmap.getPixel(outerRect.left, y))
                perimeterPixels.add(bitmap.getPixel(outerRect.right, y))
            }

            if (perimeterPixels.isEmpty()) return isSolidBackground(block)

            var lightCount = 0
            var darkCount = 0
            var midCount = 0

            for (p in perimeterPixels) {
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                val brightness = (r + g + b) / 3

                val maxColor = maxOf(r, g, b)
                val minColor = minOf(r, g, b)
                val saturation = if (maxColor == 0) 0 else (maxColor - minColor) * 255 / maxColor

                if (saturation > 40) {
                    // Nếu pixel có màu rõ rệt -> Tính vào nhóm xám/màu (mid)
                    midCount++
                } else {
                    if (brightness > 220) {
                        lightCount++
                    } else if (brightness < 45) {
                        darkCount++
                    } else {
                        midCount++ // Các sắc xám (screentones, shading)
                    }
                }
            }

            val totalCount = perimeterPixels.size
            val midRatio = midCount.toFloat() / totalCount
            val lightRatio = lightCount.toFloat() / totalCount
            val darkRatio = darkCount.toFloat() / totalCount

            // Bong bóng thoại TH1: Nền trắng chữ đen, hoặc nền đen chữ trắng.
            // Điều quan trọng là số lượng pixel xám/màu (mid) ở rìa rất ít.
            // Có thể lẹm 1 chút vào viền đen của bong bóng (khiến darkRatio tăng khi nền trắng),
            // nhưng lightRatio vẫn sẽ chiếm ưu thế.
            val isSolidWhite = lightRatio > 0.60f && midRatio < 0.25f
            val isSolidBlack = darkRatio > 0.60f && midRatio < 0.25f

            return isSolidWhite || isSolidBlack

        } catch (e: Exception) {
            Log.e(TAG, "Error in isSolidColorBubble", e)
            return isSolidBackground(block)
        }
    }

    /**
     * Kiểm tra nền có phải đơn sắc (trắng hoặc đen) không.
     */
    private fun isSolidBackground(block: TextBlockInfo): Boolean {
        // Nếu backgroundType là WHITE → chắc chắn đơn sắc trắng
        if (block.backgroundType == BackgroundType.WHITE) {
            return true
        }

        // Nếu có averageBackgroundColor, kiểm tra gần trắng
        val bgColor = block.averageBackgroundColor
        if (bgColor != null) {
            val r = Color.red(bgColor)
            val g = Color.green(bgColor)
            val b = Color.blue(bgColor)

            // Gần trắng: RGB đều > 220
            val isNearWhite = r > 220 && g > 220 && b > 220

            // Text trên nền đen thường là text trên ảnh/quần áo (TH2) chứ không phải bong bóng thoại.
            // Do đó loại bỏ kiểm tra isNearBlack ở đây để tránh nhận nhầm TH1.

            if (isNearWhite) {
                return true
            }
        }

        // Nếu backgroundType là COLORED nhưng không gần trắng → không đơn sắc
        return false
    }

    // ==================== CASE 1: SOLID BUBBLE ====================

    /**
     * Tối ưu cho bubble đơn sắc trắng/đen.
     * 
     * Mục tiêu:
     * - Overlay đục (alpha = 1.0)
     * - Inset vừa đủ để không lẹm viền bubble
     * - Overlay color = màu nền bubble
     * - Text color = màu text gốc hoặc tự chọn tương phản
     */
    private fun optimizeForSolidBubble(
        block: TextBlockInfo,
        index: Int,
        imageWidth: Int,
        imageHeight: Int,
        imageBitmap: Bitmap?
    ): OptimizationResult {
        val bgColor = block.averageBackgroundColor
        val isDarkBg = bgColor != null && isColorDark(bgColor)

        // Xác định màu overlay = màu nền bubble
        val overlayColor = if (isDarkBg) {
            bgColor ?: Color.BLACK
        } else {
            bgColor ?: Color.WHITE
        }

        // Xác định màu text (tương phản với nền)
        val textColor = block.customTextColor
            ?: block.originalTextColor
            ?: if (isDarkBg) Color.WHITE else Color.BLACK

        val shapeToUse = block.shapeType
        val containerType = if (shapeToUse == 1) ContainerType.BUBBLE_OVAL else ContainerType.BUBBLE_RECT

        var newBounds = Rect(block.bounds)
        var newFontSize = block.fontSize
        var insetH = 0f
        var insetV = 0f

        if (imageBitmap != null) {
            // Define border pixel: significantly different brightness from background
            fun isBorderPixel(p: Int): Boolean {
                val red = Color.red(p)
                val green = Color.green(p)
                val blue = Color.blue(p)
                val brightness = (red + green + blue) / 3
                return if (isDarkBg) {
                    brightness > 120 // Border of dark bubble is light
                } else {
                    brightness < 120 // Border of light bubble is dark
                }
            }

            val origBounds = block.bounds

            // Instead of originalTouchesBorder, we use a more robust two-phase approach (Expand then Shrink)
            // with different hit thresholds to distinguish between text and bubble borders.
            var l = origBounds.left.coerceIn(0, imageBitmap.width - 1)
            var t = origBounds.top.coerceIn(0, imageBitmap.height - 1)
            var r = origBounds.right.coerceIn(0, imageBitmap.width - 1)
            var b = origBounds.bottom.coerceIn(0, imageBitmap.height - 1)

            // Yêu cầu của người dùng: "nếu đã che hết text gốc -> vẫn giữu giới hạn 20%; ngược lại thì giới hạn là che hết text gốc trước đã"
            // - Hình Chữ Nhật (Rect): Mặc định đã che hết text gốc (origBounds). Nên giới hạn mở rộng là 20% tổng (tức 10% mỗi bên -> 0.1f).
            // - Hình Bầu Dục (Oval): Để một hình oval nội tiếp có thể che trọn 4 góc của hình chữ nhật, nó phải lớn hơn hình chữ nhật đó ít nhất căn(2) lần (tức ~1.414 lần).
            //   Do đó, nó CẦN phải mở rộng thêm 41.4% tổng (tức ~21% mỗi bên -> 0.21f) thì mới "che hết text gốc".
            val limitFactor = if (shapeToUse == 1) 0.21f else 0.10f
            val limitL = (origBounds.left - origBounds.width() * limitFactor).toInt().coerceAtLeast(0)
            val limitR = (origBounds.right + origBounds.width() * limitFactor).toInt().coerceAtMost(imageBitmap.width - 1)
            val limitT = (origBounds.top - origBounds.height() * limitFactor).toInt().coerceAtLeast(0)
            val limitB = (origBounds.bottom + origBounds.height() * limitFactor).toInt().coerceAtMost(imageBitmap.height - 1)

            var expandLeft = true
            var expandRight = true
            var expandTop = true
            var expandBottom = true
            val maxStep = (imageBitmap.width + imageBitmap.height) / 4
            var step = 0

            // PHASE 1: EXPAND OUTWARDS
            // We use a low threshold (e.g. 2 hits) to stop exactly at the tips of jagged borders.
            while (step < maxStep && (expandLeft || expandRight || expandTop || expandBottom)) {
                if (expandLeft && l > limitL) {
                    var hits = 0
                    val checkX = l - 1
                    for (y in t..b) if (isBorderPixel(imageBitmap.getPixel(checkX, y))) hits++
                    if (hits > 2) expandLeft = false else l--
                } else expandLeft = false

                if (expandRight && r < limitR) {
                    var hits = 0
                    val checkX = r + 1
                    for (y in t..b) if (isBorderPixel(imageBitmap.getPixel(checkX, y))) hits++
                    if (hits > 2) expandRight = false else r++
                } else expandRight = false

                if (expandTop && t > limitT) {
                    var hits = 0
                    val checkY = t - 1
                    for (x in l..r) if (isBorderPixel(imageBitmap.getPixel(x, checkY))) hits++
                    if (hits > 2) expandTop = false else t--
                } else expandTop = false

                if (expandBottom && b < limitB) {
                    var hits = 0
                    val checkY = b + 1
                    for (x in l..r) if (isBorderPixel(imageBitmap.getPixel(x, checkY))) hits++
                    if (hits > 2) expandBottom = false else b++
                } else expandBottom = false

                step++
            }

            // PHASE 2: SHRINK INWARDS
            // If the original bounds overlapped a thick or jagged border, Phase 1 wouldn't have expanded.
            // We shrink inwards to clear the border. Since Phase 1 guarantees we are either on the border
            // or in the white gap, we can safely use a low threshold (hits > 2) to perfectly clear jagged tips
            // without worrying about hitting text (unless the text physically touches the border).
            var shrinkLeft = true
            var shrinkRight = true
            var shrinkTop = true
            var shrinkBottom = true

            // Max shrink is 10% of original bounds, to prevent eating too much text in worst cases
            val shrinkLimitL = (origBounds.left + origBounds.width() * 0.1f).toInt().coerceAtMost(imageBitmap.width - 1)
            val shrinkLimitR = (origBounds.right - origBounds.width() * 0.1f).toInt().coerceAtLeast(0)
            val shrinkLimitT = (origBounds.top + origBounds.height() * 0.1f).toInt().coerceAtMost(imageBitmap.height - 1)
            val shrinkLimitB = (origBounds.bottom - origBounds.height() * 0.1f).toInt().coerceAtLeast(0)

            while (shrinkLeft || shrinkRight || shrinkTop || shrinkBottom) {
                if (shrinkLeft && l < shrinkLimitL) {
                    var hits = 0
                    for (y in t..b) if (isBorderPixel(imageBitmap.getPixel(l, y))) hits++
                    if (hits > 2) l++ else shrinkLeft = false
                } else shrinkLeft = false

                if (shrinkRight && r > shrinkLimitR) {
                    var hits = 0
                    for (y in t..b) if (isBorderPixel(imageBitmap.getPixel(r, y))) hits++
                    if (hits > 2) r-- else shrinkRight = false
                } else shrinkRight = false

                if (shrinkTop && t < shrinkLimitT) {
                    var hits = 0
                    for (x in l..r) if (isBorderPixel(imageBitmap.getPixel(x, t))) hits++
                    if (hits > 2) t++ else shrinkTop = false
                } else shrinkTop = false

                if (shrinkBottom && b > shrinkLimitB) {
                    var hits = 0
                    for (x in l..r) if (isBorderPixel(imageBitmap.getPixel(x, b))) hits++
                    if (hits > 2) b-- else shrinkBottom = false
                } else shrinkBottom = false
            }

            newBounds = Rect(l, t, r, b)
            insetH = 0f
            insetV = 0f

            // Scale text size proportional to the expanded bounds, capping at 20% increase
            if (newBounds.width() > origBounds.width() || newBounds.height() > origBounds.height()) {
                val scaleFactor = minOf(
                    newBounds.width().toFloat() / origBounds.width().toFloat(),
                    newBounds.height().toFloat() / origBounds.height().toFloat()
                ).coerceIn(1f, 1.2f)

                newFontSize = block.fontSize * scaleFactor
            }
        }

        return OptimizationResult(
            overlayInsetHorizontal = insetH,
            overlayInsetVertical = insetV,
            overlayAlpha = 1.0f, // Đục hoàn toàn
            overlayColor = block.customOverlayColor ?: block.averageBackgroundColor ?: Color.WHITE,
            textColor = block.customTextColor ?: block.originalTextColor ?: Color.BLACK,
            borderColor = null,
            borderThickness = 0f,
            needsTransparency = false,
            transparencyThreshold = 1.0f,
            confidence = 0.95f,
            containerType = containerType,
            reason = "Solid ${if (isDarkBg) "dark" else "light"} bubble, optimized bounds",
            shapeType = shapeToUse,
            newBounds = newBounds,
            newFontSize = newFontSize
        )
    }

    // ==================== CASE 2: TRANSPARENT ====================

    /**
     * Tối ưu cho các trường hợp khác: chuyển overlay về dạng trong suốt.
     * 
     * Mục tiêu:
     * - Overlay trong suốt (alpha thấp) để không che mất artwork
     * - Text vẫn đọc được nhờ border/shadow hoặc semi-transparent background
     */
    private fun optimizeForTransparent(
        block: TextBlockInfo,
        index: Int,
        imageWidth: Int,
        imageHeight: Int
    ): OptimizationResult {
        val bgColor = block.averageBackgroundColor
        val isDarkBg = bgColor != null && isColorDark(bgColor)

        // Text color: tương phản với nền
        val textColor = block.customTextColor
            ?: block.originalTextColor
            ?: if (isDarkBg) Color.WHITE else Color.BLACK

        // Tính viền cho text (nếu text màu trắng -> viền đen ; các màu khác -> viền trắng)
        val isTextWhite = Color.red(textColor) > 240 && Color.green(textColor) > 240 && Color.blue(textColor) > 240
        val borderColor = if (isTextWhite) Color.BLACK else Color.WHITE

        // Overlay color: semi-transparent white hoặc black
        val overlayColor = if (isDarkBg) {
            Color.argb(120, 0, 0, 0) // Nền tối → overlay đen bán trong suốt
        } else {
            Color.argb(120, 255, 255, 255) // Nền sáng → overlay trắng bán trong suốt
        }

        // Inset nhỏ, không cần tránh viền vì overlay đã trong suốt
        val insetH = 0f
        val insetV = 0f

        val containerType = classifyNonBubble(block)

        val originalAlpha = block.overlayAlpha
        // Log được chuyển sang applyResult

        return OptimizationResult(
            overlayInsetHorizontal = insetH,
            overlayInsetVertical = insetV,
            overlayAlpha = 0.0f, // Trong suốt hoàn toàn (0%)
            overlayColor = block.customOverlayColor ?: block.averageBackgroundColor ?: Color.WHITE,
            textColor = textColor,
            borderColor = borderColor,
            borderThickness = -1f, // -1f = Dùng tỷ lệ động (fontSize / 6)
            needsTransparency = true,
            transparencyThreshold = 0.0f,
            confidence = 0.7f,
            containerType = containerType,
            reason = "Non-solid background, transparent overlay",
            shapeType = block.shapeType
        )
    }

    // ==================== HELPERS ====================

    /**
     * Phân loại sơ bộ cho các block không phải solid bubble
     */
    private fun classifyNonBubble(block: TextBlockInfo): ContainerType {
        val containerType = block.containerInfo?.type
        return when (containerType) {
            TextContainerType.SEMI_TRANSPARENT -> ContainerType.BLOCK_RECT
            TextContainerType.FREE_TEXT -> ContainerType.TEXT_ON_IMAGE
            else -> ContainerType.UNKNOWN
        }
    }

    /**
     * Kiểm tra màu có phải tối không
     */
    private fun isColorDark(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) +
                0.587 * Color.green(color) +
                0.114 * Color.blue(color)) / 255.0
        return luminance < 0.5
    }

    /**
     * Apply kết quả tối ưu lên block
     */
    private fun applyResult(
        block: TextBlockInfo,
        result: OptimizationResult,
        index: Int = -1
    ): TextBlockInfo {
        var newText = formatPunctuationSpacing(block.text)
        var newFontSize = result.newFontSize ?: block.fontSize
        var newBounds = result.newBounds ?: Rect(block.bounds)
        var newInsetH = result.overlayInsetHorizontal
        var newInsetV = result.overlayInsetVertical

        val blockIdentifier = if (index >= 0) "$index" else "${block.bubbleId ?: "?"}"
        val origText = block.originalText?.replace("\n", " ") ?: ""
        val transText = newText.replace("\n", " ")

        if (!result.needsTransparency) {
            val logMessage = """
                |[TH1] Block $blockIdentifier
                |Orig text: '$origText'
                |Trans text: '$transText' (FontSize: $newFontSize)
                |Orig inset: (H: ${block.overlayInsetHorizontal}, V: ${block.overlayInsetVertical})
                |New inset: (H: $newInsetH, V: $newInsetV)
                |Orig bounds: ${block.bounds}
                |New bounds: $newBounds
            """.trimMargin()
            Log.i(TAG, "\n" + logMessage)
        } else {
            val logMessage = """
                |[TH2] Block $blockIdentifier
                |Orig text: '$origText'
                |Trans text: '$transText' (FontSize: $newFontSize)
                |Orig bounds: ${block.bounds}
                |New bounds: $newBounds
            """.trimMargin()
            Log.i(TAG, "\n" + logMessage)
        }

        return block.copy(
            text = newText,
            fontSize = newFontSize,
            bounds = newBounds,
            overlayAlpha = result.overlayAlpha,
            overlayInsetHorizontal = newInsetH,
            overlayInsetVertical = newInsetV,
            shapeType = result.shapeType,
            customBorderColor = result.borderColor ?: block.customBorderColor,
            borderThickness = result.borderThickness ?: block.borderThickness
            // KHÔNG can thiệp vào customOverlayColor và customTextColor (nếu không cần thiết)
        )
    }

    /**
     * Tự động thêm khoảng trắng vào các dấu câu đặc biệt để dễ đọc hơn.
     */
    private fun formatPunctuationSpacing(text: String): String {
        var res = text
        // "text" + "..." -> "text" + " " + "..."
        res = res.replace(Regex("([\\p{L}\\d])(\\.\\.\\.)"), "$1 $2")
        // "..." + "text2" -> "..." + " " + "text2"
        res = res.replace(Regex("(\\.\\.\\.)([\\p{L}\\d])"), "$1 $2")
        // "text1" + "-" hoặc "." + "text2" -> "text1" + "-"/"." + " " + "text2"
        res = res.replace(Regex("([\\p{L}\\d])([-\\.])([\\p{L}\\d])"), "$1$2 $3")
        return res
    }
}
