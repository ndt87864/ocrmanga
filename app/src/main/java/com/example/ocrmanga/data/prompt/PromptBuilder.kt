package com.example.ocrmanga.data.prompt

import com.example.ocrmanga.data.models.TextBlockInfo

/**
 * Helper để build prompts với context động
 */
object PromptBuilder {

    /**
     * Build user prompt cho translation
     */
    fun buildTranslationUserPrompt(
        template: PromptTemplate,
        ocrResults: List<Pair<Float, String>>,
        textBlocks: List<TextBlockInfo>,
        previousTranslation: List<TextBlockInfo>? = null,
        isAncientMode: Boolean = false
    ): String {
        val ocrResultsText = ocrResults.mapIndexed { index, (scale, text) ->
            "Kết quả quét ${index + 1} (scale ${String.format("%.2f", scale)}): $text"
        }.joinToString("\n\n")

        val numberedBlocks = textBlocks.mapIndexed { index, block ->
            "${index + 1}. ${block.text}"
        }.joinToString("\n")

        val previousContext = if (!previousTranslation.isNullOrEmpty()) {
            buildPreviousContext(previousTranslation)
        } else {
            "Không có ngữ cảnh từ ảnh trước."
        }

        val ancientModeInstruction = if (isAncientMode) {
            "⚠️ CHẾ ĐỘ CỔ TRANG: Bắt buộc dùng văn phong Hán Việt, xưng hô cổ (ta/ngươi, tại hạ/các hạ...)."
        } else ""

        val variables = mapOf(
            "previous_context" to previousContext,
            "ocr_results" to ocrResultsText,
            "numbered_blocks" to numberedBlocks,
            "block_count" to textBlocks.size.toString(),
            "ancient_mode_instruction" to ancientModeInstruction
        )

        return replaceVariables(template.content, variables)
    }

    /**
     * Build user prompt cho review
     */
    fun buildReviewUserPrompt(
        template: PromptTemplate,
        textBlocks: List<TextBlockInfo>,
        translations: List<String>,
        isAncientMode: Boolean = false
    ): String {
        val numberedTranslations = translations.mapIndexed { index, translation ->
            "Block #${index + 1}:\n  Gốc: ${textBlocks[index].text}\n  Dịch: $translation"
        }.joinToString("\n\n")

        val ancientModeNote = if (isAncientMode) {
            "⚠️ LƯU Ý: Phải tuân thủ văn phong CỔ TRANG (ta/ngươi, tại hạ, huynh/đệ...)."
        } else ""

        val variables = mapOf(
            "numbered_translations" to numberedTranslations,
            "ancient_mode_note" to ancientModeNote,
            "block_count" to textBlocks.size.toString()
        )

        return replaceVariables(template.content, variables)
    }

    /**
     * Build previous context text
     */
    private fun buildPreviousContext(previousTranslation: List<TextBlockInfo>): String {
        val prevBlocks = previousTranslation.mapIndexed { index, block ->
            "${index + 1}. ${block.text}"
        }.joinToString("\n")

        // Phân tích xưng hô
        val allText = previousTranslation.joinToString(" ") { it.text.uppercase() }
        val pronounPair = detectPronounPair(allText)

        return """
Cặp xưng hô đã xác lập: ${pronounPair ?: "chưa xác định"}
${if (pronounPair == "TAO-MÀY") "(Lưu ý: Chỉ giữ tiếp nếu vẫn đang cãi vã gắt gỏng)" else ""}

Nội dung ảnh trước:
$prevBlocks
        """.trimIndent()
    }

    /**
     * Detect pronoun pair từ text
     */
    private fun detectPronounPair(text: String): String? {
        val hasToiPattern = text.contains(" TÔI ")
        val hasMinhPattern = text.contains(" MÌNH ")
        val hasTaoPattern = text.contains(" TAO ")
        val hasCauPattern = text.contains(" CẬU ")
        val hasMayPattern = text.contains(" MÀY ")
        val hasAnhPattern = text.contains(" ANH ")
        val hasEmPattern = text.contains(" EM ")

        return when {
            hasToiPattern && hasCauPattern -> "TÔI-CẬU"
            hasMinhPattern && hasCauPattern -> "MÌNH-CẬU"
            hasTaoPattern && hasMayPattern -> "TAO-MÀY"
            hasToiPattern && hasAnhPattern -> "TÔI-ANH"
            hasEmPattern && hasAnhPattern -> "EM-ANH"
            hasToiPattern -> "TÔI"
            hasMinhPattern -> "MÌNH"
            hasTaoPattern -> "TAO"
            else -> null
        }
    }

    /**
     * Replace variables trong template
     */
    private fun replaceVariables(content: String, variables: Map<String, String>): String {
        var result = content
        for ((key, value) in variables) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
