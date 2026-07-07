package com.example.ocrmanga.ui.screens.view

import androidx.compose.foundation.BorderStroke
import com.example.ocrmanga.utils.AppLogger as Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.fontResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ocrmanga.R
import com.example.ocrmanga.ui.components.tutorialTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: Color,
    initialAlpha: Float = 1.0f,
    initialBoldness: Float = 1.0f,
    initialSaturation: Float = 1.0f,
    initialThickness: Float = 0.0f,
    maxThickness: Float = 20.0f,
    isOverlayDialog: Boolean = false,
    supportGradient: Boolean = false,
    initialGradientColors: List<Color> = listOf(Color.Black, Color.White),
    initialGradientOffsets: List<Float> = listOf(0f, 1f),
    initialGradientType: Int = 0,
    onColorSelected: (Color) -> Unit,
    onGradientSelected: ((List<Color>, List<Float>, Int) -> Unit)? = null,
    onAlphaChanged: ((Float) -> Unit)? = null,
    onBoldnessChanged: ((Float) -> Unit)? = null,
    onSaturationChanged: ((Float) -> Unit)? = null,
    onThicknessChanged: ((Float) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableStateOf(initialColor) }
    var currentAlpha by remember { mutableStateOf(initialAlpha) }
    var currentBoldness by remember { mutableStateOf(initialBoldness) }
    var currentSaturation by remember { mutableStateOf(initialSaturation) }
    var currentThickness by remember { mutableStateOf(initialThickness) }

    // Gradient state
    var isGradientMode by remember { 
        mutableStateOf(supportGradient && onGradientSelected != null && initialGradientColors.size >= 2 && initialGradientColors != listOf(Color.Black, Color.White)) 
    }
    var colors by remember { mutableStateOf(initialGradientColors.toMutableList()) }
    var offsets by remember { mutableStateOf(initialGradientOffsets.toMutableList()) }
    var gradientType by remember { mutableStateOf(initialGradientType) }
    var editingColorIndex by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, modifier = Modifier.weight(1f))
                if (supportGradient && onGradientSelected != null) {
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                            .padding(2.dp)
                    ) {
                        Surface(
                            onClick = { isGradientMode = false },
                            color = if (!isGradientMode) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("Đơn", 
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (!isGradientMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            onClick = { isGradientMode = true },
                            color = if (isGradientMode) MaterialTheme.colorScheme.secondary else Color.Transparent,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("Gradient", 
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isGradientMode) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                // --- PREVIEW ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val previewText = "AaBbCcDd"
                    OutlinedTextPreview(
                        text = previewText,
                        textColor = if (!isOverlayDialog && onThicknessChanged == null) selectedColor else Color.Black,
                        borderColor = if (!isOverlayDialog && onThicknessChanged != null) selectedColor.copy(alpha = currentAlpha) else Color.Transparent,
                        borderThickness = if (!isOverlayDialog && onThicknessChanged != null) currentThickness else 0f,
                        isGradientMode = isGradientMode,
                        gradientColors = colors,
                        gradientOffsets = offsets,
                        gradientType = gradientType
                    )
                }

                if (!isGradientMode) {
                    // --- MÀU ĐƠN UI ---
                    com.example.ocrmanga.ui.components.AdvancedColorPicker(
                        selectedColor = selectedColor,
                        onColorSelected = { color ->
                            color?.let { 
                                selectedColor = if (isOverlayDialog) {
                                    it.copy(alpha = currentAlpha)
                                } else {
                                    it.copy(alpha = 1f)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        showAlphaSlider = false,
                        showPredefinedColors = !isOverlayDialog
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    // --- GRADIENT UI ---
                    Spacer(Modifier.height(8.dp))
                    Text("Hướng Gradient:", style = MaterialTheme.typography.titleSmall)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        val types = listOf("Dọc", "Ngang", "Chéo \u2198", "Chéo \u2199")
                        val icons = listOf(Icons.Default.VerticalAlignBottom, Icons.Default.AlignHorizontalLeft, Icons.Default.ScreenRotation, Icons.Default.ScreenRotation)
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (i in 0..1) {
                                FilterChip(
                                    selected = gradientType == i,
                                    onClick = { gradientType = i },
                                    label = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icons[i], null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(types[i], fontSize = 11.sp)
                                        }
                                    }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (i in 2..3) {
                                FilterChip(
                                    selected = gradientType == i,
                                    onClick = { gradientType = i },
                                    label = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icons[i], null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(types[i], fontSize = 11.sp)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    Text("Các mốc màu:", style = MaterialTheme.typography.titleSmall)
                    colors.forEachIndexed { index, color ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(color, CircleShape)
                                    .border(1.dp, Color.Gray, CircleShape)
                                    .clickable { editingColorIndex = index }
                            )
                            Spacer(Modifier.width(8.dp))
                            Slider(
                                value = offsets[index],
                                onValueChange = { 
                                    val newOffsets = offsets.toMutableList()
                                    newOffsets[index] = it
                                    offsets = newOffsets
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                if (colors.size > 2) {
                                    colors = colors.toMutableList().also { it.removeAt(index) }
                                    offsets = offsets.toMutableList().also { it.removeAt(index) }
                                }
                            }) {
                                Icon(Icons.Default.Delete, null, tint = Color.Red)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            colors = colors.toMutableList().also { it.add(Color.Gray) }
                            offsets = offsets.toMutableList().also { it.add(1f) }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text("Thêm màu")
                    }
                }

                // --- SHARED ADJUSTMENTS (Visible in both modes) ---
                if (isOverlayDialog && onAlphaChanged != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Độ trong suốt overlay: ${(currentAlpha * 100).toInt()}%", 
                         style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = currentAlpha,
                        onValueChange = { newAlpha ->
                            currentAlpha = newAlpha
                            selectedColor = selectedColor.copy(alpha = newAlpha)
                            onAlphaChanged(newAlpha)
                        },
                        valueRange = 0f..1.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                if (!isOverlayDialog && onBoldnessChanged != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Độ đậm chữ: ${(currentBoldness * 100).toInt()}%", 
                         style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = currentBoldness,
                        onValueChange = { newBoldness ->
                            currentBoldness = newBoldness
                            onBoldnessChanged(newBoldness)
                        },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                if (onSaturationChanged != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Độ bão hòa màu: ${(currentSaturation * 100).toInt()}%", 
                         style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = currentSaturation,
                        onValueChange = { newSaturation ->
                            currentSaturation = newSaturation
                            onSaturationChanged(newSaturation)
                        },
                        valueRange = 0f..2f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!isOverlayDialog && onAlphaChanged != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Độ đậm viền: ${(currentAlpha * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = currentAlpha,
                        onValueChange = { newAlpha ->
                            currentAlpha = newAlpha
                            onAlphaChanged(newAlpha)
                        },
                        valueRange = 0f..1.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!isOverlayDialog && onThicknessChanged != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Độ dày viền: ${"%.1f".format(currentThickness)}",
                        style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = currentThickness,
                        onValueChange = { newThickness ->
                            currentThickness = newThickness
                            onThicknessChanged(newThickness)
                        },
                        valueRange = 0f..maxThickness,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { 
                if (isGradientMode && onGradientSelected != null) {
                    onGradientSelected(colors, offsets, gradientType)
                } else {
                    val finalColor = if (isOverlayDialog) selectedColor.copy(alpha = currentAlpha) else selectedColor
                    onColorSelected(finalColor)
                }
                onDismiss()
            }) {
                Text("Áp dụng")
            }
        },
        dismissButton = {       
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Hủy")
            }
        }
    )

    if (editingColorIndex != null) {
        val idx = editingColorIndex!!
        ColorPickerDialog(
            title = "Chọn màu cho mốc ${idx + 1}",
            initialColor = colors[idx],
            onColorSelected = { newColor ->
                val newColors = colors.toMutableList()
                newColors[idx] = newColor
                colors = newColors
                editingColorIndex = null
            },
            onDismiss = { editingColorIndex = null }
        )
    }
}

@Composable
fun OutlinedTextPreview(
    text: String,
    textColor: Color,
    borderColor: Color,
    borderThickness: Float,
    isGradientMode: Boolean = false,
    gradientColors: List<Color> = emptyList(),
    gradientOffsets: List<Float> = emptyList(),
    gradientType: Int = 0
) {
    // Draw a more accurate preview using native Canvas so we can render:
    // shadow (blurred) -> stroke (outline) -> fill (text). This makes the
    // shadow appear outside the stroke and around rounded glyph corners.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textStyle = MaterialTheme.typography.headlineLarge
    val textSizePx = with(density) { 48.sp.toPx() }

    Canvas(modifier = Modifier
        .fillMaxWidth()
        .height(100.dp)) {
        val native = drawContext.canvas.nativeCanvas
        // center coordinates
        val cx = size.width / 2f
        val cy = size.height / 2f

        // Android Paints
        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            color = textColor.toArgb()
            style = android.graphics.Paint.Style.FILL
            textAlign = android.graphics.Paint.Align.CENTER
        }

        if (isGradientMode && gradientColors.size >= 2) {
            val colorsArr = gradientColors.map { it.toArgb() }.toIntArray()
            val posArr = gradientOffsets.toFloatArray()
            val fm = fillPaint.fontMetrics
            val top = cy + fm.ascent
            val bottom = cy + fm.descent
            val textWidth = fillPaint.measureText(text)
            
            fillPaint.shader = when (gradientType) {
                0 -> android.graphics.LinearGradient(cx, top, cx, bottom, colorsArr, posArr, android.graphics.Shader.TileMode.CLAMP)
                1 -> android.graphics.LinearGradient(cx - textWidth / 2, cy, cx + textWidth / 2, cy, colorsArr, posArr, android.graphics.Shader.TileMode.CLAMP)
                2 -> android.graphics.LinearGradient(cx - textWidth / 2, top, cx + textWidth / 2, bottom, colorsArr, posArr, android.graphics.Shader.TileMode.CLAMP)
                3 -> android.graphics.LinearGradient(cx + textWidth / 2, top, cx - textWidth / 2, bottom, colorsArr, posArr, android.graphics.Shader.TileMode.CLAMP)
                else -> null
            }
        }

        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            color = borderColor.toArgb()
            style = android.graphics.Paint.Style.STROKE
            // Stroke width: try to use the provided value directly. If the caller
            // passed a shadow radius (for shadow dialog) this will also work as a
            // visible outline for preview. Use round joins/caps so rounded glyph
            // corners look smooth.
            strokeWidth = borderThickness
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            // Use transparent fill so only the blurred shadow is visible
            color = android.graphics.Color.TRANSPARENT
            style = android.graphics.Paint.Style.FILL
            // Set a shadow layer. We choose blur radius proportional to borderThickness
            // so the same slider can preview both border-thickness and shadow-radius dialogs.
            setShadowLayer(borderThickness * 1.8f.coerceAtLeast(1f), 0f, 0f, borderColor.toArgb())
            textAlign = android.graphics.Paint.Align.CENTER
        }

        // Compute baseline so text is vertically centered
        val fm = fillPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val baseline = cy + textHeight / 2f - fm.descent

        // Draw shadow first (blurred, outside)
        native.save()
        // Draw using shadow paint (transparent fill + shadow layer)
        native.drawText(text, cx, baseline, shadowPaint)
        native.restore()

        // Draw stroke / outline
        if (borderThickness > 0f) {
            native.drawText(text, cx, baseline, strokePaint)
        }

        // Draw fill text on top
        native.drawText(text, cx, baseline, fillPaint)
    }
}
