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
import com.google.android.gms.common.util.CollectionUtils.listOf
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LaMa-based inpainting engine using TensorFlow Lite.
 *
 * Blending: Laplacian pyramid (Burt & Adelson 1983)
 * - Coarse levels fix large-scale colour/brightness mismatch
 * - Fine levels preserve sharp texture and edges
 * - Hard binary mask input; Gaussian pyramid creates soft multi-scale gradient
 */
object LamaInpainter {

    private const val TAG = "LamaInpainter"
    private const val MODEL_PATH = "models/LaMa-Dilated_float.tflite"
    // Keep original 10px padding - 5px was too tight and caused edge artifacts
    private const val MASK_PADDING = 10
    private const val CONTEXT_PADDING = 128

    /** Morphological dilation radius applied to the binary mask before LaMa inference.
     *  Removes residual ink pixels at character-silhouette boundaries. */
    private const val MASK_DILATE_RADIUS = 5

    /** Unsharp mask strength applied to the filled region after pyramid blend.
     *  0 = no sharpening, 1 = full difference added back. */
    private const val SHARPEN_AMOUNT = 0.45f
    private const val SHARPEN_BLUR_RADIUS = 1

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var inputH = 0; private var inputW = 0; private var inputC = 0
    private var maskC = 0; private var outputC = 0
    private var hasMaskInput = false; private var isDynamic = false
    private val mutex = Mutex()
    private var imgBuf: ByteBuffer? = null; private var maskBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null; private var combBuf: ByteBuffer? = null

    enum class ImageType { GRAYSCALE, COLOR }
    data class InpaintBlock(
        val bounds: Rect, val shapeType: Int = 0,
        val overlayInsetHorizontal: Float = 0f, val overlayInsetVertical: Float = 0f
    )

    // ── Initialise ────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val modelFile = FileUtil.loadMappedFile(context, MODEL_PATH)
            val options = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
            }
            val interp = Interpreter(modelFile, options)
            interpreter = interp
            val imgShape = interp.getInputTensor(0).shape()
            Log.i(TAG, "Input[0] shape: ${imgShape.contentToString()}")
            hasMaskInput = interp.inputTensorCount > 1
            if (hasMaskInput) {
                val ms = interp.getInputTensor(1).shape()
                maskC = if (ms.size == 4) ms[3] else 1
            }
            val outShape = interp.getOutputTensor(0).shape()
            if (imgShape.size == 4) { inputH = imgShape[1]; inputW = imgShape[2]; inputC = imgShape[3] }
            if (outShape.size == 4) { outputC = outShape[3] }
            isDynamic = inputH <= 0 || inputW <= 0
            if (isDynamic) { inputH = 512; inputW = 512 }
            if (inputC <= 0) inputC = 3; if (maskC <= 0) maskC = 1; if (outputC <= 0) outputC = 3
            allocateBuffers()
            isInitialized = true
            Log.i(TAG, "LamaInpainter ready: ${inputW}x${inputH} hasMask=$hasMaskInput dynamic=$isDynamic")
        } catch (e: Exception) { Log.e(TAG, "Init failed", e); isInitialized = false }
    }

    private fun allocateBuffers() {
        imgBuf  = ByteBuffer.allocateDirect(inputH * inputW * inputC * 4).order(ByteOrder.nativeOrder())
        if (hasMaskInput)
            maskBuf = ByteBuffer.allocateDirect(inputH * inputW * maskC * 4).order(ByteOrder.nativeOrder())
        else
            combBuf = ByteBuffer.allocateDirect(inputH * inputW * (inputC + 1) * 4).order(ByteOrder.nativeOrder())
        outBuf  = ByteBuffer.allocateDirect(inputH * inputW * outputC * 4).order(ByteOrder.nativeOrder())
    }

    fun isReady() = isInitialized

    // ── Public API ────────────────────────────────────────────────────────

    suspend fun inpaintBlocks(
        image: Bitmap, blocks: List<InpaintBlock>,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized || blocks.isEmpty()) return@withContext null
        try {
            val t0 = System.currentTimeMillis()
            onProgress?.invoke("Lấy dữ liệu...")
            val imageType = detectImageType(image)
            val work = if (imageType == ImageType.GRAYSCALE) toGray(image) else image.copy(Bitmap.Config.ARGB_8888, true)
            val fullMask = createMaskFromBlocks(image.width, image.height, blocks)
            val paddedBlocks = blocks.map { padBlock(it, image.width, image.height) }
            val clusters = paddedBlocks.map { listOf(it) }
            val result = mutex.withLock { processRegionClusters(work, fullMask, clusters, onProgress) }
            fullMask.recycle(); work.recycle()
            val final = if (imageType == ImageType.GRAYSCALE && result != null) { val g = toGray(result); result.recycle(); g } else result
            Log.d(TAG, "inpaintBlocks done: ${System.currentTimeMillis() - t0}ms")
            final
        } catch (e: Exception) { Log.e(TAG, "inpaintBlocks failed", e); null }
    }

    suspend fun inpaintWithMask(
        image: Bitmap, mask: Bitmap,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext null
        try { onProgress?.invoke("Lấy dữ liệu..."); inpaintWithMaskInternal(image, mask, onProgress) }
        catch (e: Exception) { Log.e(TAG, "inpaintWithMask failed", e); null }
    }

    suspend fun inpaintPoints(
        image: Bitmap, points: List<Point>, radius: Int = 15,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized || points.isEmpty()) return@withContext null
        try {
            onProgress?.invoke("Lấy dữ liệu...")
            val mask = createMaskFromPoints(image.width, image.height, points, radius)
            val r = inpaintWithMaskInternal(image, mask, onProgress)
            mask.recycle(); r
        } catch (e: Exception) { Log.e(TAG, "inpaintPoints failed", e); null }
    }

    /**
     * Patch-based content-aware fill — no ML inference required.
     *
     * For each masked block the algorithm:
     * 1. Extracts surrounding context pixels (unmasked border ring inside the crop).
     * 2. Slides a same-sized window over the whole image (stride = 8) and computes SSD
     *    between those context positions in the candidate and the original.
     * 3. Copies the best-matching patch over the masked area and blends seamlessly
     *    using the same Laplacian pyramid used for LaMa output.
     *
     * Works extremely well on manga where backgrounds are white or repeating tones.
     * Instant (<50 ms on device) — no neural-network inference.
     */
    suspend fun inpaintPatchBased(
        image: Bitmap, blocks: List<InpaintBlock>,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (blocks.isEmpty()) return@withContext null
        try {
            val t0 = System.currentTimeMillis()
            val w = image.width; val h = image.height
            val result = image.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(w * h)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            val fullMask = createMaskFromBlocks(w, h, blocks)
            for ((idx, block) in blocks.withIndex()) {
                onProgress?.invoke("Tìm vùng phù hợp (${idx + 1}/${blocks.size})...")
                val cropRect = padBlock(block, w, h)
                val cw = cropRect.width(); val ch = cropRect.height()
                val mkPx = IntArray(cw * ch)
                fullMask.getPixels(mkPx, 0, cw, cropRect.left, cropRect.top, cw, ch)
                val maskFloats = FloatArray(cw * ch) { i ->
                    if (Color.red(mkPx[i]) > 10 || Color.green(mkPx[i]) > 10 || Color.blue(mkPx[i]) > 10) 1f else 0f
                }
                onProgress?.invoke("Bù điểm ảnh (${idx + 1}/${blocks.size})...")
                val bestRect = findBestPatch(pixels, w, h, maskFloats, cropRect)
                val patchBmp = Bitmap.createBitmap(image, bestRect.left, bestRect.top, cw, ch)
                blendCropIntoResult(result, patchBmp, fullMask, cropRect)
                patchBmp.recycle()
            }
            fullMask.recycle()
            Log.d(TAG, "inpaintPatchBased done: ${System.currentTimeMillis() - t0}ms")
            result
        } catch (e: Exception) { Log.e(TAG, "inpaintPatchBased failed", e); null }
    }

    fun release() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null; isInitialized = false
        imgBuf = null; maskBuf = null; outBuf = null; combBuf = null
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private suspend fun inpaintWithMaskInternal(
        image: Bitmap, mask: Bitmap, onProgress: ((String) -> Unit)?
    ): Bitmap? {
        val t0 = System.currentTimeMillis()
        val imageType = detectImageType(image)
        val work = if (imageType == ImageType.GRAYSCALE) toGray(image) else image.copy(Bitmap.Config.ARGB_8888, true)
        val normMask = normalizeMask(mask, image.width, image.height)
        val bounds = findMaskBounds(normMask)
        if (bounds == null) { normMask.recycle(); work.recycle(); return null }
        val result = mutex.withLock { processRegionClusters(work, normMask, listOf(listOf(bounds)), onProgress) }
        normMask.recycle(); work.recycle()
        val final = if (imageType == ImageType.GRAYSCALE && result != null) { val g = toGray(result); result.recycle(); g } else result
        Log.d(TAG, "inpaintWithMask done: ${System.currentTimeMillis() - t0}ms")
        return final
    }

    private fun padBlock(b: InpaintBlock, imgW: Int, imgH: Int): Rect {
        val ib = Rect(
            (b.bounds.left + b.overlayInsetHorizontal.toInt()),
            (b.bounds.top + b.overlayInsetVertical.toInt()),
            (b.bounds.right - b.overlayInsetHorizontal.toInt()),
            (b.bounds.bottom - b.overlayInsetVertical.toInt())
        )
        val eff = if (ib.width() > 0 && ib.height() > 0) ib else b.bounds
        return Rect(
            (eff.left - MASK_PADDING).coerceAtLeast(0),
            (eff.top - MASK_PADDING).coerceAtLeast(0),
            (eff.right + MASK_PADDING).coerceAtMost(imgW),
            (eff.bottom + MASK_PADDING).coerceAtMost(imgH)
        )
    }

    /**
     * Analyze background complexity to decide between patch-based vs neural network inpainting.
     * Returns complexity score: 0.0 (uniform) to 1.0 (very complex)
     */
    private fun analyzeBackgroundComplexity(image: Bitmap, region: Rect, mask: Bitmap): Float {
        val w = region.width()
        val h = region.height()
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, region.left, region.top, w, h)

        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, region.left, region.top, w, h)

        // Extract unmasked (context) pixels only
        val contextPixels = mutableListOf<Int>()
        for (i in pixels.indices) {
            if (Color.red(maskPixels[i]) < 10 && Color.green(maskPixels[i]) < 10 && Color.blue(maskPixels[i]) < 10) {
                contextPixels.add(pixels[i])
            }
        }

        if (contextPixels.size < 100) return 1.0f // Too few context pixels, use neural network

        // 1. Color variance (low variance = uniform background)
        val rValues = contextPixels.map { Color.red(it) }
        val gValues = contextPixels.map { Color.green(it) }
        val bValues = contextPixels.map { Color.blue(it) }

        val rVariance = computeVariance(rValues)
        val gVariance = computeVariance(gValues)
        val bVariance = computeVariance(bValues)
        val avgVariance = (rVariance + gVariance + bVariance) / 3.0

        // Normalize variance to 0-1 range (variance > 1000 = complex)
        val varianceScore = (avgVariance / 1000.0).coerceIn(0.0, 1.0)

        // 2. Edge density (few edges = simple background)
        val edgeCount = countEdges(pixels, w, h, maskPixels)
        val edgeDensity = edgeCount.toDouble() / contextPixels.size
        val edgeScore = (edgeDensity * 10.0).coerceIn(0.0, 1.0)

        // 3. Screentone detection: high edge density + low variance = screentone pattern
        // Screentone should ALWAYS use neural network (not patch-based)
        val isScreentone = edgeScore > 0.4 && varianceScore < 0.3
        if (isScreentone) {
            Log.d(TAG, "Screentone detected: variance=$avgVariance, edges=$edgeCount → forcing neural network")
            return 1.0f // Force neural network for screentone
        }

        // Combined complexity score (weighted average)
        // Increased edge weight from 0.4 to 0.7 - edges are more important indicator
        val complexityScore = (varianceScore * 0.3 + edgeScore * 0.7).toFloat()

        Log.d(TAG, "Complexity analysis: variance=$avgVariance, edges=$edgeCount, score=$complexityScore")
        return complexityScore
    }

    private fun computeVariance(values: List<Int>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    private fun countEdges(pixels: IntArray, w: Int, h: Int, mask: IntArray): Int {
        var edgeCount = 0
        val threshold = 30 // Intensity difference threshold

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                // Skip masked pixels
                if (Color.red(mask[i]) > 10) continue

                val gray = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3
                val grayRight = (Color.red(pixels[i + 1]) + Color.green(pixels[i + 1]) + Color.blue(pixels[i + 1])) / 3
                val grayDown = (Color.red(pixels[i + w]) + Color.green(pixels[i + w]) + Color.blue(pixels[i + w])) / 3

                if (kotlin.math.abs(gray - grayRight) > threshold || kotlin.math.abs(gray - grayDown) > threshold) {
                    edgeCount++
                }
            }
        }
        return edgeCount
    }

    private fun processRegionClusters(
        image: Bitmap, mask: Bitmap, clusters: List<List<Rect>>,
        onProgress: ((String) -> Unit)?
    ): Bitmap? {
        val interp = interpreter ?: return null
        val w = image.width; val h = image.height
        val result = image.copy(Bitmap.Config.ARGB_8888, true)

        for ((idx, cluster) in clusters.withIndex()) {
            onProgress?.invoke("Xóa điểm ảnh (${idx + 1}/${clusters.size})...")
            val cb = unionBounds(cluster)
            val pX = max(CONTEXT_PADDING, (cb.width() * 0.4f).roundToInt())
            val pY = max(CONTEXT_PADDING, (cb.height() * 0.4f).roundToInt())
            val crop = Rect(
                (cb.left - pX).coerceAtLeast(0), (cb.top - pY).coerceAtLeast(0),
                (cb.right + pX).coerceAtMost(w), (cb.bottom + pY).coerceAtMost(h)
            )

            Log.d(TAG, "Cluster $idx crop=${crop.width()}x${crop.height()}")

            // Always use neural network - hybrid approach disabled due to quality issues
            val ci = Bitmap.createBitmap(result, crop.left, crop.top, crop.width(), crop.height())
            val cm = Bitmap.createBitmap(mask, crop.left, crop.top, crop.width(), crop.height())
            val (ri, pi) = resizeWithPadding(ci, inputW, inputH)
            val (rm, _) = resizeWithPadding(cm, inputW, inputH)
            ci.recycle(); cm.recycle()
            val out = runInference(interp, ri, rm)
            ri.recycle(); rm.recycle()
            if (out == null) continue
            val unpad = removePadding(out, pi); out.recycle()
            val scaled = Bitmap.createScaledBitmap(unpad, crop.width(), crop.height(), true); unpad.recycle()
            onProgress?.invoke("Bù điểm ảnh (${idx + 1}/${clusters.size})...")
            blendCropIntoResult(result, scaled, mask, crop)
            scaled.recycle()
        }
        onProgress?.invoke("Bù điểm ảnh 100%...")
        return result
    }

    // ── Laplacian pyramid seamless blend ─────────────────────────────────

    /**
     * Seamless blend using Laplacian pyramid (Burt & Adelson 1983).
     *
     * Why this beats single-scale alpha compositing:
     *  - Coarse pyramid levels handle large-scale colour/brightness mismatch.
     *  - Fine pyramid levels preserve sharp texture and edges.
     *  - The Gaussian mask pyramid smooths the transition at every scale
     *    without ever letting the original content bleed through the mask centre.
     *
     * Result: deep inside mask → 100% LaMa output (text fully erased)
     *         boundary ring    → invisible multi-scale gradient
     *         outside mask     → 100% original (untouched)
     */
    private fun blendCropIntoResult(
        result: Bitmap, crop: Bitmap, fullMask: Bitmap, cropRect: Rect
    ) {
        val cw = cropRect.width(); val ch = cropRect.height()
        val origPx = IntArray(cw * ch); result.getPixels(origPx, 0, cw, cropRect.left, cropRect.top, cw, ch)
        val lamaPx = IntArray(cw * ch); crop.getPixels(lamaPx, 0, cw, 0, 0, cw, ch)
        val mskPx  = IntArray(cw * ch); fullMask.getPixels(mskPx, 0, cw, cropRect.left, cropRect.top, cw, ch)

        // Hard binary mask: pyramid's Gaussian blur creates the soft gradient automatically
        val mask = FloatArray(cw * ch) { i ->
            if (Color.red(mskPx[i]) > 10 || Color.green(mskPx[i]) > 10 || Color.blue(mskPx[i]) > 10) 1f else 0f
        }

        val blended = laplacianPyramidBlend(origPx, lamaPx, mask, cw, ch)
        result.setPixels(blended, 0, cw, cropRect.left, cropRect.top, cw, ch)
        sharpenMaskedRegion(result, mask, cw, ch, cropRect)
    }

    // ── Unsharp mask sharpening ────────────────────────────────────────────

    /**
     * Apply unsharp mask restricted to pixels where [mask] > 0.5.
     * Unsharp formula: sharp = original + SHARPEN_AMOUNT * (original - blurred)
     * Restores manga line crispness that LaMa TFLite blurs during fill.
     */
    private fun sharpenMaskedRegion(result: Bitmap, mask: FloatArray, cw: Int, ch: Int, cropRect: Rect) {
        if (SHARPEN_AMOUNT <= 0f) return
        val px = IntArray(cw * ch)
        result.getPixels(px, 0, cw, cropRect.left, cropRect.top, cw, ch)

        // Inline separable box blur with radius SHARPEN_BLUR_RADIUS
        val r = SHARPEN_BLUR_RADIUS
        val k = 2 * r + 1
        val rF = FloatArray(cw * ch) { Color.red(px[it]) / 255f }
        val gF = FloatArray(cw * ch) { Color.green(px[it]) / 255f }
        val bF = FloatArray(cw * ch) { Color.blue(px[it]) / 255f }

        fun blurChannel(ch_: FloatArray): FloatArray {
            val tmp = FloatArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                var s = 0f
                for (d in -r..r) s += ch_[y * cw + (x + d).coerceIn(0, cw - 1)]
                tmp[y * cw + x] = s / k
            }
            val out = FloatArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                var s = 0f
                for (d in -r..r) s += tmp[(y + d).coerceIn(0, ch - 1) * cw + x]
                out[y * cw + x] = s / k
            }
            return out
        }

        val bR = blurChannel(rF); val bG = blurChannel(gF); val bB = blurChannel(bF)

        for (i in 0 until cw * ch) {
            if (mask[i] < 0.5f) continue
            val nr = (rF[i] + SHARPEN_AMOUNT * (rF[i] - bR[i])).coerceIn(0f, 1f)
            val ng = (gF[i] + SHARPEN_AMOUNT * (gF[i] - bG[i])).coerceIn(0f, 1f)
            val nb = (bF[i] + SHARPEN_AMOUNT * (bF[i] - bB[i])).coerceIn(0f, 1f)
            px[i] = Color.argb(255,
                (nr * 255).roundToInt(), (ng * 255).roundToInt(), (nb * 255).roundToInt())
        }
        result.setPixels(px, 0, cw, cropRect.left, cropRect.top, cw, ch)
    }

    // ── Laplacian pyramid helpers ─────────────────────────────────────────

    private data class PyramidLevel(val data: FloatArray, val w: Int, val h: Int)

    private fun laplacianPyramidBlend(
        orig: IntArray, inp: IntArray, mask: FloatArray, w: Int, h: Int, levels: Int = 6
    ): IntArray {
        fun ch(px: IntArray, c: Int) = FloatArray(px.size) { i ->
            when (c) { 0 -> Color.red(px[i]); 1 -> Color.green(px[i]); else -> Color.blue(px[i]) } / 255f
        }
        val blR = lapBlendChannel(ch(orig, 0), ch(inp, 0), mask, w, h, levels)
        val blG = lapBlendChannel(ch(orig, 1), ch(inp, 1), mask, w, h, levels)
        val blB = lapBlendChannel(ch(orig, 2), ch(inp, 2), mask, w, h, levels)
        return IntArray(w * h) { i ->
            Color.argb(255,
                (blR[i] * 255).roundToInt().coerceIn(0, 255),
                (blG[i] * 255).roundToInt().coerceIn(0, 255),
                (blB[i] * 255).roundToInt().coerceIn(0, 255)
            )
        }
    }

    private fun lapBlendChannel(
        orig: FloatArray, inp: FloatArray, mask: FloatArray, w: Int, h: Int, levels: Int
    ): FloatArray {
        val gOrig = buildGaussianPyramid(orig, w, h, levels)
        val gInp  = buildGaussianPyramid(inp,  w, h, levels)
        val gMask = buildGaussianPyramid(mask, w, h, levels)
        val lOrig = buildLaplacianPyramid(gOrig, levels)
        val lInp  = buildLaplacianPyramid(gInp,  levels)
        // Blend each pyramid level with the Gaussian mask at that level
        val blended = Array(levels + 1) { lv ->
            val lw = lOrig[lv].w; val lh = lOrig[lv].h
            val m = gMask[lv].data; val a = lInp[lv].data; val b = lOrig[lv].data
            PyramidLevel(FloatArray(lw * lh) { i -> a[i] * m[i] + b[i] * (1f - m[i]) }, lw, lh)
        }
        return reconstructLaplacian(blended, levels)
    }

    private fun buildGaussianPyramid(src: FloatArray, w: Int, h: Int, levels: Int): Array<PyramidLevel> {
        val pyr = ArrayList<PyramidLevel>(levels + 1)
        pyr.add(PyramidLevel(src.copyOf(), w, h))
        for (lv in 1..levels) {
            val prev = pyr[lv - 1]
            val blurred = gauss5Sep(prev.data, prev.w, prev.h)
            val dw = (prev.w + 1) / 2; val dh = (prev.h + 1) / 2
            val down = FloatArray(dw * dh) { i ->
                val dy = i / dw; val dx = i % dw
                blurred[(dy * 2).coerceAtMost(prev.h - 1) * prev.w + (dx * 2).coerceAtMost(prev.w - 1)]
            }
            pyr.add(PyramidLevel(down, dw, dh))
        }
        return pyr.toTypedArray()
    }

    private fun buildLaplacianPyramid(gauss: Array<PyramidLevel>, levels: Int): Array<PyramidLevel> {
        return Array(levels + 1) { lv ->
            if (lv == levels) {
                gauss[levels]          // coarsest level = raw Gaussian (no residual)
            } else {
                val curr = gauss[lv]; val next = gauss[lv + 1]
                val up = pyrUp(next.data, next.w, next.h, curr.w, curr.h)
                PyramidLevel(FloatArray(curr.w * curr.h) { i -> curr.data[i] - up[i] }, curr.w, curr.h)
            }
        }
    }

    private fun reconstructLaplacian(lap: Array<PyramidLevel>, levels: Int): FloatArray {
        var data = lap[levels].data.copyOf()
        var rw = lap[levels].w; var rh = lap[levels].h
        for (lv in levels - 1 downTo 0) {
            val tw = lap[lv].w; val th = lap[lv].h
            val up = pyrUp(data, rw, rh, tw, th)
            data = FloatArray(tw * th) { i -> up[i] + lap[lv].data[i] }
            rw = tw; rh = th
        }
        for (i in data.indices) data[i] = data[i].coerceIn(0f, 1f)
        return data
    }

    /** Separable 5-tap Gaussian [1,4,6,4,1]/16 — standard pyramid kernel */
    private fun gauss5Sep(src: FloatArray, w: Int, h: Int): FloatArray {
        val k = floatArrayOf(0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f)
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (d in -2..2) s += src[y * w + (x + d).coerceIn(0, w - 1)] * k[d + 2]
            tmp[y * w + x] = s
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (d in -2..2) s += tmp[(y + d).coerceIn(0, h - 1) * w + x] * k[d + 2]
            out[y * w + x] = s
        }
        return out
    }

    /** Bilinear upsample to exact target size (used in pyramid reconstruction) */
    private fun pyrUp(src: FloatArray, sw: Int, sh: Int, tw: Int, th: Int): FloatArray {
        val dst = FloatArray(tw * th)
        val sx = (sw - 1).toFloat() / (tw - 1).coerceAtLeast(1)
        val sy = (sh - 1).toFloat() / (th - 1).coerceAtLeast(1)
        for (y in 0 until th) {
            val fy = y * sy; val y0 = fy.toInt().coerceIn(0, sh - 1); val y1 = (y0 + 1).coerceAtMost(sh - 1); val vy = fy - y0
            for (x in 0 until tw) {
                val fx = x * sx; val x0 = fx.toInt().coerceIn(0, sw - 1); val x1 = (x0 + 1).coerceAtMost(sw - 1); val vx = fx - x0
                dst[y * tw + x] = src[y0 * sw + x0] * (1 - vx) * (1 - vy) +
                                   src[y0 * sw + x1] * vx * (1 - vy) +
                                   src[y1 * sw + x0] * (1 - vx) * vy +
                                   src[y1 * sw + x1] * vx * vy
            }
        }
        return dst
    }

    // ── Clustering helpers ────────────────────────────────────────────────

    private fun unionBounds(rects: List<Rect>): Rect {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (rc in rects) { l = min(l, rc.left); t = min(t, rc.top); r = max(r, rc.right); b = max(b, rc.bottom) }
        return Rect(l, t, r, b)
    }

    private fun findMaskBounds(mask: Bitmap): Rect? {
        val w = mask.width; val h = mask.height
        val px = IntArray(w * h); mask.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            if (Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) {
                minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
            }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    // ── Resize / padding ──────────────────────────────────────────────────

    data class PadInfo(val offsetX: Int, val offsetY: Int, val scaledW: Int, val scaledH: Int)

    private fun resizeWithPadding(src: Bitmap, tW: Int, tH: Int): Pair<Bitmap, PadInfo> {
        val scale = min(tW.toFloat() / src.width, tH.toFloat() / src.height)
        val sW = (src.width * scale).roundToInt().coerceAtLeast(1)
        val sH = (src.height * scale).roundToInt().coerceAtLeast(1)
        val oX = (tW - sW) / 2; val oY = (tH - sH) / 2
        val scaled = Bitmap.createScaledBitmap(src, sW, sH, true)
        val padded = Bitmap.createBitmap(tW, tH, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply { drawColor(Color.BLACK); drawBitmap(scaled, oX.toFloat(), oY.toFloat(), null) }
        scaled.recycle()
        return Pair(padded, PadInfo(oX, oY, sW, sH))
    }

    private fun removePadding(src: Bitmap, info: PadInfo): Bitmap =
        Bitmap.createBitmap(src,
            info.offsetX.coerceAtLeast(0), info.offsetY.coerceAtLeast(0),
            info.scaledW.coerceAtMost(src.width  - info.offsetX.coerceAtLeast(0)),
            info.scaledH.coerceAtMost(src.height - info.offsetY.coerceAtLeast(0)))

    // ── Inference ─────────────────────────────────────────────────────────

    private fun runInference(interp: Interpreter, image: Bitmap, mask: Bitmap): Bitmap? {
        return try {
            if (isDynamic) {
                interp.resizeInput(0, intArrayOf(1, inputH, inputW, inputC))
                if (hasMaskInput) interp.resizeInput(1, intArrayOf(1, inputH, inputW, maskC))
                interp.allocateTensors()
            }
            val outShape = interp.getOutputTensor(0).shape()
            val oH = outShape[1]; val oW = outShape[2]; val oC = outShape[3]

            val mPx = IntArray(inputW * inputH); mask.getPixels(mPx, 0, inputW, 0, 0, inputW, inputH)
            val mF = FloatArray(inputW * inputH) { i ->
                if (Color.red(mPx[i]) > 10 || Color.green(mPx[i]) > 10 || Color.blue(mPx[i]) > 10) 1f else 0f
            }
            val iPx = IntArray(inputW * inputH); image.getPixels(iPx, 0, inputW, 0, 0, inputW, inputH)

            val ib = imgBuf!!; ib.rewind()
            for (i in iPx.indices) {
                val k = 1f - mF[i]
                ib.putFloat(Color.red(iPx[i]) / 255f * k)
                ib.putFloat(Color.green(iPx[i]) / 255f * k)
                ib.putFloat(Color.blue(iPx[i]) / 255f * k)
                for (c in 3 until inputC) ib.putFloat(0f)
            }

            val obN = oH * oW * oC * 4
            val ob = if (outBuf != null && outBuf!!.capacity() >= obN) outBuf!!
                     else ByteBuffer.allocateDirect(obN).order(ByteOrder.nativeOrder()).also { outBuf = it }
            ob.rewind()

            if (hasMaskInput) {
                val mbN = inputH * inputW * maskC * 4
                val mb = if (maskBuf != null && maskBuf!!.capacity() >= mbN) maskBuf!!
                         else ByteBuffer.allocateDirect(mbN).order(ByteOrder.nativeOrder()).also { maskBuf = it }
                mb.rewind()
                for (v in mF) { mb.putFloat(v); for (c in 1 until maskC) mb.putFloat(v) }
                mb.rewind()
                interp.runForMultipleInputsOutputs(arrayOf(ib, mb), mapOf(0 to ob))
            } else {
                val cbN = inputH * inputW * (inputC + 1) * 4
                val cb = if (combBuf != null && combBuf!!.capacity() >= cbN) combBuf!!
                         else ByteBuffer.allocateDirect(cbN).order(ByteOrder.nativeOrder()).also { combBuf = it }
                cb.rewind()
                for (i in iPx.indices) {
                    val k = 1f - mF[i]
                    cb.putFloat(Color.red(iPx[i]) / 255f * k)
                    cb.putFloat(Color.green(iPx[i]) / 255f * k)
                    cb.putFloat(Color.blue(iPx[i]) / 255f * k)
                    cb.putFloat(mF[i])
                }
                cb.rewind()
                if (isDynamic) { interp.resizeInput(0, intArrayOf(1, inputH, inputW, inputC + 1)); interp.allocateTensors() }
                interp.runForMultipleInputsOutputs(arrayOf(cb), mapOf(0 to ob))
            }
            floatBufferToBitmap(ob, oW, oH, oC)
        } catch (e: Exception) { Log.e(TAG, "Inference failed: ${e.message}", e); null }
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

    // ── Mask generation ───────────────────────────────────────────────────

    private fun createMaskFromBlocks(w: Int, h: Int, blocks: List<InpaintBlock>): Bitmap {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(mask); cv.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        for (b in blocks) {
            val ib = Rect(
                (b.bounds.left + b.overlayInsetHorizontal.toInt()),
                (b.bounds.top + b.overlayInsetVertical.toInt()),
                (b.bounds.right - b.overlayInsetHorizontal.toInt()),
                (b.bounds.bottom - b.overlayInsetVertical.toInt())
            )
            if (ib.width() <= 0 || ib.height() <= 0) continue
            val pr = Rect(
                (ib.left - MASK_PADDING).coerceAtLeast(0), (ib.top - MASK_PADDING).coerceAtLeast(0),
                (ib.right + MASK_PADDING).coerceAtMost(w), (ib.bottom + MASK_PADDING).coerceAtMost(h)
            )
            if (b.shapeType == 1) cv.drawOval(RectF(pr), paint) else cv.drawRect(pr, paint)
        }
        return dilateMask(mask, MASK_DILATE_RADIUS)
    }

    private fun createMaskFromPoints(w: Int, h: Int, points: List<Point>, radius: Int): Bitmap {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(mask); cv.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        for (p in points) cv.drawCircle(p.x.toFloat(), p.y.toFloat(), radius.toFloat(), paint)
        return mask
    }

    private fun normalizeMask(mask: Bitmap, tw: Int, th: Int): Bitmap {
        val sc = if (mask.width != tw || mask.height != th) Bitmap.createScaledBitmap(mask, tw, th, false)
                 else mask.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(sc.width * sc.height); sc.getPixels(px, 0, sc.width, 0, 0, sc.width, sc.height)
        for (i in px.indices) {
            val p = px[i]
            px[i] = if ((Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) && Color.alpha(p) > 10) Color.WHITE else Color.BLACK
        }
        sc.setPixels(px, 0, sc.width, 0, 0, sc.width, sc.height)
        return dilateMask(sc, MASK_DILATE_RADIUS)
    }

    // ── Mask dilation ─────────────────────────────────────────────────────

    /**
     * Morphological dilation: expands every white pixel in [src] by [radius] pixels.
     * Uses a box structuring element (separable: horizontal then vertical pass).
     * Ensures remnant ink at character silhouettes is fully covered by the mask.
     */
    private fun dilateMask(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        // 1 = mask, 0 = background
        val bin = BooleanArray(w * h) { i ->
            val p = px[i]
            Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10
        }
        // Horizontal pass
        val hPass = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var hit = false
            for (d in -radius..radius) {
                if (bin[y * w + (x + d).coerceIn(0, w - 1)]) { hit = true; break }
            }
            hPass[y * w + x] = hit
        }
        // Vertical pass
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var hit = false
            for (d in -radius..radius) {
                if (hPass[(y + d).coerceIn(0, h - 1) * w + x]) { hit = true; break }
            }
            out[y * w + x] = if (hit) Color.WHITE else Color.BLACK
        }
        result.setPixels(out, 0, w, 0, 0, w, h)
        src.recycle()
        return result
    }

    // ── Patch search ──────────────────────────────────────────────────────

    /**
     * Returns a [Rect] (top-left origin, same size as [cropRect]) for the patch
     * in [pixels] whose unmasked context pixels best match those of [cropRect].
     *
     * @param pixels         Full image pixels row-major, stride = [imgW]
     * @param maskFloatsCrop Float mask for the crop: 1 = hole to fill, 0 = context
     * @param cropRect       Position + size of the hole in image coordinates
     */
    private fun findBestPatch(
        pixels: IntArray, imgW: Int, imgH: Int,
        maskFloatsCrop: FloatArray, cropRect: Rect
    ): Rect {
        val cw = cropRect.width(); val ch = cropRect.height()
        val CONTEXT_STRIDE = 4
        val SEARCH_STRIDE  = 8

        // Collect unmasked context sample positions within the crop
        val ctxFlatIdx = mutableListOf<Int>()
        val ctxR       = mutableListOf<Float>()
        val ctxG       = mutableListOf<Float>()
        val ctxB       = mutableListOf<Float>()
        for (y in 0 until ch step CONTEXT_STRIDE) {
            for (x in 0 until cw step CONTEXT_STRIDE) {
                val fi = y * cw + x
                if (maskFloatsCrop[fi] < 0.5f) {
                    val p = pixels[(cropRect.top + y) * imgW + (cropRect.left + x)]
                    ctxFlatIdx.add(fi)
                    ctxR.add(Color.red(p)   / 255f)
                    ctxG.add(Color.green(p) / 255f)
                    ctxB.add(Color.blue(p)  / 255f)
                }
            }
        }
        if (ctxFlatIdx.isEmpty()) return cropRect   // no context → stay in place

        val maxX = imgW - cw; val maxY = imgH - ch
        if (maxX <= 0 || maxY <= 0) return cropRect

        var bestScore = Float.MAX_VALUE
        var bestX = cropRect.left; var bestY = cropRect.top

        for (ty in 0..maxY step SEARCH_STRIDE) {
            outer@ for (tx in 0..maxX step SEARCH_STRIDE) {
                // Skip candidates that heavily overlap the original hole
                val olW = (min(tx + cw, cropRect.right)  - max(tx, cropRect.left)).coerceAtLeast(0)
                val olH = (min(ty + ch, cropRect.bottom) - max(ty, cropRect.top)).coerceAtLeast(0)
                if (olW * olH > cw * ch / 6) continue

                var score = 0f
                for (i in ctxFlatIdx.indices) {
                    val cx = ctxFlatIdx[i] % cw
                    val cy = ctxFlatIdx[i] / cw
                    val p  = pixels[(ty + cy) * imgW + (tx + cx)]
                    val dr = ctxR[i] - Color.red(p)   / 255f
                    val dg = ctxG[i] - Color.green(p) / 255f
                    val db = ctxB[i] - Color.blue(p)  / 255f
                    score += dr * dr + dg * dg + db * db
                    if (score >= bestScore) continue@outer  // early exit
                }
                if (score < bestScore) { bestScore = score; bestX = tx; bestY = ty }
            }
        }
        return Rect(bestX, bestY, bestX + cw, bestY + ch)
    }

    // ── Utils ─────────────────────────────────────────────────────────────

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

    private fun toGray(src: Bitmap): Bitmap {
        val r = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(r); val p = Paint()
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        c.drawBitmap(src, 0f, 0f, p); return r
    }
}
