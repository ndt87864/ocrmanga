package com.example.ocrmanga.ui.screens.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
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
    whiteoutShapes: MutableMap<Int, Int>,
    selectedIndex: Int?,
    onDragBlocksChange: (List<DragBlockState>) -> Unit,
    onWhiteoutShapesChange: (MutableMap<Int, Int>) -> Unit,
    onSelectedIndexChange: (Int?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF0F0F0))
            .padding(8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        val isBlockSelected = selectedIndex != null
        var showShapeMenu by remember { mutableStateOf(false) }
        val shapeLabels = listOf("Hình chữ nhật", "Hình oval")
        val shapeIcons = listOf(Icons.Default.CropSquare, Icons.Default.Circle)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .padding(horizontal = 8.dp)
        ) {
            Box {
                IconButton(
                    onClick = { showShapeMenu = true },
                    modifier = Modifier
                ) {
                    Icon(
                        imageVector = shapeIcons[selectedIndex?.let { whiteoutShapes[it] ?: 0 } ?: 0],
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
                            leadingIcon = {
                                Icon(shapeIcons[index], contentDescription = null)
                            },
                            onClick = {
                                selectedIndex?.let { idx ->
                                    onWhiteoutShapesChange(whiteoutShapes.also { it[idx] = index })
                                }
                                showShapeMenu = false
                            }
                        )
                    }
                }
            }
            var resizeMode by remember { mutableStateOf(0) }
            var resizeDropdownExpanded by remember { mutableStateOf(false) }
            val resizeOptions = listOf("Tất cả", "Chiều cao", "Chiều rộng")
            IconButton(
                onClick = {
                    selectedIndex?.let { idx ->
                        onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                            val old = list[idx]
                            val b = old.block
                            val bounds = android.graphics.Rect(b.bounds)
                            when (resizeMode) {
                                0 -> bounds.inset(-10, -10)
                                1 -> {
                                    bounds.top -= 10
                                    bounds.bottom += 10
                                }
                                2 -> {
                                    bounds.left -= 10
                                    bounds.right += 10
                                }
                            }
                            list[idx] = old.copy(block = b.copy(bounds = bounds))
                        })
                    }
                },
                enabled = isBlockSelected,
                modifier = Modifier
            ) { Icon(Icons.Default.AddBox, contentDescription = "Tăng kích thước") }
            Box(
                modifier = Modifier.size(40.dp)
            ) {
                OutlinedButton(
                    onClick = { resizeDropdownExpanded = true },
                    modifier = Modifier.size(40.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        resizeOptions[resizeMode],
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = resizeDropdownExpanded,
                    onDismissRequest = { resizeDropdownExpanded = false }
                ) {
                    resizeOptions.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                resizeMode = index
                                resizeDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    selectedIndex?.let { idx ->
                        onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                            val old = list[idx]
                            val b = old.block
                            val bounds = android.graphics.Rect(b.bounds)
                            when (resizeMode) {
                                0 -> bounds.inset(10, 10)
                                1 -> {
                                    bounds.top += 10
                                    bounds.bottom -= 10
                                }
                                2 -> {
                                    val shrinkAmount = 10
                                    if (bounds.width() > 2 * shrinkAmount) {
                                        bounds.left += shrinkAmount
                                        bounds.right -= shrinkAmount
                                    }
                                }
                            }
                            list[idx] = old.copy(block = b.copy(bounds = bounds))
                        })
                    }
                },
                enabled = isBlockSelected,
                modifier = Modifier
            ) { Icon(Icons.Default.IndeterminateCheckBox, contentDescription = "Giảm kích thước") }
            IconButton(
                onClick = {
                    selectedIndex?.let { idx ->
                        onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                            list.removeAt(idx)
                        })
                        onSelectedIndexChange(dragBlocks.indices.minOrNull()?.takeIf { dragBlocks.isNotEmpty() })
                    }
                },
                enabled = isBlockSelected,
                modifier = Modifier
            ) { Icon(Icons.Default.Delete, contentDescription = "Xóa vùng đã chọn", tint = if (isBlockSelected) Color.Red else Color.Gray) }
            var showEditBlockDialog by remember { mutableStateOf(false) }
            IconButton(
                onClick = {
                    if (isBlockSelected) showEditBlockDialog = true
                },
                enabled = isBlockSelected,
                modifier = Modifier
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Sửa bản dịch", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray)
            }
            Spacer(Modifier.width(16.dp))
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
                                onClick = {
                                    editedParts = editedParts.toMutableList().also { it.add("") }
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
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
                                    val newBlocks = nonBlankParts.map { part ->
                                        old.copy(block = old.block.copy(text = part))
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
            IconButton(
                onClick = {
                    selectedIndex?.let { idx ->
                        onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                            val old = list[idx]
                            val newFont = (old.fontSize ?: 16f) + 2f
                            list[idx] = old.copy(fontSize = newFont)
                        })
                    }
                },
                enabled = isBlockSelected,
                modifier = Modifier
            ) { Icon(Icons.Default.TextIncrease, contentDescription = "Tăng cỡ chữ") }
            IconButton(
                onClick = {
                    selectedIndex?.let { idx ->
                        onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                            val old = list[idx]
                            val newFont = (old.fontSize ?: 16f) - 2f
                            list[idx] = old.copy(fontSize = newFont.coerceAtLeast(8f))
                        })
                    }
                },
                enabled = isBlockSelected,
                modifier = Modifier
            ) { Icon(Icons.Default.TextDecrease, contentDescription = "Giảm cỡ chữ") }
            Spacer(Modifier.width(16.dp))
            var isRotatingClockwise by remember { mutableStateOf(false) }
            var isRotatingCounterClockwise by remember { mutableStateOf(false) }
            val rotationSpeed = 2f
            val rotationInterval = 16L
            val coroutineScope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pointerInput(isBlockSelected) {
                        if (isBlockSelected) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                isRotatingClockwise = true
                                val job = coroutineScope.launch {
                                    while (isRotatingClockwise) {
                                        selectedIndex?.let { idx ->
                                            onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                                val old = list[idx]
                                                val newRot = (old.rotation + rotationSpeed) % 360f
                                                list[idx] = old.copy(rotation = newRot)
                                            })
                                        }
                                        delay(rotationInterval)
                                    }
                                }
                                waitForUpOrCancellation()?.consume()
                                isRotatingClockwise = false
                                job.cancel()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RotateRight,
                    contentDescription = "Xoay theo chiều kim đồng hồ",
                    tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray
                )
                if (isBlockSelected) {
                    val rot = selectedIndex?.let { idx -> dragBlocks[idx].rotation } ?: 0f
                    Text(
                        text = "${rot.toInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pointerInput(isBlockSelected) {
                        if (isBlockSelected) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                isRotatingCounterClockwise = true
                                val job = coroutineScope.launch {
                                    while (isRotatingCounterClockwise) {
                                        selectedIndex?.let { idx ->
                                            onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                                val old = list[idx]
                                                val newRot = (old.rotation - rotationSpeed) % 360f
                                                list[idx] = old.copy(rotation = newRot)
                                            })
                                        }
                                        delay(rotationInterval)
                                    }
                                }
                                waitForUpOrCancellation()?.consume()
                                isRotatingCounterClockwise = false
                                job.cancel()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RotateLeft,
                    contentDescription = "Xoay ngược chiều kim đồng hồ",
                    tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray
                )
                if (isBlockSelected) {
                    val rot = selectedIndex?.let { idx -> dragBlocks[idx].rotation } ?: 0f
                    Text(
                        text = "${rot.toInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}