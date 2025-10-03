package com.example.ocrmanga.ui.screens.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    val totalPages = 3

    // Hoist state variables to the top level to prevent them from resetting on page change
    var showShapeMenu by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(0) }
    var resizeDropdownExpanded by remember { mutableStateOf(false) }
    var showEditBlockDialog by remember { mutableStateOf(false) }
    var isRotatingClockwise by remember { mutableStateOf(false) }
    var isRotatingCounterClockwise by remember { mutableStateOf(false) }

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
    }
}