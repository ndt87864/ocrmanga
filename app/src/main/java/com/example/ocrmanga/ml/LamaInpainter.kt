package com.example.ocrmanga.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import com.example.ocrmanga.utils.AppLogger as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LaMa-based inpainting engine using TensorFlow Lite.
 * Per-region crop processing for high quality: each text block is cropped
 * with context padding and processed at near-original resolution (~0.9x)
 * instead of downscaling the whole image (0.3x). Nearby blocks are
 * clustered to reduce total inference calls.
 */
object LamaInpainter {

    private const val TAG = "LamaInpainter"
    private const val MODEL_PATH = "models/LaMa-Dilated_float.tflite"
    private const val MASK_PADDING = 10          // Must cover text at overlay edges
    private const val CONTEXT_PADDING = 128     // Extra context around crop for model quality

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    private var inputH = 0
    private var inputW = 0
    private var inputC = 0
    private var maskC = 0
    private var outputC = 0
    private var hasMaskInput = false
    private var isDynamic = false

    private var imgBuf: ByteBuffer? = null
    private var maskBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null
    private var combBuf: ByteBuffer? = null

    enum class ImageType { GRAYSCALE, COLOR }
    data class InpaintBlock(
        val bounds: Rect,
        val shapeType: Int = 0, // 0=rect, 1=oval
        val overlayInsetHorizontal: Float = 0f,
        val overlayInsetVertical: Float = 0f
    )
    private data class ColorScale(val r: Float, val g: Float, val b: Float)

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val modelFile = FileUtil.loadMappedFile(context, MODEL_PATH)
            val options = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
            }
            val interp = Interpreter(modelFile, options)
            interpreter = interp

            val imgTensor = interp.getInputTensor(0)
            val imgShape = imgTensor.shape()
            Log.i(TAG, "Input[0] shape: ${imgShape.contentToString()}, dtype: ${imgTensor.dataType()}")

            hasMaskInput = interp.inputTensorCount > 1
            if (hasMaskInput) {
                val maskTensor = interp.getInputTensor(1)
                val maskShape = maskTensor.shape()
                Log.i(TAG, "Input[1] (mask) shape: ${maskShape.contentToString()}, dtype: ${maskTensor.dataType()}")
                maskC = if (maskShape.size == 4) maskShape[3] else 1
            }

            val outTensor = interp.getOutputTensor(0)
            val outShape = outTensor.shape()
            Log.i(TAG, "Output[0] shape: ${outShape.contentToString()}, dtype: ${outTensor.dataType()}")

            if (imgShape.size == 4) { inputH = imgShape[1]; inputW = imgShape[2]; inputC = imgShape[3] }
            if (outShape.size == 4) { outputC = outShape[3] }

            isDynamic = inputH <= 0 || inputW <= 0
            if (isDynamic) { inputH = 512; inputW = 512 }
            if (inputC <= 0) inputC = 3
            if (maskC <= 0) maskC = 1
            if (outputC <= 0) outputC = 3

            allocateBuffers()

            Log.i(TAG, "Resolved: input=${inputW}x${inputH}x${inputC}, maskC=$maskC, outC=$outputC, dynamic=$isDynamic, hasMask=$hasMaskInput")
            isInitialized = true
            Log.i(TAG, "LamaInpainter initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LamaInpainter", e)
            isInitialized = false
        }
    }

    private fun allocateBuffers() {
        imgBuf = ByteBuffer.allocateDirect(inputH * inputW * inputC * 4).order(ByteOrder.nativeOrder())
        if (hasMaskInput) {
            maskBuf = ByteBuffer.allocateDirect(inputH * inputW * maskC * 4).order(ByteOrder.nativeOrder())
        } else {
            combBuf = ByteBuffer.allocateDirect(inputH * inputW * (inputC + 1) * 4).order(ByteOrder.nativeOrder())
        }
        outBuf = ByteBuffer.allocateDirect(inputH * inputW * outputC * 4).order(ByteOrder.nativeOrder())
    }

    fun isReady(): Boolean = isInitialized

    // ── Public API ────────────────────────────────────────────────────────

    suspend fun inpaintBlocks(
        image: Bitmap, blocks: List<InpaintBlock>,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized || blocks.isEmpty()) return@withContext null
        try {
            val startTime = System.currentTimeMillis()
            onProgress?.invoke("Lấy dữ liệu...")

            val imageType = detectImageType(image)
            val workImage = if (imageType == ImageType.GRAYSCALE) toGrayscaleBitmap(image)
            else image.copy(Bitmap.Config.ARGB_8888, true)

            val fullMask = createMaskFromBlocks(image.width, image.height, blocks)

            // Process each block independently (no clustering) for max quality
            val paddedBlocks = blocks.map {
                val insetBounds = Rect(
                    (it.bounds.left + it.overlayInsetHorizontal.toInt()),
                    (it.bounds.top + it.overlayInsetVertical.toInt()),
                    (it.bounds.right - it.overlayInsetHorizontal.toInt()),
                    (it.bounds.bottom - it.overlayInsetVertical.toInt())
                )
                val effectiveBounds = if (insetBounds.width() > 0 && insetBounds.height() > 0) insetBounds else it.bounds
                Rect(
                    (effectiveBounds.left - MASK_PADDING).coerceAtLeast(0),
                    (effectiveBounds.top - MASK_PADDING).coerceAtLeast(0),
                    (effectiveBounds.right + MASK_PADDING).coerceAtMost(image.width),
                    (effectiveBounds.bottom + MASK_PADDING).coerceAtMost(image.height)
                )
            }
            // Each block is its own cluster - no merging to avoid smearing
            val clusters = paddedBlocks.map { listOf(it) }
            Log.d(TAG, "inpaintBlocks: ${blocks.size} blocks -> ${clusters.size} clusters, type=$imageType")

            val result = processRegionClusters(workImage, fullMask, clusters, onProgress)
            fullMask.recycle(); workImage.recycle()

            val finalResult = if (imageType == ImageType.GRAYSCALE && result != null) {
                val g = toGrayscaleBitmap(result); result.recycle(); g
            } else result

            Log.d(TAG, "Total inpaintBlocks: ${System.currentTimeMillis() - startTime}ms")
            finalResult
        } catch (e: Exception) { Log.e(TAG, "inpaintBlocks failed", e); null }
    }

    suspend fun inpaintWithMask(
        image: Bitmap, mask: Bitmap,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext null
        try {
            onProgress?.invoke("Lấy dữ liệu...")
            inpaintWithMaskInternal(image, mask, onProgress)
        } catch (e: Exception) { Log.e(TAG, "inpaintWithMask failed", e); null }
    }

    suspend fun inpaintPoints(
        image: Bitmap, points: List<Point>, radius: Int = 15,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized || points.isEmpty()) return@withContext null
        try {
            onProgress?.invoke("Lấy dữ liệu...")
            val mask = createMaskFromPoints(image.width, image.height, points, radius)
            val result = inpaintWithMaskInternal(image, mask, onProgress)
            mask.recycle()
            result
        } catch (e: Exception) { Log.e(TAG, "inpaintPoints failed", e); null }
    }

    fun release() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null; isInitialized = false
        imgBuf = null; maskBuf = null; outBuf = null; combBuf = null
    }

    // ── Internal mask-based inpainting ────────────────────────────────────

    private fun inpaintWithMaskInternal(
        image: Bitmap, mask: Bitmap,
        onProgress: ((String) -> Unit)?
    ): Bitmap? {
        val startTime = System.currentTimeMillis()
        val imageType = detectImageType(image)
        val workImage = if (imageType == ImageType.GRAYSCALE) toGrayscaleBitmap(image)
        else image.copy(Bitmap.Config.ARGB_8888, true)

        val normalizedMask = normalizeMask(mask, image.width, image.height)
        val maskBounds = findMaskBounds(normalizedMask)
        if (maskBounds == null) {
            normalizedMask.recycle(); workImage.recycle(); return null
        }

        val result = processRegionClusters(workImage, normalizedMask, listOf(listOf(maskBounds)), onProgress)
        normalizedMask.recycle(); workImage.recycle()

        val finalResult = if (imageType == ImageType.GRAYSCALE && result != null) {
            val g = toGrayscaleBitmap(result); result.recycle(); g
        } else result

        Log.d(TAG, "Total inpaintWithMask: ${System.currentTimeMillis() - startTime}ms")
        return finalResult
    }

    // ── Per-region processing with clustering ─────────────────────────────

    /**
     * Process each cluster at near-original resolution.
     * A 200x300 block + 128px padding = 456x556 → fits in 512x512 with ~0.9x scale.
     * Much better than whole-image: 1000x1500 → 512x512 at ~0.3x scale.
     */
    private fun processRegionClusters(
        image: Bitmap, mask: Bitmap, clusters: List<List<Rect>>,
        onProgress: ((String) -> Unit)?
    ): Bitmap? {
        val interp = interpreter ?: return null
        val w = image.width; val h = image.height
        val result = image.copy(Bitmap.Config.ARGB_8888, true)

        for ((idx, cluster) in clusters.withIndex()) {
            onProgress?.invoke("Xóa điểm ảnh (${idx + 1}/${clusters.size})...")

            val clusterBounds = unionBounds(cluster)
            // Dynamic padding: at least CONTEXT_PADDING, or 40% of region size
            val dynPadX = max(CONTEXT_PADDING, (clusterBounds.width() * 0.4f).roundToInt())
            val dynPadY = max(CONTEXT_PADDING, (clusterBounds.height() * 0.4f).roundToInt())

            val cropRect = Rect(
                (clusterBounds.left - dynPadX).coerceAtLeast(0),
                (clusterBounds.top - dynPadY).coerceAtLeast(0),
                (clusterBounds.right + dynPadX).coerceAtMost(w),
                (clusterBounds.bottom + dynPadY).coerceAtMost(h)
            )

            val scale = min(inputW.toFloat() / cropRect.width(), inputH.toFloat() / cropRect.height())
            Log.d(TAG, "Cluster $idx: ${cluster.size} rects, crop=${cropRect.width()}x${cropRect.height()}, scale=${"%.2f".format(scale)}")

            // Crop image and mask for this region
            val cropImg = Bitmap.createBitmap(result, cropRect.left, cropRect.top,
                cropRect.width(), cropRect.height())
            val cropMask = Bitmap.createBitmap(mask, cropRect.left, cropRect.top,
                cropRect.width(), cropRect.height())

            // Resize to model input
            val (resizedImg, padInfo) = resizeWithPadding(cropImg, inputW, inputH)
            val (resizedMask, _) = resizeWithPadding(cropMask, inputW, inputH)
            cropImg.recycle(); cropMask.recycle()

            // Inference
            val output = runInference(interp, resizedImg, resizedMask)
            resizedImg.recycle(); resizedMask.recycle()
            if (output == null) continue

            // Remove padding, resize back to crop dimensions
            val unpadded = removePadding(output, padInfo)
            output.recycle()
            val fullCrop = Bitmap.createScaledBitmap(unpadded, cropRect.width(), cropRect.height(), true)
            unpadded.recycle()

            // Blend with local per-channel color correction
            onProgress?.invoke("Bù điểm ảnh (${idx + 1}/${clusters.size})...")
            blendCropIntoResult(result, fullCrop, mask, cropRect)
            fullCrop.recycle()
        }

        onProgress?.invoke("Bù điểm ảnh 100%...")
        return result
    }

    // ── Clustering (Union-Find) ───────────────────────────────────────────

    private fun clusterRects(rects: List<Rect>, distance: Int): List<List<Rect>> {
        val n = rects.size
        if (n <= 1) return if (n == 0) emptyList() else listOf(rects)

        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (cur != root) { val next = parent[cur]; parent[cur] = root; cur = next }
            return root
        }

        for (i in 0 until n) for (j in i + 1 until n) {
            if (rectDistance(rects[i], rects[j]) <= distance) parent[find(i)] = find(j)
        }

        return (0 until n).groupBy { find(it) }.values.map { indices -> indices.map { rects[it] } }
    }

    private fun rectDistance(a: Rect, b: Rect): Int {
        val dx = max(0, max(a.left - b.right, b.left - a.right))
        val dy = max(0, max(a.top - b.bottom, b.top - a.bottom))
        return max(dx, dy)
    }

    private fun unionBounds(rects: List<Rect>): Rect {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (rect in rects) { l = min(l, rect.left); t = min(t, rect.top); r = max(r, rect.right); b = max(b, rect.bottom) }
        return Rect(l, t, r, b)
    }

    private fun findMaskBounds(mask: Bitmap): Rect? {
        val w = mask.width; val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            if (Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) {
                minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
            }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    // ── Hard-mask blending with color correction ──────────────────────────

    private fun blendCropIntoResult(
        result: Bitmap, crop: Bitmap, fullMask: Bitmap, cropRect: Rect
    ) {
        val cw = cropRect.width(); val ch = cropRect.height()
        val resultPx = IntArray(cw * ch)
        result.getPixels(resultPx, 0, cw, cropRect.left, cropRect.top, cw, ch)
        val cropPx = IntArray(cw * ch)
        crop.getPixels(cropPx, 0, cw, 0, 0, cw, ch)
        val maskPx = IntArray(cw * ch)
        fullMask.getPixels(maskPx, 0, cw, cropRect.left, cropRect.top, cw, ch)

        val binary = FloatArray(cw * ch) { i ->
            if (Color.red(maskPx[i]) > 10 || Color.green(maskPx[i]) > 10 || Color.blue(maskPx[i]) > 10) 1f else 0f
        }

        // Color correction from border pixels
        val scale = computeColorScale(resultPx, cropPx, binary, cw, ch)
        Log.d(TAG, "Local color scale: R=${"%.3f".format(scale.r)}, G=${"%.3f".format(scale.g)}, B=${"%.3f".format(scale.b)}")

        // Hard mask: only replace pixels inside mask, no feathering
        for (i in 0 until cw * ch) {
            if (binary[i] > 0.5f) {
                val ir = (Color.red(cropPx[i]) * scale.r).roundToInt().coerceIn(0, 255)
                val ig = (Color.green(cropPx[i]) * scale.g).roundToInt().coerceIn(0, 255)
                val ib = (Color.blue(cropPx[i]) * scale.b).roundToInt().coerceIn(0, 255)
                resultPx[i] = Color.argb(255, ir, ig, ib)
            }
        }
        result.setPixels(resultPx, 0, cw, cropRect.left, cropRect.top, cw, ch)
    }

    /**
     * Multiplicative per-channel correction from background-only border pixels.
     * Uses IQR (interquartile range) filtering to exclude text pixels regardless
     * of whether text is dark or light - text is always an outlier vs background.
     * Samples pixels 8-20px from mask to avoid text residue at edges.
     */
    private fun computeColorScale(
        origPx: IntArray, inpPx: IntArray, mask: FloatArray, w: Int, h: Int
    ): ColorScale {
        val SAMPLE_NEAR = 8
        val SAMPLE_FAR = 20

        val origSamples = mutableListOf<IntArray>()  // [r, g, b]
        val inpSamples = mutableListOf<IntArray>()
        val step = if (w * h < 60000) 1 else 2

        for (y in SAMPLE_FAR until h - SAMPLE_FAR step step) {
            for (x in SAMPLE_FAR until w - SAMPLE_FAR step step) {
                val i = y * w + x
                if (mask[i] > 0.5f) continue

                var nearMask = false
                var tooClose = false
                outer@ for (dy in -SAMPLE_FAR..SAMPLE_FAR step 3) {
                    for (dx in -SAMPLE_FAR..SAMPLE_FAR step 3) {
                        val ni = (y + dy) * w + (x + dx)
                        if (ni in mask.indices && mask[ni] > 0.5f) {
                            val dist = max(kotlin.math.abs(dx), kotlin.math.abs(dy))
                            if (dist < SAMPLE_NEAR) { tooClose = true; break@outer }
                            nearMask = true
                        }
                    }
                }
                if (nearMask && !tooClose) {
                    origSamples.add(intArrayOf(Color.red(origPx[i]), Color.green(origPx[i]), Color.blue(origPx[i])))
                    inpSamples.add(intArrayOf(Color.red(inpPx[i]), Color.green(inpPx[i]), Color.blue(inpPx[i])))
                }
            }
        }

        if (origSamples.size < 5) return ColorScale(1f, 1f, 1f)

        // IQR filter: sort by brightness, keep middle 50% (Q1-Q3)
        // This removes text outliers whether text is dark or light
        val brightnesses = origSamples.map { (it[0] * 0.299 + it[1] * 0.587 + it[2] * 0.114).toFloat() }
        val indices = brightnesses.indices.sortedBy { brightnesses[it] }
        val q1 = indices.size / 4
        val q3 = indices.size * 3 / 4
        val filteredIndices = indices.subList(q1, q3)

        if (filteredIndices.size < 3) return ColorScale(1f, 1f, 1f)

        var origR = 0.0; var origG = 0.0; var origB = 0.0
        var inpR = 0.0; var inpG = 0.0; var inpB = 0.0
        for (idx in filteredIndices) {
            origR += origSamples[idx][0]; origG += origSamples[idx][1]; origB += origSamples[idx][2]
            inpR += inpSamples[idx][0]; inpG += inpSamples[idx][1]; inpB += inpSamples[idx][2]
        }
        val n = filteredIndices.size
        val aOR = origR / n; val aOG = origG / n; val aOB = origB / n
        val aIR = inpR / n; val aIG = inpG / n; val aIB = inpB / n
        return ColorScale(
            r = if (aIR > 5) (aOR / aIR).toFloat().coerceIn(0.85f, 1.2f) else 1f,
            g = if (aIG > 5) (aOG / aIG).toFloat().coerceIn(0.85f, 1.2f) else 1f,
            b = if (aIB > 5) (aOB / aIB).toFloat().coerceIn(0.85f, 1.2f) else 1f
        )
    }

    // ── Resize with aspect-ratio padding ──────────────────────────────────

    data class PadInfo(val offsetX: Int, val offsetY: Int, val scaledW: Int, val scaledH: Int)

    private fun resizeWithPadding(src: Bitmap, targetW: Int, targetH: Int): Pair<Bitmap, PadInfo> {
        val scale = min(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val scaledW = (src.width * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).roundToInt().coerceAtLeast(1)
        val offsetX = (targetW - scaledW) / 2
        val offsetY = (targetH - scaledH) / 2
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val padded = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply { drawColor(Color.BLACK); drawBitmap(scaled, offsetX.toFloat(), offsetY.toFloat(), null) }
        scaled.recycle()
        return Pair(padded, PadInfo(offsetX, offsetY, scaledW, scaledH))
    }

    private fun removePadding(src: Bitmap, info: PadInfo): Bitmap {
        return Bitmap.createBitmap(src,
            info.offsetX.coerceAtLeast(0), info.offsetY.coerceAtLeast(0),
            info.scaledW.coerceAtMost(src.width - info.offsetX.coerceAtLeast(0)),
            info.scaledH.coerceAtMost(src.height - info.offsetY.coerceAtLeast(0)))
    }

    // ── Inference (reusable buffers) ──────────────────────────────────────

    private fun runInference(interp: Interpreter, image: Bitmap, mask: Bitmap): Bitmap? {
        try {
            if (isDynamic) {
                interp.resizeInput(0, intArrayOf(1, inputH, inputW, inputC))
                if (hasMaskInput) interp.resizeInput(1, intArrayOf(1, inputH, inputW, maskC))
                interp.allocateTensors()
            }

            val outShape = interp.getOutputTensor(0).shape()
            val outH = outShape[1]; val outW = outShape[2]; val outC = outShape[3]

            val maskPixels = IntArray(inputW * inputH)
            mask.getPixels(maskPixels, 0, inputW, 0, 0, inputW, inputH)
            val maskFloat = FloatArray(inputW * inputH) { i ->
                if (Color.red(maskPixels[i]) > 10 || Color.green(maskPixels[i]) > 10 || Color.blue(maskPixels[i]) > 10) 1f else 0f
            }

            val imgPixels = IntArray(inputW * inputH)
            image.getPixels(imgPixels, 0, inputW, 0, 0, inputW, inputH)

            val ib = imgBuf!!; ib.rewind()
            for (i in imgPixels.indices) {
                val keep = 1f - maskFloat[i]
                ib.putFloat(Color.red(imgPixels[i]) / 255f * keep)
                ib.putFloat(Color.green(imgPixels[i]) / 255f * keep)
                ib.putFloat(Color.blue(imgPixels[i]) / 255f * keep)
                for (c in 3 until inputC) ib.putFloat(0f)
            }

            val outBufNeeded = outH * outW * outC * 4
            val ob = if (outBuf != null && outBuf!!.capacity() >= outBufNeeded) outBuf!!
            else ByteBuffer.allocateDirect(outBufNeeded).order(ByteOrder.nativeOrder()).also { outBuf = it }
            ob.rewind()

            if (hasMaskInput) {
                val mb = maskBuf!!; mb.rewind()
                for (v in maskFloat) { mb.putFloat(v); for (c in 1 until maskC) mb.putFloat(v) }
                interp.runForMultipleInputsOutputs(arrayOf(ib, mb), mapOf(0 to ob))
            } else {
                val cb = combBuf!!; cb.rewind()
                for (i in imgPixels.indices) {
                    val keep = 1f - maskFloat[i]
                    cb.putFloat(Color.red(imgPixels[i]) / 255f * keep)
                    cb.putFloat(Color.green(imgPixels[i]) / 255f * keep)
                    cb.putFloat(Color.blue(imgPixels[i]) / 255f * keep)
                    cb.putFloat(maskFloat[i])
                }
                interp.resizeInput(0, intArrayOf(1, inputH, inputW, inputC + 1))
                interp.allocateTensors()
                interp.runForMultipleInputsOutputs(arrayOf(cb), mapOf(0 to ob))
            }

            return floatBufferToBitmap(ob, outW, outH, outC)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            return null
        }
    }

    // ── Fast O(n) box blur ───────────────────────────────────────────────

    private fun fastBoxBlur(input: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var cur = input.copyOf()
        repeat(2) { cur = horizontalBlur(cur, w, h, radius); cur = verticalBlur(cur, w, h, radius) }
        return cur
    }

    private fun horizontalBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val dst = FloatArray(w * h)
        for (y in 0 until h) {
            var sum = 0f; val off = y * w
            for (dx in 0..min(r, w - 1)) sum += src[off + dx]
            for (x in 0 until w) {
                if (x + r < w) sum += src[off + x + r]
                if (x - r - 1 >= 0) sum -= src[off + x - r - 1]
                dst[off + x] = sum / (min(x + r, w - 1) - max(x - r, 0) + 1)
            }
        }
        return dst
    }

    private fun verticalBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val dst = FloatArray(w * h)
        for (x in 0 until w) {
            var sum = 0f
            for (dy in 0..min(r, h - 1)) sum += src[dy * w + x]
            for (y in 0 until h) {
                if (y + r < h) sum += src[(y + r) * w + x]
                if (y - r - 1 >= 0) sum -= src[(y - r - 1) * w + x]
                dst[y * w + x] = sum / (min(y + r, h - 1) - max(y - r, 0) + 1)
            }
        }
        return dst
    }

    // ── Buffer → Bitmap ──────────────────────────────────────────────────

    private fun floatBufferToBitmap(buf: ByteBuffer, w: Int, h: Int, ch: Int): Bitmap {
        buf.rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (i in px.indices) {
            val r = (buf.float.coerceIn(0f, 1f) * 255).roundToInt()
            val g = if (ch >= 2) (buf.float.coerceIn(0f, 1f) * 255).roundToInt() else r
            val b = if (ch >= 3) (buf.float.coerceIn(0f, 1f) * 255).roundToInt() else r
            for (c in 3 until ch) buf.float
            px[i] = Color.argb(255, r, g, b)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    // ── Mask generation ──────────────────────────────────────────────────

    private fun createMaskFromBlocks(w: Int, h: Int, blocks: List<InpaintBlock>): Bitmap {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask); canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        for (block in blocks) {
            // Apply overlay insets to match the actual visible overlay area
            val insetBounds = Rect(
                (block.bounds.left + block.overlayInsetHorizontal.toInt()),
                (block.bounds.top + block.overlayInsetVertical.toInt()),
                (block.bounds.right - block.overlayInsetHorizontal.toInt()),
                (block.bounds.bottom - block.overlayInsetVertical.toInt())
            )
            // Skip if insets make the rect invalid
            if (insetBounds.width() <= 0 || insetBounds.height() <= 0) continue

            val paddedRect = Rect(
                (insetBounds.left - MASK_PADDING).coerceAtLeast(0),
                (insetBounds.top - MASK_PADDING).coerceAtLeast(0),
                (insetBounds.right + MASK_PADDING).coerceAtMost(w),
                (insetBounds.bottom + MASK_PADDING).coerceAtMost(h)
            )
            if (block.shapeType == 1) {
                canvas.drawOval(RectF(paddedRect), paint)
            } else {
                canvas.drawRect(paddedRect, paint)
            }
        }
        return mask
    }

    private fun createMaskFromPoints(w: Int, h: Int, points: List<Point>, radius: Int): Bitmap {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask); canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        for (p in points) canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), radius.toFloat(), paint)
        return mask
    }

    private fun normalizeMask(mask: Bitmap, tw: Int, th: Int): Bitmap {
        val scaled = if (mask.width != tw || mask.height != th) Bitmap.createScaledBitmap(mask, tw, th, false)
        else mask.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        for (i in px.indices) {
            val p = px[i]
            px[i] = if ((Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) && Color.alpha(p) > 10) Color.WHITE else Color.BLACK
        }
        scaled.setPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return scaled
    }

    // ── Utils ────────────────────────────────────────────────────────────

    private fun detectImageType(bmp: Bitmap): ImageType {
        val sx = max(1, bmp.width / 15); val sy = max(1, bmp.height / 15)
        var sat = 0f; var n = 0; val hsv = FloatArray(3)
        for (y in 0 until bmp.height step sy) {
            for (x in 0 until bmp.width step sx) {
                Color.colorToHSV(bmp.getPixel(x, y), hsv); sat += hsv[1]; n++; if (n >= 200) break
            }
            if (n >= 200) break
        }
        return if (n > 0 && sat / n * 100f < 10f) ImageType.GRAYSCALE else ImageType.COLOR
    }

    private fun toGrayscaleBitmap(src: Bitmap): Bitmap {
        val r = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(r); val p = Paint()
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        c.drawBitmap(src, 0f, 0f, p); return r
    }
}
