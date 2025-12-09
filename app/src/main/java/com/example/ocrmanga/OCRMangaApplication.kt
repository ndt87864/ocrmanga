package com.example.ocrmanga

import android.app.Application
import com.example.ocrmanga.utils.TextRemovalHelper

/**
 * Custom Application class để khởi tạo các thành phần cần thiết khi app khởi động
 */
class OCRMangaApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Khởi tạo Python runtime
        TextRemovalHelper.initializePython(this)
    }
}
