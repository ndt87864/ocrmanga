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
        
        ■ 1. SUY LUẬN & CHỈNH SỬA OCR (TIỀN ĐIỀU KIỆN):
          - BẮT BUỘC: Phân tích toàn bộ các blocks để nắm "mạch truyện".
          - PHÂN TÍCH CỤM DANH TỪ (RẤT QUAN TRỌNG):
            + Cấu trúc "AのようなB" (B giống như A) -> B là danh từ chính. Ví dụ: "オタクのような生徒" = "Học sinh trông giống Otaku" (người đó là học sinh). Không dịch thành "Tôi giống Otaku".
            + Cấu trúc bị động "Aに...れる" (Bị A làm gì đó) -> A là kẻ thực hiện hành động.
          - SỬA LỖI QUÉT (OCR CORRECTION):
            + "牛徒" (Ngưu đồ) -> "生徒" (Học sinh) (lỗi nét 'sanh' thành 'ngưu').
            + "寝込みを...われ" -> "寝込みを襲われ" (Bị tập kích/tấn công lúc ngủ).
            + "われ" đứng cuối câu bị động thường là "襲われ" (bị tấn công), "言われ" (bị nói), "思われ" (bị tưởng là).
            + Kanji bị nhận diện nhầm: "午"->"牛", "人"->"入", "工"->"エ".
            + Các cụm từ bị quét ngắt quãng hoặc dính ký tự lạ -> suy luận từ các block xung quanh để khôi phục cấu trúc câu hoàn chỉnh.
          - KẾT NỐI TỪNG PHẦN (FRAGMENT): Nếu một block chứa các cụm từ ngắt quãng (do dấu chấm, dấu phẩy thừa của OCR), hãy ghép chúng lại thành câu hoàn chỉnh trước khi dịch.
            + VD: "オタクのような" ... "牛徒" ... -> "オタクのような生徒" (Học sinh giống Otaku).

        ■ 2. VĂN PHONG & LOGIC (LOCALIZATION):
          - QUY LUẬT NHÂN QUẢ (BẮT BUỘC): Luôn sắp xếp lại câu/mệnh đề theo luồng logic: [Nguyên nhân/Tiền tố] -> [Hành động/Biến chuyển] -> [Kết quả/Cảm xúc]. Tuyệt đối không để kết quả đứng trước nguyên nhân nếu điều đó làm câu văn lủng củng.
          - TRÌNH TỰ THỜI GIAN: Sự kiện xảy ra trước phải được dịch trước. Tránh đảo lộn trình tự gây khó hiểu.
          - TINH CHỈNH TỪ NGỮ: 
            + Tránh dịch word-by-word. 
            + Trong các cảnh tự sự mang tính bàng hoàng, dùng từ ngữ miêu tả trạng thái và cảm nhận để tăng độ mượt (VD: thay vì "Ngực tôi to ra" hãy dùng "Cơ thể tôi bắt đầu nhú lên những đường cong lạ lẫm...").
            + Dùng từ ngữ tinh tế, thoát ý, giàu hình ảnh.

        ■ 3. ĐẠI TỪ & XƯNG HÔ (VÔ CÙNG QUAN TRỌNG):
          - ĐỘC THOẠI NỘI TÂM / TỰ SỰ / GIỚI THIỆU BẢN THÂN: Tuyệt đối CẤM dùng "tao". Dùng "Tôi", "Mình" hoặc lược bỏ chủ ngữ. Bất kể bản gốc xưng "俺" (Ore) hay gì, nếu là tự sự thì phải dùng xưng hô lịch sự/trung tính.
          - ĐỐI THOẠI (DIALOGUE): Dùng "tao - mày" CHỈ KHI nhân vật đang thực sự điên tiết, chửi lộn hoặc muốn sỉ nhục người khác.
          - TÌNH HUỐNG BƠ VƠ / YẾU THẾ: Khi nhân vật bị hại/bị tấn công, họ phải xưng "tôi" hoặc "em" để thể hiện sự bàng hoàng, tuyệt vọng.
          - TRUNG TÍNH: Ưu tiên [Tôi - Cậu], [Anh - Em], [Mày - Tao] (hạn chế).
          - NHẤT QUÁN: Đại từ phải nhất quán từ đầu đến cuối trang truyện.
        
        ■ 4. CẤU TRÚC CÂU:
          - Lược bỏ chủ ngữ lặp: chỉ giữ ở mệnh đề đầu, các mệnh đề sau bỏ.
          - Cấm dùng "và" nối mệnh đề → thay bằng dấu phẩy, rồi, xong, liền.
          - Gộp câu đơn ngắn liền nhau thành câu ghép mạch lạc.
          - Đảo cấu trúc cho thuần Việt: "A if B" → "Nếu B thì A".
        
        ■ 4. VĂN PHONG (LOCALIZATION):
          - TUYỆT ĐỐI CẤM dịch word-by-word. Bản dịch phải nghe như thể nó được viết bằng tiếng Việt ngay từ đầu.
          - SỬ DỤNG TIẾNG LÓNG & KHẨU NGỮ: Dùng ngôn từ đời thường của người Việt (ví dụ: "vãi", "thôi xong", "đùa à", "chết tiệt"... khi phù hợp).
          - THÊM TIỂU TỪ CUỐI CÂU: à, ừ, nhé, nhỉ, đâu, cơ, mà, hả, chứ, sao, vậy... là BẮT BUỘC.
          - Câu ngắn gốc → dịch ngắn gọn, súc tích. Không thêm thắt từ ngữ "kiểu máy dịch".
          - Thuật ngữ chuyên môn/địa phương nước ngoài → chuyển sang từ đời thường tương đương trong văn hoá Việt Nam.
        
        ■ 5. KIỂM TRA CHẤT LƯỢNG (tự check trước khi output):
          ✓ Văn phong có chất "Localization" (bản địa hóa) chưa, hay vẫn còn mùi "máy dịch"?
          ✓ Có dùng từ "và" sai cách không?
          ✓ Đại từ có tự nhiên và nhất quán không?
          ✓ Câu văn có mạch lạc, đọc lên nghe như hội thoại đời thực không?
        
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
