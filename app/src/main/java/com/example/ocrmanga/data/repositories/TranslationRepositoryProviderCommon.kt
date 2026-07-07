package com.example.ocrmanga.data.repositories

import android.app.Application
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.gson.stream.JsonReader
import java.io.StringReader

open class TranslationRepositoryProviderCommon(application: Application) : TranslationRepositoryCore(application) {

    protected fun parseMultiBlockResponse(content: String, textBlocks: List<TextBlockInfo>): List<String> {
        val translatedBlocksMap = mutableMapOf<Int, String>()
        val lines = content.trim().split("\n")

        // Regex mạnh mẽ hơn để parse nhiều format: "Block #1:", "**Block #1:**", "Block #1 [Gốc] -> [Dịch]" etc.
        val blockPattern = Regex("""^\*{0,2}[Bb]lock\s*#?(\d+)(?:\**[:.)\->\s-]\**)?\s*(.*)$""")

        var i = 0
        while (i < lines.size) {
            val trimmedLine = lines[i].trim()
            val match = blockPattern.find(trimmedLine)
            if (match != null) {
                try {
                    val blockNumber = match.groupValues[1].toInt()
                    val blockIndex = blockNumber - 1
                    var contentAfterHeader = match.groupValues[2].trim()

                    // Thu thập tất cả các dòng thuộc về block này
                    val blockLines = mutableListOf<String>()
                    if (contentAfterHeader.isNotEmpty()) blockLines.add(contentAfterHeader)

                    var j = i + 1
                    while (j < lines.size) {
                        val nextLine = lines[j].trim()
                        if (blockPattern.matches(nextLine)) break
                        if (nextLine.isNotEmpty()) blockLines.add(nextLine)
                        j++
                    }
                    i = j - 1

                    var translation: String
                    // Ưu tiên 1: Tìm dấu mũi tên "->" hoặc "→"
                    val arrowLine = blockLines.find { it.contains("→") || it.contains("->") }
                    if (arrowLine != null) {
                        translation = if (arrowLine.contains("→")) arrowLine.substringAfter("→").trim()
                        else arrowLine.substringAfter("->").trim()
                    } else {
                        // Ưu tiên 2: Tìm dòng chứa nhãn loại block như *Hội thoại*
                        val typeLabeledLine = blockLines.find {
                            it.contains("*Hội thoại*") || it.contains("*Độc thoại*") ||
                            it.contains("*Trần thuật*") || it.contains("*SFX*")
                        }
                        if (typeLabeledLine != null) {
                            translation = typeLabeledLine
                                .replace(Regex("""^\*{0,2}[Bb]lock\s*#?\d+\s*"""), "")
                                .replace(Regex("""^\*?(Hội thoại|Độc thoại|Trần thuật|SFX)\*?:?\s*"""), "")
                                .trim()
                        } else {
                            // Ưu tiên 3: Lấy dòng cuối cùng không phải là text gốc (thường text gốc bọc trong *)
                            val cleanLines = blockLines.filter { !it.matches(Regex("""^\*+[^*]+\*+$""")) }
                            translation = if (cleanLines.isNotEmpty()) cleanLines.last() else blockLines.lastOrNull() ?: ""
                        }
                    }

                    // Dọn dẹp định dạng cuối cùng
                    translation = translation.replace("**", "").replace("*", "").trim()
                    if (translation.startsWith("[") && translation.endsWith("]")) {
                        translation = translation.substring(1, translation.length - 1).trim()
                    }
                    translation = translation.replace(Regex("^\\*?(Độc thoại|Hội thoại|Trần thuật|SFX)\\*?\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
                    translation = translation.replace(Regex("""^(Dịch|Translation|Gốc|Original|Vietnamese|Target)(\s*\(.*?\))?\s*[:\-]\s*""", RegexOption.IGNORE_CASE), "")

                    // Loại bỏ các chú thích/nội dung rác hoặc các dòng mô tả logic gộp block
                    val isAnnotation = translation.startsWith("(Lưu ý:") ||
                                      translation.contains("Lưu ý: Tôi buộc phải") ||
                                      translation.contains("-> Block #") ||
                                      translation.matches(Regex("""^\(.*[Gg]ộp.*[Bb]lock.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Xx]em.*[Bb]lock.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Kk]hông dịch.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Bb]ỏ qua.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Tt]ham chiếu.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Mm]erged.*\)$""")) ||
                                      translation.matches(Regex("""^\(.*[Ss]ee.*[Bb]lock.*\)$""")) ||
                                      (translation.startsWith("(") && translation.endsWith(")") && translation.length < 60) ||
                                      translation.length > 600

                    if (blockIndex >= 0 && blockIndex < textBlocks.size && !isAnnotation) {
                        translatedBlocksMap[blockIndex] = translation
                    }
                } catch (e: Exception) { }
            }
            i++
        }

        // Fallback: Nếu không parse được gì theo format 'Block #', thử parse theo số thứ tự đơn giản
        if (translatedBlocksMap.isEmpty()) {
            val numberPattern = Regex("""^(\d+)[.:\)]\s*(.+)$""")
            for (line in lines) {
                val match = numberPattern.find(line.trim())
                if (match != null) {
                    try {
                        val blockNumber = match.groupValues[1].toInt()
                        val trans = match.groupValues[2].trim()
                        if (blockNumber > 0 && trans.isNotBlank()) {
                            translatedBlocksMap[blockNumber - 1] = trans
                        }
                    } catch (e: Exception) { }
                }
            }
        }

        return List(textBlocks.size) { index ->
            translatedBlocksMap[index]?.takeIf { it.isNotBlank() } ?: textBlocks[index].text
        }
    }

    protected fun getCurrentGeminiModel(): String {
        val models = geminiModels
        return if (models.isNotEmpty()) models[0] else "gemini-2.5-flash"
    }

    protected fun getModelsInRotationOrder(providerModels: List<String>, currentIndex: Int, incrementIndex: () -> Unit): List<String> {
        if (providerModels.isEmpty()) return emptyList()
        val startingIdx = synchronized(this) {
            val idx = currentIndex % providerModels.size
            incrementIndex()
            idx
        }
        val order = mutableListOf<String>()
        for (offset in 0 until providerModels.size) {
            order.add(providerModels[(startingIdx + offset) % providerModels.size])
        }
        return order
    }

    protected fun normalizeBlockColors(blocks: List<TextBlockInfo>): List<TextBlockInfo> {
        if (blocks.isEmpty()) return blocks

        return try {
            val processedBlocks = blocks.map { it.copy() }.toMutableList()
            
            // 1. Phân loại màu cho từng block
            val ungroupedIndices = mutableListOf<Int>()
            
            for (i in processedBlocks.indices) {
                val block = processedBlocks[i]
                val color = block.originalTextColor ?: continue // Nếu không có màu text, bỏ qua
                
                val a = (color shr 24) and 0xFF
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val brightness = (r + g + b) / 3
                
                if (brightness < 55) {
                    // Luật 1: Đen hoặc gần đen -> Đen thuần
                    processedBlocks[i] = block.copy(originalTextColor = 0xFF000000.toInt())
                } else if (brightness > 200) {
                    // Luật 2: Trắng hoặc gần trắng -> Trắng thuần
                    processedBlocks[i] = block.copy(originalTextColor = 0xFFFFFFFF.toInt())
                } else {
                    // Cần gom nhóm màu gần giống nhau
                    ungroupedIndices.add(i)
                }
            }
            
            // 2. Luật 3: Gom các block có màu gần giống nhau
            val similarityThreshold = 55.0 // Ngưỡng khoảng cách màu Euclidean trong không gian RGB
            
            while (ungroupedIndices.isNotEmpty()) {
                val baseIdx = ungroupedIndices.removeAt(0)
                val baseBlock = processedBlocks[baseIdx]
                val baseColor = baseBlock.originalTextColor!!
                
                val baseR = (baseColor shr 16) and 0xFF
                val baseG = (baseColor shr 8) and 0xFF
                val baseB = baseColor and 0xFF
                
                // Tìm các block khác có màu gần giống baseBlock
                val clusterIndices = mutableListOf<Int>()
                clusterIndices.add(baseIdx)
                
                val iterator = ungroupedIndices.iterator()
                while (iterator.hasNext()) {
                    val idx = iterator.next()
                    val blockColor = processedBlocks[idx].originalTextColor!!
                    val r = (blockColor shr 16) and 0xFF
                    val g = (blockColor shr 8) and 0xFF
                    val b = blockColor and 0xFF
                    
                    val dist = kotlin.math.sqrt(
                        ((baseR - r) * (baseR - r) + 
                         (baseG - g) * (baseG - g) + 
                         (baseB - b) * (baseB - b)).toDouble()
                    )
                    
                    if (dist < similarityThreshold) {
                        clusterIndices.add(idx)
                        iterator.remove() // Đã gộp nhóm thì loại khỏi danh sách chờ
                    }
                }
                
                // Nếu nhóm có từ 1 block trở lên, tính màu trung bình và gán cho cả nhóm
                if (clusterIndices.isNotEmpty()) {
                    var sumR = 0
                    var sumG = 0
                    var sumB = 0
                    
                    clusterIndices.forEach { idx ->
                        val c = processedBlocks[idx].originalTextColor!!
                        sumR += (c shr 16) and 0xFF
                        sumG += (c shr 8) and 0xFF
                        sumB += c and 0xFF
                    }
                    
                    val avgR = sumR / clusterIndices.size
                    val avgG = sumG / clusterIndices.size
                    val avgB = sumB / clusterIndices.size
                    val avgColor = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
                    
                    clusterIndices.forEach { idx ->
                        processedBlocks[idx] = processedBlocks[idx].copy(originalTextColor = avgColor)
                    }
                }
            }
            
            processedBlocks
        } catch (e: Exception) {
            Log.e("TranslationRepository", "Lỗi khi chuẩn hóa màu text của các block", e)
            blocks
        }
    }

}
