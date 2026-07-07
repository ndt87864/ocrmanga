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

    internal const val TAG = "LamaInpainter"
    internal const val MODEL_PATH = "models/anime-manga-big-lama.onnx"
    // Keep original 10px padding - 5px was too tight and caused edge artifacts
    internal const val MASK_PADDING = 10
    internal const val CONTEXT_PADDING = 128
    internal const val MAX_CLUSTER_AREA = 900_000
    internal const val MAX_CLUSTER_SIDE = 1400

    /** Morphological dilation radius applied to the binary mask before LaMa inference.
     *  Removes residual ink pixels at character-silhouette boundaries. */
    internal const val MASK_DILATE_RADIUS = 5

    /** Unsharp mask strength applied to the filled region after pyramid blend.
     *  0 = no sharpening, 1 = full difference added back. */
    internal const val SHARPEN_AMOUNT = 0.45f
    internal const val SHARPEN_BLUR_RADIUS = 1

    /** Fixed model dimensions (NCHW) */
    internal const val MODEL_H = 512
    internal const val MODEL_W = 512
    internal const val MODEL_C = 3

    internal var ortEnv: OrtEnvironment? = null
    internal var ortSession: OrtSession? = null
    internal var isInitialized = false
    internal var inputH = MODEL_H; internal var inputW = MODEL_W; internal var inputC = MODEL_C
    internal val mutex = Mutex()

    // Buffer pools để tái sử dụng, tránh cấp phát lặp lại
    internal val tensorImageBuffer = FloatArray(1 * 3 * MODEL_H * MODEL_W)
    internal val tensorMaskBuffer = FloatArray(1 * 1 * MODEL_H * MODEL_W)
    internal val pixelBuffer = IntArray(MODEL_H * MODEL_W)

    // FloatBuffer wrappers tái sử dụng cho ONNX tensor creation
    internal val imageFloatBuffer = FloatBuffer.wrap(tensorImageBuffer)
    internal val maskFloatBuffer = FloatBuffer.wrap(tensorMaskBuffer)

    // Buffer cho pyramid blend - sẽ được resize khi cần
    internal var pyramidWorkBuffer: FloatArray? = null
    internal var pyramidMaxSize = 0

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
                //Log.i(TAG, "Copying ONNX model to internal storage (asset=$assetSize, local=${modelFile.length()})...")
                context.assets.open(MODEL_PATH).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
                //Log.i(TAG, "Model copied: ${modelFile.length()} bytes")
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
            }

            val session = env.createSession(modelFile.absolutePath, sessionOptions)
            ortSession = session

            // Log input/output info
            for ((name, info) in session.inputInfo) {
                val tensorInfo = info.info as? ai.onnxruntime.TensorInfo
                //Log.i(TAG, "Input '$name' shape: ${tensorInfo?.shape?.contentToString()}")
            }
            for ((name, info) in session.outputInfo) {
                val tensorInfo = info.info as? ai.onnxruntime.TensorInfo
                //Log.i(TAG, "Output '$name' shape: ${tensorInfo?.shape?.contentToString()}")
            }

            isInitialized = true
            //Log.i(TAG, "LamaInpainter ready (ONNX): ${inputW}x${inputH}")
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

}
