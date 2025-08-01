package com.example.ocrmanga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import com.example.ocrmanga.services.ScreenCaptureService
import com.example.ocrmanga.services.OverlayTranslationService

/**
 * Test for screen translation functionality
 */
@RunWith(AndroidJUnit4::class)
class ScreenTranslationTest {

    @Test
    fun testScreenCaptureServiceConstants() {
        // Test that service constants are properly defined
        assertEquals("start_capture", ScreenCaptureService.ACTION_START_CAPTURE)
        assertEquals("stop_capture", ScreenCaptureService.ACTION_STOP_CAPTURE)
        assertEquals("result_code", ScreenCaptureService.EXTRA_RESULT_CODE)
        assertEquals("data", ScreenCaptureService.EXTRA_DATA)
    }

    @Test
    fun testOverlayTranslationServiceConstants() {
        // Test that overlay service constants are properly defined
        assertEquals("show_overlay", OverlayTranslationService.ACTION_SHOW_OVERLAY)
        assertEquals("hide_overlay", OverlayTranslationService.ACTION_HIDE_OVERLAY)
        assertEquals("process_screen", OverlayTranslationService.ACTION_PROCESS_SCREEN)
        assertEquals("bitmap", OverlayTranslationService.EXTRA_BITMAP)
    }

    @Test
    fun testAppContext() {
        // Context of the app under test
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.ocrmanga", appContext.packageName)
    }
}