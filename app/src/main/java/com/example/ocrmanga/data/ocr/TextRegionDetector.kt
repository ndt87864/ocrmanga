package com.example.ocrmanga.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.ocrmanga.data.ocr.models.DetectionConfig
import com.example.ocrmanga.data.ocr.models.RegionType
import com.example.ocrmanga.data.ocr.models.TextRegion
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Detects text regions in manga pages using edge detection and morphological operations
 */
class TextRegionDetector(
    private val config: DetectionConfig = DetectionConfig()
) {
    companion object {
        private const val TAG = "TextRegionDetector"
    }

    /**
     * Detect all text regions in the given bitmap
     */
    fun detectRegions(bitmap: Bitmap): List<TextRegion> {
        try {
            Log.d(TAG, "Starting region detection for ${bitmap.width}x${bitmap.height} image")

            // Step 1: Preprocess
            val preprocessed = preprocessImage(bitmap)

            // Step 2: Detect edges
            val edges = detectEdges(preprocessed)

            // Step 3: Apply morphology
            val morphed = applyMorphology(edges)

            // Step 4: Find connected components
            val components = findConnectedComponents(morphed)

            // Step 5: Filter regions
            val filtered = filterRegions(components, bitmap.width, bitmap.height, edges)

            // Step 6: Merge overlapping regions
            val merged = mergeOverlappingRegions(filtered)

            Log.d(TAG, "Detected ${merged.size} text regions")

            // Release Mats
            preprocessed.release()
            edges.release()
            morphed.release()

            return merged

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting regions", e)
            // Fallback: return full image as single region
            return listOf(
                TextRegion(
                    bounds = Rect(0, 0, bitmap.width, bitmap.height),
                    confidence = 0.5f,
                    mask = null,
                    type = RegionType.UNKNOWN
                )
            )
        }
    }

    /**
     * Preprocess image for edge detection
     * Steps: Grayscale → Gaussian Blur → Adaptive Threshold
     */
    private fun preprocessImage(bitmap: Bitmap): Mat {
        // Convert bitmap to Mat
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

        // Apply Gaussian blur to reduce noise
        val blurred = Mat()
        val kernelSize = Size(config.gaussianKernelSize.toDouble(), config.gaussianKernelSize.toDouble())
        Imgproc.GaussianBlur(gray, blurred, kernelSize, 0.0)

        // Apply adaptive thresholding
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            blurred,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            config.adaptiveBlockSize,
            config.adaptiveC
        )

        // Release intermediate mats
        mat.release()
        gray.release()
        blurred.release()

        return binary
    }

    /**
     * Detect edges using Canny edge detector
     */
    private fun detectEdges(mat: Mat): Mat {
        val edges = Mat()

        Imgproc.Canny(
            mat,
            edges,
            config.cannyThreshold1,
            config.cannyThreshold2
        )

        return edges
    }

    /**
     * Apply morphological operations to connect nearby edges
     * Steps: Dilation → Closing
     */
    private fun applyMorphology(edges: Mat): Mat {
        // Dilation: expand edges
        val dilated = Mat()
        val dilationKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(config.dilationKernel.toDouble(), config.dilationKernel.toDouble())
        )
        Imgproc.dilate(edges, dilated, dilationKernel, Point(-1.0, -1.0), config.dilationIterations)

        // Closing: fill holes
        val closed = Mat()
        val closingKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(config.closingKernel.toDouble(), config.closingKernel.toDouble())
        )
        Imgproc.morphologyEx(dilated, closed, Imgproc.MORPH_CLOSE, closingKernel)

        // Release intermediate mats
        dilationKernel.release()
        dilated.release()
        closingKernel.release()

        return closed
    }

    /**
     * Find connected components (contours) in the binary image
     */
    private fun findConnectedComponents(mat: Mat): List<MatOfPoint> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()

        Imgproc.findContours(
            mat,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,  // Only external contours
            Imgproc.CHAIN_APPROX_SIMPLE  // Compress contours
        )

        hierarchy.release()

        Log.d(TAG, "Found ${contours.size} contours")

        return contours
    }

    /**
     * Filter regions based on size, aspect ratio, edge density, and solidity
     */
    private fun filterRegions(
        components: List<MatOfPoint>,
        imageWidth: Int,
        imageHeight: Int,
        edgeMap: Mat
    ): List<TextRegion> {
        val imageArea = imageWidth * imageHeight
        val maxArea = (imageArea * config.maxRegionAreaRatio).toInt()

        return components.mapNotNull { contour ->
            // Calculate bounding box (OpenCV Rect)
            val cvBoundingRect = Imgproc.boundingRect(contour)
            val area = cvBoundingRect.width * cvBoundingRect.height

            // Filter by area
            if (area < config.minRegionArea || area > maxArea) {
                return@mapNotNull null
            }

            // Filter by aspect ratio
            val aspectRatio = cvBoundingRect.width.toFloat() / cvBoundingRect.height
            if (aspectRatio < config.minAspectRatio || aspectRatio > config.maxAspectRatio) {
                return@mapNotNull null
            }

            // Calculate edge density
            val edgeDensity = calculateEdgeDensity(cvBoundingRect, edgeMap)
            if (edgeDensity < config.minEdgeDensity) {
                return@mapNotNull null
            }

            // Calculate solidity
            val solidity = calculateSolidity(contour)
            if (solidity < config.minSolidity) {
                return@mapNotNull null
            }

            // Calculate confidence
            val confidence = calculateConfidence(edgeDensity, solidity, area, imageArea)

            // Classify region type
            val type = classifyRegion(cvBoundingRect, solidity, imageHeight)

            // Convert OpenCV Rect to Android Rect
            val androidRect = android.graphics.Rect(
                cvBoundingRect.x,
                cvBoundingRect.y,
                cvBoundingRect.x + cvBoundingRect.width,
                cvBoundingRect.y + cvBoundingRect.height
            )

            TextRegion(
                bounds = androidRect,
                confidence = confidence,
                mask = null,
                type = type
            )
        }
    }

    /**
     * Calculate edge density in a region
     */
    private fun calculateEdgeDensity(bounds: org.opencv.core.Rect, edgeMap: Mat): Float {
        val roi = Mat(edgeMap, bounds)
        val nonZero = Core.countNonZero(roi)
        val total = bounds.width * bounds.height
        roi.release()

        return nonZero.toFloat() / total
    }

    /**
     * Calculate solidity (compactness) of a contour
     */
    private fun calculateSolidity(contour: MatOfPoint): Float {
        val area = Imgproc.contourArea(contour)

        val hull = MatOfInt()
        Imgproc.convexHull(contour, hull)

        val hullPoints = MatOfPoint()
        val hullIndices = hull.toArray()
        val contourPoints = contour.toArray()
        val hullPointsList = hullIndices.map { contourPoints[it] }
        hullPoints.fromList(hullPointsList)

        val hullArea = Imgproc.contourArea(hullPoints)

        hull.release()
        hullPoints.release()

        return if (hullArea > 0) (area / hullArea).toFloat() else 0f
    }

    /**
     * Calculate confidence score for a region
     */
    private fun calculateConfidence(
        edgeDensity: Float,
        solidity: Float,
        area: Int,
        imageArea: Int
    ): Float {
        // Size score: prefer medium-sized regions
        val sizeRatio = area.toFloat() / imageArea
        val sizeScore = when {
            sizeRatio < 0.01f -> 0.5f  // Too small
            sizeRatio > 0.3f -> 0.7f   // Too large
            else -> 1.0f               // Good size
        }

        // Weighted combination
        return (edgeDensity * 0.5f + solidity * 0.3f + sizeScore * 0.2f).coerceIn(0f, 1f)
    }

    /**
     * Classify region type based on features
     */
    private fun classifyRegion(bounds: org.opencv.core.Rect, solidity: Float, imageHeight: Int): RegionType {
        val aspectRatio = bounds.width.toFloat() / bounds.height
        val position = bounds.y.toFloat() / imageHeight

        return when {
            // Speech bubble: compact, medium aspect ratio
            solidity > 0.8f && aspectRatio in 0.5f..2.0f -> RegionType.BUBBLE

            // SFX: extreme aspect ratio
            aspectRatio > 3.0f || aspectRatio < 0.3f -> RegionType.SFX

            // Narration: top of page
            position < 0.2f -> RegionType.NARRATION

            else -> RegionType.UNKNOWN
        }
    }

    /**
     * Merge regions that overlap significantly
     */
    private fun mergeOverlappingRegions(regions: List<TextRegion>): List<TextRegion> {
        if (regions.size <= 1) return regions

        val merged = mutableListOf<TextRegion>()
        val sorted = regions.sortedBy { it.bounds.left }

        for (region in sorted) {
            // Find overlapping regions in merged list
            val overlapping = merged.filter { it.iou(region) > config.mergeIouThreshold }

            if (overlapping.isEmpty()) {
                // No overlap, add as new region
                merged.add(region)
            } else {
                // Merge with overlapping regions
                val allRegions = overlapping + region
                val mergedBounds = unionBounds(allRegions.map { it.bounds })
                val avgConfidence = allRegions.map { it.confidence }.average().toFloat()

                // Remove overlapping regions
                merged.removeAll(overlapping)

                // Add merged region
                merged.add(
                    TextRegion(
                        bounds = mergedBounds,
                        confidence = avgConfidence,
                        mask = null,
                        type = region.type
                    )
                )
            }
        }

        return merged
    }

    /**
     * Calculate union of multiple rectangles
     */
    private fun unionBounds(rects: List<Rect>): Rect {
        if (rects.isEmpty()) throw IllegalArgumentException("Cannot union empty list")

        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE

        for (rect in rects) {
            left = minOf(left, rect.left)
            top = minOf(top, rect.top)
            right = maxOf(right, rect.right)
            bottom = maxOf(bottom, rect.bottom)
        }

        return Rect(left, top, right, bottom)
    }
}
