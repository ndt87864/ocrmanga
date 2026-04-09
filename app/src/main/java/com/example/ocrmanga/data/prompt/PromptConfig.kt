package com.example.ocrmanga.data.prompt

/**
 * Configuration từ YAML frontmatter của prompt file
 */
data class PromptConfig(
    val version: String,
    val role: String,
    val temperature: Double = 0.5,
    val topP: Double = 0.9,
    val maxTokens: Int = 2048,
    val outputFormat: String = "json"
)

/**
 * Prompt template đã load từ file .md
 */
data class PromptTemplate(
    val config: PromptConfig,
    val content: String, // Nội dung markdown (đã loại bỏ frontmatter)
    val rawContent: String // Toàn bộ nội dung gốc
)
