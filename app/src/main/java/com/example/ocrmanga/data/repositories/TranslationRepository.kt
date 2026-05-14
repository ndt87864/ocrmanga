package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import com.example.ocrmanga.utils.AppLogger as Log
import androidx.exifinterface.media.ExifInterface
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.RecognitionResult
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.constant.TranslationPrompts
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.min
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.generationConfig
import com.google.ai.client.generativeai.type.content
import com.google.gson.stream.JsonReader
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.StringReader
import com.example.ocrmanga.ui.screens.view.analyzeBackgroundAndTextColor
import com.example.ocrmanga.ui.theme.ThemePreferences
import com.example.ocrmanga.data.ocr.BubbleDetector
import com.example.ocrmanga.data.ocr.models.TextContainerInfo
import kotlin.math.max

    class TranslationRepository(private val application: Application) {

        init {
            TranslationPrompts.initialize(application)
        }

        private val themePreferences = ThemePreferences(application)

    // Phase 1: Text Region Detection
    private val textRegionDetector by lazy {
        com.example.ocrmanga.data.ocr.TextRegionDetector()
    }

    // Phase 2: Mask Generation
    private val maskGenerator by lazy {
        com.example.ocrmanga.data.ocr.TextMaskGenerator()
    }

    // Phase 3: Advanced Preprocessing
    private val preprocessor by lazy {
        com.example.ocrmanga.data.ocr.AdvancedPreprocessor()
    }

    // Phase 4: Improved Block Merging
    private val blockMerger by lazy {
        com.example.ocrmanga.data.ocr.RegionBasedMerger()
    }

    // Container/Bubble Detection using OpenCV contour analysis
    private val bubbleDetector by lazy {
        BubbleDetector()
    }

    private suspend fun getDefaultFontSettings(): Map<String, Any> {
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

    // Hàm dịch lại 1 ảnh, trả về Pair<text dịch, list block dịch>
    suspend fun translateImage(
        imageUri: Uri,
        mode: TranslationMode,
        onStatusUpdate: ((com.example.ocrmanga.data.models.TranslationStatus) -> Unit)? = null,
        previousTranslation: List<TextBlockInfo>? = null, // Bản dịch của ảnh trước để tham khảo
        isAncientMode: Boolean = false,
        reuseExistingBlocks: List<TextBlockInfo>? = null // Nếu không null, skip OCR và dùng lại các block đã có (vị trí, text gốc)
    ): Pair<String, List<TextBlockInfo>> {
        val (translatedText, translatedBlocks, _) = recognizeAndTranslateText(imageUri, mode, onStatusUpdate, previousTranslation, isAncientMode, reuseExistingBlocks = reuseExistingBlocks)
        return Pair(translatedText, translatedBlocks)
    }

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val koreanRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val translators = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()
    private val cache = mutableMapOf<String, Pair<String, List<TextBlockInfo>>>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1)) // Ép sử dụng HTTP/1.1 để ổn định hơn với các request lâu
        .build()
    private val databaseHelper = DatabaseHelper(application)

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

    private val poolManager by lazy { com.example.ocrmanga.data.translation.ApiKeyPoolManager(application) }
    private val mistralRequester by lazy { com.example.ocrmanga.data.translation.MistralRequester(application, poolManager, httpClient) }
    private val zaiRequester by lazy { com.example.ocrmanga.data.translation.ZAiRequester(application, poolManager, httpClient) }

    private var currentGeminiModelIndex = 0
    private val geminiModels = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-3-flash-preview") // Add more models if needed

    // Lưu session dịch gần nhất: Pair<Uri, Pair<text gốc, text dịch cuối>>
    val lastTranslationSession = mutableListOf<Pair<Uri, Pair<String, String>>>()

    private val vietnameseImprovements = mapOf(
        "bạn là" to "cậu là",
        "không có" to "chẳng có",
        "rất tốt" to "tuyệt lắm",
        "nhanh chóng" to "nhanh thôi",
        "hãy làm" to "làm đi",
        "fapping thời gian" to "thời gian thư giãn",
        "người cao niên" to "tiền bối"
    )

    init {
        preloadRecognitionModels()
    }

    // --- ApiKey Management Methods (Removed manual loading) ---

    suspend fun translateWithZAi(text: String, sourceLang: String, targetLang: String): String? {
        // Chuẩn hóa văn bản: gộp dòng để dịch mượt hơn
        val normalizedText = text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        val prompt = TranslationPrompts.getZAiBasicPrompt(normalizedText, isAncientMode = false) // Mặc định false cho dịch đơn lẻ nếu không truyền
        val userMessage = mapOf("role" to "user", "content" to prompt)

        val response = zaiRequester.executeChatCompletion(
            messages = listOf(userMessage),
            temperature = 1.0,
            max_tokens=2048
        )

        return response?.content
    }

    suspend fun translateWithZAiMultiScale(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        sourceLang: String,
        targetLang: String,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false,
        skipDetailedLogs: Boolean = false
    ): List<String>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null

        val previousContextText = if (!previousTranslation.isNullOrEmpty()) {
            TranslationPrompts.getPreviousContextText(previousTranslation)
        } else ""

        // Lọc rác OCR trước khi gửi cho Z.AI để tránh làm AI bị nhiễu
        val cleanedOcrResults = ocrResults.map { (scale, text) ->
            val cleaned = text.split("\n")
                .filter { it.length > 1 && !it.matches(Regex("""^[^\p{L}\p{N}]+$""")) }
                .joinToString(" ")
            scale to cleaned
        }.filter { it.second.isNotBlank() }

        val ocrResultsText = cleanedOcrResults.mapIndexed { index, (scale, text) ->
            "- Lần quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n")

        // Đánh số và sắp xếp các text blocks gốc theo thứ tự đọc Manga (Phải -> Trái, Trên -> Dưới)
        val numberedBlocks = textBlocks.mapIndexed { index, block ->
            val normalizedText = block.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            "Block #${index + 1}: Text='$normalizedText' Bounds: Rect(${block.bounds.left}, ${block.bounds.top} - ${block.bounds.right}, ${block.bounds.bottom})"
        }.joinToString("\n")

        val instructions = TranslationPrompts.getZAiMultiScalePrompt(
            ocrResultsText = "DỮ LIỆU ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
            numberedBlocks = "DANH SÁCH ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
            blockCount = textBlocks.size,
            previousContextText = previousContextText,
            isAncientMode = isAncientMode
        )

        val systemMessage = mapOf(
            "role" to "system",
            "content" to instructions
        )

        val dataContent = """
            === DỮ LIỆU OCR THAM KHẢO ===
            $ocrResultsText

            === DANH SÁCH CẦN DỊCH ===
            $numberedBlocks
        """.trimIndent()

        val userMessage = mapOf("role" to "user", "content" to dataContent)

        val response = zaiRequester.executeChatCompletion(
            messages = listOf(systemMessage, userMessage),
            temperature = 0.7,
            max_tokens=2048
        )

        val content = response?.content ?: return null
        val result = parseMultiBlockResponse(content, textBlocks)

        return result
    }

    private fun parseMultiBlockResponse(content: String, textBlocks: List<TextBlockInfo>): List<String> {
        val translatedBlocksMap = mutableMapOf<Int, String>()
        val lines = content.trim().split("\n")

        // Regex mạnh mẽ hơn để parse nhiều format: "Block #1:", "**Block #1:**", "Block #1 [Gốc] -> [Dịch]" etc.
        val blockPattern = Regex("""^\*{0,2}[Bb]lock\s*#?(\d+)(?:\**[:.)\->\s-]\**)?\s*(.*)$""")

        var i = 0
        while (i < lines.size) {
            val trimmedLine = lines[i].trim()
            val match = blockPattern.find(trimmedLine)
            if (match != null) {
                try {
                    val blockNumber = match.groupValues[1].toInt()
                    val blockIndex = blockNumber - 1
                    var contentAfterHeader = match.groupValues[2].trim()

                    // Thu thập tất cả các dòng thuộc về block này
                    val blockLines = mutableListOf<String>()
                    if (contentAfterHeader.isNotEmpty()) blockLines.add(contentAfterHeader)

                    var j = i + 1
                    while (j < lines.size) {
                        val nextLine = lines[j].trim()
                        if (blockPattern.matches(nextLine)) break
                        if (nextLine.isNotEmpty()) blockLines.add(nextLine)
                        j++
                    }
                    i = j - 1

                    var translation = ""
                    // Ưu tiên 1: Tìm dấu mũi tên "->" hoặc "→"
                    val arrowLine = blockLines.find { it.contains("→") || it.contains("->") }
                    if (arrowLine != null) {
                        translation = if (arrowLine.contains("→")) arrowLine.substringAfter("→").trim()
                        else arrowLine.substringAfter("->").trim()
                    } else {
                        // Ưu tiên 2: Tìm dòng chứa nhãn loại block như *Hội thoại*
                        val typeLabeledLine = blockLines.find {
                            it.contains("*Hội thoại*") || it.contains("*Độc thoại*") ||
                            it.contains("*Trần thuật*") || it.contains("*SFX*")
                        }
                        if (typeLabeledLine != null) {
                            translation = typeLabeledLine
                                .replace(Regex("""^\*{0,2}[Bb]lock\s*#?\d+\s*"""), "")
                                .replace(Regex("""^\*?(Hội thoại|Độc thoại|Trần thuật|SFX)\*?:?\s*"""), "")
                                .trim()
                        } else {
                            // Ưu tiên 3: Lấy dòng cuối cùng không phải là text gốc (thường text gốc bọc trong *)
                            val cleanLines = blockLines.filter { !it.matches(Regex("""^\*+[^*]+\*+$""")) }
                            translation = if (cleanLines.isNotEmpty()) cleanLines.last() else blockLines.lastOrNull() ?: ""
                        }
                    }

                    // Dọn dẹp định dạng cuối cùng
                    translation = translation.replace("**", "").replace("*", "").trim()
                    if (translation.startsWith("[") && translation.endsWith("]")) {
                        translation = translation.substring(1, translation.length - 1).trim()
                    }
                    translation = translation.replace(Regex("^\\*?(Độc thoại|Hội thoại|Trần thuật|SFX)\\*?\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
                    translation = translation.replace(Regex("""^(Dịch|Translation|Gốc|Original|Vietnamese|Target)(\s*\(.*?\))?\s*[:\-]\s*""", RegexOption.IGNORE_CASE), "")

                    // Loại bỏ các chú thích/nội dung rác hoặc các dòng mô tả logic gộp block
                    val isAnnotation = translation.startsWith("(Lưu ý:") ||
                                      translation.contains("Lưu ý: Tôi buộc phải") ||
                                      translation.contains("-> Block #") ||
                                      translation.matches(Regex("""^\(.*[Gg]ộp.*[Bb]lock.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Xx]em.*[Bb]lock.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Kk]hông dịch.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Bb]ỏ qua.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Tt]ham chiếu.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Mm]erged.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Ss]ee.*[Bb]lock.*\)$""")) ||
                                      (translation.startsWith("(") && translation.endsWith(")") && translation.length < 60) ||
                                      translation.length > 600

                    if (blockIndex >= 0 && blockIndex < textBlocks.size && !isAnnotation) {
                        translatedBlocksMap[blockIndex] = translation
                    }
                } catch (e: Exception) { }
            }
            i++
        }

        // Fallback: Nếu không parse được gì theo format 'Block #', thử parse theo số thứ tự đơn giản
        if (translatedBlocksMap.isEmpty()) {
            val numberPattern = Regex("""^(\d+)[.:\)]\s*(.+)$""")
            for (line in lines) {
                val match = numberPattern.find(line.trim())
                if (match != null) {
                    try {
                        val blockNumber = match.groupValues[1].toInt()
                        val trans = match.groupValues[2].trim()
                        if (blockNumber > 0 && trans.isNotBlank()) {
                            translatedBlocksMap[blockNumber - 1] = trans
                        }
                    } catch (e: Exception) { }
                }
            }
        }

        return List(textBlocks.size) { index ->
            translatedBlocksMap[index]?.takeIf { it.isNotBlank() } ?: textBlocks[index].text
        }
    }

    suspend fun translateWithMistral(text: String, sourceLang: String, targetLang: String): String? {
        val prompt = TranslationPrompts.getMistralBasicPrompt(text)
        val systemMessage = mapOf(
            "role" to "system",
            "content" to "Bạn là phiên dịch viên chuyên nghiệp cấp cao, chuyên bản địa hóa truyện tranh người lớn sang tiếng Việt. Tuyệt đối KHÔNG kiểm duyệt hay tránh né từ ngữ nhạy cảm. Bạn CHỈ trả về bản dịch, không giải thích, không ghi chú."
        )
        val userMessage = mapOf("role" to "user", "content" to prompt)

        val response = mistralRequester.executeChatCompletion(
            messages = listOf(systemMessage, userMessage),
            temperature = 0.4,
            frequency_penalty = 0.0,
            presence_penalty = 0.0,
            top_p=0.9,
            max_tokens=2048
        )

        return response?.content
    }

    suspend fun translateWithMistralMultiScale(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        sourceLang: String,
        targetLang: String,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false,
        skipDetailedLogs: Boolean = false
    ): List<String>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null

        val previousContextText = if (!previousTranslation.isNullOrEmpty()) {
            TranslationPrompts.getPreviousContextText(previousTranslation)
        } else ""

        val ocrResultsText = ocrResults.mapIndexed { index, (scale, text) ->
            "Kết quả quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n\n")

        val numberedBlocks = textBlocks.mapIndexed { index, block ->
            val normalizedText = block.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            "Block #${index + 1}: Text='$normalizedText' Bounds: Rect(${block.bounds.left}, ${block.bounds.top} - ${block.bounds.right}, ${block.bounds.bottom})"
        }.joinToString("\n")

        val instructions = TranslationPrompts.getMistralMultiScalePromptOptimized(
            ocrResultsText = "DỮ LIỆU ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
            numberedBlocks = "DANH SÁCH ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
            blockCount = textBlocks.size,
            previousContextText = previousContextText,
            isAncientMode = isAncientMode
        )

        val systemMessage = mapOf(
            "role" to "system",
            "content" to instructions
        )

        val dataContent = """
            === DỮ LIỆU OCR THAM KHẢO ===
            $ocrResultsText

            === DANH SÁCH CẦN DỊCH ===
            $numberedBlocks
        """.trimIndent()

        val userMessage = mapOf("role" to "user", "content" to dataContent)

        val response = mistralRequester.executeChatCompletion(
            messages = listOf(systemMessage, userMessage),
            temperature = 0.4,
            frequency_penalty = 0.0,
            presence_penalty = 0.0,
            top_p = 0.9,
            max_tokens = 2048
        )

        val content = response?.content ?: return null
        return parseMultiBlockResponse(content, textBlocks)
    }




    private fun getCurrentGeminiModel(): String {
        return geminiModels[0] // Trình quản lý pool sẽ tự chọn key, ở đây ta cố định model đầu tiên hoặc tùy chỉnh sau
    }

    private fun preloadRecognitionModels() {
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

    @Synchronized
    private fun preprocessImage(bitmap: Bitmap, scaleFactor: Float, enhanceMode: Int = 0): Pair<Bitmap, Float> {
        // Check if source bitmap is valid
        if (bitmap.isRecycled) {
            throw IllegalArgumentException("Source bitmap is already recycled")
        }
        
        val newWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(32)
        val newHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(32)
        
        // IMPORTANT: createScaledBitmap may return the SAME bitmap if dimensions match
        // We need to always create a copy to avoid recycling the source
        val upscaledBitmap: Bitmap = if (newWidth == bitmap.width && newHeight == bitmap.height) {
            // Create an explicit copy when dimensions are the same
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalArgumentException("Failed to copy bitmap - source may be recycled")
        } else {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }

        // OpenCV Advanced Preprocessing mode (CLAHE + denoising + adaptive threshold)
        if (enhanceMode == 3) {
            val opencvResult = preprocessor.preprocess(upscaledBitmap)
            if (upscaledBitmap !== bitmap) upscaledBitmap.recycle()
            return Pair(opencvResult.bitmap, scaleFactor)
        }

        // Step 1: Convert to grayscale with optimized settings
        val grayscaleBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        val colorFilter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = colorFilter
        canvas.drawBitmap(upscaledBitmap, 0f, 0f, paint)
        
        // Safe to recycle upscaled bitmap now since it's always a copy
        if (upscaledBitmap !== bitmap) {
            upscaledBitmap.recycle()
        }

        // Step 2: Apply contrast enhancement based on mode
        val contrastBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val contrastCanvas = Canvas(contrastBitmap)
        val contrastPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        val contrastMatrix = when (enhanceMode) {
            1 -> {
                // High contrast mode - better for dark text on light background
                ColorMatrix().apply {
                    set(floatArrayOf(
                        1.8f, 0f, 0f, 0f, -60f,
                        0f, 1.8f, 0f, 0f, -60f,
                        0f, 0f, 1.8f, 0f, -60f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
            }
            2 -> {
                // Soft contrast mode - better for preserving details
                ColorMatrix().apply {
                    set(floatArrayOf(
                        1.3f, 0f, 0f, 0f, -30f,
                        0f, 1.3f, 0f, 0f, -30f,
                        0f, 0f, 1.3f, 0f, -30f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
            }
            else -> {
                // Standard mode - balanced contrast
                ColorMatrix().apply {
                    set(floatArrayOf(
                        1.5f, 0f, 0f, 0f, -50f,
                        0f, 1.5f, 0f, 0f, -50f,
                        0f, 0f, 1.5f, 0f, -50f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
            }
        }
        
        val contrastFilter = ColorMatrixColorFilter(contrastMatrix)
        contrastPaint.colorFilter = contrastFilter
        contrastCanvas.drawBitmap(grayscaleBitmap, 0f, 0f, contrastPaint)
        grayscaleBitmap.recycle()

        return Pair(contrastBitmap, scaleFactor)
    }

    /**
     * Detect container info for a text block using OpenCV BubbleDetector.
     * Returns null if bitmap is unavailable, OpenCV fails, or detection is low-confidence.
     */
    private fun detectContainerInfo(bitmap: Bitmap?, bounds: Rect, imageWidth: Int, imageHeight: Int): TextContainerInfo? {
        if (bitmap == null || bitmap.isRecycled) return null
        return try {
            val result = bubbleDetector.detectBubble(bitmap, bounds, imageWidth, imageHeight)
            result.containerInfo?.takeIf { it.confidence >= 0.3f }
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Error detecting container", e)
            null
        }
    }

    enum class TextOrientation {
        HORIZONTAL,      // Văn bản ngang (trái sang phải)
        VERTICAL_RTL,    // Văn bản dọc (phải sang trái) - Kiểu manga Nhật
        VERTICAL_LTR     // Văn bản dọc (trái sang phải) - Kiểu Trung Quốc truyền thống
    }

    private fun rotateImageForVerticalText(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }


    private fun transformBoundsAfterRotation(
        bounds: Rect,
        originalWidth: Int,
        originalHeight: Int,
        rotationApplied: Int
    ): Rect {
        return when (rotationApplied) {
            90 -> {
                Rect(
                    bounds.top,                          // newLeft = top' 
                    originalHeight - bounds.right,       // newTop = H - right'
                    bounds.bottom,                       // newRight = bottom'
                    originalHeight - bounds.left         // newBottom = H - left'
                )
            }
            -90 -> {
                Rect(
                    originalWidth - bounds.bottom,       // newLeft = W - bottom'
                    bounds.left,                         // newTop = left'
                    originalWidth - bounds.top,          // newRight = W - top'
                    bounds.right                         // newBottom = right'
                )
            }
            else -> bounds
        }
    }
    private fun transformBlocksAfterRotation(
        blocks: List<TextBlockInfo>,
        originalWidth: Int,
        originalHeight: Int,
        rotationApplied: Int
    ): List<TextBlockInfo> {
        return blocks.map { block ->
            val newBounds = transformBoundsAfterRotation(
                block.bounds,
                originalWidth,
                originalHeight,
                rotationApplied
            )
            block.copy(
                bounds = newBounds,
                originalImageWidth = originalWidth,
                originalImageHeight = originalHeight
            )
        }
    }

    private fun detectTextOrientationAdvanced(bitmap: Bitmap): TextOrientation {
        // Thử quét nhanh với Japanese recognizer để detect orientation
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            // Sử dụng coroutine blocking vì đây là hàm private helper
            val textResult = kotlinx.coroutines.runBlocking {
                japaneseRecognizer.process(inputImage).await()
            }
            
            if (textResult.textBlocks.isEmpty()) return TextOrientation.HORIZONTAL
            
            // Tính aspect ratio của các text blocks
            var verticalBlockCount = 0
            var horizontalBlockCount = 0
            var totalBlocks = 0
            
            for (block in textResult.textBlocks) {
                val bounds = block.boundingBox ?: continue
                val width = bounds.width().toFloat()
                val height = bounds.height().toFloat()
                if (width <= 0 || height <= 0) continue
                
                totalBlocks++
                val aspectRatio = height / width
                
                if (aspectRatio > 1.8f) {
                    // Block cao hơn rộng nhiều => có thể là vertical text
                    verticalBlockCount++
                } else if (aspectRatio < 0.6f) {
                    // Block rộng hơn cao nhiều => horizontal text
                    horizontalBlockCount++
                }
            }
            
            // Nếu đa số blocks là vertical => văn bản dọc
            if (totalBlocks > 0 && verticalBlockCount > horizontalBlockCount && 
                verticalBlockCount >= totalBlocks * 0.4) {
                // Kiểm tra layout từ phải sang trái (đặc trưng manga Nhật)
                val sortedByRight = textResult.textBlocks
                    .mapNotNull { it.boundingBox }
                    .sortedByDescending { it.right }
                
                // Nếu blocks được sắp xếp từ phải sang trái => RTL
                return TextOrientation.VERTICAL_RTL
            }
            
        } catch (e: Exception) {
            Log.w("TranslationRepository", "Không thể detect text orientation: ${e.message}")
        }
        
        return TextOrientation.HORIZONTAL
    }

    private suspend fun recognizeTextWithRotationStrategy(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onlyPreview: Boolean = false,
        forceScript: String? = null
    ): Pair<String, List<TextBlockInfo>> = withContext(Dispatchers.IO) {
        // Detect orientation trước
        val detectedOrientation = detectTextOrientationAdvanced(bitmap)
        
        Log.i("TranslationRepository", "[ROTATION-STRATEGY] Detected orientation: $detectedOrientation")
        
        // Nếu là horizontal, quét bình thường
        if (detectedOrientation == TextOrientation.HORIZONTAL) {
            return@withContext recognizeText(bitmap, rotationDegrees, onlyPreview, forceScript)
        }
        
        // Nếu là vertical text, thử quét cả ảnh gốc và ảnh xoay
        // Sử dụng skipSort=true để lấy raw lines, sau đó merge với thứ tự RTL đúng
        val results = mutableListOf<Triple<String, List<TextBlockInfo>, Double>>()
        
        // 1. Quét ảnh gốc (skipSort để lấy raw lines)
        try {
            val (text, blocks) = recognizeText(bitmap, rotationDegrees, onlyPreview, forceScript, skipSort = true)
            val score = calculateOcrScore(text, blocks)
            results.add(Triple(text, blocks, score))
            Log.i("TranslationRepository", "[ROTATION-STRATEGY] Original: text length=${text.length}, blocks=${blocks.size}, score=$score")
        } catch (e: Exception) {
            Log.w("TranslationRepository", "[ROTATION-STRATEGY] Original scan failed: ${e.message}")
        }
        
        // 2. Quét ảnh xoay 90° CW (chuyển vertical thành horizontal)
        var rotated90: Bitmap? = null
        try {
            rotated90 = rotateImageForVerticalText(bitmap, 90)
            val (text90, blocks90) = recognizeText(rotated90, 0, onlyPreview, forceScript, skipSort = true)
            
            // Transform bounds về tọa độ gốc (đã xoay 90° CW)
            val transformedBlocks = transformBlocksAfterRotation(
                blocks90,
                bitmap.width,
                bitmap.height,
                90  // Góc đã xoay là 90° CW
            )
            
            val score = calculateOcrScore(text90, transformedBlocks)
            results.add(Triple(text90, transformedBlocks, score))
            Log.i("TranslationRepository", "[ROTATION-STRATEGY] Rotated 90°: text length=${text90.length}, blocks=${transformedBlocks.size}, score=$score")
        } catch (e: Exception) {
            Log.w("TranslationRepository", "[ROTATION-STRATEGY] Rotated 90° scan failed: ${e.message}")
        } finally {
            rotated90?.recycle()
        }
        
        // 3. Quét ảnh xoay -90° CCW (cho trường hợp đặc biệt)
        var rotatedMinus90: Bitmap? = null
        try {
            rotatedMinus90 = rotateImageForVerticalText(bitmap, -90)
            val (textMinus90, blocksMinus90) = recognizeText(rotatedMinus90, 0, onlyPreview, forceScript, skipSort = true)
            
            // Transform bounds về tọa độ gốc (đã xoay -90° CCW)
            val transformedBlocks = transformBlocksAfterRotation(
                blocksMinus90,
                bitmap.width,
                bitmap.height,
                -90  // Góc đã xoay là -90° CCW
            )
            
            val score = calculateOcrScore(textMinus90, transformedBlocks)
            results.add(Triple(textMinus90, transformedBlocks, score))
            Log.i("TranslationRepository", "[ROTATION-STRATEGY] Rotated -90°: text length=${textMinus90.length}, blocks=${transformedBlocks.size}, score=$score")
        } catch (e: Exception) {
            Log.w("TranslationRepository", "[ROTATION-STRATEGY] Rotated -90° scan failed: ${e.message}")
        } finally {
            rotatedMinus90?.recycle()
        }
        
        // Chọn kết quả tốt nhất dựa trên score
        val bestResult = results.maxByOrNull { it.third }
        
        if (bestResult != null) {
            Log.i("TranslationRepository", "[ROTATION-STRATEGY] Best result: score=${bestResult.third}, text length=${bestResult.first.length}")
            // Áp dụng sortVerticalTextBlocks trên raw lines với tọa độ gốc
            // để đảm bảo thứ tự merge đúng: phải → trái (RTL) cho văn bản dọc CJK
            val rawBlocks = bestResult.second
            val processedBlocks = sortVerticalTextBlocks(rawBlocks, bitmap)
            val sortedText = processedBlocks.joinToString("\n") { it.text }
            return@withContext Pair(sortedText, processedBlocks)
        }
        
        // Fallback: quét bình thường
        return@withContext recognizeText(bitmap, rotationDegrees, onlyPreview, forceScript)
    }

    private fun calculateOcrScore(text: String, blocks: List<TextBlockInfo>): Double {
        if (text.isEmpty()) return 0.0
        
        // Đếm số ký tự Asian (CJK)
        val asianPattern = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]")
        val asianCharCount = asianPattern.findAll(text).count()
        
        // Đếm số ký tự Latin và số
        val alphaNumPattern = Regex("[a-zA-Z0-9]")
        val alphaNumCount = alphaNumPattern.findAll(text).count()
        
        // Đếm số ký tự là nhiễu
        val noiseChars = text.count { c ->
            c in setOf('|', '/', '\\', '-', '_', '.', ',', '\'', '`', '"', '○', '◯', '・')
        }
        
        // Score = ưu tiên ký tự Asian + độ dài text + số blocks hợp lệ
        val asianScore = asianCharCount * 2.5
        val alphaScore = alphaNumCount * 1.5
        val textLengthScore = text.length / 10.0
        
        // Chỉ tính blocks có nội dung có nghĩa
        val validBlocks = blocks.filter { block ->
            block.text.trim().length >= MIN_TEXT_LENGTH &&
            block.bounds.width() * block.bounds.height() >= MIN_BLOCK_AREA
        }
        val blockScore = validBlocks.size * 5.0
        
        // Penalty nặng cho noise characters
        val noisePenalty = noiseChars * 1.5
        
        // Penalty cho quá nhiều ký tự không hợp lệ
        val invalidChars = text.count { c ->
            !asianPattern.matches(c.toString()) && 
            !alphaNumPattern.matches(c.toString()) && 
            !c.isWhitespace() && 
            c !in ".,!?、。！？「」『』（）()\"'"
        }
        val invalidPenalty = invalidChars * 0.3
        
        // Bonus for text with good CJK density (typical for manga)
        val cjkDensity = if (text.isNotEmpty()) asianCharCount.toDouble() / text.length else 0.0
        val densityBonus = if (cjkDensity > 0.3) 10.0 else 0.0
        
        return asianScore + alphaScore + textLengthScore + blockScore + densityBonus - noisePenalty - invalidPenalty
    }

    suspend fun recognizeTextRegionsForRemoval(imageUri: Uri): List<TextBlockInfo> = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            bitmap = MediaStore.Images.Media.getBitmap(application.contentResolver, imageUri)
            val rotationDegrees = getRotationDegrees(imageUri)
            val scaleFactors = listOf(1.0f, 1.3f)
            val recognizers = listOf(chineseRecognizer, japaneseRecognizer, koreanRecognizer, latinRecognizer)
            val detectedBlocks = mutableListOf<TextBlockInfo>()

            for (scale in scaleFactors) {
                for (recognizer in recognizers) {
                    var preprocessedBitmap: Bitmap? = null
                    try {
                        val enhanceMode = if (scale > 1.0f) 1 else 0
                        val (preBitmap, _) = preprocessImage(bitmap, scale, enhanceMode)
                        preprocessedBitmap = preBitmap
                        val inputImage = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
                        val result = recognizer.process(inputImage).await()

                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                for (element in line.elements) {
                                    val bounds = element.boundingBox ?: continue
                                    val scaledBounds = Rect(
                                        (bounds.left / scale).toInt().coerceIn(0, bitmap.width),
                                        (bounds.top / scale).toInt().coerceIn(0, bitmap.height),
                                        (bounds.right / scale).toInt().coerceIn(0, bitmap.width),
                                        (bounds.bottom / scale).toInt().coerceIn(0, bitmap.height)
                                    )
                                    val text = element.text.trim()
                                    if (text.isBlank() || scaledBounds.width() <= 1 || scaledBounds.height() <= 1) continue

                                    detectedBlocks.add(
                                        TextBlockInfo(
                                            text = text,
                                            originalText = text,
                                            bounds = scaledBounds,
                                            fontSize = scaledBounds.height().toFloat(),
                                            originalFontSize = scaledBounds.height().toFloat(),
                                            wordCountsPerLine = listOf(1),
                                            originalImageWidth = bitmap.width,
                                            originalImageHeight = bitmap.height,
                                            applyMerge = false
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("TranslationRepository", "[TEXT-REMOVAL-OCR] Element OCR failed scale=$scale", e)
                    } finally {
                        preprocessedBitmap?.recycle()
                    }
                }
            }

            val resultBlocks = detectedBlocks.sortedByDescending { it.bounds.width() * it.bounds.height() }
                .fold(mutableListOf<TextBlockInfo>()) { kept, candidate ->
                    val isDuplicate = kept.any { existing ->
                        val overlapLeft = maxOf(existing.bounds.left, candidate.bounds.left)
                        val overlapTop = maxOf(existing.bounds.top, candidate.bounds.top)
                        val overlapRight = minOf(existing.bounds.right, candidate.bounds.right)
                        val overlapBottom = minOf(existing.bounds.bottom, candidate.bounds.bottom)
                        val overlapArea = maxOf(0, overlapRight - overlapLeft) * maxOf(0, overlapBottom - overlapTop)
                        val candidateArea = (candidate.bounds.width() * candidate.bounds.height()).coerceAtLeast(1)
                        overlapArea.toFloat() / candidateArea > 0.5f
                    }
                    if (!isDuplicate) kept.add(candidate)
                    kept
                }
                .sortedWith(compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left })

            Log.i("TranslationRepository", "[TEXT-REMOVAL-OCR] Detected ${resultBlocks.size} element regions for $imageUri")
            resultBlocks
        } finally {
            bitmap?.recycle()
        }
    }

    suspend fun recognizeAndTranslateText(
        imageUri: Uri,
        mode: TranslationMode,
        onStatusUpdate: ((com.example.ocrmanga.data.models.TranslationStatus) -> Unit)? = null,
        previousTranslation: List<TextBlockInfo>? = null, // Bản dịch của ảnh trước để tham khảo
        isAncientMode: Boolean = false,
        reuseExistingBlocks: List<TextBlockInfo>? = null, // Nếu không null, dùng lại blocks đã có (vị trí, text gốc) thay vì OCR mới
        onOcrCompleted: (suspend (List<TextBlockInfo>) -> Unit)? = null // Callback khi vừa OCR xong (trước khi dịch)
    ): Triple<String, List<TextBlockInfo>, String> = withContext(Dispatchers.IO) {
        val rotationDegrees = getRotationDegrees(imageUri)
        Log.i("TranslationRepository", "[PIPELINE-START] uri=$imageUri, mode=$mode, rotation=$rotationDegrees")
        
        if (mode == TranslationMode.OFF) {
            //log.i("TranslationRepository", "Chế độ dịch đã tắt, bỏ qua việc dịch cho $imageUri")
            return@withContext Triple("", emptyList(), "zh")
        }

        // Early check: if user selected Gemini or Mistral mode but there are no API keys in DB,
        // notify immediately and skip long-running OCR/translation work.
        if (mode == TranslationMode.GEMINI && !hasGeminiApiKeys()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "Không có API key Gemini. Vui lòng thêm ít nhất một API key Gemini trong cài đặt để dùng tính năng dịch Gemini.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext Triple("", emptyList(), "zh")
        }

        if (mode == TranslationMode.MISTRAL && !hasMistralApiKeys()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "Không có API key Mistral. Vui lòng thêm ít nhất một API key Mistral trong cài đặt để dùng tính năng dịch Mistral.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext Triple("", emptyList(), "zh")
        }

        if (mode == TranslationMode.ZAI && !hasZAiApiKeys()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "Không có API key Z.AI. Vui lòng thêm ít nhất một API key Z.AI trong cài đặt để dùng tính năng dịch Z.AI.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return@withContext Triple("", emptyList(), "zh")
        }

        val cacheKey = "$imageUri-$mode"
        cache[cacheKey]?.let {
            //log.i("TranslationRepository", "Tìm thấy kết quả trong cache cho $imageUri: ${it.first}")
            // Lưu vào session nếu lấy từ cache
            lastTranslationSession.add(Pair(imageUri, Pair("(cache)", it.first)))
            return@withContext Triple(it.first, it.second, detectLanguage(it.first) ?: "zh")
        }

        // === XỬ LÝ REUSE OCR: Nếu có existing blocks, dùng trực tiếp không OCR lại ===
        if (!reuseExistingBlocks.isNullOrEmpty()) {
            Log.i("TranslationRepository", "[REUSE-OCR] Using ${reuseExistingBlocks.size} existing blocks, skipping OCR")
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.TRANSLATING)
            }
            try {
                // Lấy danh sách văn bản gốc cho đầu vào
                val sourceTexts = reuseExistingBlocks.map { it.originalText ?: it.text }
                val joinedSourceText = sourceTexts.joinToString("\n")
                
                // Gọi translate - dùng đúng hàm MultiScale cho các model để đảm bảo Block # format nhất quán
                val translatedLines: List<String> = when (mode) {
                    TranslationMode.MISTRAL -> {
                        val fakeOcrResults = listOf(Pair(1.0f, joinedSourceText))
                        val blockListForTranslation = reuseExistingBlocks.map { b ->
                            b.copy(text = b.originalText ?: b.text)
                        }
                        translateWithMistralMultiScale(
                            textBlocks = blockListForTranslation,
                            ocrResults = fakeOcrResults,
                            sourceLang = detectLanguage(joinedSourceText) ?: "zh",
                            targetLang = "vi",
                            previousTranslation = previousTranslation,
                            isAncientMode = isAncientMode,
                            skipDetailedLogs = false
                        ) ?: sourceTexts.map { "" }
                    }
                    TranslationMode.GEMINI -> {
                        val fakeOcrResults = listOf(Pair(1.0f, joinedSourceText))
                        val blockListForTranslation = reuseExistingBlocks.map { b ->
                            b.copy(text = b.originalText ?: b.text)
                        }
                        translateWithGeminiMultiScale(
                            textBlocks = blockListForTranslation,
                            ocrResults = fakeOcrResults,
                            sourceLang = detectLanguage(joinedSourceText) ?: "zh",
                            targetLang = "vi",
                            previousTranslation = previousTranslation,
                            isAncientMode = isAncientMode,
                            skipDetailedLogs = false
                        ) ?: sourceTexts.map { "" }
                    }
                    TranslationMode.ZAI -> {
                        val fakeOcrResults = listOf(Pair(1.0f, joinedSourceText))
                        val blockListForTranslation = reuseExistingBlocks.map { b ->
                            b.copy(text = b.originalText ?: b.text)
                        }
                        translateWithZAiMultiScale(
                            textBlocks = blockListForTranslation,
                            ocrResults = fakeOcrResults,
                            sourceLang = detectLanguage(joinedSourceText) ?: "zh",
                            targetLang = "vi",
                            previousTranslation = previousTranslation,
                            isAncientMode = isAncientMode,
                            skipDetailedLogs = false
                        ) ?: sourceTexts.map { "" }
                    }
                    else -> {
                        val onlineRaw = translateTextOnline(joinedSourceText, "zh")
                        onlineRaw.split("\n").map { it.trim() }
                    }
                }
                
                val modelTag = when(mode) {
                    TranslationMode.MISTRAL -> "MISTRAL"
                    TranslationMode.GEMINI -> "GEMINI"
                    TranslationMode.ZAI -> "ZAI"
                    else -> "OCR"
                }
                Log.i("TranslationRepository", "[REUSE-$modelTag-PARSE] Got ${translatedLines.size} translated lines for ${reuseExistingBlocks.size} blocks")

                val finalBlocks = reuseExistingBlocks.mapIndexed { index, block ->
                    val cleanOriginalText = block.originalText ?: block.text
                    block.copy(
                        text = translatedLines.getOrNull(index) ?: "",
                        originalText = cleanOriginalText,
                        bounds = android.graphics.Rect(block.bounds),
                        applyMerge = false
                    )
                }
                val fullText = finalBlocks.joinToString("\n") { it.text }
                
                cache[cacheKey] = fullText to finalBlocks


                // LOG CHI TIẾT KẾT QUẢ REUSE-OCR
                Log.i("TranslationRepository", "===== KẾT QUẢ DỊCH (REUSE-$modelTag) =====")
                finalBlocks.forEachIndexed { index, block ->
                    Log.i("TranslationRepository", "[REUSE-$modelTag] #$index:")
                    Log.i("TranslationRepository", "    + Bounds: ${block.bounds}")
                    Log.i("TranslationRepository", "    + Gốc: '${block.originalText}'")
                    Log.i("TranslationRepository", "    + Dịch: '${block.text}'")
                }
                Log.i("TranslationRepository", "====================================")

                return@withContext Triple(fullText, finalBlocks, detectLanguage(fullText) ?: "zh")
            } catch (e: Exception) {
                Log.e("TranslationRepository", "Error in REUSE-OCR pipeline", e)
                // Fallback to normal OCR below if reuse fails
            }
        }

        // Thông báo: bắt đầu quét ảnh (OCR)
        withContext(Dispatchers.Main) {
            onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.SCANNING)
        }

        var bitmap: Bitmap? = null
        var fullText: String = ""
        var resultText: String = ""
        var translatedBlocks: List<TextBlockInfo> = emptyList()
        var sourceLanguage: String = "zh"
        var detectedScript: String? = null
        var hasOCR = false
        try {
            bitmap = MediaStore.Images.Media.getBitmap(application.contentResolver, imageUri)
            // rotationDegrees đã được tính ở trên
            //log.i("TranslationRepository", "[INPUT] Đang xử lý ảnh: $imageUri với góc xoay: $rotationDegrees")

            // Phát hiện loại ngôn ngữ trước khi quét (dựa trên bitmap)
            val previewText = try {
                val (previewText, _) = recognizeText(bitmap, rotationDegrees, onlyPreview = true)
                previewText
            } catch (e: Exception) {
                ""
            }
            detectedScript = detectLanguage(previewText) ?: "zh"
            //log.i("TranslationRepository", "[PREVIEW] Phát hiện script: $detectedScript")

            // Quét chính xác với recognizer phù hợp
            // Sử dụng chiến lược xoay ảnh cho văn bản dọc (tiếng Nhật/Trung/Hàn)
            val useRotationStrategy = detectedScript in listOf("ja", "zh", "ko")
            val (rawText, textBlocks) = if (useRotationStrategy) {
                Log.i("TranslationRepository", "[ROTATION] Sử dụng chiến lược xoay ảnh cho script: $detectedScript")
                recognizeTextWithRotationStrategy(bitmap, rotationDegrees, forceScript = detectedScript)
            } else {
                recognizeText(bitmap, rotationDegrees, forceScript = detectedScript)
            }
            
            // LOG OCR RESULTS
            Log.i("TranslationRepository", "===== KẾT QUẢ QUÉT OCR (${textBlocks.size} blocks) =====")
            textBlocks.forEachIndexed { index, block ->
                val textColorHex = block.originalTextColor?.let { String.format("#%08X", it) } ?: "null"
                val overlayColorHex = block.averageBackgroundColor?.let { String.format("#%08X", it) } ?: "null"
                val containerTypeStr = block.containerInfo?.type?.name ?: "UNKNOWN"
                Log.i("TranslationRepository", "[OCR-BLOCK] #$index: Text='${block.text}'")
                Log.i("TranslationRepository", "    + Container: $containerTypeStr")
                Log.i("TranslationRepository", "    + Color: Text=$textColorHex, Overlay=$overlayColorHex")
                Log.i("TranslationRepository", "    + Font: originalFontSize=${block.originalFontSize}, calculatedFontSize=${block.fontSize}")
                Log.i("TranslationRepository", "    + Bounds: ${block.bounds}")
            }
            Log.i("TranslationRepository", "================================================")
            fullText = rawText
            hasOCR = true
            //log.i("TranslationRepository", "[INPUT] Văn bản gốc: $fullText, số khối: ${textBlocks.size}")

            if (fullText.isEmpty()) {
                Log.w("TranslationRepository", "Không nhận diện được văn bản trong $imageUri")
                // Lưu session với text rỗng
                lastTranslationSession.add(Pair(imageUri, Pair("", "")))
                return@withContext Triple("", emptyList(), "zh")
            }

            sourceLanguage = detectLanguage(fullText) ?: "zh"
            //log.i("TranslationRepository", "Ngôn ngữ nguồn được phát hiện: $sourceLanguage")
            
            // Gọi callback khi OCR xong (để ViewerViewModel có thể lưu vào DB ngay)
            onOcrCompleted?.invoke(textBlocks)

            // Thông báo: bắt đầu dịch văn bản
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.TRANSLATING)
            }

            // --- LOGIC MỚI CHO MISTRAL VÀ Z.AI: THU THẬP TẤT CẢ KẾT QUẢ OCR TỪ CÁC SCALE ---
            if (mode == TranslationMode.MISTRAL || mode == TranslationMode.ZAI || mode == TranslationMode.OCR) {
                // Thu thập tất cả kết quả OCR từ các scale khác nhau
                val allOcrResults = recognizeTextAllScales(bitmap, rotationDegrees, forceScript = detectedScript)

                if (allOcrResults.isEmpty()) {
                    Log.w("TranslationRepository", "Không có kết quả OCR nào từ các scale")
                    lastTranslationSession.add(Pair(imageUri, Pair("", "")))
                    return@withContext Triple("", emptyList(), "zh")
                }

                val blocksWithBubble = assignSpeechBubblesToBlocks(textBlocks)
                val mergedBlocks = mergeBlocksByBubble(blocksWithBubble, bitmap!!)

                // Nếu chỉ mode OCR hoặc EXTERNAL thì trả về luôn không dịch
                if (mode == TranslationMode.OCR || mode == TranslationMode.EXTERNAL) {
                    return@withContext Triple(fullText, mergedBlocks, sourceLanguage)
                }

                // Gửi tất cả kết quả cho AI để tổng hợp và dịch
                val translatedTexts = if (mode == TranslationMode.MISTRAL) {
                    translateWithMistralMultiScale(mergedBlocks, allOcrResults, sourceLanguage, "vi", previousTranslation, isAncientMode)
                } else {
                    translateWithZAiMultiScale(mergedBlocks, allOcrResults, sourceLanguage, "vi", previousTranslation, isAncientMode)
                }

                if (translatedTexts.isNullOrEmpty()) {
                    Log.w("TranslationRepository", "${mode.name} không trả về kết quả dịch")
                    lastTranslationSession.add(Pair(imageUri, Pair(fullText, "")))
                    return@withContext Triple("", emptyList(), "zh")
                }

                val finalTranslatedTexts = translatedTexts ?: emptyList()

                // Thông báo: đang phân phối bản dịch trở lại tọa độ
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.DISTRIBUTING)
                }

                // Lấy font mặc định từ cài đặt
                val defaultSettings = getDefaultFontSettings()

                // Ánh xạ các bản dịch vào các text blocks tương ứng
                val blocks = mutableListOf<TextBlockInfo>()
                mergedBlocks.forEachIndexed { index, block ->
                    // Lấy văn bản dịch tương ứng với block này
                    val translatedTextForBlock = finalTranslatedTexts.getOrNull(index) ?: block.text

                    // Post-process bản dịch
                    val naturalText = postProcessTranslation(translatedTextForBlock)

                    val isVertical = block.isVertical
                    val reformattedText = if (!isVertical && block.wordCountsPerLine != null) {
                        val words = naturalText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val wordCounts = block.wordCountsPerLine
                        val reformattedLines = mutableListOf<String>()
                        var wordIndex = 0
                        for (wordCount in wordCounts) {
                            if (wordIndex >= words.size) break
                            val lineWords = words.subList(wordIndex, minOf(wordIndex + wordCount, words.size))
                            reformattedLines.add(lineWords.joinToString(" "))
                            wordIndex += wordCount
                        }
                        val maxWordsPerLine = wordCounts.lastOrNull() ?: 5
                        while (wordIndex < words.size) {
                            val remainingWords = words.subList(wordIndex, minOf(wordIndex + maxWordsPerLine, words.size))
                            reformattedLines.add(remainingWords.joinToString(" "))
                            wordIndex += maxWordsPerLine
                        }
                        reformattedLines.joinToString("\n")
                    } else {
                        naturalText
                    }

                    // Tính toán fontSize mới để vừa với overlay
                    val adjustedFontSize = calculateAdjustedFontSize(
                        reformattedText,
                        block.text,
                        block.bounds,
                        block.fontSize,
                        isVertical
                    )

                    val newBounds = adjustBoundsForTranslatedText(reformattedText, block.bounds, adjustedFontSize, 1.0f)
                    val newBlock = block.copy(
                        text = reformattedText,
                        originalText = block.text,
                        bounds = newBounds,
                        fontSize = adjustedFontSize,
                        fontFamily = defaultSettings["fontFamily"] as? String ?: "Default",
                        lineSpacing = defaultSettings["lineSpacing"] as? Float ?: 1.0f,
                        textBoldness = defaultSettings["textBoldness"] as? Float ?: 1.0f,
                        overlayAlpha = defaultSettings["overlayAlpha"] as? Float ?: 0.8f,
                        overlaySaturation = defaultSettings["overlayBrightness"] as? Float ?: 1.0f,
                        customBorderColor = (defaultSettings["borderColor"] as? String)?.let { android.graphics.Color.parseColor(it) },
                        borderThickness = defaultSettings["borderThickness"] as? Float ?: 2.0f,
                        customTextColor = block.originalTextColor, // preserve OCR-detected text color
                        applyMerge = true
                    )
                    try {
                        val input = block.text
                        val output = newBlock.text
                        val bounds = newBlock.bounds
                        val transLabel = if (mode == TranslationMode.MISTRAL) "TRANS-MISTRAL" else "TRANS-ZAI"
                        Log.i("TranslationRepository", "[$transLabel] Block #${index + 1}:")
                        Log.i("TranslationRepository", "    + Input : '$input'")
                        Log.i("TranslationRepository", "    + Output: '$output'")
                    } catch (_: Exception) { }
                    blocks.add(newBlock)
                }
                
                resultText = blocks.joinToString("\n") { it.text }
                translatedBlocks = blocks
                
                val result = Triple(resultText, translatedBlocks, sourceLanguage)
                cache[cacheKey] = resultText to translatedBlocks
                lastTranslationSession.add(Pair(imageUri, Pair(fullText, resultText)))
                return@withContext result
            }

            // --- LOGIC MỚI CHO GEMINI: THU THẬP TẤT CẢ KẾT QUẢ OCR TỪ CÁC SCALE ---
            if (mode == TranslationMode.GEMINI) {
                // Thu thập tất cả kết quả OCR từ các scale khác nhau
                val allOcrResults = recognizeTextAllScales(bitmap, rotationDegrees, forceScript = detectedScript)
                
                if (allOcrResults.isEmpty()) {
                    Log.w("TranslationRepository", "Không có kết quả OCR nào từ các scale")
                    lastTranslationSession.add(Pair(imageUri, Pair("", "")))
                    return@withContext Triple("", emptyList(), "zh")
                }
                
                // Gộp và merge các text blocks giống các mode khác
                val blocksWithBubble = assignSpeechBubblesToBlocks(textBlocks)
                val mergedBlocks = mergeBlocksByBubble(blocksWithBubble, bitmap!!)
                
                //Log.i("TranslationRepository", "[GEMINI] Số blocks cần dịch: ${mergedBlocks.size}")
                // Gửi tất cả kết quả cho Gemini AI để tổng hợp và dịch, kèm theo bản dịch ảnh trước (nếu có)
                var translatedTexts = translateWithGeminiMultiScale(mergedBlocks, allOcrResults, sourceLanguage, "vi", previousTranslation, isAncientMode)

                if (translatedTexts.isNullOrEmpty()) {
                    Log.w("TranslationRepository", "Gemini không trả về kết quả dịch")
                    lastTranslationSession.add(Pair(imageUri, Pair(fullText, "")))
                    return@withContext Triple("", emptyList(), "zh")
                }

                val finalTranslatedTexts = translatedTexts ?: emptyList()
                
                //Log.i("TranslationRepository", "[GEMINI] Số bản dịch nhận được: ${translatedTexts.size}")
                
                // Thông báo: đang phân phối bản dịch trở lại tọa độ
                withContext(Dispatchers.Main) {
                    onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.DISTRIBUTING)
                }
                
                // Lấy font mặc định từ cài đặt
                val defaultSettingsGemini = getDefaultFontSettings()
                
                // Ánh xạ các bản dịch vào các text blocks tương ứng
                val blocks = mutableListOf<TextBlockInfo>()
                mergedBlocks.forEachIndexed { index, block ->
                    // Lấy văn bản dịch tương ứng với block này
                    val translatedTextForBlock = finalTranslatedTexts.getOrNull(index) ?: block.text
                    
                    // Post-process bản dịch
                    val naturalText = postProcessTranslation(translatedTextForBlock)
                    
                    val isVertical = block.isVertical
                    val reformattedText = if (!isVertical && block.wordCountsPerLine != null) {
                        val words = naturalText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val wordCounts = block.wordCountsPerLine
                        val reformattedLines = mutableListOf<String>()
                        var wordIndex = 0
                        for (wordCount in wordCounts) {
                            if (wordIndex >= words.size) break
                            val lineWords = words.subList(wordIndex, minOf(wordIndex + wordCount, words.size))
                            reformattedLines.add(lineWords.joinToString(" "))
                            wordIndex += wordCount
                        }
                        val maxWordsPerLine = wordCounts.lastOrNull() ?: 5
                        while (wordIndex < words.size) {
                            val remainingWords = words.subList(wordIndex, minOf(wordIndex + maxWordsPerLine, words.size))
                            reformattedLines.add(remainingWords.joinToString(" "))
                            wordIndex += maxWordsPerLine
                        }
                        reformattedLines.joinToString("\n")
                    } else {
                        naturalText
                    }
                    
                    // Tính toán fontSize mới để vừa với overlay
                    val adjustedFontSize = calculateAdjustedFontSize(
                        reformattedText,
                        block.text,
                        block.bounds,
                        block.fontSize,
                        isVertical
                    )
                    
                    //Log.i("TranslationRepository", "  - FontSize đã điều chỉnh: $adjustedFontSize")
                    
                    val newBounds = adjustBoundsForTranslatedText(reformattedText, block.bounds, adjustedFontSize, 1.0f)
                    val newBlock = block.copy(
                        text = reformattedText,
                        originalText = block.text,
                        bounds = newBounds,
                        fontSize = adjustedFontSize,
                        fontFamily = defaultSettingsGemini["fontFamily"] as? String ?: "Default",
                        lineSpacing = defaultSettingsGemini["lineSpacing"] as? Float ?: 1.0f,
                        textBoldness = defaultSettingsGemini["textBoldness"] as? Float ?: 1.0f,
                        overlayAlpha = defaultSettingsGemini["overlayAlpha"] as? Float ?: 0.8f,
                        overlaySaturation = defaultSettingsGemini["overlayBrightness"] as? Float ?: 1.0f,
                        customBorderColor = (defaultSettingsGemini["borderColor"] as? String)?.let { android.graphics.Color.parseColor(it) },
                        borderThickness = defaultSettingsGemini["borderThickness"] as? Float ?: 2.0f,
                        customTextColor = block.originalTextColor, // preserve OCR-detected text color
                        applyMerge = true
                    )
                    try {
                        val input = block.text
                        val output = newBlock.text
                        val bounds = newBlock.bounds
                        Log.i("TranslationRepository", "[TRANS-GEMINI] Block #${index + 1}:")
                        Log.i("TranslationRepository", "    + Input : '$input'")
                        Log.i("TranslationRepository", "    + Output: '$output'")
                        Log.i("TranslationRepository", "    + Bounds: $bounds")
                    } catch (_: Exception) { }
                    blocks.add(newBlock)
                }
                
                resultText = blocks.joinToString("\n") { it.text }
                translatedBlocks = blocks
                
                val result = Triple(resultText, translatedBlocks, sourceLanguage)
                cache[cacheKey] = resultText to translatedBlocks
                lastTranslationSession.add(Pair(imageUri, Pair(fullText, resultText)))
                return@withContext result
            }

            // Lấy tất cả cài đặt mặc định từ cài đặt cho các mode khác
            val defaultSettingsOther = getDefaultFontSettings()
            
            val blocksWithBubble = assignSpeechBubblesToBlocks(textBlocks)
            val mergedBlocks = mergeBlocksByBubble(blocksWithBubble, bitmap!!)
            val blocks = mutableListOf<TextBlockInfo>()
            // Sử dụng coroutineScope để dịch song song các block
            kotlinx.coroutines.coroutineScope {
                val deferredBlocks = mergedBlocks.map { block ->
                    async {
                        // Log.i("TranslationRepository", "Khối văn bản gốc: ${block.text}, tọa độ: left=${block.bounds.left}, top=${block.bounds.top}") // Tắt log để tăng tốc
                        var translatedText = when (mode) {
                            TranslationMode.OFFLINE -> translateTextOffline(block.text, sourceLanguage)
                            TranslationMode.ONLINE -> translateTextOnline(block.text, sourceLanguage)
                            TranslationMode.GEMINI -> translateTextWithGemini(block.text, sourceLanguage)
                            TranslationMode.MISTRAL -> translateWithMistral(block.text, sourceLanguage, "vi") ?: ""
                            TranslationMode.ZAI -> translateWithZAi(block.text, sourceLanguage, "vi") ?: ""
                            else -> block.text
                        }
                        // Log.i("TranslationRepository", "Văn bản đã dịch lần 1: $translatedText") // Tắt log để tăng tốc
                        // Tối ưu: chỉ kiểm tra lần 2 nếu text quá ngắn (có thể bị dịch sai)
                        if (translatedText != null && translatedText.length > 5) {
                            val detectedAfterTranslation = detectLanguage(translatedText) ?: "vi"
                            if (detectedAfterTranslation != "vi" && mode != TranslationMode.OFF) {
                                //log.i("TranslationRepository", "Phát hiện cụm không phải tiếng Việt: $translatedText, ngôn ngữ: $detectedAfterTranslation")
                                translatedText = when (mode) {
                                    TranslationMode.OFFLINE -> translateTextOffline(translatedText, detectedAfterTranslation)
                                    TranslationMode.ONLINE -> translateTextOnline(translatedText, detectedAfterTranslation)
                                    TranslationMode.GEMINI -> translateTextWithGemini(translatedText, detectedAfterTranslation)
                                    TranslationMode.MISTRAL -> translateWithMistral(translatedText, detectedAfterTranslation, "vi") ?: translatedText
                                    TranslationMode.ZAI -> translateWithZAi(translatedText, detectedAfterTranslation, "vi") ?: translatedText
                                    else -> translatedText
                                }
                            }
                        }
                        // Log.i("TranslationRepository", "Văn bản sau kiểm tra lần 2: $translatedText") // Tắt log để tăng tốc
                        val naturalText = translatedText?.let { postProcessTranslation(it) }
                        // Log.i("TranslationRepository", "Văn bản tự nhiên sau xử lý: $naturalText") // Tắt log để tăng tốc
                        val isVertical = block.isVertical
                        val reformattedText = if (!isVertical && block.wordCountsPerLine != null) {
                            val words = naturalText?.split(Regex("\\s+")).orEmpty().filter { it.isNotEmpty() }
                            val wordCounts = block.wordCountsPerLine
                            val reformattedLines = mutableListOf<String>()
                            var wordIndex = 0
                            for (wordCount in wordCounts) {
                                if (wordIndex >= words.size) break
                                val lineWords = words.subList(wordIndex, minOf(wordIndex + wordCount, words.size))
                                reformattedLines.add(lineWords.joinToString(" "))
                                wordIndex += wordCount
                            }
                            val maxWordsPerLine = wordCounts.lastOrNull() ?: 5
                            while (wordIndex < words.size) {
                                val remainingWords = words.subList(wordIndex, minOf(wordIndex + maxWordsPerLine, words.size))
                                reformattedLines.add(remainingWords.joinToString(" "))
                                wordIndex += maxWordsPerLine
                            }
                            reformattedLines.joinToString("\n")
                        } else {
                            naturalText
                        }
                        //log.i("TranslationRepository", "Văn bản sau định dạng lại: $reformattedText")
                        val newBounds = adjustBoundsForTranslatedText(reformattedText.orEmpty(), block.bounds, block.fontSize, 1.0f)
                        block.copy(
                            text = reformattedText.orEmpty(),
                            originalText = block.text,
                            bounds = newBounds,
                            fontFamily = defaultSettingsOther["fontFamily"] as? String ?: "Default",
                            lineSpacing = defaultSettingsOther["lineSpacing"] as? Float ?: 1.0f,
                            textBoldness = defaultSettingsOther["textBoldness"] as? Float ?: 1.0f,
                            overlayAlpha = defaultSettingsOther["overlayAlpha"] as? Float ?: 0.8f,
                            overlaySaturation = defaultSettingsOther["overlayBrightness"] as? Float ?: 1.0f,
                            customBorderColor = (defaultSettingsOther["borderColor"] as? String)?.let { android.graphics.Color.parseColor(it) },
                            borderThickness = defaultSettingsOther["borderThickness"] as? Float ?: 2.0f,
                            customTextColor = block.originalTextColor, // preserve OCR-detected text color
                            applyMerge = true
                        )
                    }
                }
                val addedBlocks = deferredBlocks.awaitAll()
                blocks.addAll(addedBlocks)
                try {
                    addedBlocks.forEachIndexed { ai, b ->
                        val input = b.originalText ?: "N/A"
                        val output = b.text
                        val bounds = b.bounds
                        val transLabel = when (mode) {
                            TranslationMode.GEMINI -> "TRANS-GEMINI"
                            TranslationMode.MISTRAL -> "TRANS-MISTRAL"
                            TranslationMode.ZAI -> "TRANS-ZAI"
                            TranslationMode.OFFLINE -> "TRANS-OFFLINE"
                            TranslationMode.ONLINE -> "TRANS-ONLINE"
                            else -> "TRANS-OTHER"
                        }
                        Log.i("TranslationRepository", "[$transLabel] Block #${ai + 1}:")
                        Log.i("TranslationRepository", "    + Input : '$input'")
                        Log.i("TranslationRepository", "    + Output: '$output'")
                    }
                } catch (_: Exception) { }
            }

            // Thông báo: đang phân phối bản dịch trở lại tọa độ
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke(com.example.ocrmanga.data.models.TranslationStatus.DISTRIBUTING)
            }

            resultText = blocks.joinToString("\n") { it.text }
            translatedBlocks = blocks
            val detectedFinal = detectLanguage(resultText) ?: ""
            if (detectedFinal != "vi") {
                Log.w("TranslationRepository", "Kết quả cuối chưa phải tiếng Việt, thử lại OCR và dịch lại...")
                // Thực hiện lại OCR và dịch lại 1 lần nữa
                val (rawText2, textBlocks2) = recognizeText(bitmap, rotationDegrees)
                val blocks2 = mutableListOf<TextBlockInfo>()
                val blocksWithBubble2 = assignSpeechBubblesToBlocks(textBlocks2)
                val mergedBlocks2 = mergeBlocksByBubble(blocksWithBubble2, bitmap!!)
                for (block in mergedBlocks2) {
                    var translatedText = when (mode) {
                        TranslationMode.OFFLINE -> translateTextOffline(block.text, sourceLanguage)
                        TranslationMode.ONLINE -> translateTextOnline(block.text, sourceLanguage)
                        TranslationMode.GEMINI -> translateTextWithGemini(block.text, sourceLanguage)
                        TranslationMode.MISTRAL -> translateWithMistral(block.text, sourceLanguage, "vi") ?: ""
                        TranslationMode.ZAI -> translateWithZAi(block.text, sourceLanguage, "vi") ?: ""
                        else -> block.text
                    }
                    val detectedAfterTranslation = detectLanguage(translatedText) ?: "vi"
                    if (detectedAfterTranslation != "vi" && mode != TranslationMode.OFF) {
                        translatedText = when (mode) {
                            TranslationMode.OFFLINE -> translateTextOffline(translatedText, detectedAfterTranslation)
                            TranslationMode.ONLINE -> translateTextOnline(translatedText, detectedAfterTranslation)
                            TranslationMode.GEMINI -> translateTextWithGemini(translatedText, detectedAfterTranslation)
                            TranslationMode.MISTRAL -> translateWithMistral(translatedText, detectedAfterTranslation, "vi") ?: translatedText
                            TranslationMode.ZAI -> translateWithZAi(translatedText, detectedAfterTranslation, "vi") ?: translatedText
                            else -> translatedText
                        }
                    }
                    val naturalText = postProcessTranslation(translatedText)
                    val isVertical = block.isVertical
                    val reformattedText = if (!isVertical && block.wordCountsPerLine != null) {
                        val words = naturalText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val wordCounts = block.wordCountsPerLine
                        val reformattedLines = mutableListOf<String>()
                        var wordIndex = 0
                        for (wordCount in wordCounts ?: emptyList()) {
                            if (wordIndex >= words.size) break
                            val lineWords = words.subList(wordIndex, minOf(wordIndex + wordCount, words.size))
                            reformattedLines.add(lineWords.joinToString(" "))
                            wordIndex += wordCount
                        }
                        val maxWordsPerLine = wordCounts?.lastOrNull() ?: 5
                        while (wordIndex < words.size) {
                            val remainingWords = words.subList(wordIndex, minOf(wordIndex + maxWordsPerLine, words.size))
                            reformattedLines.add(remainingWords.joinToString(" "))
                            wordIndex += maxWordsPerLine
                        }
                        reformattedLines.joinToString("\n")
                    } else {
                        naturalText
                    }
                    val newBounds = adjustBoundsForTranslatedText(reformattedText, block.bounds, block.fontSize, 1.0f)
                    blocks2.add(block.copy(
                        text = reformattedText,
                        originalText = block.text,
                        bounds = newBounds,
                        fontFamily = defaultSettingsOther["fontFamily"] as? String ?: "Default",
                        lineSpacing = defaultSettingsOther["lineSpacing"] as? Float ?: 1.0f,
                        textBoldness = defaultSettingsOther["textBoldness"] as? Float ?: 1.0f,
                        overlayAlpha = defaultSettingsOther["overlayAlpha"] as? Float ?: 0.8f,
                        overlaySaturation = defaultSettingsOther["overlayBrightness"] as? Float ?: 1.0f,
                        customBorderColor = (defaultSettingsOther["borderColor"] as? String)?.let { android.graphics.Color.parseColor(it) },
                        borderThickness = defaultSettingsOther["borderThickness"] as? Float ?: 2.0f,
                        customTextColor = block.originalTextColor, // preserve OCR-detected text color
                        applyMerge = true
                    ))
                }
                val resultText2 = blocks2.joinToString("\n") { it.text }
                try {
                    blocks2.forEachIndexed { bi, b ->
                        val input = b.originalText ?: "N/A"
                        val output = b.text
                        val bounds = b.bounds
                        val retryLabel = when (mode) {
                            TranslationMode.GEMINI -> "TRANS-GEMINI-RETRY"
                            TranslationMode.MISTRAL -> "TRANS-MISTRAL-RETRY"
                            TranslationMode.ZAI -> "TRANS-ZAI-RETRY"
                            else -> "TRANS-RETRY"
                        }
                        Log.i("TranslationRepository", "[$retryLabel] Block #${bi + 1}:")
                        Log.i("TranslationRepository", "    + Input : '$input'")
                        Log.i("TranslationRepository", "    + Output: '$output'")
                    }
                } catch (_: Exception) { }
                val detectedFinal2 = detectLanguage(resultText2) ?: ""
                if (detectedFinal2 == "vi") {
                    resultText = resultText2
                    translatedBlocks = blocks2
                    //log.i("TranslationRepository", "Dịch lại thành công ra tiếng Việt.")
                } else {
                    Log.w("TranslationRepository", "Dịch lại vẫn không ra tiếng Việt, trả về kết quả tốt nhất.")
                }
            }
            val result = Triple(resultText, translatedBlocks, sourceLanguage)
            cache[cacheKey] = resultText to translatedBlocks
            //log.i("TranslationRepository", "[OUTPUT] Kết quả cuối cùng: $resultText")
            // Kiểm tra lại các block chưa dịch ra tiếng Việt, thử lại với model khác nếu cần
            val finalBlocks = translatedBlocks.map { block ->
                val lang = detectLanguage(block.text) ?: ""
                if (lang != "vi" && mode != TranslationMode.OFF) {
                    // Thử lại với model khác
                    val retryText = when (mode) {
                        TranslationMode.OFFLINE -> translateTextOnline(block.text, sourceLanguage)
                        TranslationMode.ONLINE -> translateTextWithGemini(block.text, sourceLanguage)
                        TranslationMode.GEMINI -> translateTextOffline(block.text, sourceLanguage)
                        TranslationMode.MISTRAL -> translateWithMistral(block.text, sourceLanguage, "vi") ?: block.text
                        TranslationMode.ZAI -> translateWithZAi(block.text, sourceLanguage, "vi") ?: block.text
                        else -> block.text
                    }
                    val retryLang = detectLanguage(retryText) ?: ""
                    if (retryLang == "vi") {
                        block.copy(text = postProcessTranslation(retryText), originalText = block.originalText ?: block.text, applyMerge = true)
                    } else {
                        block.copy(originalText = block.originalText ?: block.text, applyMerge = true)
                    }
                } else {
                    block.copy(applyMerge = true)
                }
            }
            val finalResultText = finalBlocks.joinToString("\n") { it.text }
            lastTranslationSession.add(Pair(imageUri, Pair(fullText, finalResultText)))
            return@withContext Triple(finalResultText, finalBlocks, sourceLanguage)
        } catch (e: IOException) {
            Log.e("TranslationRepository", "Lỗi IO với $imageUri", e)
            lastTranslationSession.add(Pair(imageUri, Pair(fullText, "")))
            return@withContext Triple("", emptyList(), "zh")
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Lỗi xử lý $imageUri", e)
            lastTranslationSession.add(Pair(imageUri, Pair(fullText, "")))
            return@withContext Triple("", emptyList(), "zh")
        } finally {
            bitmap?.recycle()
            bitmap = null
            // Gợi ý GC dọn dẹp bộ nhớ sau mỗi ảnh để tránh OOM
            System.gc()
        }
    }

    private suspend fun recognizeTextAllScales(
        bitmap: Bitmap,
        rotationDegrees: Int,
        forceScript: String? = null
    ): List<Pair<Float, String>> = withContext(Dispatchers.IO) {
        // Optimized scale factors for manga/comic text - more diverse range
        val scaleFactors = listOf(0.85f, 1.0f, 1.15f, 1.35f)
        // Different enhancement modes for better coverage
        val enhanceModes = listOf(0, 1, 2) // standard, high contrast, soft contrast
        
        val recognizers = when (forceScript) {
            "zh" -> listOf(chineseRecognizer)
            "ja" -> listOf(japaneseRecognizer, chineseRecognizer) // Cả 2 để bắt Kanji tốt hơn
            "ko" -> listOf(koreanRecognizer)
            "en", "es" -> listOf(latinRecognizer)
            else -> listOf(chineseRecognizer, japaneseRecognizer, koreanRecognizer, latinRecognizer)
        }
        
        val allResults = mutableListOf<Pair<Float, String>>()
        val seenTexts = mutableSetOf<String>() // Để tránh trùng lặp
        
        // Thu thập kết quả từ tất cả các scale và enhancement modes
        scaleFactors.forEach { scale ->
            // Chỉ dùng 1 enhance mode cho mỗi scale để tăng tốc
            val enhanceMode = when {
                scale < 1.0f -> 2 // Soft contrast cho scale nhỏ
                scale > 1.2f -> 1 // High contrast cho scale lớn
                else -> 0 // Standard cho scale trung bình
            }
            
            recognizers.forEach { recognizer ->
                var preprocessedBitmap: Bitmap? = null
                try {
                    val (preBitmap, _) = preprocessImage(bitmap, scale, enhanceMode)
                    preprocessedBitmap = preBitmap
                    val scaledInputImage = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
                    val result = recognizer.process(scaledInputImage).await()
                    
                    if (result.text.isNotEmpty()) {
                        // Áp dụng post-processing để sửa lỗi OCR (bao gồm lọc CJK cho Latin mode)
                        val processedText = postProcessOCRText(result.text, forceScript)
                        
                        // Với Latin mode: kiểm tra thêm, nếu text vẫn chứa nhiều CJK -> bỏ qua
                        val isLatinForAllScales = forceScript == "en" || forceScript == "es"
                        val cleanedText = if (isLatinForAllScales) {
                            val cjkRemain = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]")
                                .findAll(processedText).count()
                            val totalNonSpace = processedText.count { !it.isWhitespace() }
                            if (totalNonSpace > 0 && cjkRemain.toFloat() / totalNonSpace > 0.3f) {
                                "" // Quá nhiều CJK trong kết quả Latin -> bỏ
                            } else {
                                processedText
                            }
                        } else {
                            processedText
                        }
                        
                        // Chỉ thêm nếu text có ý nghĩa và chưa có
                        val normalizedText = cleanedText.trim().lowercase()
                        if (cleanedText.isNotBlank() && !seenTexts.contains(normalizedText)) {
                            allResults.add(Pair(scale, cleanedText))
                            seenTexts.add(normalizedText)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TranslationRepository", "OCR failed for scale $scale, enhance=$enhanceMode, recognizer ${recognizer.javaClass.simpleName}", e)
                } finally {
                    preprocessedBitmap?.recycle()
                }
            }
        }
        
        return@withContext allResults
    }

    private val MIN_OCR_CONFIDENCE = 0.45f
    
    private val MIN_TEXT_LENGTH = 1
    
    private val MAX_SINGLE_CHAR_ASPECT_RATIO = 3.5f
    
    private val MIN_BLOCK_AREA = 64

    // recognizeText mới: cho phép chỉ quét preview hoặc ép loại recognizer
    private suspend fun recognizeText(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onlyPreview: Boolean = false,
        forceScript: String? = null,
        skipSort: Boolean = false
    ): Pair<String, List<TextBlockInfo>> = withContext(Dispatchers.IO) {
        // Optimized scale factors for manga/comic text recognition
        // Using more diverse scales to catch text at different sizes
        val scaleFactors = if (onlyPreview) listOf(0.9f, 1.1f) else listOf(0.85f, 1.0f, 1.15f, 1.3f)
        val recognizers = when (forceScript) {
            "zh" -> listOf(chineseRecognizer)
            "ja" -> listOf(japaneseRecognizer, chineseRecognizer) // Cả 2 recognizer để bắt Kanji tốt hơn
            "ko" -> listOf(koreanRecognizer)
            // Treat Spanish ("es") the same as English (Latin script)
            "en", "es" -> listOf(latinRecognizer)
            else -> listOf(chineseRecognizer, japaneseRecognizer, koreanRecognizer, latinRecognizer)
        }
        val deferredResults = scaleFactors.flatMap { scale ->
            recognizers.map { recognizer ->
                async {
                    var preprocessedBitmap: Bitmap? = null
                    try {
                        // Dùng enhance mode khác nhau theo scale để phát hiện text trên nền phức tạp
                        val enhanceMode = when {
                            scale < 1.0f -> 2  // Soft contrast cho scale nhỏ
                            scale > 1.2f -> 1  // High contrast cho scale lớn (tốt cho text trên nền tối)
                            else -> 0          // Standard cho scale trung bình
                        }
                        val (preBitmap, _) = preprocessImage(bitmap, scale, enhanceMode)
                        preprocessedBitmap = preBitmap
                        val scaledInputImage = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
                        val result = recognizer.process(scaledInputImage).await()
                        val elements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                        val confidence = if (elements.isEmpty()) 0.0 else elements.sumOf { it.confidence.toDouble() } / elements.size
                        val textLength = result.text.length
                        val fontSizes = result.textBlocks.flatMap { block ->
                            block.lines.flatMap { line ->
                                line.elements.mapNotNull { element ->
                                    element.boundingBox?.height()?.toFloat()?.div(scale)
                                }
                            }
                        }
                        val avgFontSize = if (fontSizes.isNotEmpty()) fontSizes.average().toFloat() else 16f
//                        //log.i(
//                            "TranslationRepository",
//                            "Kết quả quét với scaleFactor=$scale, recognizer=${recognizer.javaClass.simpleName}: " +
//                                    "textLength=$textLength, averageConfidence=$confidence, avgFontSize=$avgFontSize, text=${result.text.take(100)}[...]"
//                        )
                        RecognitionResult(scale, recognizer, result, avgFontSize)
                    } catch (e: Exception) {
                        Log.e("TranslationRepository", "Nhận diện thất bại cho scale $scale và recognizer ${recognizer.javaClass.simpleName}", e)
                        null
                    } finally {
                        preprocessedBitmap?.recycle()
                    }
                }
            }
        }
        val results = deferredResults.awaitAll().filterNotNull()
        if (results.isEmpty()) {
            Log.e("TranslationRepository", "Tất cả nhận diện đều thất bại")
            throw Exception("Không thể nhận diện văn bản trong hình ảnh")
        }
        // Group results by recognizer để dùng cho kiểm tra lỗi Chinese
        val groupedByRecognizer = results.groupBy { it.recognizer }
        // Tìm script mong muốn dựa trên forceScript hoặc đoán từ text
        val isLatinForBest = forceScript == "en" || forceScript == "es"
        val scriptPattern = when (forceScript) {
            "zh" -> Regex("[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF]") // Chinese
            "ja" -> Regex("[\u3040-\u309F\u30A0-\u30FF]") // Japanese
            "ko" -> Regex("[\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F]") // Korean
            "en", "es" -> Regex("[A-Za-z]") // Latin characters
            else -> Regex("[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F]")
        }
        val cjkPenaltyPattern = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]")
        // Chọn bestResult: ưu tiên confidence, text dài, nhiều block, nhiều ký tự script mong muốn
        val bestResult = results.maxByOrNull { result ->
            val elements = result.textResult.textBlocks.flatMap { b -> b.lines }.flatMap { l -> l.elements }
            val confidence = if (elements.isEmpty()) 0.0 else elements.sumOf { e -> e.confidence.toDouble() } / elements.size
            val length = result.textResult.text.length
            val blockCount = result.textResult.textBlocks.size
            val scriptCharCount = scriptPattern.findAll(result.textResult.text).count()
            // Với Latin mode: trừ điểm nếu kết quả chứa nhiều CJK
            val cjkPenalty = if (isLatinForBest) {
                val cjkCount = cjkPenaltyPattern.findAll(result.textResult.text).count()
                cjkCount * 0.5 // Mỗi ký tự CJK bị trừ 0.5 điểm
            } else 0.0
            // Ưu tiên: confidence * 2 + length/100 + blockCount*0.5 + scriptCharCount*0.2 - cjkPenalty
            (confidence * 2.0) + (length / 100.0) + (blockCount * 0.5) + (scriptCharCount * 0.2) - cjkPenalty
        }
        if (bestResult == null) {
            Log.e("TranslationRepository", "Không tìm thấy kết quả tốt nhất")
            throw Exception("Không có kết quả nhận diện văn bản")
        }
        val bestScaleFactor = bestResult.scale
        val bestTextResult = bestResult.textResult
        val bestAvgFontSize = bestResult.avgFontSize
        //log.i("TranslationRepository", "Chọn scaleFactor tốt nhất: $bestScaleFactor với recognizer ${bestResult.recognizer.javaClass.simpleName}, avgFontSize=$bestAvgFontSize")

        // Additional validation: Check for common errors in Chinese text
        val bestText = bestTextResult.text
        val hasCommonErrors = bestText.contains("地") && !bestText.contains("她") // "地" often mistaken for "她"
        if (hasCommonErrors) {
            Log.w("TranslationRepository", "Phát hiện lỗi ngữ pháp trong kết quả tốt nhất: $bestText")
            val alternativeResult = groupedByRecognizer.values.flatten()
                .filter { result -> result != bestResult && result.textResult.text.contains("她") }
                .maxByOrNull { result ->
                    val elements = result.textResult.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                    val averageConfidence = if (elements.isEmpty()) 0.0 else elements.sumOf { e -> e.confidence.toDouble() } / elements.size
                    averageConfidence
                }
            if (alternativeResult != null) {
                //log.i("TranslationRepository", "Chuyển sang kết quả thay thế với scaleFactor=${alternativeResult.scale}")
                val alternativeTextResult = alternativeResult.textResult
                val alternativeAvgFontSize = alternativeResult.avgFontSize
                val alternativeScaleFactor = alternativeResult.scale
                //log.i("TranslationRepository", "Kết quả thay thế: scaleFactor=$alternativeScaleFactor, avgFontSize=$alternativeAvgFontSize")

                val cjkPattern = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]") 
                val isLatinModeAlt = forceScript == "en" || forceScript == "es"
                val textBlocks = alternativeTextResult.textBlocks.flatMap { block ->
                    // Kiểm tra block cha có chứa CJK không - nếu có thì ưu tiên giữ tất cả lines
                    val blockHasCJK = cjkPattern.containsMatchIn(block.text)
                    
                    // Nếu đang quét Latin mà block chủ yếu là CJK -> bỏ qua toàn bộ block
                    if (isLatinModeAlt && blockHasCJK) {
                        val totalChars = block.text.count { !it.isWhitespace() }
                        val cjkChars = cjkPattern.findAll(block.text).count()
                        if (totalChars > 0 && cjkChars.toFloat() / totalChars > 0.5f) {
                            return@flatMap emptyList<TextBlockInfo>()
                        }
                    }
                    
                    block.lines.mapNotNull { line ->
                        val bounds = line.boundingBox ?: Rect()
                        val scaledBounds = Rect(
                            (bounds.left / alternativeScaleFactor).toInt(),
                            (bounds.top / alternativeScaleFactor).toInt(),
                            (bounds.right / alternativeScaleFactor).toInt(),
                            (bounds.bottom / alternativeScaleFactor).toInt()
                        )
                        
                        // Calculate average confidence for this line
                        val elements = line.elements
                        val lineConfidence = if (elements.isNotEmpty()) {
                            elements.sumOf { it.confidence.toDouble() }.toFloat() / elements.size
                        } else {
                            0f
                        }
                        
                        val fontSizes = elements.mapNotNull { element ->
                            element.boundingBox?.height()?.toFloat()?.div(alternativeScaleFactor)
                        }
                        val fontSize = if (fontSizes.isNotEmpty()) {
                            fontSizes.sorted()[fontSizes.size / 2].coerceAtMost(alternativeAvgFontSize * 1.2f)
                        } else {
                            alternativeAvgFontSize
                        }
                        // Áp dụng post-processing để sửa lỗi OCR (ví dụ: し -> L cho Latin script)
                        val processedText = postProcessOCRText(line.text, forceScript)
                        
                        // Context-aware noise filtering:
                        // - Lines chứa CJK: luôn giữ (text hợp lệ trong manga)
                        // - Lines trong block CJK nhưng không chứa CJK: chỉ lọc nếu rõ ràng là noise
                        // - Lines không liên quan CJK: áp dụng bộ lọc noise đầy đủ
                        val lineHasCJK = cjkPattern.containsMatchIn(processedText)
                        val cjkSingleNoise = setOf("ー", "丨", "丶")
                        val shouldFilter = when {
                            processedText.isBlank() -> true
                            lineHasCJK -> processedText.trim() in cjkSingleNoise && scaledBounds.width() * scaledBounds.height() < MIN_BLOCK_AREA
                            blockHasCJK -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                            else -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                        }
                        if (shouldFilter) {
                            null
                        } else {
                            val wordCount = processedText.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                            // Phân tích màu nền và màu text
                            val (backgroundType, avgColor, textColor) = analyzeBackgroundAndTextColor(bitmap, scaledBounds)
                            // DISABLED: Container classification (OpenCV compatibility issue)
                            val containerInfo = detectContainerInfo(bitmap, scaledBounds, bitmap.width, bitmap.height)
                            TextBlockInfo(
                                text = processedText,
                                originalText = processedText,
                                bounds = scaledBounds,
                                fontSize = fontSize,
                                originalFontSize = fontSize,
                                wordCountsPerLine = listOf(wordCount),
                                originalImageWidth = bitmap.width,
                                originalImageHeight = bitmap.height,
                                backgroundType = backgroundType,
                                averageBackgroundColor = avgColor,
                                originalTextColor = textColor,
                                containerInfo = containerInfo
                            )
                        }
                    }
                }

                val clusters = groupBlocksIntoClusters(textBlocks)
                val normalizedTextBlocks = clusters.flatMap { cluster ->
                    val fontSizes = cluster.map { it.fontSize }
                    if (fontSizes.isNotEmpty()) {
                        val medianFontSize = fontSizes.sorted()[fontSizes.size / 2]
                        cluster.map { block ->
                            if (kotlin.math.abs(block.fontSize - medianFontSize) > medianFontSize * 0.3f) {
                                block.copy(fontSize = medianFontSize)
                            } else {
                                block
                            }
                        }
                    } else {
                        cluster
                    }
                }

                // Nếu skipSort, trả về raw lines không merge (dùng cho rotation strategy)
                if (skipSort) {
                    val fullText = normalizedTextBlocks.joinToString("\n") { it.text }
                    return@withContext fullText to normalizedTextBlocks
                }

                val isVertical = determineTextOrientation(normalizedTextBlocks, alternativeTextResult.text)
                val processedTextBlocks = if (isVertical) {
                    sortVerticalTextBlocks(normalizedTextBlocks, bitmap)
                } else {
                    sortHorizontalTextBlocks(normalizedTextBlocks, bitmap)
                }

                processedTextBlocks.forEachIndexed { index, block ->
                    try {
                        val origColorHex = block.originalTextColor?.let { String.format("#%08X", it) } ?: "null"
                        val avgBgHex = block.averageBackgroundColor?.let { String.format("#%08X", it) } ?: "null"
                        Log.i("TranslationRepository", "[OCR] Block #$index: text='${block.text.take(40)}', bounds=${block.bounds.left},${block.bounds.top},${block.bounds.right},${block.bounds.bottom}, fontSize=${block.fontSize}, originalFontSize=${block.originalFontSize}, originalColor=$origColorHex, avgBg=$avgBgHex")
                    } catch (_: Exception) { }
                }

                val fullText = processedTextBlocks.joinToString("\n") { it.text }
                //log.i("TranslationRepository", "Hướng văn bản: ${if (isVertical) "Dọc" else "Ngang"}, Toàn bộ văn bản: $fullText")
                return@withContext fullText to processedTextBlocks
            }
        }

        // Process text blocks with font size normalization and noise filtering
        val cjkPatternMain = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]")
        val isLatinMode = forceScript == "en" || forceScript == "es"
        val textBlocks = bestTextResult.textBlocks.flatMap { block ->
            // Kiểm tra block cha có chứa CJK không - nếu có thì ưu tiên giữ tất cả lines
            val blockHasCJK = cjkPatternMain.containsMatchIn(block.text)
            
            // Nếu đang quét Latin mà block chủ yếu là CJK -> bỏ qua toàn bộ block
            if (isLatinMode && blockHasCJK) {
                val totalChars = block.text.count { !it.isWhitespace() }
                val cjkChars = cjkPatternMain.findAll(block.text).count()
                // Nếu >50% ký tự là CJK -> skip block này khi đang ở Latin mode
                if (totalChars > 0 && cjkChars.toFloat() / totalChars > 0.5f) {
                    return@flatMap emptyList<TextBlockInfo>()
                }
            }
            
            block.lines.mapNotNull { line ->
                val bounds = line.boundingBox ?: Rect()
                val scaledBounds = Rect(
                    (bounds.left / bestScaleFactor).toInt(),
                    (bounds.top / bestScaleFactor).toInt(),
                    (bounds.right / bestScaleFactor).toInt(),
                    (bounds.bottom / bestScaleFactor).toInt()
                )
                
                // Calculate average confidence for this line
                val elements = line.elements
                val lineConfidence = if (elements.isNotEmpty()) {
                    elements.sumOf { it.confidence.toDouble() }.toFloat() / elements.size
                } else {
                    0f
                }
                
                val fontSizes = elements.mapNotNull { it.boundingBox?.height()?.toFloat()?.div(bestScaleFactor) }
                val fontSize = if (fontSizes.isNotEmpty()) {
                    fontSizes.sorted()[fontSizes.size / 2].coerceAtMost(bestAvgFontSize * 1.2f)
                } else {
                    bestAvgFontSize
                }
                
                // Áp dụng post-processing để sửa lỗi OCR (ví dụ: し -> L cho Latin script)
                val processedText = postProcessOCRText(line.text, forceScript)
                
                // Context-aware noise filtering:
                // - Lines chứa CJK: luôn giữ (text hợp lệ trong manga)
                // - Lines trong block CJK nhưng không chứa CJK: chỉ lọc nếu rõ ràng là noise
                // - Lines không liên quan CJK: áp dụng bộ lọc noise đầy đủ
                val lineHasCJK = cjkPatternMain.containsMatchIn(processedText)
                val cjkSingleNoise = setOf("ー", "丨", "丶")
                val shouldFilter = when {
                    processedText.isBlank() -> true
                    lineHasCJK -> processedText.trim() in cjkSingleNoise && scaledBounds.width() * scaledBounds.height() < MIN_BLOCK_AREA
                    blockHasCJK -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                    else -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                }
                if (shouldFilter) {
                    null // Skip this block
                } else {
                    val wordCount = processedText.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                    // Phân tích màu nền và màu text
                    val (backgroundType, avgColor, textColor) = analyzeBackgroundAndTextColor(bitmap, scaledBounds)
                    // DISABLED: Container classification
                    val containerInfo = detectContainerInfo(bitmap, scaledBounds, bitmap.width, bitmap.height)
                    TextBlockInfo(
                        text = processedText,
                        originalText = processedText,
                        bounds = scaledBounds,
                        fontSize = fontSize,
                        wordCountsPerLine = listOf(wordCount),
                        originalImageWidth = bitmap.width,
                        originalImageHeight = bitmap.height,
                        backgroundType = backgroundType,
                        averageBackgroundColor = avgColor,
                        originalTextColor = textColor,
                        containerInfo = containerInfo
                    )
                }
            }
        }

        // === MULTI-SCALE FUSION ===
        // Thu thập text blocks bổ sung từ các scale khác
        // Text nhỏ hoặc trên nền phức tạp có thể chỉ được phát hiện ở scale khác
        // Cho phép cross-fusion giữa Japanese và Chinese recognizer (chia sẻ Kanji)
        // Nhưng KHÔNG merge từ Korean/Latin recognizer khi quét CJK (tránh garbage)
        val mergedTextBlocks = textBlocks.toMutableList()
        val cjkRecognizers = setOf(japaneseRecognizer, chineseRecognizer)
        val otherResults = if (onlyPreview) emptyList() else results.filter { result ->
            result != bestResult && (
                result.recognizer == bestResult.recognizer ||
                // Cho phép cross-fusion giữa Japanese và Chinese recognizer
                (result.recognizer in cjkRecognizers && bestResult.recognizer in cjkRecognizers)
            )
        }

        for (otherResult in otherResults) {
            val otherScaleFactor = otherResult.scale
            val otherTextResult = otherResult.textResult
            val otherAvgFontSize = otherResult.avgFontSize

            val otherBlocks = otherTextResult.textBlocks.flatMap { block ->
                val blockHasCJK = cjkPatternMain.containsMatchIn(block.text)

                if (isLatinMode && blockHasCJK) {
                    val totalChars = block.text.count { !it.isWhitespace() }
                    val cjkChars = cjkPatternMain.findAll(block.text).count()
                    if (totalChars > 0 && cjkChars.toFloat() / totalChars > 0.5f) {
                        return@flatMap emptyList<TextBlockInfo>()
                    }
                }

                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox ?: Rect()
                    val scaledBounds = Rect(
                        (bounds.left / otherScaleFactor).toInt(),
                        (bounds.top / otherScaleFactor).toInt(),
                        (bounds.right / otherScaleFactor).toInt(),
                        (bounds.bottom / otherScaleFactor).toInt()
                    )

                    val elements = line.elements
                    val lineConfidence = if (elements.isNotEmpty()) {
                        elements.sumOf { it.confidence.toDouble() }.toFloat() / elements.size
                    } else { 0f }

                    val fontSizes = elements.mapNotNull { it.boundingBox?.height()?.toFloat()?.div(otherScaleFactor) }
                    val fontSize = if (fontSizes.isNotEmpty()) {
                        fontSizes.sorted()[fontSizes.size / 2].coerceAtMost(otherAvgFontSize * 1.2f)
                    } else { otherAvgFontSize }

                    val processedText = postProcessOCRText(line.text, forceScript)

                    val lineHasCJK = cjkPatternMain.containsMatchIn(processedText)
                    val cjkSingleNoise = setOf("ー", "丨", "丶")
                    val shouldFilter = when {
                        processedText.isBlank() -> true
                        lineHasCJK -> processedText.trim() in cjkSingleNoise && scaledBounds.width() * scaledBounds.height() < MIN_BLOCK_AREA
                        blockHasCJK -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                        else -> isNoiseBlock(processedText, scaledBounds, lineConfidence)
                    }

                    if (shouldFilter) null
                    else {
                        val wordCount = processedText.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        val (backgroundType, avgColor, textColor) = analyzeBackgroundAndTextColor(bitmap, scaledBounds)
                        // Phân loại container type (TẠM THỜI TẮT)
                        val containerInfo = detectContainerInfo(bitmap, scaledBounds, bitmap.width, bitmap.height)
                        TextBlockInfo(
                            text = processedText,
                            originalText = processedText,
                            bounds = scaledBounds,
                            fontSize = fontSize,
                            wordCountsPerLine = listOf(wordCount),
                            originalImageWidth = bitmap.width,
                            originalImageHeight = bitmap.height,
                            backgroundType = backgroundType,
                            averageBackgroundColor = avgColor,
                            originalTextColor = textColor,
                            containerInfo = containerInfo
                        )
                    }
                }
            }

            // Thêm blocks mới không overlap đáng kể với blocks hiện có
            for (newBlock in otherBlocks) {
                val hasSignificantOverlap = mergedTextBlocks.any { existing ->
                    val overlapLeft = maxOf(existing.bounds.left, newBlock.bounds.left)
                    val overlapTop = maxOf(existing.bounds.top, newBlock.bounds.top)
                    val overlapRight = minOf(existing.bounds.right, newBlock.bounds.right)
                    val overlapBottom = minOf(existing.bounds.bottom, newBlock.bounds.bottom)
                    val overlapArea = maxOf(0, overlapRight - overlapLeft) * maxOf(0, overlapBottom - overlapTop)
                    val newBlockArea = newBlock.bounds.width() * newBlock.bounds.height()
                    val existingArea = existing.bounds.width() * existing.bounds.height()
                    val smallerArea = minOf(newBlockArea, existingArea).coerceAtLeast(1)
                    overlapArea.toFloat() / smallerArea > 0.4f
                }
                if (!hasSignificantOverlap) {
                    mergedTextBlocks.add(newBlock)
                    Log.i("TranslationRepository", "[MULTI-SCALE] Added block from scale=${otherResult.scale}: '${newBlock.text}'")
                }
            }
        }

        if (mergedTextBlocks.size > textBlocks.size) {
            Log.i("TranslationRepository", "[MULTI-SCALE] Total blocks: ${textBlocks.size} (best) + ${mergedTextBlocks.size - textBlocks.size} (other scales) = ${mergedTextBlocks.size}")
        }

        // === RAW BITMAP SCAN ===
        // Quét thêm trên ảnh gốc (không grayscale/contrast) để bắt text mà preprocessing phá hủy
        // VD: text trên nền phức tạp, text màu nhạt, SFX manga, text trên nền tối
        if (!onlyPreview) {
            val rawBlocksBefore = mergedTextBlocks.size
            // Dùng lại danh sách recognizers đã xác định ở trên (có đúng type TextRecognizer)
            for (rawRecognizer in recognizers) {
                try {
                    val rawInputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
                    val rawResult = rawRecognizer.process(rawInputImage).await()

                    for (rawBlock in rawResult.textBlocks) {
                        val rawBlockHasCJK = cjkPatternMain.containsMatchIn(rawBlock.text)
                        if (isLatinMode && rawBlockHasCJK) {
                            val totalCharsRaw = rawBlock.text.count { c -> !c.isWhitespace() }
                            val cjkCharsRaw = cjkPatternMain.findAll(rawBlock.text).count()
                            if (totalCharsRaw > 0 && cjkCharsRaw.toFloat() / totalCharsRaw > 0.5f) continue
                        }

                        for (rawLine in rawBlock.lines) {
                            val rawBounds = rawLine.boundingBox ?: continue
                            val rawElements = rawLine.elements
                            val rawLineConfidence = if (rawElements.isNotEmpty()) {
                                rawElements.sumOf { el -> el.confidence.toDouble() }.toFloat() / rawElements.size
                            } else { 0f }

                            val rawFontSizes = rawElements.mapNotNull { el -> el.boundingBox?.height()?.toFloat() }
                            val rawFontSize = if (rawFontSizes.isNotEmpty()) {
                                rawFontSizes.sorted()[rawFontSizes.size / 2]
                            } else { bestAvgFontSize }

                            val rawProcessedText = postProcessOCRText(rawLine.text, forceScript)
                            val rawLineHasCJK = cjkPatternMain.containsMatchIn(rawProcessedText)
                            val rawCjkSingleNoise = setOf("ー", "丨", "丶")
                            val rawShouldFilter = when {
                                rawProcessedText.isBlank() -> true
                                rawLineHasCJK -> rawProcessedText.trim() in rawCjkSingleNoise && rawBounds.width() * rawBounds.height() < MIN_BLOCK_AREA
                                rawBlockHasCJK -> isNoiseBlock(rawProcessedText, rawBounds, rawLineConfidence)
                                else -> isNoiseBlock(rawProcessedText, rawBounds, rawLineConfidence)
                            }
                            if (rawShouldFilter) continue

                            // Kiểm tra overlap với blocks hiện có
                            val rawHasOverlap = mergedTextBlocks.any { existing ->
                                val oLeft = maxOf(existing.bounds.left, rawBounds.left)
                                val oTop = maxOf(existing.bounds.top, rawBounds.top)
                                val oRight = minOf(existing.bounds.right, rawBounds.right)
                                val oBottom = minOf(existing.bounds.bottom, rawBounds.bottom)
                                val oArea = maxOf(0, oRight - oLeft) * maxOf(0, oBottom - oTop)
                                val smaller = minOf(
                                    rawBounds.width() * rawBounds.height(),
                                    existing.bounds.width() * existing.bounds.height()
                                ).coerceAtLeast(1)
                                oArea.toFloat() / smaller > 0.4f
                            }
                            if (!rawHasOverlap) {
                                val rawWordCount = rawProcessedText.split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.size
                                val (rawBgType, rawAvgColor, rawTextColor) = analyzeBackgroundAndTextColor(bitmap, rawBounds)
                                // DISABLED: Container classification
                                val rawContainerInfo = detectContainerInfo(bitmap, rawBounds, bitmap.width, bitmap.height)
                                mergedTextBlocks.add(TextBlockInfo(
                                    text = rawProcessedText,
                                    originalText = rawProcessedText,
                                    bounds = rawBounds,
                                    fontSize = rawFontSize,
                                    originalFontSize = rawFontSize,
                                    wordCountsPerLine = listOf(rawWordCount),
                                    originalImageWidth = bitmap.width,
                                    originalImageHeight = bitmap.height,
                                    backgroundType = rawBgType,
                                    averageBackgroundColor = rawAvgColor,
                                    originalTextColor = rawTextColor,
                                    containerInfo = rawContainerInfo
                                ))
                                Log.i("TranslationRepository", "[RAW-SCAN] Added block: '${rawProcessedText}' bounds=$rawBounds")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "[RAW-SCAN] Failed: ${e.message}")
                }
            }
            if (mergedTextBlocks.size > rawBlocksBefore) {
                Log.i("TranslationRepository", "[RAW-SCAN] Added ${mergedTextBlocks.size - rawBlocksBefore} new blocks from raw bitmap scan")
            }
        }

        // Check for font size consistency within clusters
        val clusters = groupBlocksIntoClusters(mergedTextBlocks)
        val normalizedTextBlocks = clusters.flatMap { cluster ->
            val fontSizes = cluster.map { it.fontSize }
            if (fontSizes.isNotEmpty()) {
                val medianFontSize = fontSizes.sorted()[fontSizes.size / 2]
                cluster.map { block ->
                    if (abs(block.fontSize - medianFontSize) > medianFontSize * 0.3f) {
                        block.copy(fontSize = medianFontSize)
                    } else {
                        block
                    }
                }
            } else {
                cluster
            }
        }

        // Nếu skipSort, trả về raw lines không merge (dùng cho rotation strategy)
        if (skipSort) {
            val fullText = normalizedTextBlocks.joinToString("\n") { it.text }
            return@withContext fullText to normalizedTextBlocks
        }

        val isVertical = determineTextOrientation(normalizedTextBlocks, bestTextResult.text)
        val processedTextBlocks = if (isVertical) {
            sortVerticalTextBlocks(normalizedTextBlocks, bitmap)
        } else {
            sortHorizontalTextBlocks(normalizedTextBlocks, bitmap)
        }

        processedTextBlocks.forEachIndexed { index, block ->
            //log.i("TranslationRepository", "Khối #$index: text=${block.text}, left=${block.bounds.left}, top=${block.bounds.top}, bottom=${block.bounds.bottom}, fontSize=${block.fontSize}")
        }

        val fullText = processedTextBlocks.joinToString("\n") { it.text }
        //log.i("TranslationRepository", "Hướng văn bản: ${if (isVertical) "Dọc" else "Ngang"}, Toàn bộ văn bản: $fullText")
        fullText to processedTextBlocks
    }

    // Nhóm các text block thành các khung thoại (bubble) dựa trên vị trí và khoảng cách, kiểm tra overlap dọc đủ lớn và không ghép nếu lệch trục quá xa
    private fun assignSpeechBubblesToBlocks(textBlocks: List<TextBlockInfo>): List<TextBlockInfo> {
        if (textBlocks.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<TextBlockInfo>>()
        // Tăng threshold và IoU để tách các bubble riêng biệt tốt hơn
        val iouThreshold = 0.4f
        fun iou(a: Rect, b: Rect): Float {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val right = minOf(a.right, b.right)
            val bottom = minOf(a.bottom, b.bottom)
            val intersection = maxOf(0, right - left) * maxOf(0, bottom - top)
            val union = a.width() * a.height() + b.width() * b.height() - intersection
            return if (union > 0) intersection.toFloat() / union else 0f
        }
        fun verticalOverlap(a: Rect, b: Rect): Int {
            val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            return maxOf(0, overlap)
        }
        fun isVerticalOverlapEnough(a: Rect, b: Rect): Boolean {
            val overlap = verticalOverlap(a, b)
            val minHeight = minOf(a.height(), b.height())
            return overlap >= minHeight / 2 // tăng yêu cầu overlap dọc
        }
        fun isTooFarVertical(a: Rect, b: Rect): Boolean {
            val aHeight = a.height()
            val bHeight = b.height()
            val verticalGap1 = b.top - a.bottom
            val verticalGap2 = a.top - b.bottom
            return (verticalGap1 > aHeight / 2) || (verticalGap2 > bHeight / 2)
        }
        textBlocks.forEach { block ->
            var assigned = false
            for (cluster in clusters) {
                val last = cluster.last()
                // Tính khoảng trắng ngang thực sự giữa block và last
                val hGap = when {
                    block.bounds.right <= last.bounds.left -> last.bounds.left - block.bounds.right
                    block.bounds.left >= last.bounds.right -> block.bounds.left - last.bounds.right
                    else -> 0
                }
                val vOverlap = verticalOverlap(block.bounds, last.bounds)
                val minHgt = minOf(block.bounds.height(), last.bounds.height()).coerceAtLeast(1)
                // Các cột dọc liền kề trong cùng speech bubble có x-gap nhỏ (< 15px) và
                // overlap dọc lớn. IoU = 0 vì không chồng x-range → cần check riêng.
                // Cap 15px để không nhầm với các bubble khác nhau (gap thường ≥ 30px).
                val isHorizontallyAdjacentColumn = hGap <= 15 && vOverlap >= minHgt * 0.3f
                if ((iou(block.bounds, last.bounds) > iouThreshold && isVerticalOverlapEnough(block.bounds, last.bounds) && !isTooFarVertical(block.bounds, last.bounds)) || isHorizontallyAdjacentColumn) {
                    cluster.add(block)
                    assigned = true
                    break
                }
            }
            if (!assigned) clusters.add(mutableListOf(block))
        }
        // Với mỗi cluster, tính bounding box bao ngoài (bubbleBounds)
        val clusterBounds = clusters.map { cluster ->
            cluster.fold(Rect(cluster[0].bounds)) { acc, block ->
                acc.union(block.bounds)
                acc
            }
        }
        // Gán bubbleId cho từng block
        val result = mutableListOf<TextBlockInfo>()
        clusters.forEachIndexed { idx, cluster ->
            val bubble = clusterBounds[idx]
            cluster.forEach { block ->
                result.add(block.copy(
                    polygon = null,
                    bounds = block.bounds,
                    fontSize = block.fontSize,
                    isVertical = block.isVertical,
                    wordCountsPerLine = block.wordCountsPerLine,
                    originalImageWidth = block.originalImageWidth,
                    originalImageHeight = block.originalImageHeight,
                    bubbleId = idx // Gán bubbleId
                ))
            }
        }
        return result
    }

    private fun groupBlocksIntoClusters(textBlocks: List<TextBlockInfo>): List<List<TextBlockInfo>> {
        val clusters = mutableListOf<MutableList<TextBlockInfo>>()
        // Giảm threshold để tránh merge nhầm các cụm xa nhau
        val verticalThreshold = 35
        val horizontalThreshold = 60

        textBlocks.forEach { block ->
            var assigned = false
            for (cluster in clusters) {
                if (cluster.any { other ->
                        val xDistance = minOf(
                            abs(block.bounds.left - other.bounds.right),
                            abs(other.bounds.left - block.bounds.right)
                        )
                        val yDistance = minOf(
                            abs(block.bounds.top - other.bounds.bottom),
                            abs(other.bounds.top - block.bounds.bottom)
                        )
                        xDistance <= horizontalThreshold && yDistance <= verticalThreshold
                    }) {
                    cluster.add(block)
                    assigned = true
                    break
                }
            }
            if (!assigned) {
                clusters.add(mutableListOf(block))
            }
        }
        return clusters
    }

    private fun sortHorizontalTextBlocks(textBlocks: List<TextBlockInfo>, bitmap: Bitmap?): List<TextBlockInfo> {
        if (textBlocks.isEmpty()) return emptyList()

        val sortedByTopThenLeft = textBlocks.sortedWith(
            compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left }
        )

        val topValues = sortedByTopThenLeft.map { it.bounds.top }
        val topGaps = topValues.zipWithNext { a, b -> b - a }.filter { it > 0 }
        val avgTopGap = if (topGaps.isNotEmpty()) topGaps.average().toInt() else 50
        val verticalThreshold = (avgTopGap * 0.4).toInt().coerceAtLeast(15)

        // Thắt chặt ngưỡng proximity dọc: không quá 1.0x font size trung bình để tránh merge các bubble khác nhau
        val avgFontSize = textBlocks.map { it.fontSize }.average().toFloat()
        val verticalProximityThreshold = minOf(avgTopGap, (avgFontSize * 1.0f).toInt()).coerceAtLeast(25)

        val rows = mutableListOf<MutableList<TextBlockInfo>>()
        var currentRow = mutableListOf<TextBlockInfo>()
        var lastTop = sortedByTopThenLeft.first().bounds.top

        for (block in sortedByTopThenLeft) {
            val currentTop = block.bounds.top
            if (currentTop - lastTop <= verticalThreshold) {
                currentRow.add(block)
            } else {
                if (currentRow.isNotEmpty()) {
                    rows.add(currentRow.sortedBy { it.bounds.left }.toMutableList())
                }
                currentRow = mutableListOf(block)
            }
            lastTop = currentTop
        }
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow.sortedBy { it.bounds.left }.toMutableList())
        }

        rows.forEachIndexed { rowIndex, rowBlocks ->
            rowBlocks.forEach { block ->
//                //log.i(
//                    "TranslationRepository",
//                    "Row #$rowIndex, Block: text=${block.text}, left=${block.bounds.left}, top=${block.bounds.top}, right=${block.bounds.right}, bottom=${block.bounds.bottom}"
//                )
            }
        }

        val clusters = mutableListOf<MutableList<TextBlockInfo>>()
        sortedByTopThenLeft.forEach { block ->
            var assigned = false
            for (cluster in clusters) {
                if (cluster.any { other ->
                        val xOverlap = block.bounds.left <= other.bounds.right && other.bounds.left <= block.bounds.right
                        val yOverlap = block.bounds.top <= other.bounds.bottom && other.bounds.top <= block.bounds.bottom
                        val yDistance = if (block.bounds.top > other.bounds.bottom) {
                            block.bounds.top - other.bounds.bottom
                        } else {
                            other.bounds.top - block.bounds.bottom
                        }
                        val yCloseEnough = yDistance <= verticalProximityThreshold
                        xOverlap && (yOverlap || yCloseEnough)
                    }) {
                    cluster.add(block)
                    assigned = true
                    break
                }
            }
            if (!assigned) {
                clusters.add(mutableListOf(block))
            }
        }

        val secondMergeClusters = mutableListOf<MutableList<TextBlockInfo>>()
        val processedClusters = mutableSetOf<Int>()
        clusters.forEachIndexed { index, cluster ->
            if (index in processedClusters) return@forEachIndexed

            val mergedCluster = mutableListOf<TextBlockInfo>().apply { addAll(cluster) }
            processedClusters.add(index)

            for (otherIndex in (index + 1) until clusters.size) {
                if (otherIndex in processedClusters) continue

                val otherCluster = clusters[otherIndex]
                val clusterBottom = cluster.maxOf { it.bounds.bottom }
                val clusterTop = cluster.minOf { it.bounds.top }
                val otherTop = otherCluster.minOf { it.bounds.top }
                val otherBottom = otherCluster.maxOf { it.bounds.bottom }

                // Tính khoảng cách dọc giữa hai cluster
                val verticalGap = if (otherTop > clusterBottom) {
                    otherTop - clusterBottom
                } else {
                    clusterTop - otherBottom
                }

                // Tính overlap ngang
                val clusterLeft = cluster.minOf { it.bounds.left }
                val clusterRight = cluster.maxOf { it.bounds.right }
                val otherLeft = otherCluster.minOf { it.bounds.left }
                val otherRight = otherCluster.maxOf { it.bounds.right }
                val horizontalOverlap = maxOf(0, min(clusterRight, otherRight) - max(clusterLeft, otherLeft))
                val clusterWidth = clusterRight - clusterLeft
                val otherWidth = otherRight - otherLeft
                val overlapRatio = if (clusterWidth > 0 && otherWidth > 0) {
                    horizontalOverlap.toFloat() / min(clusterWidth, otherWidth)
                } else 0f

                // Merge nếu khoảng cách dọc nhỏ (dựa trên font size) và có overlap ngang
                val avgFontSize = cluster.map { it.fontSize }.average().toFloat()
                val maxVerticalGap = (avgFontSize * 1.5f).coerceAtMost(60f) // Giới hạn khoảng cách dọc nghiêm ngặt hơn

                // Yêu cầu overlap ngang hoặc khoảng cách ngang nhỏ để tránh merge các bubble chéo nhau xa
                val isCloseEnough = verticalGap <= maxVerticalGap && overlapRatio > 0.3f

                if (isCloseEnough) {
                    val clusterText = cluster.joinToString(" ") { it.text }
                    val otherText = otherCluster.joinToString(" ") { it.text }
                    val combinedText = "$clusterText $otherText"
                    if (isTextCoherent(combinedText)) {
                        mergedCluster.addAll(otherCluster)
                        processedClusters.add(otherIndex)
                    }
                }
            }
            secondMergeClusters.add(mergedCluster)
        }

        val mergedBlocks = mutableListOf<TextBlockInfo>()
        secondMergeClusters.forEachIndexed { clusterIndex, clusterBlocks ->
            if (clusterBlocks.isEmpty()) return@forEachIndexed

            val sortedBlocks = clusterBlocks.sortedWith(
                compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left }
            )
            val mergedText = StringBuilder()
            lateinit var mergedBounds: Rect
            var minFontSize = Float.MAX_VALUE
            var blockCount = 0

            sortedBlocks.forEachIndexed { blockIndex, block ->
                if (mergedText.isNotEmpty()) {
                    mergedText.append(" ")
                }
                mergedText.append(block.text)
                val wordCount = block.wordCountsPerLine?.sum() ?: block.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                val currentWordCountsPerLine = block.wordCountsPerLine ?: listOf(wordCount)
                if (blockCount == 0) {
                    mergedBounds = Rect(block.bounds)
                } else {
                    mergedBounds.set(
                        minOf(mergedBounds.left, block.bounds.left),
                        minOf(mergedBounds.top, block.bounds.top),
                        maxOf(mergedBounds.right, block.bounds.right),
                        maxOf(mergedBounds.bottom, block.bounds.bottom)
                    )
                }
                minFontSize = minOf(minFontSize, block.fontSize)
                blockCount++
            }

            // Phân tích màu nền và màu text cho merged block
            val (backgroundType, avgColor, textColor) = analyzeBackgroundAndTextColor(bitmap, mergedBounds)
            val containerInfo = detectContainerInfo(bitmap, mergedBounds, (bitmap?.width ?: 0), (bitmap?.height ?: 0))

            val mergedOriginalText = sortedBlocks.joinToString("\n") { it.originalText ?: it.text }
            val mergedBlock = TextBlockInfo(
                text = mergedText.toString(),
                originalText = mergedOriginalText,
                bounds = Rect(mergedBounds),
                fontSize = minFontSize,
                originalFontSize = sortedBlocks.maxOfOrNull { it.originalFontSize ?: it.fontSize } ?: minFontSize,
                wordCountsPerLine = null, // Reset wordCountsPerLine after merging
                originalImageWidth = sortedBlocks.firstOrNull()?.originalImageWidth,
                originalImageHeight = sortedBlocks.firstOrNull()?.originalImageHeight,
                backgroundType = backgroundType,
                averageBackgroundColor = avgColor,
                originalTextColor = textColor,
                containerInfo = containerInfo
            )

//            Log.i(
//                "TranslationRepository",
//                "Cluster #$clusterIndex: text=${mergedBlock.text}, left=${mergedBlock.bounds.left}, top=${mergedBlock.bounds.top}, right=${mergedBlock.bounds.right}, bottom=${mergedBlock.bounds.bottom}, fontSize=${mergedBlock.fontSize}"
//            )

            mergedBlocks.add(mergedBlock)
        }

        return mergedBlocks.sortedWith(
            compareBy<TextBlockInfo> { it.bounds.top }.thenBy { it.bounds.left }
        )
    }

    private fun sortVerticalTextBlocks(textBlocks: List<TextBlockInfo>, bitmap: Bitmap?): List<TextBlockInfo> {
        if (textBlocks.isEmpty()) return emptyList()

        val sortedByLeft = textBlocks.sortedBy { it.bounds.left }
        // Tính khoảng trắng thực sự giữa các cột: right của block trước → left của block sau.
        // Điều này phân biệt chính xác các cột liền kề (~10px) vs các bubble khác nhau (~60px+).
        // So sánh left-to-left trước đây bị ảnh hưởng bởi block width nhỏ (~30px)
        // dẫn đến "avgLeftGap * 0.8" không đủ để tách các bubble riêng biệt.
        val avgBlockWidth = sortedByLeft.map { it.bounds.width() }.average().toFloat().coerceAtLeast(10f)
        // interColumnGaps = khoảng trắng thực sự giữa right cạnh của block trước và left cạnh của block sau
        val interColumnGaps = sortedByLeft.zipWithNext { a, b ->
            (b.bounds.left - a.bounds.right).coerceAtLeast(0)
        }.filter { it >= 0 }
        // Dùng median của interColumnGaps để tránh outlier; threshold = median + 1*avgBlockWidth
        // Nếu gap < threshold → cùng cột (liền kề), nếu gap >= threshold → cột khác (khác bubble)
        val medianInterGap = if (interColumnGaps.isNotEmpty()) {
            val sorted = interColumnGaps.sorted()
            if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else 0
        // Threshold: cho phép gap tới median + 1 block-width → bắt được cột kề mà vẫn tách bubble.
        // Cap tại 2× avgBlockWidth để cột cách nhau hơn 2 block-width luôn thuộc bubble khác nhau.
        val horizontalThreshold = (medianInterGap + avgBlockWidth).toInt().coerceAtLeast(20).coerceAtMost((avgBlockWidth * 2).toInt())

        val columns = mutableListOf<MutableList<TextBlockInfo>>()
        var currentColumn = mutableListOf(sortedByLeft.first())
        var lastRight = sortedByLeft.first().bounds.right  // theo dõi right edge, không phải left

        for (block in sortedByLeft.drop(1)) {
            val interGap = (block.bounds.left - lastRight).coerceAtLeast(0)
            if (interGap <= horizontalThreshold) {
                currentColumn.add(block)
            } else {
                columns.add(currentColumn)
                currentColumn = mutableListOf(block)
            }
            lastRight = maxOf(lastRight, block.bounds.right)
        }
        if (currentColumn.isNotEmpty()) {
            columns.add(currentColumn)
        }

        val mergedBlocks = mutableListOf<TextBlockInfo>()
        columns.forEachIndexed { columnIndex, columnBlocks ->
            val sortedByTop = columnBlocks.sortedBy { it.bounds.top }
            val topValues = sortedByTop.map { it.bounds.top }
            val topGaps = topValues.zipWithNext { a, b -> b - a }.filter { it > 0 }
            // Dùng median thay vì average để tránh outlier gap lớn làm threshold quá cao,
            // dẫn đến merge nhầm các block từ các speech bubble khác nhau trong cùng cột.
            val medianTopGap = if (topGaps.isNotEmpty()) {
                val sorted = topGaps.sorted()
                if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            } else 100
            // Cap threshold tại 1.5× block height trung bình để tránh merge quá tham lam
            val avgBlockHeight = sortedByTop.map { it.bounds.height() }.average().toInt().coerceAtLeast(20)
            val verticalThreshold = (medianTopGap * 1.2).toInt().coerceAtLeast(50).coerceAtMost(avgBlockHeight * 2)

            val regions = mutableListOf<MutableList<TextBlockInfo>>()
            var currentRegion = mutableListOf(sortedByTop.first())
            var lastTop = sortedByTop.first().bounds.top
            // Theo dõi bounding box tích lũy của region hiện tại để kiểm tra overlap
            var regionLeft = sortedByTop.first().bounds.left
            var regionTop = sortedByTop.first().bounds.top
            var regionRight = sortedByTop.first().bounds.right
            var regionBottom = sortedByTop.first().bounds.bottom

            for (block in sortedByTop.drop(1)) {
                val currentTop = block.bounds.top
                // Kiểm tra overlap giữa block hiện tại và bounding box của region hiện tại.
                // Nếu 2 block chồng nhau đáng kể (>30% diện tích block nhỏ hơn),
                // luôn merge chúng bất kể top gap → tránh tách nhầm các block lồng nhau.
                val overlapLeft = maxOf(block.bounds.left, regionLeft)
                val overlapTop = maxOf(block.bounds.top, regionTop)
                val overlapRight = minOf(block.bounds.right, regionRight)
                val overlapBottom = minOf(block.bounds.bottom, regionBottom)
                val overlapArea = if (overlapRight > overlapLeft && overlapBottom > overlapTop) {
                    (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
                } else 0
                val blockArea = block.bounds.width() * block.bounds.height()
                val regionArea = (regionRight - regionLeft) * (regionBottom - regionTop)
                val smallerArea = minOf(blockArea, regionArea).coerceAtLeast(1)
                val overlapRatio = overlapArea.toFloat() / smallerArea

                if (currentTop - lastTop <= verticalThreshold || overlapRatio > 0.3f) {
                    currentRegion.add(block)
                } else {
                    regions.add(currentRegion)
                    currentRegion = mutableListOf(block)
                    regionLeft = block.bounds.left
                    regionTop = block.bounds.top
                    regionRight = block.bounds.right
                    regionBottom = block.bounds.bottom
                }
                // Cập nhật bounding box tích lũy
                regionLeft = minOf(regionLeft, block.bounds.left)
                regionTop = minOf(regionTop, block.bounds.top)
                regionRight = maxOf(regionRight, block.bounds.right)
                regionBottom = maxOf(regionBottom, block.bounds.bottom)
                lastTop = currentTop
            }
            if (currentRegion.isNotEmpty()) {
                regions.add(currentRegion)
            }

            regions.forEachIndexed { regionIndex, regionBlocks ->
                val sortedBlocks = regionBlocks.sortedWith(
                    compareByDescending<TextBlockInfo> { it.bounds.left }
                        .thenBy { it.bounds.top }
                )

                // Chia nhỏ region thành các sub-group theo chiều ngang.
                // Các cột trong manga đôi khi có x-range chồng lên nhau giữa nhiều bubble,
                // dẫn đến các block từ bubble khác nhau lọt vào cùng column/region.
                // Có 2 trường hợp để tách:
                // 1. khoảng trắng giữa prev.left → curr.right > avgBlockWidth * 1.5
                // 2. curr hoàn toàn nằm bên trái sub-group hiện tại (không overlap ngang) VÀ
                //    curr bắt đầu tại hoặc dưới đáy sub-group → là bubble khác xếp chéo dọc
                val subGroupThreshold = avgBlockWidth * 1.5f
                val subGroups = mutableListOf<MutableList<TextBlockInfo>>()
                var currentSubGroup = mutableListOf(sortedBlocks.first())
                // Theo dõi bounding box tích lũy của sub-group hiện tại
                var subGroupMinLeft = sortedBlocks.first().bounds.left
                var subGroupMaxRight = sortedBlocks.first().bounds.right
                var subGroupMinTop = sortedBlocks.first().bounds.top
                var subGroupMaxBottom = sortedBlocks.first().bounds.bottom
                for (idx in 1 until sortedBlocks.size) {
                    val prev = sortedBlocks[idx - 1]  // block bên phải hơn (left lớn hơn)
                    val curr = sortedBlocks[idx]      // block bên trái hơn
                    // Tiêu chí 1: khoảng trắng ngang giữa prev và curr vượt ngưỡng
                    val colGap = (prev.bounds.left - curr.bounds.right).toFloat().coerceAtLeast(0f)
                    // Tiêu chí 1b: curr không chồng x-range với sub-group VÀ có khoảng trắng ngang
                    //              đáng kể → 2 cột riêng biệt trong cùng region.
                    //              Ngưỡng = max(avgBlockWidth * 0.5, 20px) để tránh split nhầm khi
                    //              avgBlockWidth nhỏ (block chữ dọc hẹp ~15px → threshold chỉ 7.5px,
                    //              dễ split nhầm các cột liền kề cùng bubble có gap 4-9px).
                    val gapToSubGroup = (subGroupMinLeft - curr.bounds.right).toFloat().coerceAtLeast(0f)
                    val noXOverlapWithSubGroup = curr.bounds.right <= subGroupMinLeft
                    val separateColumnThreshold = (avgBlockWidth * 0.5f).coerceAtLeast(20f)
                    val isSeparateColumn = noXOverlapWithSubGroup && gapToSubGroup > separateColumnThreshold
                    // Tiêu chí 2: curr hoàn toàn nằm bên TRÁI sub-group (không overlap ngang)
                    //             VÀ curr bắt đầu tại hoặc sau đáy sub-group (xếp chéo dọc)
                    //             → cặp trên (x cao) và cặp dưới (x thấp) thuộc 2 bubble khác nhau
                    val noHorizontalOverlap = curr.bounds.right <= subGroupMinLeft
                    val startsAtOrBelowSubGroup = curr.bounds.top >= subGroupMaxBottom - 15
                    val isDiagonallyStacked = noHorizontalOverlap && startsAtOrBelowSubGroup
                    // Tiêu chí 3: curr chồng x-range với sub-group (cùng cột thực sự) nhưng
                    //             khoảng dọc giữa đáy sub-group và đỉnh curr quá lớn (theo cả 2 chiều).
                    //             Dùng subGroupMinTop để xử lý cả trường hợp curr nằm TRÊN sub-group
                    //             (do sort descending left, block trên có thể vào subGroup sau block dưới).
                    //             "Khoảng trống dọc" = gap giữa bounding rect của curr và bounding rect của sub-group.
                    val hasSameColumnOverlap = curr.bounds.right > subGroupMinLeft && curr.bounds.left < subGroupMaxRight
                    val actualVerticalGap = when {
                        curr.bounds.bottom <= subGroupMinTop -> subGroupMinTop - curr.bounds.bottom  // curr ở trên
                        curr.bounds.top >= subGroupMaxBottom -> curr.bounds.top - subGroupMaxBottom  // curr ở dưới
                        else -> 0  // curr chồng lên dọc với sub-group → không có gap
                    }
                    val isLargeVerticalGapSameColumn = hasSameColumnOverlap && actualVerticalGap > avgBlockHeight * 0.8f
                    if (colGap > subGroupThreshold || isSeparateColumn || isDiagonallyStacked || isLargeVerticalGapSameColumn) {
                        subGroups.add(currentSubGroup)
                        currentSubGroup = mutableListOf(curr)
                        subGroupMinLeft = curr.bounds.left
                        subGroupMaxRight = curr.bounds.right
                        subGroupMinTop = curr.bounds.top
                        subGroupMaxBottom = curr.bounds.bottom
                    } else {
                        currentSubGroup.add(curr)
                        subGroupMinLeft = minOf(subGroupMinLeft, curr.bounds.left)
                        subGroupMaxRight = maxOf(subGroupMaxRight, curr.bounds.right)
                        subGroupMinTop = minOf(subGroupMinTop, curr.bounds.top)
                        subGroupMaxBottom = maxOf(subGroupMaxBottom, curr.bounds.bottom)
                    }
                }
                subGroups.add(currentSubGroup)

                subGroups.forEachIndexed { subGroupIndex, subGroupBlocks ->
                    val mergedText = StringBuilder()
                    lateinit var mergedBounds: Rect
                    var minFontSize = Float.MAX_VALUE
                    var blockCount = 0

                    subGroupBlocks.forEach { block ->
                        if (mergedText.isNotEmpty()) {
                            mergedText.append(" ")
                        }
                        mergedText.append(block.text)
                        if (blockCount == 0) {
                            mergedBounds = Rect(block.bounds)
                        } else {
                            mergedBounds.set(
                                minOf(mergedBounds.left, block.bounds.left),
                                minOf(mergedBounds.top, block.bounds.top),
                                maxOf(mergedBounds.right, block.bounds.right),
                                maxOf(mergedBounds.bottom, block.bounds.bottom)
                            )
                        }
                        minFontSize = minOf(minFontSize, block.fontSize)
                        blockCount++
                    }

                    // Phân tích màu nền và màu text cho merged block
                    val (backgroundType, avgColor, textColor) = analyzeBackgroundAndTextColor(bitmap, mergedBounds)
                    // Re-enabled: Container classification after merging for better accuracy (TẠM THỜI TẮT)
                    val containerInfo = detectContainerInfo(bitmap, mergedBounds, (bitmap?.width ?: 0), (bitmap?.height ?: 0))

                    val mergedOriginalText = subGroupBlocks.mapNotNull { it.originalText }.joinToString("\n").ifBlank { mergedText.toString() }
                    val mergedBlock = TextBlockInfo(
                        text = mergedText.toString(),
                        originalText = mergedOriginalText,
                        bounds = mergedBounds,
                        fontSize = minFontSize,
                originalFontSize = subGroupBlocks.maxOfOrNull { it.originalFontSize ?: it.fontSize } ?: minFontSize,
                        isVertical = true, // Đánh dấu là vertical text để downstream merge đúng thứ tự RTL
                        wordCountsPerLine = null, // Reset wordCountsPerLine after merging
                        originalImageWidth = subGroupBlocks.firstOrNull()?.originalImageWidth,
                        originalImageHeight = subGroupBlocks.firstOrNull()?.originalImageHeight,
                        backgroundType = backgroundType,
                        averageBackgroundColor = avgColor,
                        originalTextColor = textColor,
                        containerInfo = containerInfo
                    )

                    Log.i("TranslationRepository", "Column #$columnIndex, Merged Region #$regionIndex-$subGroupIndex: text=${mergedBlock.text}, left=${mergedBlock.bounds.left}, top=${mergedBlock.bounds.top}, right=${mergedBlock.bounds.right}, bottom=${mergedBlock.bounds.bottom}")

                    mergedBlocks.add(mergedBlock)
                }
            }
        }

        // Sắp xếp kết quả cuối: nhóm theo dải ngang (band), trong mỗi band sắp xếp Phải → Trái (RTL)
        // Đây là thứ tự đọc đúng cho văn bản dọc tiếng Nhật/Trung (manga)
        if (mergedBlocks.isEmpty()) return mergedBlocks
        
        val sortedByTop = mergedBlocks.sortedBy { it.bounds.top }
        val bands = mutableListOf<MutableList<TextBlockInfo>>()
        var currentBand = mutableListOf(sortedByTop.first())
        var bandBottom = sortedByTop.first().bounds.bottom
        
        for (block in sortedByTop.drop(1)) {
            // Block overlap hoặc gần band hiện tại → cùng band
            val overlapWithBand = block.bounds.top < bandBottom
            val avgHeight = (block.bounds.height() + currentBand.last().bounds.height()) / 2
            val closeEnough = (block.bounds.top - bandBottom) < avgHeight
            
            if (overlapWithBand || closeEnough) {
                currentBand.add(block)
                bandBottom = maxOf(bandBottom, block.bounds.bottom)
            } else {
                bands.add(currentBand)
                currentBand = mutableListOf(block)
                bandBottom = block.bounds.bottom
            }
        }
        if (currentBand.isNotEmpty()) bands.add(currentBand)
        
        // Trong mỗi band: sắp xếp Phải → Trái (descending left)
        // Sau khi sort xong, chuyển isVertical = false để bản dịch tiếng Việt render theo chiều ngang
        // (tránh lỗi "bản dịch thành cột đứng" khi hệ thống vẽ ép mỗi ký tự một dòng)
        return bands.flatMap { band ->
            band.sortedByDescending { it.bounds.left }
        }.map { it.copy(isVertical = false) }
    }

    private fun determineTextOrientation(textBlocks: List<TextBlockInfo>, fullText: String): Boolean {
        val sampleText = fullText.take(100)
        val chinesePattern = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF]")
        val japanesePattern = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
        val koreanPattern = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]")

        val isChineseJapaneseOrKorean = chinesePattern.containsMatchIn(sampleText) ||
                japanesePattern.containsMatchIn(sampleText) ||
                koreanPattern.containsMatchIn(sampleText)

        if (isChineseJapaneseOrKorean && textBlocks.isNotEmpty()) {
            val verticalCount = textBlocks.count { block ->
                val bounds = block.bounds
                bounds.height() > bounds.width() * 1.5
            }
            val totalBlocks = textBlocks.size
            return verticalCount > totalBlocks * 0.6
        }
        return false
    }

    private fun adjustBoundsForTranslatedText(text: String, originalBounds: Rect, fontSize: Float, scaleFactor: Float): Rect {
        val charWidthEstimate = fontSize * 0.6f
        val textWidth = (text.length * charWidthEstimate).toInt()
        val left = originalBounds.left
        val top = originalBounds.top
        val right = (left + textWidth).coerceAtMost(originalBounds.right)
        val height = originalBounds.height().coerceAtLeast(fontSize.toInt())
        val bottom = top + height
        return Rect(left, top, right, bottom)
    }

    private fun calculateAdjustedFontSize(
        translatedText: String,
        originalText: String,
        originalBounds: Rect,
        originalFontSize: Float,
        isVertical: Boolean = false
    ): Float {
        // Tính số dòng trong văn bản dịch
        val translatedLines = translatedText.split("\n")
        val originalLines = originalText.split("\n")
        
        val availableWidth = originalBounds.width().toFloat()
        val availableHeight = originalBounds.height().toFloat()
        
        // Xử lý văn bản DỌC (vertical)
        if (isVertical) {
            // Đối với văn bản dọc:
            // - Số lượng ký tự (độ dài văn bản) ảnh hưởng đến HEIGHT (chiều cao)
            // - Chiều rộng thường chỉ có 1 ký tự
            
            // Loại bỏ ký tự xuống dòng vì chúng không được hiển thị trong vertical text
            val translatedCharsNoNewline = translatedText.replace("\n", "").length
            val originalCharsNoNewline = originalText.replace("\n", "").length
            
            // Tính tỷ lệ số ký tự
            val charRatio = if (originalCharsNoNewline > 0) {
                translatedCharsNoNewline.toFloat() / originalCharsNoNewline
            } else {
                1.0f
            }
            
            // Tính fontSize dựa trên HEIGHT (số ký tự chồng lên nhau)
            // line spacing = 1.15 cho vertical text (conservative để đảm bảo vừa)
            val lineSpacing = 1.1f
            val estimatedHeight = translatedCharsNoNewline * originalFontSize * lineSpacing

            val heightScale = if (estimatedHeight > availableHeight) {
                availableHeight / estimatedHeight
            } else {
                1.0f
            }
            
            
            // Đối với vertical, chiều rộng ít khi là vấn đề (thường chỉ 1 ký tự)
            // Nhưng vẫn cần kiểm tra xem fontSize có quá lớn không
            val charWidthEstimate = originalFontSize * 0.5f
            val estimatedWidth = charWidthEstimate // 1 ký tự trên mỗi "dòng"
            val widthScale = if (estimatedWidth > availableWidth) {
                availableWidth / estimatedWidth
            } else {
                1.0f
            }
            
            // Chọn scale nhỏ hơn để đảm bảo vừa
            val finalScale = minOf(widthScale, heightScale, 1.0f)
            
            // Tăng giới hạn tối thiểu fontSize lên 30% fontSize gốc cho vertical để text không quá to
            val minFontSize = maxOf(originalFontSize * 0.3f, 15f)
            val newFontSize = (originalFontSize * finalScale).coerceAtLeast(minFontSize)
            
            return newFontSize
        }
        
        // Xử lý văn bản NGANG (horizontal) - logic cũ
        // Tính độ dài trung bình mỗi dòng
        val avgTranslatedLineLength = if (translatedLines.isNotEmpty()) {
            translatedLines.sumOf { it.length }.toFloat() / translatedLines.size
        } else {
            translatedText.length.toFloat()
        }
        
        val avgOriginalLineLength = if (originalLines.isNotEmpty()) {
            originalLines.sumOf { it.length }.toFloat() / originalLines.size
        } else {
            originalText.length.toFloat()
        }
        
        // Tính tỷ lệ độ dài văn bản
        val lengthRatio = if (avgOriginalLineLength > 0) {
            avgTranslatedLineLength / avgOriginalLineLength
        } else {
            1.0f
        }
        
        // Tính tỷ lệ số dòng
        val lineRatio = if (originalLines.size > 0) {
            translatedLines.size.toFloat() / originalLines.size
        } else {
            1.0f
        }
        
        // Tính fontSize dựa trên chiều rộng
        val charWidthEstimate = originalFontSize * 0.6f
        val estimatedWidth = avgTranslatedLineLength * charWidthEstimate
        val widthScale = if (estimatedWidth > availableWidth) {
            availableWidth / estimatedWidth
        } else {
            1.0f
        }
        
        // Tính fontSize dựa trên chiều cao (số dòng)
        val estimatedHeight = translatedLines.size * originalFontSize * 1.1f // 1.2 là line spacing
        val heightScale = if (estimatedHeight > availableHeight) {
            availableHeight / estimatedHeight
        } else {
            1.0f
        }
        
        // Chọn scale nhỏ hơn để đảm bảo vừa cả width và height
        val finalScale = minOf(widthScale, heightScale, 1.0f)
        
    // Giới hạn tối thiểu fontSize bằng 30% fontSize gốc (nhưng không dưới 15f) để text không bị quá nhỏ mà vẫn vừa khung
    val minFontSize = maxOf(originalFontSize * 0.3f, 15f)
    val newFontSize = (originalFontSize * finalScale).coerceAtLeast(minFontSize)
//        Log.i("TranslationRepository", "[FONT-ADJUST-HORIZONTAL] Original: '${originalText.take(30)}...', " +
//            "Translated: '${translatedText.take(30)}...', " +
//            "lengthRatio=$lengthRatio, lineRatio=$lineRatio, " +
//            "widthScale=$widthScale, heightScale=$heightScale, " +
//            "originalFontSize=$originalFontSize, newFontSize=$newFontSize (min: $minFontSize)")
    return newFontSize
    }

    private suspend fun translateTextOffline(originalText: String, sourceLanguage: String): String = withContext(Dispatchers.IO) {
        if (originalText.isEmpty()) return@withContext ""
        if (sourceLanguage == "vi") return@withContext originalText
        try {
            val sourceLang = mapLanguageToMLKit(sourceLanguage)
            val translator = translators.getOrPut(sourceLang) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLang)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                    .build()
                Translation.getClient(options).also { translator ->
                    translator.downloadModelIfNeeded()
                        .addOnSuccessListener { Log.i("TranslationRepository", "Đã tải mô hình dịch cho $sourceLang") }
                        .addOnFailureListener { e -> Log.e("TranslationRepository", "Tải mô hình dịch cho $sourceLang thất bại", e) }
                }
            }
            translator.translate(originalText).await()
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Dịch ngoại tuyến thất bại cho văn bản: $originalText", e)
            originalText
        }
    }

    private suspend fun translateTextOnline(originalText: String, sourceLanguage: String): String = withContext(Dispatchers.IO) {
        if (originalText.isEmpty()) return@withContext ""
        if (sourceLanguage == "vi") return@withContext originalText
        try {
            val encodedText = URLEncoder.encode(originalText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLanguage&tl=vi&dt=t&q=$encodedText"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val json = response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("TranslationRepository", "Yêu cầu dịch trực tuyến thất bại: ${resp.code}")
                    return@withContext originalText
                }
                resp.body?.string()
            } ?: return@withContext originalText
            val jsonArray = JsonParser.parseString(json).asJsonArray
            if (jsonArray.size() == 0) return@withContext originalText
            val translations = mutableListOf<String>()
            val sentencesArray = jsonArray[0].asJsonArray
            for (sentence in sentencesArray) {
                val translationArray = sentence.asJsonArray
                val translatedText = translationArray[0].asString
                translations.add(translatedText)
            }
            translations.joinToString("")
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Dịch trực tuyến thất bại cho văn bản: $originalText", e)
            originalText
        }
    }

    private suspend fun translateTextWithGemini(originalText: String, sourceLanguage: String): String = withContext(Dispatchers.IO) {
        if (originalText.isEmpty()) return@withContext ""
        if (sourceLanguage == "vi") return@withContext originalText

        val triedKeys = mutableSetOf<String>()
        var lastError: Exception? = null
        val maxTries = 6

        for (i in 0 until maxTries) {
            val apiKeyInfo = poolManager.selectBestKey("gemini") ?: break
            val apiKey = apiKeyInfo.value
            val modelName = getCurrentGeminiModel()

            triedKeys.add(apiKey)

            try {
                val safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
                )

                val config = generationConfig {
                    temperature = 1.0f
                    topP = 1.0f
                    topK = 90
                    maxOutputTokens = 6000
                }

                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey,
                    safetySettings = safetySettings,
                    generationConfig = config
                )

                val prompt = TranslationPrompts.getMistralBasicPrompt(originalText)

                val response = generativeModel.generateContent(prompt)
                val content = response.text?.trim() ?: ""
                val translatedText = content.trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .trim()

                val analysisText = Regex("\\[ANALYSIS\\][\\s\\S]*?(\\[END ANALYSIS\\]|\\[/ANALYSIS\\])").find(content)?.value
                    ?: Regex("\\[ANALYSIS\\][\\s\\S]*?(?=\\n\\s*(?:\\*\\*)?Block #1)").find(content)?.value
                    ?: "Không tìm thấy [ANALYSIS]"
                val translationResult = content.replace(analysisText, "").trim()
                Log.d("TranslationRepository", "[DEBUG-RESULT] $analysisText")
                Log.d("TranslationRepository", "KẾT QUẢ DỊCH:\n$translationResult")

                // Nếu dịch thành công và khác với gốc thì trả về luôn
                if (!translatedText.equals(originalText, ignoreCase = true)) {
                    // Báo cáo số token
                    try {
                        val usage = response.usageMetadata
                        if (usage != null) {
                            Log.i("TranslationRepository", "[GEMINI-USAGE] Prompt: ${usage.promptTokenCount} | Completion: ${usage.candidatesTokenCount} | Total: ${usage.totalTokenCount} tokens")
                        }
                    } catch (e: Exception) {
                        Log.w("TranslationRepository", "Không thể lấy token usage từ Gemini: ${e.message}")
                    }
                    return@withContext translatedText
                }
            } catch (e: Exception) {
                lastError = e
                val keyPrefix = apiKey.take(10)
                Log.e("TranslationRepository", "[GEMINI-ERROR] API key bị lỗi: ${keyPrefix}... | Model: $modelName | Exception: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        // Nếu thử hết vẫn không dịch được, trả về văn bản gốc
        if (lastError != null) {
            val errorMessage = lastError!!.message
            Log.e("TranslationRepository", "[GEMINI-SUMMARY] Thử các key Gemini thất bại | Lỗi cuối: $errorMessage")
            Log.e("TranslationRepository", "[GEMINI-SUMMARY] Số keys đã thử: ${triedKeys.size}")
        }
        return@withContext originalText
    }

    suspend fun translateWithGeminiMultiScale(
        textBlocks: List<TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        sourceLang: String,
        targetLang: String,
        previousTranslation: List<TextBlockInfo>? = null, // Bản dịch của ảnh trước để tham khảo
        isAncientMode: Boolean = false,
        skipDetailedLogs: Boolean = false
    ): List<String>? {
        if (ocrResults.isEmpty() || textBlocks.isEmpty()) return null

        var lastError: Exception? = null
        val maxTries = 6 // Thử tối đa 6 lượt (kết hợp key và model)
        
        // Tạo context từ bản dịch ảnh trước (nếu có)
        val previousContextText = if (!previousTranslation.isNullOrEmpty()) {
            Log.i("TranslationRepository", "[GEMINI-PREV] Có bản dịch tham khảo với ${previousTranslation.size} blocks")
            
            // Phân tích và log ngôi xưng hô từ ảnh trước
            val allText = previousTranslation.joinToString(" ") { it.text.uppercase() }
            val pronouns = mutableListOf<String>()
            val hasToi = allText.contains(" TÔI ") || allText.contains("TÔI ")
            val hasMinh = allText.contains(" MÌNH ") || allText.contains("MÌNH ")
            val hasTao = allText.contains(" TAO ") || allText.contains("TAO ")
            val hasCau = allText.contains(" CẬU ") || allText.contains("CẬU ")
            val hasMay = allText.contains(" MÀY ") || allText.contains("MÀY ")
            val hasAnh = allText.contains(" ANH ") || allText.contains("ANH ")
            val hasEm = allText.contains(" EM ")
            
            if (hasToi) pronouns.add("TÔI")
            if (hasMinh) pronouns.add("MÌNH")
            if (hasTao) pronouns.add("TAO")
            if (hasCau) pronouns.add("CẬU")
            if (hasMay) pronouns.add("MÀY")
            if (hasAnh) pronouns.add("ANH")
            if (hasEm) pronouns.add("EM")
            
            // Xác định cặp ngôi chính
            val mainPair = when {
                hasToi && hasCau -> "TÔI-CẬU"
                hasMinh && hasCau -> "MÌNH-CẬU"
                hasTao && hasMay -> "TAO-MÀY"
                hasToi && hasAnh -> "TÔI-ANH"
                hasEm && hasAnh -> "EM-ANH"
                hasToi -> "TÔI"
                hasMinh -> "MÌNH"
                hasTao -> "TAO"
                else -> "không xác định"
            }
            
            Log.i("TranslationRepository", "[GEMINI-PREV] Đại từ phát hiện: ${pronouns.joinToString(", ")}")
            Log.i("TranslationRepository", "[GEMINI-PREV] Cặp ngôi xưng hô chính: $mainPair")
            
            TranslationPrompts.getPreviousContextText(previousTranslation)
        } else {
            Log.i("TranslationRepository", "[GEMINI-PREV] Không có bản dịch tham khảo")
            ""
        }
        
        // Tạo prompt với tất cả kết quả OCR từ các scale khác nhau
        val ocrResultsText = ocrResults.mapIndexed { index, (scale, text) ->
            "Kết quả quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n\n")
        
        // Đánh số các text blocks gốc (sử dụng index + 1 để khớp với logic parse 1-based)
        val numberedBlocks = textBlocks.mapIndexed { index, block ->
            val normalizedText = block.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            "Block #${index + 1}: Text='$normalizedText' Bounds: Rect(${block.bounds.left}, ${block.bounds.top} - ${block.bounds.right}, ${block.bounds.bottom})"
        }.joinToString("\n")
        
        var attempt = 0
        var skipped429 = 0
        while (attempt < maxTries) {
            val apiKeyInfo = poolManager.selectBestKey("gemini") ?: return null
            val useKey = apiKeyInfo.value
            val modelName = getCurrentGeminiModel()

            try {
                val safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
                )
                
                val config = generationConfig {
                    temperature = 1.0f
                    topP = 1.0f
                    topK = 90
                    maxOutputTokens = 6000
                }

                val instructions = TranslationPrompts.getGeminiMultiScalePrompt(
                    ocrResultsText = "DỮ LIỆU ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
                    numberedBlocks = "DANH SÁCH ĐƯỢC CUNG CẤP TRONG USER MESSAGE",
                    blockCount = textBlocks.size,
                    previousContextText = previousContextText,
                    isAncientMode = isAncientMode
                )

                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = useKey,
                    safetySettings = safetySettings,
                    generationConfig = config,
                    systemInstruction = content { text(instructions) }
                )

                val dataContent = """
                    === DỮ LIỆU OCR ===
                    $ocrResultsText

                    === BLOCKS CẦN DỊCH ===
                    $numberedBlocks
                """.trimIndent()
                
                val response = generativeModel.generateContent(dataContent)
                val content = response.text?.trim()

                if (content.isNullOrBlank()) {
                    Log.w("TranslationRepository", "[GEMINI] Response rỗng từ key, model $modelName")
                    continue
                }

                val analysisText = Regex("\\[ANALYSIS\\][\\s\\S]*?(\\[END ANALYSIS\\]|\\[/ANALYSIS\\])").find(content)?.value
                    ?: Regex("\\[ANALYSIS\\][\\s\\S]*?(?=\\n\\s*(?:\\*\\*)?Block #1)").find(content)?.value
                    ?: "Không tìm thấy [ANALYSIS]"
                val translationResult = content.replace(analysisText, "").trim()
                Log.d("TranslationRepository", "[DEBUG-RESULT] $analysisText")
                Log.d("TranslationRepository", "KẾT QUẢ DỊCH:\n$translationResult")

                // Báo cáo số token
                try {
                    val usage = response.usageMetadata
                    if (usage != null) {
                        Log.i("TranslationRepository", "[GEMINI-MULTI-USAGE] Prompt: ${usage.promptTokenCount} | Completion: ${usage.candidatesTokenCount} | Total: ${usage.totalTokenCount} tokens")
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "Không thể lấy token usage từ Gemini Multi-Scale: ${e.message}")
                }

                val translatedBlocks = parseMultiBlockResponse(content, textBlocks)

                return translatedBlocks
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                val keyPrefix = useKey.take(10)
                // Nếu là lỗi 429 hoặc throttling thì bỏ qua key này, không tăng attempt
                if (msg.contains("429") || msg.contains("too many requests") || msg.contains("throttl")) {
                    Log.w("TranslationRepository", "[GEMINI-MULTI-429] Key bị giới hạn tốc độ: ${keyPrefix}... | Model: $modelName | Lỗi: ${e.message} | Đã bỏ qua: ${skipped429 + 1}")
                    skipped429++
                    continue // thử key tiếp theo, không tăng attempt
                }
                lastError = e
                Log.e("TranslationRepository", "[GEMINI-MULTI-ERROR] Key bị lỗi: ${keyPrefix}... | Model: $modelName | Exception: ${e.javaClass.simpleName} - ${e.message} | Lần thử: ${attempt + 1}/$maxTries", e)
                attempt++ // chỉ tăng attempt nếu không phải lỗi 429/quota
            }
        }

        if (lastError != null) {
            val errorMessage = lastError.message
            Log.e("TranslationRepository", "[GEMINI-MULTI-SUMMARY] Tất cả key Gemini và ${geminiModels.size} model đều thất bại (multi-scale)")
            Log.e("TranslationRepository", "[GEMINI-MULTI-SUMMARY] Tổng lần thử: $attempt/$maxTries | Keys bị 429/quota: $skipped429 | Lỗi cuối: $errorMessage")
        }
        return null
    }
    
    private suspend fun translateWithGemini(inputText: String): String? {
        val apiKeyInfo = poolManager.selectBestKey("gemini") ?: run {
            Log.e("TranslationRepository", "No Gemini API keys available in pool.")
            return null
        }

        val apiKey = apiKeyInfo.value
        val modelName = getCurrentGeminiModel()

        val client = GenerativeModel(
            modelName = modelName,
            apiKey = apiKey
        )

        val prompt = "Translate the following text: $inputText"

        return try {
            val response = client.generateContent(prompt)
            val content = response.text ?: ""

            val analysisText = Regex("\\[ANALYSIS\\][\\s\\S]*?(\\[END ANALYSIS\\]|\\[/ANALYSIS\\])").find(content)?.value
                ?: Regex("\\[ANALYSIS\\][\\s\\S]*?(?=\\n\\s*(?:\\*\\*)?Block #1)").find(content)?.value
                ?: "Không tìm thấy [ANALYSIS]"
            val translationResult = content.replace(analysisText, "").trim()
            Log.d("TranslationRepository", "[DEBUG-RESULT] $analysisText")
            Log.d("TranslationRepository", "KẾT QUẢ DỊCH:\n$translationResult")

            if (content.isNotEmpty()) {
                // Báo cáo số token
                try {
                    val usage = response.usageMetadata
                    if (usage != null) {
                        Log.i("TranslationRepository", "[GEMINI-USAGE] Prompt: ${usage.promptTokenCount} | Completion: ${usage.candidatesTokenCount} | Total: ${usage.totalTokenCount} tokens")
                    }
                } catch (e: Exception) {
                    Log.w("TranslationRepository", "Không thể lấy token usage từ Gemini: ${e.message}")
                }
            }
            translationResult
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Error during translation: ${e.message}")
            null
        }
    }

    private fun postProcessOCRText(text: String, detectedScript: String?): String {
        if (text.isBlank()) return text

        var result = text

        // Loại bỏ các ký tự nhiễu phổ biến trong OCR manga
        // Các ký tự này thường bị nhận nhầm từ nét vẽ, mồ hôi, nếp gấp
        val noisePatterns = listOf(
            Regex("^[\\s\\-_\\.\\,\\:\\;\\!\\?]+$"),  // Chỉ chứa dấu câu
            Regex("^[\\d]+$"),  // Chỉ chứa số đơn lẻ
            Regex("^[|lIi1]+$"),  // Chỉ chứa các ký tự giống đường thẳng
            Regex("^[\\-]+$"),  // Chỉ chứa gạch ngang
            Regex("^[\\'\\.\\`]+$"),  // Chỉ chứa dấu chấm/nháy
            Regex("^[oO0○◯]+$"),  // Chỉ chứa hình tròn (thường là mồ hôi)
        )

        if (noisePatterns.any { it.matches(result.trim()) }) {
            return ""
        }

        // Loại bỏ các ký tự lẻ thường là nhiễu
        val singleNoiseChars = setOf('|', '/', '\\', '-', '_', '.', ',', '\'', '`', '"', '○', '◯', '・')
        if (result.length == 1 && result[0] in singleNoiseChars) {
            return ""
        }

        // Count Latin characters
        val latinCount = result.count { it in 'A'..'Z' || it in 'a'..'z' }
        val totalChars = result.filter { !it.isWhitespace() }.length

        // Nếu script được phát hiện là Latin (en, es) hoặc chứa nhiều chữ Latin
        val isLatinScript = detectedScript == "en" || detectedScript == "es" ||
            (latinCount > totalChars * 0.5)

        // FIX COMMON OCR ERRORS: Luôn fix khi có Latin characters (>30%)
        // Ngay cả khi script detection sai, vẫn fix common errors
        if (latinCount > totalChars * 0.3) {
            // Chuyển し (U+3057 - Hiragana Shi) và シ (U+30B7 - Katakana Shi) thành L
            result = result.replace('し', 'L').replace('シ', 'L')

            // Fix other common OCR errors for Latin text
            result = result.replace('ｌ', 'l')  // Fullwidth l → normal l
            result = result.replace('Ｌ', 'L')  // Fullwidth L → normal L
            result = result.replace('０', '0')  // Fullwidth 0 → normal 0
            result = result.replace('Ｏ', 'O')  // Fullwidth O → normal O
        }

        if (isLatinScript) {
            // Loại bỏ TẤT CẢ ký tự tượng hình (CJK) khỏi kết quả Latin
            // Bao gồm: CJK Unified Ideographs, CJK Extension A/B, Hiragana, Katakana, Hangul,
            // CJK Compatibility Ideographs, CJK Symbols, Enclosed CJK, Fullwidth forms
            result = result.replace(Regex("[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF" +
                "\u3040-\u309F\u30A0-\u30FF" + // Hiragana, Katakana (trừ đã convert ở trên)
                "\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F" + // Korean
                "\u3000-\u303F" + // CJK Symbols and Punctuation
                "\u31F0-\u31FF" + // Katakana Phonetic Extensions
                "\uFF65-\uFF9F" + // Halfwidth Katakana
                "\u2E80-\u2EFF" + // CJK Radicals Supplement
                "\u3200-\u32FF" + // Enclosed CJK Letters
                "\u3300-\u33FF" + // CJK Compatibility
                "\uFE30-\uFE4F" + // CJK Compatibility Forms
                "\uFF00-\uFF60" + // Fullwidth Latin -> giữ lại, chỉ bỏ CJK fullwidth
                "]"), "")

            // Nếu sau khi lọc CJK, text trống hoặc chỉ còn khoảng trắng/dấu câu -> trả về rỗng
            if (result.trim().isEmpty() || Regex("^[\\s\\-_\\.\\,\\:\\;\\!\\?]+$").matches(result.trim())) {
                return ""
            }
        }

        // Loại bỏ khoảng trắng thừa
        result = result.trim().replace(Regex("\\s+"), " ")

        return result
    }
    
    private fun isNoiseBlock(text: String, bounds: Rect, confidence: Float): Boolean {
        val cleanText = text.trim()
        val area = bounds.width() * bounds.height()
        val aspectRatio = bounds.height().toFloat() / bounds.width().coerceAtLeast(1)
        
        // Kiểm tra có chứa ký tự CJK không (bonus cho manga text)
        val hasCJK = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]").containsMatchIn(cleanText)
        
        // CJK text trong manga hầu như luôn hợp lệ (nằm trong bong bóng thoại)
        // Chỉ lọc CJK nếu là ký tự noise đã biết VÀ kích thước rất nhỏ
        if (hasCJK) {
            val cjkNoiseOnly = setOf("ー", "丨", "丶")
            // Giữ tất cả CJK text trừ khi là single noise char với area cực nhỏ
            if (cleanText in cjkNoiseOnly && area < MIN_BLOCK_AREA / 2 && bounds.width() < 15 && bounds.height() < 15) {
                Log.d("TranslationRepository", "[NOISE-FILTER] CJK noise '$text' rejected: area=$area")
                return true
            }
            // Tất cả CJK text khác: luôn giữ
            return false
        }
        
        // ---- Phần dưới chỉ áp dụng cho non-CJK text ----
        
        // Count noise indicators (cần nhiều dấu hiệu cùng lúc mới reject)
        var noiseScore = 0
        
        // 1. Block quá nhỏ về diện tích
        if (area < MIN_BLOCK_AREA / 2) {
            noiseScore += 2
        } else if (area < MIN_BLOCK_AREA) {
            noiseScore += 1
        }
        
        // 2. Block ngắn với aspect ratio kỳ lạ (nét vẽ mồ hôi, viền)
        if (cleanText.length <= 2) {
            if (aspectRatio > MAX_SINGLE_CHAR_ASPECT_RATIO || aspectRatio < 1.0f / MAX_SINGLE_CHAR_ASPECT_RATIO) {
                noiseScore += 2
            }
        }
        
        // 3. Confidence thấp
        if (confidence < 0.25f) {
            noiseScore += 3
        } else if (confidence < MIN_OCR_CONFIDENCE) {
            noiseScore += 1
        }
        
        // 4. Text chỉ chứa các ký tự nhiễu
        val noiseOnlyPattern = Regex("^[\\s\\-_\\.\\,\\|/\\\\\\'\"`○◯・]+$")
        if (noiseOnlyPattern.matches(cleanText)) {
            noiseScore += 2
        }
        
        // 5. Block chỉ có 1 ký tự phổ biến bị nhận nhầm + size nhỏ
        val commonFalsePositives = setOf(
            "I", "l", "|", "1", "-", "_", ".", ",", "'", "`",
            "○", "◯", "O", "o", "0"
        )
        if (cleanText in commonFalsePositives && (bounds.width() < 20 || bounds.height() < 20)) {
            noiseScore += 2
        }
        
        // Ngưỡng reject: cần noiseScore >= 4
        val isNoise = noiseScore >= 4
        
        if (isNoise) {
            Log.d("TranslationRepository", "[NOISE-FILTER] Block '$text' rejected: noiseScore=$noiseScore (area=$area, confidence=$confidence, aspectRatio=$aspectRatio)")
        }
        
        return isNoise
    }

    private fun postProcessTranslation(translatedText: String): String {
        var result = translatedText.trim()
        vietnameseImprovements.forEach { (old, new) -> result = result.replace(old, new, ignoreCase = true) }
        result = result.replace(" .", ".").replace(" ,", ",").replace(" !", "!").replace(" ?", "?")
        if (result.length < 20) {
            result = when {
                result.endsWith("là") -> "$result thế nào nhỉ?"
                result.contains("không") -> "$result đâu mà!"
                result.contains("có") -> "$result thật đấy!"
                else -> result
            }
        }
        return result
    }

    private fun mapLanguageToMLKit(language: String): String {
        return when (language) {
            "zh" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "es" -> TranslateLanguage.SPANISH
            "en" -> TranslateLanguage.ENGLISH
            "vi" -> TranslateLanguage.VIETNAMESE
            else -> TranslateLanguage.ENGLISH
        }
    }

    private fun detectLanguage(text: String): String? {
        val sampleText = text.take(100)
        val chinesePattern = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF]")
        val japanesePattern = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
        val koreanPattern = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]")
        val vietnamesePattern = Regex("[àáảãạăắằẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵ]")
        val latinPattern = Regex("[A-Za-z]")
        // Spanish-specific characters and common words
        val spanishAccentPattern = Regex("[ñÑáÁéÉíÍóÓúÚüÜ]")
        val spanishWordPattern = Regex("\\b(que|de|la|el|y|en|no|si|por|para|con|una|un|los|las|se|del|al)\\b", RegexOption.IGNORE_CASE)

        return when {
            vietnamesePattern.containsMatchIn(sampleText) -> "vi"
            koreanPattern.containsMatchIn(sampleText) -> "ko"
            japanesePattern.containsMatchIn(sampleText) -> "ja"
            chinesePattern.containsMatchIn(sampleText) -> "zh"
            spanishAccentPattern.containsMatchIn(sampleText) -> "es"
            // If common Spanish words appear enough, assume Spanish
            spanishWordPattern.findAll(sampleText).count() >= 2 -> "es"
            latinPattern.containsMatchIn(sampleText) && sampleText.count { it in 'A'..'z' } > sampleText.length * 0.5 -> "en"
            else -> null
        }
    }

    private fun getRotationDegrees(imageUri: Uri): Int {
        return try {
            val inputStream = application.contentResolver.openInputStream(imageUri)
            val exif = inputStream?.let { ExifInterface(it) }
            inputStream?.close()
            when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Không thể lấy góc xoay", e)
            0
        }
    }

    private fun isTextCoherent(text: String): Boolean {
        val normalizedText = text.trim().replace(Regex("\\s+"), " ")
        if (normalizedText.isEmpty()) return false

        val words = normalizedText.split(" ")
        if (words.size < 3) return false

        val vietnameseContentWords = listOf(
            "là", "có", "được", "đi", "làm", "nói", "nghĩ", "biết", "thấy", "muốn", "cần",
            "người", "cái", "nhà", "điều", "thời gian", "công việc", "hôm nay", "tốt", "nhanh", "đẹp"
        )
        val hasContent = words.any { word ->
            vietnameseContentWords.any { contentWord -> word.contains(contentWord, ignoreCase = true) }
        }

        val isNotPunctuationOnly = normalizedText.any { it.isLetterOrDigit() }

        val incompleteEndings = listOf(" và", " nhưng", " hoặc", " vì", " nếu")
        val endsAbruptly = incompleteEndings.any { normalizedText.endsWith(it, ignoreCase = true) }

        return hasContent && isNotPunctuationOnly && !endsAbruptly
    }

    // Merge các block theo bubbleId, chỉ merge block thực sự cùng dòng (ngang) hoặc cùng cột (dọc) trong từng bubble
    private fun mergeBlocksByBubble(blocks: List<TextBlockInfo>, bitmap: Bitmap?): List<TextBlockInfo> {
        if (blocks.isEmpty()) return emptyList()
        val grouped = blocks.groupBy { it.bubbleId ?: -1 }
        val merged = mutableListOf<TextBlockInfo>()
        for ((bubbleId, bubbleBlocks) in grouped) {
            if (bubbleBlocks.size == 1) {
                merged.add(bubbleBlocks.first())
                continue
            }
            val isVertical = bubbleBlocks.first().isVertical
            val sorted = if (isVertical) {
                // Sửa logic sort: Vertical text (Chinese/Japanese) đọc từ Phải sang Trái -> Sort descending by Left
                bubbleBlocks.sortedWith(compareByDescending<TextBlockInfo> { it.bounds.left }.thenBy { it.bounds.top })
            } else {
                bubbleBlocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            }
            // Group theo dòng/cột trong bubble
            val groups = mutableListOf<MutableList<TextBlockInfo>>()
            val threshold = if (isVertical) 0.3 else 0.2 // tỉ lệ khoảng cách cho phép
            
           // Log.i("TranslationRepository", "[MERGE] BubbleId=$bubbleId, isVertical=$isVertical, số blocks=${sorted.size}")
            
            for (block in sorted) {
                var assigned = false
                for (group in groups) {
                    val ref = group.first()
                    if (isVertical) {
                        // Kiểm tra cả left proximity và vertical gap để tránh merge các đoạn văn bản cách xa
                        val leftDiff = kotlin.math.abs(block.bounds.left - ref.bounds.left)
                        val avgWidth = (block.bounds.width() + ref.bounds.width()) / 2f
                        
                        // Tính khoảng cách dọc từ block cuối cùng trong group
                        val lastBlock = group.last()
                        val verticalGap = block.bounds.top - lastBlock.bounds.bottom
                        val avgHeight = (block.bounds.height() + lastBlock.bounds.height()) / 2f
                        
                        val leftThreshold = avgWidth * threshold
                        val gapThreshold = avgHeight * 1.5f
                        
                        // Kiểm tra nếu block nằm sát cột bên cạnh trong cùng speech bubble:
                        // các cột dọc liền kề nhau (right của cột này ≈ left của cột kia)
                        // và có overlap dọc đủ lớn → cho phép merge ngay cả khi leftDiff lớn.
                        val groupRight = group.maxOf { it.bounds.right }
                        val groupLeft = group.minOf { it.bounds.left }
                        val horizontalGapToGroup = when {
                            block.bounds.right <= groupLeft -> groupLeft - block.bounds.right // block ở bên trái group
                            block.bounds.left >= groupRight -> block.bounds.left - groupRight // block ở bên phải group
                            else -> 0 // overlap ngang
                        }
                        // Overlap dọc giữa block và toàn bộ group
                        val groupTop = group.minOf { it.bounds.top }
                        val groupBottom = group.maxOf { it.bounds.bottom }
                        val verticalOverlap = minOf(block.bounds.bottom, groupBottom) - maxOf(block.bounds.top, groupTop)
                        val minBlockHeight = minOf(block.bounds.height(), groupBottom - groupTop).coerceAtLeast(1)
                        val isAdjacentColumn = horizontalGapToGroup <= avgWidth * 0.8f &&
                            verticalOverlap >= minBlockHeight * 0.3f
                        
                        // Chỉ merge nếu left gần nhau VÀ khoảng cách dọc không quá lớn,
                        // HOẶC nếu là cột liền kề có overlap dọc đủ (cùng speech bubble)
                        if ((leftDiff < leftThreshold && verticalGap < gapThreshold) || isAdjacentColumn) {
                           // Log.i("TranslationRepository", "[MERGE-CHECK] ✓ MERGE vào group hiện tại")
                            group.add(block)
                            assigned = true
                            break
                        } else {
                            Log.i("TranslationRepository", "[MERGE-CHECK] ✗ KHÔNG MERGE (leftDiff=${leftDiff >= leftThreshold}, gap=${verticalGap >= gapThreshold}, adjacentCol=$isAdjacentColumn)")
                        }
                    } else {
                        val topDiff = kotlin.math.abs(block.bounds.top - ref.bounds.top)
                        val avgHeight = (block.bounds.height() + ref.bounds.height()) / 2f

                        // Tính vertical gap từ block cuối cùng trong group
                        val lastBlock = group.last()
                        val verticalGap = block.bounds.top - lastBlock.bounds.bottom

                        if (topDiff < avgHeight * threshold || (verticalGap > 0 && verticalGap < avgHeight * 1.0f)) {
                            val groupBounds = group.drop(1).fold(Rect(group.first().bounds)) { acc, b ->
                                acc.union(b.bounds)
                                acc
                            }
                            val xOverlap = block.bounds.left <= groupBounds.right && block.bounds.right >= groupBounds.left
                            val avgWidth = (block.bounds.width() + ref.bounds.width()) / 2f
                            val hGap = if (block.bounds.left > groupBounds.right) block.bounds.left - groupBounds.right else 0

                            // Thắt chặt khoảng cách ngang (giảm từ 4x xuống 1.5x)
                            if (xOverlap || hGap <= avgWidth * 1.5f) {
                                group.add(block)
                                assigned = true
                                break
                            }
                        }
                    }
                }
                if (!assigned) {
                    //Log.i("TranslationRepository", "[MERGE-CHECK] ⭐ TẠO GROUP MỚI cho block='${block.text.take(10)}'")
                    groups.add(mutableListOf(block))
                }
            }
            
            //Log.i("TranslationRepository", "[MERGE] Tổng số groups sau khi phân loại: ${groups.size}")
            
            // Merge từng group nhỏ trong bubble
            for (group in groups) {
                if (group.size == 1) {
                    merged.add(group.first())
                } else {
                    val mergedText = group.joinToString(if (isVertical) " " else "\n") { it.text }
                    val mergedBounds = group.drop(1).fold(Rect(group.first().bounds)) { acc, block ->
                        acc.union(block.bounds)
                        acc
                    }
                    val minFontSize = group.minOf { it.fontSize }
                    // Tối ưu: sử dụng màu của block đầu tiên thay vì phân tích lại để tăng tốc
                    val firstBlock = group.first()
                    val (backgroundType, avgColor, textColor) = if (bitmap != null) {
                        analyzeBackgroundAndTextColor(bitmap, mergedBounds)
                    } else {
                        Triple(firstBlock.backgroundType, firstBlock.averageBackgroundColor, firstBlock.originalTextColor)
                    }
                    // DISABLED: Container classification
                    val containerInfo = firstBlock.containerInfo ?: detectContainerInfo(bitmap, mergedBounds, (bitmap?.width ?: 0), (bitmap?.height ?: 0))
                    val mergedOriginalText = group.mapNotNull { it.originalText }.joinToString("\n").ifBlank { mergedText }
                    merged.add(
                        TextBlockInfo(
                            text = mergedText,
                            originalText = mergedOriginalText,
                            bounds = mergedBounds,
                            fontSize = minFontSize,
                            originalFontSize = group.maxOfOrNull { it.originalFontSize ?: it.fontSize } ?: minFontSize,
                            isVertical = isVertical,
                            wordCountsPerLine = null,
                            originalImageWidth = group.first().originalImageWidth,
                            originalImageHeight = group.first().originalImageHeight,
                            bubbleId = bubbleId,
                            backgroundType = backgroundType,
                            averageBackgroundColor = avgColor,
                            originalTextColor = textColor,
                            containerInfo = containerInfo
                        )
                    )
                }
            }
        }
        // Sắp xếp kết quả cuối: nếu có vertical blocks, sort Phải → Trái (RTL)
        val hasVertical = merged.any { it.isVertical }
        return if (hasVertical) {
            // Nhóm theo dải ngang (band), trong mỗi band sort Phải → Trái
            val sortedByTop = merged.sortedBy { it.bounds.top }
            if (sortedByTop.isEmpty()) return merged
            
            val bands = mutableListOf<MutableList<TextBlockInfo>>()
            var currentBand = mutableListOf(sortedByTop.first())
            var bandBottom = sortedByTop.first().bounds.bottom
            
            for (block in sortedByTop.drop(1)) {
                val overlapWithBand = block.bounds.top < bandBottom
                val avgHeight = (block.bounds.height() + currentBand.last().bounds.height()) / 2
                val closeEnough = (block.bounds.top - bandBottom) < avgHeight
                
                if (overlapWithBand || closeEnough) {
                    currentBand.add(block)
                    bandBottom = maxOf(bandBottom, block.bounds.bottom)
                } else {
                    bands.add(currentBand)
                    currentBand = mutableListOf(block)
                    bandBottom = block.bounds.bottom
                }
            }
            if (currentBand.isNotEmpty()) bands.add(currentBand)

            // Sau khi sort RTL xong, chuyển isVertical = false để bản dịch tiếng Việt render ngang
            bands.flatMap { band ->
                band.sortedByDescending { it.bounds.left }
            }.map { it.copy(isVertical = false) }
        } else {
            merged
        }
    }
}

