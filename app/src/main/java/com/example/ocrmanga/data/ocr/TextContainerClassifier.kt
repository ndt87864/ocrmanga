package com.example.ocrmanga.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.ocrmanga.data.ocr.models.TextContainerInfo
import com.example.ocrmanga.data.ocr.models.TextContainerType
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Phân loại các loại container chứa text trong manga
 * Phân biệt: oval bubble, rectangular box, transparent, no container
 */
class TextContainerClassifier {

    companion object {
        private const val TAG = "TextContainerClassifier"

        // Thresholds cho phân loại
        private const val OVAL_CIRCULARITY_THRESHOLD = 0.65
        private const val RECTANGULAR_CIRCULARITY_THRESHOLD = 0.5
        private const val CORNER_COUNT_RECT = 4
        private const val CORNER_COUNT_OVAL_MIN = 8
        private const val TRANSPARENCY_GRADIENT_THRESHOLD = 30.0
        private const val TRANSPARENCY_CONTRAST_THRESHOLD = 20.0
        private const val STRONG_BORDER_EDGE_DENSITY = 0.15f
    }

    /**
     * Phân loại container cho một text region
     */
    fun classifyContainer(
        image: Bitmap,
        textBoundingBox: Rect
    ): TextContainerInfo {
        if (!OpenCvInitializer.ensureInitialized()) {
            Log.w(TAG, "[CLASSIFY] OpenCV not initialized, returning UNKNOWN")
            return TextContainerInfo(
                type = TextContainerType.UNKNOWN,
                confidence = 0.0f,
                hasStrongBorder = false,
                borderThickness = 0f,
                backgroundOpacity = 0.5f,
                circularity = 0.0,
                aspectRatio = 1.0,
                cornerCount = 0
            )
        }

        try {
            Log.d(TAG, "[CLASSIFY] Starting classification for bounds: $textBoundingBox")

            // Mở rộng vùng để bao gồm cả container (1.5x)
            val expandedRegion = expandBoundingBox(textBoundingBox, image.width, image.height, 1.5f)

            // Chuyển bitmap sang Mat
            val mat = Mat()
            Utils.bitmapToMat(image, mat)
            val roi = Mat(mat, convertToOpenCVRect(expandedRegion))

            // 1. Phát hiện contours
            val contours = detectContours(roi)

            // 2. Tìm contour bao quanh text
            val containerContour = findContainerContour(contours, textBoundingBox, expandedRegion)

            val result = if (containerContour != null) {
                // Có contour rõ ràng - phân tích hình dạng
                analyzeContourShape(containerContour, roi)
            } else {
                // Không có contour rõ - kiểm tra transparent hoặc no container
                analyzeWithoutContour(roi)
            }

            // Cleanup
            mat.release()
            roi.release()
            contours.forEach { it.release() }

            Log.d(TAG, "[CLASSIFY] Result: type=${result.type}, confidence=${result.confidence}, hasStrongBorder=${result.hasStrongBorder}")
            return result

        } catch (error: Throwable) {
            Log.e(TAG, "Error classifying container", error)
            return TextContainerInfo(
                type = TextContainerType.UNKNOWN,
                confidence = 0.3f,
                hasStrongBorder = false,
                borderThickness = 0f,
                backgroundOpacity = 0.5f,
                circularity = 0.0,
                aspectRatio = 1.0,
                cornerCount = 0
            )
        }
    }

    /**
     * Mở rộng bounding box theo tỷ lệ
     */
    private fun expandBoundingBox(
        box: Rect,
        imageWidth: Int,
        imageHeight: Int,
        scale: Float
    ): Rect {
        val centerX = box.centerX()
        val centerY = box.centerY()
        val newWidth = (box.width() * scale).toInt()
        val newHeight = (box.height() * scale).toInt()

        val left = max(0, centerX - newWidth / 2)
        val top = max(0, centerY - newHeight / 2)
        val right = min(imageWidth, centerX + newWidth / 2)
        val bottom = min(imageHeight, centerY + newHeight / 2)

        return Rect(left, top, right, bottom)
    }

    /**
     * Chuyển Android Rect sang OpenCV Rect
     */
    private fun convertToOpenCVRect(rect: Rect): org.opencv.core.Rect {
        return org.opencv.core.Rect(
            rect.left,
            rect.top,
            rect.width(),
            rect.height()
        )
    }

    /**
     * Phát hiện contours trong ảnh
     */
    private fun detectContours(image: Mat): List<MatOfPoint> {
        var gray = Mat()
        val edges = Mat()

        // Chuyển sang grayscale
        if (image.channels() > 1) {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            gray = image.clone()
        }

        // Gaussian blur để giảm noise
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // Canny edge detection
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        // Morphological closing để đóng các khoảng trống
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        // Tìm contours
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        // Lọc contours quá nhỏ
        val filtered = contours.filter { contour ->
            val area = Imgproc.contourArea(contour)
            area > 100
        }

        // Cleanup
        gray.release()
        edges.release()
        kernel.release()
        hierarchy.release()

        return filtered
    }

    /**
     * Tìm contour bao quanh text region
     */
    private fun findContainerContour(
        contours: List<MatOfPoint>,
        textBox: Rect,
        expandedRegion: Rect
    ): MatOfPoint? {
        if (contours.isEmpty()) return null

        // Điều chỉnh textBox về tọa độ local của expandedRegion
        val localTextBox = Rect(
            textBox.left - expandedRegion.left,
            textBox.top - expandedRegion.top,
            textBox.right - expandedRegion.left,
            textBox.bottom - expandedRegion.top
        )

        // Tìm contour lớn nhất chứa text box
        return contours
            .filter { contour ->
                val boundingRect = Imgproc.boundingRect(contour)
                val androidRect = Rect(
                    boundingRect.x,
                    boundingRect.y,
                    boundingRect.x + boundingRect.width,
                    boundingRect.y + boundingRect.height
                )

                // Contour phải bao quanh ít nhất 70% text box
                val intersection = Rect(androidRect)
                intersection.intersect(localTextBox)
                val intersectionArea = intersection.width() * intersection.height()
                val textArea = localTextBox.width() * localTextBox.height()

                intersectionArea.toFloat() / textArea > 0.7f
            }
            .maxByOrNull { Imgproc.contourArea(it) }
    }

    /**
     * Phân tích hình dạng contour
     */
    private fun analyzeContourShape(contour: MatOfPoint, image: Mat): TextContainerInfo {
        val area = Imgproc.contourArea(contour)
        val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)

        // Circularity: 4π × area / perimeter²
        val circularity = if (perimeter > 0) {
            (4 * Math.PI * area / (perimeter * perimeter)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        // Bounding rectangle
        val boundingRect = Imgproc.boundingRect(contour)
        val aspectRatio = boundingRect.width.toDouble() / boundingRect.height.coerceAtLeast(1)

        // Approximate polygon để đếm góc
        val approx = MatOfPoint2f()
        val contour2f = MatOfPoint2f(*contour.toArray())
        val epsilon = 0.02 * perimeter
        Imgproc.approxPolyDP(contour2f, approx, epsilon, true)
        val cornerCount = approx.rows()

        // Tính diện tích của bounding box
        val boundingArea = boundingRect.width.toDouble() * boundingRect.height.toDouble()
        // Tỷ lệ lấp đầy bounding box (extent)
        val extent = if (boundingArea > 0) area / boundingArea else 0.0

        // Tính edge density để xác định độ rõ của viền
        val edgeDensity = calculateEdgeDensity(boundingRect, image)
        val hasStrongBorder = edgeDensity > STRONG_BORDER_EDGE_DENSITY

        // Ước tính độ dày viền
        val borderThickness = estimateBorderThickness(contour, image)

        // Phân loại dựa trên đặc điểm hình học & border
        val (type, confidence) = when {
            // Hộp thoại (Speech Frame) thường có dạng hình chữ nhật: lấp đầy phần lớn bounding box (> 0.85)
            // hoặc có từ 4 đến khoảng 6-8 góc (do bo tròn)
            (extent > 0.85 || cornerCount in 4..8) && hasStrongBorder && circularity < 0.85 -> {
                TextContainerType.SPEECH_FRAME to (extent.toFloat().coerceIn(0.6f, 1.0f))
            }

            // Bong bóng chat (Speech Bubble) có viền rõ, thường lấp đầy từ 60%-85% bounding box hoặc có độ tròn tương đối
            hasStrongBorder -> {
                TextContainerType.SPEECH_BUBBLE to (if (circularity > 0.5) circularity.toFloat() else 0.6f)
            }

            // Viền yếu -> bán trong suốt hoặc text nổi thẳng lên nền
            !hasStrongBorder -> {
                TextContainerType.SEMI_TRANSPARENT to 0.7f
            }

            else -> {
                TextContainerType.UNKNOWN to 0.5f
            }
        }

        // Phân tích background opacity
        val backgroundOpacity = analyzeBackgroundOpacity(boundingRect, image)

        // Cleanup
        approx.release()
        contour2f.release()

        return TextContainerInfo(
            type = type,
            confidence = confidence,
            hasStrongBorder = hasStrongBorder,
            borderThickness = borderThickness,
            backgroundOpacity = backgroundOpacity,
            circularity = circularity,
            aspectRatio = aspectRatio,
            cornerCount = cornerCount
        )
    }

    /**
     * Phân tích khi không có contour rõ ràng
     */
    private fun analyzeWithoutContour(image: Mat): TextContainerInfo {
        val hasGradient = detectGradient(image)
        val contrast = calculateContrast(image)
        val lowContrast = contrast < TRANSPARENCY_CONTRAST_THRESHOLD

        val type = when {
            hasGradient || lowContrast -> TextContainerType.SEMI_TRANSPARENT
            else -> TextContainerType.FREE_TEXT
        }

        val backgroundOpacity = if (lowContrast || hasGradient) 0.3f else 0.0f

        return TextContainerInfo(
            type = type,
            confidence = 0.6f,
            hasStrongBorder = false,
            borderThickness = 0f,
            backgroundOpacity = backgroundOpacity,
            circularity = 0.0,
            aspectRatio = 1.0,
            cornerCount = 0
        )
    }

    /**
     * Tính edge density trong một vùng
     */
    private fun calculateEdgeDensity(bounds: org.opencv.core.Rect, image: Mat): Float {
        var gray = Mat()
        if (image.channels() > 1) {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            gray = image.clone()
        }

        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        val roi = Mat(edges, bounds)
        val nonZero = Core.countNonZero(roi)
        val total = bounds.width * bounds.height

        gray.release()
        edges.release()
        roi.release()

        return if (total > 0) nonZero.toFloat() / total else 0f
    }

    /**
     * Ước tính độ dày viền
     */
    private fun estimateBorderThickness(contour: MatOfPoint, image: Mat): Float {
        // Đơn giản hóa: tính từ perimeter và area
        val area = Imgproc.contourArea(contour)
        val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)

        // Ước tính: thickness ≈ (perimeter - sqrt(4π×area)) / 2
        val innerPerimeter = Math.sqrt(4 * Math.PI * area)
        val thickness = ((perimeter - innerPerimeter) / 2).coerceAtLeast(0.0)

        return thickness.toFloat().coerceIn(0f, 10f)
    }

    /**
     * Phân tích độ mờ đục của background
     */
    private fun analyzeBackgroundOpacity(bounds: org.opencv.core.Rect, image: Mat): Float {
        val roi = Mat(image, bounds)
        var gray = Mat()

        if (roi.channels() > 1) {
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            gray = roi.clone()
        }

        // Tính histogram để xác định phân bố màu
        val hist = Mat()
        Imgproc.calcHist(
            listOf(gray),
            MatOfInt(0),
            Mat(),
            hist,
            MatOfInt(256),
            MatOfFloat(0f, 256f)
        )

        // Nếu histogram tập trung ở giá trị cao (sáng) -> nền trắng/sáng -> opacity cao
        var brightPixels = 0
        for (i in 200..255) {
            brightPixels += hist.get(i, 0)[0].toInt()
        }

        val totalPixels = bounds.width * bounds.height
        val opacity = brightPixels.toFloat() / totalPixels

        roi.release()
        gray.release()
        hist.release()

        return opacity.coerceIn(0f, 1f)
    }

    /**
     * Phát hiện gradient trong ảnh
     */
    private fun detectGradient(image: Mat): Boolean {
        var gray = Mat()
        if (image.channels() > 1) {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            gray = image.clone()
        }

        // Tính gradient sử dụng Sobel
        val gradX = Mat()
        val gradY = Mat()
        Imgproc.Sobel(gray, gradX, CvType.CV_32F, 1, 0)
        Imgproc.Sobel(gray, gradY, CvType.CV_32F, 0, 1)

        val magnitude = Mat()
        Core.magnitude(gradX, gradY, magnitude)

        val mean = Core.mean(magnitude)

        gray.release()
        gradX.release()
        gradY.release()
        magnitude.release()

        // Gradient nhẹ = bán trong suốt
        return mean.`val`[0] < TRANSPARENCY_GRADIENT_THRESHOLD
    }

    /**
     * Tính độ tương phản
     */
    private fun calculateContrast(image: Mat): Double {
        var gray = Mat()
        if (image.channels() > 1) {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            gray = image.clone()
        }

        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        Core.meanStdDev(gray, mean, stdDev)

        val contrast = stdDev.get(0, 0)[0]

        gray.release()
        mean.release()
        stdDev.release()

        return contrast
    }
}
