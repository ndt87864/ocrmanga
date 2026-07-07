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

internal suspend fun LamaInpainter.inpaintWithMaskInternal(
    image: Bitmap, mask: Bitmap, onProgress: ((String) -> Unit)?
): Bitmap? {
    val t0 = System.currentTimeMillis()
    val work = image.copy(Bitmap.Config.ARGB_8888, true)
    val normMask = normalizeMask(mask, image.width, image.height)
    
    // Tìm các vùng bôi vẽ riêng biệt (không giao nhau)
    val separateBounds = findSeparateMaskBounds(normMask)
    if (separateBounds.isEmpty()) {
        normMask.recycle()
        work.recycle()
        return null
    }
    
    // Phân cụm an toàn các vùng bôi vẽ dựa trên khoảng cách và giới hạn kích thước vùng
    val clusters = createSafeClusters(separateBounds, image.width, image.height)
    
    val result = mutex.withLock { processRegionClusters(work, normMask, clusters, onProgress) }
    normMask.recycle()
    //Log.d(TAG, "inpaintWithMask done: ${System.currentTimeMillis() - t0}ms")
    return result
}

internal fun LamaInpainter.padBlock(b: LamaInpainter.InpaintBlock, imgW: Int, imgH: Int): Rect {
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

internal fun LamaInpainter.createSafeClusters(blocks: List<Rect>, imgW: Int, imgH: Int): List<List<Rect>> {
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

internal fun LamaInpainter.expandForContext(bounds: Rect, imgW: Int, imgH: Int): Rect {
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
internal fun LamaInpainter.analyzeBackgroundComplexity(image: Bitmap, region: Rect, mask: Bitmap): Float {
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

internal fun LamaInpainter.computeVariance(values: List<Int>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    return values.map { (it - mean) * (it - mean) }.average()
}

internal fun LamaInpainter.countEdges(pixels: IntArray, w: Int, h: Int, mask: IntArray): Int {
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

internal fun LamaInpainter.processRegionClusters(
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
internal fun LamaInpainter.blendCropIntoResult(
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
internal fun LamaInpainter.sharpenMaskedRegion(result: Bitmap, mask: FloatArray, cw: Int, ch: Int, cropRect: Rect) {
    if (SHARPEN_AMOUNT <= 0f) return
    val size = cw * ch

    // Tái sử dụng buffer nếu đủ lớn
    if (pyramidMaxSize < size) {
        pyramidMaxSize = size
        pyramidWorkBuffer = FloatArray(size * 3)
    }
    val workBuf = pyramidWorkBuffer ?: return

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

