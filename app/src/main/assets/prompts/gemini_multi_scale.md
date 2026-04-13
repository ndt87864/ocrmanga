{{basePrompt}}

[ĐỊNH DẠNG OUTPUT BẮT BUỘC]
Hãy trả về đúng {{blockCount}} dòng cho {{blockCount}} block, định dạng chính xác từng ký tự như sau:
Block #1: [Nội dung dịch]
Block #2: [Nội dung dịch]
...
Block #{{blockCount}}: [Nội dung dịch]

LƯU Ý QUAN TRỌNG:
1. BẮT BUỘC phải có tiền tố "Block #N:" ở đầu mỗi dòng.
2. KHÔNG dùng định dạng markdown (như **in đậm**).
3. KHÔNG thêm bất kỳ lời dẫn, giải thích hay ghi chú nào khác.
4. Nếu không dịch được block nào, hãy giữ nguyên nội dung gốc của block đó.
