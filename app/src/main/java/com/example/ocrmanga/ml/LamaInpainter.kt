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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LaMa-based inpainting engine using TensorFlow Lite.
 *
 * Color correction v4 (additive border correction):
 * - Measure mean(orig) - mean(lama) per channel in a border ring around the mask
 * - Apply as additive offset, clamped to ±MAX_COLOR_OFFSET
 * - Additive is safer than multiplicative: cannot amplify channel bias
 * - Feathered mask edge (smoothstep, FEATHER_HALF px)
 */
object LamaInpainter {

    private const val TAG = "LamaInpainter"
    private const val MODEL_PATH = "models/LaMa-Dilated_float.tflite"
    private const val MASK_PADDING = 10
    private const val CONTEXT_PADDING = 128

    /** Maximum additive correction per channel (0-255 scale). Keep small to avoid artefacts. */
    private const val MAX_COLOR_OFFSET = 18f

    /** Ring of background pixels sampled for correction: RING_NEAR..RING_FAR px from mask. */
    private const val RING_NEAR = 5
    private const val RING_FAR  = 20

    /** Feather transition width at mask boundary (pixels). */
    private const val FEATHER_HALF = 4

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
            val ci = Bitmap.createBitmap(result, crop.left, crop.top, crop.width(), crop.height())
            val cm = Bitmap.createBitmap(mask,   crop.left, crop.top, crop.width(), crop.height())
            val (ri, pi) = resizeWithPadding(ci, inputW, inputH)
            val (rm, _)  = resizeWithPadding(cm, inputW, inputH)
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

    // ── Additive border-correction blend ─────────────────────────────────

    /**
     * Blend LaMa output into result.
     *
     * Colour correction: additive, NOT multiplicative.
     *   correction_R = mean(orig_R in border ring) - mean(lama_R in border ring)
     *   corrected_R  = lama_R + correction_R
     *
     * Why additive:
     * - Directly measures LaMa's colour drift; cannot amplify a channel bias
     * - Safe clamp ±MAX_COLOR_OFFSET → blue/green tint impossible
     * - Works on gradients because the border ring is spatially close to the mask
     *
     * Feathered mask edge (smoothstep) eliminates hard paste boundary.
     */
    private fun blendCropIntoResult(
        result: Bitmap, crop: Bitmap, fullMask: Bitmap, cropRect: Rect
    ) {
        val cw = cropRect.width(); val ch = cropRect.height()
        val resPx  = IntArray(cw * ch); result.getPixels(resPx,  0, cw, cropRect.left, cropRect.top, cw, ch)
        val cropPx = IntArray(cw * ch); crop.getPixels(cropPx, 0, cw, 0, 0, cw, ch)
        val mskPx  = IntArray(cw * ch); fullMask.getPixels(mskPx, 0, cw, cropRect.left, cropRect.top, cw, ch)

        val binary = FloatArray(cw * ch) { i ->
            if (Color.red(mskPx[i]) > 10 || Color.green(mskPx[i]) > 10 || Color.blue(mskPx[i]) > 10) 1f else 0f
        }
        val feather = buildFeatheredMask(binary, cw, ch)

        // Measure LaMa colour drift in border ring, correct additively
        val (dR, dG, dB) = computeBorderCorrection(resPx, cropPx, binary, cw, ch)
        Log.d(TAG, "Border additive correction: dR=${dR.roundToInt()} dG=${dG.roundToInt()} dB=${dB.roundToInt()}")

        for (i in 0 until cw * ch) {
            val fa = feather[i]; if (fa < 0.01f) continue
            // Apply additive colour correction to LaMa pixel
            val cr = (Color.red(cropPx[i])   + dR).roundToInt().coerceIn(0, 255)
            val cg = (Color.green(cropPx[i]) + dG).roundToInt().coerceIn(0, 255)
            val cb = (Color.blue(cropPx[i])  + dB).roundToInt().coerceIn(0, 255)
            // Alpha-composite with original (feathered at boundary)
            val oR = Color.red(resPx[i]); val oG = Color.green(resPx[i]); val oB = Color.blue(resPx[i])
            resPx[i] = Color.argb(255,
                (cr * fa + oR * (1f - fa)).roundToInt().coerceIn(0, 255),
                (cg * fa + oG * (1f - fa)).roundToInt().coerceIn(0, 255),
                (cb * fa + oB * (1f - fa)).roundToInt().coerceIn(0, 255)
            )
        }
        result.setPixels(resPx, 0, cw, cropRect.left, cropRect.top, cw, ch)
    }

    /**
     * Compute mean per-channel additive correction from border ring (RING_NEAR..RING_FAR px
     * outside the mask). Returns Triple(dR, dG, dB) clamped to ±MAX_COLOR_OFFSET.
     * Falls back to (0,0,0) if fewer than 5 border pixels found.
     */
    private fun computeBorderCorrection(
        origPx: IntArray, inpPx: IntArray, mask: FloatArray, w: Int, h: Int
    ): Triple<Float, Float, Float> {
        var sumDR = 0.0; var sumDG = 0.0; var sumDB = 0.0; var n = 0
        val step = if (w * h < 80_000) 1 else 2

        for (y in RING_FAR until h - RING_FAR step step) {
            for (x in RING_FAR until w - RING_FAR step step) {
                val i = y * w + x
                if (mask[i] > 0.5f) continue   // skip masked pixels

                // Find nearest mask pixel distance (Manhattan)
                var nearDist = Int.MAX_VALUE
                var tooClose = false
                outer@ for (dy in -RING_FAR..RING_FAR step 2) {
                    for (dx in -RING_FAR..RING_FAR step 2) {
                        val ni = (y + dy) * w + (x + dx)
                        if (ni in mask.indices && mask[ni] > 0.5f) {
                            val d = max(abs(dx), abs(dy))
                            if (d < RING_NEAR) { tooClose = true; break@outer }
                            if (d < nearDist) nearDist = d
                        }
                    }
                }
                if (tooClose || nearDist == Int.MAX_VALUE) continue

                // diff = original - lama (positive → lama too dark, negative → lama too bright)
                sumDR += Color.red(origPx[i])   - Color.red(inpPx[i])
                sumDG += Color.green(origPx[i]) - Color.green(inpPx[i])
                sumDB += Color.blue(origPx[i])  - Color.blue(inpPx[i])
                n++
            }
        }

        if (n < 5) return Triple(0f, 0f, 0f)
        return Triple(
            (sumDR / n).toFloat().coerceIn(-MAX_COLOR_OFFSET, MAX_COLOR_OFFSET),
            (sumDG / n).toFloat().coerceIn(-MAX_COLOR_OFFSET, MAX_COLOR_OFFSET),
            (sumDB / n).toFloat().coerceIn(-MAX_COLOR_OFFSET, MAX_COLOR_OFFSET)
        )
    }

    // ── Feathered mask ────────────────────────────────────────────────────

    private fun buildFeatheredMask(binary: FloatArray, w: Int, h: Int): FloatArray {
        val dist = distFromBorder(binary, w, h)
        return FloatArray(w * h) { i ->
            if (binary[i] < 0.5f) 0f else {
                val t = (dist[i] / FEATHER_HALF).coerceIn(0f, 1f)
                t * t * (3f - 2f * t)   // smoothstep
            }
        }
    }

    private fun distFromBorder(binary: FloatArray, w: Int, h: Int): FloatArray {
        val INF = 99999f
        val dist = FloatArray(w * h) { if (binary[it] > 0.5f) INF else 0f }
        // Mark border pixels
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; if (binary[i] < 0.5f) continue
            val border = (x == 0 || binary[i - 1] < 0.5f) || (x == w - 1 || binary[i + 1] < 0.5f) ||
                         (y == 0 || binary[i - w] < 0.5f) || (y == h - 1 || binary[i + w] < 0.5f)
            if (border) dist[i] = 0f
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; if (dist[i] == 0f) continue
            if (y > 0) dist[i] = min(dist[i], dist[(y - 1) * w + x] + 1f)
            if (x > 0) dist[i] = min(dist[i], dist[i - 1] + 1f)
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x; if (dist[i] == 0f) continue
            if (y < h - 1) dist[i] = min(dist[i], dist[(y + 1) * w + x] + 1f)
            if (x < w - 1) dist[i] = min(dist[i], dist[i + 1] + 1f)
        }
        return dist
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
        return mask
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
        sc.setPixels(px, 0, sc.width, 0, 0, sc.width, sc.height); return sc
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
