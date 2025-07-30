package com.example.ocrmanga.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Test
import org.junit.Assert.*
import com.example.ocrmanga.ui.theme.ThemeColorSchemes

class AdvancedColorPickerTest {
    
    @Test
    fun testColorLuminanceConstraint() {
        // Test that light and dark mode colors are properly constrained
        val brightColor = Color(0xFFFFFFFF) // White
        val darkColor = Color(0xFF000000) // Black
        
        // Create light color scheme (should be brighter)
        val lightScheme = ThemeColorSchemes.LightPurple
        val customLightScheme = ThemeColorSchemes.createCustomColorScheme(lightScheme, brightColor)
        
        // Create dark color scheme (should be darker)
        val darkScheme = ThemeColorSchemes.DarkPurple
        val customDarkScheme = ThemeColorSchemes.createCustomColorScheme(darkScheme, darkColor)
        
        // Verify that the custom colors are properly adjusted
        val lightLuminance = getColorLuminance(customLightScheme.primary)
        val darkLuminance = getColorLuminance(customDarkScheme.primary)
        
        // Light mode should have higher luminance (brighter)
        assertTrue("Light mode primary should be brighter", lightLuminance > darkLuminance)
        
        // The difference should be reasonable (not exactly 100 units but within expected range)
        val luminanceDifference = lightLuminance - darkLuminance
        assertTrue("Luminance difference should be significant", luminanceDifference > 0.2f)
        
        // Light mode should be in upper range (0.6-0.9)
        assertTrue("Light mode luminance should be >= 0.6", lightLuminance >= 0.6f)
        assertTrue("Light mode luminance should be <= 0.9", lightLuminance <= 0.9f)
        
        // Dark mode should be in lower range (0.1-0.4)
        assertTrue("Dark mode luminance should be >= 0.1", darkLuminance >= 0.1f)
        assertTrue("Dark mode luminance should be <= 0.4", darkLuminance <= 0.4f)
    }
    
    @Test
    fun testHslConversion() {
        val color = Color(0xFF5533CC) // Purple-ish color
        
        // Test RGB to HSL and back conversion
        val hsl = rgbToHsl(color.red, color.green, color.blue)
        val backToRgb = hslToRgb(hsl[0], hsl[1], hsl[2])
        
        // Should be approximately the same (within tolerance due to floating point)
        assertEquals("Red component", color.red, backToRgb[0], 0.01f)
        assertEquals("Green component", color.green, backToRgb[1], 0.01f)
        assertEquals("Blue component", color.blue, backToRgb[2], 0.01f)
    }
    
    @Test
    fun testColorSimilarity() {
        val color1 = Color(0xFF5533CC)
        val color2 = Color(0xFF5533CD) // Very slightly different
        val color3 = Color(0xFFFFFFFF) // Very different
        
        assertTrue("Similar colors should be detected as similar", color1.isSimilarTo(color2, 0.1f))
        assertFalse("Different colors should not be detected as similar", color1.isSimilarTo(color3, 0.1f))
    }
    
    // Helper functions (copied from AdvancedColorPicker.kt for testing)
    private fun getColorLuminance(color: Color): Float {
        val r = if (color.red <= 0.03928f) color.red / 12.92f else kotlin.math.pow((color.red + 0.055f) / 1.055f, 2.4f).toFloat()
        val g = if (color.green <= 0.03928f) color.green / 12.92f else kotlin.math.pow((color.green + 0.055f) / 1.055f, 2.4f).toFloat()
        val b = if (color.blue <= 0.03928f) color.blue / 12.92f else kotlin.math.pow((color.blue + 0.055f) / 1.055f, 2.4f).toFloat()
        
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
    
    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        
        val lightness = (max + min) / 2f
        
        val saturation = if (delta == 0f) 0f else {
            if (lightness < 0.5f) delta / (max + min) else delta / (2f - max - min)
        }
        
        val hue = when {
            delta == 0f -> 0f
            max == r -> ((g - b) / delta + if (g < b) 6f else 0f) * 60f
            max == g -> ((b - r) / delta + 2f) * 60f
            else -> ((r - g) / delta + 4f) * 60f
        }
        
        return floatArrayOf(hue, saturation, lightness)
    }
    
    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        val hue = h / 360f
        val saturation = s.coerceIn(0f, 1f)
        val lightness = l.coerceIn(0f, 1f)
        
        val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - kotlin.math.abs((hue * 6f) % 2f - 1f))
        val m = lightness - c / 2f
        
        val (r, g, b) = when {
            hue < 1f/6f -> Triple(c, x, 0f)
            hue < 2f/6f -> Triple(x, c, 0f)
            hue < 3f/6f -> Triple(0f, c, x)
            hue < 4f/6f -> Triple(0f, x, c)
            hue < 5f/6f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        
        return floatArrayOf(
            (r + m).coerceIn(0f, 1f),
            (g + m).coerceIn(0f, 1f),
            (b + m).coerceIn(0f, 1f)
        )
    }
    
    private fun Color.isSimilarTo(other: Color, threshold: Float = 0.1f): Boolean {
        val rDiff = kotlin.math.abs(red - other.red)
        val gDiff = kotlin.math.abs(green - other.green)
        val bDiff = kotlin.math.abs(blue - other.blue)
        val aDiff = kotlin.math.abs(alpha - other.alpha)
        
        return rDiff < threshold && gDiff < threshold && bDiff < threshold && aDiff < threshold
    }
}