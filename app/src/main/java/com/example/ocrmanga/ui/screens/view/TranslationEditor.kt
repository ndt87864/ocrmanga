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
fun TranslationEditor(
    dragBlocks: List<DragBlockState>,
    selectedIndex: Int?,
    onDragBlocksChange: (List<DragBlockState>) -> Unit,
    onSelectedIndexChange: (Int?) -> Unit,
    onSave: () -> Unit,
    isTextRemovalMode: Boolean = false,
    onToggleTextRemovalMode: () -> Unit = {},
    onTagReported: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Use centralized font registry to avoid duplication
    val fontOptions = FontRegistry.fontOptions
    // --- STATE MANAGEMENT ---
    val isBlockSelected = selectedIndex != null
    var currentPage by remember { mutableStateOf(0) }
    val totalPages = 4 // 4 trang: Lưu+Hình dạng, Sửa+Xóa, Xoay, Màu sắc

    // Hoist state variables to the top level to prevent them from resetting on page change
    var showShapeMenu by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(0) }
    var resizeDropdownExpanded by remember { mutableStateOf(false) }
    var showEditBlockDialog by remember { mutableStateOf(false) }
    // Khi mở dialog sửa/ thêm bản dịch, lưu trạng thái xem ảnh đã có block trước đó chưa.
    // Nếu đã có block, khi lưu chúng ta sẽ KHÔNG tự động gọi onSave() để về chế độ xem.
    var openedWithExistingBlocks by remember { mutableStateOf(false) }
    var isRotatingClockwise by remember { mutableStateOf(false) }
    var isRotatingCounterClockwise by remember { mutableStateOf(false) }
    // State cho xoay overlay
    var isOverlayRotatingClockwise by remember { mutableStateOf(false) }
    var isOverlayRotatingCounterClockwise by remember { mutableStateOf(false) }
    
    // State cho màu sắc
    var showOverlayColorPicker by remember { mutableStateOf(false) }
    var showTextColorPicker by remember { mutableStateOf(false) }
    
    // State cho viền chữ
    var showBorderColorPicker by remember { mutableStateOf(false) }
    // State cho đổ bóng chữ
    var showShadowColorPicker by remember { mutableStateOf(false) }
    // State cho chỉnh khoảng cách dòng
    var showLineSpacingDialog by remember { mutableStateOf(false) }
    // State cho chỉnh overlay inset
    var showOverlayInsetDialog by remember { mutableStateOf(false) }
    // Lưu giá trị lineSpacing hiện tại của block đang chọn để truyền vào form
    var initialLineSpacing by remember { mutableStateOf(1.0f) }

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

    // LaunchedEffect cho xoay overlay theo chiều kim đồng hồ
    LaunchedEffect(isOverlayRotatingClockwise, selectedIndex) {
        while (isOverlayRotatingClockwise && selectedIndex != null && isBlockSelected) {
            selectedIndex.let { idx ->
                if (idx < dragBlocks.size) {
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        val newOverlayRot = ((old.overlayRotation ?: 0f) + rotationSpeed) % 360f
                        //Log.d("TranslationEditor", "[ROTATE OVERLAY CW] idx=$idx oldRot=${old.overlayRotation} newRot=$newOverlayRot")
                        list[idx] = old.copy(overlayRotation = newOverlayRot)
                    })
                }
            }
            delay(rotationInterval)
        }
    }

    // LaunchedEffect cho xoay overlay ngược chiều kim đồng hồ
    LaunchedEffect(isOverlayRotatingCounterClockwise, selectedIndex) {
        while (isOverlayRotatingCounterClockwise && selectedIndex != null && isBlockSelected) {
            selectedIndex.let { idx ->
                if (idx < dragBlocks.size) {
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        val newOverlayRot = ((old.overlayRotation ?: 0f) - rotationSpeed) % 360f
                        //Log.d("TranslationEditor", "[ROTATE OVERLAY CCW] idx=$idx oldRot=${old.overlayRotation} newRot=$newOverlayRot")
                        list[idx] = old.copy(overlayRotation = newOverlayRot)
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {

        val shapeLabels = listOf("Hình chữ nhật", "Hình oval")
        val shapeIcons = listOf(Icons.Default.CropSquare, Icons.Default.Circle)
        val resizeOptions = listOf("Tất cả", "Chiều cao", "Chiều rộng")

        androidx.compose.animation.AnimatedVisibility(
            visible = isTextRemovalMode,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Cảnh báo: Yêu cầu Snapdragon 845 / 8GB RAM để đạt hiệu năng tốt nhất khi xóa text.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
        }

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


                        // Nút bật/tắt chế độ xóa text thủ công (cục tẩy)
                        IconButton(
                            onClick = {
                                if (!isTextRemovalMode) {
                                    android.widget.Toast.makeText(context, "Không khuyến khích sử dụng chức năng này cho thiết bị yếu hơn snapdragon 845, 8gb ram", android.widget.Toast.LENGTH_LONG).show()
                                }
                                onToggleTextRemovalMode()
                            },
                            modifier = Modifier.tutorialTag("viewer_remove", onTagReported)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = "Chế độ xóa text (Tô đỏ vùng cần xóa)",
                                tint = if (isTextRemovalMode) Color.Red else MaterialTheme.colorScheme.onBackground
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
                                                val baseEditSize = com.example.ocrmanga.ui.screens.view.computeEditModeFontSize(
                                                    block = currentBlock.block,
                                                    editedFontSize = currentBlock.fontSize
                                                )
                                                val newFontSize = if (currentBlock.block.isVertical) {
                                                    baseEditSize
                                                } else {
                                                    calculateOptimalFontSize(
                                                        text = currentBlock.block.text,
                                                        width = width,
                                                        height = height,
                                                        minFontSize = 12f,
                                                        shapeType = newShapeType,
                                                        context = context,
                                                        fontFamilyName = currentBlock.block.fontFamily,
                                                        extraSizeAllowance = 2f,
                                                        lineSpacing = currentBlock.lineSpacing,
                                                        maxFontSize = 1000f
                                                    )

                                                }


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
                                    val newList = dragBlocks.toMutableList().also { it.removeAt(idx) }
                                    onDragBlocksChange(newList)
                                    if (newList.isEmpty()) {
                                        onSelectedIndexChange(null)
                                    } else {
                                        // Chọn block gần nhất nếu còn, hoặc null nếu hết
                                        val newIdx = if (idx < newList.size) idx else newList.size - 1
                                        onSelectedIndexChange(newIdx.takeIf { newList.isNotEmpty() })
                                    }
                                }
                            },
                            enabled = isBlockSelected
                        ) {
                            Icon(Icons.Default.Delete, "Xóa vùng đã chọn", tint = if (isBlockSelected) Color.Red else Color.Gray)
                        }

                        IconButton(
                            onClick = {
                                // Lưu trạng thái ban đầu: ảnh đã có block hay chưa trước khi mở dialog
                                openedWithExistingBlocks = dragBlocks.isNotEmpty()
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

                        // Compute current/min/max for selected block to control button states
                        val selectedIdx = selectedIndex
                        val minFontGlobal = 10f
                        var currentFontForSelected = 0f
                        var maxFontForSelected = 100f
                        // These represent the font values as shown in edit UI (computeEditModeFontSize
                        // scales vertical text). Use these for tinting and toast checks so the UI reflects
                        // what the user actually sees.
                        var displayedCurrentFont = 0f
                        var displayedMaxFont = 100f
                        var displayedMinFont = if (/*vertical scaled min*/ false) minFontGlobal else minFontGlobal
                        if (selectedIdx != null && selectedIdx in dragBlocks.indices) {
                            val sel = dragBlocks[selectedIdx]
                            val b = sel.block
                            val bounds = b.bounds
                            val width = bounds.width().toFloat()
                            val height = bounds.height().toFloat()
                            // compute raw maximum allowed font for the block (with allowance)
                            val rawMax = calculateOptimalFontSize(
                                text = b.text,
                                width = width,
                                height = height,
                                minFontSize = minFontGlobal,
                                shapeType = b.shapeType,
                                context = context,
                                fontFamilyName = b.fontFamily,
                                extraSizeAllowance = 2f,
                                lineSpacing = sel.lineSpacing,
                                maxFontSize = 1000f
                            )
                            maxFontForSelected = rawMax
                            currentFontForSelected = sel.fontSize ?: b.fontSize
                            // compute displayed sizes (edit-mode scaling)
                            displayedCurrentFont = com.example.ocrmanga.ui.screens.view.computeEditModeFontSize(
                                block = b,
                                editedFontSize = sel.fontSize
                            )
                            displayedMaxFont = if (b.isVertical) (rawMax / 3f) else rawMax
                            displayedMinFont = if (b.isVertical) (minFontGlobal / 3f) else minFontGlobal
                        }

                        // Debug logging to inspect computed values at runtime
                        if (selectedIdx != null && selectedIdx in dragBlocks.indices) {
                            val sel = dragBlocks[selectedIdx]
                        }

                        // Keep buttons clickable so we can show a toast when user hits the limit,
                        // but tint them gray when they are effectively disabled (based on displayed sizes).
                        val decreaseEnabled = isBlockSelected && displayedCurrentFont > displayedMinFont + 0.01f
                        val increaseEnabled = isBlockSelected && displayedCurrentFont < displayedMaxFont - 0.01f

                        IconButton(
                            onClick = {
                                selectedIdx?.let { idx ->
                                    // Check displayed value so toast/tint match what user sees
                                    // Use raw stored font for toast triggers so it matches the actual value stored
                                    val currentRaw = dragBlocks.getOrNull(idx)?.let { it.fontSize ?: it.block.fontSize } ?: minFontGlobal
                                    val curDragBlockForCheck = dragBlocks.getOrNull(idx)
                                    val curBlockForCheck = curDragBlockForCheck?.block
                                    val rawMaxForThis = if (curBlockForCheck != null && curDragBlockForCheck != null) {
                                        val bb = curBlockForCheck
                                        val w = bb.bounds.width().toFloat()
                                        val h = bb.bounds.height().toFloat()
                                        calculateOptimalFontSize(
                                            text = bb.text,
                                            width = w,
                                            height = h,
                                            minFontSize = minFontGlobal,
                                            shapeType = bb.shapeType,
                                            context = context,
                                            fontFamilyName = bb.fontFamily,
                                            extraSizeAllowance = 2f,
                                            lineSpacing = curDragBlockForCheck.lineSpacing,
                                            maxFontSize = 1000f
                                        )
                                    } else {
                                        minFontGlobal
                                    }
                                    val alreadyAtMinRaw = currentRaw <= minFontGlobal + 0.001f
                                    if (alreadyAtMinRaw) {
                                        android.widget.Toast.makeText(context, "Đã đạt kích thước chữ nhỏ nhất", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    onDragBlocksChange(dragBlocks.toMutableList().also {
                                        val old = it[idx]
                                        val b = old.block
                                        val bounds = b.bounds
                                        val width = bounds.width().toFloat()
                                        val height = bounds.height().toFloat()
                                        val maxFont = calculateOptimalFontSize(
                                            text = b.text,
                                            width = width,
                                            height = height,
                                            minFontSize = minFontGlobal,
                                            shapeType = b.shapeType,
                                            context = context,
                                            fontFamilyName = b.fontFamily,
                                            extraSizeAllowance = 2f,
                                            lineSpacing = old.lineSpacing,
                                            maxFontSize = 1000f
                                        )
                                        val newFontCandidate = (old.fontSize ?: b.fontSize) - 1f
                                        val clamped = newFontCandidate.coerceIn(minFontGlobal, maxFont)
                                        it[idx] = old.copy(fontSize = clamped)
                                    })
                                }
                            },
                            // keep clickable even if it would do nothing so we can show a toast
                            enabled = true
                        ) {
                            Icon(
                                imageVector = Icons.Default.TextDecrease,
                                contentDescription = "Giảm cỡ chữ",
                                tint = if (decreaseEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Nút chỉnh khoảng cách dòng (đặt riêng để tránh lồng nhau)
                        IconButton(
                            onClick = {
                                if (isBlockSelected && selectedIndex != null) {
                                    initialLineSpacing = dragBlocks[selectedIndex].lineSpacing
                                    showLineSpacingDialog = true
                                }
                            },
                            enabled = isBlockSelected
                        ) {
                            Icon(
                                imageVector = Icons.Default.FormatLineSpacing,
                                contentDescription = "Khoảng cách dòng",
                                tint = if (isBlockSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                selectedIdx?.let { idx ->
                                    // Use displayed values for toast/tint so user sees consistent behavior
                                    // Use raw stored font for toast triggers so it matches the actual value stored
                                    val currentRawMaxCheck = dragBlocks.getOrNull(idx)?.let { it.fontSize ?: it.block.fontSize } ?: minFontGlobal
                                    val curDragBlockForCheck2 = dragBlocks.getOrNull(idx)
                                    val curBlockForCheck2 = curDragBlockForCheck2?.block
                                    val rawMaxForThisCheck = if (curBlockForCheck2 != null && curDragBlockForCheck2 != null) {
                                        val bb = curBlockForCheck2
                                        val w = bb.bounds.width().toFloat()
                                        val h = bb.bounds.height().toFloat()
                                        calculateOptimalFontSize(
                                            text = bb.text,
                                            width = w,
                                            height = h,
                                            minFontSize = minFontGlobal,
                                            shapeType = bb.shapeType,
                                            context = context,
                                            fontFamilyName = bb.fontFamily,
                                            extraSizeAllowance = 2f,
                                            lineSpacing = curDragBlockForCheck2.lineSpacing,
                                            maxFontSize = 1000f
                                        )
                                    } else {
                                        minFontGlobal
                                    }
                                    val alreadyAtMaxRaw = currentRawMaxCheck >= rawMaxForThisCheck - 0.001f
                                    if (alreadyAtMaxRaw) {
                                        android.widget.Toast.makeText(context, "Đã đạt kích thước chữ tối đa", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    onDragBlocksChange(dragBlocks.toMutableList().also {
                                        val old = it[idx]
                                        val b = old.block
                                        val bounds = b.bounds
                                        val width = bounds.width().toFloat()
                                        val height = bounds.height().toFloat()
                                        val maxFont = calculateOptimalFontSize(
                                            text = b.text,
                                            width = width,
                                            height = height,
                                            minFontSize = minFontGlobal,
                                            shapeType = b.shapeType,
                                            context = context,
                                            fontFamilyName = b.fontFamily,
                                            extraSizeAllowance = 2f,
                                            lineSpacing = old.lineSpacing,
                                            maxFontSize = 1000f
                                        )
                                        val newFontCandidate = (old.fontSize ?: b.fontSize) + 1f
                                        val clamped = newFontCandidate.coerceIn(minFontGlobal, maxFont)
                                        it[idx] = old.copy(fontSize = clamped)
                                    })
                                }
                            },
                            // keep clickable even if it would do nothing so we can show a toast
                            enabled = true
                        ) {
                            Icon(
                                Icons.Default.TextIncrease,
                                "Tăng cỡ chữ",
                                tint = if (increaseEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // --- TRANG 3: XOAY ---
                    2 -> {

                            IconButton(
                                onClick = { onSelectedIndexChange(selectedIndex?.let { maxOf(0, it - 1) } ?: 0) },
                                enabled = dragBlocks.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Block trước",
                                    tint = if (dragBlocks.isNotEmpty()) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onSelectedIndexChange(selectedIndex?.let { minOf(dragBlocks.size - 1, it + 1) } ?: 0) },
                                enabled = dragBlocks.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "Block tiếp theo",
                                    tint = if (dragBlocks.isNotEmpty()) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                        // Xoay TEXT theo chiều kim đồng hồ
                        Box(
                            modifier = Modifier.size(40.dp).pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (isBlockSelected) { isRotatingClockwise = true; waitForUpOrCancellation(); isRotatingClockwise = false }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.RotateRight, "Xoay text theo chiều kim đồng hồ", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isBlockSelected) {
                                val rot = selectedIndex?.let { dragBlocks[it].rotation } ?: 0f
                                Text("${rot.toInt()}°", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }

                        // Xoay TEXT ngược chiều kim đồng hồ
                        Box(
                            modifier = Modifier.size(40.dp).pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (isBlockSelected) { isRotatingCounterClockwise = true; waitForUpOrCancellation(); isRotatingCounterClockwise = false }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.RotateLeft, "Xoay text ngược chiều kim đồng hồ", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isBlockSelected) {
                                val rot = selectedIndex?.let { dragBlocks[it].rotation } ?: 0f
                                Text("${rot.toInt()}°", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }

                        // Divider nhỏ giữa text và overlay
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        )

                        // Xoay OVERLAY theo chiều kim đồng hồ
                        Box(
                            modifier = Modifier.size(40.dp).pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (isBlockSelected) { isOverlayRotatingClockwise = true; waitForUpOrCancellation(); isOverlayRotatingClockwise = false }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Crop, "Xoay overlay theo chiều kim đồng hồ", tint = if (isBlockSelected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isBlockSelected) {
                                val overlayRot = selectedIndex?.let { dragBlocks[it].overlayRotation ?: 0f } ?: 0f
                                Text("${overlayRot.toInt()}°", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }

                        // Xoay OVERLAY ngược chiều kim đồng hồ
                        Box(
                            modifier = Modifier.size(40.dp).pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (isBlockSelected) { isOverlayRotatingCounterClockwise = true; waitForUpOrCancellation(); isOverlayRotatingCounterClockwise = false }
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CropRotate, "Xoay overlay ngược chiều kim đồng hồ", tint = if (isBlockSelected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isBlockSelected) {
                                val overlayRot = selectedIndex?.let { dragBlocks[it].overlayRotation ?: 0f } ?: 0f
                                Text("${overlayRot.toInt()}°", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
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
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                            }

                            // Nút chọn màu text
                            IconButton(
                                onClick = { if (isBlockSelected) showTextColorPicker = true },
                                enabled = isBlockSelected
                            ) {
                                val sel = selectedIndex?.let { dragBlocks[it] }
                                val currentTextColor = sel?.textColor ?: Color.Black
                                val gradColors = sel?.textGradientColors
                                val gradType = sel?.textGradientType ?: 0

                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .then(
                                            if (gradColors != null && gradColors.size >= 2) {
                                                val colorsList = gradColors.map { Color(it) }
                                                val brush = when (gradType) {
                                                    0 -> androidx.compose.ui.graphics.Brush.verticalGradient(colorsList)
                                                    1 -> androidx.compose.ui.graphics.Brush.horizontalGradient(colorsList)
                                                    2 -> androidx.compose.ui.graphics.Brush.linearGradient(
                                                        colors = colorsList,
                                                        start = androidx.compose.ui.geometry.Offset.Zero,
                                                        end = androidx.compose.ui.geometry.Offset.Infinite
                                                    )
                                                    else -> androidx.compose.ui.graphics.Brush.linearGradient(
                                                        colors = colorsList,
                                                        start = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f),
                                                        end = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY)
                                                    )
                                                }
                                                Modifier.background(brush, CircleShape)
                                            } else {
                                                Modifier.background(currentTextColor, CircleShape)
                                            }
                                        )
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                            }
                            
                            // Nút chọn màu viền (giống overlay/text)
                            IconButton(
                                onClick = { if (isBlockSelected) showBorderColorPicker = true },
                                enabled = isBlockSelected
                            ) {
                                val currentBorderColor = selectedIndex?.let { dragBlocks[it].textBorderColor } ?: Color.Black
                                val currentBorderAlpha = selectedIndex?.let { dragBlocks[it].textBorderAlpha } ?: 1f
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(Color.Transparent, CircleShape)
                                        .border(
                                            width = 3.dp,
                                            color = currentBorderColor.copy(alpha = currentBorderAlpha),
                                            shape = CircleShape
                                        )
                                )
                            }
                             // Nút chọn màu đổ bóng chữ (bên cạnh màu viền)
                            IconButton(
                                onClick = { if (isBlockSelected) showShadowColorPicker = true },
                                enabled = isBlockSelected
                            ) {
                                val currentShadowColor = selectedIndex?.let { dragBlocks[it].textShadowColor } ?: Color.Black
                                val currentShadowAlpha = selectedIndex?.let { dragBlocks[it].textShadowAlpha } ?: 1f
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(Color.Transparent, CircleShape)
                                        .border(
                                            width = 2.dp,
                                            color = currentShadowColor.copy(alpha = currentShadowAlpha),
                                            shape = CircleShape
                                        )
                                )
                            }

                            // Nút chỉnh overlay inset
                            IconButton(
                                onClick = {
                                    if (isBlockSelected && selectedIndex != null) {
                                        showOverlayInsetDialog = true
                                    }
                                },
                                enabled = isBlockSelected
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Crop,
                                    contentDescription = "Chỉnh overlay inset",
                                    tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                                                overlayInset = 0f,
                                                textBorderColor = null,
                                                textBorderThickness = 0.0f,
                                                textBorderAlpha = 1.0f
                                            )
                                        })
                                    }
                                },
                                enabled = isBlockSelected
                            ) {
                                Icon(Icons.Default.Refresh, "Reset màu mặc định", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                icon = {
                    Box(
                        modifier = Modifier.size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = { Text("Sửa/Thêm bản dịch", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        // Font selection dropdown
                        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            OutlinedButton(onClick = { fontDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Font: $selectedFontName")
                            }
                            if (fontDropdownExpanded) {
                                AlertDialog(
                                    onDismissRequest = { fontDropdownExpanded = false },
                                    shape = RoundedCornerShape(20.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    title = { Text("Chọn Font", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                                    text = {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 400.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            val fontCategories = listOf(
                                                "Truyện Tranh (Comic/Manga)" to listOf(
                                                    "mto_comic_1", "mto_comic_2", "cent_comics", "comic_sans", 
                                                    "mto_sans", "mto_astro_city", "chinacat", "chit_chat", 
                                                    "mto_dom", "mto_mikes", "lnth", "iciel_pony"
                                                ),
                                                "Viết Tay (Handwritten)" to listOf(
                                                    "mto_augie", "novitha_script", "boutique_script", "fresh_script", 
                                                    "adeline", "calligraphy", "handelson_two", "mto_chancery", 
                                                    "imaginary_friend"
                                                ),
                                                "Bút Lông (Brush)" to listOf(
                                                    "dexsar_brush", "blow_brush", "break_brush", "harry_brush", 
                                                    "kashima_brush", "story_brush", "okami", "semhesta"
                                                ),
                                                " SFX (Display)" to listOf(
                                                    "mighty_zero", "mto_shadow", "kingston", "bougher", "entrails", 
                                                    "felt", "hiro_misake", "mto_chranko", "redtowns", "wrong_hunt", 
                                                    "you_murdere"
                                                )
                                            )

                                            fontCategories.forEach { (category, fonts) ->
                                                Text(
                                                    text = category,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(vertical = 8.dp)
                                                )
                                                
                                                val categoryFonts = fonts.mapNotNull { name -> 
                                                    fontOptions.find { it.first == name }
                                                }
                                                val fontChunks = categoryFonts.chunked(3)
                                                
                                                fontChunks.forEach { rowItems ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        rowItems.forEach { (name, family) ->
                                                            Surface(
                                                                onClick = {
                                                                    selectedFontName = name
                                                                    fontDropdownExpanded = false
                                                                    // Update lineSpacing to default for selected font
                                                                    val defaultLineSpacing = when {
                                                                        name.equals("mto_comic_1", ignoreCase = true) -> 1.1f
                                                                        name.equals("mto_augie", ignoreCase = true) || name.contains("augie", ignoreCase = true) -> 2.0f
                                                                        name.equals("mighty_zero", ignoreCase = true) || name.contains("mighty_zero", ignoreCase = true) -> 0.92f
                                                                        else -> 1.0f
                                                                    }
                                                                    // Only update lineSpacing for the currently selected block
                                                                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                                                        val oldBlock = list[idx]
                                                                        list[idx] = oldBlock.copy(lineSpacing = defaultLineSpacing)
                                                                    })
                                                                },
                                                                modifier = Modifier.weight(1f),
                                                                shape = MaterialTheme.shapes.small,
                                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                                color = if (name == selectedFontName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                                            ) {
                                                                Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                                                                    Text(
                                                                        text = name,
                                                                        fontFamily = family,
                                                                        fontSize = 14.sp,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis,
                                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        if (rowItems.size < 3) {
                                                            repeat(3 - rowItems.size) {
                                                                Spacer(modifier = Modifier.weight(1f))
                                                            }
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                }
                                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { fontDropdownExpanded = false }) { Text("Đóng") }
                                    }
                                )
                            }
                        }

                        // Alignment selection (Left / Center / Center lower longer)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val alignOptions = listOf(
                                com.example.ocrmanga.data.models.TextAlignMode.LEFT to "Trái",
                                com.example.ocrmanga.data.models.TextAlignMode.CENTER to "Giữa"
                            )
                            alignOptions.forEach { (mode, label) ->
                                OutlinedButton(
                                    onClick = {
                                        onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                            val old = list[idx]
                                            list[idx] = old.copy(block = old.block.copy(textAlign = mode))
                                        })
                                    },
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, if (block.textAlign == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        // Existing translation parts
                        editedParts.forEachIndexed { i, part ->
                            OutlinedTextField(
                                value = part,
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
                            onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                val oldBlock = list[idx]
                                val newBlocks = nonBlankParts.mapIndexed { index, text ->
                                    val defaultLineSpacing = when {
                                        selectedFontName.equals("mto_comic_1", ignoreCase = true) -> 1.1f
                                        selectedFontName.equals("mto_augie", ignoreCase = true) || selectedFontName.contains("augie", ignoreCase = true) -> 2.0f
                                        selectedFontName.equals("mighty_zero", ignoreCase = true) || selectedFontName.contains("mighty_zero", ignoreCase = true) -> 0.92f
                                        else -> 1.0f
                                    }
                                    oldBlock.copy(
                                        block = oldBlock.block.copy(
                                            text = text,
                                            fontFamily = selectedFontName,
                                            lineSpacing = defaultLineSpacing
                                        )
                                    )
                                }
                                list.removeAt(idx)
                                list.addAll(idx, newBlocks)
                            })
                            // Nếu ảnh ban đầu KHÔNG có block thì tự động gọi onSave() (về chế độ xem).
                            // Nếu ảnh đã có block từ trước (openedWithExistingBlocks == true),
                            // chỉ cập nhật dragBlocks mà KHÔNG tự động lưu/thoát.
                            if (!openedWithExistingBlocks) {
                                onSave()
                            }
                        }
                        showEditBlockDialog = false
                        // Reset flag để lần mở sau sẽ tính lại từ dragBlocks hiện tại
                        openedWithExistingBlocks = false
                    }) { Text("Lưu") }
                },
                dismissButton = {
                    OutlinedButton(
                    onClick = { showEditBlockDialog = false },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Hủy") }
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
            val idx = selectedIndex!!
            val block = dragBlocks[idx]
            val currentColor = block.textColor ?: Color.Black
            val currentBoldness = block.textBoldness
            val currentSaturation = block.textSaturation
            
            ColorPickerDialog(
                title = "Chọn màu chữ",
                initialColor = currentColor.copy(alpha = 1f),
                initialAlpha = 1f, // Text luôn không trong suốt
                initialBoldness = currentBoldness,
                initialSaturation = currentSaturation,
                isOverlayDialog = false,
                supportGradient = true,
                initialGradientColors = block.textGradientColors?.map { Color(it) } ?: listOf(Color.Black, Color.White),
                initialGradientOffsets = block.textGradientOffsets ?: listOf(0f, 1f),
                initialGradientType = block.textGradientType,
                onColorSelected = { color ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(
                            textColor = color,
                            textGradientColors = null,
                            textGradientOffsets = null
                        )
                    })
                    showTextColorPicker = false
                },
                onGradientSelected = { colors, offsets, gType ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(
                            textGradientColors = colors.map { it.toArgb() },
                            textGradientOffsets = offsets,
                            textGradientType = gType,
                            textColor = null // Clear single color when gradient is selected
                        )
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
                maxThickness = (dragBlocks[idx].fontSize ?: dragBlocks[idx].block.fontSize),
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

        // Dialog chọn màu đổ bóng chữ
        if (showShadowColorPicker && isBlockSelected && selectedIndex != null) {
            val idx = selectedIndex
            val currentColor = dragBlocks[idx].textShadowColor ?: Color.Black
            val currentAlpha = dragBlocks[idx].textShadowAlpha
            ColorPickerDialog(
                title = "Chọn màu đổ bóng chữ",
                initialColor = currentColor.copy(alpha = 1f),
                initialAlpha = currentAlpha,
                initialThickness = (dragBlocks[idx].textShadowRadius),
                isOverlayDialog = false,
                onColorSelected = { color ->
                    // Thêm log kiểm tra giá trị shadowColor
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textShadowColor = color)
                    })
                    showShadowColorPicker = false
                },
                onAlphaChanged = { alpha ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textShadowAlpha = alpha)
                    })
                },
                onThicknessChanged = { radius ->
                    onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                        val old = list[idx]
                        list[idx] = old.copy(textShadowRadius = radius)
                    })
                },
                onDismiss = { showShadowColorPicker = false }
            )
        }

        // Dialog chỉnh khoảng cách dòng
        if (showLineSpacingDialog && isBlockSelected && selectedIndex != null) {
            val idx = selectedIndex
            // Sử dụng initialLineSpacing đã truyền vào khi mở dialog
            var currentSpacing by remember(initialLineSpacing) { mutableStateOf(initialLineSpacing) }
            AlertDialog(
                onDismissRequest = { showLineSpacingDialog = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                icon = {
                    Box(
                        modifier = Modifier.size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatLineSpacing,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = { Text("Khoảng cách dòng", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Điều chỉnh khoảng cách giữa các dòng của bản dịch", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = currentSpacing,
                            onValueChange = { currentSpacing = it },
                            valueRange = 0.5f..2.5f,
                            steps = 80, // step = 0.025
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Hiện tại: ${"%.2f".format(currentSpacing)}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                val old = list[idx]
                                list[idx] = old.copy(lineSpacing = currentSpacing)
                            })
                            showLineSpacingDialog = false
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Áp dụng") }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showLineSpacingDialog = false },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Hủy") }
                }
            )
        }

        // Dialog chỉnh overlay inset
        if (showOverlayInsetDialog && isBlockSelected && selectedIndex != null) {
            val idx = selectedIndex
            val currentInsetH = dragBlocks[idx].overlayInsetHorizontal
            val currentInsetV = dragBlocks[idx].overlayInsetVertical
            var insetMode by remember { mutableStateOf(0) } // 0=Tất cả, 1=Chiều rộng, 2=Chiều cao
            var insetValueH by remember(currentInsetH) { mutableStateOf(currentInsetH) }
            var insetValueV by remember(currentInsetV) { mutableStateOf(currentInsetV) }
            val insetModeOptions = listOf("Tất cả", "Chiều rộng", "Chiều cao")
            
            AlertDialog(
                onDismissRequest = { showOverlayInsetDialog = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                icon = {
                    Box(
                        modifier = Modifier.size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SpaceBar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = { Text("Chỉnh overlay inset", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Điều chỉnh khoảng cách inset của overlay (làm overlay nhỏ hơn)", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Chọn chế độ inset
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            insetModeOptions.forEachIndexed { index, label ->
                                FilterChip(
                                    selected = insetMode == index,
                                    onClick = { insetMode = index },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        when (insetMode) {
                            0 -> {
                                Text("Inset tất cả:", style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = insetValueH,
                                    onValueChange = { 
                                        insetValueH = it
                                        insetValueV = it
                                    },
                                    valueRange = 0f..50f,
                                    steps = 250,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(text = "${"%.1f".format(insetValueH)} px", style = MaterialTheme.typography.bodySmall)
                            }
                            1 -> {
                                Text("Inset chiều rộng (trái/phải):", style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = insetValueH,
                                    onValueChange = { insetValueH = it },
                                    valueRange = 0f..50f,
                                    steps = 250,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(text = "${"%.1f".format(insetValueH)} px", style = MaterialTheme.typography.bodySmall)
                            }
                            2 -> {
                                Text("Inset chiều cao (trên/dưới):", style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = insetValueV,
                                    onValueChange = { insetValueV = it },
                                    valueRange = 0f..50f,
                                    steps = 250,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(text = "${"%.1f".format(insetValueV)} px", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Hiện tại: Ngang = ${"%.1f".format(insetValueH)} px, Dọc = ${"%.1f".format(insetValueV)} px",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDragBlocksChange(dragBlocks.toMutableList().also { list ->
                                val old = list[idx]
                                list[idx] = old.copy(
                                    overlayInset = maxOf(insetValueH, insetValueV),
                                    overlayInsetHorizontal = insetValueH,
                                    overlayInsetVertical = insetValueV
                                )
                            })
                            showOverlayInsetDialog = false
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Áp dụng") }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showOverlayInsetDialog = false },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Hủy") }
                }
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