package com.example.ocrmanga.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.example.ocrmanga.data.models.BackgroundType
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.ocr.BubbleDetector
import com.example.ocrmanga.data.ocr.models.TextContainerType
import kotlin.math.abs

/**
 * Tối ưu hiển thị overlay translation tự động.
 *
 * Sử dụng OpenCV BubbleDetector để phát hiện viền bubble chính xác,
 * từ đó:
 * 1. Phân loại TH1 (solid bubble) vs TH2 (complex background/artwork) chính xác hơn
 * 2. Tính inset tự động từ bubble bounds - text bounds
 * 3. Xác định shape type (oval vs rect) qua circularity
 */
object OverlayOptimizer {

    private const val TAG = "OverlayOptimizer"

    // BubbleDetector dùng OpenCV để phát hiện viền bubble
    private val bubbleDetector = BubbleDetector()

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
        imageHeight: Int,
        forceSolid: Boolean = false
    ): Pair<List<TextBlockInfo>, List<OptimizationResult>> {
        bubbleDetectionCache.clear()
        val results = blocks.mapIndexed { index, block ->
            optimizeBlock(block, index, imageBitmap, imageWidth, imageHeight, forceSolid)
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
        bubbleDetectionCache.clear()
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
    // Cache bubble detection results for the current batch
    private val bubbleDetectionCache = mutableMapOf<Int, BubbleDetector.BubbleDetectionResult>()

    private fun optimizeBlock(
        block: TextBlockInfo,
        index: Int,
        imageBitmap: Bitmap?,
        imageWidth: Int,
        imageHeight: Int,
        forceSolid: Boolean = false
    ): OptimizationResult {
        // Sử dụng BubbleDetector (OpenCV) để phát hiện bubble và container info
        var isSolidBubble = false
        var bubbleBounds: Rect? = null

        if (imageBitmap != null) {
            val cached = bubbleDetectionCache[index]
            val detection = cached ?: bubbleDetector.detectBubble(
                imageBitmap, block.bounds, imageWidth, imageHeight
            )
            if (cached == null) {
                bubbleDetectionCache[index] = detection
            }

            isSolidBubble = detection.isSolidBubble

            bubbleBounds = detection.bubbleBounds
        }

        // [FIX] KHÔNG dùng containerInfo.type (SPEECH_BUBBLE/SPEECH_FRAME) để xác định isSolidBubble.
        // Container type từ contour shape KHÔNG đáng tin cậy - dễ false positive trên artwork phức tạp.
        // Dùng OR consensus giữa 2 pixel-based checks:
        //   1. detection.isSolidBubble: OpenCV scan giữa contour bounds & text bounds (chính xác nếu contour đúng)
        //   2. isSolidColorBubble(): Scan perimeter có margin quanh text (đã đạt >90%, luôn đáng tin cậy)
        // Nếu một trong hai nói là solid → tin tưởng (bảo thủ: ưu tiên che phủ text hơn là để lộ)
        if (imageBitmap != null) {
            val legacyCheck = isSolidColorBubble(block, imageBitmap)
            isSolidBubble = legacyCheck || isSolidBubble
        }
        val isSolidBg = if (imageBitmap == null) isSolidBackground(block) else false

        return if (forceSolid || isSolidBubble || isSolidBg) {
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

        val origBounds = block.bounds
        var newFontSize = block.fontSize
        val insetH = 0f
        val insetV = 0f

        // [SAFETY FIRST] Luôn áp dụng lề an toàn tối thiểu và mở rộng cho hình Oval
        // đảm bảo che phủ text gốc tuyệt đối ngay cả khi không có bitmap.
        val minSafePadding = 10 // Tăng lên 10px để chắc chắn che text gốc ngay từ đầu
        val ovalFactor = 0.45f

        var l = (origBounds.left - minSafePadding).coerceAtLeast(0)
        var t = (origBounds.top - minSafePadding).coerceAtLeast(0)
        var r = (origBounds.right + minSafePadding).coerceAtMost(imageWidth - 1)
        var b = (origBounds.bottom + minSafePadding).coerceAtMost(imageHeight - 1)

        if (imageBitmap != null) {
            // Define border pixel: significantly different brightness from background
            fun isBorderPixel(p: Int): Boolean {
                val red = Color.red(p)
                val green = Color.green(p)
                val blue = Color.blue(p)
                val brightness = (red + green + blue) / 3
                return if (isDarkBg) {
                    brightness > 90 // Nhạy hơn với viền sáng trên nền tối
                } else {
                    brightness < 160 // Nhạy hơn với viền tối trên nền sáng (manga thường là nền trắng viền đen)
                }
            }

            // PHASE: DYNAMIC EXPAND OUTWARDS
            // Tiếp tục mở rộng nếu vùng xung quanh vẫn là màu nền đơn sắc (trắng/đen)
            var maxLimitFactor = 1.0f // Cho phép mở rộng tối đa 100% kích thước để đảm bảo che hết text
            if (shapeToUse == 1) maxLimitFactor += ovalFactor

            val limitL = (origBounds.left - (origBounds.width() * maxLimitFactor).toInt()).coerceAtLeast(0)
            val limitR = (origBounds.right + (origBounds.width() * maxLimitFactor).toInt()).coerceAtMost(imageBitmap.width - 1)
            val limitT = (origBounds.top - (origBounds.height() * maxLimitFactor).toInt()).coerceAtLeast(0)
            val limitB = (origBounds.bottom + (origBounds.height() * maxLimitFactor).toInt()).coerceAtMost(imageBitmap.height - 1)

            var expandLeft = true
            var expandRight = true
            var expandTop = true
            var expandBottom = true
            val maxStep = (imageBitmap.width + imageBitmap.height) / 4
            var step = 0

            // Ngưỡng dừng: Nhạy hơn (chỉ cần 2px viền hoặc 8% kích thước) để tránh lẹm vào viền mảnh
            val stopThresholdH = (origBounds.height() * 0.08f).toInt().coerceIn(2, 8)
            val stopThresholdV = (origBounds.width() * 0.08f).toInt().coerceIn(2, 8)

            while (step < maxStep && (expandLeft || expandRight || expandTop || expandBottom)) {
                if (expandLeft && l > limitL) {
                    var hits = 0
                    val checkX = l - 1
                    for (y in t..b) if (isBorderPixel(imageBitmap.getPixel(checkX, y))) hits++
                    if (hits >= stopThresholdH) {
                        expandLeft = false
                        l += 2 // Safety retreat 2px để chắc chắn không đè lên viền
                    } else l--
                } else expandLeft = false

                if (expandRight && r < limitR) {
                    var hits = 0
                    val checkX = r + 1
                    for (y in t..b) if (isBorderPixel(imageBitmap.getPixel(checkX, y))) hits++
                    if (hits >= stopThresholdH) {
                        expandRight = false
                        r -= 2 // Safety retreat
                    } else r++
                } else expandRight = false

                if (expandTop && t > limitT) {
                    var hits = 0
                    val checkY = t - 1
                    for (x in l..r) if (isBorderPixel(imageBitmap.getPixel(x, checkY))) hits++
                    if (hits >= stopThresholdV) {
                        expandTop = false
                        t += 2 // Safety retreat
                    } else t--
                } else expandTop = false

                if (expandBottom && b < limitB) {
                    var hits = 0
                    val checkY = b + 1
                    for (x in l..r) if (isBorderPixel(imageBitmap.getPixel(x, checkY))) hits++
                    if (hits >= stopThresholdV) {
                        expandBottom = false
                        b -= 2 // Safety retreat
                    } else b++
                } else expandBottom = false

                step++
            }
        }

        val finalBounds = Rect(l, t, r, b)

        // Cập nhật lại fontSize dựa trên vùng bao mới để text trông cân đối hơn
        if (finalBounds.width() > origBounds.width() || finalBounds.height() > origBounds.height()) {
            val scaleFactor = minOf(
                finalBounds.width().toFloat() / origBounds.width().toFloat(),
                finalBounds.height().toFloat() / origBounds.height().toFloat()
            ).coerceIn(1f, 1.2f)

            newFontSize = block.fontSize * scaleFactor
        }

        return OptimizationResult(
            overlayInsetHorizontal = insetH,
            overlayInsetVertical = insetV,
            overlayAlpha = 1.0f, // Đục hoàn toàn
            overlayColor = overlayColor,
            textColor = textColor,
            borderColor = null,
            borderThickness = 0f,
            needsTransparency = false,
            transparencyThreshold = 1.0f,
            confidence = 0.95f,
            containerType = containerType,
            reason = "Solid ${if (isDarkBg) "dark" else "light"} bubble, optimized bounds",
            shapeType = shapeToUse,
            newBounds = finalBounds,
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
        val newBounds = result.newBounds ?: Rect(block.bounds)
        val newInsetH = result.overlayInsetHorizontal
        val newInsetV = result.overlayInsetVertical

        val blockIdentifier = if (index >= 0) "$index" else "${block.bubbleId ?: "?"}"
        val origText = block.originalText?.replace("\n", " ") ?: ""
        val transText = newText.replace("\n", " ")

        // RÀNG BUỘC: Đảm bảo fontSize không vượt quá kích thước vùng chứa (bounds)
        // Capping ở mức 70% chiều cao hoặc chiều rộng (tùy cái nào nhỏ hơn) để tránh tràn
        val minDim = minOf(newBounds.width(), newBounds.height()).toFloat()
        val fontSizeLimit = minDim * 0.7f
        if (newFontSize > fontSizeLimit) {
            newFontSize = fontSizeLimit
        }
        // Giới hạn dưới tuyệt đối (6f) để đảm bảo đọc được và chống tràn viền
        if (newFontSize < 6f) {
            newFontSize = 6f
        }

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
            //Log.i(TAG, "\n" + logMessage)
        } else {
            val logMessage = """
                |[TH2] Block $blockIdentifier
                |Orig text: '$origText'
                |Trans text: '$transText' (FontSize: $newFontSize)
                |Orig bounds: ${block.bounds}
                |New bounds: $newBounds
            """.trimMargin()
            //Log.i(TAG, "\n" + logMessage)
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
