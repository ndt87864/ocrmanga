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
        [ROLE] Phiên dịch viên chuyên nghiệp bản địa hóa truyện tranh sang tiếng Việt.
        
        [INPUT] $text
        
        [TIỀN XỬ LÝ]
        1. Sửa lỗi OCR: từ dính, sai chính tả, ký tự rác → suy luận từ ngữ cảnh.
        2. Sắp xếp lại nếu thứ tự từ bị đảo.
        $ancientInstruction
        [QUY TẮC BẮT BUỘC]
        ■ CHỐNG LẶP: Không lặp đại từ liên tục. "I... I..." → lược bỏ 1, gộp câu.
        ■ THOÁT Ý: Dịch theo nghĩa, KHÔNG dịch word-for-word. Ưu tiên văn nói tự nhiên.
        ■ NGẮN GỌN: Câu ngắn → dịch ngắn. Không thêm thắt thừa.
        ■ TIỂU TỪ: Thêm à, ừ, nhé, nhỉ, đâu, mà, chứ, sao, vậy, cơ... cho tự nhiên.
        ■ CẤM: từ "và" nối mệnh đề → dùng dấu phẩy hoặc rồi/xong/liền.
        
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
        [ROLE] Phiên dịch viên bản địa hóa truyện tranh cấp cao. Bạn dịch như một biên kịch viên Việt Nam chuyên nghiệp — không phải máy dịch.
        
        [NHIỆM VỤ] 
        Phân tích các kết quả OCR multi-scale từ 1 trang truyện, tổng hợp text chính xác nhất, rồi dịch TỪNG BLOCK sang tiếng Việt đạt chất lượng xuất bản.
        $previousContextText
        
        === DỮ LIỆU OCR (nhiều scale) ===
        $ocrResultsText
        
        === BLOCKS CẦN DỊCH ===
        $numberedBlocks
        
        $ancientInstruction
        
        === QUY TẮC PHIÊN DỊCH (BẮT BUỘC) ===
        
        ■ 1. TIỀN XỬ LÝ OCR:
          - Sửa lỗi dính từ, sai chính tả, ký tự rác bằng suy luận ngữ cảnh.
          - So sánh các scale để chọn text chính xác nhất cho mỗi block.
          - Nếu text vô nghĩa hoàn toàn → suy luận từ ngữ cảnh các block xung quanh.
        
        ■ 2. ĐẠI TỪ & XƯNG HÔ:
          - Xác định MỐI QUAN HỆ nhân vật rồi mới chọn đại từ.
          - Tình cảm/vợ chồng: anh – em.
          - Thô bạo/cưỡng ép: tao – mày, gã – con này.
          - Bạn bè đồng lứa: tao – mày, nó – hắn.
          - Độc thoại: lược bỏ chủ ngữ hoặc dùng "mình".
          - NHẤT QUÁN xuyên suốt toàn bộ blocks trong cùng 1 ảnh.
        
        ■ 3. CẤU TRÚC CÂU:
          - Lược bỏ chủ ngữ lặp: chỉ giữ ở mệnh đề đầu, các mệnh đề sau bỏ.
          - Cấm dùng "và" nối mệnh đề → thay bằng dấu phẩy, rồi, xong, liền.
          - Gộp câu đơn ngắn liền nhau thành câu ghép mạch lạc.
          - Đảo cấu trúc cho thuần Việt: "A if B" → "Nếu B thì A".
        
        ■ 4. VĂN PHONG:
          - Dịch THOÁT Ý, cấm dịch word-for-word.
          - Dùng khẩu ngữ, văn nói đời thường — như người Việt thực sự nói.
          - Bắt buộc thêm tiểu từ cuối câu: à, ừ, nhé, nhỉ, đâu, cơ, mà, hả, chứ, sao, vậy...
          - Câu ngắn gốc → dịch ngắn. Không thêm thắt vô nghĩa.
          - Thuật ngữ chuyên môn → chuyển sang từ đời thường tương đương.
        
        ■ 5. KIỂM TRA CHẤT LƯỢNG (tự check trước khi output):
          ✓ Không lặp đại từ liên tục?
          ✓ Văn phong tự nhiên, không "máy dịch"?
          ✓ Đại từ nhất quán xuyên suốt?
          ✓ Câu có mạch lạc, đọc lên nghe như hội thoại thật?
          ✓ Không có từ thừa, câu lủng củng?
        
        === VÍ DỤ DỊCH CHUẨN ===
        ✗ SAI: "Tôi không thể chờ thêm được nữa. Tôi bắt đầu đây."
        ✓ ĐÚNG: "Không nhịn được nữa rồi,tôi bắt đầu đây."
        
        ✗ SAI: "Bạn không có quyền nói gì nếu bạn không muốn."
        ✓ ĐÚNG: "Không muốn thì im đi, đừng có càm ràm."
        
        ✗ SAI: "Nó rất tốt. Tôi rất thích nó."
        ✓ ĐÚNG: "Sướng thật... thích quá đi."
        
        === OUTPUT (CHỈ FORMAT NÀY, KHÔNG GÌ KHÁC) ===
        ⛔ TUYỆT ĐỐI CẤM: chú thích, ghi chú, giải thích, "(Đã gộp vào...)", "(Xem Block...)", "(Không dịch được)", hay BẤT KỲ nội dung nào ngoài bản dịch.
        ⛔ MỖI BLOCK PHẢI CÓ BẢN DỊCH RIÊNG. Không được gộp, bỏ qua, hay tham chiếu block khác.
        ⛔ Nếu text gốc ngắn/vô nghĩa → vẫn phải suy luận và dịch ra 1 câu có nghĩa.
        Block #1: <bản dịch>
        Block #2: <bản dịch>
        ...
        Block #$blockCount: <bản dịch>
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
            
            QUY TẮC ĐỐI XỨNG:
            - Cặp "$mainPronounPair" có nghĩa: cả 2 bên đều dùng CHUNG cặp này.
            - VD cặp "TÔI-CẬU": A nói "TÔI thăm CẬU" → B đáp "CẬU đưa TÔI đi đâu?" (đối xứng).
            - CẤM lẫn đại từ khác (mình, bạn...) vào hội thoại đã xác lập cặp.
            - NGOẠI LỆ: Độc thoại (suy nghĩ nội tâm) → dùng "mình" hoặc lược bỏ chủ ngữ.
            
            """
        } else ""
        
        return """
        
        === NGỮ CẢNH TỪ ẢNH TRƯỚC ===
        Cặp xưng hô: ${mainPronounPair ?: "chưa xác định"} → GIỮ NGUYÊN.
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
