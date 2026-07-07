package com.example.ocrmanga.data.ocr

import com.example.ocrmanga.utils.AppLogger
import org.opencv.core.Mat

/**
 * Loads the OpenCV native runtime once and verifies JNI bindings before OCR uses it.
 */
object OpenCvInitializer {
    private const val TAG = "OpenCvInitializer"
    private const val LIB_NAME = "opencv_java4"

    @Volatile
    private var initialized = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true

        synchronized(this) {
            if (initialized) return true

            try {
                // Attempt to explicitly load standard C++ shared library which is a common dependency 
                // missing on some OEM ROMs when loading OpenCV
                System.loadLibrary("c++_shared")
            } catch (e: Throwable) {
                AppLogger.w(TAG, "c++_shared could not be explicitly loaded (might already be present or not needed)", e)
            }

            // Attempt 1: Official Loader
            initialized = try {
                if (org.opencv.android.OpenCVLoader.initLocal()) {
                    val probe = Mat()
                    probe.release()
                    //AppLogger.i(TAG, "OpenCV native library loaded successfully via initLocal()")
                    true
                } else false
            } catch (e: Throwable) {
                AppLogger.e(TAG, "initLocal() failed", e)
                false
            }

            // Attempt 2: Direct System.loadLibrary fallback
            if (!initialized) {
                initialized = try {
                    System.loadLibrary(LIB_NAME)
                    val probe = Mat()
                    probe.release()
                    //AppLogger.i(TAG, "OpenCV native library loaded successfully via System.loadLibrary()")
                    true
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "System.loadLibrary() failed", e)
                    false
                }
            }

            if (!initialized) {
                AppLogger.e(TAG, "All attempts to initialize OpenCV natively failed.")
            }
            return initialized
        }
    }
}
