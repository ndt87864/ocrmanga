package com.example.ocrmanga.ui.theme

import androidx.compose.ui.graphics.Color

// Material 3 seed colors for different themes
object MaterialColors {
    // Default Purple Theme
    val Purple80 = Color(0xFFD0BCFF)
    val PurpleGrey80 = Color(0xFFCCC2DC)
    val Pink80 = Color(0xFFEFB8C8)
    val Purple40 = Color(0xFF6650a4)
    val PurpleGrey40 = Color(0xFF625b71)
    val Pink40 = Color(0xFF7D5260)
    
    // Blue Theme
    val Blue80 = Color(0xFFAEC6FF)
    val BlueGrey80 = Color(0xFFB8C5D3)
    val LightBlue80 = Color(0xFFB8E6FF)
    val Blue40 = Color(0xFF2E5EDB)
    val BlueGrey40 = Color(0xFF3F4A5D)
    val LightBlue40 = Color(0xFF006398)
    
    // Green Theme  
    val Green80 = Color(0xFFB8F4C8)
    val GreenGrey80 = Color(0xFFC5D3C2)
    val LightGreen80 = Color(0xFFDCF8C6)
    val Green40 = Color(0xFF2E7D32)
    val GreenGrey40 = Color(0xFF3E5048)
    val LightGreen40 = Color(0xFF4C7C54)
    
    // Orange Theme
    val Orange80 = Color(0xFFFFD7B8)
    val OrangeGrey80 = Color(0xFFE1CFC2)
    val Amber80 = Color(0xFFFFE9B8)
    val Orange40 = Color(0xFFD84315)
    val OrangeGrey40 = Color(0xFF5D4037)
    val Amber40 = Color(0xFFFF8F00)
    
    // Red Theme
    val Red80 = Color(0xFFFFDAD6)
    val RedGrey80 = Color(0xFFE8C5C1)
    val Pink80Material = Color(0xFFFFB3BA)
    val Red40 = Color(0xFFD32F2F)
    val RedGrey40 = Color(0xFF5D4037)
    val Pink40Material = Color(0xFFC2185B)
    
    // Neutral colors
    val Surface = Color(0xFFFFFBFE)
    val OnSurface = Color(0xFF1C1B1F)
    val SurfaceVariant = Color(0xFFE7E0EC)
    val OnSurfaceVariant = Color(0xFF49454F)
    val Outline = Color(0xFF79747E)
    val OutlineVariant = Color(0xFFCAC4D0)
    
    // Dark theme neutrals
    val SurfaceDark = Color(0xFF1C1B1F)
    val OnSurfaceDark = Color(0xFFE6E1E5)
    val SurfaceVariantDark = Color(0xFF49454F)
    val OnSurfaceVariantDark = Color(0xFFCAC4D0)
    val OutlineDark = Color(0xFF938F99)
    val OutlineVariantDark = Color(0xFF49454F)
}

// Theme variants enum
enum class ThemeVariant {
    DEFAULT_PURPLE,
    BLUE,
    GREEN,
    ORANGE,
    RED
}

// Legacy color for compatibility
val ds = Color(0x1DF7F4F5)