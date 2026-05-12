package com.example.ocrmanga.data.constant

import android.content.Context
import com.example.ocrmanga.utils.PromptUtils

object TranslationPrompts {

    private var managerSystemPrompt: String = ""
    private var translatorSystemPrompt: String = ""
    private var mistralBasicPrompt: String = ""
    private var multiScalePrompt: String = ""
    private var zaiBasicPrompt: String = ""
    private var managerReviewPrompt: String = ""
    private var translatorRevisePrompt: String = ""

    /**
     * Khởi tạo prompt từ assets
     */
    fun initialize(context: Context) {
        mistralBasicPrompt = PromptUtils.loadPromptFromAssets(context, "mistral_basic.md")
        multiScalePrompt = PromptUtils.loadPromptFromAssets(context, "translation_prompt.md")
        zaiBasicPrompt = PromptUtils.loadPromptFromAssets(context, "zai_basic.md")
    }

    val MANAGER_SYSTEM_PROMPT: String
        get() = ""

    val TRANSLATOR_SYSTEM_PROMPT: String
        get() = ""

    /**
     * Prompt cơ bản cho Mistral - dịch đơn giản một đoạn văn bản
     */
    fun getMistralBasicPrompt(text: String, isAncientMode: Boolean = false): String {
        return mistralBasicPrompt
            .replace("{{text}}", text)
            .replace("{{ancientInstruction}}", if (isAncientMode) "[CHẾ ĐỘ CỔ TRANG] Dùng văn phong Hán Việt, xưng hô ta/ngươi." else "")
    }

    /**
     * Prompt cơ bản cho Z.AI - dịch đơn giản một đoạn văn bản
     */
    fun getZAiBasicPrompt(text: String, isAncientMode: Boolean = false): String {
        return zaiBasicPrompt
            .replace("{{text}}", text)
            .replace("{{ancientInstruction}}", if (isAncientMode) "[CHẾ ĐỘ CỔ TRANG] Dùng văn phong Hán Việt, xưng hô ta/ngươi." else "")
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
        return getMistralMultiScalePromptOptimized(ocrResultsText, numberedBlocks, blockCount, previousContextText, isAncientMode)
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
            """
            [CHẾ ĐỘ CỔ TRANG - ƯU TIÊN CAO NHẤT]
            - Bối cảnh: Cổ đại, tiên hiệp, kiếm hiệp, lịch sử.
            - Văn phong: Sử dụng từ Hán Việt trang trọng, nhã nhặn hoặc uy dũng tùy nhân vật. Tuyệt đối tránh từ ngữ hiện đại, từ lóng gen Z.
            - Xưng hô (Dialogue Pronouns):
                + Ngôi thứ nhất: Ta, tại hạ, bần đạo, lão phu, bổn tọa, bổn cung, trẫm, thần, muội, tỷ, huynh.
                + Ngôi thứ hai: Ngươi, các hạ, vị này, huynh đệ, nương tử, phu quân, cô nương, công tử, đại hiệp, tiểu hữu, chư vị.
                + Ngôi thứ ba: Hắn, thị, y, bọn chúng, chúng nhân.
            - CẤM DÙNG: anh, em, cậu, tớ, mình, bạn, mày, tao (trừ khi có quan hệ gia đình cực kỳ gần gũi như huynh-muội).
            - Độc thoại (Inner Monologue): Sử dụng "ta" hoặc "mình" (nếu nhẹ nhàng) cho bản thân, dùng "hắn/thị/y/vị này" cho người khác.
            - SFX: Chuyển sang âm Hán Việt (ví dụ: "Bùm" -> "Oanh", "Xoẹt" -> "Xoát", "Vèo" -> "Tốc", "Choảng" -> "Keng").
            """.trimIndent()
        } else ""

        return multiScalePrompt
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
        return getMistralMultiScalePromptOptimized(
            ocrResultsText, numberedBlocks, blockCount, previousContextText, isAncientMode
        )
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
        return getMistralMultiScalePromptOptimized(
            ocrResultsText, numberedBlocks, blockCount, previousContextText, isAncientMode
        )
    }
}
