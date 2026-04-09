---
version: "2.0"
type: "user_prompt"
---

# Ngữ cảnh từ ảnh trước

{{previous_context}}

# Dữ liệu OCR (multi-scale)

{{ocr_results}}

# Blocks cần dịch

{{numbered_blocks}}

# Yêu cầu

Hãy dịch {{block_count}} blocks trên sang tiếng Việt, tuân thủ tất cả quy tắc đã nêu.

{{ancient_mode_instruction}}

# Output

Trả về JSON hợp lệ theo schema đã định nghĩa. BẮT BUỘC phải có đủ {{block_count}} blocks.
