package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.example.ocrmanga.data.constant.TranslationPrompts
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.ocr.BubbleDetector
import com.example.ocrmanga.ui.theme.ThemePreferences
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

open class TranslationRepositoryCore(protected val application: Application) {

        init {
            TranslationPrompts.initialize(application)
        }

        private val themePreferences = ThemePreferences(application)

    // Phase 1: Text Region Detection
    protected val textRegionDetector by lazy {
        com.example.ocrmanga.data.ocr.TextRegionDetector()
    }

    // Phase 2: Mask Generation
    protected val maskGenerator by lazy {
        com.example.ocrmanga.data.ocr.TextMaskGenerator()
    }

    // Phase 3: Advanced Preprocessing
    protected val preprocessor by lazy {
        com.example.ocrmanga.data.ocr.AdvancedPreprocessor()
    }

    // Phase 4: Improved Block Merging
    protected val blockMerger by lazy {
        com.example.ocrmanga.data.ocr.RegionBasedMerger()
    }

    // Container/Bubble Detection using OpenCV contour analysis
    protected val bubbleDetector by lazy {
        BubbleDetector()
    }

    protected suspend fun getDefaultFontSettings(): Map<String, Any> {
        val fontFamily = themePreferences.defaultTranslationFont.first() ?: "Default"
        val lineSpacing = themePreferences.defaultLineSpacing.first() ?: 1.0f
        val textBoldness = themePreferences.defaultTextBoldness.first() ?: 1.0f
        val overlayAlpha = themePreferences.defaultOverlayAlpha.first() ?: 0.8f
        val overlayBrightness = themePreferences.defaultOverlayBrightness.first() ?: 1.0f
        val borderColor = themePreferences.defaultBorderColor.first() ?: "#000000"
        val borderThickness = themePreferences.defaultBorderThickness.first() ?: 2.0f
        val textColor = themePreferences.defaultTextColor.first() ?: "#FFFFFF"
        
        return mapOf(
            "fontFamily" to fontFamily,
            "lineSpacing" to lineSpacing,
            "textBoldness" to textBoldness,
            "overlayAlpha" to overlayAlpha,
            "overlayBrightness" to overlayBrightness,
            "overlayColor" to "#FFFFFF", // Default white overlay
            "borderColor" to borderColor,
            "borderThickness" to borderThickness,
            "textColor" to textColor
        )
    }

    // Public helpers so UI/ViewModel can check availability of API keys
    fun hasGeminiApiKeys(): Boolean {
        return poolManager.selectBestKey("gemini") != null
    }

    fun hasMistralApiKeys(): Boolean {
        return poolManager.selectBestKey("mistral") != null
    }

    fun hasZAiApiKeys(): Boolean {
        return poolManager.selectBestKey("zai") != null
    }

    fun hasOcrMangaApiKeys(): Boolean {
        return poolManager.selectBestKey("ocrmanga") != null
    }

    protected val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    protected val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    protected val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    protected val koreanRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    protected val translators = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()
    protected val cache = mutableMapOf<String, Pair<String, List<TextBlockInfo>>>()
    protected val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1)) // Ép sử dụng HTTP/1.1 để ổn định hơn với các request lâu
        .build()
    protected val databaseHelper = DatabaseHelper(application)

    fun clearSession() {
        try {
            cache.clear()
        } catch (e: Throwable) {
            Log.w("TranslationRepository", "Failed to clear cache", e)
        }
        try {
            lastTranslationSession.clear()
        } catch (e: Throwable) {
            Log.w("TranslationRepository", "Failed to clear lastTranslationSession", e)
        }
        // Note: translators (MLKit) do not expose a cancel; we don't close them here.
    }

    fun clearCacheForImage(imageUri: Uri, mode: TranslationMode? = null) {
        try {
            if (mode != null) {
                cache.remove("$imageUri-$mode")
            } else {
                val keysToRemove = cache.keys.filter { it.startsWith("$imageUri-") }
                keysToRemove.forEach { cache.remove(it) }
            }
        } catch (e: Throwable) {
            Log.w("TranslationRepository", "Failed to clear cache for $imageUri", e)
        }
    }

    protected val poolManager by lazy { com.example.ocrmanga.data.translation.ApiKeyPoolManager(application) }
    protected val mistralRequester by lazy { com.example.ocrmanga.data.translation.MistralRequester(application, poolManager, httpClient) }
    protected val zaiRequester by lazy { com.example.ocrmanga.data.translation.ZAiRequester(application, poolManager, httpClient) }
    protected val ocrMangaRequester by lazy { com.example.ocrmanga.data.translation.OcrMangaRequester(application, poolManager, httpClient) }

    protected var currentGeminiModelIndex = 0
    protected var currentMistralModelIndex = 0
    protected var currentZAiModelIndex = 0
    protected var currentOcrMangaModelIndex = 0

    protected val modelPrefs by lazy { application.getSharedPreferences("api_key_prefs", android.content.Context.MODE_PRIVATE) }

    protected fun getModelsFromPrefs(type: String, defaultModels: List<String>): List<String> {
        val raw = modelPrefs.getString("${type}_available_models", null) ?: return defaultModels
        val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) defaultModels else list
    }

    protected val geminiModels: List<String>
        get() = getModelsFromPrefs("gemini", listOf("gemini-3.5-flash", "gemini-3-flash-preview", "gemini-2.5-flash", "gemini-3.1-flash-lite-preview", "gemini-2.5-flash-lite"))

    protected val mistralModels: List<String>
        get() = getModelsFromPrefs("mistral", listOf("mistral-large-latest", "mistral-medium-2508", "open-mixtral-8x22b", "mistral-small-latest"))

    protected val zAiModels: List<String>
        get() = getModelsFromPrefs("zai", listOf("glm-4.5", "glm-4.7-flash", "glm-4-plus"))

    protected val ocrMangaModels: List<String>
        get() = getModelsFromPrefs("ocrmanga", listOf("kr/claude-sonnet-4.5", "kr/glm-5", "cc/claude-opus-4.7", "gh/claude-sonnet-4.6"))

    // Lưu session dịch gần nhất: Pair<Uri, Pair<text gốc, text dịch cuối>>
    val lastTranslationSession = mutableListOf<Pair<Uri, Pair<String, String>>>()

    protected val vietnameseImprovements = mapOf(
        "bạn là" to "cậu là",
        "không có" to "chẳng có",
        "rất tốt" to "tuyệt lắm",
        "nhanh chóng" to "nhanh thôi",
        "hãy làm" to "làm đi",
        "fapping thời gian" to "thời gian thư giãn",
        "người cao niên" to "tiền bối"
    )

    protected val MIN_OCR_CONFIDENCE = 0.45f
    protected val MIN_TEXT_LENGTH = 1
    protected val MAX_SINGLE_CHAR_ASPECT_RATIO = 3.5f
    protected val MIN_BLOCK_AREA = 64

    init {
        preloadRecognitionModels()
    }

    // --- ApiKey Management Methods (Removed manual loading) ---

    protected fun preloadRecognitionModels() {
        try {
            // Tạo bitmap dummy kích thước tối thiểu 32x32
            val dummyBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val inputImage = InputImage.fromBitmap(dummyBitmap, 0)
            latinRecognizer.process(inputImage)
            chineseRecognizer.process(inputImage)
            japaneseRecognizer.process(inputImage)
            koreanRecognizer.process(inputImage)
        } catch (e: Exception) {
            // Chỉ log lỗi, không Toast để tránh spam Toast
            Log.e("TranslationRepository", "Tải trước mô hình nhận diện thất bại: ${e.javaClass.simpleName}", e)
        }
    }
}
