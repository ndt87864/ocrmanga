package com.example.ocrmanga.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Legacy color schemes for compatibility
private val DarkColorScheme = darkColorScheme(
    primary = MaterialColors.Purple80,
    secondary = MaterialColors.PurpleGrey80,
    tertiary = MaterialColors.Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = MaterialColors.Purple40,
    secondary = MaterialColors.PurpleGrey40,
    tertiary = MaterialColors.Pink40
)

@Composable
fun OCRMangaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themePreferences = ThemePreferences(context)
    
    // Collect theme preferences
    val themeVariant by themePreferences.themeVariant.collectAsState(initial = ThemeVariant.DEFAULT_PURPLE)
    val isDarkModePreferred by themePreferences.isDarkMode.collectAsState(initial = false)
    val isDynamicColorEnabled by themePreferences.isDynamicColorEnabled.collectAsState(initial = true)
    val customPrimaryColor by themePreferences.customPrimaryColor.collectAsState(initial = null)
    val useCustomColor by themePreferences.useCustomColor.collectAsState(initial = false)
    
    // Determine actual dark theme based on preference or system
    val actualDarkTheme = isDarkModePreferred ?: darkTheme
    
    val colorScheme = when {
        // Use dynamic colors if enabled and available (Android 12+)
        isDynamicColorEnabled && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (actualDarkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        
        // Use custom color if enabled and available
        useCustomColor && customPrimaryColor != null -> {
            val baseScheme = ThemeColorSchemes.getColorScheme(themeVariant, actualDarkTheme)
            customPrimaryColor.toColor()?.let { customColor ->
                ThemeColorSchemes.createCustomColorScheme(baseScheme, customColor)
            } ?: baseScheme
        }
        
        // Use theme variant
        else -> ThemeColorSchemes.getColorScheme(themeVariant, actualDarkTheme)
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !actualDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Simple theme variant for testing without preferences
@Composable
fun OCRMangaThemePreview(
    themeVariant: ThemeVariant = ThemeVariant.DEFAULT_PURPLE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = ThemeColorSchemes.getColorScheme(themeVariant, darkTheme)
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}