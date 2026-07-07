package com.example.ocrmanga.utils

import android.net.Uri
import com.example.ocrmanga.data.models.TextBlockInfo
import com.google.gson.GsonBuilder

object ViewerJsonHelper {
    fun exportBlocksToJson(
        uri: Uri?,
        imageUris: List<Uri>,
        remainingImages: List<Uri>,
        getBlocks: (Uri) -> List<TextBlockInfo>
    ): String {
        val uris = if (uri != null) listOf(uri) else (imageUris + remainingImages)

        if (uri != null) {
            val blocks = getBlocks(uri)
            if (blocks.isEmpty()) return "[]"
            val exportList = blocks.mapIndexed { index, block ->
                mapOf(
                    "index" to index + 1,
                    "original_text" to (block.originalText ?: block.text),
                    "bounds" to mapOf(
                        "left" to block.bounds.left,
                        "top" to block.bounds.top,
                        "right" to block.bounds.right,
                        "bottom" to block.bounds.bottom
                    )
                )
            }
            return GsonBuilder().setPrettyPrinting().create().toJson(exportList)
        } else {
            val result = uris.mapIndexed { imgIdx, imageUri ->
                val blocks = getBlocks(imageUri)
                mapOf(
                    "image_id" to imgIdx + 1,
                    "blocks" to blocks.mapIndexed { blockIdx, block ->
                        mapOf(
                            "index" to blockIdx + 1,
                            "original_text" to (block.originalText ?: block.text),
                            "bounds" to mapOf(
                                "left" to block.bounds.left,
                                "top" to block.bounds.top,
                                "right" to block.bounds.right,
                                "bottom" to block.bounds.bottom
                            )
                        )
                    }
                )
            }
            return GsonBuilder().setPrettyPrinting().create().toJson(result)
        }
    }

    fun getExternalTranslationPrompt(uri: Uri?, json: String, isAncient: Boolean): String {
        val isBulk = uri == null

        val ancientInstruction = if (isAncient) {
            """
            [CHẾ ĐỘ CỔ TRANG - ƯU TIÊN CAO NHẤT]
            - Bối cảnh: Cổ đại, tiên hiệp, kiếm hiệp, lịch sử.
            - Văn phong: Sử dụng từ Hán Việt trang trọng, nhã nhặn hoặc uy dũng tùy nhân vật. Tuyệt đối tránh từ ngữ hiện đại, từ lóng gen Z.
            - Xưng hô (Dialogue Pronouns):
                + Ngôi thứ nhất: Ta, tại hạ, bần đạo, lão phu, bổn tọa, bổn cung, trẫm, thần, muội, tỷ, huynh.
                + Ngôi thứ hai: Ngươi, các hạ, vị này, huynh đệ, nương tử, phu quân, cô nương, công tử, đại hiệp, tiểu hữu, chư vị.
                + Ngôi thứ ba: Hắn, thị, y, bọn chúng, chúng nhân.
            - CẤM DÙNG: anh, em, cậu, tớ, mình, bạn, mày, tao (trừ khi có quan hệ gia đình cực kỳ gần gũi như huynh-muội).
            - SFX: Chuyển sang âm Hán Việt (ví dụ: "Bùm" -> "Oanh", "Xoẹt" -> "Xoát", "Vèo" -> "Tốc", "Choảng" -> "Keng").
            """.trimIndent()
        } else ""

        return if (!isBulk) {
            """
                Bạn là một phiên dịch viên chuyên nghiệp chuyên về manga.
                $ancientInstruction
                Hãy dịch các đoạn văn bản này sang tiếng Việt theo phong cách truyện tranh manga gần gũi với ngôn ngữ nói của người Việt Nam, giữ nguyên cấu trúc JSON và số thứ tự (index).
                Chỉ trả về file JSON duy nhất, không thêm giải thích.

                Cấu trúc yêu cầu:
                [
                  {
                    "index": 1,
                    "translated_text": "bản dịch ở đây"
                  },
                  ...
                ]

                Dữ liệu gốc:
                $json
            """.trimIndent()
        } else {
            """
                Bạn là một phiên dịch viên chuyên nghiệp chuyên về manga.
                $ancientInstruction
                Dưới đây là dữ liệu văn bản từ nhiều trang truyện tranh (được đánh dấu bằng image_id).
                Hãy dịch các đoạn văn bản này sang tiếng Việt theo phong cách truyện tranh manga gần gũi với ngôn ngữ nói của người Việt Nam, giữ nguyên cấu trúc JSON, image_id và index của từng block.
                Chỉ trả về file JSON duy nhất, không thêm giải thích.

                Cấu trúc yêu cầu:
                [
                  {
                    "image_id": 1,
                    "blocks": [
                      {
                        "index": 1,
                        "translated_text": "bản dịch ở đây"
                      },
                      ...
                    ]
                  },
                  ...
                ]

                Dữ liệu gốc:
                $json
            """.trimIndent()
        }
    }
}
