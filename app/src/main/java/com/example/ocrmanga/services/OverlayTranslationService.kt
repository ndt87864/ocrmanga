package com.example.ocrmanga.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.repositories.TranslationRepository
import com.example.ocrmanga.ui.theme.OCRMangaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayTranslationService : Service() {
    
    companion object {
        private const val TAG = "OverlayTranslationService"
        
        const val ACTION_SHOW_OVERLAY = "show_overlay"
        const val ACTION_HIDE_OVERLAY = "hide_overlay"
        const val ACTION_PROCESS_SCREEN = "process_screen"
        const val EXTRA_BITMAP = "bitmap"
    }
    
    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var translationRepository: TranslationRepository? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var translatedTexts by mutableStateOf<List<TextBlockInfo>>(emptyList())
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        translationRepository = TranslationRepository(application)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_PROCESS_SCREEN -> {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_BITMAP, Bitmap::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_BITMAP)
                }
                bitmap?.let { processScreenBitmap(it) }
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun showOverlay() {
        if (overlayView != null) return
        
        try {
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            params.gravity = Gravity.TOP or Gravity.START
            
            overlayView = ComposeView(this).apply {
                setContent {
                    OCRMangaTheme {
                        OverlayContent(translatedTexts)
                    }
                }
            }
            
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay", e)
        }
    }
    
    private fun hideOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                overlayView = null
                Log.d(TAG, "Overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding overlay", e)
            }
        }
    }
    
    private fun processScreenBitmap(bitmap: Bitmap) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Convert bitmap to URI format that can be processed by existing translation logic
                val uri = saveBitmapToTempUri(bitmap)
                
                // Use existing translation repository to process the image
                val result = translationRepository?.translateImage(uri, TranslationMode.GEMINI)
                
                result?.second?.let { textBlocks ->
                    launch(Dispatchers.Main) {
                        translatedTexts = textBlocks
                        updateOverlay()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing screen bitmap", e)
            }
        }
    }
    
    private fun saveBitmapToTempUri(bitmap: Bitmap): android.net.Uri {
        // Save bitmap to temporary file and return URI
        val tempFile = java.io.File(cacheDir, "temp_screen_${System.currentTimeMillis()}.png")
        val outputStream = java.io.FileOutputStream(tempFile)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        outputStream.close()
        return android.net.Uri.fromFile(tempFile)
    }
    
    private fun updateOverlay() {
        // Trigger recomposition of overlay
        (overlayView as? ComposeView)?.setContent {
            OCRMangaTheme {
                OverlayContent(translatedTexts)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        serviceScope.cancel()
    }
}

@Composable
private fun OverlayContent(translatedTexts: List<TextBlockInfo>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        translatedTexts.forEach { textBlock ->
            Text(
                text = textBlock.translatedText ?: textBlock.originalText,
                modifier = Modifier
                    .padding(4.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    )
                    .padding(8.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}