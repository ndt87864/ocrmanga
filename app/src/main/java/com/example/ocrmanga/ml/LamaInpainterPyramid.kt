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

internal data class PyramidLevel(val data: FloatArray, val w: Int, val h: Int)

internal fun LamaInpainter.laplacianPyramidBlend(
    orig: IntArray, inp: IntArray, mask: FloatArray, w: Int, h: Int, levels: Int = 6
): IntArray {
    val size = w * h
    // Đảm bảo buffer đủ lớn cho kích thước hiện tại
    if (pyramidMaxSize < size) {
        pyramidMaxSize = size
        pyramidWorkBuffer = FloatArray(size * 3) // R, G, B channels
    }
    val workBuf = pyramidWorkBuffer ?: return intArrayOf()

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

internal fun LamaInpainter.lapBlendChannel(
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

internal fun LamaInpainter.buildGaussianPyramid(src: FloatArray, w: Int, h: Int, levels: Int): Array<PyramidLevel> {
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

internal fun LamaInpainter.buildLaplacianPyramid(gauss: Array<PyramidLevel>, levels: Int): Array<PyramidLevel> {
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

internal fun LamaInpainter.reconstructLaplacian(lap: Array<PyramidLevel>, levels: Int): FloatArray {
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
internal fun LamaInpainter.gauss5Sep(src: FloatArray, w: Int, h: Int): FloatArray {
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
internal fun LamaInpainter.pyrUp(src: FloatArray, sw: Int, sh: Int, tw: Int, th: Int): FloatArray {
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

internal fun LamaInpainter.unionBounds(rects: List<Rect>): Rect {
    var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
    for (rc in rects) { l = min(l, rc.left); t = min(t, rc.top); r = max(r, rc.right); b = max(b, rc.bottom) }
    return Rect(l, t, r, b)
}

internal fun LamaInpainter.findMaskBounds(mask: Bitmap): Rect? {
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

/**
 * Tìm các vùng bôi vẽ riêng biệt (không giao nhau) sử dụng thuật toán Connected Components BFS.
 * Quét nhanh và nhóm các pixel liền kề của nét vẽ vào từng Rect riêng biệt.
 */
internal fun LamaInpainter.findSeparateMaskBounds(mask: Bitmap): List<Rect> {
    val w = mask.width
    val h = mask.height
    val px = IntArray(w * h)
    mask.getPixels(px, 0, w, 0, 0, w, h)
    
    val visited = java.util.BitSet(w * h)
    val rects = mutableListOf<Rect>()
    
    val dx = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
    val dy = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)
    
    val queue = java.util.ArrayDeque<Int>()
    
    for (y in 0 until h) {
        for (x in 0 until w) {
            val idx = y * w + x
            if (visited.get(idx)) continue
            
            val p = px[idx]
            if (Color.red(p) > 10 || Color.green(p) > 10 || Color.blue(p) > 10) {
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                
                queue.clear()
                queue.add(idx)
                visited.set(idx)
                
                while (!queue.isEmpty()) {
                    val currIdx = queue.poll() ?: break
                    val cx = currIdx % w
                    val cy = currIdx / w
                    
                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)
                    
                    for (d in 0 until 8) {
                        val nx = cx + dx[d]
                        val ny = cy + dy[d]
                        if (nx in 0 until w && ny in 0 until h) {
                            val nIdx = ny * w + nx
                            if (!visited.get(nIdx)) {
                                val np = px[nIdx]
                                if (Color.red(np) > 10 || Color.green(np) > 10 || Color.blue(np) > 10) {
                                    visited.set(nIdx)
                                    queue.add(nIdx)
                                }
                            }
                        }
                    }
                }
                
                // Thêm một chút padding nhỏ (ví dụ 6px) để đảm bảo bao phủ hoàn toàn nét vẽ mờ ở rìa
                val pad = 6
                val left = (minX - pad).coerceAtLeast(0)
                val top = (minY - pad).coerceAtLeast(0)
                val right = (maxX + 1 + pad).coerceAtMost(w)
                val bottom = (maxY + 1 + pad).coerceAtMost(h)
                
                if (right > left && bottom > top) {
                    rects.add(Rect(left, top, right, bottom))
                }
            }
        }
    }
    return rects
}

// ── Resize / padding ──────────────────────────────────────────────────

