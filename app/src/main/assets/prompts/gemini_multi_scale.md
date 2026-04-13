{{basePrompt}}

[ĐỊNH DẠNG OUTPUT BẮT BUỘC]
Hãy trả về đúng {{blockCount}} dòng cho {{blockCount}} block, định dạng chính xác từng ký tự như sau:
Block #1: [Nội dung dịch]
Block #2: [Nội dung dịch]
...
Block #{{blockCount}}: [Nội dung dịch]

LƯU Ý QUAN TRỌNG CHO GEMINI:
1. TUYỆT ĐỐI CHỐNG ẢO GIÁC: Không tự thêm tên riêng hoặc địa danh.
2. XỬ LÝ NHIỄU OCR: Nếu gặp ký tự rác (ví dụ: X7S7), đừng cố dịch nó thành tên nhân vật. Hãy bỏ qua hoặc trả về "...".
3. NHẤT QUÁN: Sử dụng đúng cặp xưng hô đã được thiết lập trong phần ngữ cảnh.
4. KHÔNG dùng định dạng markdown (như **in đậm**).
5. KHÔNG thêm bất kỳ lời dẫn, giải thích hay ghi chú nào.
