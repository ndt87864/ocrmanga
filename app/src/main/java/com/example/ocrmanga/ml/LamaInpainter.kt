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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LaMa-based inpainting engine using TensorFlow Lite.
 */
object LamaInpainter {

    private const val TAG = "LamaInpainter"
    private const val MODEL_PATH = "models/LaMa-Dilated_float.tflite"
    private const val ELEMENT_PADDING = 20
    private const val CONTEXT_PADDING = 100
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

    enum class ImageType { GRAYSCALE, COLOR }

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val modelFile = FileUtil.loadMappedFile(context, MODEL_PATH)
            val options = Interpreter.Options().apply { setNumThreads(4) }
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

            Log.i(TAG, "Resolved: input=${inputW}x${inputH}x${inputC}, maskC=$maskC, outC=$outputC, dynamic=$isDynamic, hasMask=$hasMaskInput")

            isInitialized = true
            Log.i(TAG, "LamaInpainter initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LamaInpainter", e)
            isInitialized = false
        }
    }

    fun isReady(): Boolean = isInitialized

    suspend fun inpaintBlocks(image: Bitmap, blocks: List<Rect>): Bitmap? =
        withContext(Dispatchers.Default) {
            if (!isInitialized || blocks.isEmpty()) return@withContext null
            try {
                val mask = createMaskFromBlocks(image.width, image.height, blocks)
                val result = inpaintInternal(image, mask)
                mask.recycle()
                result
            } catch (e: Exception) { Log.e(TAG, "inpaintBlocks failed", e); null }
        }

    suspend fun inpaintWithMask(image: Bitmap, mask: Bitmap): Bitmap? =
        withContext(Dispatchers.Default) {
            if (!isInitialized) return@withContext null
            try {
                val normalizedMask = normalizeMask(mask, image.width, image.height)
                val result = inpaintInternal(image, normalizedMask)
                normalizedMask.recycle()
                result
            } catch (e: Exception) { Log.e(TAG, "inpaintWithMask failed", e); null }
        }

    suspend fun inpaintPoints(image: Bitmap, points: List<Point>, radius: Int = 15): Bitmap? =
        withContext(Dispatchers.Default) {
            if (!isInitialized || points.isEmpty()) return@withContext null
            try {
                val mask = createMaskFromPoints(image.width, image.height, points, radius)
                val result = inpaintInternal(image, mask)
                mask.recycle()
                result
            } catch (e: Exception) { Log.e(TAG, "inpaintPoints failed", e); null }
        }

    fun release() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null; isInitialized = false
    }

    // ── Core pipeline ────────────────────────────────────────────────────

    private fun inpaintInternal(image: Bitmap, mask: Bitmap): Bitmap? {
        val interp = interpreter ?: return null
        val imageType = detectImageType(image)
        Log.d(TAG, "Image type: $imageType, size: ${image.width}x${image.height}")

        val workImage = if (imageType == ImageType.GRAYSCALE) toGrayscaleBitmap(image)
        else image.copy(Bitmap.Config.ARGB_8888, true)

        val regions = findMaskedRegions(mask)
        if (regions.isEmpty()) { workImage.recycle(); return image.copy(Bitmap.Config.ARGB_8888, true) }

        Log.d(TAG, "Processing ${regions.size} masked region(s)")
        val result = workImage.copy(Bitmap.Config.ARGB_8888, true)

        for ((idx, region) in regions.withIndex()) {
            try { processRegion(interp, workImage, mask, region, result, idx) }
            catch (e: Exception) { Log.w(TAG, "Region $idx failed: ${e.message}") }
        }
        workImage.recycle()

        if (imageType == ImageType.GRAYSCALE) {
            val g = toGrayscaleBitmap(result); result.recycle(); return g
        }
        return result
    }

    private fun processRegion(
        interp: Interpreter, srcImage: Bitmap, fullMask: Bitmap,
        region: Rect, resultBitmap: Bitmap, regionIdx: Int
    ) {
        val cropRect = Rect(
            (region.left - CONTEXT_PADDING).coerceAtLeast(0),
            (region.top - CONTEXT_PADDING).coerceAtLeast(0),
            (region.right + CONTEXT_PADDING).coerceAtMost(srcImage.width),
            (region.bottom + CONTEXT_PADDING).coerceAtMost(srcImage.height)
        )
        val cropW = cropRect.width(); val cropH = cropRect.height()
        if (cropW <= 0 || cropH <= 0) return

        val imageCrop = Bitmap.createBitmap(srcImage, cropRect.left, cropRect.top, cropW, cropH)
        val maskCrop = Bitmap.createBitmap(fullMask, cropRect.left, cropRect.top, cropW, cropH)

        // Resize preserving aspect ratio with padding
        val (paddedImage, padInfo) = resizeWithPadding(imageCrop, inputW, inputH)
        val (paddedMask, _) = resizeWithPadding(maskCrop, inputW, inputH)

        val outputBitmap = runInference(interp, paddedImage, paddedMask)
        paddedImage.recycle(); paddedMask.recycle()

        if (outputBitmap == null) {
            imageCrop.recycle(); maskCrop.recycle(); return
        }

        // Remove padding and resize back to crop dimensions
        val unpadded = removePadding(outputBitmap, padInfo)
        outputBitmap.recycle()
        val outputResized = Bitmap.createScaledBitmap(unpadded, cropW, cropH, true)
        unpadded.recycle()

        // Post-process: match brightness of inpainted area to border pixels
        val postProcessed = matchBorderBrightness(outputResized, imageCrop, maskCrop)
        outputResized.recycle()

        // Blend into result
        blendIntoResult(resultBitmap, postProcessed, maskCrop, cropRect)
        postProcessed.recycle(); imageCrop.recycle(); maskCrop.recycle()

        Log.d(TAG, "Region $regionIdx done: ${cropW}x${cropH}")
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
        canvas.drawColor(Color.BLACK) // pad with black
        canvas.drawBitmap(scaled, offsetX.toFloat(), offsetY.toFloat(), null)
        scaled.recycle()

        return Pair(padded, PadInfo(offsetX, offsetY, scaledW, scaledH))
    }

    private fun removePadding(src: Bitmap, padInfo: PadInfo): Bitmap {
        val x = padInfo.offsetX.coerceAtLeast(0)
        val y = padInfo.offsetY.coerceAtLeast(0)
        val w = padInfo.scaledW.coerceAtMost(src.width - x)
        val h = padInfo.scaledH.coerceAtMost(src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    // ── Inference ────────────────────────────────────────────────────────

    private fun runInference(interp: Interpreter, image: Bitmap, mask: Bitmap): Bitmap? {
        try {
            if (isDynamic) {
                interp.resizeInput(0, intArrayOf(1, inputH, inputW, inputC))
                if (hasMaskInput) interp.resizeInput(1, intArrayOf(1, inputH, inputW, maskC))
                interp.allocateTensors()
            }

            val outShape = interp.getOutputTensor(0).shape()
            val outH = outShape[1]; val outW = outShape[2]; val outC = outShape[3]

            // Build mask array
            val maskPixels = IntArray(inputW * inputH)
            mask.getPixels(maskPixels, 0, inputW, 0, 0, inputW, inputH)
            val maskFloat = FloatArray(inputW * inputH) { i ->
                val p = maskPixels[i]
                if (Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) 1.0f else 0.0f
            }

            // Image pixels
            val imgPixels = IntArray(inputW * inputH)
            image.getPixels(imgPixels, 0, inputW, 0, 0, inputW, inputH)

            // Image buffer: masked pixels zeroed out
            val imgBuf = ByteBuffer.allocateDirect(inputH * inputW * inputC * 4).order(ByteOrder.nativeOrder())
            for (i in imgPixels.indices) {
                val keep = 1.0f - maskFloat[i]
                imgBuf.putFloat(Color.red(imgPixels[i]) / 255.0f * keep)
                imgBuf.putFloat(Color.green(imgPixels[i]) / 255.0f * keep)
                imgBuf.putFloat(Color.blue(imgPixels[i]) / 255.0f * keep)
                for (c in 3 until inputC) imgBuf.putFloat(0f)
            }

            val outBuf = ByteBuffer.allocateDirect(outH * outW * outC * 4).order(ByteOrder.nativeOrder())

            if (hasMaskInput) {
                val maskBuf = ByteBuffer.allocateDirect(inputH * inputW * maskC * 4).order(ByteOrder.nativeOrder())
                for (v in maskFloat) { maskBuf.putFloat(v); for (c in 1 until maskC) maskBuf.putFloat(v) }
                interp.runForMultipleInputsOutputs(arrayOf(imgBuf, maskBuf), mapOf(0 to outBuf))
            } else {
                // Single input: concat RGB + mask as 4ch
                val cC = inputC + 1
                val combBuf = ByteBuffer.allocateDirect(inputH * inputW * cC * 4).order(ByteOrder.nativeOrder())
                for (i in imgPixels.indices) {
                    val keep = 1.0f - maskFloat[i]
                    combBuf.putFloat(Color.red(imgPixels[i]) / 255.0f * keep)
                    combBuf.putFloat(Color.green(imgPixels[i]) / 255.0f * keep)
                    combBuf.putFloat(Color.blue(imgPixels[i]) / 255.0f * keep)
                    combBuf.putFloat(maskFloat[i])
                }
                interp.resizeInput(0, intArrayOf(1, inputH, inputW, cC))
                interp.allocateTensors()
                interp.runForMultipleInputsOutputs(arrayOf(combBuf), mapOf(0 to outBuf))
            }

            return floatBufferToBitmap(outBuf, outW, outH, outC)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            return null
        }
    }

    // ── Post-processing: brightness matching ─────────────────────────────

    /**
     * Match the brightness of the inpainted area to the surrounding border pixels.
     * This fixes the gray-on-white problem where the model output is darker/lighter
     * than the actual background.
     */
    private fun matchBorderBrightness(
        inpainted: Bitmap, original: Bitmap, mask: Bitmap
    ): Bitmap {
        val w = inpainted.width; val h = inpainted.height
        val inpPixels = IntArray(w * h)
        inpainted.getPixels(inpPixels, 0, w, 0, 0, w, h)
        val origPixels = IntArray(w * h)
        original.getPixels(origPixels, 0, w, 0, 0, w, h)
        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)

        // Find border pixels: non-masked pixels adjacent to masked pixels
        var borderOrigSum = 0.0; var borderInpSum = 0.0; var borderCount = 0
        val borderRadius = 5
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val isMasked = Color.red(maskPixels[i]) > 10 || Color.green(maskPixels[i]) > 10 || Color.blue(maskPixels[i]) > 10
                if (isMasked) continue
                // Check if adjacent to a masked pixel
                var nearMask = false
                for (dy in -borderRadius..borderRadius) {
                    for (dx in -borderRadius..borderRadius) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val ni = ny * w + nx
                            val mp = maskPixels[ni]
                            if (Color.red(mp) > 10 || Color.green(mp) > 10 || Color.blue(mp) > 10) {
                                nearMask = true; break
                            }
                        }
                    }
                    if (nearMask) break
                }
                if (nearMask) {
                    val origBright = (Color.red(origPixels[i]) + Color.green(origPixels[i]) + Color.blue(origPixels[i])) / 3.0
                    val inpBright = (Color.red(inpPixels[i]) + Color.green(inpPixels[i]) + Color.blue(inpPixels[i])) / 3.0
                    borderOrigSum += origBright; borderInpSum += inpBright; borderCount++
                }
            }
        }

        if (borderCount < 5) {
            // Not enough border pixels, return as-is
            return inpainted.copy(Bitmap.Config.ARGB_8888, true)
        }

        val avgOrigBright = borderOrigSum / borderCount
        val avgInpBright = borderInpSum / borderCount
        val brightnessDiff = avgOrigBright - avgInpBright

        Log.d(TAG, "Brightness match: orig=${"%.1f".format(avgOrigBright)}, inp=${"%.1f".format(avgInpBright)}, diff=${"%.1f".format(brightnessDiff)}")

        if (abs(brightnessDiff) < 5.0) {
            // Difference is negligible
            return inpainted.copy(Bitmap.Config.ARGB_8888, true)
        }

        // Apply brightness correction to masked pixels only
        val result = inpainted.copy(Bitmap.Config.ARGB_8888, true)
        val resultPixels = IntArray(w * h)
        result.getPixels(resultPixels, 0, w, 0, 0, w, h)

        val shift = brightnessDiff.roundToInt()
        for (i in 0 until w * h) {
            val mp = maskPixels[i]
            val isMasked = Color.red(mp) > 10 || Color.green(mp) > 10 || Color.blue(mp) > 10
            if (isMasked) {
                val r = (Color.red(resultPixels[i]) + shift).coerceIn(0, 255)
                val g = (Color.green(resultPixels[i]) + shift).coerceIn(0, 255)
                val b = (Color.blue(resultPixels[i]) + shift).coerceIn(0, 255)
                resultPixels[i] = Color.argb(255, r, g, b)
            }
        }
        result.setPixels(resultPixels, 0, w, 0, 0, w, h)
        return result
    }

    // ── Blending ─────────────────────────────────────────────────────────

    private fun blendIntoResult(
        result: Bitmap, inpainted: Bitmap, maskCrop: Bitmap, cropRect: Rect
    ) {
        val w = inpainted.width; val h = inpainted.height
        val feathered = buildFeatheredMask(maskCrop)

        val inpPixels = IntArray(w * h)
        inpainted.getPixels(inpPixels, 0, w, 0, 0, w, h)
        val resultPixels = IntArray(w * h)
        result.getPixels(resultPixels, 0, w, cropRect.left, cropRect.top, w, h)

        for (i in 0 until w * h) {
            val alpha = feathered[i]
            if (alpha > 0.001f) {
                val r = (Color.red(resultPixels[i]) * (1f - alpha) + Color.red(inpPixels[i]) * alpha).roundToInt().coerceIn(0, 255)
                val g = (Color.green(resultPixels[i]) * (1f - alpha) + Color.green(inpPixels[i]) * alpha).roundToInt().coerceIn(0, 255)
                val b = (Color.blue(resultPixels[i]) * (1f - alpha) + Color.blue(inpPixels[i]) * alpha).roundToInt().coerceIn(0, 255)
                resultPixels[i] = Color.argb(255, r, g, b)
            }
        }
        result.setPixels(resultPixels, 0, w, cropRect.left, cropRect.top, w, h)
    }

    private fun buildFeatheredMask(maskBitmap: Bitmap): FloatArray {
        val w = maskBitmap.width; val h = maskBitmap.height
        val pixels = IntArray(w * h)
        maskBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val binary = FloatArray(w * h) { i ->
            val p = pixels[i]
            if (Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) 1.0f else 0.0f
        }
        return boxBlur(binary, w, h, MASK_FEATHER_RADIUS)
    }

    private fun boxBlur(input: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var cur = input.copyOf(); var nxt = FloatArray(w * h)
        repeat(3) {
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var c = 0
                for (dx in -radius..radius) { val nx = x + dx; if (nx in 0 until w) { s += cur[y * w + nx]; c++ } }
                nxt[y * w + x] = s / c
            }
            cur = nxt.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var c = 0
                for (dy in -radius..radius) { val ny = y + dy; if (ny in 0 until h) { s += cur[ny * w + x]; c++ } }
                nxt[y * w + x] = s / c
            }
            cur = nxt.copyOf()
        }
        return cur
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

    // ── Region finding ───────────────────────────────────────────────────

    private fun findMaskedRegions(mask: Bitmap): List<Rect> {
        val px = IntArray(mask.width * mask.height)
        mask.getPixels(px, 0, mask.width, 0, 0, mask.width, mask.height)
        var gMinX = mask.width; var gMaxX = 0; var gMinY = mask.height; var gMaxY = 0; var has = false
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            val p = px[y * mask.width + x]
            if (Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) {
                gMinX = min(gMinX, x); gMaxX = max(gMaxX, x); gMinY = min(gMinY, y); gMaxY = max(gMaxY, y); has = true
            }
        }
        if (!has) return emptyList()

        val ts = min(inputW, inputH)
        val rw = gMaxX - gMinX + 1; val rh = gMaxY - gMinY + 1
        val tw = ts.coerceAtMost(rw); val th = ts.coerceAtMost(rh)
        val nx = (rw + tw - 1) / tw; val ny = (rh + th - 1) / th

        val result = mutableListOf<Rect>()
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val l = gMinX + tx * tw; val t = gMinY + ty * th
            val r = (l + tw).coerceAtMost(mask.width); val b = (t + th).coerceAtMost(mask.height)
            var found = false
            outer@ for (y in t until b) for (x in l until r) {
                val p = px[y * mask.width + x]
                if (Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) { found = true; break@outer }
            }
            if (found) result.add(Rect(l, t, r, b))
        }
        return result
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
