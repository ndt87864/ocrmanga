package com.example.ocrmanga.data.prompt

import android.content.Context
import com.example.ocrmanga.utils.AppLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Load và parse prompt templates từ assets/prompts/
 */
class PromptLoader(private val context: Context) {

    companion object {
        private const val TAG = "PromptLoader"
        private const val PROMPTS_DIR = "prompts"

        // Cache để tránh đọc file nhiều lần
        private val cache = mutableMapOf<String, PromptTemplate>()
    }

    /**
     * Load prompt template từ file .md
     *
     * @param filename Tên file (ví dụ: "translator_v2.md")
     * @return PromptTemplate hoặc null nếu lỗi
     */
    fun loadPrompt(filename: String): PromptTemplate? {
        // Kiểm tra cache
        cache[filename]?.let { return it }

        return try {
            val fullPath = "$PROMPTS_DIR/$filename"
            val inputStream = context.assets.open(fullPath)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val rawContent = reader.readText()
            reader.close()

            val template = parsePromptFile(rawContent)

            // Lưu vào cache
            if (template != null) {
                cache[filename] = template
                AppLogger.i(TAG, "Loaded prompt: $filename (version ${template.config.version})")
            }

            template
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load prompt: $filename", e)
            null
        }
    }

    /**
     * Parse nội dung file .md với YAML frontmatter
     */
    private fun parsePromptFile(content: String): PromptTemplate? {
        val lines = content.lines()

        // Kiểm tra có frontmatter không (bắt đầu bằng ---)
        if (lines.isEmpty() || lines[0].trim() != "---") {
            AppLogger.w(TAG, "No YAML frontmatter found")
            return null
        }

        // Tìm dòng kết thúc frontmatter (dòng --- thứ 2)
        var endIndex = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                endIndex = i
                break
            }
        }

        if (endIndex == -1) {
            AppLogger.w(TAG, "Invalid YAML frontmatter (no closing ---)")
            return null
        }

        // Parse YAML frontmatter
        val frontmatter = lines.subList(1, endIndex)
        val config = parseFrontmatter(frontmatter) ?: return null

        // Nội dung markdown (sau frontmatter)
        val markdownContent = lines.subList(endIndex + 1, lines.size).joinToString("\n").trim()

        return PromptTemplate(
            config = config,
            content = markdownContent,
            rawContent = content
        )
    }

    /**
     * Parse YAML frontmatter thành PromptConfig
     */
    private fun parseFrontmatter(lines: List<String>): PromptConfig? {
        val map = mutableMapOf<String, String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val parts = trimmed.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().removeSurrounding("\"")
                map[key] = value
            }
        }

        return try {
            PromptConfig(
                version = map["version"] ?: "1.0",
                role = map["role"] ?: "unknown",
                temperature = map["temperature"]?.toDoubleOrNull() ?: 0.5,
                topP = map["top_p"]?.toDoubleOrNull() ?: 0.9,
                maxTokens = map["max_tokens"]?.toIntOrNull() ?: 2048,
                outputFormat = map["output_format"] ?: "text"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse frontmatter", e)
            null
        }
    }

    /**
     * Clear cache (dùng khi cần reload prompts)
     */
    fun clearCache() {
        cache.clear()
        AppLogger.i(TAG, "Prompt cache cleared")
    }

    /**
     * Build prompt với variables
     *
     * @param template PromptTemplate đã load
     * @param variables Map của các biến cần thay thế (ví dụ: "{{blocks}}" -> actual blocks)
     * @return Prompt đã điền đầy đủ
     */
    fun buildPrompt(template: PromptTemplate, variables: Map<String, String>): String {
        var result = template.content

        for ((key, value) in variables) {
            result = result.replace("{{$key}}", value)
        }

        return result
    }
}
