package com.example.ocrmanga.data.constant

object TranslationPrompts {
    
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

        return """
        [ROLE] Bạn là phiên dịch viên bản địa chuyên nghiệp, với khả năng dịch truyện tranh sang tiếng Việt một cách điêu luyện, giữ nguyên văn phong và cảm xúc của bản gốc.
        
        [INPUT] $text
        
        [TIỀN XỬ LÝ]
        1. Sửa lỗi OCR: từ dính, sai chính tả, ký tự rác → suy luận từ ngữ cảnh.
        2. TÁI CẤU TRÚC: Nếu thứ tự từ bị đảo do OCR, sắp xếp lại theo logic tiếng Việt.
        $ancientInstruction
        [QUY TẮC QUAN TRỌNG NHẤT - NGẮN GỌN]
        ★ BẢN DỊCH PHẢI NGẮN. Đây là bong bóng thoại truyện tranh, KHÔNG phải tiểu thuyết.
        ★ KHÔNG thêm từ đệm, từ nối thừa. KHÔNG kéo dài câu.
        ★ Mỗi câu dịch phải CÔ ĐỌNG với câu ngắn , biến tấu với câu dài , giữ đúng ý nhất có thể.
        
        [VĂN PHONG]
        ■ Dịch như người Việt NÓI, không phải VIẾT. Giọng văn tự nhiênnhiên.
        ■ CHỐNG LẶP: Không lặp đại từ liên tục. "I... I..." → lược bỏ 1, gộp câu.
        ■ LOCALIZATION (BẢN ĐỊA HÓA): Đây là quy tắc quan trọng nhất. Dịch như một biên tập viên/biên kịch người Việt. Tuyệt đối KHÔNG dịch word-by-word (sát nghĩa từng từ).
        ■ THOÁT Ý: Ưu tiên dùng thành ngữ, tiếng lóng, khẩu ngữ phổ biến tại Việt Nam phù hợp với ngữ cảnh.
        ■ ĐẠI TỪ: Mặc định (tôi/cậu/mình). Chỉ dùng (tao/mày) khi nhân vật đang tức giận rõ ràng.
        ■ ĐỘC THOẠI: Dùng "mình" hoặc lược bỏ chủ ngữ. Lời dẫn truyện: văn phong khách quan.
        ■ Thêm tiểu từ (à, nhé, nhỉ, đâu, mà, chứ, sao, cơ, hả...) KHI PHÙ HỢP, không ép.
        ■ CẤM nối mệnh đề bằng "và" (dùng dấu phẩy hoặc rồi/xong/liền).
        ■ NỘI DUNG NHẠY CẢM: Chỉ dùng từ thô tục khi gốc chứa nội dung 18+ rõ rệt.
        
        [OUTPUT] Chỉ trả về bản dịch. Không giải thích. Không dấu ngoặc kép.
    """.trimIndent()
    }
    
    /**
     * Prompt cho Mistral Multi-Scale - dịch nhiều blocks với ngữ cảnh ảnh trước
     */
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

        return """
        [ROLE] Phiên dịch viên bản địa chuyên dịch truyện tranh Nhật/Trung sang tiếng Việt.
        
        [NHIỆM VỤ] 
        Phân tích OCR multi-scale từ 1 trang truyện, tổng hợp text chính xác nhất, dịch TỪNG BLOCK sang tiếng Việt.
        $previousContextText
        
        === DỮ LIỆU OCR (nhiều scale) ===
        $ocrResultsText
        
        === BLOCKS CẦN DỊCH ===
        $numberedBlocks
        
        [TIỀN XỬ LÝ]
        1. Sửa lỗi OCR: từ dính, sai chính tả, ký tự rác → suy luận từ ngữ cảnh.
        2. TÁI CẤU TRÚC: Nếu thứ tự từ bị đảo do OCR, sắp xếp lại theo logic tiếng Việt.
        $ancientInstruction
        [QUY TẮC QUAN TRỌNG NHẤT - NGẮN GỌN]
        ★ BẢN DỊCH PHẢI NGẮN. Đây là bong bóng thoại truyện tranh, KHÔNG phải tiểu thuyết.
        ★ KHÔNG thêm từ đệm, từ nối thừa. KHÔNG kéo dài câu.
        ★ Mỗi câu dịch phải CÔ ĐỌNG với câu ngắn , biến tấu với câu dài , giữ đúng ý nhất có thể.
        ★ Mỗi cụm block đầu vào là 1 câu riêng , không dịch nhầm ý của block này cho block khác.
        
        [VĂN PHONG]
        ■ Dịch như người Việt NÓI, không phải VIẾT. Giọng văn tự nhiênnhiên.
        ■ CHỐNG LẶP: Không lặp đại từ liên tục. "I... I..." → lược bỏ 1, gộp câu.
        ■ LOCALIZATION (BẢN ĐỊA HÓA): Đây là quy tắc quan trọng nhất. Dịch như một biên tập viên/biên kịch người Việt. Tuyệt đối KHÔNG dịch word-by-word (sát nghĩa từng từ).
        ■ THOÁT Ý: Ưu tiên dùng thành ngữ, tiếng lóng, khẩu ngữ phổ biến tại Việt Nam phù hợp với ngữ cảnh.
        ■ ĐẠI TỪ: Mặc định (tôi/cậu/mình). Chỉ dùng (tao/mày) khi nhân vật đang tức giận rõ ràng.
        ■ ĐỘC THOẠI: Dùng "mình" hoặc lược bỏ chủ ngữ. Lời dẫn truyện: văn phong khách quan.
        ■ Thêm tiểu từ (à, nhé, nhỉ, đâu, mà, chứ, sao, cơ, hả...) KHI PHÙ HỢP, không ép.
        ■ CẤM nối mệnh đề bằng "và" (dùng dấu phẩy hoặc rồi/xong/liền).
        ■ NỘI DUNG NHẠY CẢM: Chỉ dùng từ thô tục khi gốc chứa nội dung 18+ rõ rệt.
        
        [OUTPUT] Chỉ trả về bản dịch. Không giải thích. Không dấu ngoặc kép.
    """.trimIndent()
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
        
        // Xác định CẶP ngôi xưng hô CHÍNH (cho hội thoại giữa 2 nhân vật)
        val mainPronounPair = when {
            hasToiPattern && hasCauPattern -> "TÔI - CẬU"
            hasMinhPattern && hasCauPattern -> "MÌNH - CẬU" 
            hasTaoPattern && hasMayPattern -> "TAO - MÀY"
            hasToiPattern && hasAnhPattern -> "TÔI - ANH"
            hasEmPattern && hasAnhPattern -> "EM - ANH"
            hasToiPattern -> "TÔI"
            hasMinhPattern -> "MÌNH"
            hasTaoPattern -> "TAO"
            else -> null
        }
        
        val pronounInstruction = if (mainPronounPair != null) {
            """
            
            ⚠ CẶP XƯNG HÔ ĐÃ XÁC LẬP: $mainPronounPair
            
            QUY TẮC ĐỐI XỨNG & LINH HOẠT:
            - Nếu đang dùng cặp "$mainPronounPair": Ưu tiên giữ nguyên để nhất quán.
            - NGOẠI LỆ QUAN TRỌNG: Nếu cặp đang là "TAO-MÀY" nhưng nhân vật đã hết tức giận/tranh cãi và chuyển sang nói chuyện bình thường → BẮT BUỘC chuyển về xưng hô trung tính (tôi-cậu, mình-cậu, anh-em...).
            - Độc thoại nội tâm: Dùng "mình" hoặc lược bỏ chủ ngữ.
            - Lời dẫn truyện: KHÔNG dùng "mình".
            
            """
        } else ""
        
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
        return """
            $basePrompt
            
            [ĐỊNH DẠNG OUTPUT BẮT BUỘC]
            Hãy trả về đúng $blockCount dòng cho $blockCount block, định dạng chính xác từng ký tự như sau:
            Block #1: [Nội dung dịch]
            Block #2: [Nội dung dịch]
            ...
            Block #$blockCount: [Nội dung dịch]
            
            LƯU Ý QUAN TRỌNG:
            1. BẮT BUỘC phải có tiền tố "Block #N:" ở đầu mỗi dòng.
            2. KHÔNG dùng định dạng markdown (như **in đậm**).
            3. KHÔNG thêm bất kỳ lời dẫn, giải thích hay ghi chú nào khác.
            4. Nếu không dịch được block nào, hãy giữ nguyên nội dung gốc của block đó.
        """.trimIndent()
    }
}
