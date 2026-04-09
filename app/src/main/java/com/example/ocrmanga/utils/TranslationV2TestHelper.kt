package com.example.ocrmanga.utils

import android.content.Context
import com.example.ocrmanga.data.prompt.PromptLoader
import com.example.ocrmanga.data.translation.OptimizedTranslationService
import com.example.ocrmanga.data.api.NvidiaTranslationService
import okhttp3.OkHttpClient

/**
 * Helper để test và debug Translation V2
 */
object TranslationV2TestHelper {

    /**
     * Test load tất cả prompt templates
     */
    fun testPromptLoading(context: Context): Boolean {
        val promptLoader = PromptLoader(context)
        val prompts = listOf(
            "translator_v2.md",
            "translator_user_prompt_v2.md",
            "manager_v2.md",
            "manager_user_prompt_v2.md"
        )

        var allSuccess = true

        for (filename in prompts) {
            val template = promptLoader.loadPrompt(filename)
            if (template != null) {
                AppLogger.i("V2-TEST", "✓ Loaded: $filename (v${template.config.version})")
            } else {
                AppLogger.e("V2-TEST", "✗ Failed to load: $filename")
                allSuccess = false
            }
        }

        return allSuccess
    }

    /**
     * Test parse structured JSON response
     */
    fun testJsonParsing(context: Context): Boolean {
        val httpClient = OkHttpClient()
        val optimizedService = OptimizedTranslationService(
            context = context,
            nvidiaService = NvidiaTranslationService(httpClient)
        )

        // Sample JSON response
        val sampleJson = """
        {
          "blocks": [
            {"blockNumber": 1, "translation": "Test 1", "confidence": 0.9},
            {"blockNumber": 2, "translation": "Test 2", "confidence": 0.85}
          ],
          "metadata": {
            "totalBlocks": 2,
            "ancientMode": false,
            "pronounPair": "TÔI-CẬU"
          }
        }
        """.trimIndent()

        val result = optimizedService.parseStructuredResponse(sampleJson, 2)

        if (result != null) {
            AppLogger.i("V2-TEST", "✓ JSON parsing success")
            AppLogger.i("V2-TEST", "  Translations: ${result.translations}")
            AppLogger.i("V2-TEST", "  Confidence: ${result.confidence}")
            AppLogger.i("V2-TEST", "  Parse method: ${result.metadata.parseMethod}")
            return true
        } else {
            AppLogger.e("V2-TEST", "✗ JSON parsing failed")
            return false
        }
    }

    /**
     * Test fallback parser
     */
    fun testFallbackParsing(context: Context): Boolean {
        val httpClient = OkHttpClient()
        val optimizedService = OptimizedTranslationService(
            context = context,
            nvidiaService = NvidiaTranslationService(httpClient)
        )

        // Sample text response (legacy format)
        val sampleText = """
        Block #1: Bản dịch thứ nhất
        Block #2: Bản dịch thứ hai
        Block #3: Bản dịch thứ ba
        """.trimIndent()

        val result = optimizedService.parseFallback(sampleText, 3)

        if (result != null) {
            AppLogger.i("V2-TEST", "✓ Fallback parsing success")
            AppLogger.i("V2-TEST", "  Translations: ${result.translations}")
            AppLogger.i("V2-TEST", "  Success rate: ${result.metadata.successfulBlocks}/${result.metadata.totalBlocks}")
            return true
        } else {
            AppLogger.e("V2-TEST", "✗ Fallback parsing failed")
            return false
        }
    }

    /**
     * Run all tests
     */
    fun runAllTests(context: Context): TestResults {
        AppLogger.i("V2-TEST", "═══ RUNNING V2 TESTS ═══")

        val promptLoadingSuccess = testPromptLoading(context)
        val jsonParsingSuccess = testJsonParsing(context)
        val fallbackParsingSuccess = testFallbackParsing(context)

        val allSuccess = promptLoadingSuccess && jsonParsingSuccess && fallbackParsingSuccess

        AppLogger.i("V2-TEST", "═══ TEST RESULTS ═══")
        AppLogger.i("V2-TEST", "Prompt loading: ${if (promptLoadingSuccess) "✓ PASS" else "✗ FAIL"}")
        AppLogger.i("V2-TEST", "JSON parsing: ${if (jsonParsingSuccess) "✓ PASS" else "✗ FAIL"}")
        AppLogger.i("V2-TEST", "Fallback parsing: ${if (fallbackParsingSuccess) "✓ PASS" else "✗ FAIL"}")
        AppLogger.i("V2-TEST", "Overall: ${if (allSuccess) "✓ ALL PASS" else "✗ SOME FAILED"}")

        return TestResults(
            promptLoading = promptLoadingSuccess,
            jsonParsing = jsonParsingSuccess,
            fallbackParsing = fallbackParsingSuccess,
            overall = allSuccess
        )
    }

    data class TestResults(
        val promptLoading: Boolean,
        val jsonParsing: Boolean,
        val fallbackParsing: Boolean,
        val overall: Boolean
    )

    /**
     * Compare V1 vs V2 performance
     */
    suspend fun comparePerformance(
        context: Context,
        textBlocks: List<com.example.ocrmanga.data.models.TextBlockInfo>,
        ocrResults: List<Pair<Float, String>>,
        apiKey: String
    ): PerformanceComparison {
        val httpClient = OkHttpClient()

        // V1
        val v1Service = NvidiaTranslationService(httpClient)
        val v1StartTime = System.currentTimeMillis()
        val v1Result = v1Service.translateWithGLM5(textBlocks, ocrResults, apiKey)
        val v1Duration = System.currentTimeMillis() - v1StartTime

        // V2
        val v2Service = com.example.ocrmanga.data.api.NvidiaTranslationServiceV2(context, httpClient)
        val v2StartTime = System.currentTimeMillis()
        val v2Result = v2Service.translateWithGLM5(textBlocks, ocrResults, apiKey)
        val v2Duration = System.currentTimeMillis() - v2StartTime

        AppLogger.i("V2-BENCHMARK", "═══ PERFORMANCE COMPARISON ═══")
        AppLogger.i("V2-BENCHMARK", "V1 duration: ${v1Duration}ms")
        AppLogger.i("V2-BENCHMARK", "V2 duration: ${v2Duration}ms")
        AppLogger.i("V2-BENCHMARK", "Difference: ${v2Duration - v1Duration}ms (${if (v2Duration < v1Duration) "V2 faster" else "V1 faster"})")

        return PerformanceComparison(
            v1Duration = v1Duration,
            v2Duration = v2Duration,
            v1Success = v1Result != null,
            v2Success = v2Result != null
        )
    }

    data class PerformanceComparison(
        val v1Duration: Long,
        val v2Duration: Long,
        val v1Success: Boolean,
        val v2Success: Boolean
    ) {
        val v2Faster: Boolean get() = v2Duration < v1Duration
        val speedupPercentage: Double get() = ((v1Duration - v2Duration).toDouble() / v1Duration) * 100
    }
}
