package com.example.ocrmanga.data.repositories

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.RecognitionResult
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
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
import com.google.gson.stream.JsonReader
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.StringReader
import com.example.ocrmanga.ui.screens.view.analyzeBackgroundAndTextColor

class TranslationRepository(private val application: Application) {

    // Hàm dịch lại 1 ảnh, trả về Pair<text dịch, list block dịch>
    suspend fun translateImage(imageUri: Uri, mode: TranslationMode): Pair<String, List<TextBlockInfo>> {
        val (translatedText, translatedBlocks, _) = recognizeAndTranslateText(imageUri, mode)
        return Pair(translatedText, translatedBlocks)
    }

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val koreanRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val translators = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()
    private val cache = mutableMapOf<String, Pair<String, List<TextBlockInfo>>>()
    private val httpClient = OkHttpClient()
    private val databaseHelper = DatabaseHelper(application)

    private var geminiApiKeys: List<String> = emptyList()
    private var currentGeminiKeyIndex = 0
    private var currentGeminiModelIndex = 0
    private val geminiModels = listOf("gemini-2.0-flash-lite","gemini-2.0-flash", "gemini-2.5-flash") // Add more models if needed

    // Mistral API keys
    private var mistralApiKeys: List<String> = emptyList()
    private var mistralKeyUsageQueue: MutableList<String> = mutableListOf()
    private val mistralApiUrl = "https://api.mistral.ai/v1/chat/completions"
    // Toast spam prevention for Mistral errors
    @Volatile private var mistralErrorToastShown = false

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
        loadGeminiApiKeys()
        loadMistralApiKeys()
    }

    private fun loadGeminiApiKeys() {
        geminiApiKeys = databaseHelper.getAllApiKeys()
            .filter { it.second == "gemini" && it.first.isNotBlank() }
            .map { it.first }
        if (geminiApiKeys.isEmpty()) {
            Log.w("TranslationRepository", "Không tìm thấy API key Gemini nào trong cơ sở dữ liệu.")
        } else {
            //Log.i("TranslationRepository", "Đã tải ${geminiApiKeys.size} API key Gemini.")
        }
    }

    private fun loadMistralApiKeys() {
        mistralApiKeys = databaseHelper.getAllApiKeys()
            .filter { it.second == "mistral" && it.first.isNotBlank() }
            .map { it.first }
        mistralKeyUsageQueue = mistralApiKeys.toMutableList()
        if (mistralApiKeys.isEmpty()) {
            Log.w("TranslationRepository", "Không tìm thấy API key Mistral nào trong cơ sở dữ liệu.")
        } else {
            //log.i("TranslationRepository", "Đã tải ${mistralApiKeys.size} API key Mistral.")
        }
    }
    // Hàm lấy API key Mistral tiếp theo: mỗi key chỉ dùng 1 lần/lượt, hết danh sách mới quay lại đầu
    private fun getNextMistralApiKey(): String? {
        if (mistralApiKeys.isEmpty()) {
            Log.e("TranslationRepository", "Không có API key Mistral nào được cấu hình. Không thể dịch.")
            return null
        }
        if (mistralKeyUsageQueue.isEmpty()) {
            // Khi queue rỗng, reload lại danh sách key từ database
            loadMistralApiKeys()
        }
        if (mistralKeyUsageQueue.isEmpty()) {
            Log.e("TranslationRepository", "Hàng đợi API key Mistral rỗng sau khi reload. Không thể dịch.")
            return null
        }
        return mistralKeyUsageQueue.removeAt(0)
    }

    /**
     * Hàm dịch văn bản bằng Mistral API
     * @param text Văn bản nguồn
     * @param sourceLang Ngôn ngữ nguồn (ví dụ: "ja", "zh", "en")
     * @param targetLang Ngôn ngữ đích (ví dụ: "vi")
     * @return Văn bản đã dịch hoặc null nếu lỗi
     */
    suspend fun translateWithMistral(text: String, sourceLang: String, targetLang: String): String? {
        var lastError: Exception? = null
        val maxTries = mistralApiKeys.size.coerceAtLeast(1)
        val isAllUpper = text.isNotBlank() && text == text.uppercase()
        for (i in 0 until maxTries) {
            val mistralKey = getNextMistralApiKey() ?: return null
            val prompt = buildString {
                append("\n")
                append("                    Vai trò : Bạn là chuyên gia tổ hợp văn bản và chuyển ngữ .\n")
                append("                    Nhiệm vụ : Hãy tổ hợp lại văn bản và  trả về 1 bản dịch lại với kiểu chữ hoa cho chính xác và đồng bộ nhất sang tiếng Việt: $text\n")
                append("                    Yêu cầu khi dịch :")
                append("                           1. Văn bản này là từ truyện tranh/manga, hãy dịch tự nhiên và phù hợp ngữ cảnh.\n")
                append("                           2. Có 1 số văn bản truyền vào bị lỗi hoặc bị thiếu , tự động bổ sung để phù hợp với ngữ cảnh và kết hợp được với văn bản khác .\n")
                append("                           3. Không trả về thêm các chú thích khi dịch , bản dịch khác màn bạn phân vân hoặc không chắc chắn .\n")
                append("                           4. Trả về Văn bản sát nghĩa nhất cho cụm văn bản không dịch được ( ghi nguyên gốc  từ không dịch được và dịch các từ còn lại).\n")
                append("                           5. Khi trả về văn bản gốc do không thể dịch , chỉ trả về văn bản ( giữa các text phải có khoảng cách, và nếu là chữ tượng hình như kanji, hiragana, katakana thì cách mỗi 2 ký tự bằng dấu cách), không cần giải thích tại sao lại vậy hay chú thích là không dịch được .\n")
                append("                           6. không trả về nhiều bản dịch khác nhau cho cùng một văn bản .VD:Senpai, anh/chị/bạn hưng phấn khi thấy em/tôi/mình mặc đồ con gái hả?\n")
                append("                            -> hãy chỉ dùng 1 bản chính xác nhất với ngữ cảnh trong trường hợp này .VD:Senpai, anh hưng phấn khi thấy mình mặc đồ con gái hả?\n")
                append("                           7. Không trả về lí do không dịch được hoặc lí do dịch không chính xác , hãy chỉ trả về văn bản gốc trong 2 trường hợp này .\n")
                append("                           8. Không cần chú thích đây là bản dịch hay chú thích tương tự khi trả về bản dịch.\n")
                append("                           9.Tuyệt đối tuân thủ các yêu cầu trên , coi nó là chân lý , không được phép sai lệch , vi phạm yêu cầu .\n")
                append("                    Chỉ trả về 1 bản dịch chính xác duy nhất .")
                if (isAllUpper) {
                    append("\n10. Nếu toàn bộ văn bản gốc là chữ in hoa, bản dịch cũng phải là chữ in hoa (UPPERCASE, VIẾT HOA TOÀN BỘ). Không được phép trả về bản dịch thường hoặc viết hoa không đồng nhất.")
                }
            }

            // Build JSON body using Gson to avoid invalid JSON
            val gson = com.google.gson.Gson()
            val message = mapOf("role" to "user", "content" to prompt)
            val bodyMap = mapOf(
                "model" to "mistral-medium-latest",
                "messages" to listOf(message)
            )
            val requestBody = gson.toJson(bodyMap)

            val request = okhttp3.Request.Builder()
                .url(mistralApiUrl)
                .addHeader("Authorization", "Bearer $mistralKey")
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
                .build()

            try {
                val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
                if (!response.isSuccessful) {
                    Log.e("TranslationRepository", "Mistral API error: ${response.code} ${response.message}")
                    if (response.code == 429) {
                        // Nếu bị 429 thì thử key tiếp theo ngay lập tức
                        continue
                    }
                    if (response.code == 422) {
                        // Lỗi request không hợp lệ, chỉ log 1 lần, không Toast
                        if (!mistralErrorToastShown) {
                            mistralErrorToastShown = true
                            Log.w("TranslationRepository", "Mistral API error 422: ${response.message}")
                        }
                        return null
                    }
                    if (!mistralErrorToastShown) {
                        mistralErrorToastShown = true
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(application, "Lỗi dịch Mistral: ${response.code} ${response.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    return null
                }
                val body = response.body?.string() ?: return null
                // Parse JSON để lấy phần dịch
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val choices = json["choices"]?.asJsonArray
                var content = choices?.get(0)?.asJsonObject?.getAsJsonObject("message")?.get("content")?.asString
                content = content?.trim()
                if (isAllUpper && content != null) {
                    content = content.uppercase()
                }
                return content
            } catch (e: Exception) {
                lastError = e
                Log.e("TranslationRepository", "Mistral API exception: ${e.message}", e)
                if (!mistralErrorToastShown) {
                    mistralErrorToastShown = true
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(application, "Lỗi dịch Mistral: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                // Nếu lỗi là HTTP 429 (Too Many Requests) từ OkHttp
                if (e is okhttp3.internal.http2.StreamResetException && e.errorCode == okhttp3.internal.http2.ErrorCode.ENHANCE_YOUR_CALM) {
                    continue
                }
                // Hoặc kiểm tra message có chứa 429 (phòng trường hợp khác)
                if (e.message?.contains("429") == true) {
                    continue
                }
                return null
            }
        }
        return null
    }

    private fun getNextGeminiApiKey(): String? {
        if (geminiApiKeys.isEmpty()) {
            Log.e("TranslationRepository", "Không có API key Gemini nào được cấu hình.")
            return null
        }
        val key = geminiApiKeys[currentGeminiKeyIndex]
        currentGeminiKeyIndex = (currentGeminiKeyIndex + 1) % geminiApiKeys.size
        if (currentGeminiKeyIndex == 0) {
            // Cycle through models when all keys have been used once
            currentGeminiModelIndex = (currentGeminiModelIndex + 1) % geminiModels.size
            //log.i("TranslationRepository", "Đã sử dụng hết các API key, chuyển sang model: ${geminiModels[currentGeminiModelIndex]}")
        }
        return key
    }

    private fun getCurrentGeminiModel(): String {
        return geminiModels[currentGeminiModelIndex]
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

    private fun preprocessImage(bitmap: Bitmap, scaleFactor: Float): Pair<Bitmap, Float> {
        val newWidth = (bitmap.width * scaleFactor).toInt()
        val newHeight = (bitmap.height * scaleFactor).toInt()
        val upscaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

        val grayscaleBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        val paint = Paint()
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        val colorFilter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = colorFilter
        canvas.drawBitmap(upscaledBitmap, 0f, 0f, paint)

        val contrastBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val contrastCanvas = Canvas(contrastBitmap)
        val contrastPaint = Paint()
        val contrastMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                1.5f, 0f, 0f, 0f, -50f,
                0f, 1.5f, 0f, 0f, -50f,
                0f, 0f, 1.5f, 0f, -50f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        val contrastFilter = ColorMatrixColorFilter(contrastMatrix)
        contrastPaint.colorFilter = contrastFilter
        contrastCanvas.drawBitmap(grayscaleBitmap, 0f, 0f, contrastPaint)

        return Pair(contrastBitmap, scaleFactor)
    }

    suspend fun recognizeAndTranslateText(imageUri: Uri, mode: TranslationMode): Triple<String, List<TextBlockInfo>, String> = withContext(Dispatchers.IO) {
        if (mode == TranslationMode.OFF) {
            //log.i("TranslationRepository", "Chế độ dịch đã tắt, bỏ qua việc dịch cho $imageUri")
            return@withContext Triple("", emptyList(), "zh")
        }

        // Reset Toast flag at the start of each batch
        if (mode == TranslationMode.MISTRAL) {
            mistralErrorToastShown = false
        }

        val cacheKey = "$imageUri-$mode"
        cache[cacheKey]?.let {
            //log.i("TranslationRepository", "Tìm thấy kết quả trong cache cho $imageUri: ${it.first}")
            // Lưu vào session nếu lấy từ cache
            lastTranslationSession.add(Pair(imageUri, Pair("(cache)", it.first)))
            return@withContext Triple(it.first, it.second, detectLanguage(it.first) ?: "zh")
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
            val rotationDegrees = getRotationDegrees(imageUri)
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
            val (rawText, textBlocks) = recognizeText(bitmap, rotationDegrees, forceScript = detectedScript)
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

            // --- TỰ ĐỘNG GÁN BUBBLE, MERGE, VÀ DỊCH ---
            val blocksWithBubble = assignSpeechBubblesToBlocks(textBlocks)
            val mergedBlocks = mergeBlocksByBubble(blocksWithBubble, bitmap!!)
            val blocks = mutableListOf<TextBlockInfo>()
            // Sử dụng coroutineScope để dịch song song các block
            kotlinx.coroutines.coroutineScope {
                val deferredBlocks = mergedBlocks.map { block ->
                    async {
                        var translatedText = when (mode) {
                            TranslationMode.OFFLINE -> translateTextOffline(block.text, sourceLanguage)
                            TranslationMode.ONLINE -> translateTextOnline(block.text, sourceLanguage)
                            TranslationMode.GEMINI -> translateTextWithGemini(block.text, sourceLanguage)
                            TranslationMode.OFF -> block.text
                            TranslationMode.MISTRAL -> translateWithMistral(block.text, sourceLanguage, "vi") ?: ""
                        }
                        if (translatedText != null && translatedText.length > 5) {
                            val detectedAfterTranslation = detectLanguage(translatedText) ?: "vi"
                            if (detectedAfterTranslation != "vi" && mode != TranslationMode.OFF) {
                                translatedText = when (mode) {
                                    TranslationMode.OFFLINE -> translateTextOffline(translatedText, detectedAfterTranslation)
                                    TranslationMode.ONLINE -> translateTextOnline(translatedText, detectedAfterTranslation)
                                    TranslationMode.GEMINI -> translateTextWithGemini(translatedText, detectedAfterTranslation)
                                    else -> translatedText
                                }
                            }
                        }
                        val naturalText = translatedText?.let { postProcessTranslation(it) }
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
                        val cleanedText = reformattedText.orEmpty().replace("**", "")
                        val newBounds = adjustBoundsForTranslatedText(cleanedText, block.bounds, block.fontSize, 1.0f)
                        block.copy(text = cleanedText, bounds = newBounds, fontFamily = "SF Toontime Extended")
                    }
                }
                blocks.addAll(deferredBlocks.awaitAll())
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
                        TranslationMode.OFF -> block.text
                    }
                    val detectedAfterTranslation = detectLanguage(translatedText) ?: "vi"
                    if (detectedAfterTranslation != "vi" && mode != TranslationMode.OFF) {
                        translatedText = when (mode) {
                            TranslationMode.OFFLINE -> translateTextOffline(translatedText, detectedAfterTranslation)
                            TranslationMode.ONLINE -> translateTextOnline(translatedText, detectedAfterTranslation)
                            TranslationMode.GEMINI -> translateTextWithGemini(translatedText, detectedAfterTranslation)
                            TranslationMode.MISTRAL -> translateWithMistral(translatedText, detectedAfterTranslation, "vi") ?: translatedText
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
                    blocks2.add(block.copy(text = reformattedText, bounds = newBounds))
                }
                val resultText2 = blocks2.joinToString("\n") { it.text }
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
                val cleanedText = block.text.replace("**", "")
                if (lang != "vi" && mode != TranslationMode.OFF) {
                    val retryText = when (mode) {
                        TranslationMode.OFFLINE -> translateTextOnline(cleanedText, sourceLanguage)
                        TranslationMode.ONLINE -> translateTextWithGemini(cleanedText, sourceLanguage)
                        TranslationMode.GEMINI -> translateTextOffline(cleanedText, sourceLanguage)
                        TranslationMode.MISTRAL -> translateWithMistral(cleanedText, sourceLanguage, "vi") ?: cleanedText
                        else -> cleanedText
                    }
                    val retryLang = detectLanguage(retryText) ?: ""
                    if (retryLang == "vi") {
                        block.copy(text = postProcessTranslation(retryText).replace("**", ""), fontFamily = "SF Toontime Extended")
                    } else {
                        block.copy(text = cleanedText, fontFamily = "SF Toontime Extended")
                    }
                } else {
                    block.copy(text = cleanedText, fontFamily = "SF Toontime Extended")
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
        }
    }

    // recognizeText mới: cho phép chỉ quét preview hoặc ép loại recognizer
    private suspend fun recognizeText(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onlyPreview: Boolean = false,
        forceScript: String? = null
    ): Pair<String, List<TextBlockInfo>> = withContext(Dispatchers.IO) {
        // Tối ưu tốc độ: giảm số scale factors và ưu tiên recognizer chính xác
        val scaleFactors = if (onlyPreview) listOf(0.95f, 1.003f, 1.08f, 1.12f) else listOf(0.95f, 1.003f, 1.08f, 1.12f, 1.18f)
        val recognizers = when (forceScript) {
            "zh" -> listOf(chineseRecognizer)
            "ja" -> listOf(japaneseRecognizer)
            "ko" -> listOf(koreanRecognizer)
            "en" -> listOf(latinRecognizer)
            else -> listOf(chineseRecognizer, japaneseRecognizer, koreanRecognizer, latinRecognizer)
        }
        val deferredResults = scaleFactors.flatMap { scale ->
            recognizers.map { recognizer ->
                async {
                    var preprocessedBitmap: Bitmap? = null
                    try {
                        val (preBitmap, _) = preprocessImage(bitmap, scale)
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
        val scriptPattern = when (forceScript) {
            "zh" -> Regex("[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF]") // Chinese
            "ja" -> Regex("[\u3040-\u309F\u30A0-\u30FF]") // Japanese
            "ko" -> Regex("[\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F]") // Korean
            else -> Regex("[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F]")
        }
        // Chọn bestResult: ưu tiên confidence, text dài, nhiều block, nhiều ký tự script mong muốn
        val bestResult = results.maxByOrNull { result ->
            val elements = result.textResult.textBlocks.flatMap { b -> b.lines }.flatMap { l -> l.elements }
            val confidence = if (elements.isEmpty()) 0.0 else elements.sumOf { e -> e.confidence.toDouble() } / elements.size
            val length = result.textResult.text.length
            val blockCount = result.textResult.textBlocks.size
            val scriptCharCount = scriptPattern.findAll(result.textResult.text).count()
            // Ưu tiên: confidence * 2 + length/100 + blockCount*0.5 + scriptCharCount*0.2
            (confidence * 2.0) + (length / 100.0) + (blockCount * 0.5) + (scriptCharCount * 0.2)
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

                val textBlocks = alternativeTextResult.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        val bounds = line.boundingBox ?: Rect()
                        val scaledBounds = Rect(
                            (bounds.left / alternativeScaleFactor).toInt(),
                            (bounds.top / alternativeScaleFactor).toInt(),
                            (bounds.right / alternativeScaleFactor).toInt(),
                            (bounds.bottom / alternativeScaleFactor).toInt()
                        )
                        val fontSizes = line.elements.mapNotNull { element ->
                            element.boundingBox?.height()?.toFloat()?.div(alternativeScaleFactor)
                        }
                        val fontSize = if (fontSizes.isNotEmpty()) {
                            fontSizes.sorted()[fontSizes.size / 2].coerceAtMost(alternativeAvgFontSize * 1.2f)
                        } else {
                            alternativeAvgFontSize
                        }
                        val wordCount = line.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        // Phân tích màu nền và màu text
                        val (backgroundType, avgColor, textColor) = analyzeBackgroundAndTextColor(bitmap, scaledBounds)
                        TextBlockInfo(
                            text = line.text, 
                            bounds = scaledBounds, 
                            fontSize = fontSize, 
                            wordCountsPerLine = listOf(wordCount), 
                            originalImageWidth = bitmap.width, 
                            originalImageHeight = bitmap.height,
                            backgroundType = backgroundType,
                            averageBackgroundColor = avgColor,
                            originalTextColor = textColor
                        )
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

                val isVertical = determineTextOrientation(normalizedTextBlocks, alternativeTextResult.text)
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
                return@withContext fullText to processedTextBlocks
            }
        }

        // Process text blocks with font size normalization
        val textBlocks = bestTextResult.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val bounds = line.boundingBox ?: Rect()
                val scaledBounds = Rect(
                    (bounds.left / bestScaleFactor).toInt(),
                    (bounds.top / bestScaleFactor).toInt(),
                    (bounds.right / bestScaleFactor).toInt(),
                    (bounds.bottom / bestScaleFactor).toInt()
                )
                val fontSizes = line.elements.mapNotNull { it.boundingBox?.height()?.toFloat()?.div(bestScaleFactor) }
                val fontSize = if (fontSizes.isNotEmpty()) {
                    fontSizes.sorted()[fontSizes.size / 2].coerceAtMost(bestAvgFontSize * 1.2f)
                } else {
                    bestAvgFontSize
                }
                val wordCount = line.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                // Phân tích màu nền và màu text
                val (backgroundType, avgColor, textColor) = analyzeBackgroundAndTextColor(bitmap, scaledBounds)
                TextBlockInfo(
                    text = line.text, 
                    bounds = scaledBounds, 
                    fontSize = fontSize, 
                    wordCountsPerLine = listOf(wordCount), 
                    originalImageWidth = bitmap.width, 
                    originalImageHeight = bitmap.height,
                    backgroundType = backgroundType,
                    averageBackgroundColor = avgColor,
                    originalTextColor = textColor
                )
            }
        }

        // Check for font size consistency within clusters
        val clusters = groupBlocksIntoClusters(textBlocks)
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
        val threshold = 60 // px, tăng threshold để tránh merge nhầm cụm gần nhau
        val iouThreshold = 0.25f // Tăng IoU tối thiểu để merge
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
                if (iou(block.bounds, last.bounds) > iouThreshold && isVerticalOverlapEnough(block.bounds, last.bounds) && !isTooFarVertical(block.bounds, last.bounds)) {
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
        val verticalThreshold = 50
        val horizontalThreshold = 100

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
        val verticalThreshold = (avgTopGap * 0.5).toInt().coerceAtLeast(20)
        val verticalProximityThreshold = avgTopGap.coerceAtLeast(30)

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

                val topDifference = abs(clusterTop - otherTop)
                val isTopSimilar = topDifference <= verticalThreshold

                val isVerticallyOverlapping = clusterTop <= otherBottom && otherTop <= clusterBottom
                val yDistance = if (otherTop > clusterBottom) {
                    otherTop - clusterBottom
                } else {
                    clusterTop - otherBottom
                }
                val isVerticallyClose = yDistance <= verticalProximityThreshold

                if (isTopSimilar && (isVerticallyOverlapping || isVerticallyClose)) {
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
            val mergedBlock = TextBlockInfo(
                text = mergedText.toString(),
                bounds = Rect(mergedBounds),
                fontSize = minFontSize,
                wordCountsPerLine = null, // Reset wordCountsPerLine after merging
                originalImageWidth = sortedBlocks.firstOrNull()?.originalImageWidth,
                originalImageHeight = sortedBlocks.firstOrNull()?.originalImageHeight,
                backgroundType = backgroundType,
                averageBackgroundColor = avgColor,
                originalTextColor = textColor
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
        val leftValues = sortedByLeft.map { it.bounds.left }
        val leftGaps = leftValues.zipWithNext { a, b -> b - a }.filter { it > 0 }
        val avgLeftGap = if (leftGaps.isNotEmpty()) leftGaps.average().toInt() else 100
        val horizontalThreshold = (avgLeftGap * 0.8).toInt().coerceAtLeast(50)

        val columns = mutableListOf<MutableList<TextBlockInfo>>()
        var currentColumn = mutableListOf(sortedByLeft.first())
        var lastLeft = sortedByLeft.first().bounds.left

        for (block in sortedByLeft.drop(1)) {
            val currentLeft = block.bounds.left
            if (currentLeft - lastLeft <= horizontalThreshold) {
                currentColumn.add(block)
            } else {
                columns.add(currentColumn)
                currentColumn = mutableListOf(block)
            }
            lastLeft = currentLeft
        }
        if (currentColumn.isNotEmpty()) {
            columns.add(currentColumn)
        }

        val mergedBlocks = mutableListOf<TextBlockInfo>()
        columns.forEachIndexed { columnIndex, columnBlocks ->
            val sortedByTop = columnBlocks.sortedBy { it.bounds.top }
            val topValues = sortedByTop.map { it.bounds.top }
            val topGaps = topValues.zipWithNext { a, b -> b - a }.filter { it > 0 }
            val avgTopGap = if (topGaps.isNotEmpty()) topGaps.average().toInt() else 100
            val verticalThreshold = (avgTopGap * 0.8).toInt().coerceAtLeast(50)

            val regions = mutableListOf<MutableList<TextBlockInfo>>()
            var currentRegion = mutableListOf(sortedByTop.first())
            var lastTop = sortedByTop.first().bounds.top

            for (block in sortedByTop.drop(1)) {
                val currentTop = block.bounds.top
                if (currentTop - lastTop <= verticalThreshold) {
                    currentRegion.add(block)
                } else {
                    regions.add(currentRegion)
                    currentRegion = mutableListOf(block)
                }
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
                val mergedBlock = TextBlockInfo(
                    text = mergedText.toString(),
                    bounds = mergedBounds,
                    fontSize = minFontSize,
                    wordCountsPerLine = null, // Reset wordCountsPerLine after merging
                    originalImageWidth = sortedBlocks.firstOrNull()?.originalImageWidth,
                    originalImageHeight = sortedBlocks.firstOrNull()?.originalImageHeight,
                    backgroundType = backgroundType,
                    averageBackgroundColor = avgColor,
                    originalTextColor = textColor
                )

                Log.i("TranslationRepository", "Column #$columnIndex, Merged Region #$regionIndex: text=${mergedBlock.text}, left=${mergedBlock.bounds.left}, top=${mergedBlock.bounds.top}, right=${mergedBlock.bounds.right}, bottom=${mergedBlock.bounds.bottom}")

                mergedBlocks.add(mergedBlock)
            }
        }

        return mergedBlocks.sortedBy { it.bounds.top }
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
            if (!response.isSuccessful) {
                Log.e("TranslationRepository", "Yêu cầu dịch trực tuyến thất bại: ${response.code}")
                return@withContext originalText
            }
            val json = response.body?.string() ?: return@withContext originalText
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

        val triedKeys = mutableSetOf<Int>()
        val triedModels = mutableSetOf<Int>()
        var lastError: Exception? = null

        repeat(geminiApiKeys.size * geminiModels.size) {
            val apiKeyIndex = currentGeminiKeyIndex
            val modelIndex = currentGeminiModelIndex
            val apiKey = getNextGeminiApiKey()
            val modelName = getCurrentGeminiModel()
            if (apiKey == null) return@withContext originalText

            triedKeys.add(apiKeyIndex)
            triedModels.add(modelIndex)

            try {
                val safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.MEDIUM_AND_ABOVE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.MEDIUM_AND_ABOVE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.MEDIUM_AND_ABOVE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.MEDIUM_AND_ABOVE),
                )

                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey,
                    safetySettings = safetySettings
                )

                val prompt = """
                    Vai trò : Bạn là chuyên gia tổ hợp văn bản và chuyển ngữ .
                    Nhiệm vụ : Hãy tổ hợp lại văn bản và  trả về 1 bản dịch lại với kiểu chữ hoa cho chính xác nhất sang tiếng Việt: $originalText
                    Yêu cầu khi dịch :1. Văn bản này là từ truyện tranh/manga, hãy dịch tự nhiên và phù hợp ngữ cảnh.
                           2. Có 1 số văn bản truyền vào bị lỗi hoặc bị thiếu , tự động bổ sung để phù hợp với ngữ cảnh và kết hợp được với văn bản khác .
                           3. Không trả về thêm các chú thích khi dịch , bản dịch khác màn bạn phân vân hoặc không chắc chắn .
                           4. Trả về Văn bản sát nghĩa nhất cho cụm văn bản không dịch được ( ghi nguyên gốc  từ không dịch được và dịch các từ còn lại).
                           5. không trả về nhiều bản dịch khác nhau cho cùng một văn bản .VD:Senpai, anh/chị/bạn hưng phấn khi thấy em/tôi/mình mặc đồ con gái hả?
                            -> hãy chỉ dùng 1 bản chính xác nhất với ngữ cảnh trong trường hợp này .VD:Senpai, anh hưng phấn khi thấy mình mặc đồ con gái hả?
                           6. Không trả về lí do không dịch được hoặc lí do dịch không chính xác , hãy chỉ trả về văn bản gốc trong 2 trường hợp này .
                           7.Tuyệt đối tuân thủ các yêu cầu trên , coi nó là chân lý , không được phép sai lệch , vi phạm yêu cầu .
                    Chỉ trả về 1 bản dịch chính xác duy nhất .
                """.trimIndent()

                val response = generativeModel.generateContent(prompt)
                val translatedText = response.text?.trim()
                    ?.removeSurrounding("\"")
                    ?.removeSurrounding("'")
                    ?.trim() ?: originalText

                // Nếu dịch thành công và khác với gốc thì trả về luôn
                if (!translatedText.equals(originalText, ignoreCase = true)) {
                    //Log.i("TranslationRepository", "Gemini translated: $originalText -> $translatedText (model=$modelName, key=${apiKey.take(5)}...)")
                    return@withContext translatedText
                }
            } catch (e: Exception) {
                lastError = e
                Log.e("TranslationRepository", "Dịch bằng Gemini thất bại với model=$modelName, key=${apiKey.take(5)}...: ${e.message}")
            }
            // Nếu chưa thử hết key/model thì tiếp tục, còn không thì break
        }

        // Nếu thử hết vẫn không dịch được, trả về văn bản gốc
        lastError?.let { Log.e("TranslationRepository", "Tất cả key/model đều thất bại: ${it.message}") }
        return@withContext originalText
    }

    private suspend fun translateWithGemini(inputText: String): String? {
        if (geminiApiKeys.isEmpty()) {
            Log.e("TranslationRepository", "No API keys available.")
            return null
        }

        val apiKey = geminiApiKeys[currentGeminiKeyIndex]
        val client = GenerativeModel(
            modelName = currentGeminiModelIndex.toString(),
            apiKey = apiKey
        )

        val prompt = "Translate the following text: $inputText"

        return try {
            val response = client.generateContent(prompt)
            val translatedText = response.text

            // Cycle to the next API key
            currentGeminiKeyIndex = (currentGeminiKeyIndex + 1) % geminiApiKeys.size
            if (currentGeminiKeyIndex == 0) {
                currentGeminiModelIndex = if (currentGeminiModelIndex == 0) 1 else 0
            }

            translatedText
        } catch (e: IOException) {
            Log.e("TranslationRepository", "Error during translation: ${e.message}")
            null
        }
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

        return when {
            vietnamesePattern.containsMatchIn(sampleText) -> "vi"
            koreanPattern.containsMatchIn(sampleText) -> "ko"
            japanesePattern.containsMatchIn(sampleText) -> "ja"
            chinesePattern.containsMatchIn(sampleText) -> "zh"
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
                bubbleBlocks.sortedWith(compareBy({ it.bounds.left }, { it.bounds.top }))
            } else {
                bubbleBlocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            }
            // Group theo dòng/cột trong bubble
            val groups = mutableListOf<MutableList<TextBlockInfo>>()
            val threshold = if (isVertical) 0.3 else 0.2 // tỉ lệ khoảng cách cho phép
            for (block in sorted) {
                var assigned = false
                for (group in groups) {
                    val ref = group.first()
                    if (isVertical) {
                        val leftDiff = kotlin.math.abs(block.bounds.left - ref.bounds.left)
                        val avgWidth = (block.bounds.width() + ref.bounds.width()) / 2f
                        if (leftDiff < avgWidth * threshold) {
                            group.add(block)
                            assigned = true
                            break
                        }
                    } else {
                        val topDiff = kotlin.math.abs(block.bounds.top - ref.bounds.top)
                        val avgHeight = (block.bounds.height() + ref.bounds.height()) / 2f
                        if (topDiff < avgHeight * threshold) {
                            group.add(block)
                            assigned = true
                            break
                        }
                    }
                }
                if (!assigned) groups.add(mutableListOf(block))
            }
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
                    merged.add(
                        TextBlockInfo(
                            text = mergedText,
                            bounds = mergedBounds,
                            fontSize = minFontSize,
                            isVertical = isVertical,
                            wordCountsPerLine = null,
                            originalImageWidth = group.first().originalImageWidth,
                            originalImageHeight = group.first().originalImageHeight,
                            bubbleId = bubbleId,
                            backgroundType = backgroundType,
                            averageBackgroundColor = avgColor,
                            originalTextColor = textColor
                        )
                    )
                }
            }
        }
        return merged
    }
}
