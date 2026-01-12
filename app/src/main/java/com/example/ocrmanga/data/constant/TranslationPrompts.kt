package com.example.ocrmanga.data.constant

object TranslationPrompts {
    
    /**
     * Prompt cơ bản cho Mistral - dịch đơn giản một đoạn văn bản
     */
    fun getMistralBasicPrompt(text: String, isAncientMode: Boolean = false): String {
        val ancientInstruction = if (isAncientMode) {
            """
            === CHẾ ĐỘ DỊCH CỔ TRANG/KIẾM HIỆP (BẮT BUỘC) ===
            1. PHONG CÁCH NGÔN NGỮ:
               - Sử dụng từ ngữ Hán Việt, văn phong cổ trang, kiếm hiệp.
               - Dùng các từ như: "tại hạ", "các hạ", "tiểu tử", "lão phu", "huynh đài", "cô nương", "bổn toạ", "vi sư", "đồ nhi"...
               - Câu văn cần trang trọng, uy nghiêm hoặc mang đậm sắc thái cổ xưa.
            2. XƯNG HÔ:
               - TÔI -> Ta, Tại hạ, Bổn toạ, Lão phu, Bần đạo... (tuỳ vai vế)
               - BẠN/CẬU -> Ngươi, Các hạ, Huynh đài, Cô nương, Tiểu tử...
               - ANH/EM -> Huynh/Đệ, Muội, Tỷ...
            3. LƯU Ý: Tuyệt đối không dùng từ ngữ hiện đại (anh, em, cậu, tới, mình, hớ...) trừ khi ngữ cảnh đặc biệt yêu cầu.
            """
        } else ""

        return """
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
        
        $ancientInstruction
        
        === YÊU CẦU KHI DỊCH ===
        1. Văn bản này là từ truyện tranh/manga, hãy dịch tự nhiên và phù hợp ngữ cảnh.
        2. Chỉ bổ sung từ khi thực sự cần thiết để câu hoàn chỉnh, tránh thêm từ không có trong text gốc.
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
        
        === CẤM DỊCH QUÁ NGẮN ===
        - Nếu văn bản gốc dài (>= 10 ký tự), bản dịch PHẢI tương xứng, KHÔNG được chỉ trả về 1-2 từ
        - VÍ DỤ SAI: Gốc 50 ký tự → Dịch "Ai..." (CẤM!)
        - Nếu không hiểu, hãy giữ nguyên văn bản gốc thay vì dịch quá ngắn
        
        Chỉ trả về 1 bản dịch chính xác duy nhất.
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
            === CHẾ ĐỘ DỊCH CỔ TRANG/KIẾM HIỆP (BẮT BUỘC) ===
            1. PHONG CÁCH NGÔN NGỮ:
               - Sử dụng từ ngữ Hán Việt, văn phong cổ trang, kiếm hiệp.
               - Dùng các từ như: "tại hạ", "các hạ", "tiểu tử", "lão phu", "huynh đài", "cô nương", "bổn toạ", "vi sư", "đồ nhi", "phu quân", "nương tử", "chủ nhân", "nô tỳ"...
               - Câu văn cần trang trọng, uy nghiêm hoặc mang đậm sắc thái cổ xưa.
            2. XƯNG HÔ:
               - TÔI -> Ta, Tại hạ, Bổn toạ, Lão phu, Bần đạo... (tuỳ vai vế)
               - BẠN/CẬU -> Ngươi, Các hạ, Huynh đài, Cô nương, Tiểu tử...
               - ANH/EM -> Huynh/Đệ, Muội, Tỷ...
            3. LƯU Ý: Tuyệt đối không dùng từ ngữ hiện đại (anh, em, cậu, tớ, mình, bạn...) trừ khi ngữ cảnh đặc biệt yêu cầu.
            """
        } else ""

        return """
        Vai trò: Bạn là chuyên gia tổ hợp văn bản và chuyển ngữ, đặc biệt giỏi trong việc phân tích và khôi phục văn bản OCR bị lỗi.
        
        Nhiệm vụ: Dưới đây là các kết quả quét OCR từ cùng một ảnh truyện tranh/manga với các độ phóng đại (scale) khác nhau. Hãy phân tích, tổng hợp và chọn lọc thông tin chính xác nhất từ tất cả các kết quả này, sau đó trả về bản dịch tiếng Việt cho TỪNG BLOCK theo đúng thứ tự.
        $previousContextText
        Các kết quả OCR từ các scale khác nhau:
        $ocrResultsText
        
        Các text blocks gốc cần dịch (đã được đánh số):
        $numberedBlocks
        
        $ancientInstruction
        
        ╔══════════════════════════════════════════════════════════════════╗
        ║  !!! CẢNH BÁO NGHIÊM TRỌNG - ĐỌC KỸ TRƯỚC KHI DỊCH !!!         ║
        ╠══════════════════════════════════════════════════════════════════╣
        ║  TUYỆT ĐỐI CẤM HOÁN ĐỔI NỘI DUNG DỊCH GIỮA CÁC BLOCKS!        ║
        ║                                                                  ║
        ║  Block #N gốc nói gì → Block #N dịch PHẢI là bản dịch của       ║
        ║  CHÍNH XÁC văn bản gốc đó, KHÔNG PHẢI của block khác!          ║
        ╚══════════════════════════════════════════════════════════════════╝
        
        CÁCH DỊCH ĐÚNG - TỪNG BLOCK MỘT:
        1. Đọc Block #1 gốc → Dịch Block #1 → Ghi "Block #1: <bản dịch>"
        2. Đọc Block #2 gốc → Dịch Block #2 → Ghi "Block #2: <bản dịch>"
        3. ... tiếp tục cho từng block
        
        KHÔNG ĐƯỢC:
        - Đọc tất cả blocks rồi sắp xếp lại thứ tự dịch
        - Gán bản dịch của block này cho block khác
        - Hoán đổi vị trí nội dung dịch
        
        VÍ DỤ LỖI NGHIÊM TRỌNG (BỊ CẤM TUYỆT ĐỐI):
        ┌─────────────────────────────────────────────────────────────────┐
        │ Block #1 gốc: "意外と冷静ですね" (Bạn thật bình tĩnh)           │
        │ Block #1 dịch: "Tức là không thể trở về?" ← SAI! HOÁN ĐỔI!     │
        │                                                                 │
        │ Block #14 gốc: "つまり、元の身体には戻れない" (Không thể trở về)│
        │ Block #14 dịch: "Bạn thật bình tĩnh" ← SAI! HOÁN ĐỔI!          │
        └─────────────────────────────────────────────────────────────────┘
        
        VÍ DỤ ĐÚNG:
        ┌─────────────────────────────────────────────────────────────────┐
        │ Block #1 gốc: "意外と冷静ですね"                                │
        │ Block #1 dịch: "Bạn thật bình tĩnh nhỉ." ← ĐÚNG!               │
        │                                                                 │
        │ Block #14 gốc: "つまり、元の身体には戻れない"                   │
        │ Block #14 dịch: "Tức là không thể trở về cơ thể cũ sao?" ← ĐÚNG!│
        └─────────────────────────────────────────────────────────────────┘
        
        KIỂM TRA TRƯỚC KHI TRẢ VỀ:
        - Block #1 dịch có KHỚP NGHĨA với Block #1 gốc không?
        - Block #2 dịch có KHỚP NGHĨA với Block #2 gốc không?
        - ... kiểm tra từng block
        
        === BƯỚC XỬ LÝ TRƯỚC KHI DỊCH ===
        
        BƯỚC 1 - KHÔI PHỤC TỪ VÔ NGHĨA:
        - Kiểm tra từng block xem có từ/cụm từ vô nghĩa, bị nhận dạng sai không (ví dụ: ký tự lạ, từ không tồn tại trong ngôn ngữ gốc, từ bị đứt đoạn)
        - Nếu phát hiện từ vô nghĩa, hãy so sánh với các kết quả OCR từ scale khác để tìm từ đúng
        - Nếu không tìm được từ đúng từ các scale khác, hãy suy luận từ ngữ cảnh câu và các block xung quanh để khôi phục nội dung hợp lý
        - Ưu tiên: OCR từ scale khác > Suy luận ngữ cảnh > Giữ nguyên nếu không thể khôi phục
        
        BƯỚC 2 - SẮP XẾP LẠI THỨ TỰ TỪ/CỤM TỪ BÊN TRONG MỖI BLOCK (QUAN TRỌNG!):
        !!! VĂN BẢN DỌC TIẾNG NHẬT/TRUNG ĐƯỢC OCR QUÉT THEO CỘT (PHẢI→TRÁI), NHƯNG CÂU CẦN ĐƯỢC DỊCH THEO NGỮ PHÁP !!!
        
        CÁCH XỬ LÝ:
        - OCR quét văn bản dọc theo thứ tự: cột phải → cột trái
        - Nhưng khi DỊCH, phải sắp xếp lại theo CẤU TRÚC NGỮ PHÁP để có nghĩa
        - KHÔNG dịch máy móc theo thứ tự OCR quét
        
        VÍ DỤ MINH HỌA:
        - OCR quét được (theo cột phải→trái): "つまり、有佐羽きんに起こった この現象は人という種に 於いて進化に匹敵する 経験であり"
        - Nếu dịch theo thứ tự OCR (SAI): "Hiện tượng này... tiến hóa... xảy ra với Ari... Tức là..."
        - Phải hiểu CÂU HOÀN CHỈNH rồi dịch (ĐÚNG): "Nói cách khác, những gì xảy ra với ông Arisawa là trải nghiệm tương đương với quá trình tiến hóa của loài người."
        
        QUY TẮC:
        1. ĐỌC TOÀN BỘ văn bản trong block TRƯỚC
        2. HIỂU NGỮ PHÁP tiếng Nhật/Trung để xác định cấu trúc câu đúng
        3. DỊCH theo nghĩa của CÂU HOÀN CHỈNH, không phải theo thứ tự OCR
        4. Tiếng Nhật: Chủ ngữ + は/が + ... + Động từ/Tính từ (ở cuối)
        5. "つまり" (tức là/nói cách khác) thường đứng ĐẦU CÂU khi dịch sang tiếng Việt
        
        BƯỚC 3 - KIỂM TRA NGỮ CẢNH LIÊN BLOCK:
        - Xem xét mối quan hệ ngữ nghĩa giữa các block trong cùng một ảnh
        - Đảm bảo các block có sự liên kết logic (đối thoại, hội thoại, sự kiện)
        - Nếu một block đơn lẻ không có nghĩa nhưng kết hợp với block khác thì có nghĩa, hãy điều chỉnh cho phù hợp
        
        BƯỚC 4 - DỊCH THEO NGỮ PHÁP, KHÔNG THEO THỨ TỰ OCR:
        - QUAN TRỌNG: Tiếng Nhật có cấu trúc SOV (Chủ ngữ - Tân ngữ - Động từ)
        - Động từ/Tính từ thường ở CUỐI CÂU tiếng Nhật, nhưng khi dịch sang tiếng Việt phải đặt SAU chủ ngữ
        - Các từ nối như "つまり" (tức là), "しかし" (nhưng), "だから" (vì vậy) phải đặt ở ĐẦU câu tiếng Việt
        - KHÔNG dịch từng cụm theo thứ tự OCR quét, phải HIỂU CẢ CÂU rồi mới dịch
        
        VÍ DỤ CÁCH DỊCH ĐÚNG:
        - Gốc: "つまり、有佐羽きんに起こったこの現象は人という種に於いて進化に匹敵する経験であり"
        - Phân tích: つまり(tức là) + 有佐羽きんに起こった(xảy ra với Arisawa) + この現象は(hiện tượng này) + 人という種に於いて(đối với loài người) + 進化に匹敵する(tương đương tiến hóa) + 経験であり(là trải nghiệm)
        - Dịch ĐÚNG: "Nói cách khác, những gì xảy ra với ông Arisawa là trải nghiệm tương đương với quá trình tiến hóa của loài người."
        - Dịch SAI: "Hiện tượng này... tiến hóa... xảy ra với Ari... Tức là..." (dịch theo thứ tự OCR)
        
        === YÊU CẦU KHI DỊCH ===
        1. Văn bản này là từ truyện tranh/manga, hãy dịch tự nhiên và phù hợp ngữ cảnh.
        2. Chỉ bổ sung từ khi thực sự cần thiết để câu hoàn chỉnh và logic, tránh thêm từ không có trong text gốc gây lặp lại ý nghĩa.
        3. Không trả về thêm các chú thích khi dịch, bản dịch khác màn bạn phân vân hoặc không chắc chắn.
        4. Trả về Văn bản sát nghĩa nhất cho cụm văn bản không dịch được (ghi nguyên gốc từ không dịch được và dịch các từ còn lại).
        5. Khi trả về văn bản gốc do không thể dịch, chỉ trả về văn bản (giữa các text phải có khoảng cách, và nếu là chữ tượng hình như kanji, hiragana, katakana thì cách mỗi 2 ký tự bằng dấu cách), không cần giải thích tại sao lại vậy hay chú thích là không dịch được.
        6. Không trả về nhiều bản dịch khác nhau cho cùng một văn bản. VD: Senpai, anh/chị/bạn hưng phấn khi thấy em/tôi/mình mặc đồ con gái hả? -> hãy chỉ dùng 1 bản chính xác nhất với ngữ cảnh trong trường hợp này. VD: Senpai, anh hưng phấn khi thấy mình mặc đồ con gái hả?
        7. Không trả về lí do không dịch được hoặc lí do dịch không chính xác, hãy chỉ trả về văn bản gốc trong 2 trường hợp này.
        8. Không cần chú thích đây là bản dịch hay chú thích tương tự khi trả về bản dịch.
        9. Trả về bản dịch là chữ hoa nếu bản gốc là chữ in hoa.
        10. Không được trả về bất kỳ ký tự đặc biệt nào như dấu nháy kép ("), dấu sao (*), hoặc các ký tự đặc biệt không cần thiết khác trong bản dịch.
        
        === CẤM DỊCH QUÁ NGẮN - BẮT BUỘC DỊCH ĐẦY ĐỦ NỘI DUNG ===
        !!! NGHIÊM CẤM: Dịch văn bản dài thành câu rất ngắn hoặc 1-2 từ !!!
        
        QUY TẮC ĐỘ DÀI BẢN DỊCH:
        - Nếu văn bản gốc có >= 10 ký tự → bản dịch PHẢI có ít nhất 5 ký tự
        - Nếu văn bản gốc có >= 20 ký tự → bản dịch PHẢI có ít nhất 10 ký tự  
        - Nếu văn bản gốc có >= 50 ký tự → bản dịch PHẢI có ít nhất 20 ký tự
        - Nếu văn bản gốc là câu hoàn chỉnh → bản dịch PHẢI là câu hoàn chỉnh
        
        VÍ DỤ SAI (BỊ CẤM):
        - Gốc: "四半世紀ほど前から症例が報告されていますが非常に稀なため" (50+ ký tự)
        - Dịch: "Ai..." ← SAI! Quá ngắn, không dịch đủ nội dung
        
        VÍ DỤ ĐÚNG:
        - Gốc: "四半世紀ほど前から症例が報告されていますが非常に稀なため、一般ではあまり知られていません"
        - Dịch: "Các ca bệnh đã được báo cáo từ khoảng một phần tư thế kỷ trước, nhưng vì rất hiếm gặp nên không được biết đến rộng rãi."
        
        NẾU KHÔNG HIỂU VĂN BẢN:
        - KHÔNG được trả về "Ai...", "À...", "Ừ..." cho văn bản dài
        - Hãy dịch từng phần có thể hiểu được
        - Phần không hiểu thì giữ nguyên văn bản gốc (có khoảng cách giữa các ký tự)
        
        === CẤM DỊCH NGƯỢC THỨ TỰ NGỮ NGHĨA ===
        !!! BẢN DỊCH PHẢI THEO THỨ TỰ NGỮ NGHĨA TỰ NHIÊN TIẾNG VIỆT !!!
        
        VÍ DỤ DỊCH NGƯỢC (SAI):
        - Gốc: "あいつ… 未来の僕" (Đứa đó... là mình trong tương lai)
        - Dịch SAI: "Tương lai của mình... Đứa đó..." ← NGƯỢC! Không tự nhiên
        - Dịch ĐÚNG: "Đứa đó... là mình trong tương lai"
        
        - Gốc: "思われます。分泌が続くと 女性ホルモンの これから更に"
        - Dịch SAI: "...dường như sẽ tiếp tục tiết ra hormone nữ. Từ giờ trở đi..." ← CẮT GIỮA CHỪNG
        - Dịch ĐÚNG: "Có vẻ hormone nữ sẽ tiếp tục được tiết ra. Từ giờ trở đi sẽ còn thay đổi nhiều hơn nữa."
        
        QUY TẮC:
        1. Câu dịch phải HOÀN CHỈNH, không cắt giữa chừng với "..."
        2. Thứ tự từ trong câu phải TỰ NHIÊN theo tiếng Việt
        3. Nếu OCR bị lộn xộn, hãy SUY LUẬN nghĩa đúng từ ngữ cảnh
        4. Chủ ngữ đi TRƯỚC, vị ngữ đi SAU trong tiếng Việt
        
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
            - VÍ DỤ TRONG MANGA (SENPAI/KOHAI):
              + SENPAI (người đàn anh/chị) thường gọi KOHAI (người đàn em): "cậu/em" và tự xưng "tôi/anh"
              + KOHAI gọi SENPAI: "senpai/anh/cậu" và tự xưng "tôi/em"
              + KHÔNG dùng "mình" trong hội thoại giữa senpai-kohai trừ khi là độc thoại
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
              + "MÌNH không hiểu ý CẬU" (thân mật, ngang hàng)              + SENPAI nói với KOHAI: "CẬU làm gì vậy?" (tôi ngầm)
              + KOHAI trả lời SENPAI: "TÔI đang làm bài tập." (tôi rõ ràng)        
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
        
        ╔══════════════════════════════════════════════════════════════════╗
        ║ 20. QUY TẮC VÀNG - TUYỆT ĐỐI KHÔNG VI PHẠM:                     ║
        ╠══════════════════════════════════════════════════════════════════╣
        ║ DỊCH TỪNG BLOCK MỘT, THEO ĐÚNG THỨ TỰ:                          ║
        ║                                                                  ║
        ║ ① Đọc Block #1 gốc → Dịch ĐÚNG NỘI DUNG Block #1 → Ghi ra      ║
        ║ ② Đọc Block #2 gốc → Dịch ĐÚNG NỘI DUNG Block #2 → Ghi ra      ║
        ║ ③ ... tiếp tục cho tất cả blocks                                ║
        ║                                                                  ║
        ║ !!! CẤM HOÁN ĐỔI NỘI DUNG DỊCH GIỮA CÁC BLOCKS !!!             ║
        ║                                                                  ║
        ║ Block #N gốc nói gì → Block #N dịch PHẢI là nghĩa của chính nó  ║
        ║ KHÔNG lấy nghĩa của block khác gán cho block này!               ║
        ╚══════════════════════════════════════════════════════════════════╝
        
        21. CẤM TUYỆT ĐỐI CÁC TIỀN TỐ "Dịch:", "Dịch (Cổ trang):"
            - Chỉ trả về nội dung của bản dịch.
            - KHÔNG BAO GIỜ viết: "Dịch: Xin chào" hay "Dịch (Cổ trang): Tại hạ xin chào"
            - HÃY VIẾT: "Xin chào" hoặc "Tại hạ xin chào"
            - KHÔNG trả về các dòng phân tích kiểu "Block #1 -> Block #5"
            
        === LƯU Ý CUỐI CÙNG ===
        - ĐỘC THOẠI: Dùng "mình" hoặc lược bỏ chủ ngữ, giọng tự hỏi
        - HỘI THOẠI: Dùng đại từ rõ ràng (tôi/cậu, tao/mày...)
        - CẤM thêm nhãn "*Độc thoại*" hay "*Hội thoại*" vào kết quả
        
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
