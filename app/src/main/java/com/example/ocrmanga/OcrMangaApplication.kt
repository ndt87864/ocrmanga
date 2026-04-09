package com.example.ocrmanga

import android.app.Application
import android.util.Log
import com.example.ocrmanga.data.ocr.OpenCvInitializer

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

        if (!OpenCvInitializer.ensureInitialized()) {
            Log.e(TAG, "Unable to load OpenCV!")
        } else {
            Log.d(TAG, "OpenCV loaded successfully")
        }
    }
}
