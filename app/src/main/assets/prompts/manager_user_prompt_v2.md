---
version: "2.0"
type: "user_prompt"
---

# Danh sách bản dịch cần review

{{numbered_translations}}

# Yêu cầu

Hãy review {{block_count}} blocks trên theo tiêu chí đã nêu.

{{ancient_mode_note}}

# Output

Trả về JSON hợp lệ theo schema đã định nghĩa. BẮT BUỘC phải review đủ {{block_count}} blocks.

CRITICAL: Return ONLY valid JSON. No markdown, no explanation, no text outside JSON.
