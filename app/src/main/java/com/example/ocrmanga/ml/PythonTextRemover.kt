package com.example.ocrmanga.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Python-based text remover using text_remover.remove_text module
 * Converts Android Bitmap and Rect list to JSON blocks, calls Python inpainting.
 */
class PythonTextRemover(private val context: Context) {
    
    companion object {
        private const val TAG = "PythonTextRemover"
        private const val PYTHON_MODULE = "text_remover"
        private const val PYTHON_FUNCTION = "remove_text"
    }

    /**
     * Simple on-device fallback inpainting.
     * Strategy: for each rect, crop a padded neighbourhood, downscale+upscale to produce a blur,
     * then draw it back into the region. This hides text reasonably when Python isn't available.
     */
    private fun inpaintFallback(srcBitmap: Bitmap, rects: List<Rect>): Bitmap {
        try {
            val bmp = srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint().apply { isAntiAlias = true }

            for (r in rects) {
                try {
                    val left = r.left.coerceAtLeast(0)
                    val top = r.top.coerceAtLeast(0)
                    val right = r.right.coerceAtMost(bmp.width)
                    val bottom = r.bottom.coerceAtMost(bmp.height)
                    val w = right - left
                    val h = bottom - top
                    if (w <= 0 || h <= 0) continue

                    // padding to sample surrounding pixels
                    val pad = (minOf(w, h) / 4).coerceIn(8, 64)
                    val sx = (left - pad).coerceAtLeast(0)
                    val sy = (top - pad).coerceAtLeast(0)
                    val ex = (right + pad).coerceAtMost(bmp.width)
                    val ey = (bottom + pad).coerceAtMost(bmp.height)
                    val sw = ex - sx
                    val sh = ey - sy
                    if (sw <= 0 || sh <= 0) continue

                    val neighbourhood = Bitmap.createBitmap(bmp, sx, sy, sw, sh)

                    // downscale/ upscale to approximate blur
                    val smallW = (maxOf(1, sw / 8))
                    val smallH = (maxOf(1, sh / 8))
                    val small = Bitmap.createScaledBitmap(neighbourhood, smallW, smallH, true)
                    val blurred = Bitmap.createScaledBitmap(small, sw, sh, true)

                    // draw blurred neighbourhood back
                    canvas.drawBitmap(blurred, sx.toFloat(), sy.toFloat(), paint)

                    neighbourhood.recycle()
                    small.recycle()
                    blurred.recycle()
                } catch (e: Exception) {
                    Log.w(TAG, "inpaintFallback rect failed: ${e.message}")
                }
            }

            return bmp
        } catch (e: Exception) {
            Log.e(TAG, "inpaintFallback failed", e)
            return srcBitmap
        }
    }

    /**
     * Python-based inpainter is ready once initialized (no async loading needed)
     */
    fun isReady(): Boolean = true

    /**
     * Inpaint image using Python text_remover module.
     */
    suspend fun inpaintFromBlocks(imageBitmap: Bitmap, textRects: List<Rect>): Bitmap? =
        withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting Python inpainting with ${textRects.size} text blocks")
            
            // 1. Save image to temp file
            val imagePath = saveBitmapToTemp(imageBitmap, "input_image.png")
            if (imagePath == null) {
                Log.e(TAG, "Failed to save input image")
                return@withContext null
            }
            
            // 2. Convert Rect list to JSON blocks format
            val blocksJson = convertRectsToBlocksJson(textRects)
            Log.d(TAG, "Blocks JSON: $blocksJson")
            
            // 3. Call Python function via subprocess or direct Python invocation
            val outputPath = File(context.cacheDir, "python_inpaint_output_${System.currentTimeMillis()}.png").absolutePath
            
            val result = callPythonRemoveText(imagePath, blocksJson, outputPath)

            if (!result) {
                Log.w(TAG, "Python inpainting failed or unavailable, using on-device fallback")
                return@withContext inpaintFallback(imageBitmap, textRects)
            }
            
            // 4. Read result bitmap
            val resultBitmap = readBitmapFromPath(outputPath)
            if (resultBitmap == null) {
                Log.e(TAG, "Failed to read result bitmap from: $outputPath")
                return@withContext null
            }
            
            Log.i(TAG, "Python inpainting succeeded: ${resultBitmap.width}x${resultBitmap.height}")
            return@withContext resultBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in Python inpainting", e)
            return@withContext null
        }
    }

    private fun convertRectsToBlocksJson(rects: List<Rect>): String {
        val blocksArray = JSONArray()
        for (rect in rects) {
            val block = JSONObject().apply {
                put("x", rect.left)
                put("y", rect.top)
                put("width", rect.right - rect.left)
                put("height", rect.bottom - rect.top)
            }
            blocksArray.put(block)
        }
        return blocksArray.toString()
    }

    private fun saveBitmapToTemp(bitmap: Bitmap, filename: String): String? {
        return try {
            val outFile = File(context.cacheDir, filename)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap temp file", e)
            null
        }
    }

    private fun readBitmapFromPath(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $path")
                return null
            }
            android.graphics.BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bitmap from: $path", e)
            null
        }
    }

    private fun callPythonRemoveText(imagePath: String, blocksJson: String, outputPath: String): Boolean {
        return try {
            val pythonCmd = getPythonExecutable()
            val pythonScriptContent = """
                import sys
                import traceback
                sys.path.insert(0, r'${context.filesDir.absolutePath}')
                try:
                    from text_remover import remove_text
                    result = remove_text(r'$imagePath', r'$blocksJson', r'$outputPath')
                    print("SUCCESS: " + str(result))
                except Exception as e:
                    print("PYTHON_ERROR: " + str(e))
                    traceback.print_exc()
            """.trimIndent()

            val scriptFile = File(context.cacheDir, "temp_remove_text_${System.currentTimeMillis()}.py")
            scriptFile.writeText(pythonScriptContent)

            Log.d(TAG, "Executing Python script: $pythonCmd ${scriptFile.absolutePath}")
            Log.d(TAG, "Image: $imagePath, Output: $outputPath")

            val process = try {
                ProcessBuilder(pythonCmd, scriptFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                Log.w(TAG, "Direct exec failed for '$pythonCmd': ${e.message}")
                scriptFile.delete()
                return false
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            Log.d(TAG, "Python process exit code: $exitCode")
            if (output.isNotEmpty()) Log.d(TAG, "Python output: $output")

            scriptFile.delete()

            val outputExists = File(outputPath).exists()
            val success = outputExists && exitCode == 0

            Log.i(TAG, "Python inpainting result: success=$success, outputExists=$outputExists, exitCode=$exitCode")

            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Python remove_text: ${e.message}", e)
            false
        }
    }

    suspend fun inpaintWithMask(imageBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? =
        withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting Python inpainting with mask bitmap")

            val imagePath = saveBitmapToTemp(imageBitmap, "input_image_mask.png")
            val maskPath = saveBitmapToTemp(maskBitmap, "mask_bitmap.png")
            if (imagePath == null || maskPath == null) return@withContext null

            val outputPath = File(context.cacheDir, "python_inpaint_mask_${System.currentTimeMillis()}.png").absolutePath

            val result = callPythonRemoveTextWithMask(imagePath, maskPath, outputPath)
            if (!result) {
                Log.e(TAG, "Python mask-based inpainting failed")
                return@withContext null
            }

            val resultBitmap = readBitmapFromPath(outputPath)
            if (resultBitmap == null) return@withContext null
            return@withContext resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error in Python mask-based inpainting", e)
            return@withContext null
        }
    }

    private fun callPythonRemoveTextWithMask(imagePath: String, maskPath: String, outputPath: String): Boolean {
        return try {
            val pythonCmd = getPythonExecutable()
            val pythonScriptContent = """
                import sys
                import traceback
                sys.path.insert(0, r'${context.filesDir.absolutePath}')
                try:
                    from text_remover import remove_text_with_mask
                    result = remove_text_with_mask(r'$imagePath', r'$maskPath', r'$outputPath')
                    print("SUCCESS: " + str(result))
                except Exception as e:
                    print("PYTHON_ERROR: " + str(e))
                    traceback.print_exc()
            """.trimIndent()

            val scriptFile = File(context.cacheDir, "temp_remove_text_mask_${System.currentTimeMillis()}.py")
            scriptFile.writeText(pythonScriptContent)

            Log.d(TAG, "Executing Python mask script: $pythonCmd")
            Log.d(TAG, "Image: $imagePath, Mask: $maskPath, Output: $outputPath")

            val process = try {
                ProcessBuilder(pythonCmd, scriptFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                Log.w(TAG, "Direct exec failed for mask script with '$pythonCmd': ${e.message}")
                scriptFile.delete()
                return false
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            Log.d(TAG, "Python mask process exit code: $exitCode")
            if (output.isNotEmpty()) Log.d(TAG, "Python output: $output")

            scriptFile.delete()

            val outputExists = File(outputPath).exists()
            val success = outputExists && exitCode == 0

            Log.i(TAG, "Python mask inpainting result: success=$success, outputExists=$outputExists, exitCode=$exitCode")

            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Python remove_text_with_mask: ${e.message}", e)
            false
        }
    }

    private fun getPythonExecutable(): String {
        val candidates = listOf(
            "python",
            "python3",
            "/usr/bin/python3",
            "/usr/local/bin/python3",
            "/opt/miniconda/bin/python",
            "/opt/conda/bin/python"
        )

        val simpleNames = listOf("python3", "python")
        for (name in simpleNames) {
            try {
                val whichProc = ProcessBuilder("sh", "-c", "command -v $name || which $name").start()
                val path = whichProc.inputStream.bufferedReader().use { it.readText().trim() }
                if (path.isNotEmpty()) {
                    val f = File(path)
                    if (f.exists() && f.canExecute()) {
                        Log.d(TAG, "Found Python at: $path")
                        return path
                    }
                }
            } catch (e: Exception) {
            }
        }

        for (candidate in candidates) {
            if (candidate.contains("/")) {
                val f = File(candidate)
                if (f.exists() && f.canExecute()) {
                    try {
                        val p = ProcessBuilder(candidate, "--version").start()
                        if (p.waitFor() == 0) {
                            Log.d(TAG, "Found Python at: $candidate")
                            return candidate
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }

        Log.w(TAG, "No usable Python interpreter found on device; defaulting to 'python3'")
        return "python3"
    }
}
