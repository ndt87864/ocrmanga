package com.example.ocrmanga.data.repositories

import android.graphics.Rect
import com.google.mlkit.nl.translate.TranslateLanguage

object OcrNoiseLanguageHelper {

    fun cleanText(text: String, detectedScript: String): String {
        var result = text

        val noisePatterns = listOf(
            Regex("^[\\s\\-_\\.\\,\\:\\;\\!\\?]+$"),
            Regex("^[\\d]+$"),
            Regex("^[|lIi1]+$"),
            Regex("^[\\-]+$"),
            Regex("^[\\'\\.\\`]+$"),
            Regex("^[oO0○◯]+$")
        )

        if (noisePatterns.any { it.matches(result.trim()) }) {
            return ""
        }

        val singleNoiseChars = setOf('|', '/', '\\', '-', '_', '.', ',', '\'', '`', '"', '○', '◯', '・')
        if (result.length == 1 && result[0] in singleNoiseChars) {
            return ""
        }

        val latinCount = result.count { it in 'A'..'Z' || it in 'a'..'z' }
        val totalChars = result.filter { !it.isWhitespace() }.length

        val isLatinScript = detectedScript == "en" || detectedScript == "es" ||
            (latinCount > totalChars * 0.5)

        if (latinCount > totalChars * 0.3) {
            result = result.replace('し', 'L').replace('シ', 'L')
            result = result.replace('ｌ', 'l')
            result = result.replace('Ｌ', 'L')
            result = result.replace('０', '0')
            result = result.replace('Ｏ', 'O')
        }

        if (isLatinScript) {
            result = result.replace(Regex("[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF" +
                "\u3040-\u309F\u30A0-\u30FF" +
                "\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F" +
                "\u3000-\u303F" +
                "\u31F0-\u31FF" +
                "\uFF65-\uFF9F" +
                "\u2E80-\u2EFF" +
                "\u3200-\u32FF" +
                "\u3300-\u33FF" +
                "\uFE30-\uFE4F" +
                "\uFF00-\uFF60" +
                "]"), "")

            if (result.trim().isEmpty() || Regex("^[\\s\\-_\\.\\,\\:\\;\\!\\?]+$").matches(result.trim())) {
                return ""
            }
        }

        return result.trim().replace(Regex("\\s+"), " ")
    }

    fun isNoiseBlock(text: String, bounds: Rect, confidence: Float, minBlockArea: Int, maxSingleCharAspect: Float, minOcrConfidence: Float): Boolean {
        val cleanText = text.trim()
        val area = bounds.width() * bounds.height()
        val aspectRatio = bounds.height().toFloat() / bounds.width().coerceAtLeast(1)
        
        val hasCJK = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF]").containsMatchIn(cleanText)
        
        if (hasCJK) {
            val cjkNoiseOnly = setOf("ー", "丨", "丶")
            if (cleanText in cjkNoiseOnly && area < minBlockArea / 2 && bounds.width() < 15 && bounds.height() < 15) {
                return true
            }
            return false
        }
        
        var noiseScore = 0
        
        if (area < minBlockArea / 2) {
            noiseScore += 2
        } else if (area < minBlockArea) {
            noiseScore += 1
        }
        
        if (cleanText.length <= 2) {
            if (aspectRatio > maxSingleCharAspect || aspectRatio < 1.0f / maxSingleCharAspect) {
                noiseScore += 2
            }
        }
        
        if (confidence < 0.25f) {
            noiseScore += 3
        } else if (confidence < minOcrConfidence) {
            noiseScore += 1
        }
        
        val noiseOnlyPattern = Regex("^[\\s\\-_\\.\\,\\|/\\\\\\'\"`○◯・]+$")
        if (noiseOnlyPattern.matches(cleanText)) {
            noiseScore += 2
        }
        
        val commonFalsePositives = setOf(
            "I", "l", "|", "1", "-", "_", ".", ",", "'", "`",
            "○", "◯", "O", "o", "0"
        )
        if (cleanText in commonFalsePositives && (bounds.width() < 20 || bounds.height() < 20)) {
            noiseScore += 2
        }
        
        return noiseScore >= 4
    }

    fun mapLanguageToMLKit(language: String): String {
        return when (language) {
            "zh" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "es" -> TranslateLanguage.SPANISH
            "en" -> TranslateLanguage.ENGLISH
            "vi" -> TranslateLanguage.VIETNAMESE
            else -> TranslateLanguage.ENGLISH
        }
    }

    fun detectLanguage(text: String): String? {
        val sampleText = text.take(100)
        val chinesePattern = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF]")
        val japanesePattern = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
        val koreanPattern = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]")
        val vietnamesePattern = Regex("[àáảãạăắằẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵ]")
        val latinPattern = Regex("[A-Za-z]")
        val spanishAccentPattern = Regex("[ñÑáÁéÉíÍóÓúÚüÜ]")
        val spanishWordPattern = Regex("\\b(que|de|la|el|y|en|no|si|por|para|con|una|un|los|las|se|del|al)\\b", RegexOption.IGNORE_CASE)

        return when {
            vietnamesePattern.containsMatchIn(sampleText) -> "vi"
            koreanPattern.containsMatchIn(sampleText) -> "ko"
            japanesePattern.containsMatchIn(sampleText) -> "ja"
            chinesePattern.containsMatchIn(sampleText) -> "zh"
            spanishAccentPattern.containsMatchIn(sampleText) -> "es"
            spanishWordPattern.findAll(sampleText).count() >= 2 -> "es"
            latinPattern.containsMatchIn(sampleText) && sampleText.count { it in 'A'..'z' } > sampleText.length * 0.5 -> "en"
            else -> null
        }
    }
}
