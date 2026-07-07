

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

data class ColorAnalysisResult(
    val backgroundType: com.example.ocrmanga.data.models.BackgroundType,
    val backgroundColor: Int?,
    val textColor: Int?,
    val borderColor: Int? = null,
    val borderThickness: Float = 0f
)

fun analyzeColorsAndBorder(bitmap: Bitmap?, bounds: android.graphics.Rect): ColorAnalysisResult {
    if (bitmap == null || bounds.isEmpty || bounds.left >= bitmap.width || bounds.top >= bitmap.height) {
        return ColorAnalysisResult(com.example.ocrmanga.data.models.BackgroundType.WHITE, null, null)
    }

    val width = bounds.width()
    val height = bounds.height()
    if (width < 2 || height < 2) {
        return ColorAnalysisResult(com.example.ocrmanga.data.models.BackgroundType.WHITE, null, null)
    }

    val openCvAvailable = com.example.ocrmanga.data.ocr.OpenCvInitializer.ensureInitialized()
    if (!openCvAvailable) {
        // Fallback về thuật toán phân tích pixel cũ
        val oldRes = analyzeBackgroundAndTextColorOld(bitmap, bounds)
        return ColorAnalysisResult(oldRes.first, oldRes.second, oldRes.third)
    }

    try {
        // Step 1: Crop vùng bounds từ bitmap gốc
        val cropRect = android.graphics.Rect(
            bounds.left.coerceAtLeast(0),
            bounds.top.coerceAtLeast(0),
            bounds.right.coerceAtMost(bitmap.width),
            bounds.bottom.coerceAtMost(bitmap.height)
        )
        val crop = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        
        val mat = org.opencv.core.Mat()
        org.opencv.android.Utils.bitmapToMat(crop, mat)
        crop.recycle()

        // Step 2: Grayscale
        val gray = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.cvtColor(mat, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)

        // Step 3: Tạo mask nét chữ (text strokes) bằng Adaptive Thresholding
        val binary = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.adaptiveThreshold(
            gray, binary, 255.0,
            org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            org.opencv.imgproc.Imgproc.THRESH_BINARY_INV,
            15, 8.0
        )

        // Giãn nở (Dilation) để tạo viền phủ hết nét chữ và viền
        val dilated = org.opencv.core.Mat()
        val kernel = org.opencv.imgproc.Imgproc.getStructuringElement(org.opencv.imgproc.Imgproc.MORPH_RECT, org.opencv.core.Size(3.0, 3.0))
        org.opencv.imgproc.Imgproc.dilate(binary, dilated, kernel)
        kernel.release()

        // Step 4: Tạo mask cho Nền (Background) bằng cách nghịch đảo dilated
        val bgMask = org.opencv.core.Mat()
        org.opencv.core.Core.bitwise_not(dilated, bgMask)

        // Tính màu trung bình của nền
        val bgMean = org.opencv.core.Core.mean(mat, bgMask)
        
        // Tính độ biến thiên màu nền (Standard Deviation)
        val mean = org.opencv.core.MatOfDouble()
        val stdDev = org.opencv.core.MatOfDouble()
        org.opencv.core.Core.meanStdDev(mat, mean, stdDev, bgMask)
        val stdVal = stdDev.get(0, 0)[0]
        
        // Đọc màu trung bình của nền
        val avgR = bgMean.`val`[0].toInt()
        val avgG = bgMean.`val`[1].toInt()
        val avgB = bgMean.`val`[2].toInt()
        val avgA = bgMean.`val`[3].toInt()
        
        val avgBgColor = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
        val brightness = (avgR + avgG + avgB) / 3

        // Phân loại BackgroundType dựa trên độ trong suốt và độ biến thiên
        val backgroundType = when {
            avgA < 200 -> com.example.ocrmanga.data.models.BackgroundType.TRANSPARENT
            brightness >= 235 && stdVal < 15 -> com.example.ocrmanga.data.models.BackgroundType.WHITE
            brightness >= 200 && stdVal < 25 -> {
                // Kiểm tra nếu các kênh màu RGB lệch nhau rất ít thì là trắng/xám trắng
                val rDev = kotlin.math.abs(avgR - avgG)
                val gDev = kotlin.math.abs(avgG - avgB)
                val bDev = kotlin.math.abs(avgB - avgR)
                val rgbDev = (rDev + gDev + bDev) / 3
                if (rgbDev <= 10) com.example.ocrmanga.data.models.BackgroundType.WHITE
                else com.example.ocrmanga.data.models.BackgroundType.COLORED
            }
            else -> com.example.ocrmanga.data.models.BackgroundType.COLORED
        }

        // Step 5: Phân tích màu chữ và màu viền chữ
        // - Thân chữ (Core Text) = nằm ở các pixel màu trắng (255) trong `binary`
        // - Viền chữ (Border/Stroke) = nằm ở các pixel trong dilated nhưng không có trong binary (dilated - binary)
        val borderMask = org.opencv.core.Mat()
        org.opencv.core.Core.subtract(dilated, binary, borderMask)

        // Tính màu trung bình của Thân chữ
        var textColor: Int? = null
        val textMean = org.opencv.core.Core.mean(mat, binary)
        val tR = textMean.`val`[0].toInt()
        val tG = textMean.`val`[1].toInt()
        val tB = textMean.`val`[2].toInt()
        val tA = if (textMean.`val`[3] == 0.0) 0xFF else textMean.`val`[3].toInt()
        
        val rawTextColor = (tA shl 24) or (tR shl 16) or (tG shl 8) or tB
        
        // Tính màu trung bình của Viền chữ (nếu có pixel viền)
        var borderColor: Int? = null
        var borderThickness = 0f
        
        val borderPixelsCount = org.opencv.core.Core.countNonZero(borderMask)
        val textPixelsCount = org.opencv.core.Core.countNonZero(binary)
        
        if (borderPixelsCount > 5 && textPixelsCount > 5) {
            val borderMean = org.opencv.core.Core.mean(mat, borderMask)
            val bR = borderMean.`val`[0].toInt()
            val bG = borderMean.`val`[1].toInt()
            val bB = borderMean.`val`[2].toInt()
            val bA = if (borderMean.`val`[3] == 0.0) 0xFF else borderMean.`val`[3].toInt()
            val rawBorderColor = (bA shl 24) or (bR shl 16) or (bG shl 8) or bB
            
            // Tính khoảng cách màu giữa màu chữ và màu viền
            val dist = kotlin.math.sqrt(
                ((tR - bR) * (tR - bR) + (tG - bG) * (tG - bG) + (tB - bB) * (tB - bB)).toDouble()
            )
            
            // Khoảng cách màu giữa viền và nền
            val bgDist = kotlin.math.sqrt(
                ((avgR - bR) * (avgR - bR) + (avgG - bG) * (avgG - bG) + (avgB - bB) * (avgB - bB)).toDouble()
            )
            
            // Nếu màu viền và màu chữ thực sự khác nhau rõ rệt (khoảng cách màu > 45)
            // và viền khác nền rõ rệt (bgDist > 30) thì chữ đó có viền
            if (dist > 45.0 && bgDist > 30.0) {
                textColor = rawTextColor
                borderColor = rawBorderColor
                
                // Ước lượng độ dày viền từ tỉ lệ diện tích: borderPixels / textPixels
                val areaRatio = borderPixelsCount.toFloat() / textPixelsCount.toFloat()
                borderThickness = (areaRatio * 1.5f).coerceIn(1.0f, 4.0f)
            } else {
                textColor = rawTextColor
            }
        } else {
            textColor = rawTextColor
        }

        // Dọn dẹp bộ nhớ OpenCV Mat
        mat.release()
        gray.release()
        binary.release()
        dilated.release()
        bgMask.release()
        borderMask.release()
        mean.release()
        stdDev.release()

        // Tránh Nền sáng + Chữ sáng hoặc Nền tối + Chữ tối
        textColor?.let { color ->
            val cr = (color shr 16) and 0xFF
            val cg = (color shr 8) and 0xFF
            val cb = color and 0xFF
            val textBright = (cr + cg + cb) / 3
            if (brightness > 170 && textBright > 170) {
                textColor = 0xFF000000.toInt()
            } else if (brightness < 85 && textBright < 85) {
                textColor = 0xFFFFFFFF.toInt()
            }
        }

        if (textColor == null) {
            textColor = if (brightness > 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        return ColorAnalysisResult(
            backgroundType = backgroundType,
            backgroundColor = if (backgroundType != com.example.ocrmanga.data.models.BackgroundType.WHITE) avgBgColor else null,
            textColor = textColor,
            borderColor = borderColor,
            borderThickness = borderThickness
        )

    } catch (e: Exception) {
        Log.e("ImageTextUtils", "Error during OpenCV color analysis, falling back to old method", e)
        val oldRes = analyzeBackgroundAndTextColorOld(bitmap, bounds)
        return ColorAnalysisResult(oldRes.first, oldRes.second, oldRes.third)
    }
}

fun analyzeBackgroundAndTextColor(bitmap: Bitmap?, bounds: android.graphics.Rect): Triple<com.example.ocrmanga.data.models.BackgroundType, Int?, Int?> {
    val res = analyzeColorsAndBorder(bitmap, bounds)
    return Triple(res.backgroundType, res.backgroundColor, res.textColor)
}

// Phân tích màu nền và màu text của text block từ bitmap gốc (Thuật toán cũ dự phòng)
private fun analyzeBackgroundAndTextColorOld(bitmap: Bitmap?, bounds: android.graphics.Rect): Triple<com.example.ocrmanga.data.models.BackgroundType, Int?, Int?> {
    if (bitmap == null) return Triple(com.example.ocrmanga.data.models.BackgroundType.WHITE, null, null)
    
    try {
        val width = bounds.width()
        val height = bounds.height()
        
        // Lấy mẫu từ nhiều điểm bên trong bounds
        val allSamplePoints = mutableListOf<Int>()
        val margin = 2
        
        // Lấy mẫu từ viền và trung tâm
        val samplePositions = listOf(
            // 4 góc bên trong
            Pair(bounds.left + margin, bounds.top + margin),
            Pair(bounds.right - margin, bounds.top + margin),
            Pair(bounds.left + margin, bounds.bottom - margin),
            Pair(bounds.right - margin, bounds.bottom - margin),
            // 4 cạnh bên trong
            Pair(bounds.centerX(), bounds.top + margin),
            Pair(bounds.centerX(), bounds.bottom - margin),
            Pair(bounds.left + margin, bounds.centerY()),
            Pair(bounds.right - margin, bounds.centerY()),
            // Điểm trung tâm
            Pair(bounds.centerX(), bounds.centerY()),
            // Thêm các điểm phụ để có nhiều mẫu hơn
            Pair(bounds.left + width / 4, bounds.top + margin),
            Pair(bounds.left + 3 * width / 4, bounds.top + margin),
            Pair(bounds.left + width / 4, bounds.bottom - margin),
            Pair(bounds.left + 3 * width / 4, bounds.bottom - margin)
        )
        
        samplePositions.forEach { (x, y) ->
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                allSamplePoints.add(bitmap.getPixel(x, y))
            }
        }
        
        if (allSamplePoints.isEmpty()) return Triple(com.example.ocrmanga.data.models.BackgroundType.WHITE, null, null)
        
        // Phân tích các pixel biên (border pixels) để phân biệt Nền Trắng (Speech bubble) và Nền Cảnh (Background)
        val borderPixels = mutableListOf<Int>()
        samplePositions.forEachIndexed { index, (x, y) ->
            if (index != 8) { // Bỏ qua điểm trung tâm (thường là nét chữ)
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    borderPixels.add(bitmap.getPixel(x, y))
                }
            }
        }

        // Đếm số lượng pixel biên có màu trắng hoặc gần trắng
        val whiteBorderCount = borderPixels.count { color ->
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            // Một pixel được coi là gần trắng nếu cả 3 kênh đều >= 220 và chênh lệch giữa các kênh thấp
            r >= 220 && g >= 220 && b >= 220 && (maxC - minC) <= 15
        }

        // Tỷ lệ pixel biên màu trắng
        val whiteBorderRatio = if (borderPixels.isNotEmpty()) whiteBorderCount.toFloat() / borderPixels.size else 0f

        val samplePoints = if (whiteBorderRatio >= 0.45f) {
            // TRƯỜNG HỢP 1: Nền trắng (Speech bubble)
            // Áp dụng bộ lọc pixel sáng cũ để loại bỏ nét chữ tối và tính nền trắng chính xác
            val brightPixels = allSamplePoints.filter { color ->
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val pixelBrightness = (r + g + b) / 3
                pixelBrightness > 150
            }
            if (brightPixels.size >= allSamplePoints.size * 0.3) {
                brightPixels
            } else {
                allSamplePoints
            }
        } else {
            // TRƯỜNG HỢP 2: Nền cảnh có màu hoặc nền tối (Colored background)
            // Tránh lọc pixel sáng cứng nhắc (sẽ loại bỏ hết nền tối/màu và chỉ giữ lại viền trắng của chữ)
            // Dùng phương pháp Trimmed Mean trên các pixel biên: loại bỏ 20% pixel sáng nhất (có thể là viền trắng)
            // và 20% pixel tối nhất (có thể là nét chữ đè lên biên) để lấy màu nền cảnh chuẩn nhất.
            if (borderPixels.isNotEmpty()) {
                val sortedBorder = borderPixels.sortedBy { color ->
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    (r + g + b) / 3
                }
                val trimCount = (sortedBorder.size * 0.2).toInt()
                if (sortedBorder.size - 2 * trimCount >= 3) {
                    sortedBorder.subList(trimCount, sortedBorder.size - trimCount)
                } else {
                    sortedBorder
                }
            } else {
                allSamplePoints
            }
        }
        
        // Tính màu trung bình từ các pixel đã lọc
        var totalR = 0
        var totalG = 0
        var totalB = 0
        var totalA = 0
        
        samplePoints.forEach { color ->
            totalR += (color shr 16) and 0xFF
            totalG += (color shr 8) and 0xFF
            totalB += color and 0xFF
            totalA += (color shr 24) and 0xFF
        }
        
        val avgR = totalR / samplePoints.size
        val avgG = totalG / samplePoints.size
        val avgB = totalB / samplePoints.size
        val avgA = totalA / samplePoints.size
        
        val avgColor = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
        
        // Xác định loại nền dựa trên brightness và color variance
        val brightness = (avgR + avgG + avgB) / 3
        
        // Tính độ biến thiên màu sắc để phát hiện nền có màu (chỉ từ pixel sáng)
        var colorVariance = 0
        var colorChannelVariance = 0
        samplePoints.forEach { color ->
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val pixelBrightness = (r + g + b) / 3
            colorVariance += kotlin.math.abs(pixelBrightness - brightness)
            
            val maxChannel = maxOf(r, g, b)
            val minChannel = minOf(r, g, b)
            colorChannelVariance += (maxChannel - minChannel)
        }
        colorVariance /= samplePoints.size
        colorChannelVariance /= samplePoints.size
        
        // Tính độ lệch chuẩn của RGB để phát hiện màu thật
        val rVariance = kotlin.math.abs(avgR - avgG)
        val gVariance = kotlin.math.abs(avgG - avgB)
        val bVariance = kotlin.math.abs(avgB - avgR)
        val rgbDeviation = (rVariance + gVariance + bVariance) / 3
        
        // Phân tích màu text bằng cách lấy mẫu lưới bên trong bounds,
        // chọn các pixel có độ sáng khác biệt so với nền (ứng viên text).
        var textColor: Int? = null
        try {
            val candidates = mutableListOf<Int>()
            val backgroundBrightness = brightness
            
            // Helper to check and add candidate
            fun checkAndAdd(pixelColor: Int) {
                val r = (pixelColor shr 16) and 0xFF
                val g = (pixelColor shr 8) and 0xFF
                val b = pixelColor and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                
                // Check contrast: Text must differ from background
                val diff = kotlin.math.abs(lum - backgroundBrightness)
                if (diff >= 30) { // Threshold 30 to avoid noise/artifacts
                    candidates.add(pixelColor)
                }
            }

            // 1. Grid Sampling
            val gridSize = 6 // 6x6 grid sampling
            val stepX = maxOf(1, width / gridSize)
            val stepY = maxOf(1, height / gridSize)

            for (dy in 0 until gridSize) {
                for (dx in 0 until gridSize) {
                    val x = bounds.left + dx * stepX + stepX / 2
                    val y = bounds.top + dy * stepY + stepY / 2
                    if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                        try { checkAndAdd(bitmap.getPixel(x, y)) } catch (_: Exception) {}
                    }
                }
            }

            // 2. Dense Scan on Center Lines (Fallback if grid missed)
            // If we found specific candidates in grid, use them. If not (text might be thin or sparse), 
            // scan the center lines where text is likely to be.
            if (candidates.size < 3) {
                val cy = bounds.centerY()
                // Scan horizontal center line
                for (x in bounds.left until bounds.right step 2) {
                     if (x in 0 until bitmap.width && cy in 0 until bitmap.height) {
                        try { checkAndAdd(bitmap.getPixel(x, cy)) } catch (_: Exception) {}
                     }
                }
                
                // Scan vertical center line
                val cx = bounds.centerX()
                for (y in bounds.top until bounds.bottom step 2) {
                     if (cx in 0 until bitmap.width && y in 0 until bitmap.height) {
                        try { checkAndAdd(bitmap.getPixel(cx, y)) } catch (_: Exception) {}
                     }
                }
            }

            if (candidates.isNotEmpty()) {
                // Phân tích xem có các pixel màu sắc (chromatic) không
                val chromaticCandidates = candidates.filter { c ->
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val maxChannel = maxOf(r, g, b)
                    val minChannel = minOf(r, g, b)
                    (maxChannel - minChannel) > 35 // Có sắc độ rõ ràng (chroma > 35)
                }
                
                // Nếu có đủ pixel có sắc độ (ít nhất 5 pixel hoặc ít nhất 10% ứng viên),
                // ta sẽ ưu tiên chọn màu từ các pixel này để tránh bị viền đen/xám lấn át màu thực của chữ.
                val finalCandidates = if (chromaticCandidates.size >= 5 || (chromaticCandidates.isNotEmpty() && chromaticCandidates.size >= candidates.size * 0.10)) {
                    chromaticCandidates
                } else {
                    candidates
                }

                // Quantize to 16-level buckets per channel to find dominant color
                val buckets = mutableMapOf<Int, MutableList<Int>>()
                for (c in finalCandidates) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val keyR = (r / 16) and 0xF
                    val keyG = (g / 16) and 0xF
                    val keyB = (b / 16) and 0xF
                    val key = (keyR shl 8) or (keyG shl 4) or keyB
                    buckets.getOrPut(key) { mutableListOf() }.add(c)
                }
                val dominantBucket = buckets.maxByOrNull { it.value.size }?.value
                if (!dominantBucket.isNullOrEmpty()) {
                    var totR = 0; var totG = 0; var totB = 0; var totA = 0
                    dominantBucket.forEach { cc ->
                        totR += (cc shr 16) and 0xFF
                        totG += (cc shr 8) and 0xFF
                        totB += cc and 0xFF
                        totA += (cc shr 24) and 0xFF
                    }
                    val n = dominantBucket.size
                    val avgR = totR / n
                    val avgG = totG / n
                    val avgB = totB / n
                    val avgA = if (totA == 0) 0xFF else totA / n
                    textColor = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
                }
            }

            if (textColor == null) {
                // Smart Default: Contrast with background
                textColor = if (backgroundBrightness > 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            
            // --- STRICT FIX: Prevent White-on-White and Black-on-Black ---
            // Yêu cầu: Nền sáng -> không được chứa text trắng. Nền tối -> không được chứa text đen.
            textColor.let { color ->
                val tr = (color shr 16) and 0xFF
                val tg = (color shr 8) and 0xFF
                val tb = color and 0xFF
                val textBrightness = (tr + tg + tb) / 3
                
                // Nếu nền sáng (> 170) mà text cũng sáng (> 170) -> Force Black
                if (backgroundBrightness > 170 && textBrightness > 170) {
                    textColor = 0xFF000000.toInt()
                }
                // Nếu nền tối (< 85) mà text cũng tối (< 85) -> Force White
                else if (backgroundBrightness < 85 && textBrightness < 85) {
                    textColor = 0xFFFFFFFF.toInt()
                }
            }
        } catch (e: Exception) {
            textColor = if (brightness > 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        val backgroundType = when {
            // Nền trong suốt: alpha thấp (kiểm tra trước)
            avgA < 200 -> com.example.ocrmanga.data.models.BackgroundType.TRANSPARENT
            
            // Nền trắng thuần: brightness rất cao + RGB đồng đều
            brightness >= 245 && rgbDeviation <= 5 && colorVariance <= 15 -> {
                com.example.ocrmanga.data.models.BackgroundType.WHITE
            }
            
            // Nền trắng/xám nhạt: brightness cao + RGB gần nhau
            brightness >= 230 && rgbDeviation <= 10 && colorChannelVariance <= 15 -> {
                // Kiểm tra kỹ: nếu TẤT CẢ RGB đều >= 230 thì là trắng
                if (avgR >= 230 && avgG >= 230 && avgB >= 230) {
                    com.example.ocrmanga.data.models.BackgroundType.WHITE
                } else {
                    // Có ít nhất 1 kênh < 230 -> có màu
                    com.example.ocrmanga.data.models.BackgroundType.COLORED
                }
            }
            
            // Nền gần trắng nhưng có chút sắc độ
            brightness >= 210 && rgbDeviation <= 20 && colorChannelVariance <= 25 -> {
                // Kiểm tra lại lần nữa với ngưỡng thấp hơn
                if (avgR >= 210 && avgG >= 210 && avgB >= 210 && rgbDeviation <= 12) {
                    com.example.ocrmanga.data.models.BackgroundType.WHITE
                } else {
                    com.example.ocrmanga.data.models.BackgroundType.COLORED
                }
            }
            
            // Nền có màu rõ ràng: có độ lệch lớn giữa các kênh hoặc brightness thấp
            colorChannelVariance > 25 || rgbDeviation > 20 || brightness < 210 -> {
                com.example.ocrmanga.data.models.BackgroundType.COLORED
            }
            
            // Default: dựa vào brightness
            else -> if (brightness >= 200) {
                com.example.ocrmanga.data.models.BackgroundType.WHITE
            } else {
                com.example.ocrmanga.data.models.BackgroundType.COLORED
            }
        }
        
        try {
            val tHex = textColor?.let { String.format("#%08X", it) } ?: "null"
            val bgHex = if (backgroundType != com.example.ocrmanga.data.models.BackgroundType.WHITE) avgColor.let { String.format("#%08X", it) } ?: "null" else "WHITE"
        } catch (_: Exception) { }
        return Triple(backgroundType, if (backgroundType != com.example.ocrmanga.data.models.BackgroundType.WHITE) avgColor else null, textColor)
        
    } catch (e: Exception) {
        // Nếu xảy ra exception, mặc định màu đen (0xFF000000)
        return Triple(com.example.ocrmanga.data.models.BackgroundType.WHITE, null, 0xFF000000.toInt())
    }
}

// Hàm wrapper để tương thích với code cũ
fun analyzeBackgroundColor(bitmap: Bitmap?, bounds: android.graphics.Rect): Pair<com.example.ocrmanga.data.models.BackgroundType, Int?> {
    val (backgroundType, avgColor, _) = analyzeBackgroundAndTextColor(bitmap, bounds)
    return Pair(backgroundType, avgColor)
}

// Vẽ overlay bán trong suốt cho text trên nền có màu với màu nền gốc
