package com.example.ocrmanga

import android.app.Application
import com.example.ocrmanga.data.ocr.OpenCvInitializer
import com.example.ocrmanga.utils.AppLogger

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
            AppLogger.e(TAG, "Unable to load OpenCV!")
        } else {
            //AppLogger.d(TAG, "OpenCV loaded successfully")
        }
    }
}
