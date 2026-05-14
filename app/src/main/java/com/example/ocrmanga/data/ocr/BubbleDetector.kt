package com.example.ocrmanga.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.ocrmanga.data.ocr.models.TextContainerInfo
import com.example.ocrmanga.data.ocr.models.TextContainerType
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenCV-powered bubble detector.
 *
 * Uses edge detection + contour analysis to find speech bubble borders
 * around text blocks, enabling accurate overlay inset calculation,
 * container classification, and TH1/TH2 determination.
 *
 * Pipeline:
 *   1. Crop region around text bounds (with margin)
 *   2. Grayscale + Gaussian blur
 *   3. Canny edge detection
 *   4. Morphological close to connect edges
 *   5. Find contours
 *   6. Select contour that encloses text bounds
 *   7. Calculate circularity, border thickness, container type
 */
class BubbleDetector {

    companion object {
        private const val TAG = "BubbleDetector"
    }

    data class BubbleDetectionResult(
        val containerInfo: TextContainerInfo?,
        val bubbleBounds: Rect?,
        val isSolidBubble: Boolean,
        val confidence: Float
    )

    /**
     * Detect bubble container around text bounds.
     *
     * @param bitmap    Full image bitmap
     * @param textBounds Bounds of the detected text (from OCR)
     * @return BubbleDetectionResult with container info, bubble bounds, and classification
     */
    fun detectBubble(
        bitmap: Bitmap,
        textBounds: Rect,
        imageWidth: Int = bitmap.width,
        imageHeight: Int = bitmap.height
    ): BubbleDetectionResult {
        if (!OpenCvInitializer.ensureInitialized()) {
            Log.w(TAG, "OpenCV unavailable, skipping bubble detection")
            return BubbleDetectionResult(null, null, false, 0f)
        }

        try {
            // Step 1: Crop region around text with adaptive margin
            val baseMargin = max(textBounds.width(), textBounds.height())
            val margin = max(baseMargin, 40) // At least 40px around text

            val cropRect = Rect(
                (textBounds.left - margin).coerceAtLeast(0),
                (textBounds.top - margin).coerceAtLeast(0),
                (textBounds.right + margin).coerceAtMost(imageWidth - 1),
                (textBounds.bottom + margin).coerceAtMost(imageHeight - 1)
            )

            if (cropRect.width() < 20 || cropRect.height() < 20) {
                return BubbleDetectionResult(null, null, false, 0f)
            }

            val crop = try {
                Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top,
                    cropRect.width(), cropRect.height())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to crop bitmap", e)
                return BubbleDetectionResult(null, null, false, 0f)
            }

            // Step 2: Convert to OpenCV Mat
            val mat = Mat()
            Utils.bitmapToMat(crop, mat)

            // Step 3: Grayscale
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            mat.release()

            // Step 4: Gaussian blur to reduce noise
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            // Step 5: Adaptive thresholding for binarization
            // This works better than Canny alone for manga with varying line thickness
            val binary = Mat()
            Imgproc.adaptiveThreshold(
                blurred, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, // Invert: text/edges = white, background = black
                15, 8.0
            )
            blurred.release()

            // Step 6: Morphological close to connect nearby edges (bubble border)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            val closed = Mat()
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            binary.release()

            // Step 7: Find contours
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                closed, contours, hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )
            closed.release()
            hierarchy.release()

            // Convert text bounds to crop-local coordinates
            val textInCrop = org.opencv.core.Rect(
                textBounds.left - cropRect.left,
                textBounds.top - cropRect.top,
                textBounds.width(),
                textBounds.height()
            )

            // Step 8: Score each contour to find the best bubble candidate
            var bestContour: MatOfPoint? = null
            var bestScore = -1f
            var hasEnclosingContour = false

            for (contour in contours) {
                val contourRect = Imgproc.boundingRect(contour)
                val area = contourRect.area().toFloat()
                if (area < 200.0f) {
                    contour.release()
                    continue
                }

                // Check if contour encloses text bounds
                val enclosesText = textInCrop.x >= contourRect.x &&
                        textInCrop.y >= contourRect.y &&
                        textInCrop.x + textInCrop.width <= contourRect.x + contourRect.width &&
                        textInCrop.y + textInCrop.height <= contourRect.y + contourRect.height

                // Calculate overlap with text bounds
                val overlapLeft = max(textInCrop.x, contourRect.x)
                val overlapTop = max(textInCrop.y, contourRect.y)
                val overlapRight = min(textInCrop.x + textInCrop.width, contourRect.x + contourRect.width)
                val overlapBottom = min(textInCrop.y + textInCrop.height, contourRect.y + contourRect.height)
                val overlapArea = max(0, overlapRight - overlapLeft) * max(0, overlapBottom - overlapTop)
                val textArea = textInCrop.area().toFloat()
                val overlapRatio = if (textArea > 0) overlapArea / textArea else 0f

                // Calculate how well contour fits around text (not too tight, not too loose)
                val enclosureRatio = if (textArea > 0) area / textArea else 0f
                val fitScore = when {
                    enclosesText -> {
                        // Ideal: contour encloses text with reasonable margin
                        // Perfect enclosure ratio is ~2-6x text area for a bubble
                        val sizeScore = when {
                            enclosureRatio in 2.0f..8.0f -> 1.0f
                            enclosureRatio in 1.2f..15.0f -> 0.7f
                            else -> 0.4f
                        }
                        hasEnclosingContour = true
                        overlapRatio * 0.3f + sizeScore * 0.7f
                    }
                    overlapRatio > 0.6f && !hasEnclosingContour -> {
                        // Text overflows contour but significant overlap
                        val contourArea = Imgproc.contourArea(contour).toFloat()
                        val boundingArea = (contourRect.width * contourRect.height).toFloat()
                        val solidityScore = if (boundingArea > 0) contourArea / boundingArea else 0f
                        0.5f + solidityScore * 0.3f + overlapRatio * 0.2f
                    }
                    else -> 0f
                }

                if (fitScore > bestScore) {
                    if (bestContour != null) bestContour.release()
                    bestContour = contour
                    bestScore = fitScore
                } else {
                    contour.release()
                }
            }

            // Step 9: Fallback — no good contour found
            if (bestContour == null || bestScore < 0.3f) {
                crop.recycle()
                return BubbleDetectionResult(null, null, false, 0f)
            }

            val bubbleCvRect = Imgproc.boundingRect(bestContour)

            // Convert back to image coordinates
            val bubbleBounds = Rect(
                (bubbleCvRect.x + cropRect.left).coerceAtLeast(0),
                (bubbleCvRect.y + cropRect.top).coerceAtLeast(0),
                (bubbleCvRect.x + bubbleCvRect.width + cropRect.left).coerceAtMost(imageWidth - 1),
                (bubbleCvRect.y + bubbleCvRect.height + cropRect.top).coerceAtMost(imageHeight - 1)
            )

            // Step 10: Calculate circularity
            val contourArea = Imgproc.contourArea(bestContour).toDouble()
            val contourPerimeter = Imgproc.arcLength(
                MatOfPoint2f(*bestContour.toArray()), true
            ).toDouble()
            val circularity = if (contourPerimeter > 0)
                4.0 * PI * contourArea / (contourPerimeter * contourPerimeter) else 0.0

            val hasOvalShape = circularity > 0.5
            val cornerCount = if (hasOvalShape) 0 else {
                // Approximate corner count using convex hull deficiency
                val hull = MatOfInt()
                Imgproc.convexHull(bestContour, hull)
                hull.total().toInt()
            }

            // Step 11: Determine container type
            val aspectRatio = bubbleCvRect.width.toDouble() / bubbleCvRect.height.toDouble().coerceAtLeast(1.0)
            val containerType = when {
                hasOvalShape -> TextContainerType.SPEECH_BUBBLE
                aspectRatio in 0.3..3.0 -> TextContainerType.SPEECH_FRAME
                else -> TextContainerType.UNKNOWN
            }

            // Step 12: Estimate border thickness
            val borderThickness = estimateBorderThickness(gray, bubbleCvRect, textInCrop)

            // Step 13: Analyze if this is a solid-color bubble
            val solidBubble = analyzePerimeterSolidity(crop, bubbleCvRect, textInCrop)

            // Step 14: Estimate background opacity
            val backgroundOpacity = estimateBackgroundOpacity(gray, bubbleCvRect, textInCrop)

            val containerInfo = TextContainerInfo(
                type = containerType,
                confidence = bestScore.coerceIn(0f, 1f),
                hasStrongBorder = borderThickness > 1.5f,
                borderThickness = borderThickness,
                backgroundOpacity = backgroundOpacity,
                circularity = circularity,
                aspectRatio = aspectRatio,
                cornerCount = cornerCount,
                containerBounds = bubbleBounds
            )

            crop.recycle()
            gray.release()
            bestContour.release()

            return BubbleDetectionResult(
                containerInfo = containerInfo,
                bubbleBounds = bubbleBounds,
                isSolidBubble = solidBubble,
                confidence = containerInfo.confidence
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting bubble", e)
            return BubbleDetectionResult(null, null, false, 0f)
        }
    }

    /**
     * Estimate border thickness by measuring edge density in the ring
     * between text bounds and bubble bounds.
     */
    private fun estimateBorderThickness(
        gray: Mat,
        bubbleRect: org.opencv.core.Rect,
        textRect: org.opencv.core.Rect
    ): Float {
        // Sample horizontal and vertical strips between text and bubble border
        val edgeMat = Mat()
        Imgproc.Canny(gray, edgeMat, 50.0, 150.0)

        val samples = mutableListOf<Float>()

        // Top strip: between text top and bubble top
        if (textRect.y > bubbleRect.y) {
            val topStrip = edgeMat.submat(
                bubbleRect.y, textRect.y,
                max(bubbleRect.x, textRect.x),
                min(bubbleRect.x + bubbleRect.width, textRect.x + textRect.width)
            )
            val edgeRatio = Core.countNonZero(topStrip).toFloat() / topStrip.total().toFloat()
            if (edgeRatio > 0.05f) samples.add(edgeRatio)
            topStrip.release()
        }

        // Bottom strip
        val textBottom = textRect.y + textRect.height
        val bubbleBottom = bubbleRect.y + bubbleRect.height
        if (bubbleBottom > textBottom) {
            val bottomStrip = edgeMat.submat(
                textBottom, bubbleBottom,
                max(bubbleRect.x, textRect.x),
                min(bubbleRect.x + bubbleRect.width, textRect.x + textRect.width)
            )
            val edgeRatio = Core.countNonZero(bottomStrip).toFloat() / bottomStrip.total().toFloat()
            if (edgeRatio > 0.05f) samples.add(edgeRatio)
            bottomStrip.release()
        }

        // Left strip
        if (textRect.x > bubbleRect.x) {
            val leftStrip = edgeMat.submat(
                max(bubbleRect.y, textRect.y),
                min(bubbleRect.y + bubbleRect.height, textRect.y + textRect.height),
                bubbleRect.x, textRect.x
            )
            val edgeRatio = Core.countNonZero(leftStrip).toFloat() / leftStrip.total().toFloat()
            if (edgeRatio > 0.05f) samples.add(edgeRatio)
            leftStrip.release()
        }

        // Right strip
        val textRight = textRect.x + textRect.width
        val bubbleRight = bubbleRect.x + bubbleRect.width
        if (bubbleRight > textRight) {
            val rightStrip = edgeMat.submat(
                max(bubbleRect.y, textRect.y),
                min(bubbleRect.y + bubbleRect.height, textRect.y + textRect.height),
                textRight, bubbleRight
            )
            val edgeRatio = Core.countNonZero(rightStrip).toFloat() / rightStrip.total().toFloat()
            if (edgeRatio > 0.05f) samples.add(edgeRatio)
            rightStrip.release()
        }

        edgeMat.release()

        // Higher edge density = thicker border (more border pixels relative to area)
        return if (samples.isNotEmpty()) {
            val avgEdgeDensity = samples.average().toFloat()
            // Map edge density to approximate pixel thickness
            (avgEdgeDensity * 20f).coerceIn(0.5f, 10f)
        } else {
            // No clear border detected
            0f
        }
    }

    /**
     * Analyze if the area around text (within bubble) is solid color.
     * Samples perimeter pixels between text bounds and bubble bounds.
     */
    private fun analyzePerimeterSolidity(
        crop: Bitmap,
        bubbleRect: org.opencv.core.Rect,
        textRect: org.opencv.core.Rect
    ): Boolean {
        var lightCount = 0
        var darkCount = 0
        var midCount = 0
        var totalSamples = 0
        val step = 3

        data class PixelClass(val isLight: Boolean, val isDark: Boolean, val isMid: Boolean)

        fun classifyColor(color: Int): PixelClass {
            val r = android.graphics.Color.red(color)
            val g = android.graphics.Color.green(color)
            val b = android.graphics.Color.blue(color)
            val brightness = (r + g + b) / 3
            val maxColor = maxOf(r, g, b)
            val minColor = minOf(r, g, b)
            val saturation = if (maxColor == 0) 0 else (maxColor - minColor) * 255 / maxColor

            return if (saturation > 40) {
                PixelClass(false, false, true) // mid (colored)
            } else if (brightness > 220) {
                PixelClass(true, false, false) // light
            } else if (brightness < 45) {
                PixelClass(false, true, false) // dark
            } else {
                PixelClass(false, false, true) // mid (gray)
            }
        }

        // Helper to scan a rectangular strip
        fun scanStrip(startY: Int, endY: Int, startX: Int, endX: Int) {
            for (y in startY until endY step step) {
                for (x in startX until endX step step) {
                    if (x < 0 || x >= crop.width || y < 0 || y >= crop.height) continue
                    totalSamples++
                    val cls = classifyColor(crop.getPixel(x, y))
                    if (cls.isLight) lightCount++
                    else if (cls.isDark) darkCount++
                    else midCount++
                }
            }
        }

        // Top strip
        if (bubbleRect.y < textRect.y) {
            scanStrip(bubbleRect.y, textRect.y,
                max(bubbleRect.x, 2), min(bubbleRect.x + bubbleRect.width, crop.width - 2))
        }

        // Bottom strip
        val textBottom = textRect.y + textRect.height
        val bubbleBottom = bubbleRect.y + bubbleRect.height
        if (textBottom < bubbleBottom) {
            scanStrip(textBottom, bubbleBottom,
                max(bubbleRect.x, 2), min(bubbleRect.x + bubbleRect.width, crop.width - 2))
        }

        // Left strip
        if (bubbleRect.x < textRect.x) {
            scanStrip(
                max(bubbleRect.y, textRect.y),
                min(bubbleRect.y + bubbleRect.height, textRect.y + textRect.height),
                bubbleRect.x, textRect.x
            )
        }

        // Right strip
        val textRight = textRect.x + textRect.width
        val bubbleRight = bubbleRect.x + bubbleRect.width
        if (bubbleRight > textRight) {
            scanStrip(
                max(bubbleRect.y, textRect.y),
                min(bubbleRect.y + bubbleRect.height, textRect.y + textRect.height),
                textRight, bubbleRight
            )
        }

        if (totalSamples < 10) return false

        val lightRatio = lightCount.toFloat() / totalSamples
        val darkRatio = darkCount.toFloat() / totalSamples
        val midRatio = midCount.toFloat() / totalSamples

        // Solid bubble if >60% light or >60% dark with <25% mid-tones
        return (lightRatio > 0.60f && midRatio < 0.25f) ||
                (darkRatio > 0.60f && midRatio < 0.25f)
    }



    /**
     * Estimate background opacity of the bubble.
     * Uses variance within the bubble region.
     */
    private fun estimateBackgroundOpacity(
        gray: Mat,
        bubbleRect: org.opencv.core.Rect,
        textRect: org.opencv.core.Rect
    ): Float {
        // Extract bubble region excluding text area
        if (bubbleRect.area() <= 0 || textRect.area() <= 0) return 1.0f

        val bubbleRegion = gray.submat(
            bubbleRect.y, (bubbleRect.y + bubbleRect.height).coerceAtMost(gray.rows()),
            bubbleRect.x, (bubbleRect.x + bubbleRect.width).coerceAtMost(gray.cols())
        )

        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        Core.meanStdDev(bubbleRegion, mean, stdDev)
        bubbleRegion.release()

        val stdVal = stdDev.get(0, 0)[0]
        // Low std deviation = uniform background = opaque
        // High std deviation = varied background = transparent
        return (1.0f - (stdVal / 128.0).toFloat().coerceIn(0f, 1f))
    }
}
