package com.example.ocrmanga.ui.screens.view

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp

@Composable
fun ScanlineOverlay(
    modifier: Modifier = Modifier,
    progress: Float? = null, // if null -> indefinite animation
    reversed: Boolean = false,
    color: Color = Color.White.copy(alpha = 0.18f)
) {
    val infinite = rememberInfiniteTransition()
    val anim by if (progress == null) {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart)
        )
    } else {
        val state = remember { Animatable(progress) }
        LaunchedEffect(progress) { state.snapTo(progress) }
        derivedStateOf { state.value }
    }

    // use localAnim as float 0..1
    val localAnim = progress ?: anim

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val barH = (h * 0.12f).coerceAtLeast(24f)
            val y = if (!reversed) (localAnim * (h + barH) - barH) else ((1f - localAnim) * (h + barH) - barH)

            val gradient = Brush.verticalGradient(
                colors = listOf(Color.Transparent, color, Color.Transparent),
                startY = y - barH,
                endY = y + barH
            )

            // Use DrawScope.drawRect with brush to avoid Canvas.drawRect signature mismatch
                drawRect(
                    brush = gradient,
                    topLeft = Offset(0f, y - barH),
                    size = Size(w, barH * 2f)
                )
        }
    }
}
