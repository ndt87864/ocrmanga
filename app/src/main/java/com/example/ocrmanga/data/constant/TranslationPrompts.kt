package com.example.ocrmanga.data.constant

import android.content.Context
import com.example.ocrmanga.utils.PromptUtils

object TranslationPrompts {

    private var managerSystemPrompt: String = ""
    private var translatorSystemPrompt: String = ""
    private var mistralBasicPrompt: String = ""
    private var mistralMultiScalePrompt: String = ""
    private var mistralMultiScalePromptOptimized: String = ""
    private var geminiMultiScalePrompt: String = ""
    private var zaiBasicPrompt: String = ""
    private var zaiMultiScalePrompt: String = ""
    private var managerReviewPrompt: String = ""
    private var translatorRevisePrompt: String = ""

    /**
     * Khởi tạo prompt từ assets
     */
    fun initialize(context: Context) {
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] Bắt đầu tải prompts từ assets...")

        managerSystemPrompt = PromptUtils.loadPromptFromAssets(context, "manager_system.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] manager_system.md: ${managerSystemPrompt.length} chars")

        translatorSystemPrompt = PromptUtils.loadPromptFromAssets(context, "translator_system.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] translator_system.md: ${translatorSystemPrompt.length} chars")

        mistralBasicPrompt = PromptUtils.loadPromptFromAssets(context, "mistral_basic.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] mistral_basic.md: ${mistralBasicPrompt.length} chars")

        mistralMultiScalePrompt = PromptUtils.loadPromptFromAssets(context, "mistral_multi_scale.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] mistral_multi_scale.md: ${mistralMultiScalePrompt.length} chars")

        mistralMultiScalePromptOptimized = PromptUtils.loadPromptFromAssets(context, "mistral_multi_scale_optimized.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] mistral_multi_scale_optimized.md: ${mistralMultiScalePromptOptimized.length} chars")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] mistral_multi_scale_optimized.md preview:\n${mistralMultiScalePromptOptimized.take(500)}")

        geminiMultiScalePrompt = PromptUtils.loadPromptFromAssets(context, "gemini_multi_scale.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] gemini_multi_scale.md: ${geminiMultiScalePrompt.length} chars")

        zaiBasicPrompt = PromptUtils.loadPromptFromAssets(context, "zai_basic.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] zai_basic.md: ${zaiBasicPrompt.length} chars")

        zaiMultiScalePrompt = PromptUtils.loadPromptFromAssets(context, "zai_multi_scale.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] zai_multi_scale.md: ${zaiMultiScalePrompt.length} chars")

        managerReviewPrompt = PromptUtils.loadPromptFromAssets(context, "manager_review.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] manager_review.md: ${managerReviewPrompt.length} chars")

        translatorRevisePrompt = PromptUtils.loadPromptFromAssets(context, "translator_revise.md")
        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] translator_revise.md: ${translatorRevisePrompt.length} chars")

        com.example.ocrmanga.utils.AppLogger.d("TranslationPrompts", "[INIT] Hoàn tất tải tất cả prompts!")
    }

    val MANAGER_SYSTEM_PROMPT: String
        get() = managerSystemPrompt

    val TRANSLATOR_SYSTEM_PROMPT: String
        get() = translatorSystemPrompt

    /**
     * Prompt cơ bản cho Mistral - dịch đơn giản một đoạn văn bản
     */
    fun getMistralBasicPrompt(text: String, isAncientMode: Boolean = false): String {
        val ancientInstruction = if (isAncientMode) {
            """
            [CHẾ ĐỘ CỔ TRANG]
            - Văn phong: Hán Việt, cổ trang, kiếm hiệp.
            - Xưng hô: ta/ngươi, tại hạ/các hạ, huynh/đệ, cô nương/tiểu tử, bổn toạ, lão phu, bần đạo...
            - Cấm dùng từ hiện đại: anh, em, cậu, tớ, mình, bạn.
            """
        } else ""

        return mistralBasicPrompt
            .replace("{{text}}", text)
            .replace("{{ancientInstruction}}", ancientInstruction)
    }

    /**
     * Prompt cho Mistral Multi-Scale - dịch nhiều blocks với ngữ cảnh ảnh trước
     */
    fun getMistralMultiScalePrompt(
        ocrResultsText: String,
        numberedBlocks: String,
        blockCount: Int,
        previousContextText: String = "",
        isAncientMode: Boolean = false
    ): String {
        val ancientInstruction = if (isAncientMode) {
            """
            [CHẾ ĐỘ CỔ TRANG]
            - Văn phong: Hán Việt, cổ trang, kiếm hiệp. Câu văn trang trọng, cổ kính.
            - Xưng hô: ta/ngươi, tại hạ/các hạ, huynh/đệ, muội/tỷ, cô nương/tiểu tử, bổn toạ, lão phu, bần đạo, phu quân/nương tử, chủ nhân/nô tỳ...
            - Cấm dùng từ hiện đại: anh, em, cậu, tớ, mình, bạn.
            """
        } else ""

        return mistralMultiScalePrompt
            .replace("{{previousContextText}}", previousContextText)
            .replace("{{ocrResultsText}}", ocrResultsText)
            .replace("{{numberedBlocks}}", numberedBlocks)
            .replace("{{blockCount}}", blockCount.toString())
            .replace("{{ancientInstruction}}", ancientInstruction)
    }

    /**
     * Prompt tối ưu cho Mistral API (giảm ~350 tokens so với version gốc)
     * Giữ nguyên tất cả translation rules, chỉ tối ưu cấu trúc/format
     */
    fun getMistralMultiScalePromptOptimized(
        ocrResultsText: String,
        numberedBlocks: String,
        blockCount: Int,
        previousContextText: String = "",
        isAncientMode: Boolean = false
    ): String {
        val ancientInstruction = if (isAncientMode) {
            "[CHẾ ĐỘ CỔ TRANG] Văn phong Hán Việt, cổ trang, kiếm hiệp. Xưng hô: ta/ngươi, tại hạ/các hạ, huynh/đệ, cô nương/tiểu tử, bổn toạ, lão phu, bần đạo. Cấm từ hiện đại: anh/em/cậu/tớ/mình/bạn."
        } else ""

        return mistralMultiScalePromptOptimized
            .replace("{{previousContextText}}", previousContextText)
            .replace("{{ocrResultsText}}", ocrResultsText)
            .replace("{{numberedBlocks}}", numberedBlocks)
            .replace("{{blockCount}}", blockCount.toString())
            .replace("{{ancientInstruction}}", ancientInstruction)
    }

    /**
     * Tạo phần context từ bản dịch ảnh trước
     */
    fun getPreviousContextText(previousTranslation: List<com.example.ocrmanga.data.models.TextBlockInfo>): String {
        if (previousTranslation.isEmpty()) return ""

        val prevBlocks = previousTranslation.mapIndexed { index, block ->
            "${index + 1}. ${block.text}"
        }.joinToString("\n")

        // Phân tích NGÔI xưng hô từ ảnh trước (lịch sự vs suồng sã)
        val allText = previousTranslation.joinToString(" ") { it.text.uppercase() }

        // Phân tích chi tiết hơn về các đại từ - TÌM CẶP XÂY DỰNG QUAN HỆ
        val hasToiPattern = allText.contains(" TÔI ") || allText.contains("TÔI ") || allText.contains(" TÔI")
        val hasMinhPattern = allText.contains(" MÌNH ") || allText.contains("MÌNH ") || allText.contains(" MÌNH")
        val hasTaoPattern = allText.contains(" TAO ") || allText.contains("TAO ") || allText.contains(" TAO")
        val hasCauPattern = allText.contains(" CẬU ") || allText.contains("CẬU ") || allText.contains(" CẬU")
        val hasMayPattern = allText.contains(" MÀY ") || allText.contains("MÀY ") || allText.contains(" MÀY")
        val hasAnhPattern = allText.contains(" ANH ") || allText.contains("ANH ")
        val hasEmPattern = allText.contains(" EM ") || allText.contains("EM ")

        // Xác định CẶP ngôi xưng hô CHÍNH (ưu tiên cặp hoàn chỉnh)
        val mainPronounPair = when {
            hasTaoPattern || hasMayPattern -> "TAO - MÀY"
            hasToiPattern && hasCauPattern -> "TÔI - CẬU"
            hasMinhPattern && hasCauPattern -> "MÌNH - CẬU"
            hasEmPattern && hasAnhPattern -> "EM - ANH"
            hasToiPattern && hasAnhPattern -> "TÔI - ANH"
            hasTaoPattern -> "TAO - MÀY"
            hasToiPattern -> "TÔI - CẬU"
            else -> null
        }

        val pronounInstruction = if (mainPronounPair != null) {
            """

            ⚠ QUY TẮC NHẤT QUÁN (DỰA TRÊN NGỮ CẢNH): $mainPronounPair
            - Ưu tiên sử dụng cặp "$mainPronounPair" nếu mối quan hệ nhân vật không thay đổi.
            - Đảm bảo tính ĐỐI XỨNG và LOGIC xuyên suốt toàn bộ hội thoại.

            """
        } else {
            """

            ⚠ LƯU Ý VỀ XƯNG HÔ:
            - Phân tích kỹ thái độ và vị thế của các nhân vật để chọn cặp xưng hô phù hợp (Tôi-Cậu, Tao-Mày, Anh-Em...).
            - Giữ sự nhất quán tuyệt đối trong cùng một phân cảnh.

            """
        }

        return """

        === NGỮ CẢNH TỪ ẢNH TRƯỚC ===
        Cặp xưng hô: ${mainPronounPair ?: "chưa xác định"}.
        (Lưu ý: Nếu là TAO-MÀY, chỉ giữ tiếp nếu vẫn đang cãi vã gắt gỏng).
        $pronounInstruction
        Nội dung ảnh trước (để nắm mạch truyện):
        $prevBlocks

        """.trimIndent()
    }

    /**
     * Prompt cho Gemini Multi-Scale - tương tự Mistral nhưng cho Gemini
     */
    fun getGeminiMultiScalePrompt(
        ocrResultsText: String,
        numberedBlocks: String,
        blockCount: Int,
        previousContextText: String = "",
        isAncientMode: Boolean = false
    ): String {
        // Lấy prompt cơ bản từ Mistral
        val basePrompt = getMistralMultiScalePrompt(ocrResultsText, numberedBlocks, blockCount, previousContextText, isAncientMode)

        // Thêm hướng dẫn định dạng nghiêm ngặt cho Gemini (vì Gemini không dùng system prompt như Mistral)
        return geminiMultiScalePrompt
            .replace("{{basePrompt}}", basePrompt)
            .replace("{{blockCount}}", blockCount.toString())
    }

    /**
     * Prompt cơ bản cho Z.AI - dịch đơn giản một đoạn văn bản
     */
    fun getZAiBasicPrompt(text: String, isAncientMode: Boolean = false): String {
        val ancientInstruction = if (isAncientMode) {
            "Văn phong: Hán Việt, cổ trang. Xưng hô: ta/ngươi, tại hạ/các hạ, huynh/đệ..."
        } else ""

        return zaiBasicPrompt
            .replace("{{text}}", text)
            .replace("{{ancientInstruction}}", ancientInstruction)
    }

    /**
     * Prompt chuyên dụng cho Z.AI Multi-Scale
     */
    fun getZAiMultiScalePrompt(
        ocrResultsText: String,
        numberedBlocks: String,
        blockCount: Int,
        previousContextText: String = "",
        isAncientMode: Boolean = false
    ): String {
        val ancientInstruction = if (isAncientMode) {
            "[CHẾ ĐỘ CỔ TRANG] Văn phong Hán Việt, cổ trang. Xưng hô: ta/ngươi, tại hạ/các hạ, huynh/đệ. Cấm dùng từ hiện đại: anh/em/cậu/tớ."
        } else ""

        return zaiMultiScalePrompt
            .replace("{{previousContextText}}", previousContextText)
            .replace("{{ocrResultsText}}", ocrResultsText)
            .replace("{{numberedBlocks}}", numberedBlocks)
            .replace("{{blockCount}}", blockCount.toString())
            .replace("{{ancientInstruction}}", ancientInstruction)
    }

    /**
     * Prompt cho Manager review toàn bộ bản dịch của một trang
     */
    fun getReviewPrompt(
        textBlocks: List<com.example.ocrmanga.data.models.TextBlockInfo>,
        translations: List<String>,
        isAncientMode: Boolean = false
    ): String {
        val numberedTranslations = translations.mapIndexed { index, s ->
            "Block #${index + 1}: [GỐC: ${textBlocks[index].text}] -> [DỊCH: $s]"
        }.joinToString("\n")

        val ancientInstruction = if (isAncientMode) {
            "LƯU Ý: Phải tuân thủ văn phong CỔ TRANG (ta/ngươi, tại hạ, huynh/đệ...)."
        } else ""

        return managerReviewPrompt
            .replace("{{numberedTranslations}}", numberedTranslations)
            .replace("{{ancientInstruction}}", ancientInstruction)
    }

    /**
     * Prompt cho Translator dịch lại một block dựa trên feedback của Manager
     */
    fun getRevisePrompt(
        originalText: String,
        currentTranslation: String,
        feedback: String,
        isAncientMode: Boolean = false
    ): String {
        val ancientInstruction = if (isAncientMode) {
            "[CHẾ ĐỘ CỔ TRANG]: Dùng từ Hán Việt, xưng hô cổ (ta/ngươi, tại hạ...)."
        } else ""

        return translatorRevisePrompt
            .replace("{{originalText}}", originalText)
            .replace("{{currentTranslation}}", currentTranslation)
            .replace("{{feedback}}", feedback)
            .replace("{{ancientInstruction}}", ancientInstruction)
    }
}
