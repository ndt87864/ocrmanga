package com.example.ocrmanga.ui.screens.view

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TranslationEditor(
    dragBlocks: List<DragBlockState>,
    selectedIndex: Int?,
    onDragBlocksChange: (List<DragBlockState>) -> Unit,
    onSelectedIndexChange: (Int?) -> Unit,
    onSave: () -> Unit
) {
    // --- STATE MANAGEMENT ---
    val isBlockSelected = selectedIndex != null
    var currentPage by remember { mutableStateOf(0) }
    val totalPages = 4 // 4 trang: Lưu+Hình dạng, Sửa+Xóa, Xoay, Màu sắc

    // Hoist state variables to the top level to prevent them from resetting on page change
    var showShapeMenu by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(0) }
    var resizeDropdownExpanded by remember { mutableStateOf(false) }
    var showEditBlockDialog by remember { mutableStateOf(false) }
    var isRotatingClockwise by remember { mutableStateOf(false) }
    var isRotatingCounterClockwise by remember { mutableStateOf(false) }
    
    // State cho màu sắc
    var showOverlayColorPicker by remember { mutableStateOf(false) }
    var showTextColorPicker by remember { mutableStateOf(false) }

    // --- EFFECTS ---
    // Hoist LaunchedEffects to the top level so they are always active
    val rotationSpeed = 2f
    val rotationInterval = 5L
    LaunchedEffect(isRotatingClockwise, selectedIndex) {
        while (isRotatingClockwise && selectedIndex != null && isBlockSelected) {
            selectedIndex.let { idx ->
                if (idx < dragBlocks.size) {
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        val newRot = (old.rotation + rotationSpeed) % 360f
                        list[idx] = old.copy(rotation = newRot)
                    })
                }
            }
            delay(rotationInterval)
        }
    }

    LaunchedEffect(isRotatingCounterClockwise, selectedIndex) {
        while (isRotatingCounterClockwise && selectedIndex != null && isBlockSelected) {
            selectedIndex.let { idx ->
                if (idx < dragBlocks.size) {
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        val newRot = (old.rotation - rotationSpeed) % 360f
                        list[idx] = old.copy(rotation = newRot)
                    })
                }
            }
            delay(rotationInterval)
        }
    }

    // --- UI ---
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF0F0F0))
            .padding(8.dp)
    ) {
        val shapeLabels = listOf("Hình chữ nhật", "Hình oval")
        val shapeIcons = listOf(Icons.Default.CropSquare, Icons.Default.Circle)
        val resizeOptions = listOf("Tất cả", "Chiều cao", "Chiều rộng")

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Nút chuyển trang trái
            IconButton(
                onClick = { currentPage = (currentPage - 1).coerceAtLeast(0) },
                enabled = currentPage > 0
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = "Trang trước"
                )
            }

            // Row chứa các công cụ theo từng trang
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                when (currentPage) {
                    // --- TRANG 1: LƯU, HÌNH DẠNG, KÍCH THƯỚC ---
                    0 -> {
                        IconButton(onClick = onSave) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = "Lưu và chuyển về chế độ xem",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Box {
                            IconButton(onClick = { showShapeMenu = true }) {
                                Icon(
                                    imageVector = shapeIcons[selectedIndex?.let { dragBlocks[it].block.shapeType } ?: 0],
                                    contentDescription = "Chọn hình dạng bôi trắng",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = showShapeMenu,
                                onDismissRequest = { showShapeMenu = false }
                            ) {
                                shapeLabels.forEachIndexed { index, label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = { Icon(shapeIcons[index], null) },
                                        onClick = {
                                            selectedIndex?.let { idx ->
                                                val updatedBlocks = dragBlocks.toMutableList()
                                                val currentBlock = updatedBlocks[idx]
                                                val newShapeType = index
                                                
                                                // Tính lại font size cho shape mới
                                                val bounds = currentBlock.block.bounds
                                                val width = bounds.width().toFloat()
                                                val height = bounds.height().toFloat()
                                                val newFontSize = calculateOptimalFontSize(
                                                    text = currentBlock.block.text,
                                                    width = width,
                                                    height = height,
                                                    minFontSize = 12f,
                                                    shapeType = newShapeType
                                                )
                                                
                                                updatedBlocks[idx] = currentBlock.copy(
                                                    block = currentBlock.block.copy(shapeType = newShapeType),
                                                    fontSize = newFontSize
                                                )
                                                onDragBlocksChange(updatedBlocks)
                                            }
                                            showShapeMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                selectedIndex?.let { idx ->
                                    onDragBlocksChange(dragBlocks.toMutableList().also {
                                        val old = it[idx]
                                        val b = old.block
                                        val bounds = android.graphics.Rect(b.bounds)
                                        when (resizeMode) {
                                            0 -> bounds.inset(-10, -10)
                                            1 -> { bounds.top -= 10; bounds.bottom += 10 }
                                            2 -> { bounds.left -= 10; bounds.right += 10 }
                                        }
                                        it[idx] = old.copy(block = b.copy(bounds = bounds))
                                    })
                                }
                            },
                            enabled = isBlockSelected
                        ) { Icon(Icons.Default.AddBox, "Tăng kích thước") }

                        Box(modifier = Modifier.size(40.dp)) {
                            OutlinedButton(
                                onClick = { resizeDropdownExpanded = true },
                                modifier = Modifier.size(40.dp),
                                contentPadding = PaddingValues(0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            ) {
                                Text(resizeOptions[resizeMode], style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(
                                expanded = resizeDropdownExpanded,
                                onDismissRequest = { resizeDropdownExpanded = false }
                            ) {
                                resizeOptions.forEachIndexed { index, label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { resizeMode = index; resizeDropdownExpanded = false }
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                selectedIndex?.let { idx ->
                                    onDragBlocksChange(dragBlocks.toMutableList().also {
                                        val old = it[idx]
                                        val b = old.block
                                        val bounds = android.graphics.Rect(b.bounds)
                                        when (resizeMode) {
                                            0 -> bounds.inset(10, 10)
                                            1 -> { bounds.top += 10; bounds.bottom -= 10 }
                                            2 -> {
                                                val shrinkAmount = 10
                                                if (bounds.width() > 2 * shrinkAmount) {
                                                    bounds.left += shrinkAmount
                                                    bounds.right -= shrinkAmount
                                                }
                                            }
                                        }
                                        it[idx] = old.copy(block = b.copy(bounds = bounds))
                                    })
                                }
                            },
                            enabled = isBlockSelected
                        ) { Icon(Icons.Default.IndeterminateCheckBox, "Giảm kích thước") }
                    }

                    // --- TRANG 2: XÓA, SỬA, CỠ CHỮ ---
                    1 -> {
                        IconButton(
                            onClick = {
                                selectedIndex?.let { idx ->
                                    onDragBlocksChange(dragBlocks.toMutableList().also { it.removeAt(idx) })
                                    onSelectedIndexChange(dragBlocks.indices.minOrNull()?.takeIf { dragBlocks.isNotEmpty() })
                                }
                            },
                            enabled = isBlockSelected
                        ) {
                            Icon(Icons.Default.Delete, "Xóa vùng đã chọn", tint = if (isBlockSelected) Color.Red else Color.Gray)
                        }

                        IconButton(
                            onClick = { if (isBlockSelected) showEditBlockDialog = true },
                            enabled = isBlockSelected
                        ) {
                            Icon(Icons.Default.Edit, "Sửa bản dịch", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                        }

                        IconButton(
                            onClick = {
                                selectedIndex?.let { idx ->
                                    onDragBlocksChange(dragBlocks.toMutableList().also {
                                        val old = it[idx]
                                        val newFont = (old.fontSize ?: 16f) + 2f
                                        it[idx] = old.copy(fontSize = newFont)
                                    })
                                }
                            },
                            enabled = isBlockSelected
                        ) { Icon(Icons.Default.TextIncrease, "Tăng cỡ chữ") }

                        IconButton(
                            onClick = {
                                selectedIndex?.let { idx ->
                                    onDragBlocksChange(dragBlocks.toMutableList().also {
                                        val old = it[idx]
                                        val newFont = (old.fontSize ?: 16f) - 2f
                                        it[idx] = old.copy(fontSize = newFont.coerceAtLeast(8f))
                                    })
                                }
                            },
                            enabled = isBlockSelected
                        ) { Icon(Icons.Default.TextDecrease, "Giảm cỡ chữ") }
                    }

                    // --- TRANG 3: XOAY ---
                    2 -> {
                        Box(
                            modifier = Modifier.size(40.dp).pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (isBlockSelected) { isRotatingClockwise = true; waitForUpOrCancellation(); isRotatingClockwise = false }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.RotateRight, "Xoay theo chiều kim đồng hồ", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                            if (isBlockSelected) {
                                val rot = selectedIndex?.let { dragBlocks[it].rotation } ?: 0f
                                Text("${rot.toInt()}°", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }

                        Box(
                            modifier = Modifier.size(40.dp).pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (isBlockSelected) { isRotatingCounterClockwise = true; waitForUpOrCancellation(); isRotatingCounterClockwise = false }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.RotateLeft, "Xoay ngược chiều kim đồng hồ", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                            if (isBlockSelected) {
                                val rot = selectedIndex?.let { dragBlocks[it].rotation } ?: 0f
                                Text("${rot.toInt()}°", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }

                    // --- TRANG 4: MÀU SẮC ---
                    3 -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Nút chọn màu overlay
                            IconButton(
                                onClick = { if (isBlockSelected) showOverlayColorPicker = true },
                                enabled = isBlockSelected
                            ) {
                                val currentOverlayColor = selectedIndex?.let { dragBlocks[it].whiteoutColor } ?: Color.White
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(currentOverlayColor, CircleShape)
                                        .border(1.dp, Color.Gray, CircleShape)
                                )
                            }

                            // Nút chọn màu text
                            IconButton(
                                onClick = { if (isBlockSelected) showTextColorPicker = true },
                                enabled = isBlockSelected
                            ) {
                                val currentTextColor = selectedIndex?.let { dragBlocks[it].textColor } ?: Color.Black
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(currentTextColor, CircleShape)
                                        .border(1.dp, Color.Gray, CircleShape)
                                )
                            }

                            // Nút reset màu về mặc định
                            IconButton(
                                onClick = {
                                    selectedIndex?.let { idx ->
                                        onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                            val old = list[idx]
                                            list[idx] = old.copy(
                                                whiteoutColor = null,
                                                textColor = null,
                                                overlayAlpha = 1.0f,
                                                textBoldness = 1.0f,
                                                overlaySaturation = 1.0f,
                                                textSaturation = 1.0f
                                            )
                                        })
                                    }
                                },
                                enabled = isBlockSelected
                            ) {
                                Icon(Icons.Default.Refresh, "Reset màu mặc định", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                            }
                        }
                    }
                }
            }

            // Nút chuyển trang phải
            IconButton(
                onClick = { currentPage = (currentPage + 1).coerceAtMost(totalPages - 1) },
                enabled = currentPage < totalPages - 1
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = "Trang sau"
                )
            }
        }

        // Dialog sửa bản dịch, đặt ở đây để không bị ảnh hưởng bởi việc chuyển trang
        if (showEditBlockDialog && isBlockSelected && selectedIndex != null) {
            val idx = selectedIndex
            val block = dragBlocks[idx].block
            val parts = remember(block.text) { block.text.split("\n") }
            var editedParts by remember(block.text) { mutableStateOf(parts.toMutableList()) }
            AlertDialog(
                onDismissRequest = { showEditBlockDialog = false },
                title = { Text("Sửa bản dịch") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        editedParts.forEachIndexed { i, part ->
                            OutlinedTextField(
                                value = part,
                                onValueChange = { newText ->
                                    editedParts = editedParts.toMutableList().also { it[i] = newText }
                                },
                                label = { Text("Phần ${i + 1}") },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { editedParts = editedParts.toMutableList().also { it.add("") } },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Thêm phần")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val nonBlankParts = editedParts.map { it.trim() }.filter { it.isNotEmpty() }
                        if (nonBlankParts.isNotEmpty()) {
                            onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                val old = list[idx]
                                val newBlocks = nonBlankParts.map { part -> old.copy(block = old.block.copy(text = part)) }
                                list.removeAt(idx)
                                list.addAll(idx, newBlocks)
                            })
                        }
                        showEditBlockDialog = false
                    }) { Text("Lưu") }
                },
                dismissButton = {
                    TextButton(onClick = { showEditBlockDialog = false }) { Text("Hủy") }
                }
            )
        }

        // Dialog chọn màu overlay
        if (showOverlayColorPicker && isBlockSelected && selectedIndex != null) {
            val idx = selectedIndex
            val currentColor = dragBlocks[idx].whiteoutColor ?: Color.White
            val currentAlpha = dragBlocks[idx].overlayAlpha
            val currentSaturation = dragBlocks[idx].overlaySaturation
            ColorPickerDialog(
                title = "Chọn màu nền overlay",
                initialColor = currentColor,
                initialAlpha = currentAlpha,
                initialSaturation = currentSaturation,
                isOverlayDialog = true,
                onColorSelected = { color ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(whiteoutColor = color)
                    })
                    showOverlayColorPicker = false
                },
                onAlphaChanged = { alpha ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(overlayAlpha = alpha)
                    })
                },
                onSaturationChanged = { saturation ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(overlaySaturation = saturation)
                    })
                },
                onDismiss = { showOverlayColorPicker = false }
            )
        }

        // Dialog chọn màu text
        if (showTextColorPicker && isBlockSelected && selectedIndex != null) {
            val idx = selectedIndex
            val currentColor = dragBlocks[idx].textColor ?: Color.Black
            val currentBoldness = dragBlocks[idx].textBoldness
            val currentSaturation = dragBlocks[idx].textSaturation
            ColorPickerDialog(
                title = "Chọn màu chữ",
                initialColor = currentColor,
                initialBoldness = currentBoldness,
                initialSaturation = currentSaturation,
                isOverlayDialog = false,
                onColorSelected = { color ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textColor = color)
                    })
                    showTextColorPicker = false
                },
                onBoldnessChanged = { boldness ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textBoldness = boldness)
                    })
                },
                onSaturationChanged = { saturation ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textSaturation = saturation)
                    })
                },
                onDismiss = { showTextColorPicker = false }
            )
        }
    }
}

@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: Color,
    initialAlpha: Float = 1.0f,
    initialBoldness: Float = 1.0f,
    initialSaturation: Float = 1.0f,
    isOverlayDialog: Boolean = false, // true nếu là dialog chọn màu overlay
    onColorSelected: (Color) -> Unit,
    onAlphaChanged: ((Float) -> Unit)? = null,
    onBoldnessChanged: ((Float) -> Unit)? = null,
    onSaturationChanged: ((Float) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableStateOf(initialColor) }
    var currentAlpha by remember { mutableStateOf(initialAlpha) }
    var currentBoldness by remember { mutableStateOf(initialBoldness) }
    var currentSaturation by remember { mutableStateOf(initialSaturation) }
    
    // Các màu preset phổ biến
    val presetColors = listOf(
        Color.White, Color.Black, Color.Red, Color.Green, Color.Blue,
        Color.Yellow, Color.Cyan, Color.Magenta, Color.Gray,
        Color(0xFFFFE0B2), Color(0xFFE1F5FE), Color(0xFFF3E5F5),
        Color(0xFFE8F5E8), Color(0xFFFFF3E0), Color(0xFFE3F2FD)
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Hiển thị màu hiện tại với preview thời gian thực
                Text("Màu hiện tại:", style = MaterialTheme.typography.bodyMedium)
                
                // Tính toán màu preview với saturation
                val previewColor = remember(selectedColor, currentSaturation) {
                    if (currentSaturation != 1.0f) {
                        // Chuyển đổi sang HSV để điều chỉnh saturation
                        val red = selectedColor.red
                        val green = selectedColor.green  
                        val blue = selectedColor.blue
                        
                        // Tính HSV đơn giản
                        val max = maxOf(red, green, blue)
                        val min = minOf(red, green, blue)
                        val delta = max - min
                        
                        val saturation = if (max == 0f) 0f else delta / max
                        val newSaturation = saturation * currentSaturation
                        
                        // Áp dụng saturation mới
                        val factor = if (saturation == 0f) 1f else newSaturation / saturation
                        val newRed = min + (red - min) * factor
                        val newGreen = min + (green - min) * factor
                        val newBlue = min + (blue - min) * factor
                        
                        Color(newRed.coerceIn(0f, 1f), newGreen.coerceIn(0f, 1f), newBlue.coerceIn(0f, 1f))
                    } else {
                        selectedColor
                    }
                }
                
                val displayColor = if (isOverlayDialog) previewColor.copy(alpha = currentAlpha) else previewColor
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Preview màu gốc
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("Gốc", style = MaterialTheme.typography.bodySmall)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (isOverlayDialog) selectedColor.copy(alpha = 1f) else selectedColor,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                        )
                    }
                    
                    // Preview màu đã chỉnh sửa
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("Preview", style = MaterialTheme.typography.bodySmall)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(displayColor, RoundedCornerShape(8.dp))
                                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Grid chọn màu
                Text("Chọn màu:", style = MaterialTheme.typography.bodyMedium)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(120.dp)
                ) {
                    items(presetColors) { color ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(color, CircleShape)
                                .border(
                                    width = if (color == selectedColor) 3.dp else 1.dp,
                                    color = if (color == selectedColor) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = color }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Slider độ trong suốt cho overlay
                if (isOverlayDialog && onAlphaChanged != null) {
                    Text("Độ trong suốt: ${(currentAlpha * 100).toInt()}%", 
                         style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = currentAlpha,
                        onValueChange = { newAlpha ->
                            currentAlpha = newAlpha
                            onAlphaChanged(newAlpha)
                        },
                        valueRange = 0.1f..1.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Slider độ đậm cho text
                if (!isOverlayDialog && onBoldnessChanged != null) {
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
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Slider độ bão hòa
                if (onSaturationChanged != null) {
                    Text("Độ bão hòa: ${(currentSaturation * 100).toInt()}%", 
                         style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = currentSaturation,
                        onValueChange = { newSaturation ->
                            currentSaturation = newSaturation
                            onSaturationChanged(newSaturation)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(selectedColor) }) {
                Text("Chọn")
            }
        },
        dismissButton = {       
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}