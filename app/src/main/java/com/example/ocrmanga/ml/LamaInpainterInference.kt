package com.example.ocrmanga.ml

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
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.withLock

internal data class PadInfo(val offsetX: Int, val offsetY: Int, val scaledW: Int, val scaledH: Int)

internal fun LamaInpainter.resizeWithPadding(src: Bitmap, tW: Int, tH: Int): Pair<Bitmap, PadInfo> {
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

internal fun LamaInpainter.removePadding(src: Bitmap, info: PadInfo): Bitmap =
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
internal fun LamaInpainter.runInference(env: OrtEnvironment, session: OrtSession, image: Bitmap, mask: Bitmap): Bitmap? {
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
internal fun LamaInpainter.nchwFloatBufferToBitmap(buf: FloatBuffer, w: Int, h: Int, ch: Int): Bitmap {
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

internal fun LamaInpainter.createMaskFromBlocks(w: Int, h: Int, blocks: List<LamaInpainter.InpaintBlock>): Bitmap {
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

internal fun LamaInpainter.createMaskFromPoints(w: Int, h: Int, points: List<Point>, radius: Int): Bitmap {
    val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(mask); cv.drawColor(Color.BLACK)
    val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    for (p in points) cv.drawCircle(p.x.toFloat(), p.y.toFloat(), radius.toFloat(), paint)
    return mask
}

internal fun LamaInpainter.normalizeMask(mask: Bitmap, tw: Int, th: Int): Bitmap {
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
internal fun LamaInpainter.dilateMask(src: Bitmap, radius: Int): Bitmap {
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
internal fun LamaInpainter.findBestPatch(
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

internal fun LamaInpainter.detectImageType(bmp: Bitmap): LamaInpainter.ImageType {
    val sx = max(1, bmp.width / 15); val sy = max(1, bmp.height / 15)
    var sat = 0f; var n = 0; val hsv = FloatArray(3)
    for (y in 0 until bmp.height step sy) {
        for (x in 0 until bmp.width step sx) {
            Color.colorToHSV(bmp.getPixel(x, y), hsv); sat += hsv[1]; n++; if (n >= 200) break
        }
        if (n >= 200) break
    }
    return if (n > 0 && sat / n * 100f < 10f) LamaInpainter.ImageType.GRAYSCALE else LamaInpainter.ImageType.COLOR
}

internal fun LamaInpainter.toGray(src: Bitmap): Bitmap {
    val r = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(r); val p = Paint()
    p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    c.drawBitmap(src, 0f, 0f, p); return r
}

/**
 * Chuyển bitmap sang grayscale tại chỗ, không tạo bản sao mới.
 * Giảm việc cấp phát bộ nhớ và sao chép pixel.
 */
internal fun LamaInpainter.toGrayInPlace(bitmap: Bitmap) {
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
