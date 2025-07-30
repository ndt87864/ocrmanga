# OCR Manga Theme System Documentation

## Overview
The OCR Manga app now features a comprehensive Material 3 theme system that provides modern, beautiful, and customizable themes following Android 16 design guidelines.

## Features

### 🎨 Multiple Theme Variants
- **Purple** (Default) - Classic purple theme
- **Blue** - Ocean-inspired blue theme  
- **Green** - Nature-inspired green theme
- **Orange** - Warm orange theme
- **Red** - Bold red theme

### 🌓 Dark/Light Mode Support
- Manual dark/light mode toggle
- System-aware theme switching
- Proper contrast ratios for accessibility

### 🎯 Dynamic Colors (Android 12+)
- Material You dynamic color extraction
- Automatic color adaptation from wallpaper
- Seamless integration with system themes

### 🖌️ Custom Color Support
- User-defined primary colors
- 16 preset color options
- Real-time theme preview

### 📱 Material 3 Compliance
- Complete Material 3 design tokens
- Modern typography scale
- Elevated surfaces and containers
- Proper color roles and semantics

## Implementation Details

### Architecture
```
ui/theme/
├── Color.kt - MaterialColors definitions
├── ColorSchemes.kt - Complete light/dark schemes
├── Theme.kt - Main theme composition
├── ThemePreferences.kt - DataStore preferences
├── ThemePreferences.kt - User settings storage
└── Type.kt - Material 3 typography

ui/screens/settings/
├── ThemeSettingsScreen.kt - Theme customization UI
└── ThemeSettingsViewModel.kt - State management

ui/components/
└── ModernComponents.kt - Material 3 components
```

### Theme Selection Logic
1. Check if dynamic colors are enabled (Android 12+)
2. Check if custom colors are enabled
3. Fall back to selected theme variant
4. Apply dark/light mode preference

### Color System
Each theme variant includes:
- Primary, Secondary, Tertiary color roles
- Surface, Background, Error colors
- On-color variants for contrast
- Container colors for elevated surfaces
- Outline and inverse colors

### Storage
User preferences are persisted using:
- DataStore Preferences (modern replacement for SharedPreferences)
- Reactive Flow-based state management
- Type-safe preference keys

## Usage Examples

### Basic Theme Application
```kotlin
@Composable
fun MyApp() {
    OCRMangaTheme {
        // Your app content
    }
}
```

### Preview with Specific Theme
```kotlin
@Preview
@Composable
fun PreviewBlueTheme() {
    OCRMangaThemePreview(
        themeVariant = ThemeVariant.BLUE,
        darkTheme = false
    ) {
        MyScreen()
    }
}
```

### Modern Components
```kotlin
ModernCard {
    SectionHeader(
        title = "Modern Card",
        subtitle = "With Material 3 styling"
    )
}

ModernIconButton(
    onClick = { },
    icon = Icons.Default.Settings,
    contentDescription = "Settings"
)
```

## Testing
- Unit tests for theme logic in `ThemeSystemTest.kt`
- Comprehensive preview system in `ThemeShowcase.kt` 
- Visual regression testing support

## Benefits

### For Users
- Personalized visual experience
- Accessibility-friendly themes
- Consistent with Android 16 design language
- Battery-friendly dark mode

### For Developers  
- Type-safe theme system
- Easy to extend with new variants
- Reactive state management
- Clean separation of concerns

## Future Enhancements
- [ ] Theme scheduling (auto dark mode by time)
- [ ] Accent color extraction from manga covers
- [ ] High contrast accessibility themes
- [ ] Custom font support
- [ ] Theme sharing between devices

## Migration Guide
The new theme system is backward compatible. Existing screens will automatically benefit from:
- Improved color contrast
- Modern Material 3 styling
- Dark mode support
- Dynamic color adaptation

No breaking changes to existing UI code.