package com.example.ocrmanga

import android.graphics.Rect
import com.example.ocrmanga.data.models.TextBlockInfo
import org.junit.Test
import org.junit.Assert.*

class VerticalTextTest {

    @Test
    fun testVerticalTextOrdering() {
        // Giả lập các text block của văn bản dọc tiếng Nhật
        // Đọc từ phải sang trái, từ trên xuống dưới
        val textBlocks = listOf(
            // Cột phải (đọc trước)
            TextBlockInfo("彼", Rect(300, 100, 350, 150), 20f, isVertical = true),
            TextBlockInfo("は", Rect(300, 160, 350, 210), 20f, isVertical = true),
            TextBlockInfo("学", Rect(300, 220, 350, 270), 20f, isVertical = true),
            
            // Cột trái (đọc sau)
            TextBlockInfo("私", Rect(200, 100, 250, 150), 20f, isVertical = true),
            TextBlockInfo("の", Rect(200, 160, 250, 210), 20f, isVertical = true),
            TextBlockInfo("友", Rect(200, 220, 250, 270), 20f, isVertical = true)
        )

        // Sắp xếp theo logic văn bản dọc: từ phải sang trái, từ trên xuống dưới
        val sorted = textBlocks.sortedWith(
            compareByDescending<TextBlockInfo> { it.bounds.right }
                .thenBy { it.bounds.top }
        )

        // Kết quả mong đợi: "彼は学私の友" (cột phải trước, cột trái sau)
        val expectedOrder = listOf("彼", "は", "学", "私", "の", "友")
        val actualOrder = sorted.map { it.text }

        assertEquals(expectedOrder, actualOrder)
    }

    @Test
    fun testHorizontalTextOrdering() {
        // Giả lập các text block của văn bản ngang
        // Đọc từ trái sang phải, từ trên xuống dưới
        val textBlocks = listOf(
            // Dòng dưới
            TextBlockInfo("World", Rect(200, 200, 280, 230), 20f, isVertical = false),
            TextBlockInfo("Hello", Rect(100, 200, 180, 230), 20f, isVertical = false),
            
            // Dòng trên
            TextBlockInfo("Text", Rect(200, 100, 280, 130), 20f, isVertical = false),
            TextBlockInfo("Sample", Rect(100, 100, 180, 130), 20f, isVertical = false)
        )

        // Sắp xếp theo logic văn bản ngang: từ trên xuống dưới, từ trái sang phải
        val sorted = textBlocks.sortedWith(
            compareBy<TextBlockInfo> { it.bounds.top }
                .thenBy { it.bounds.left }
        )        // Kết quả mong đợi: "Sample Text Hello World"
        val expectedOrder = listOf("Sample", "Text", "Hello", "World")
        val actualOrder = sorted.map { it.text }

        // Debug output
        println("Expected: $expectedOrder")
        println("Actual: $actualOrder")
        sorted.forEach { block ->
            println("${block.text}: top=${block.bounds.top}, left=${block.bounds.left}")
        }

        assertEquals(expectedOrder, actualOrder)
    }

    @Test
    fun testLanguageDetection() {
        val chineseText = "这是中文文本"
        val japaneseText = "これは日本語です"
        val koreanText = "이것은 한국어입니다"
        val englishText = "This is English text"

        // Regex pattern cho CJK
        val chinesePattern = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF]")
        val japanesePattern = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
        val koreanPattern = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]")

        assertTrue("Chinese text should be detected", chinesePattern.containsMatchIn(chineseText))
        assertTrue("Japanese text should be detected", 
            japanesePattern.containsMatchIn(japaneseText) || chinesePattern.containsMatchIn(japaneseText))
        assertTrue("Korean text should be detected", koreanPattern.containsMatchIn(koreanText))
        assertFalse("English text should not be detected as CJK", 
            chinesePattern.containsMatchIn(englishText) || 
            japanesePattern.containsMatchIn(englishText) || 
            koreanPattern.containsMatchIn(englishText))
    }
}
