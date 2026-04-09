package com.example.ocrmanga.data.ocr

import android.util.Log
import org.opencv.core.Mat

/**
 * Loads the OpenCV native runtime once and verifies JNI bindings before OCR uses it.
 */
object OpenCvInitializer {
    private const val TAG = "OpenCvInitializer"
    private const val LIB_NAME = "opencv_java4"

    @Volatile
    private var initialized = false

    @Volatile
    private var attempted = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true

        synchronized(this) {
            if (initialized) return true
            if (attempted) return false

            attempted = true
            initialized = try {
                System.loadLibrary(LIB_NAME)

                // Probe the exact JNI entrypoint that was crashing at runtime.
                val probe = Mat()
                probe.release()

                Log.i(TAG, "OpenCV native library loaded successfully")
                true
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to initialize OpenCV native runtime", error)
                false
            }

            return initialized
        }
    }
}
