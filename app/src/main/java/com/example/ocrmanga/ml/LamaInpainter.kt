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
 * Single whole-image inference for speed, multiplicative per-channel
 * color correction for quality, progress callbacks for UI feedback.
 */
object LamaInpainter {

    private const val TAG = "LamaInpainter"
    private const val MODEL_PATH = "models/LaMa-Dilated_float.tflite"
    private const val ELEMENT_PADDING = 20
    private const val MASK_FEATHER_RADIUS = 10

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    private var inputH = 0
    private var inputW = 0
    private var inputC = 0
    private var maskC = 0
    private var outputC = 0
    private var hasMaskInput = false
    private var isDynamic = false

    // Reusable buffers (allocated once, reused every inference)
    private var imgBuf: ByteBuffer? = null
    private var maskBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null
    private var combBuf: ByteBuffer? = null

    enum class ImageType { GRAYSCALE, COLOR }
    private data class ColorScale(val r: Float, val g: Float, val b: Float)

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val modelFile = FileUtil.loadMappedFile(context, MODEL_PATH)
            val options = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
                // NNAPI disabled: produces corrupted output on some chipsets (e.g. SD865)
                // CPU multi-threaded inference is reliable across all devices
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
        val imgSize = inputH * inputW * inputC * 4
        imgBuf = ByteBuffer.allocateDirect(imgSize).order(ByteOrder.nativeOrder())
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
        image: Bitmap, blocks: List<Rect>,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized || blocks.isEmpty()) return@withContext null
        try {
            onProgress?.invoke("Lấy dữ liệu...")
            val mask = createMaskFromBlocks(image.width, image.height, blocks)
            val result = inpaintWholeImage(image, mask, onProgress)
            mask.recycle()
            result
        } catch (e: Exception) { Log.e(TAG, "inpaintBlocks failed", e); null }
    }

    suspend fun inpaintWithMask(
        image: Bitmap, mask: Bitmap,
        onProgress: ((String) -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext null
        try {
            onProgress?.invoke("Lấy dữ liệu...")
            val normalizedMask = normalizeMask(mask, image.width, image.height)
            val result = inpaintWholeImage(image, normalizedMask, onProgress)
            normalizedMask.recycle()
            result
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
            val result = inpaintWholeImage(image, mask, onProgress)
            mask.recycle()
            result
        } catch (e: Exception) { Log.e(TAG, "inpaintPoints failed", e); null }
    }

    fun release() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null; isInitialized = false
        imgBuf = null; maskBuf = null; outBuf = null; combBuf = null
    }

    // ── Single-pass whole-image inpainting ────────────────────────────────

    private fun inpaintWholeImage(
        image: Bitmap, mask: Bitmap,
        onProgress: ((String) -> Unit)?
    ): Bitmap? {
        val interp = interpreter ?: return null
        val startTime = System.currentTimeMillis()

        val imageType = detectImageType(image)
        val origW = image.width; val origH = image.height
        Log.d(TAG, "Whole-image inpaint: ${origW}x${origH}, type=$imageType")

        val workImage = if (imageType == ImageType.GRAYSCALE) toGrayscaleBitmap(image)
        else image.copy(Bitmap.Config.ARGB_8888, true)

        // Resize entire image + mask to model input size (preserving aspect ratio)
        val (resizedImg, padInfo) = resizeWithPadding(workImage, inputW, inputH)
        val (resizedMask, _) = resizeWithPadding(mask, inputW, inputH)

        onProgress?.invoke("Xóa điểm ảnh...")
        Log.d(TAG, "Running inference ${inputW}x${inputH}...")
        val inferenceStart = System.currentTimeMillis()

        val outputBitmap = runInference(interp, resizedImg, resizedMask)
        resizedImg.recycle(); resizedMask.recycle()

        Log.d(TAG, "Inference took ${System.currentTimeMillis() - inferenceStart}ms")

        if (outputBitmap == null) { workImage.recycle(); return null }

        // Remove padding and resize back to original dimensions
        val unpadded = removePadding(outputBitmap, padInfo)
        outputBitmap.recycle()
        val fullOutput = Bitmap.createScaledBitmap(unpadded, origW, origH, true)
        unpadded.recycle()

        // Multiplicative color correction + feathered blending
        onProgress?.invoke("Bù điểm ảnh 0%...")
        val result = blendWithColorCorrection(workImage, fullOutput, mask, onProgress)
        fullOutput.recycle(); workImage.recycle()

        if (imageType == ImageType.GRAYSCALE) {
            val g = toGrayscaleBitmap(result); result.recycle()
            Log.d(TAG, "Total time: ${System.currentTimeMillis() - startTime}ms")
            return g
        }

        Log.d(TAG, "Total time: ${System.currentTimeMillis() - startTime}ms")
        return result
    }

    // ── Blending with multiplicative color correction ─────────────────────

    /**
     * Blend inpainted output into original using multiplicative per-channel
     * color correction. This correctly handles white areas:
     * if model outputs 90% brightness (230), and border is 255,
     * scale = 255/230 = 1.11 → 230 * 1.11 = 255.
     */
    private fun blendWithColorCorrection(
        original: Bitmap, inpainted: Bitmap, mask: Bitmap,
        onProgress: ((String) -> Unit)?
    ): Bitmap {
        val w = original.width; val h = original.height
        val origPx = IntArray(w * h)
        original.getPixels(origPx, 0, w, 0, 0, w, h)
        val inpPx = IntArray(w * h)
        inpainted.getPixels(inpPx, 0, w, 0, 0, w, h)
        val maskPx = IntArray(w * h)
        mask.getPixels(maskPx, 0, w, 0, 0, w, h)

        // Build binary mask
        val binary = FloatArray(w * h) { i ->
            if (Color.red(maskPx[i]) > 10 || Color.green(maskPx[i]) > 10 || Color.blue(maskPx[i]) > 10) 1.0f else 0.0f
        }

        onProgress?.invoke("Bù điểm ảnh 20%...")

        // Feathered mask for smooth blending
        val feathered = fastBoxBlur(binary, w, h, MASK_FEATHER_RADIUS)

        onProgress?.invoke("Bù điểm ảnh 40%...")

        // Compute multiplicative per-channel correction from border pixels
        val scale = computeColorScale(origPx, inpPx, binary, w, h)
        Log.d(TAG, "Color scale: R=${"%.3f".format(scale.r)}, G=${"%.3f".format(scale.g)}, B=${"%.3f".format(scale.b)}")

        onProgress?.invoke("Bù điểm ảnh 60%...")

        // Blend with correction
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val resultPx = IntArray(w * h)
        for (i in 0 until w * h) {
            val alpha = feathered[i]
            if (alpha > 0.001f) {
                // Apply multiplicative correction to inpainted pixel
                val ir = (Color.red(inpPx[i]) * scale.r).roundToInt().coerceIn(0, 255)
                val ig = (Color.green(inpPx[i]) * scale.g).roundToInt().coerceIn(0, 255)
                val ib = (Color.blue(inpPx[i]) * scale.b).roundToInt().coerceIn(0, 255)
                // Feathered blend with original
                val r = (Color.red(origPx[i]) * (1f - alpha) + ir * alpha).roundToInt().coerceIn(0, 255)
                val g = (Color.green(origPx[i]) * (1f - alpha) + ig * alpha).roundToInt().coerceIn(0, 255)
                val b = (Color.blue(origPx[i]) * (1f - alpha) + ib * alpha).roundToInt().coerceIn(0, 255)
                resultPx[i] = Color.argb(255, r, g, b)
            } else {
                resultPx[i] = origPx[i]
            }
        }

        onProgress?.invoke("Bù điểm ảnh 100%...")

        result.setPixels(resultPx, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Compute per-channel multiplicative scale factors from border pixels.
     * Border = non-masked pixels adjacent to masked ones (within 3px).
     *
     * Example: if border avg is 252 and inpainted avg is 230,
     * scale = 252/230 = 1.096 → correctly maps 230 → 252.
     */
    private fun computeColorScale(
        origPx: IntArray, inpPx: IntArray, mask: FloatArray, w: Int, h: Int
    ): ColorScale {
        var origR = 0.0; var origG = 0.0; var origB = 0.0
        var inpR = 0.0; var inpG = 0.0; var inpB = 0.0
        var count = 0

        val step = if (w * h < 60000) 1 else 2

        for (y in 3 until h - 3 step step) {
            for (x in 3 until w - 3 step step) {
                val i = y * w + x
                if (mask[i] > 0.5f) continue

                // Check if any pixel within 3px is masked
                var hasNearbyMask = false
                outer@ for (dy in -3..3) {
                    for (dx in -3..3) {
                        val ni = (y + dy) * w + (x + dx)
                        if (ni in mask.indices && mask[ni] > 0.5f) {
                            hasNearbyMask = true; break@outer
                        }
                    }
                }
                if (hasNearbyMask) {
                    origR += Color.red(origPx[i])
                    origG += Color.green(origPx[i])
                    origB += Color.blue(origPx[i])
                    inpR += Color.red(inpPx[i])
                    inpG += Color.green(inpPx[i])
                    inpB += Color.blue(inpPx[i])
                    count++
                }
            }
        }

        if (count < 5) return ColorScale(1f, 1f, 1f)

        val avgOrigR = origR / count
        val avgOrigG = origG / count
        val avgOrigB = origB / count
        val avgInpR = inpR / count
        val avgInpG = inpG / count
        val avgInpB = inpB / count

        return ColorScale(
            r = if (avgInpR > 5) (avgOrigR / avgInpR).toFloat().coerceIn(0.7f, 1.5f) else 1f,
            g = if (avgInpG > 5) (avgOrigG / avgInpG).toFloat().coerceIn(0.7f, 1.5f) else 1f,
            b = if (avgInpB > 5) (avgOrigB / avgInpB).toFloat().coerceIn(0.7f, 1.5f) else 1f
        )
    }

    // ── Aspect-ratio-preserving resize ───────────────────────────────────

    data class PadInfo(val offsetX: Int, val offsetY: Int, val scaledW: Int, val scaledH: Int)

    private fun resizeWithPadding(src: Bitmap, targetW: Int, targetH: Int): Pair<Bitmap, PadInfo> {
        val scale = min(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val scaledW = (src.width * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).roundToInt().coerceAtLeast(1)
        val offsetX = (targetW - scaledW) / 2
        val offsetY = (targetH - scaledH) / 2

        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val padded = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, offsetX.toFloat(), offsetY.toFloat(), null)
        scaled.recycle()
        return Pair(padded, PadInfo(offsetX, offsetY, scaledW, scaledH))
    }

    private fun removePadding(src: Bitmap, info: PadInfo): Bitmap {
        return Bitmap.createBitmap(src,
            info.offsetX.coerceAtLeast(0), info.offsetY.coerceAtLeast(0),
            info.scaledW.coerceAtMost(src.width - info.offsetX.coerceAtLeast(0)),
            info.scaledH.coerceAtMost(src.height - info.offsetY.coerceAtLeast(0)))
    }

    // ── Inference (with reusable buffers) ─────────────────────────────────

    private fun runInference(interp: Interpreter, image: Bitmap, mask: Bitmap): Bitmap? {
        try {
            if (isDynamic) {
                interp.resizeInput(0, intArrayOf(1, inputH, inputW, inputC))
                if (hasMaskInput) interp.resizeInput(1, intArrayOf(1, inputH, inputW, maskC))
                interp.allocateTensors()
            }

            val outShape = interp.getOutputTensor(0).shape()
            val outH = outShape[1]; val outW = outShape[2]; val outC = outShape[3]

            // Build mask float array
            val maskPixels = IntArray(inputW * inputH)
            mask.getPixels(maskPixels, 0, inputW, 0, 0, inputW, inputH)
            val maskFloat = FloatArray(inputW * inputH) { i ->
                if (Color.red(maskPixels[i]) > 10 || Color.green(maskPixels[i]) > 10 || Color.blue(maskPixels[i]) > 10) 1.0f else 0.0f
            }

            // Image pixels
            val imgPixels = IntArray(inputW * inputH)
            image.getPixels(imgPixels, 0, inputW, 0, 0, inputW, inputH)

            // Fill reusable image buffer (masked pixels zeroed per LaMa convention)
            val ib = imgBuf!!; ib.rewind()
            for (i in imgPixels.indices) {
                val keep = 1.0f - maskFloat[i]
                ib.putFloat(Color.red(imgPixels[i]) / 255.0f * keep)
                ib.putFloat(Color.green(imgPixels[i]) / 255.0f * keep)
                ib.putFloat(Color.blue(imgPixels[i]) / 255.0f * keep)
                for (c in 3 until inputC) ib.putFloat(0f)
            }

            // Ensure output buffer matches actual output shape
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
                    val keep = 1.0f - maskFloat[i]
                    cb.putFloat(Color.red(imgPixels[i]) / 255.0f * keep)
                    cb.putFloat(Color.green(imgPixels[i]) / 255.0f * keep)
                    cb.putFloat(Color.blue(imgPixels[i]) / 255.0f * keep)
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

    // ── Fast box blur using prefix sums: O(n) instead of O(n*radius) ─────

    private fun fastBoxBlur(input: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var cur = input.copyOf()
        repeat(2) {
            cur = horizontalBlur(cur, w, h, radius)
            cur = verticalBlur(cur, w, h, radius)
        }
        return cur
    }

    private fun horizontalBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val dst = FloatArray(w * h)
        for (y in 0 until h) {
            var sum = 0f
            val rowOff = y * w
            for (dx in 0..min(r, w - 1)) sum += src[rowOff + dx]
            for (x in 0 until w) {
                val left = x - r - 1
                val right = x + r
                if (right < w) sum += src[rowOff + right]
                if (left >= 0) sum -= src[rowOff + left]
                val count = min(x + r, w - 1) - max(x - r, 0) + 1
                dst[rowOff + x] = sum / count
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
                val top = y - r - 1
                val bot = y + r
                if (bot < h) sum += src[bot * w + x]
                if (top >= 0) sum -= src[top * w + x]
                val count = min(y + r, h - 1) - max(y - r, 0) + 1
                dst[y * w + x] = sum / count
            }
        }
        return dst
    }

    // ── Buffer conversion ────────────────────────────────────────────────

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

    private fun createMaskFromBlocks(w: Int, h: Int, blocks: List<Rect>): Bitmap {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask); canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        for (b in blocks) {
            canvas.drawRect(Rect(
                (b.left - ELEMENT_PADDING).coerceAtLeast(0), (b.top - ELEMENT_PADDING).coerceAtLeast(0),
                (b.right + ELEMENT_PADDING).coerceAtMost(w), (b.bottom + ELEMENT_PADDING).coerceAtMost(h)
            ), paint)
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
