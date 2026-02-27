package com.example.ocrmanga.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.ocrmanga.utils.AppLogger as Log

/**
 * Text remover that delegates to LamaInpainter for on-device TFLite inference.
 * Kept as a wrapper class for backward compatibility with TextRemovalHelper and ViewerViewModel.
 */
class PythonTextRemover(private val context: Context) {

    companion object {
        private const val TAG = "PythonTextRemover"
    }

    init {
        LamaInpainter.initialize(context)
        Log.i(TAG, "Initialized with LamaInpainter backend")
    }

    fun isReady(): Boolean = LamaInpainter.isReady()

    suspend fun inpaintFromBlocks(imageBitmap: Bitmap, textRects: List<Rect>): Bitmap? {
        Log.i(TAG, "inpaintFromBlocks: ${textRects.size} blocks, image ${imageBitmap.width}x${imageBitmap.height}")
        return LamaInpainter.inpaintBlocks(imageBitmap, textRects.map { LamaInpainter.InpaintBlock(it) })
    }

    suspend fun inpaintWithMask(imageBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        Log.i(TAG, "inpaintWithMask: image ${imageBitmap.width}x${imageBitmap.height}, mask ${maskBitmap.width}x${maskBitmap.height}")
        return LamaInpainter.inpaintWithMask(imageBitmap, maskBitmap)
    }
}
