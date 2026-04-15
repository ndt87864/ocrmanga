package com.example.ocrmanga.data.models

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color

data class PrecomputedRegion(
    val block: TextBlockInfo,
    val rect: Rect,
    val fontSize: Float,
    val rotation: Float,
    val overlayRotation: Float? = null,
    val whiteoutColor: Color? = null,
    val textColor: Color? = null,
    val overlayAlpha: Float = 1.0f,
    val textBoldness: Float = 1.0f,
    val overlaySaturation: Float = 1.0f,
    val textSaturation: Float = 1.0f,
    val lineSpacing: Float = 1.0f,
    val textBorderColor: Color? = null,
    val textBorderThickness: Float = 0.0f,
    val textBorderAlpha: Float = 1.0f,
    val textShadowColor: Color? = null,
    val textShadowAlpha: Float = 1.0f,
    val textShadowRadius: Float = 0f,
    val overlayInset: Float = 0f,
    val overlayInsetHorizontal: Float = 0f,
    val overlayInsetVertical: Float = 0f,
    val textGradientColors: List<Int>? = null,
    val textGradientOffsets: List<Float>? = null,
    val textGradientType: Int = 0,
    // Precomputed results
    val wrappedLines: List<String> = emptyList(),
    val finalFontSize: Float = fontSize,
    val finalOverlayColor: Color? = null,
    val finalTextColor: Color = Color.Black,
    val lineHeight: Float = 0f,
    val isOval: Boolean = false
)
