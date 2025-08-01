# Screen Translation Feature

This document describes the new screen translation functionality added to OCR Manga.

## Overview

The screen translation feature allows users to translate text directly from their device screen in real-time, rather than just from uploaded images. This provides a seamless experience for translating content from any app, website, or screen content.

## Architecture

The screen translation feature consists of three main components:

### 1. ScreenCaptureService
- **Purpose**: Captures screenshots using Android's MediaProjection API
- **Location**: `app/src/main/java/com/example/ocrmanga/services/ScreenCaptureService.kt`
- **Key features**:
  - Runs as a foreground service for continuous screen capture
  - Uses VirtualDisplay and ImageReader for efficient screen capture
  - Processes captured images and sends them to the overlay service

### 2. OverlayTranslationService  
- **Purpose**: Displays translated text as an overlay on top of other apps
- **Location**: `app/src/main/java/com/example/ocrmanga/services/OverlayTranslationService.kt`
- **Key features**:
  - Creates system overlay windows using WindowManager
  - Uses Jetpack Compose for overlay UI
  - Integrates with existing TranslationRepository for OCR and translation
  - Displays translations with semi-transparent background

### 3. ScreenTranslationScreen
- **Purpose**: User interface for controlling screen translation
- **Location**: `app/src/main/java/com/example/ocrmanga/ui/screens/ScreenTranslationScreen.kt`
- **Key features**:
  - Permission management for overlay and screen capture
  - Start/stop controls for screen translation
  - User instructions and status display

## How It Works

1. **Permission Setup**: User grants overlay permission and screen capture permission
2. **Service Initialization**: ScreenCaptureService starts capturing screen content
3. **Image Processing**: Captured screens are processed using existing OCR pipeline
4. **Translation**: Recognized text is translated using the same translation services
5. **Overlay Display**: Translated text is displayed as floating overlay

## Integration Points

The screen translation feature reuses existing infrastructure:

- **TranslationRepository**: Same OCR and translation logic used for images
- **ML Kit Services**: Same text recognition and translation APIs
- **UI Theme**: Consistent styling with OCRMangaTheme
- **Navigation**: Integrated into main app navigation system

## Permissions Required

Added to AndroidManifest.xml:
- `SYSTEM_ALERT_WINDOW`: For overlay display
- `FOREGROUND_SERVICE`: For background screen capture
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Specific to media projection

## Usage Flow

1. User opens OCR Manga app
2. Navigates to "Screen Translation" from settings menu
3. Grants required permissions when prompted
4. Taps "Start Screen Translation"
5. Allows screen capture permission
6. Screen translation runs in background with overlay
7. User can stop translation anytime

## Technical Considerations

- **Performance**: Uses efficient screen capture with controlled frame rate
- **Battery**: Foreground service with user notification for transparency
- **Privacy**: User explicitly grants permissions and can stop anytime
- **Compatibility**: Works on Android 7.0+ with proper permission handling

## Future Enhancements

Potential improvements for the screen translation feature:

1. **Region Selection**: Allow users to select specific screen regions
2. **Language Detection**: Auto-detect source language from screen content
3. **Translation Caching**: Cache recent translations for better performance
4. **Customizable Overlay**: User-configurable overlay appearance
5. **Gesture Controls**: Floating action button for quick start/stop

## Testing

Basic instrumentation tests are provided in:
- `app/src/androidTest/java/com/example/ocrmanga/ScreenTranslationTest.kt`

These tests verify service constants and basic functionality.