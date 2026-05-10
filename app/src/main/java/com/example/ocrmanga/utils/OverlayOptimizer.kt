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
        val needsTransparency: Boolean,
        val transparencyThreshold: Float,
        val confidence: Float,
        val containerType: ContainerType,
        val reason: String,
        val shapeType: Int // 0=Rect, 1=Oval
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
        val results = blocks.map { block ->
            optimizeBlock(block, imageBitmap, imageWidth, imageHeight)
        }
        val optimizedBlocks = blocks.mapIndexed { index, block ->
            applyResult(block, results[index])
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
        return blocks.map { block ->
            optimizeBlock(block, imageBitmap, imageWidth, imageHeight)
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
        imageBitmap: Bitmap?,
        imageWidth: Int,
        imageHeight: Int
    ): OptimizationResult {
        val isSolidBubble = isSolidColorBubble(block, imageBitmap)

        return if (isSolidBubble) {
            optimizeForSolidBubble(block, imageWidth, imageHeight)
        } else {
            optimizeForTransparent(block, imageWidth, imageHeight)
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
        imageWidth: Int,
        imageHeight: Int
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

        // Tính inset hợp lý dựa trên kích thước block
        // Để không làm lộ text gốc, inset phải bằng 0.
        // Hủy bỏ việc tự động ép sang Oval vì Oval sẽ cắt lẹm 4 góc của bounding box chữ nhật,
        // khiến chữ tiếng Nhật gốc bị lộ ra ngoài. Thay vào đó, ta sẽ xử lý bo góc nhẹ cho Rectangle
        // bên trong ImageViewer.kt để tránh viền sắc nhọn mà không làm lộ chữ.
        val shapeToUse = block.shapeType

        // Dùng inset 0 để đảm bảo che kín 100% text gốc
        val insetH = 0f
        val insetV = 0f

        val containerType = if (shapeToUse == 1) ContainerType.BUBBLE_OVAL else ContainerType.BUBBLE_RECT

        val originalInsetH = block.overlayInsetHorizontal
        val originalInsetV = block.overlayInsetVertical
        Log.i(TAG, "[TH1] Block '${block.text.take(20)}...', inset ban đầu=($originalInsetH, $originalInsetV), inset sau khi sửa=($insetH, $insetV)")

        return OptimizationResult(
            overlayInsetHorizontal = insetH,
            overlayInsetVertical = insetV,
            overlayAlpha = 1.0f, // Đục hoàn toàn
            overlayColor = block.customOverlayColor ?: block.averageBackgroundColor ?: Color.WHITE,
            textColor = block.customTextColor ?: block.originalTextColor ?: Color.BLACK,
            needsTransparency = false,
            transparencyThreshold = 1.0f,
            confidence = 0.95f,
            containerType = containerType,
            reason = "Solid ${if (isDarkBg) "dark" else "light"} bubble, inset to avoid border",
            shapeType = shapeToUse
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
        imageWidth: Int,
        imageHeight: Int
    ): OptimizationResult {
        val bgColor = block.averageBackgroundColor
        val isDarkBg = bgColor != null && isColorDark(bgColor)

        // Text color: tương phản với nền
        val textColor = block.customTextColor
            ?: block.originalTextColor
            ?: if (isDarkBg) Color.WHITE else Color.BLACK

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
        Log.i(TAG, "[TH2] Block '${block.text.take(20)}...', độ trong suốt ban đầu=$originalAlpha, độ trong suốt sau khi sửa=0.0")

        return OptimizationResult(
            overlayInsetHorizontal = insetH,
            overlayInsetVertical = insetV,
            overlayAlpha = 0.0f, // Trong suốt hoàn toàn (0%)
            overlayColor = block.customOverlayColor ?: block.averageBackgroundColor ?: Color.WHITE,
            textColor = block.customTextColor ?: block.originalTextColor ?: Color.BLACK,
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
        result: OptimizationResult
    ): TextBlockInfo {
        return block.copy(
            overlayAlpha = result.overlayAlpha,
            overlayInsetHorizontal = result.overlayInsetHorizontal,
            overlayInsetVertical = result.overlayInsetVertical,
            shapeType = result.shapeType
            // KHÔNG can thiệp vào customOverlayColor và customTextColor
        )
    }
}
