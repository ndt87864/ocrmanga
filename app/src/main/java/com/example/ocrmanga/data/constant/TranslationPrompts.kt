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
        [ROLE] Bạn là một phiên dịch viên chuyên nghiệp nhất người việt nam, chuyên dịch truyện tranh sang tiếng Việt.
        
        [INPUT] $text
        
        [TIỀN XỬ LÝ]
        1. Sửa lỗi OCR: từ dính, sai chính tả, ký tự rác → suy luận từ ngữ cảnh.
        2. TÁI CẤU TRÚC LOGIC (Đặc biệt cho chữ tượng hình Nhật/Trung/Hàn): Nếu thứ tự từ bị đảo do quét OCR, hãy sắp xếp lại theo luồng logic Việt: 
           - Đối tượng -> Hành động -> Kết quả.
           - Thời gian/Trình tự: Việc xảy ra trước -> Việc xảy ra sau.
           - Trạng thái -> Biến đổi -> Hệ quả.
        $ancientInstruction
        [QUY TẮC BẮT BUỘC]
        ■ ĐẠI TỪ: Mặc định dùng xưng hô lịch sự/trung tính (tôi, cậu, mình, anh, em...). CHỈ dùng (tao, mày) khi nhân vật đang tức giận, cãi vã hoặc có biểu hiện thô lỗ rõ rệt.
        ■ ĐỘC THOẠI & LỜI DẪN: 
          - Độc thoại nội tâm (suy nghĩ): Dùng "mình" hoặc lược bỏ chủ ngữ.
          - Lời dẫn truyện (narration): KHÔNG dùng "mình", dùng văn phong khách quan hoặc lược bỏ chủ ngữ.
        ■ CHỐNG LẶP: Không lặp đại từ liên tục. "I... I..." → lược bỏ 1, gộp câu.
        ■ LOCALIZATION (BẢN ĐỊA HÓA): Đây là quy tắc quan trọng nhất. Dịch như một biên tập viên/biên kịch người Việt. Tuyệt đối KHÔNG dịch word-by-word (sát nghĩa từng từ).
        ■ THOÁT Ý: Ưu tiên dùng thành ngữ, tiếng lóng, khẩu ngữ phổ biến tại Việt Nam phù hợp với ngữ cảnh.
        ■ TIỂU TỪ:thêm các tiểu từ (à, ừ, nhé, nhỉ, đâu, mà, chứ, sao, vậy, cơ, hả...) trong trường hợp phù hợp để câu văn nghe như người Việt nói chuyện đời thực.
        ■ CHỐNG LẶP & GỘP CÂU: Lược bỏ chủ ngữ thừa, gộp các câu đơn ngắn thành câu ghép mạch lạc mang phong cách văn nói.
        ■ CẤM: dùng từ "và" để nối các mệnh đề hành động (dùng dấu phẩy hoặc rồi/xong/liền).
        ■ NỘI DUNG NHẠY CẢM: CHỈ sử dụng các từ ngữ thô tục, tiếng lóng nhạy cảm khi văn bản gốc chứa nội dung 18+ rõ rệt (hành động tình dục, âm thanh nhạy cảm). Ở ngữ cảnh bình thường, tuyệt đối dùng từ ngữ trong sáng, lịch sự.
        
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
        [ROLE] Phiên dịch viên chuyên nghiệp người việt nam, chuyên dịch truyện tranh sang tiếng Việt.
        
        [NHIỆM VỤ] 
        Phân tích các kết quả OCR multi-scale từ 1 trang truyện, tổng hợp text chính xác nhất, rồi dịch TỪNG BLOCK sang tiếng Việt đạt chất lượng xuất bản.
        $previousContextText
        
        === DỮ LIỆU OCR (nhiều scale) ===
        $ocrResultsText
        
        === BLOCKS CẦN DỊCH ===
        $numberedBlocks
        
        [TIỀN XỬ LÝ]
        1. Sửa lỗi OCR: từ dính, sai chính tả, ký tự rác → suy luận từ ngữ cảnh.
        2. TÁI CẤU TRÚC LOGIC (Đặc biệt cho chữ tượng hình Nhật/Trung/Hàn): Nếu thứ tự từ bị đảo do quét OCR, hãy sắp xếp lại theo luồng logic Việt: 
           - Đối tượng -> Hành động -> Kết quả.
           - Thời gian/Trình tự: Việc xảy ra trước -> Việc xảy ra sau.
           - Trạng thái -> Biến đổi -> Hệ quả.
        $ancientInstruction
        [QUY TẮC BẮT BUỘC]
        ■ ĐẠI TỪ: Mặc định dùng xưng hô lịch sự/trung tính (tôi, cậu, mình, anh, em...). CHỈ dùng (tao, mày) khi nhân vật đang tức giận, cãi vã hoặc có biểu hiện thô lỗ rõ rệt.
        ■ ĐỘC THOẠI & LỜI DẪN: 
          - Độc thoại nội tâm (suy nghĩ): Dùng "mình" hoặc lược bỏ chủ ngữ.
          - Lời dẫn truyện (narration): KHÔNG dùng "mình", dùng văn phong khách quan hoặc lược bỏ chủ ngữ.
        ■ CHỐNG LẶP: Không lặp đại từ liên tục. "I... I..." → lược bỏ 1, gộp câu.
        ■ LOCALIZATION (BẢN ĐỊA HÓA): Đây là quy tắc quan trọng nhất. Dịch như một biên tập viên/biên kịch người Việt. Tuyệt đối KHÔNG dịch word-by-word (sát nghĩa từng từ).
        ■ THOÁT Ý: Ưu tiên dùng thành ngữ, tiếng lóng, khẩu ngữ phổ biến tại Việt Nam phù hợp với ngữ cảnh.
        ■ TIỂU TỪ: Bắt buộc thêm các tiểu từ (à, ừ, nhé, nhỉ, đâu, mà, chứ, sao, vậy, cơ, hả...) để câu văn nghe như người Việt nói chuyện đời thực.
        ■ CHỐNG LẶP & GỘP CÂU: Lược bỏ chủ ngữ thừa, gộp các câu đơn ngắn thành câu ghép mạch lạc mang phong cách văn nói.
        ■ CẤM: dùng từ "và" để nối các mệnh đề hành động (dùng dấu phẩy hoặc rồi/xong/liền).
        ■ NỘI DUNG NHẠY CẢM: CHỈ sử dụng các từ ngữ thô tục, tiếng lóng nhạy cảm khi văn bản gốc chứa nội dung 18+ rõ rệt (hành động tình dục, âm thanh nhạy cảm). Ở ngữ cảnh bình thường, tuyệt đối dùng từ ngữ trong sáng, lịch sự.
        
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
    ): String = getMistralMultiScalePrompt(ocrResultsText, numberedBlocks, blockCount, previousContextText, isAncientMode)
}
