package com.example.ocrmanga

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Application class for OCR Manga
 * Initializes OpenCV for image processing
 */
class OcrMangaApplication : Application() {

    companion object {
        private const val TAG = "OcrMangaApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "Unable to load OpenCV!")
        } else {
            Log.d(TAG, "OpenCV loaded successfully")
        }
    }
}
