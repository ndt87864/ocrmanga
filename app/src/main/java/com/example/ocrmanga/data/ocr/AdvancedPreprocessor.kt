package com.example.ocrmanga.data.ocr

import android.graphics.Bitmap
import android.util.Log
import com.example.ocrmanga.data.ocr.models.*
import kotlinx.coroutines.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.pow

/**
 * Advanced image preprocessing for improved OCR accuracy
 */
class AdvancedPreprocessor(
    private val config: PreprocessingConfig = PreprocessingConfig()
) {
    companion object {
        private const val TAG = "AdvancedPreprocessor"
    }

    // LRU cache for preprocessed results
    private val cache = mutableMapOf<String, PreprocessedResult>()
    private val maxCacheSize = 20

    /**
     * Preprocess bitmap using configured pipeline
     */
    fun preprocess(bitmap: Bitmap): PreprocessedResult {
        if (!OpenCvInitializer.ensureInitialized()) {
            Log.w(TAG, "OpenCV unavailable, returning original bitmap")
            return PreprocessedResult(
                bitmap = bitmap,
                method = PreprocessingMethod.STANDARD,
                metrics = PreprocessingMetrics(0.5f, 0.5f, 0.5f)
            )
        }

        try {
            Log.d(TAG, "Starting preprocessing")

            // Convert to Mat
            val mat = bitmapToMat(bitmap)

            // Step 1: Grayscale
            val gray = toGrayscale(mat)
            mat.release()

            // Step 2: Denoise
            val denoised = denoise(gray)
            gray.release()

            // Step 3: CLAHE
            val enhanced = if (config.useCLAHE) {
                val result = applyCLAHE(denoised)
                denoised.release()
                result
            } else {
                denoised
            }

            // Step 4: Adaptive Threshold
            val binary = if (config.useAdaptiveThreshold) {
                val result = applyAdaptiveThreshold(enhanced)
                enhanced.release()
                result
            } else {
                enhanced
            }

            // Step 5: Morphology
            val final = if (config.useMorphology) {
                val result = applyMorphology(binary)
                binary.release()
                result
            } else {
                binary
            }

            // Convert back to Bitmap
            val resultBitmap = matToBitmap(final)

            // Calculate metrics
            val metrics = calculateMetrics(final)
            final.release()

            return PreprocessedResult(
                bitmap = resultBitmap,
                method = PreprocessingMethod.STANDARD,
                metrics = metrics
            )

        } catch (error: Throwable) {
            Log.e(TAG, "Error preprocessing", error)
            // Fallback: return original
            return PreprocessedResult(
                bitmap = bitmap,
                method = PreprocessingMethod.STANDARD,
                metrics = PreprocessingMetrics(0.5f, 0.5f, 0.5f)
            )
        }
    }

    /**
     * Preprocess with region-specific configuration
     */
    fun preprocessForRegion(bitmap: Bitmap, region: TextRegion): PreprocessedResult {
        val regionConfig = config.regionSpecificConfig[region.type] ?: config

        val customConfig = when (region.type) {
            RegionType.BUBBLE -> {
                // Standard preprocessing for speech bubbles
                regionConfig
            }
            RegionType.SFX -> {
                // More aggressive for SFX (often stylized)
                PreprocessingConfig(
                    denoisingMethod = regionConfig.denoisingMethod,
                    useCLAHE = true,
                    claheClipLimit = 3.0,
                    useAdaptiveThreshold = true,
                    adaptiveBlockSize = 15,
                    useMorphology = true
                )
            }
            RegionType.NARRATION -> {
                // Gentler for narration boxes (usually clean)
                PreprocessingConfig(
                    denoisingMethod = DenoisingMethod.NONE,
                    useCLAHE = true,
                    claheClipLimit = 1.5,
                    useAdaptiveThreshold = true,
                    useMorphology = false
                )
            }
            RegionType.UNKNOWN -> {
                // Default
                regionConfig
            }
        }

        // Create temporary preprocessor with custom config
        val customPreprocessor = AdvancedPreprocessor(customConfig)
        val result = customPreprocessor.preprocess(bitmap)
        return result.copy(method = PreprocessingMethod.REGION_SPECIFIC)
    }

    /**
     * Convert color image to grayscale
     */
    private fun toGrayscale(mat: Mat): Mat {
        val gray = Mat()

        when (mat.channels()) {
            1 -> {
                // Already grayscale
                mat.copyTo(gray)
            }
            3 -> {
                // BGR to Gray
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            }
            4 -> {
                // BGRA to Gray
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGRA2GRAY)
            }
            else -> {
                throw IllegalArgumentException("Unsupported number of channels: ${mat.channels()}")
            }
        }

        return gray
    }

    /**
     * Apply denoising based on configured method
     */
    private fun denoise(mat: Mat): Mat {
        return when (config.denoisingMethod) {
            DenoisingMethod.NONE -> mat
            DenoisingMethod.GAUSSIAN -> applyGaussianBlur(mat)
            DenoisingMethod.BILATERAL -> applyBilateralFilter(mat)
        }
    }

    /**
     * Apply Gaussian blur for denoising
     */
    private fun applyGaussianBlur(mat: Mat): Mat {
        val result = Mat()
        val kernelSize = Size(
            config.gaussianKernel.toDouble(),
            config.gaussianKernel.toDouble()
        )

        Imgproc.GaussianBlur(mat, result, kernelSize, 0.0)

        return result
    }

    /**
     * Apply bilateral filter for edge-preserving denoising
     */
    private fun applyBilateralFilter(mat: Mat): Mat {
        val result = Mat()

        Imgproc.bilateralFilter(
            mat,
            result,
            config.bilateralD,
            config.bilateralSigmaColor,
            config.bilateralSigmaSpace
        )

        return result
    }

    /**
     * Apply Contrast Limited Adaptive Histogram Equalization
     */
    private fun applyCLAHE(mat: Mat): Mat {
        val clahe = Imgproc.createCLAHE(
            config.claheClipLimit,
            Size(config.claheTileSize.toDouble(), config.claheTileSize.toDouble())
        )

        val result = Mat()
        clahe.apply(mat, result)

        return result
    }

    /**
     * Apply adaptive thresholding for binarization
     */
    private fun applyAdaptiveThreshold(mat: Mat): Mat {
        val result = Mat()

        val adaptiveMethod = when (config.adaptiveMethod) {
            AdaptiveMethod.GAUSSIAN -> Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C
            AdaptiveMethod.MEAN -> Imgproc.ADAPTIVE_THRESH_MEAN_C
        }

        Imgproc.adaptiveThreshold(
            mat,
            result,
            255.0,
            adaptiveMethod,
            Imgproc.THRESH_BINARY,
            config.adaptiveBlockSize,
            config.adaptiveC
        )

        return result
    }

    /**
     * Apply morphological operations to clean up binary image
     */
    private fun applyMorphology(mat: Mat): Mat {
        // Opening: remove small noise
        val opened = morphologicalOpen(mat, config.openingKernel)

        // Closing: fill small gaps
        val closed = morphologicalClose(opened, config.closingKernel)
        opened.release()

        return closed
    }

    /**
     * Morphological opening (erosion followed by dilation)
     */
    private fun morphologicalOpen(mat: Mat, kernelSize: Int): Mat {
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(kernelSize.toDouble(), kernelSize.toDouble())
        )

        val result = Mat()
        Imgproc.morphologyEx(mat, result, Imgproc.MORPH_OPEN, kernel)

        kernel.release()
        return result
    }

    /**
     * Morphological closing (dilation followed by erosion)
     */
    private fun morphologicalClose(mat: Mat, kernelSize: Int): Mat {
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(kernelSize.toDouble(), kernelSize.toDouble())
        )

        val result = Mat()
        Imgproc.morphologyEx(mat, result, Imgproc.MORPH_CLOSE, kernel)

        kernel.release()
        return result
    }

    /**
     * Calculate quality metrics for preprocessed image
     */
    private fun calculateMetrics(mat: Mat): PreprocessingMetrics {
        val contrast = calculateContrast(mat)
        val sharpness = calculateSharpness(mat)
        val noiseLevel = calculateNoiseLevel(mat)

        return PreprocessingMetrics(contrast, sharpness, noiseLevel)
    }

    /**
     * Calculate contrast using standard deviation
     */
    private fun calculateContrast(mat: Mat): Float {
        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        Core.meanStdDev(mat, mean, stdDev)

        val meanVal = mean.get(0, 0)[0]
        val stdDevVal = stdDev.get(0, 0)[0]

        mean.release()
        stdDev.release()

        // Contrast = stdDev / mean
        return if (meanVal > 0) {
            (stdDevVal / meanVal).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    /**
     * Calculate sharpness using Laplacian variance
     */
    private fun calculateSharpness(mat: Mat): Float {
        val laplacian = Mat()
        Imgproc.Laplacian(mat, laplacian, CvType.CV_64F)

        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        Core.meanStdDev(laplacian, mean, stdDev)

        val variance = stdDev.get(0, 0)[0].pow(2)

        laplacian.release()
        mean.release()
        stdDev.release()

        // Normalize to 0-1 range (empirical scaling)
        return (variance / 1000.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Calculate noise level using median absolute deviation
     */
    private fun calculateNoiseLevel(mat: Mat): Float {
        val median = Mat()
        Imgproc.medianBlur(mat, median, 5)

        val diff = Mat()
        Core.absdiff(mat, median, diff)

        val meanDiff = Core.mean(diff).`val`[0]

        median.release()
        diff.release()

        // Normalize to 0-1 range
        return (meanDiff / 255.0).toFloat()
    }

    /**
     * Preprocess multiple images in parallel
     */
    suspend fun preprocessBatch(
        bitmaps: List<Bitmap>
    ): List<PreprocessedResult> = coroutineScope {
        bitmaps.map { bitmap ->
            async(Dispatchers.Default) {
                preprocess(bitmap)
            }
        }.awaitAll()
    }

    /**
     * Convert Bitmap to OpenCV Mat
     */
    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    /**
     * Convert OpenCV Mat to Bitmap
     */
    private fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    /**
     * Clear preprocessing cache
     */
    fun clearCache() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }
}
