package com.example.ocrmanga.data.translation

import android.app.Application
import com.example.ocrmanga.utils.AppLogger as Log
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient

/**
 * Hệ thống dịch thuật theo mô hình đội nhóm:
 *
 * - PHIÊN DỊCH VIÊN (Translator): Dịch từng block (đã thực hiện ở bước trước)
 * - QUẢN LÝ (Manager): Review toàn bộ bản dịch, đánh giá chất lượng
 *   → Nếu block nào chưa OK → yêu cầu phiên dịch viên dịch lại
 *   → Gemini: 1 vòng review | Mistral: 3 vòng review
 */
class TranslationTeamManager(private val application: Application) {

    companion object {
        private const val TAG = "TeamManager"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val databaseHelper = DatabaseHelper(application)

    // API keys
    private var geminiApiKeys: List<String> = emptyList()
    private var mistralApiKeys: List<String> = emptyList()
    private var currentGeminiKeyIndex = 0
    private var currentMistralKeyIndex = 0

    private val mistralApiUrl = "https://api.mistral.ai/v1/chat/completions"
    private val mistralModel = "mistral-medium-latest"
    private val geminiModel = "gemini-flash-latest"

    init {
        loadApiKeys()
    }

    private fun loadApiKeys() {
        val allKeys = databaseHelper.getAllApiKeys()
        geminiApiKeys = allKeys.filter { it.second == "gemini" && it.first.isNotBlank() }.map { it.first }
        mistralApiKeys = allKeys.filter { it.second == "mistral" && it.first.isNotBlank() }.map { it.first }
    }

    private fun getNextGeminiKey(): String? {
        if (geminiApiKeys.isEmpty()) return null
        val key = geminiApiKeys[currentGeminiKeyIndex % geminiApiKeys.size]
        currentGeminiKeyIndex++
        return key
    }

    private fun getNextMistralKey(): String? {
        if (mistralApiKeys.isEmpty()) return null
        val key = mistralApiKeys[currentMistralKeyIndex % mistralApiKeys.size]
        currentMistralKeyIndex++
        return key
    }

    // ========================
    // DATA CLASS
    // ========================

    data class ReviewResult(
        val allApproved: Boolean,
        val rejections: Map<Int, String> // blockIndex -> lý do cần sửa
    )

    // ========================
    // ENTRY POINT
    // ========================

    /**
     * Nhận bản dịch ban đầu (từ TranslationRepository), thực hiện review & revision.
     *
     * @param initialTranslations Bản dịch đã có từ translateWithMistral/GeminiMultiScale
     * @param textBlocks Danh sách blocks gốc (text CJK)
     * @param mode GEMINI hoặc MISTRAL
     * @param isAncientMode Chế độ cổ trang
     * @return Bản dịch đã qua review (hoặc bản gốc nếu review thất bại)
     */
    suspend fun orchestrateReview(
        initialTranslations: List<String>,
        textBlocks: List<TextBlockInfo>,
        mode: TranslationMode,
        isAncientMode: Boolean = false
    ): List<String> {
        if (initialTranslations.isEmpty() || textBlocks.isEmpty()) return initialTranslations

        val maxReviewRounds = when (mode) {
            TranslationMode.GEMINI -> 1
            TranslationMode.MISTRAL -> 3
            else -> return initialTranslations // OFF, ONLINE, OFFLINE không cần review
        }

        Log.i(TAG, "═══ BẮT ĐẦU REVIEW DỊCH THUẬT ═══")
        Log.i(TAG, "Mode: $mode | Số vòng review tối đa: $maxReviewRounds | Số blocks: ${textBlocks.size}")

        val current = initialTranslations.toMutableList()
        val frozenBlocks = mutableSetOf<Int>() // Blocks đã sửa 1 lần → không cho reject lại

        for (round in 1..maxReviewRounds) {
            Log.i(TAG, "── Vòng review #$round/$maxReviewRounds ──")

            // BƯỚC 1: Manager review tất cả bản dịch
            val review = managerReview(textBlocks, current, mode, isAncientMode, frozenBlocks)

            if (review == null) {
                Log.w(TAG, "Review thất bại ở vòng $round, giữ bản dịch hiện tại")
                break
            }

            if (review.allApproved) {
                Log.i(TAG, "✓ Manager duyệt tất cả blocks ở vòng $round")
                break
            }

            Log.i(TAG, "✗ Manager yêu cầu sửa ${review.rejections.size} blocks: ${review.rejections.keys}")

            // BƯỚC 2: Phiên dịch viên dịch lại các blocks bị reject
            for ((blockIndex, reason) in review.rejections) {
                if (blockIndex < 0 || blockIndex >= textBlocks.size) continue
                if (blockIndex in frozenBlocks) {
                    Log.i(TAG, "[REVISION] Block #${blockIndex + 1}: ĐÃ FREEZE - bỏ qua")
                    continue
                }

                val originalText = textBlocks[blockIndex].text
                val currentTranslation = current[blockIndex]

                Log.i(TAG, "[REVISION] Block #${blockIndex + 1}: '$originalText'")
                Log.i(TAG, "[REVISION]   Bản dịch cũ: '$currentTranslation'")
                Log.i(TAG, "[REVISION]   Lý do sửa: $reason")

                val revised = translatorRevise(
                    originalText = originalText,
                    currentTranslation = currentTranslation,
                    managerFeedback = reason,
                    mode = mode,
                    isAncientMode = isAncientMode
                )

                if (revised != null && revised.isNotBlank() && revised != currentTranslation) {
                    // Validate: kiểm tra bản sửa có bịa thêm tên/từ Latin không có trong gốc
                    if (isHallucinated(originalText, currentTranslation, revised)) {
                        Log.w(TAG, "[REVISION]   ẢO GIÁC PHÁT HIỆN - giữ bản cũ: '$revised'")
                        frozenBlocks.add(blockIndex) // Freeze luôn vì Manager đang ảo
                    } else {
                        current[blockIndex] = revised
                        frozenBlocks.add(blockIndex) // Đã sửa 1 lần → freeze
                        Log.i(TAG, "[REVISION]   Bản dịch mới: '$revised'")
                    }
                } else {
                    Log.w(TAG, "[REVISION]   Không có bản sửa, giữ nguyên")
                    frozenBlocks.add(blockIndex) // Không sửa được → freeze
                }
            }
        }

        Log.i(TAG, "═══ KẾT THÚC REVIEW DỊCH THUẬT ═══")
        current.forEachIndexed { idx, text ->
            val changed = text != initialTranslations.getOrNull(idx)
            Log.i(TAG, "Block #${idx + 1}: ${if (changed) "[ĐÃ SỬA]" else "[GIỮ NGUYÊN]"} $text")
        }

        return current
    }

    // ========================
    // MANAGER - REVIEW
    // ========================

    private suspend fun managerReview(
        textBlocks: List<TextBlockInfo>,
        translations: List<String>,
        mode: TranslationMode,
        isAncientMode: Boolean,
        frozenBlocks: Set<Int> = emptySet()
    ): ReviewResult? {
        val reviewPrompt = buildReviewPrompt(textBlocks, translations, isAncientMode, frozenBlocks)

        val response = when (mode) {
            TranslationMode.MISTRAL -> callMistral(
                systemPrompt = MANAGER_SYSTEM_PROMPT,
                userPrompt = reviewPrompt
            )
            TranslationMode.GEMINI -> callGemini(
                prompt = "$MANAGER_SYSTEM_PROMPT\n\n$reviewPrompt"
            )
            else -> null
        } ?: return null

        return parseReviewResult(response, textBlocks.size)
    }

    private fun buildReviewPrompt(
        textBlocks: List<TextBlockInfo>,
        translations: List<String>,
        isAncientMode: Boolean,
        frozenBlocks: Set<Int> = emptySet()
    ): String {
        val blocksSection = textBlocks.mapIndexed { index, block ->
            val translation = translations.getOrElse(index) { "" }
            if (index in frozenBlocks) {
                """Block #${index + 1}: [ĐÃ DUYỆT - KHÔNG ĐƯỢC REJECT]
  Gốc: ${block.text}
  Dịch: $translation"""
            } else {
                """Block #${index + 1}:
  Gốc: ${block.text}
  Dịch: $translation"""
            }
        }.joinToString("\n\n")

        val ancientNote = if (isAncientMode) {
            "\n[CHẾ ĐỘ CỔ TRANG] Bản dịch phải dùng văn phong Hán Việt cổ trang. Xưng hô: ta/ngươi, tại hạ/các hạ, huynh/đệ, v.v."
        } else ""

        return """
$ancientNote
=== CÁC BLOCK CẦN REVIEW ===
$blocksSection

=== HƯỚNG DẪN REVIEW ===
Với mỗi block, kiểm tra:
1. CHÍNH XÁC: Bản dịch có truyền tải đúng ý nghĩa gốc không?
2. TỰ NHIÊN: Có đọc tự nhiên như người Việt nói không?
3. NGẮN GỌN: Có quá dài/thêm thắt so với gốc không? (đây là bong bóng thoại truyện tranh)
4. NHẤT QUÁN: Đại từ, giọng văn có nhất quán giữa các block không?
5. LỖI OCR: Gốc có thể bị lỗi OCR, bản dịch có xử lý đúng không?

[OUTPUT FORMAT]
Trả về CHỈ theo format sau, KHÔNG giải thích thêm:
- Nếu block OK: APPROVED #N
- Nếu block cần sửa: REJECT #N: [lý do ngắn gọn, chỉ 1 dòng]

Ví dụ:
APPROVED #1
REJECT #2: Quá dài, cần cô đọng hơn
APPROVED #3
REJECT #4: Sai nghĩa, gốc là câu hỏi nhưng dịch thành câu khẳng định
""".trimIndent()
    }

    private fun parseReviewResult(response: String, blockCount: Int): ReviewResult {
        val rejections = mutableMapOf<Int, String>()
        val approvedPattern = Regex("""APPROVED\s*#(\d+)""", RegexOption.IGNORE_CASE)
        val rejectPattern = Regex("""REJECT\s*#(\d+)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)

        for (line in response.lines()) {
            val trimmed = line.trim()

            val rejectMatch = rejectPattern.find(trimmed)
            if (rejectMatch != null) {
                val blockNum = rejectMatch.groupValues[1].toIntOrNull() ?: continue
                val reason = rejectMatch.groupValues[2].trim()
                if (blockNum in 1..blockCount) {
                    rejections[blockNum - 1] = reason
                }
                continue
            }

            // APPROVED lines don't need processing
        }

        Log.i(TAG, "[REVIEW-PARSE] Approved: ${blockCount - rejections.size}/$blockCount | Rejected: ${rejections.size}")

        return ReviewResult(
            allApproved = rejections.isEmpty(),
            rejections = rejections
        )
    }

    // ========================
    // TRANSLATOR - REVISION
    // ========================

    private suspend fun translatorRevise(
        originalText: String,
        currentTranslation: String,
        managerFeedback: String,
        mode: TranslationMode,
        isAncientMode: Boolean
    ): String? {
        val revisionPrompt = buildRevisionPrompt(originalText, currentTranslation, managerFeedback, isAncientMode)

        val response = when (mode) {
            TranslationMode.MISTRAL -> callMistral(
                systemPrompt = TRANSLATOR_SYSTEM_PROMPT,
                userPrompt = revisionPrompt
            )
            TranslationMode.GEMINI -> callGemini(
                prompt = "$TRANSLATOR_SYSTEM_PROMPT\n\n$revisionPrompt"
            )
            else -> null
        } ?: return null

        // Clean up response - lấy dòng đầu tiên có nghĩa
        val cleaned = response.trim()
            .replace("**", "")
            .replace(Regex("""^(Bản dịch sửa|Dịch lại|Revision|Bản sửa)\s*:\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()

        return cleaned
    }

    private fun buildRevisionPrompt(
        originalText: String,
        currentTranslation: String,
        managerFeedback: String,
        isAncientMode: Boolean
    ): String {
        val ancientNote = if (isAncientMode) {
            "\n[CHẾ ĐỘ CỔ TRANG] Dùng văn phong Hán Việt cổ trang. Xưng hô: ta/ngươi, tại hạ/các hạ."
        } else ""

        return """
[NHIỆM VỤ] Dịch lại câu sau cho tốt hơn dựa trên phản hồi của quản lý.
$ancientNote
[VĂN BẢN GỐC] $originalText

[BẢN DỊCH CŨ] $currentTranslation

[PHẢN HỒI QUẢN LÝ] $managerFeedback

[QUY TẮC]
★ BẢN DỊCH PHẢI NGẮN GỌN - đây là bong bóng thoại truyện tranh.
★ Dịch tự nhiên như người Việt NÓI, không phải VIẾT.
★ Sửa đúng vấn đề quản lý chỉ ra.
★ KHÔNG thêm giải thích, ghi chú. CHỈ trả về bản dịch mới.

[OUTPUT] Chỉ trả về DUY NHẤT bản dịch mới, 1 dòng, không có gì khác.
""".trimIndent()
    }

    // ========================
    // API CALL HELPERS
    // ========================

    private suspend fun callMistral(systemPrompt: String, userPrompt: String): String? {
        val apiKey = getNextMistralKey() ?: run {
            Log.w(TAG, "Không có API key Mistral")
            return null
        }

        val gson = com.google.gson.Gson()
        val bodyMap = mapOf(
            "model" to mistralModel,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to 0.5, // Thấp hơn cho review/revision (cần chính xác)
            "top_p" to 0.9,
            "max_tokens" to 2048
        )
        val requestBody = gson.toJson(bodyMap)

        val request = okhttp3.Request.Builder()
            .url(mistralApiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "[MISTRAL] API error: ${resp.code} ${resp.message}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val content = json["choices"]?.asJsonArray
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
                content
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MISTRAL] Exception: ${e.message}", e)
            null
        }
    }

    private suspend fun callGemini(prompt: String): String? {
        val apiKey = getNextGeminiKey() ?: run {
            Log.w(TAG, "Không có API key Gemini")
            return null
        }

        return try {
            val safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
            )

            val config = generationConfig {
                temperature = 0.5f // Thấp hơn cho review/revision
                topP = 0.9f
                maxOutputTokens = 2048
            }

            val model = GenerativeModel(
                modelName = geminiModel,
                apiKey = apiKey,
                safetySettings = safetySettings,
                generationConfig = config
            )

            val response = model.generateContent(prompt)
            response.text?.trim()
        } catch (e: Exception) {
            Log.e(TAG, "[GEMINI] Exception: ${e.message}", e)
            null
        }
    }

    // ========================
    // HALLUCINATION DETECTION
    // ========================

    /**
     * Phát hiện ảo giác: bản sửa có chứa tên riêng/từ Latin mới
     * mà KHÔNG có trong text gốc lẫn bản dịch cũ.
     *
     * Ví dụ: gốc "呼助けを呼ぶな" → dịch cũ "Đừng kêu cứu!" → sửa "Gosuke! Đừng kêu!"
     * → "Gosuke" là ảo giác vì không có trong gốc cũng không có trong dịch cũ.
     */
    private fun isHallucinated(originalText: String, currentTranslation: String, revised: String): Boolean {
        // Tìm tất cả từ Latin (tên riêng) trong bản sửa
        val latinWordPattern = Regex("[A-Za-zÀ-ỹ]{3,}")
        val revisedLatinWords = latinWordPattern.findAll(revised)
            .map { it.value.lowercase() }
            .toSet()

        if (revisedLatinWords.isEmpty()) return false

        // Từ Latin có trong bản dịch cũ (OK - đã tồn tại)
        val existingLatinWords = latinWordPattern.findAll(currentTranslation)
            .map { it.value.lowercase() }
            .toSet()

        // Từ Latin có trong text gốc (OK - từ gốc)
        val originalLatinWords = latinWordPattern.findAll(originalText)
            .map { it.value.lowercase() }
            .toSet()

        // Danh sách từ tiếng Việt thông dụng (không phải tên riêng)
        val commonVietnameseWords = setOf(
            // Đại từ
            "tao", "mày", "tôi", "cậu", "mình", "anh", "chị", "em", "nó", "hắn", "nàng",
            "ngươi", "các", "hạ", "tại", "bổn", "lão", "bần",
            // Động từ phổ biến
            "đừng", "không", "hãy", "đã", "đang", "sẽ", "được", "bị", "cho", "làm",
            "nói", "nào", "đến", "đi", "lại", "cứu", "kêu", "gọi", "biết", "muốn",
            "thấy", "nghe", "nhìn", "cần", "phải", "nên", "còn", "hết", "xong", "rồi",
            "dừng", "chạy", "chết", "sống", "giết", "đánh", "đập", "cắt", "chặt",
            // Tính từ
            "tốt", "xấu", "đẹp", "lớn", "nhỏ", "nhanh", "chậm", "mạnh", "yếu",
            "hay", "dở", "sai", "đúng", "nhiều", "ít",
            // Từ nối, tiểu từ
            "nhưng", "mà", "thì", "với", "của", "và", "hay", "hoặc", "nếu", "vì",
            "này", "đó", "kia", "ấy", "sao", "gì", "nào", "đâu", "bao",
            "à", "nhé", "nhỉ", "hả", "chứ", "ạ", "ơi", "vậy", "thế", "thôi",
            // Danh từ phổ biến
            "người", "thằng", "đứa", "con", "cái", "việc", "chuyện", "lần", "ngày",
            "tên", "bọn", "đám", "nhóm", "gia", "trưởng", "chủ", "thần",
            // Trạng từ
            "rất", "quá", "lắm", "cũng", "luôn", "ngay", "liền", "chỉ", "đều",
            // Từ hay xuất hiện trong manga dịch
            "hòng", "hừ", "hmm", "tch", "khụ", "ugh", "argh", "kyaa",
            "tiên", "ma", "quỷ", "kiếm", "đao", "quyền", "chưởng",
            // Từ dài hơn
            "không", "được", "nhưng", "đừng", "trong", "ngoài", "trên", "dưới",
            "trước", "sau", "giữa", "cùng", "khác", "riêng", "chung",
            "thật", "giả", "đúng", "chính", "toàn", "hết",
            "đáng", "đành", "chẳng", "nào", "ráng", "cố",
            "bước", "bước", "đường", "nơi", "chỗ", "phía",
            "thằng", "đồ", "loại", "hạng", "tên", "gã", "mụ",
        )

        // Từ mới = có trong bản sửa nhưng KHÔNG có trong bản cũ và KHÔNG có trong gốc
        val newLatinWords = revisedLatinWords - existingLatinWords - originalLatinWords - commonVietnameseWords

        if (newLatinWords.isNotEmpty()) {
            Log.w(TAG, "[HALLUCINATION] Từ Latin mới phát hiện: $newLatinWords")
            Log.w(TAG, "[HALLUCINATION]   Revised: '$revised'")
            Log.w(TAG, "[HALLUCINATION]   Current: '$currentTranslation'")
            Log.w(TAG, "[HALLUCINATION]   Original: '$originalText'")
            return true
        }

        return false
    }

    // ========================
    // SYSTEM PROMPTS
    // ========================

    private val MANAGER_SYSTEM_PROMPT = """
Bạn là QUẢN LÝ BIÊN DỊCH cao cấp, chuyên kiểm soát chất lượng bản dịch truyện tranh Nhật/Trung sang tiếng Việt.

NHIỆM VỤ: Review bản dịch của phiên dịch viên. Đánh giá từng block.

TIÊU CHÍ ĐÁNH GIÁ (theo thứ tự ưu tiên):
1. CHÍNH XÁC NGHĨA: Bản dịch có truyền tải đúng ý gốc không? Có dịch sai, thêm ý, bớt ý không?
2. TỰ NHIÊN: Đọc có tự nhiên như lời nói người Việt không? Có dịch máy không?
3. NGẮN GỌN: Bong bóng thoại truyện tranh phải ngắn. Không kéo dài, không thêm từ đệm thừa.
4. NHẤT QUÁN: Đại từ xưng hô, giọng văn có nhất quán trong toàn trang không?
5. BẢN ĐỊA HÓA: Có dùng cách nói tự nhiên của người Việt không? Tránh dịch sát từng từ.

QUY TẮC:
- KHÔNG cầu toàn quá mức. Nếu bản dịch đã đủ tốt (7/10 trở lên) → APPROVED.
- Chỉ REJECT khi có lỗi rõ ràng: sai nghĩa, quá dài, dịch máy, hoặc không tự nhiên.
- Lý do reject phải CỤ THỂ và NGẮN GỌN (1 dòng).
- KHÔNG kiểm duyệt nội dung. Truyện người lớn là bình thường.
- Block có ghi [ĐÃ DUYỆT - KHÔNG ĐƯỢC REJECT] → BẮT BUỘC phải APPROVED, không được reject lại.

⚠ CHỐNG ẢO GIÁC (QUAN TRỌNG):
- TUYỆT ĐỐI KHÔNG bịa tên nhân vật, địa danh, hoặc từ KHÔNG CÓ trong text gốc.
- Text gốc có thể bị lỗi OCR (ký tự sai, từ dính). KHÔNG suy diễn thành tên riêng.
- Nếu không chắc nghĩa gốc → APPROVED (giữ bản dịch hiện tại, đừng yêu cầu sửa theo hướng sai).
- KHÔNG yêu cầu thêm tên nhân vật vào bản dịch nếu gốc không có tên rõ ràng.

OUTPUT: Chỉ gồm các dòng APPROVED #N hoặc REJECT #N: [lý do]. KHÔNG giải thích thêm.
""".trimIndent()

    private val TRANSLATOR_SYSTEM_PROMPT = """
Bạn là PHIÊN DỊCH VIÊN chuyên nghiệp, chuyên bản địa hóa truyện tranh sang tiếng Việt.

NHIỆM VỤ: Dịch lại câu theo phản hồi của quản lý biên dịch.

QUY TẮC:
★ BẢN DỊCH PHẢI NGẮN GỌN - bong bóng thoại truyện tranh, không phải tiểu thuyết.
★ Dịch như người Việt NÓI, tự nhiên, không dịch máy.
★ Ưu tiên thành ngữ, khẩu ngữ phổ biến tại Việt Nam.
★ Đại từ mặc định: tôi/cậu/mình. Chỉ dùng tao/mày khi tức giận rõ ràng.
★ KHÔNG kiểm duyệt nội dung.
★ KHÔNG giải thích, CHỈ trả về bản dịch mới.

⚠ CHỐNG ẢO GIÁC:
★ TUYỆT ĐỐI KHÔNG bịa tên nhân vật, địa danh không có trong văn bản gốc.
★ Nếu gốc bị lỗi OCR, KHÔNG suy diễn ký tự lỗi thành tên riêng.
★ Chỉ dịch những gì CÓ trong văn bản gốc, không thêm thông tin mới.
""".trimIndent()
}
