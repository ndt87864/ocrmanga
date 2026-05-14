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
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LaMa-based inpainting engine using ONNX Runtime.
 *
 * Model: anime-manga-big-lama.onnx
 *   Input "image": [1, 3, 512, 512] float32 (NCHW, values 0..1)
 *   Input "mask":  [1, 1, 512, 512] float32 (NCHW, values 0 or 1)
 *   Output "output": [1, 3, 512, 512] float32 (NCHW, values 0..1)
 *
 * Blending: Laplacian pyramid (Burt & Adelson 1983)
 * - Coarse levels fix large-scale colour/brightness mismatch
 * - Fine levels preserve sharp texture and edges
 * - Hard binary mask input; Gaussian pyramid creates soft multi-scale gradient
 */
object LamaInpainter {

    private const val TAG = "LamaInpainter"
    private const val MODEL_PATH = "models/anime-manga-big-lama.onnx"
    // Keep original 10px padding - 5px was too tight and caused edge artifacts
    private const val MASK_PADDING = 10
    private const val CONTEXT_PADDING = 128
    private const val MAX_CLUSTER_AREA = 900_000
    private const val MAX_CLUSTER_SIDE = 1400

    /** Morphological dilation radius applied to the binary mask before LaMa inference.
     *  Removes residual ink pixels at character-silhouette boundaries. */
    private const val MASK_DILATE_RADIUS = 5

    /** Unsharp mask strength applied to the filled region after pyramid blend.
     *  0 = no sharpening, 1 = full difference added back. */
    private const val SHARPEN_AMOUNT = 0.45f
    private const val SHARPEN_BLUR_RADIUS = 1

    /** Fixed model dimensions (NCHW) */
    private const val MODEL_H = 512
    private const val MODEL_W = 512
    private const val MODEL_C = 3

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false
    private var inputH = MODEL_H; private var inputW = MODEL_W; private var inputC = MODEL_C
    private val mutex = Mutex()

    // Buffer pools để tái sử dụng, tránh cấp phát lặp lại
    private val tensorImageBuffer = FloatArray(1 * 3 * MODEL_H * MODEL_W)
    private val tensorMaskBuffer = FloatArray(1 * 1 * MODEL_H * MODEL_W)
    private val pixelBuffer = IntArray(MODEL_H * MODEL_W)

    // FloatBuffer wrappers tái sử dụng cho ONNX tensor creation
    private val imageFloatBuffer = FloatBuffer.wrap(tensorImageBuffer)
    private val maskFloatBuffer = FloatBuffer.wrap(tensorMaskBuffer)

    // Buffer cho pyramid blend - sẽ được resize khi cần
    private var pyramidWorkBuffer: FloatArray? = null
    private var pyramidMaxSize = 0

    enum class ImageType { GRAYSCALE, COLOR }
    data class InpaintBlock(
        val bounds: Rect, val shapeType: Int = 0,
        val overlayInsetHorizontal: Float = 0f, val overlayInsetVertical: Float = 0f
    )

    // ── Initialise ────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            // Copy model from assets to internal storage to avoid OOM from readBytes()
            val modelFile = File(context.filesDir, "anime-manga-big-lama.onnx")
            // Re-copy if file missing or size changed (model updated)
            val assetSize = context.assets.open(MODEL_PATH).use { it.available().toLong() }
            if (!modelFile.exists() || modelFile.length() != assetSize) {
                Log.i(TAG, "Copying ONNX model to internal storage (asset=$assetSize, local=${modelFile.length()})...")
                context.assets.open(MODEL_PATH).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
                Log.i(TAG, "Model copied: ${modelFile.length()} bytes")
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
            }

            val session = env.createSession(modelFile.absolutePath, sessionOptions)
            ortSession = session

            // Log input/output info
            for ((name, info) in session.inputInfo) {
                val tensorInfo = info.info as? ai.onnxruntime.TensorInfo
                Log.i(TAG, "Input '$name' shape: ${tensorInfo?.shape?.contentToString()}")
            }
            for ((name, info) in session.outputInfo) {
                val tensorInfo = info.info as? ai.onnxruntime.TensorInfo
                Log.i(TAG, "Output '$name' shape: ${tensorInfo?.shape?.contentToString()}")
            }

            isInitialized = true
            Log.i(TAG, "LamaInpainter ready (ONNX): ${inputW}x${inputH}")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            isInitialized = false
        }
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
            // Tạo bitmap làm việc chỉ một lần - sẽ được sửa đổi tại chỗ
            val work = image.copy(Bitmap.Config.ARGB_8888, true)
            val fullMask = createMaskFromBlocks(image.width, image.height, blocks)
            val paddedBlocks = blocks.map { padBlock(it, image.width, image.height) }
            val clusters = createSafeClusters(paddedBlocks, image.width, image.height)
            val result = mutex.withLock { processRegionClusters(work, fullMask, clusters, onProgress) }
            fullMask.recycle()
            //Log.d(TAG, "inpaintBlocks done: ${System.currentTimeMillis() - t0}ms")
            result
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
            // Làm việc trực tiếp trên bản sao duy nhất
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
                // Blend trực tiếp vào result
                blendCropIntoResult(result, patchBmp, fullMask, cropRect)
                patchBmp.recycle()
            }
            fullMask.recycle()
            //Log.d(TAG, "inpaintPatchBased done: ${System.currentTimeMillis() - t0}ms")
            result
        } catch (e: Exception) { Log.e(TAG, "inpaintPatchBased failed", e); null }
    }

    fun release() {
        try { ortSession?.close() } catch (_: Exception) {}
        try { ortEnv?.close() } catch (_: Exception) {}
        ortSession = null; ortEnv = null; isInitialized = false
        // Giải phóng buffer pools
        pyramidWorkBuffer = null
        pyramidMaxSize = 0
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private suspend fun inpaintWithMaskInternal(
        image: Bitmap, mask: Bitmap, onProgress: ((String) -> Unit)?
    ): Bitmap? {
        val t0 = System.currentTimeMillis()
        val work = image.copy(Bitmap.Config.ARGB_8888, true)
        val normMask = normalizeMask(mask, image.width, image.height)
        val bounds = findMaskBounds(normMask)
        if (bounds == null) { normMask.recycle(); work.recycle(); return null }
        val result = mutex.withLock { processRegionClusters(work, normMask, listOf(listOf(bounds)), onProgress) }
        normMask.recycle()
        //Log.d(TAG, "inpaintWithMask done: ${System.currentTimeMillis() - t0}ms")
        return result
    }

    private fun padBlock(b: InpaintBlock, imgW: Int, imgH: Int): Rect {
        // Use full OCR bounds (NOT overlay-inset area) for inpainting region
        val ob = b.bounds
        val eff = if (ob.width() > 0 && ob.height() > 0) ob else b.bounds
        // Expand by 4% to ensure complete text removal
        val expandH = (eff.width() * 0.04f).toInt().coerceAtLeast(MASK_PADDING)
        val expandV = (eff.height() * 0.04f).toInt().coerceAtLeast(MASK_PADDING)
        return Rect(
            (eff.left - expandH).coerceAtLeast(0),
            (eff.top - expandV).coerceAtLeast(0),
            (eff.right + expandH).coerceAtMost(imgW),
            (eff.bottom + expandV).coerceAtMost(imgH)
        )
    }

    private fun createSafeClusters(blocks: List<Rect>, imgW: Int, imgH: Int): List<List<Rect>> {
        if (blocks.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<Rect>>()
        val sortedBlocks = blocks.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })

        for (block in sortedBlocks) {
            var added = false
            for (cluster in clusters) {
                val candidateBounds = unionBounds(cluster + block)
                val cropBounds = expandForContext(candidateBounds, imgW, imgH)
                val safeSize = cropBounds.width() * cropBounds.height() <= MAX_CLUSTER_AREA &&
                        cropBounds.width() <= MAX_CLUSTER_SIDE && cropBounds.height() <= MAX_CLUSTER_SIDE
                val nearby = cluster.any { existing ->
                    val dx = if (block.right < existing.left) existing.left - block.right else if (existing.right < block.left) block.left - existing.right else 0
                    val dy = if (block.bottom < existing.top) existing.top - block.bottom else if (existing.bottom < block.top) block.top - existing.bottom else 0
                    dx <= CONTEXT_PADDING * 2 && dy <= CONTEXT_PADDING * 2
                }
                if (safeSize && nearby) {
                    cluster.add(block)
                    added = true
                    break
                }
            }
            if (!added) clusters.add(mutableListOf(block))
        }

        return clusters
    }

    private fun expandForContext(bounds: Rect, imgW: Int, imgH: Int): Rect {
        val pX = max(CONTEXT_PADDING, (bounds.width() * 0.4f).roundToInt())
        val pY = max(CONTEXT_PADDING, (bounds.height() * 0.4f).roundToInt())
        return Rect(
            (bounds.left - pX).coerceAtLeast(0),
            (bounds.top - pY).coerceAtLeast(0),
            (bounds.right + pX).coerceAtMost(imgW),
            (bounds.bottom + pY).coerceAtMost(imgH)
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
            //Log.d(TAG, "Screentone detected: variance=$avgVariance, edges=$edgeCount → forcing neural network")
            return 1.0f // Force neural network for screentone
        }

        // Combined complexity score (weighted average)
        // Increased edge weight from 0.4 to 0.7 - edges are more important indicator
        val complexityScore = (varianceScore * 0.3 + edgeScore * 0.7).toFloat()

        //Log.d(TAG, "Complexity analysis: variance=$avgVariance, edges=$edgeCount, score=$complexityScore")
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
        val session = ortSession ?: return null
        val env = ortEnv ?: return null
        val w = image.width; val h = image.height
        // Làm việc trực tiếp trên image thay vì tạo bản sao
        // image đã là mutable copy từ caller

        for ((idx, cluster) in clusters.withIndex()) {
            onProgress?.invoke("Xóa điểm ảnh (${idx + 1}/${clusters.size})...")
            val cb = unionBounds(cluster)
            val pX = max(CONTEXT_PADDING, (cb.width() * 0.4f).roundToInt())
            val pY = max(CONTEXT_PADDING, (cb.height() * 0.4f).roundToInt())
            val crop = Rect(
                (cb.left - pX).coerceAtLeast(0), (cb.top - pY).coerceAtLeast(0),
                (cb.right + pX).coerceAtMost(w), (cb.bottom + pY).coerceAtMost(h)
            )

            //Log.d(TAG, "Cluster $idx crop=${crop.width()}x${crop.height()}")

            val ci = Bitmap.createBitmap(image, crop.left, crop.top, crop.width(), crop.height())
            val cm = Bitmap.createBitmap(mask, crop.left, crop.top, crop.width(), crop.height())
            val (ri, pi) = resizeWithPadding(ci, inputW, inputH)
            val (rm, _) = resizeWithPadding(cm, inputW, inputH)
            ci.recycle(); cm.recycle()
            val out = runInference(env, session, ri, rm)
            ri.recycle(); rm.recycle()
            if (out == null) continue
            val unpad = removePadding(out, pi); out.recycle()
            val scaled = Bitmap.createScaledBitmap(unpad, crop.width(), crop.height(), true); unpad.recycle()
            onProgress?.invoke("Bù điểm ảnh (${idx + 1}/${clusters.size})...")
            // Blend trực tiếp vào image (in-place modification)
            blendCropIntoResult(image, scaled, mask, crop)
            scaled.recycle()
        }
        onProgress?.invoke("Bù điểm ảnh 100%...")
        return image
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
     * Restores manga line crispness that LaMa blurs during fill.
     */
    private fun sharpenMaskedRegion(result: Bitmap, mask: FloatArray, cw: Int, ch: Int, cropRect: Rect) {
        if (SHARPEN_AMOUNT <= 0f) return
        val size = cw * ch

        // Tái sử dụng buffer nếu đủ lớn
        if (pyramidMaxSize < size) {
            pyramidMaxSize = size
            pyramidWorkBuffer = FloatArray(size * 3)
        }
        val workBuf = pyramidWorkBuffer!!

        val px = IntArray(size)
        result.getPixels(px, 0, cw, cropRect.left, cropRect.top, cw, ch)

        // Inline separable box blur with radius SHARPEN_BLUR_RADIUS
        val r = SHARPEN_BLUR_RADIUS
        val k = 2 * r + 1

        // Sử dụng workBuf cho R, G, B channels
        for (i in 0 until size) {
            workBuf[i] = Color.red(px[i]) / 255f
            workBuf[size + i] = Color.green(px[i]) / 255f
            workBuf[size * 2 + i] = Color.blue(px[i]) / 255f
        }

        fun blurChannel(offset: Int): FloatArray {
            val ch_ = workBuf.copyOfRange(offset, offset + size)
            val tmp = FloatArray(size)
            for (y in 0 until ch) for (x in 0 until cw) {
                var s = 0f
                for (d in -r..r) s += ch_[y * cw + (x + d).coerceIn(0, cw - 1)]
                tmp[y * cw + x] = s / k
            }
            val out = FloatArray(size)
            for (y in 0 until ch) for (x in 0 until cw) {
                var s = 0f
                for (d in -r..r) s += tmp[(y + d).coerceIn(0, ch - 1) * cw + x]
                out[y * cw + x] = s / k
            }
            return out
        }

        val bR = blurChannel(0)
        val bG = blurChannel(size)
        val bB = blurChannel(size * 2)

        for (i in 0 until size) {
            if (mask[i] < 0.5f) continue
            val rF = workBuf[i]
            val gF = workBuf[size + i]
            val bF = workBuf[size * 2 + i]
            val nr = (rF + SHARPEN_AMOUNT * (rF - bR[i])).coerceIn(0f, 1f)
            val ng = (gF + SHARPEN_AMOUNT * (gF - bG[i])).coerceIn(0f, 1f)
            val nb = (bF + SHARPEN_AMOUNT * (bF - bB[i])).coerceIn(0f, 1f)
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
        val size = w * h
        // Đảm bảo buffer đủ lớn cho kích thước hiện tại
        if (pyramidMaxSize < size) {
            pyramidMaxSize = size
            pyramidWorkBuffer = FloatArray(size * 3) // R, G, B channels
        }
        val workBuf = pyramidWorkBuffer!!

        // Extract channels vào buffer tái sử dụng
        for (i in 0 until size) {
            workBuf[i] = Color.red(orig[i]) / 255f
            workBuf[size + i] = Color.green(orig[i]) / 255f
            workBuf[size * 2 + i] = Color.blue(orig[i]) / 255f
        }
        val origR = workBuf.copyOfRange(0, size)
        val origG = workBuf.copyOfRange(size, size * 2)
        val origB = workBuf.copyOfRange(size * 2, size * 3)

        for (i in 0 until size) {
            workBuf[i] = Color.red(inp[i]) / 255f
            workBuf[size + i] = Color.green(inp[i]) / 255f
            workBuf[size * 2 + i] = Color.blue(inp[i]) / 255f
        }
        val inpR = workBuf.copyOfRange(0, size)
        val inpG = workBuf.copyOfRange(size, size * 2)
        val inpB = workBuf.copyOfRange(size * 2, size * 3)

        val blR = lapBlendChannel(origR, inpR, mask, w, h, levels)
        val blG = lapBlendChannel(origG, inpG, mask, w, h, levels)
        val blB = lapBlendChannel(origB, inpB, mask, w, h, levels)

        return IntArray(size) { i ->
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

    // ── Inference (ONNX Runtime) ──────────────────────────────────────────

    /**
     * Run ONNX inference with the anime-manga-big-lama model.
     *
     * Model expects NCHW layout:
     *   "image" → [1, 3, H, W] float32, pixel values 0..1, masked regions zeroed
     *   "mask"  → [1, 1, H, W] float32, 1 = inpaint, 0 = keep
     *
     * Output "output" → [1, 3, H, W] float32, inpainted result 0..1
     */
    private fun runInference(env: OrtEnvironment, session: OrtSession, image: Bitmap, mask: Bitmap): Bitmap? {
        return try {
            val pixelCount = inputW * inputH

            // Tái sử dụng buffer thay vì cấp phát mới
            // Extract mask float values
            mask.getPixels(pixelBuffer, 0, inputW, 0, 0, inputW, inputH)
            for (i in 0 until pixelCount) {
                val p = pixelBuffer[i]
                tensorMaskBuffer[i] = if (Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) 1f else 0f
            }

            // Extract image pixels
            image.getPixels(pixelBuffer, 0, inputW, 0, 0, inputW, inputH)

            // Build image tensor in NCHW format: [1, 3, H, W]
            // Channel-first: all R values, then all G values, then all B values
            for (i in 0 until pixelCount) {
                val k = 1f - tensorMaskBuffer[i]  // zero out masked pixels
                val p = pixelBuffer[i]
                tensorImageBuffer[0 * pixelCount + i] = Color.red(p) / 255f * k   // R channel
                tensorImageBuffer[1 * pixelCount + i] = Color.green(p) / 255f * k // G channel
                tensorImageBuffer[2 * pixelCount + i] = Color.blue(p) / 255f * k  // B channel
            }

            // Tạo tensors từ FloatBuffer wrappers tái sử dụng
            // Reset position của buffer trước khi tạo tensor
            imageFloatBuffer.rewind()
            maskFloatBuffer.rewind()

            val imgTensor = OnnxTensor.createTensor(env, imageFloatBuffer, longArrayOf(1, 3, inputH.toLong(), inputW.toLong()))
            val maskTensor = OnnxTensor.createTensor(env, maskFloatBuffer, longArrayOf(1, 1, inputH.toLong(), inputW.toLong()))

            // Run inference with named inputs
            val inputs = mapOf("image" to imgTensor, "mask" to maskTensor)
            val results = session.run(inputs)

            // Extract output: NCHW [1, 3, H, W]
            val outputTensor = results[0] as OnnxTensor
            val outputData = outputTensor.floatBuffer
            val oShape = outputTensor.info.shape  // [1, 3, H, W]
            val oC = oShape[1].toInt()
            val oH = oShape[2].toInt()
            val oW = oShape[3].toInt()

            val bitmap = nchwFloatBufferToBitmap(outputData, oW, oH, oC)

            // Cleanup
            imgTensor.close()
            maskTensor.close()
            results.close()

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            null
        }
    }

    // ── NCHW Buffer → Bitmap ──────────────────────────────────────────────

    /**
     * Convert NCHW float buffer to ARGB Bitmap.
     * Layout: [C, H, W] — all R values first, then G, then B.
     */
    private fun nchwFloatBufferToBitmap(buf: FloatBuffer, w: Int, h: Int, ch: Int): Bitmap {
        buf.rewind()
        val pixelCount = w * h
        val totalFloats = ch * pixelCount
        val data = FloatArray(totalFloats)
        buf.get(data)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val r = (data[0 * pixelCount + i].coerceIn(0f, 1f) * 255).roundToInt()
            val g = if (ch >= 2) (data[1 * pixelCount + i].coerceIn(0f, 1f) * 255).roundToInt() else r
            val b = if (ch >= 3) (data[2 * pixelCount + i].coerceIn(0f, 1f) * 255).roundToInt() else r
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
            // Use full OCR bounds (NOT overlay-inset area) to mask original text region
            val ob = b.bounds
            if (ob.width() <= 0 || ob.height() <= 0) continue
            // Expand by 4% to ensure complete text removal
            val expandH = (ob.width() * 0.04f).toInt().coerceAtLeast(MASK_PADDING)
            val expandV = (ob.height() * 0.04f).toInt().coerceAtLeast(MASK_PADDING)
            val pr = Rect(
                (ob.left - expandH).coerceAtLeast(0), (ob.top - expandV).coerceAtLeast(0),
                (ob.right + expandH).coerceAtMost(w), (ob.bottom + expandV).coerceAtMost(h)
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

    /**
     * Chuyển bitmap sang grayscale tại chỗ, không tạo bản sao mới.
     * Giảm việc cấp phát bộ nhớ và sao chép pixel.
     */
    private fun toGrayInPlace(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            // Công thức luminance chuẩn: 0.299R + 0.587G + 0.114B
            val gray = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
            pixels[i] = Color.argb(Color.alpha(p), gray, gray, gray)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
