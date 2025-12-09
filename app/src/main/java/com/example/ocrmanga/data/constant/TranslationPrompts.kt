package com.example.ocrmanga.data.constant

object TranslationPrompts {
    
    /**
     * Prompt cơ bản cho Mistral - dịch đơn giản một đoạn văn bản
     */
    fun getMistralBasicPrompt(text: String): String = """
        Vai trò: Bạn là chuyên gia tổ hợp văn bản và chuyển ngữ, đặc biệt giỏi trong việc phân tích và khôi phục văn bản OCR bị lỗi.
        
        Nhiệm vụ: Phân tích, khôi phục và dịch văn bản sau sang tiếng Việt: $text
        
        === BƯỚC XỬ LÝ TRƯỚC KHI DỊCH (BẮT BUỘC) ===
        
        BƯỚC 1 - KHÔI PHỤC TỪ VÔ NGHĨA:
        - Kiểm tra văn bản có từ/cụm từ vô nghĩa, bị nhận dạng sai không
        - Nếu phát hiện từ vô nghĩa, hãy suy luận từ ngữ cảnh câu để khôi phục nội dung đúng
        - Ưu tiên: Suy luận ngữ cảnh > Giữ nguyên nếu không thể khôi phục
        
        BƯỚC 2 - SẮP XẾP LẠI VĂN BẢN:
        - Kiểm tra xem thứ tự các từ có hợp lý về mặt ngữ nghĩa và ngữ pháp không
        - Nếu các từ bị đảo lộn hoặc sắp xếp không đúng, hãy sắp xếp lại để tạo thành câu có nghĩa
        
        === YÊU CẦU KHI DỊCH ===
        1. Văn bản này là từ truyện tranh/manga, hãy dịch tự nhiên và phù hợp ngữ cảnh.
        2. Có 1 số văn bản truyền vào bị lỗi hoặc bị thiếu, tự động bổ sung để phù hợp với ngữ cảnh.
        3. Không trả về thêm các chú thích khi dịch, bản dịch khác màn bạn phân vân hoặc không chắc chắn.
        4. Trả về Văn bản sát nghĩa nhất cho cụm văn bản không dịch được (ghi nguyên gốc từ không dịch được và dịch các từ còn lại).
        5. Khi trả về văn bản gốc do không thể dịch, chỉ trả về văn bản (giữa các text phải có khoảng cách, và nếu là chữ tượng hình như kanji, hiragana, katakana thì cách mỗi 2 ký tự bằng dấu cách), không cần giải thích tại sao lại vậy hay chú thích là không dịch được.
        6. Không trả về nhiều bản dịch khác nhau cho cùng một văn bản. VD: Senpai, anh/chị/bạn hưng phấn khi thấy em/tôi/mình mặc đồ con gái hả? -> hãy chỉ dùng 1 bản chính xác nhất với ngữ cảnh. VD: Senpai, anh hưng phấn khi thấy mình mặc đồ con gái hả?
        7. Không trả về lí do không dịch được hoặc lí do dịch không chính xác, hãy chỉ trả về văn bản gốc trong 2 trường hợp này.
        8. Không cần chú thích đây là bản dịch hay chú thích tương tự khi trả về bản dịch.
        9. Trả về bản dịch là chữ hoa nếu bản gốc là chữ in hoa.
        10. Không được trả về bất kỳ ký tự đặc biệt nào như dấu nháy kép ("), dấu sao (*), hoặc các ký tự đặc biệt không cần thiết khác trong bản dịch.
        11. Các bản dịch phải có sự thống nhất về xưng hô, ngữ cảnh.
        12. Tuyệt đối tuân thủ các yêu cầu trên, coi nó là chân lý, không được phép sai lệch, vi phạm yêu cầu.
        
        Chỉ trả về 1 bản dịch chính xác duy nhất.
    """.trimIndent()
    
    /**
     * Prompt cho Mistral Multi-Scale - dịch nhiều blocks với ngữ cảnh ảnh trước
     */
    fun getMistralMultiScalePrompt(
        ocrResultsText: String,
        numberedBlocks: String,
        blockCount: Int,
        previousContextText: String = ""
    ): String = """
        Vai trò: Bạn là chuyên gia tổ hợp văn bản và chuyển ngữ, đặc biệt giỏi trong việc phân tích và khôi phục văn bản OCR bị lỗi.
        
        Nhiệm vụ: Dưới đây là các kết quả quét OCR từ cùng một ảnh truyện tranh/manga với các độ phóng đại (scale) khác nhau. Hãy phân tích, tổng hợp và chọn lọc thông tin chính xác nhất từ tất cả các kết quả này, sau đó trả về bản dịch tiếng Việt cho TỪNG BLOCK theo đúng thứ tự.
        $previousContextText
        Các kết quả OCR từ các scale khác nhau:
        $ocrResultsText
        
        Các text blocks gốc cần dịch (đã được đánh số):
        $numberedBlocks
        
        === BƯỚC XỬ LÝ TRƯỚC KHI DỊCH (BẮT BUỘC) ===
        
        BƯỚC 1 - KHÔI PHỤC TỪ VÔ NGHĨA:
        - Kiểm tra từng block xem có từ/cụm từ vô nghĩa, bị nhận dạng sai không (ví dụ: ký tự lạ, từ không tồn tại trong ngôn ngữ gốc, từ bị đứt đoạn)
        - Nếu phát hiện từ vô nghĩa, hãy so sánh với các kết quả OCR từ scale khác để tìm từ đúng
        - Nếu không tìm được từ đúng từ các scale khác, hãy suy luận từ ngữ cảnh câu và các block xung quanh để khôi phục nội dung hợp lý
        - Ưu tiên: OCR từ scale khác > Suy luận ngữ cảnh > Giữ nguyên nếu không thể khôi phục
        
        BƯỚC 2 - SẮP XẾP LẠI VĂN BẢN OCR:
        - Kiểm tra xem thứ tự các từ trong mỗi block có hợp lý về mặt ngữ nghĩa và ngữ pháp không
        - Nếu các từ bị đảo lộn hoặc sắp xếp không đúng, hãy sắp xếp lại để tạo thành câu có nghĩa
        - Đảm bảo văn bản sau khi sắp xếp tuân theo cấu trúc ngữ pháp của ngôn ngữ gốc (Nhật/Trung/Hàn)
        - Với văn bản dọc (vertical), chú ý đọc từ trên xuống dưới, từ phải sang trái
        
        BƯỚC 3 - KIỂM TRA NGỮ CẢNH LIÊN BLOCK:
        - Xem xét mối quan hệ ngữ nghĩa giữa các block trong cùng một ảnh
        - Đảm bảo các block có sự liên kết logic (đối thoại, hội thoại, sự kiện)
        - Nếu một block đơn lẻ không có nghĩa nhưng kết hợp với block khác thì có nghĩa, hãy điều chỉnh cho phù hợp
        
        === YÊU CẦU KHI DỊCH ===
        1. Văn bản này là từ truyện tranh/manga, hãy dịch tự nhiên và phù hợp ngữ cảnh.
        2. Có 1 số văn bản truyền vào bị lỗi hoặc bị thiếu, tự động bổ sung để phù hợp với ngữ cảnh và kết hợp được với văn bản khác.
        3. Không trả về thêm các chú thích khi dịch, bản dịch khác màn bạn phân vân hoặc không chắc chắn.
        4. Trả về Văn bản sát nghĩa nhất cho cụm văn bản không dịch được (ghi nguyên gốc từ không dịch được và dịch các từ còn lại).
        5. Khi trả về văn bản gốc do không thể dịch, chỉ trả về văn bản (giữa các text phải có khoảng cách, và nếu là chữ tượng hình như kanji, hiragana, katakana thì cách mỗi 2 ký tự bằng dấu cách), không cần giải thích tại sao lại vậy hay chú thích là không dịch được.
        6. Không trả về nhiều bản dịch khác nhau cho cùng một văn bản. VD: Senpai, anh/chị/bạn hưng phấn khi thấy em/tôi/mình mặc đồ con gái hả? -> hãy chỉ dùng 1 bản chính xác nhất với ngữ cảnh trong trường hợp này. VD: Senpai, anh hưng phấn khi thấy mình mặc đồ con gái hả?
        7. Không trả về lí do không dịch được hoặc lí do dịch không chính xác, hãy chỉ trả về văn bản gốc trong 2 trường hợp này.
        8. Không cần chú thích đây là bản dịch hay chú thích tương tự khi trả về bản dịch.
        9. Trả về bản dịch là chữ hoa nếu bản gốc là chữ in hoa.
        10. Không được trả về bất kỳ ký tự đặc biệt nào như dấu nháy kép ("), dấu sao (*), hoặc các ký tự đặc biệt không cần thiết khác trong bản dịch.
        
        === QUAN TRỌNG: PHÂN BIỆT ĐỘC THOẠI VÀ HỘI THOẠI ===
        11. NHẬN BIẾT LOẠI VĂN BẢN (BẮT BUỘC PHÂN TÍCH TRƯỚC KHI DỊCH):
            a) ĐỘC THOẠI NỘI TÂM (suy nghĩ trong đầu):
               - Thường là văn bản trong khung suy nghĩ (bubble mây), không có đuôi nhọn
               - Nhân vật tự nói với bản thân, không có người nghe
               - Giọng điệu: Thắc mắc, ngạc nhiên, tự hỏi ("Sao lại thế nhỉ?", "Mình đang làm gì vậy?")
               - CÁCH DỊCH: Dùng "mình" hoặc lược bỏ chủ ngữ. TRÁNH dùng "tôi" trong độc thoại vì không tự nhiên.
               - VÍ DỤ: 
                 + "¿QUÉ ESTÁ PASANDO?" -> "Chuyện gì đang xảy ra vậy?" (KHÔNG phải "Tôi không hiểu chuyện gì đang xảy ra")
                 + "¿PORQUE VINO EL DUEÑO A VERME?" -> "Sao chủ quán lại đến thăm nhỉ?" (KHÔNG dùng "tôi/mình" - tự hỏi)
                 + "NO SERIA MEJOR..." -> "Chẳng phải... còn hơn sao?" (tự suy nghĩ, lược bỏ chủ ngữ)
            
            b) HỘI THOẠI (nói chuyện với người khác):
               - Văn bản trong khung thoại có đuôi nhọn chỉ về người nói
               - Có người nói và người nghe rõ ràng
               - Giọng điệu: Trực tiếp, có đại từ nhân xưng rõ ràng
               - CÁCH DỊCH: Dùng đại từ phù hợp quan hệ nhân vật (tôi-anh, tao-mày, mình-cậu, em-anh...)
               - VÍ DỤ:
                 + "FUISTE EL ULTIMO EN TERMINAR ASI QUE VINE A VERTE" -> "Cậu là người cuối cùng kết thúc, nên tôi đến thăm cậu."
                 + "GRACIAS POR TU ARDUO ESFUERZO" -> "Cảm ơn vì sự nỗ lực của cậu."
        
        12. ĐỒNG NHẤT XƯNG HÔ GIỮA CÁC NHÂN VẬT (BẮT BUỘC - ƯU TIÊN CAO NHẤT):
            - Mỗi CẶP nhân vật PHẢI có cách xưng hô NHẤT QUÁN trong toàn bộ truyện:
              + Nếu A gọi B là "cậu" thì LUÔN gọi "cậu", không đổi sang "anh/em/mày"
              + Nếu B tự xưng với A là "tôi" thì LUÔN xưng "tôi", không đổi sang "mình/tao/ta"
            - NẾU CÓ BẢN DỊCH ẢNH TRƯỚC: 
              + Phân tích KỸ LƯỠNG từng đại từ: TÔI, MÌNH, CẬU, ANH, EM, TAO, MÀY
              + BẮT BUỘC giữ NGUYÊN KHÔNG SAI SÓT đại từ cho từng nhân vật
              + Ví dụ: Nếu ảnh trước nhân vật A dùng "TÔI" và "CẬU" thì ảnh sau PHẢI tiếp tục "TÔI" và "CẬU"
            - QUAN TRỌNG: Xưng hô phản ánh MỐI QUAN HỆ, không nên thay đổi trừ khi có lý do trong cốt truyện
            
            VÍ DỤ ĐÚNG:
            - Ảnh 1: "CẬU làm gì vậy?" / "TÔI đang tìm đồ"
            - Ảnh 2: "CẬU tìm thấy chưa?" / "TÔI chưa thấy" ✓ (nhất quán)
            
            VÍ DỤ SAI:
            - Ảnh 1: "CẬU làm gì vậy?" / "TÔI đang tìm đồ"
            - Ảnh 2: "MÀY tìm thấy chưa?" / "TAO chưa thấy" ✗ (đổi ngôi bất hợp lý)
        
        13. NGÔI XƯNG HÔ TRONG ĐỘC THOẠI VS HỘI THOẠI:
            - ĐỘC THOẠI: Ưu tiên "mình" hoặc lược bỏ chủ ngữ để tự nhiên
              + "Sao tóc mình dài thế nhỉ?" (tự hỏi)
              + "Chân cũng nhỏ đi rồi..." (lược bỏ chủ ngữ)
            - HỘI THOẠI: Dùng đại từ rõ ràng theo quan hệ
              + "TÔI không hiểu ý ANH" (lịch sự, xa cách)
              + "TAO không hiểu ý MÀY" (suồng sã, thân thiết/thô lỗ)
              + "MÌNH không hiểu ý CẬU" (thân mật, ngang hàng)
        
        14. SỬ DỤNG ĐẠI TỪ HỢP LÝ (BẮT BUỘC):
            - TRÁNH lặp đại từ xưng hô LIÊN TIẾP trong 3-4 block liền nhau. Có thể lược bỏ chủ ngữ ở một số câu khi ngữ cảnh đã rõ.
            - Ví dụ LẶP QUÁ NHIỀU (SAI): Block 1: "TÔI nghe nói...", Block 2: "TÔI đã quan sát...", Block 3: "TÔI đi loanh quanh...", Block 4: "TÔI không muốn..."
            - Ví dụ CÂN BẰNG (ĐÚNG): Block 1: "TÔI nghe nói...", Block 2: "Quan sát một lúc thì thấy...", Block 3: "Đi loanh quanh phát hiện ra...", Block 4: "TÔI không muốn làm..."
            - VẪN PHẢI GIỮ đại từ trong các trường hợp sau:
              + Câu đầu tiên của nhân vật (để xác định ai đang nói)
              + Khi có sự đối lập/so sánh ("TÔI thì...", "còn CẬU thì...")
              + Khi cần nhấn mạnh cảm xúc ("TÔI không thể chịu nổi!")
              + Khi chuyển đổi người nói trong hội thoại
              + Câu ngắn, đơn lẻ cần chủ ngữ để có nghĩa
        
        15. VĂN PHONG TỰ NHIÊN - MƯỢT MÀ (ƯU TIÊN CAO NHẤT):
            - KHÔNG dịch máy móc từng từ. Hãy dịch theo NGHĨA và CẢM XÚC của câu.
            - Dịch như cách người Việt THỰC SỰ nói chuyện hàng ngày, tự nhiên như đang đọc truyện tranh Việt Nam.
            - Sử dụng ngữ khí từ phù hợp: "à", "ơi", "nhỉ", "đấy", "thôi", "mà", "chứ", "sao", "vậy", "thế"...
            - Câu ngắn gọn, có nhịp điệu, tránh câu dài lê thê.
            - QUAN TRỌNG: Khi dịch cảm thán/than thở, hãy dùng cách nói tự nhiên:
              + "NO PUEDO GANAR" -> "Sao lại thua liên tục vậy!" (KHÔNG phải "Tôi không thể thắng")
              + "NO GANE NADA" -> "Chẳng thắng được gì cả!" (KHÔNG phải "Tôi không thắng được gì")
              + "MI CABELLO NO ERA TAN LARGO" -> "Tóc mình đâu có dài thế đâu nhỉ?" (KHÔNG phải "Tóc tôi trước đây không dài thế này")
              + "QUE PASA CON ESTAS MANOS" -> "Sao tay lại nhỏ vậy?" (KHÔNG phải "Sao bàn tay tôi gầy thế này")
            - Khi nhân vật tự nói với bản thân (độc thoại nội tâm), dùng giọng thắc mắc, ngạc nhiên tự nhiên.
            - Tránh lặp cấu trúc câu. Nếu block trước dùng "...thế này", block sau dùng "...vậy" hoặc "...nhỉ".
        
        16. Tuyệt đối tuân thủ các yêu cầu trên, coi nó là chân lý, không được phép sai lệch, vi phạm yêu cầu.
        17. So sánh và phân tích sự khác biệt giữa các kết quả OCR để chọn ra văn bản gốc chính xác nhất trước khi dịch.
        18. BẮT BUỘC: Trả về kết quả theo định dạng SAU, mỗi block trên một dòng:
            Block #1: <bản dịch block 1>
            Block #2: <bản dịch block 2>
            Block #3: <bản dịch block 3>
            ...
        19. QUAN TRỌNG: Phải dịch đủ $blockCount blocks theo đúng thứ tự từ Block #1 đến Block #$blockCount
        
        === LƯU Ý CUỐI CÙNG - QUAN TRỌNG NHẤT ===
        - TRƯỚC KHI DỊCH: Tự phân tích trong đầu từng block là ĐỘC THOẠI hay HỘI THOẠI (KHÔNG GHI RA)
        - ĐỘC THOẠI: Dùng "mình" hoặc lược bỏ chủ ngữ, giọng tự hỏi, ngạc nhiên
        - HỘI THOẠI: Dùng đại từ rõ ràng (tôi/cậu, tao/mày...) và GIỮ NGUYÊN như ảnh trước
        - KHÔNG BAO GIỜ đổi đại từ giữa các ảnh khi cùng nhân vật!
        - CẤM TUYỆT ĐỐI: Thêm nhãn "*Độc thoại*" hay "*Hội thoại*" vào kết quả. CHỈ TRẢ VỀ BẢN DỊCH THUẦN TÚY.
        
        CHỈ TRẢ VỀ:
        Block #1: <bản dịch>
        Block #2: <bản dịch>
        ...
        
        KHÔNG TRẢ VỀ (SAI):
        Block #1: *Độc thoại* <bản dịch>
        Block #1: *Hội thoại* <bản dịch>
        **Block #1:** *Độc thoại*
        <bản dịch>
        
        Trả về bản dịch cho TỪNG BLOCK theo định dạng đã nêu.
    """.trimIndent()
    
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
            
            !!! CẢNH BÁO NGHIÊM TRỌNG - BẮT BUỘC TUÂN THỦ !!!
            ===================================================
            Ảnh trước đã xác lập CẶP ngôi xưng hô: $mainPronounPair
            
            => QUY TẮC VÀNG - BẮT BUỘC:
            
            1. HIỂU RÕ CẶP ĐỐI XỨNG (QUAN TRỌNG NHẤT):
               - "TÔI-CẬU" nghĩa là: Người A tự xưng "TÔI" và gọi người B là "CẬU"
               - KHI ĐÓ: Người B PHẢI tự xưng "TÔI" và gọi người A là "CẬU"
               - VÍ DỤ:
                 + A nói với B: "TÔI đến thăm CẬU" 
                 + B trả lời A: "CẬU sẽ đưa TÔI đi đâu?" ✓ (đối xứng)
                 + B trả lời A: "CẬU sẽ đưa MÌNH đi đâu?" ✗ (SAI - phá vỡ cặp)
            
            2. PHÂN BIỆT NGƯỜI NÓI:
               - Xác định rõ: Block này ai đang nói? Nói với ai?
               - Nếu là người được gọi "CẬU" → tự xưng "TÔI"
               - Nếu là người gọi người kia "CẬU" → tự xưng "TÔI"
               - CẤM: Trong hội thoại "TÔI-CẬU", KHÔNG BAO GIỜ dùng "MÌNH"
            
            3. ĐỘC THOẠI (suy nghĩ nội tâm) - NGOẠI LỆ:
               - Độc thoại KHÔNG phải hội thoại → dùng "mình" hoặc lược bỏ
               - Ví dụ: "Sao chủ quán lại đến nhỉ?" (không có "tôi/mình")
               - Ví dụ: "Giờ mình cũng thành con gái rồi..." (độc thoại)
            
            4. KIỂM TRA TRƯỚC KHI TRẢ VỀ:
               Mỗi block hội thoại:
               ☑ Ai đang nói? (A hay B?)
               ☑ Người đó tự xưng gì? (phải là "TÔI")
               ☑ Người đó gọi người kia gì? (phải là "CẬU")
               ☑ Có lẫn "MÌNH" không? (nếu có → SAI!)
            
            VÍ DỤ ĐÚNG (cặp TÔI-CẬU):
            - Block độc thoại: "Sao chủ quán lại đến nhỉ?" ✓
            - Block độc thoại: "Giờ mình cũng thành con gái..." ✓
            - A→B: "CẬU là người cuối, TÔI đến thăm CẬU." ✓
            - A→B: "TÔI thích thú khi thấy CẬU hoảng hốt." ✓
            - B→A: "CẬU sẽ đưa TÔI đi đâu?" ✓ (ĐỐI XỨNG)
            - B→A: "TÔI hiểu, TÔI sẽ làm." ✓
            
            VÍ DỤ SAI (cặp TÔI-CẬU):
            - B→A: "CẬU sẽ đưa MÌNH đi đâu?" ✗ (phải là TÔI)
            - B→A: "MÌNH hiểu, MÌNH sẽ làm." ✗ (phải là TÔI)
            - A→B: "MÌNH đến thăm CẬU." ✗ (phải là TÔI)
            
            !!! ĐỌC KỸ: Trong hội thoại "TÔI-CẬU", CẢ HAI NGƯỜI đều tự xưng "TÔI" !!!
            ===================================================
            
            """
        } else ""
        
        return """
        
        === BẢN DỊCH ẢNH TRƯỚC (BẮT BUỘC TUÂN THỦ) ===
        Dưới đây là bản dịch của ảnh trước đó trong cùng bộ truyện. BẮT BUỘC phải:
        - Giữ CHÍNH XÁC cặp ngôi xưng hô đã xác lập: ${mainPronounPair ?: "chưa xác định"}
        - PHÂN BIỆT rõ ràng giữa ĐỘC THOẠI (dùng "mình") và HỘI THOẠI (dùng cặp ngôi đã định)
        - Nắm bắt ngữ cảnh câu chuyện để dịch nối tiếp một cách mạch lạc
        - Nhận biết các nhân vật và cách họ giao tiếp với nhau
        $pronounInstruction
        Bản dịch ảnh trước:
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
        previousContextText: String = ""
    ): String = getMistralMultiScalePrompt(ocrResultsText, numberedBlocks, blockCount, previousContextText)
}
