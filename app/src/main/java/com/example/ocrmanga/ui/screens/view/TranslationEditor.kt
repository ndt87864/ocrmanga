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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.fontResource
import androidx.compose.ui.unit.dp
import com.example.ocrmanga.R
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
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Load fonts safely with remember to avoid reloading and potential crashes
    val fontOptions = remember {
        try {
            listOf(
                "SF Toontime B" to FontFamily(Font(R.font.sf_toontime_b)),
                "SF Toontime B Italic" to FontFamily(Font(R.font.sf_toontime_b_italic)),
                "SF Toontime Blotch Bold" to FontFamily(Font(R.font.sf_toontime_blotch_bold)),
                "SF Toontime Blotch Bold Italic" to FontFamily(Font(R.font.sf_toontime_blotch_bold_italic)),
                "SF Toontime Extended" to FontFamily(Font(R.font.sf_toontime_extended)),
                "SF Toontime Extended Italic" to FontFamily(Font(R.font.sf_toontime_extended_italic)),
                "SF Toontime Extended Bold" to FontFamily(Font(R.font.sf_toontime_extended_bold)),
                "SF Toontime Extended Bold Italic" to FontFamily(Font(R.font.sf_toontime_extended_bold_italic))
            )
        } catch (e: Exception) {
            // Fallback to default font if loading fails
            android.util.Log.e("TranslationEditor", "Failed to load fonts", e)
            listOf("Default" to FontFamily.Default)
        }
    }
    // --- STATE MANAGEMENT ---
    val isBlockSelected = selectedIndex != null
    var currentPage by remember { mutableStateOf(0) }
    val totalPages = 5 // 5 trang: Lưu+Hình dạng, Sửa+Xóa, Xoay, Màu sắc, Viền chữ

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
    
    // State cho viền chữ
    var showBorderColorPicker by remember { mutableStateOf(false) }

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
            // Nút chuyển trang trái (với cuộn vô hạn)
            IconButton(
                onClick = { 
                    currentPage = if (currentPage == 0) totalPages - 1 else currentPage - 1
                }
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
                                                    shapeType = newShapeType,
                                                    context = context,
                                                    fontFamilyName = currentBlock.block.fontFamily
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
                            onClick = {
                                if (isBlockSelected) {
                                    showEditBlockDialog = true
                                } else {
                                    // Thêm block mới và chuyển sang chế độ sửa luôn
                                    val newBlock = DragBlockState(
                                        block = com.example.ocrmanga.data.models.TextBlockInfo(
                                            text = "",
                                            bounds = android.graphics.Rect(100, 100, 400, 200),
                                            fontSize = 32f
                                        )
                                    )
                                    onDragBlocksChange(dragBlocks + newBlock)
                                    onSelectedIndexChange(dragBlocks.size)
                                    showEditBlockDialog = true
                                }
                            },
                            enabled = true
                        ) {
                            Icon(Icons.Default.Edit, "Sửa/Thêm bản dịch", tint = MaterialTheme.colorScheme.primary)
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
                            IconButton(onClick = { onSelectedIndexChange(selectedIndex?.let { maxOf(0, it - 1) } ?: 0) }) {
                                Icon(Icons.Default.ArrowBack, "Block trước")
                            }
                            IconButton(onClick = { onSelectedIndexChange(selectedIndex?.let { minOf(dragBlocks.size - 1, it + 1) } ?: 0) }) {
                                Icon(Icons.Default.ArrowForward, "Block tiếp theo")
                            }
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
                                                textSaturation = 1.0f,
                                                textBorderColor = null,
                                                textBorderThickness = 0.0f,
                                                textBorderAlpha = 1.0f
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
                    
                    // --- TRANG 5: VIỀN CHỮ ---
                    4 -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Nút chọn màu viền (mở dialog chỉnh màu + alpha + thickness)
                            IconButton(
                                onClick = { if (isBlockSelected) showBorderColorPicker = true },
                                enabled = isBlockSelected
                            ) {
                                val currentBorderColor = selectedIndex?.let { dragBlocks[it].textBorderColor } ?: Color.Black
                                val currentBorderAlpha = selectedIndex?.let { dragBlocks[it].textBorderAlpha } ?: 1f
                                val currentBorderThickness = selectedIndex?.let { dragBlocks[it].textBorderThickness } ?: 0f
                                val currentTextColor = selectedIndex?.let { dragBlocks[it].textColor } ?: Color.Black
                                val previewText = selectedIndex?.let { dragBlocks[it].block.text.takeIf { it.isNotBlank() }?.split("\n")?.firstOrNull() } ?: "AaBb"
                                // Preview text với viền
                                Box(
                                    modifier = Modifier
                                        .size(width = 48.dp, height = 32.dp)
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    OutlinedTextPreview(
                                        text = previewText,
                                        textColor = currentTextColor,
                                        borderColor = currentBorderColor.copy(alpha = currentBorderAlpha),
                                        borderThickness = currentBorderThickness
                                    )
                                }
                                Text(
                                    "Màu\nviền",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 26.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Nút chuyển trang phải (với cuộn vô hạn)
            IconButton(
                onClick = { 
                    currentPage = if (currentPage == totalPages - 1) 0 else currentPage + 1
                }
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
            var selectedFontName by remember { mutableStateOf(block.fontFamily) }
            var fontDropdownExpanded by remember { mutableStateOf(false) }
            val selectedFontFamily = fontOptions.find { it.first == selectedFontName }?.second ?: fontOptions.firstOrNull()?.second ?: FontFamily.Default
            AlertDialog(
                onDismissRequest = { showEditBlockDialog = false },
                title = { Text("Sửa/Thêm bản dịch") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        // Font selection dropdown
                        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            OutlinedButton(onClick = { fontDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Font: $selectedFontName")
                            }
                            DropdownMenu(expanded = fontDropdownExpanded, onDismissRequest = { fontDropdownExpanded = false }) {
                                fontOptions.forEach { (name, family) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            selectedFontName = name
                                            fontDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Existing translation parts
                        editedParts.forEachIndexed { i, part ->
                            OutlinedTextField(
                                value = part.replace("*", ""),
                                onValueChange = { newText ->
                                    editedParts = editedParts.toMutableList().also { it[i] = newText }
                                },
                                label = { Text("Phần dịch ${i + 1}") },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                        }

                        // Add new translation part
                        Button(
                            onClick = {
                                editedParts = editedParts.toMutableList().also { it.add("") }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Text("Thêm phần dịch mới")
                        }

                        Spacer(Modifier.height(8.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val nonBlankParts = editedParts.map { it.trim() }.filter { it.isNotEmpty() }
                        if (nonBlankParts.isNotEmpty()) {
                            android.util.Log.d("TranslationEditor", "Saving with font: $selectedFontName")
                            onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                val oldBlock = list[idx]
                                val newBlocks = nonBlankParts.mapIndexed { index, text ->
                                    oldBlock.copy(
                                        block = oldBlock.block.copy(
                                            text = text,
                                            fontFamily = selectedFontName
                                        )
                                    )
                                }
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
                // Truyền màu với alpha = 1.0 vì alpha sẽ được quản lý riêng
                initialColor = currentColor.copy(alpha = 1f),
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
                // Text không cần alpha, luôn set về 1.0 (không trong suốt)
                initialColor = currentColor.copy(alpha = 1f),
                initialAlpha = 1f, // Text luôn không trong suốt
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
        
        // Dialog chọn màu viền
        if (showBorderColorPicker && isBlockSelected && selectedIndex != null) {
            val idx = selectedIndex
            val currentColor = dragBlocks[idx].textBorderColor ?: Color.Black
            val currentAlpha = dragBlocks[idx].textBorderAlpha
            val currentThickness = dragBlocks[idx].textBorderThickness
            ColorPickerDialog(
                title = "Chọn màu viền chữ",
                initialColor = currentColor.copy(alpha = 1f),
                initialAlpha = currentAlpha,
                initialThickness = currentThickness,
                isOverlayDialog = false,
                onColorSelected = { color ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textBorderColor = color)
                    })
                    showBorderColorPicker = false
                },
                onAlphaChanged = { alpha ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textBorderAlpha = alpha)
                    })
                },
                onThicknessChanged = { thickness ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textBorderThickness = thickness)
                    })
                },
                onDismiss = { showBorderColorPicker = false }
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
    initialThickness: Float = 0.0f, // Thêm tham số này
    isOverlayDialog: Boolean = false,
    onColorSelected: (Color) -> Unit,
    onAlphaChanged: ((Float) -> Unit)? = null,
    onBoldnessChanged: ((Float) -> Unit)? = null,
    onSaturationChanged: ((Float) -> Unit)? = null,
    onThicknessChanged: ((Float) -> Unit)? = null, // Thêm callback này
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableStateOf(initialColor) }
    var currentAlpha by remember { mutableStateOf(initialAlpha) }
    var currentBoldness by remember { mutableStateOf(initialBoldness) }
    var currentSaturation by remember { mutableStateOf(initialSaturation) }
    var currentThickness by remember { mutableStateOf(initialThickness) } // Thêm state này

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                // --- PREVIEW TEXT THAY VÌ BOX MÀU ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Lấy text mẫu, màu chữ, màu viền, độ dày từ state hiện tại
                    val previewText = "AaBb"
                    if (!isOverlayDialog && onThicknessChanged != null) {
                        OutlinedTextPreview(
                            text = previewText,
                            textColor = Color.Black, // hoặc cho phép truyền vào
                            borderColor = selectedColor.copy(alpha = currentAlpha),
                            borderThickness = currentThickness
                        )
                    } else {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = selectedColor
                            )
                        )
                    }
                }

                // Sử dụng AdvancedColorPicker giống như trong ThemeSettingsScreen
                // Cấu hình theo loại dialog: overlay hoặc text
                com.example.ocrmanga.ui.components.AdvancedColorPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { color ->
                        color?.let { 
                            selectedColor = if (isOverlayDialog) {
                                // Giữ nguyên alpha hiện tại cho overlay
                                it.copy(alpha = currentAlpha)
                            } else {
                                // Text không cần alpha, luôn set về 1.0
                                it.copy(alpha = 1f)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    showAlphaSlider = false, // Ẩn slider alpha cho cả overlay và text
                    showPredefinedColors = !isOverlayDialog // Chỉ hiện màu có sẵn cho text
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Slider độ trong suốt cho overlay - quản lý riêng alpha
                if (isOverlayDialog && onAlphaChanged != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Độ trong suốt overlay: ${(currentAlpha * 100).toInt()}%", 
                         style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = currentAlpha,
                        onValueChange = { newAlpha ->
                            currentAlpha = newAlpha
                            // Cập nhật màu với alpha mới
                            selectedColor = selectedColor.copy(alpha = newAlpha)
                            onAlphaChanged(newAlpha)
                        },
                        valueRange = 0f..1.0f, // Đặt giá trị thấp nhất là 0% thay vì 10%
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Slider độ đậm cho text
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
                
                // Slider độ bão hòa
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

                // Slider độ đậm viền (alpha)
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

                // Slider độ dày viền
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
                        valueRange = 0f..5f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { 
                // Sử dụng màu với alpha hiện tại cho overlay
                val finalColor = if (isOverlayDialog) {
                    selectedColor.copy(alpha = currentAlpha)
                } else {
                    selectedColor
                }
                onColorSelected(finalColor)
                onDismiss()
            }) {
                Text("Áp dụng")
            }
        },
        dismissButton = {       
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}

@Composable
fun OutlinedTextPreview(
    text: String,
    textColor: Color,
    borderColor: Color,
    borderThickness: Float
) {
    // Nếu borderThickness > 0 thì vẽ viền, ngược lại chỉ vẽ text thường
    if (borderThickness > 0f) {
        // Vẽ viền bằng shadow nhiều lần quanh text
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = textColor,
                shadow = Shadow(
                    color = borderColor,
                    blurRadius = borderThickness * 2,
                    offset = Offset(0f, 0f)
                )
            ),
            maxLines = 1
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            maxLines = 1
        )
    }
}