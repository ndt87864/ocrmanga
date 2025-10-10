package com.example.ocrmanga.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable
fun AdvancedColorPicker(
    selectedColor: Color?,
    onColorSelected: (Color?) -> Unit,
    modifier: Modifier = Modifier,
    showAlphaSlider: Boolean = true, // Tham số để hiển thị/ẩn slider alpha
    showPredefinedColors: Boolean = true // Tham số để hiển thị/ẩn màu có sẵn
) {
    var hue by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(1f) }
    var lightness by remember { mutableStateOf(0.5f) }
    var alpha by remember { mutableStateOf(1f) }
    
    // Initialize values from selectedColor if provided
    LaunchedEffect(selectedColor) {
        selectedColor?.let { color ->
            val hsla = color.toHsla()
            hue = hsla[0]
            saturation = hsla[1]
            lightness = hsla[2]
            alpha = hsla[3]
        }
    }
    
    // Update color when any component changes
    // Nếu không hiển thị alpha slider, luôn dùng alpha từ selectedColor
    LaunchedEffect(hue, saturation, lightness, alpha, showAlphaSlider) {
        val finalAlpha = if (showAlphaSlider) alpha else (selectedColor?.alpha ?: 1f)
        val color = hslaToColor(hue, saturation, lightness, finalAlpha)
        onColorSelected(color)
    }
    
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Bảng màu tùy chỉnh",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        // Color wheel
        ColorWheel(
            selectedHue = hue,
            selectedSaturation = saturation,
            onColorSelected = { newHue, newSaturation ->
                hue = newHue
                saturation = newSaturation
            },
            modifier = Modifier.size(200.dp)
        )
        
        // Lightness slider
        Column {
            Text(
                text = "Độ sáng: ${(lightness * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = lightness,
                onValueChange = { lightness = it },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Alpha slider - chỉ hiển thị nếu showAlphaSlider = true
        if (showAlphaSlider) {
            Column {
                Text(
                    text = "Độ trong suốt: ${(alpha * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        // Color preview
        val currentColor = hslaToColor(hue, saturation, lightness, alpha)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(currentColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Màu đã chọn",
                    color = if (lightness > 0.5f) Color.Black else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // Predefined colors row for quick selection - chỉ hiển thị nếu showPredefinedColors = true
        if (showPredefinedColors) {
            Column {
                Text(
                    text = "Màu có sẵn",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val predefinedColors = listOf(
                        Color.White,       // Trắng
                        Color.Black,       // Đen
                        Color.Red,         // Đỏ
                        Color.Blue,        // Xanh dương
                        Color.Green,       // Xanh lá
                        Color.Yellow,      // Vàng
                        Color.Cyan,        // Xanh lơ
                        Color.Magenta,     // Hồng tím
                        Color(0xFF808080), // Xám
                        Color(0xFFFFA500), // Cam
                        Color(0xFF800080), // Tím
                        Color(0xFF8B4513), // Nâu
                        Color(0xFFFFC0CB), // Hồng nhạt
                        Color(0xFF87CEEB), // Xanh sky
                        Color(0xFF90EE90), // Xanh lá nhạt
                        Color(0xFFFFD700)  // Vàng gold
                    )
                    
                    predefinedColors.chunked(8).forEach { colorRow ->
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                colorRow.forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .clickable { 
                                                val hsla = color.toHsla()
                                                hue = hsla[0]
                                                saturation = hsla[1]
                                                lightness = hsla[2]
                                                alpha = hsla[3]
                                            }
                                            .border(
                                                width = if (currentColor.isSimilarTo(color)) 2.dp else 0.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape
                                            )
                                    )
                                }
                            }
                            if (colorRow != predefinedColors.chunked(8).last()) {
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorWheel(
    selectedHue: Float,
    selectedSaturation: Float,
    onColorSelected: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    
    Canvas(
        modifier = modifier
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change,_ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f
                    val offset = change.position - center
                    val distance = sqrt(offset.x * offset.x + offset.y * offset.y)
                    
                    if (distance <= radius) {
                        val angle = atan2(offset.y, offset.x)
                        val hue = ((angle * 180 / PI + 360) % 360).toFloat()
                        val saturation = (distance / radius).coerceIn(0f, 1f)
                        onColorSelected(hue, saturation)
                    }
                }
            }
    ) {
        drawColorWheel(size.width / 2f)
        
        // Draw selection indicator
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.width / 2f * selectedSaturation
        val angle = selectedHue * PI / 180
        val indicatorPosition = Offset(
            center.x + radius * cos(angle).toFloat(),
            center.y + radius * sin(angle).toFloat()
        )
        
        drawCircle(
            color = Color.White,
            radius = 8.dp.toPx(),
            center = indicatorPosition
        )
        drawCircle(
            color = Color.Black,
            radius = 6.dp.toPx(),
            center = indicatorPosition
        )
    }
}

private fun DrawScope.drawColorWheel(radius: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    
    for (angle in 0 until 360 step 2) {
        for (r in 0 until radius.toInt() step 2) {
            val hue = angle.toFloat()
            val saturation = r / radius
            val color = hslaToColor(hue, saturation, 0.5f, 1f)
            
            val x = center.x + r * cos(angle * PI / 180).toFloat()
            val y = center.y + r * sin(angle * PI / 180).toFloat()
            
            drawCircle(
                color = color,
                radius = 2.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

// Helper functions
private fun Color.toHsla(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val a = alpha
    
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
    
    return floatArrayOf(hue, saturation, lightness, a)
}

private fun hslaToColor(h: Float, s: Float, l: Float, a: Float): Color {
    val hue = h / 360f
    val saturation = s.coerceIn(0f, 1f)
    val lightness = l.coerceIn(0f, 1f)
    val alpha = a.coerceIn(0f, 1f)
    
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - abs((hue * 6f) % 2f - 1f))
    val m = lightness - c / 2f
    
    val (r, g, b) = when {
        hue < 1f/6f -> Triple(c, x, 0f)
        hue < 2f/6f -> Triple(x, c, 0f)
        hue < 3f/6f -> Triple(0f, c, x)
        hue < 4f/6f -> Triple(0f, x, c)
        hue < 5f/6f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
        alpha = alpha
    )
}

private fun Color.isSimilarTo(other: Color, threshold: Float = 0.1f): Boolean {
    val rDiff = abs(red - other.red)
    val gDiff = abs(green - other.green)
    val bDiff = abs(blue - other.blue)
    val aDiff = abs(alpha - other.alpha)
    
    return rDiff < threshold && gDiff < threshold && bDiff < threshold && aDiff < threshold
}

