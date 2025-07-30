package com.example.ocrmanga.ui.theme

import org.junit.Test
import org.junit.Assert.*
import androidx.compose.ui.graphics.Color

class ThemeSystemTest {
    
    @Test
    fun testThemeVariantEnumValues() {
        val variants = ThemeVariant.values()
        assertEquals(5, variants.size)
        assertTrue(variants.contains(ThemeVariant.DEFAULT_PURPLE))
        assertTrue(variants.contains(ThemeVariant.BLUE))
        assertTrue(variants.contains(ThemeVariant.GREEN))
        assertTrue(variants.contains(ThemeVariant.ORANGE))
        assertTrue(variants.contains(ThemeVariant.RED))
    }
    
    @Test
    fun testColorSchemeRetrieval() {
        // Test that all theme variants have both light and dark schemes
        for (variant in ThemeVariant.values()) {
            val lightScheme = ThemeColorSchemes.getColorScheme(variant, false)
            val darkScheme = ThemeColorSchemes.getColorScheme(variant, true)
            
            assertNotNull(lightScheme)
            assertNotNull(darkScheme)
            assertNotEquals(lightScheme.primary, darkScheme.primary)
        }
    }
    
    @Test
    fun testCustomColorSchemeCreation() {
        val baseScheme = ThemeColorSchemes.LightPurple
        val customColor = Color(0xFF123456)
        
        val customScheme = ThemeColorSchemes.createCustomColorScheme(baseScheme, customColor)
        
        assertEquals(customColor, customScheme.primary)
        assertNotEquals(baseScheme.primary, customScheme.primary)
    }
    
    @Test
    fun testColorStringConversion() {
        val hexColor = "#FF5722"
        val color = hexColor.toColor()
        
        assertNotNull(color)
        assertEquals(Color(0xFFFF5722), color)
    }
    
    @Test
    fun testInvalidColorStringConversion() {
        val invalidHex = "invalid"
        val color = invalidHex.toColor()
        
        assertNull(color)
    }
    
    @Test
    fun testMaterialColorsExist() {
        // Test that all color variants are properly defined
        assertNotNull(MaterialColors.Purple40)
        assertNotNull(MaterialColors.Blue40)
        assertNotNull(MaterialColors.Green40)
        assertNotNull(MaterialColors.Orange40)
        assertNotNull(MaterialColors.Red40)
        
        assertNotNull(MaterialColors.Purple80)
        assertNotNull(MaterialColors.Blue80)
        assertNotNull(MaterialColors.Green80)
        assertNotNull(MaterialColors.Orange80)
        assertNotNull(MaterialColors.Red80)
    }
}