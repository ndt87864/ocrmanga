package com.example.ocrmanga.data.models

import android.graphics.Rect

data class ImageBlock(
    val blockId: Long,
    val imageId: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val overlayType: Int = 0,
    val overlayColor: Int? = null,
    val overlayBrightness: Float = 1.0f,
    val overlayAlpha: Float = 1.0f,
    val overlaySaturation: Float = 1.0f,
    val textColor: Int? = null,
    val textBrightness: Float = 1.0f,
    val textBoldness: Float = 1.0f,
    val textSaturation: Float = 1.0f,
    val borderColor: Int? = null,
    val borderBrightness: Float = 1.0f,
    val borderBoldness: Float = 1.0f,
    val borderThickness: Float = 0f,
    // Shadow properties
    val shadowColor: Int? = null,
    val shadowAlpha: Float = 1.0f,
    val shadowRadius: Float = 0f,
    val rotation: Float = 0f,
    val fontFamily: String = "mto_astro_city",
    val fontSize: Float = 12f
)
