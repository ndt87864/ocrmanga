package com.example.ocrmanga.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

object ThemeColorSchemes {
    
    // Default Purple Theme
    val LightPurple = lightColorScheme(
        primary = MaterialColors.Purple40,
        onPrimary = Color.White,
        primaryContainer = MaterialColors.Purple80,
        onPrimaryContainer = Color(0xFF21005D),
        secondary = MaterialColors.PurpleGrey40,
        onSecondary = Color.White,
        secondaryContainer = MaterialColors.PurpleGrey80,
        onSecondaryContainer = Color(0xFF1D192B),
        tertiary = MaterialColors.Pink40,
        onTertiary = Color.White,
        tertiaryContainer = MaterialColors.Pink80,
        onTertiaryContainer = Color(0xFF31111D),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFFFBFE),
        onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFE),
        onSurface = Color(0xFF1C1B1F),
        surfaceVariant = Color(0xFFE7E0EC),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E),
        outlineVariant = Color(0xFFCAC4D0),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF313033),
        inverseOnSurface = Color(0xFFF4EFF4),
        inversePrimary = MaterialColors.Purple80,
        surfaceDim = Color(0xFFDDD8DD),
        surfaceBright = Color(0xFFFFFBFE),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F2F7),
        surfaceContainer = Color(0xFFF1ECF1),
        surfaceContainerHigh = Color(0xFFEBE6EB),
        surfaceContainerHighest = Color(0xFFE6E1E6)
    )
    
    val DarkPurple = darkColorScheme(
        primary = MaterialColors.Purple80,
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = MaterialColors.Purple80,
        secondary = MaterialColors.PurpleGrey80,
        onSecondary = Color(0xFF332D41),
        secondaryContainer = Color(0xFF4A4458),
        onSecondaryContainer = MaterialColors.PurpleGrey80,
        tertiary = MaterialColors.Pink80,
        onTertiary = Color(0xFF492532),
        tertiaryContainer = Color(0xFF633B48),
        onTertiaryContainer = MaterialColors.Pink80,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF10111C),
        onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF10111C),
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = Color(0xFF49454F),
        onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99),
        outlineVariant = Color(0xFF49454F),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE6E1E5),
        inverseOnSurface = Color(0xFF313033),
        inversePrimary = MaterialColors.Purple40
    )
    
    // Blue Theme
    val LightBlue = lightColorScheme(
        primary = MaterialColors.Blue40,
        onPrimary = Color.White,
        primaryContainer = MaterialColors.Blue80,
        onPrimaryContainer = Color(0xFF001D36),
        secondary = MaterialColors.BlueGrey40,
        onSecondary = Color.White,
        secondaryContainer = MaterialColors.BlueGrey80,
        onSecondaryContainer = Color(0xFF0F1419),
        tertiary = MaterialColors.LightBlue40,
        onTertiary = Color.White,
        tertiaryContainer = MaterialColors.LightBlue80,
        onTertiaryContainer = Color(0xFF001F2A),
        background = Color(0xFFFFFBFE),
        onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFE),
        onSurface = Color(0xFF1C1B1F)
    )
    
    val DarkBlue = darkColorScheme(
        primary = MaterialColors.Blue80,
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF004A77),
        onPrimaryContainer = MaterialColors.Blue80,
        secondary = MaterialColors.BlueGrey80,
        onSecondary = Color(0xFF293438),
        secondaryContainer = Color(0xFF3F4A5D),
        onSecondaryContainer = MaterialColors.BlueGrey80,
        tertiary = MaterialColors.LightBlue80,
        onTertiary = Color(0xFF003544),
        tertiaryContainer = Color(0xFF004D61),
        onTertiaryContainer = MaterialColors.LightBlue80,
        background = Color(0xFF10111C),
        onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF10111C),
        onSurface = Color(0xFFE6E1E5)
    )
    
    // Green Theme
    val LightGreen = lightColorScheme(
        primary = MaterialColors.Green40,
        onPrimary = Color.White,
        primaryContainer = MaterialColors.Green80,
        onPrimaryContainer = Color(0xFF002106),
        secondary = MaterialColors.GreenGrey40,
        onSecondary = Color.White,
        secondaryContainer = MaterialColors.GreenGrey80,
        onSecondaryContainer = Color(0xFF191E1A),
        tertiary = MaterialColors.LightGreen40,
        onTertiary = Color.White,
        tertiaryContainer = MaterialColors.LightGreen80,
        onTertiaryContainer = Color(0xFF0A2818),
        background = Color(0xFFFFFBFE),
        onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFE),
        onSurface = Color(0xFF1C1B1F)
    )
    
    val DarkGreen = darkColorScheme(
        primary = MaterialColors.Green80,
        onPrimary = Color(0xFF003909),
        primaryContainer = Color(0xFF00530D),
        onPrimaryContainer = MaterialColors.Green80,
        secondary = MaterialColors.GreenGrey80,
        onSecondary = Color(0xFF293429),
        secondaryContainer = Color(0xFF3E5048),
        onSecondaryContainer = MaterialColors.GreenGrey80,
        tertiary = MaterialColors.LightGreen80,
        onTertiary = Color(0xFF1E3A2A),
        tertiaryContainer = Color(0xFF34513E),
        onTertiaryContainer = MaterialColors.LightGreen80,
        background = Color(0xFF10111C),
        onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF10111C),
        onSurface = Color(0xFFE6E1E5)
    )
    
    // Orange Theme
    val LightOrange = lightColorScheme(
        primary = MaterialColors.Orange40,
        onPrimary = Color.White,
        primaryContainer = MaterialColors.Orange80,
        onPrimaryContainer = Color(0xFF2E1500),
        secondary = MaterialColors.OrangeGrey40,
        onSecondary = Color.White,
        secondaryContainer = MaterialColors.OrangeGrey80,
        onSecondaryContainer = Color(0xFF1A120C),
        tertiary = MaterialColors.Amber40,
        onTertiary = Color.White,
        tertiaryContainer = MaterialColors.Amber80,
        onTertiaryContainer = Color(0xFF261A00),
        background = Color(0xFFFFFBFE),
        onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFE),
        onSurface = Color(0xFF1C1B1F)
    )
    
    val DarkOrange = darkColorScheme(
        primary = MaterialColors.Orange80,
        onPrimary = Color(0xFF5A1A00),
        primaryContainer = Color(0xFF792100),
        onPrimaryContainer = MaterialColors.Orange80,
        secondary = MaterialColors.OrangeGrey80,
        onSecondary = Color(0xFF2F251F),
        secondaryContainer = Color(0xFF453B2F),
        onSecondaryContainer = MaterialColors.OrangeGrey80,
        tertiary = MaterialColors.Amber80,
        onTertiary = Color(0xFF3E2E00),
        tertiaryContainer = Color(0xFF564200),
        onTertiaryContainer = MaterialColors.Amber80,
        background = Color(0xFF10111C),
        onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF10111C),
        onSurface = Color(0xFFE6E1E5)
    )
    
    // Red Theme
    val LightRed = lightColorScheme(
        primary = MaterialColors.Red40,
        onPrimary = Color.White,
        primaryContainer = MaterialColors.Red80,
        onPrimaryContainer = Color(0xFF410002),
        secondary = MaterialColors.RedGrey40,
        onSecondary = Color.White,
        secondaryContainer = MaterialColors.RedGrey80,
        onSecondaryContainer = Color(0xFF1A120C),
        tertiary = MaterialColors.Pink40Material,
        onTertiary = Color.White,
        tertiaryContainer = MaterialColors.Pink80Material,
        onTertiaryContainer = Color(0xFF3E001D),
        background = Color(0xFFFFFBFE),
        onBackground = Color(0xFF1C1B1F),
        surface = Color(0xFFFFFBFE),
        onSurface = Color(0xFF1C1B1F)
    )
    
    val DarkRed = darkColorScheme(
        primary = MaterialColors.Red80,
        onPrimary = Color(0xFF690005),
        primaryContainer = Color(0xFF93000A),
        onPrimaryContainer = MaterialColors.Red80,
        secondary = MaterialColors.RedGrey80,
        onSecondary = Color(0xFF2F251F),
        secondaryContainer = Color(0xFF453B2F),
        onSecondaryContainer = MaterialColors.RedGrey80,
        tertiary = MaterialColors.Pink80Material,
        onTertiary = Color(0xFF5E1032),
        tertiaryContainer = Color(0xFF7B2D47),
        onTertiaryContainer = MaterialColors.Pink80Material,
        background = Color(0xFF10111C),
        onBackground = Color(0xFFE6E1E5),
        surface = Color(0xFF10111C),
        onSurface = Color(0xFFE6E1E5)
    )
    
    fun getColorScheme(variant: ThemeVariant, isDark: Boolean): ColorScheme {
        return when (variant) {
            ThemeVariant.DEFAULT_PURPLE -> if (isDark) DarkPurple else LightPurple
            ThemeVariant.BLUE -> if (isDark) DarkBlue else LightBlue
            ThemeVariant.GREEN -> if (isDark) DarkGreen else LightGreen
            ThemeVariant.ORANGE -> if (isDark) DarkOrange else LightOrange
            ThemeVariant.RED -> if (isDark) DarkRed else LightRed
        }
    }
    
    fun createCustomColorScheme(
        baseScheme: ColorScheme,
        customPrimaryColor: Color
    ): ColorScheme {
        return baseScheme.copy(
            primary = customPrimaryColor,
            primaryContainer = customPrimaryColor.copy(alpha = 0.3f),
            inversePrimary = if (baseScheme == DarkPurple || baseScheme == DarkBlue || 
                               baseScheme == DarkGreen || baseScheme == DarkOrange || 
                               baseScheme == DarkRed) {
                customPrimaryColor.copy(alpha = 0.8f)
            } else {
                customPrimaryColor
            }
        )
    }
}

// Extension function to convert hex string to Color
fun String.toColor(): Color? {
    return try {
        val colorLong = removePrefix("#").toLong(16)
        Color(colorLong or 0xFF000000)
    } catch (e: Exception) {
        null
    }
}