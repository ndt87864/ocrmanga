package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Intent
import com.example.ocrmanga.utils.AppLogger as Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.viewmodels.ViewerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ViewerScreen(
    imageUris: List<String> = emptyList(),
    roomId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel()
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var showRoomNav by remember { mutableStateOf(false) }
    var showTranslationMenu by remember { mutableStateOf(false) }
    var showMainMenu by remember { mutableStateOf(false) }
    var showRoomFontDialog by remember { mutableStateOf(false) }
    var showInsertAtIndexDialog by remember { mutableStateOf(false) }
    var insertAtIndex by remember { mutableStateOf("") }
    var showEditTitleDialog by remember { mutableStateOf(false) }
    var editTitleText by remember { mutableStateOf("") }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var pendingBack by remember { mutableStateOf(false) }
    var shouldNavigateBackAfterClear by remember { mutableStateOf(false) }
    var showImageMenu by remember { mutableStateOf(false) }
    var imageMenuUri by remember { mutableStateOf<Uri?>(null) }
    var editTranslationMode by remember { mutableStateOf(false) }
    val dragBlocksMap = remember { mutableStateMapOf<Uri, List<DragBlockState>>() }
    val lazyListState = rememberLazyListState()
    var showSpeedSlider by remember { mutableStateOf(false) }
    var autoScrollEnabled by remember { mutableStateOf(false) }
    var scrollSpeed by remember { mutableStateOf(5f) }
    // Text removal mode state
    var isTextRemovalMode by remember { mutableStateOf(false) }
    var isRemovingText by remember { mutableStateOf(false) }
    var removingTextLocalProgress by remember { mutableStateOf("") }
    var brushSize by remember { mutableStateOf(40f) }

    val uiState by viewModel.uiState.collectAsState()
    val allRoomIds by viewModel.allRoomIds.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(key1 = imageUris, key2 = roomId) {
        // If the gallery passed explicit imageUris, treat this as a new temporary session
        // and prefer it over any lingering roomId saved in navigation state.
        if (imageUris.isNotEmpty()) {
            // Ensure ViewModel state is fully reset for a new temporary session
            viewModel.clearSessionAndImages(false)
            viewModel.setImageUris(imageUris.map { Uri.parse(it) }, isNew = true)
        } else if (roomId != null) {
            viewModel.loadRoom(roomId)
        }
    }

    LaunchedEffect(shouldNavigateBackAfterClear, uiState.imageUris) {
        if (shouldNavigateBackAfterClear && uiState.imageUris.isEmpty()) {
            shouldNavigateBackAfterClear = false
            onNavigateBack()
        }
    }

    // Save all dragBlocksMap to translatedTexts when exiting edit mode
    LaunchedEffect(editTranslationMode) {
        if (!editTranslationMode) {
            dragBlocksMap.forEach { (uri, blocks) ->
                        viewModel.updateTranslatedBlocks(uri, blocks.map { dragBlock ->
                        // Bounds đã được cập nhật khi drag trong ImageViewer, không cần cộng offset nữa
                        // copy() sẽ tự động copy bounds từ dragBlock.block
                        dragBlock.block.copy(
                        fontSize = dragBlock.fontSize ?: dragBlock.block.fontSize, // Lưu fontSize đã chỉnh sửa
                        rotation = dragBlock.rotation,
                        overlayRotation = dragBlock.overlayRotation,
                        shapeType = dragBlock.block.shapeType,
                        customOverlayColor = dragBlock.whiteoutColor?.toArgb() ?: dragBlock.block.customOverlayColor,
                        customTextColor = dragBlock.textColor?.toArgb()
                            ?: dragBlock.block.customTextColor
                            ?: computeDefaultTextColor(dragBlock.whiteoutColor?.toArgb() ?: dragBlock.block.customOverlayColor, dragBlock.block.averageBackgroundColor),
                            overlayAlpha = dragBlock.overlayAlpha,
                        textBoldness = dragBlock.textBoldness,
                        overlaySaturation = dragBlock.overlaySaturation,
                        textSaturation = dragBlock.textSaturation,
                        overlayInset = dragBlock.overlayInset,
                        overlayInsetHorizontal = dragBlock.overlayInsetHorizontal,
                        overlayInsetVertical = dragBlock.overlayInsetVertical,
                        customBorderColor = dragBlock.textBorderColor?.toArgb() ?: dragBlock.block.customBorderColor,
                        borderThickness = dragBlock.textBorderThickness,
                        borderAlpha = dragBlock.textBorderAlpha,
                        // Preserve shadow settings from edit state so they persist after save/exit
                        customShadowColor = dragBlock.textShadowColor?.toArgb(),
                        shadowAlpha = dragBlock.textShadowAlpha,
                        shadowRadius = dragBlock.textShadowRadius,
                        lineSpacing = dragBlock.lineSpacing,
                        textGradientColors = dragBlock.textGradientColors,
                        textGradientOffsets = dragBlock.textGradientOffsets,
                        textGradientType = dragBlock.textGradientType,
                        applyMerge = false
                    ) 
                })
            }
            // KHÔNG clear dragBlocksMap ở đây vì LaunchedEffect rebuild sẽ chạy đồng thời
            // và có thể đọc uiState.translatedTexts cũ trước khi updateTranslatedBlocks hoàn tất
            // Thay vào đó, để LaunchedEffect rebuild tự cập nhật khi translatedTexts thay đổi
        }
    }

    // Initialize dragBlocksMap for all uris from translatedTexts
    // Ưu tiên dữ liệu mới từ translation mode thay vì giữ nguyên dragBlocksMap cũ
    // LaunchedEffect này sẽ tự động rebuild dragBlocksMap khi translatedTexts thay đổi
    LaunchedEffect(uiState.imageUris, uiState.translatedTexts, uiState.translationVersion, editTranslationMode) {
        uiState.imageUris.forEach { uri ->
            val currentTranslatedBlocks = uiState.translatedTexts[uri]?.second
            // Chỉ rebuild dragBlocksMap khi KHÔNG ở edit mode để tránh override các thay đổi đang được edit
            // Khi ở edit mode, dragBlocksMap được quản lý bởi ImageViewer
            if (!editTranslationMode) {
                // Luôn cập nhật dragBlocksMap từ translatedTexts mới
                // Nếu không có translatedTexts (mode OFF), xóa blocks
                if (currentTranslatedBlocks != null) {
                    // Get old blocks to check which ones have been edited (applyMerge=false)
                    // Dùng bounds làm key duy nhất để nhận diện block đã edit, KHÔNG dùng text
                    val oldBlocks = dragBlocksMap[uri]?.associateBy { it.block.bounds } ?: emptyMap()

                    val blocks = currentTranslatedBlocks.map { block ->
                        // Ensure overlay int includes opaque alpha so Color(...) isn't transparent
                        val rawOverlay = block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()
                        val overlayColorInt = rawOverlay or 0xFF000000.toInt()
                        val textColorInt = block.customTextColor ?: block.originalTextColor ?: computeDefaultTextColor(overlayColorInt, block.averageBackgroundColor)

                        // ✅ Ưu tiên applyMerge từ block load từ DB, giữ nguyên oldBlock nếu có
                        // Nếu load từ DB thì block.applyMerge đã có giá trị đúng từ database
                        val oldBlock = oldBlocks[block.bounds]
                        val shouldApplyMerge = oldBlock?.block?.applyMerge ?: block.applyMerge

                        DragBlockState(
                            block = block.copy(
                                customOverlayColor = overlayColorInt,
                                customTextColor = textColorInt,
                                applyMerge = shouldApplyMerge
                            ),
                            offset = androidx.compose.ui.geometry.Offset.Zero,
                            fontSize = block.fontSize,
                            rotation = block.rotation ?: 0f,
                            overlayRotation = block.overlayRotation,
                            whiteoutColor = Color(overlayColorInt),
                            textColor = Color(textColorInt),
                            overlayAlpha = block.overlayAlpha,
                            textBoldness = block.textBoldness,
                            overlaySaturation = block.overlaySaturation,
                            textSaturation = block.textSaturation,
                            lineSpacing = block.lineSpacing,
                            overlayInset = block.overlayInset,
                            overlayInsetHorizontal = block.overlayInsetHorizontal,
                            overlayInsetVertical = block.overlayInsetVertical,
                            textBorderColor = block.customBorderColor?.let { Color(it or 0xFF000000.toInt()) },
                            textBorderThickness = block.borderThickness,
                            textBorderAlpha = block.borderAlpha,
                            // BỔ SUNG SHADOW
                            textShadowColor = block.customShadowColor?.let { Color(it) },
                            textShadowAlpha = block.shadowAlpha,
                            textShadowRadius = block.shadowRadius,
                            textGradientColors = block.textGradientColors,
                            textGradientOffsets = block.textGradientOffsets,
                            textGradientType = block.textGradientType
                        )
                    }

                    dragBlocksMap[uri] = blocks
                } else {
                    // Xóa blocks khi tắt dịch
                    dragBlocksMap.remove(uri)
                }
            }
        }
    }

    // Hiển thị Toast một lần khi bắt đầu dịch, sau đó dùng Text component để theo dõi
    LaunchedEffect(uiState.currentTranslatingImage) {
        if (uiState.currentTranslatingImage != null) {
            Toast.makeText(
                context, 
                "Bắt đầu dịch ảnh...", 
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Biến để tạm dừng việc update scroll index khi đang scroll programmatically
    var isScrollingProgrammatically by remember { mutableStateOf(false) }

    // Theo dõi vị trí scroll hiện tại và thông báo cho ViewModel
    // Chỉ update khi không đang scroll programmatically để tránh xung đột
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .collect { index ->
                if (!isScrollingProgrammatically) {
                    viewModel.setCurrentScrollIndex(index)
                }
            }
    }

    // Scroll đến vị trí sau khi reload từ DB
    // Sử dụng key Unit để effect luôn chạy và tự theo dõi state thay đổi
    LaunchedEffect(Unit) {
        snapshotFlow { Pair(uiState.scrollToIndexAfterReload, uiState.imageUris.size) }
            .collect { (targetIndex, imageCount) ->
                Log.d("ViewerScreen", "scrollToIndexAfterReload changed: targetIndex=$targetIndex, imageCount=$imageCount")
                if (targetIndex != null && targetIndex > 0) {
                    Log.d("ViewerScreen", "Will scroll to index $targetIndex")
                    
                    // Đợi cho đến khi có đủ ảnh để scroll (check lại trong vòng lặp)
                    var currentCount = imageCount
                    var attempts = 0
                    while (currentCount <= targetIndex && attempts < 50) {
                        delay(100)
                        currentCount = uiState.imageUris.size
                        attempts++
                    }
                    
                    val finalCount = uiState.imageUris.size
                    if (finalCount > 0) {
                        val safeIndex = targetIndex.coerceIn(0, finalCount - 1)
                        Log.d("ViewerScreen", "Scrolling to index $safeIndex (requested: $targetIndex, total: $finalCount)")
                        
                        // Tạm dừng update scroll index
                        isScrollingProgrammatically = true
                        
                        // Delay để LazyColumn render xong
                        delay(300)
                        
                        try {
                            lazyListState.scrollToItem(safeIndex)
                            Log.d("ViewerScreen", "Scroll completed to index $safeIndex")
                        } catch (e: Exception) {
                            Log.e("ViewerScreen", "Failed to scroll to index $safeIndex", e)
                        }
                        
                        // Delay thêm rồi mới cho phép update scroll index lại
                        delay(500)
                        isScrollingProgrammatically = false
                        
                        // Update scroll index về vị trí mới
                        viewModel.setCurrentScrollIndex(safeIndex)
                        
                        // Clear scroll index sau khi đã scroll
                        viewModel.clearScrollToIndex()
                    }
                }
            }
    }

    val pickImagesAtStartLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (!uris.isNullOrEmpty()) {
                // Persist read permission for each picked URI so we can access it later
                uris.forEach { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        // ignore - keep trying to copy what we can
                        Log.w("ViewerScreen", "Could not persist permission for $uri", e)
                    }
                }
                viewModel.addNewImageUrisAtStart(uris)
                Toast.makeText(context, "Đã thêm ${uris.size} ảnh vào đầu", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val pickImagesAtEndLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (!uris.isNullOrEmpty()) {
                uris.forEach { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        Log.w("ViewerScreen", "Could not persist permission for $uri", e)
                    }
                }
                viewModel.addNewImageUris(uris)
                Toast.makeText(context, "Đã thêm ${uris.size} ảnh vào cuối", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val pickImagesAtIndexLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (!uris.isNullOrEmpty()) {
                uris.forEach { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        Log.w("ViewerScreen", "Could not persist permission for $uri", e)
                    }
                }
                val index = insertAtIndex.toIntOrNull() ?: 0
                viewModel.addNewImageUrisAtIndex(uris, index)
                Toast.makeText(context, "Đã thêm ${uris.size} ảnh vào vị trí ${index + 1}", Toast.LENGTH_SHORT).show()
                insertAtIndex = ""
            }
        }
    )

    // Replace existing handleBack implementation so we NEVER persist room data when leaving Viewer
    val handleBack: () -> Unit = {
        // Decide whether to delete saved room files too (if we opened a saved room)
        val deleteSaved = uiState.roomId != null
        viewModel.clearSessionAndImages(deleteSaved)
        onNavigateBack()
    }

    // Tạo translatedTextsFiltered để lọc các block có pendingDelete = false
    val translatedTextsFiltered = uiState.translatedTexts.mapValues { entry ->
        val pair = entry.value
        pair.copy(second = pair.second.filter { !it.pendingDelete })
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = 0.8f)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = handleBack) {
                    Icon(Icons.Default.KeyboardDoubleArrowLeft, "Thoát", tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "${(lazyListState.firstVisibleItemIndex + 1).coerceAtMost(uiState.imageUris.size)} / ${uiState.imageUris.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hiển thị tiến độ dịch khi đang dịch
                if (uiState.isTranslating && uiState.totalImagesToTranslate > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${uiState.translationProgress}/${uiState.totalImagesToTranslate}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                AutoScroll(
                    lazyListState = lazyListState,
                    autoScrollEnabled = autoScrollEnabled,
                    scrollSpeed = scrollSpeed,
                    onAutoScrollToggle = { autoScrollEnabled = it },
                    onSpeedChange = { scrollSpeed = it },
                    imageUris = uiState.imageUris,
                    onLoadMoreImages = { viewModel.loadMoreImages() },
                    onShowSpeedSliderChange = { showSpeedSlider = !showSpeedSlider },
                    isLoadingMoreImages = uiState.isLoadingMoreImages
                )
                IconButton(onClick = { showRoomNav = !showRoomNav }) {
                    Icon(
                        imageVector = if (showRoomNav) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showRoomNav) "Ẩn thanh điều hướng" else "Hiện thanh điều hướng",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Box {
                    IconButton(onClick = { showMainMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Tùy chọn", tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(
                        expanded = showMainMenu,
                        onDismissRequest = { showMainMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Translate, null, modifier = Modifier.padding(end = 8.dp))
                                    Text("Dịch")
                                }
                            },
                            onClick = {
                                showTranslationMenu = true
                                showMainMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.padding(end = 8.dp))
                                    Text("Chỉnh sửa bản dịch")
                                }
                            },
                            onClick = {
                                editTranslationMode = !editTranslationMode
                                showMainMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FontDownload, null, modifier = Modifier.padding(end = 8.dp))
                                    Text("Thay đổi font phòng")
                                }
                            },
                            onClick = {
                                showRoomFontDialog = true
                                showMainMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(24.dp)) {
                                        if (uiState.isSavingRoom) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Icon(Icons.Default.Save, null, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Lưu bộ ảnh")
                                }
                            },
                            onClick = {
                                if (!uiState.isSavingRoom) {
                                    dragBlocksMap.forEach { (uri: Uri, blocks: List<DragBlockState>) ->
                                        viewModel.updateTranslatedBlocks(
                                            uri,
                                            blocks.map { dragBlock ->
                                                dragBlock.block.copy(
                                                    fontSize = dragBlock.fontSize ?: dragBlock.block.fontSize, // Lưu fontSize đã chỉnh sửa
                                                    rotation = dragBlock.rotation,
                                                    overlayRotation = dragBlock.overlayRotation,
                                                    shapeType = dragBlock.block.shapeType,
                                                    customOverlayColor = dragBlock.whiteoutColor?.toArgb() ?: dragBlock.block.customOverlayColor,
                                                    customTextColor = dragBlock.textColor?.toArgb()
                                                        ?: dragBlock.block.customTextColor
                                                        ?: computeDefaultTextColor(dragBlock.whiteoutColor?.toArgb() ?: dragBlock.block.customOverlayColor, dragBlock.block.averageBackgroundColor),
                                                    overlayAlpha = dragBlock.overlayAlpha,
                                                    textBoldness = dragBlock.textBoldness,
                                                    overlaySaturation = dragBlock.overlaySaturation,
                                                    textSaturation = dragBlock.textSaturation,
                                                    overlayInset = dragBlock.overlayInset,
                                                    overlayInsetHorizontal = dragBlock.overlayInsetHorizontal,
                                                    overlayInsetVertical = dragBlock.overlayInsetVertical,
                                                    customBorderColor = dragBlock.textBorderColor?.toArgb(),
                                                    borderThickness = dragBlock.textBorderThickness,
                                                    borderAlpha = dragBlock.textBorderAlpha,
                                                    // persist shadow edits too
                                                    customShadowColor = dragBlock.textShadowColor?.toArgb(),
                                                    shadowAlpha = dragBlock.textShadowAlpha,
                                                    shadowRadius = dragBlock.textShadowRadius,
                                                    textGradientColors = dragBlock.textGradientColors,
                                                    textGradientOffsets = dragBlock.textGradientOffsets,
                                                    textGradientType = dragBlock.textGradientType
                                                )
                                            }
                                        )
                                    }
                                    viewModel.saveCurrentRoom()
                                    showMainMenu = false
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(24.dp)) {
                                        if (uiState.isExportingRoom) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Xuất phòng (ZIP)")
                                }
                            },
                            onClick = {
                                if (!uiState.isExportingRoom) {
                                    // Export current room's translated images as a zip
                                    showMainMenu = false
                                    val rid = uiState.roomId
                                    if (rid == null) {
                                        Toast.makeText(context, "Không có phòng để xuất", Toast.LENGTH_SHORT).show()
                                    } else {
                                        coroutineScope.launch {
                                            val path = viewModel.exportRoomAsZip(rid)
                                            if (path != null) {
                                                Toast.makeText(context, "Đã xuất: $path", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Không có ảnh đã dịch để xuất hoặc xuất thất bại", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.padding(end = 8.dp))
                                    Text("Thêm ảnh")
                                }
                            },
                            onClick = {
                                showAddMenu = true
                                showMainMenu = false
                            }
                        )
                        if (uiState.roomId != null) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Edit, null, modifier = Modifier.padding(end = 8.dp))
                                        Text("Đổi tên phòng")
                                    }
                                },
                                onClick = {
                                    showEditTitleDialog = true
                                    showMainMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (uiState.autoTranslateEnabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                            null,
                                            modifier = Modifier.padding(end = 8.dp),
                                            tint = if (uiState.autoTranslateEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                        Text(if (uiState.autoTranslateEnabled) "Tự động dịch ảnh mới: BẬT" else "Tự động dịch ảnh mới: TẮT")
                                    }
                                },
                                onClick = {
                                    viewModel.toggleAutoTranslate()
                                    showMainMenu = false
                                }
                            )

                            // Chế độ dịch cổ trang (Ancient mode) - hiển thị dưới tùy chọn Tự động dịch
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (uiState.isAncientTranslationMode) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                            null,
                                            modifier = Modifier.padding(end = 8.dp),
                                            tint = if (uiState.isAncientTranslationMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                        Text(if (uiState.isAncientTranslationMode) "Chế độ dịch cổ trang: BẬT" else "Chế độ dịch cổ trang: TẮT")
                                    }
                                },
                                onClick = {
                                    viewModel.toggleAncientTranslationMode()
                                    showMainMenu = false
                                }
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showTranslationMenu,
                        onDismissRequest = { showTranslationMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Dịch ngoại tuyến") },
                            onClick = {
                                viewModel.setTranslationMode(TranslationMode.OFFLINE)
                                showTranslationMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dịch trực tuyến") },
                            onClick = {
                                viewModel.setTranslationMode(TranslationMode.ONLINE)
                                showTranslationMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dịch với Gemini AI") },
                            onClick = {
                                viewModel.setTranslationMode(TranslationMode.GEMINI)
                                showTranslationMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dịch tất cả (Gemini)") },
                            onClick = {
                                viewModel.translateAllImagesInRoom(TranslationMode.GEMINI)
                                showTranslationMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dịch với Mistral AI") },
                            onClick = {
                                viewModel.setTranslationMode(TranslationMode.MISTRAL)
                                showTranslationMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dịch tất cả (Mistral)") },
                            onClick = {
                                viewModel.translateAllImagesInRoom(TranslationMode.MISTRAL)
                                showTranslationMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dịch với Z.AI (GLM-4)") },
                            onClick = {
                                viewModel.setTranslationMode(TranslationMode.ZAI)
                                showTranslationMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dịch tất cả (Z.AI)") },
                            onClick = {
                                viewModel.translateAllImagesInRoom(TranslationMode.ZAI)
                                showTranslationMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Tắt") },
                            onClick = {
                                viewModel.setTranslationMode(TranslationMode.OFF)
                                showTranslationMenu = false
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { showAddMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Thêm vào đầu") },
                            onClick = {
                                pickImagesAtStartLauncher.launch(arrayOf("image/*"))
                                showAddMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Thêm vào cuối") },
                            onClick = {
                                pickImagesAtEndLauncher.launch(arrayOf("image/*"))
                                showAddMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Thêm tại vị trí") },
                            onClick = {
                                showInsertAtIndexDialog = true
                                showAddMenu = false
                            }
                        )
                    }
                }
            }
        }
        if (showRoomNav) {
            RoomNavigation(
                allRoomIds = allRoomIds,
                currentRoomId = uiState.roomId,
                onRoomSelected = { roomId -> viewModel.loadRoom(roomId) }
            )
        }
        
        // Speed slider hiển thị bên dưới
        AutoScrollSpeedSlider(
            scrollSpeed = scrollSpeed,
            onSpeedChange = { scrollSpeed = it },
            showSpeedSlider = showSpeedSlider
        )

        ImageViewer(
            imageUris = uiState.imageUris,
            translatedTexts = uiState.translatedTexts,
            translationEnabled = uiState.translationEnabled,
            translatedStatus = uiState.translatedStatus,
            translatingImages = uiState.translatingImages,
            recentlySavedUris = uiState.recentlySavedUris,
            onClearRecentlySavedUri = { uri -> viewModel.clearRecentlySavedUri(uri) },
            reopenEditorUris = uiState.reopenEditorUris,
            onClearReopenEditorUri = { uri -> viewModel.clearReopenEditorUri(uri) },
            onRequestOpenEditor = { uri -> editTranslationMode = true },
            editTranslationMode = editTranslationMode,
            dragBlocksMap = dragBlocksMap,
            onEditTranslationModeToggle = { editTranslationMode = it },
            onSaveTranslation = { uri, blocks ->
                Log.d("ViewerScreen", "[onSaveTranslation] Saving ${blocks.size} blocks for $uri")
                blocks.forEachIndexed { idx, dragBlock ->
                    val textColorHex = try { dragBlock.textColor?.toArgb()?.let { String.format("#%08X", it) } ?: "null" } catch (_: Exception) { "err" }
                    val gradCols = dragBlock.textGradientColors?.joinToString(separator = ",") { c -> String.format("#%08X", c) } ?: "null"
                    Log.d("ViewerScreen", "[onSaveTranslation] Block[$idx] overlayRotation=${dragBlock.overlayRotation} rotation=${dragBlock.rotation} inset=${dragBlock.overlayInset} insetH=${dragBlock.overlayInsetHorizontal} insetV=${dragBlock.overlayInsetVertical} textColor=$textColorHex gradientColors=$gradCols")
                }
                viewModel.updateTranslatedBlocks(uri, blocks.map { dragBlock ->
                    // Bounds đã được cập nhật khi drag trong ImageViewer, không cần cộng offset nữa
                    dragBlock.block.copy(
                        fontSize = dragBlock.fontSize ?: dragBlock.block.fontSize, // Lưu fontSize đã chỉnh sửa
                        rotation = dragBlock.rotation,
                        overlayRotation = dragBlock.overlayRotation,
                        shapeType = dragBlock.block.shapeType,
                        fontFamily = dragBlock.block.fontFamily, // Lưu font family khi save translation
                        customOverlayColor = dragBlock.whiteoutColor?.toArgb(),
                        customTextColor = dragBlock.textColor?.toArgb(),
                        overlayAlpha = dragBlock.overlayAlpha,
                        textBoldness = dragBlock.textBoldness,
                        overlaySaturation = dragBlock.overlaySaturation,
                        textSaturation = dragBlock.textSaturation,
                        lineSpacing = dragBlock.lineSpacing,
                        overlayInset = dragBlock.overlayInset,
                        overlayInsetHorizontal = dragBlock.overlayInsetHorizontal,
                        overlayInsetVertical = dragBlock.overlayInsetVertical,
                        customBorderColor = dragBlock.textBorderColor?.toArgb(),
                        borderThickness = dragBlock.textBorderThickness,
                        borderAlpha = dragBlock.textBorderAlpha,
                        // persist shadow edits as well
                        customShadowColor = dragBlock.textShadowColor?.toArgb(),
                        shadowAlpha = dragBlock.textShadowAlpha,
                        shadowRadius = dragBlock.textShadowRadius,
                        textGradientColors = dragBlock.textGradientColors,
                        textGradientOffsets = dragBlock.textGradientOffsets,
                        textGradientType = dragBlock.textGradientType,
                        // Đánh dấu rằng block này đã được edit manual, không áp dụng merge logic
                        applyMerge = false
                    ) 
                })
            },
            onRetranslateImage = { uri, mode ->
                viewModel.retranslateImage(uri, mode)
            },
            showImageMenu = showImageMenu,
            imageMenuUri = imageMenuUri,
            onImageMenuDismiss = { showImageMenu = false },
            onRemoveImage = { uri ->
                viewModel.removeImageFromRoom(uri)
            },
            onShowImageMenuChange = { showImageMenu = it },
            onImageMenuUriChange = { imageMenuUri = it },
            lazyListState = lazyListState,
            // provide ViewModel accessor so ImageViewer can use stable DB imageId as keys
            getImageIdForUri = viewModel::getImageIdForUri,
            // provide a version accessor so replaced images can be forced to reload
            getImageVersionForUri = viewModel::getImageVersionForUri,
            getReloadTokenForUri = viewModel::getReloadTokenForUri,
            isLoadingMoreImages = uiState.isLoadingMoreImages,
            remainingImagesCount = uiState.remainingImages.size,
            isTextRemovalMode = isTextRemovalMode,
            onToggleTextRemovalMode = { isTextRemovalMode = !isTextRemovalMode },
            onRemoveTextWithMask = { uri, maskBmp ->
                coroutineScope.launch {
                    isRemovingText = true
                    removingTextLocalProgress = "Lấy dữ liệu..."
                    try {
                        val resultUri = com.example.ocrmanga.utils.TextRemovalHelper.removeTextWithMask(
                            context, uri, maskBmp
                        ) { progress -> removingTextLocalProgress = progress }
                        if (resultUri != null) {
                            // Use replaceImageUri with persist=false to make this a temporary replacement
                            viewModel.replaceImageUri(uri, resultUri, persist = false)
                            Toast.makeText(context, "Đã xóa text thành công!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Lỗi khi xóa text", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("ViewerScreen", "Error removing text", e)
                        Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        isRemovingText = false
                        removingTextLocalProgress = ""
                    }
                }
            },
            brushSize = brushSize,
            onBrushSizeChange = { brushSize = it }
        )
        Dialogs(
            showInsertAtIndexDialog = showInsertAtIndexDialog,
            insertAtIndex = insertAtIndex,
            onInsertAtIndexChange = { insertAtIndex = it },
            onInsertAtIndexConfirm = {
                val index = insertAtIndex.toIntOrNull()
                if (index != null && index in 1..(uiState.imageUris.size + 1)) {
                    insertAtIndex = (index - 1).toString()
                    pickImagesAtIndexLauncher.launch(arrayOf("image/*"))
                    showInsertAtIndexDialog = false
                } else {
                    Toast.makeText(context, "Vui lòng nhập vị trí hợp lệ (1-${uiState.imageUris.size + 1})", Toast.LENGTH_SHORT).show()
                }
            },
            onInsertAtIndexDismiss = {
                showInsertAtIndexDialog = false
                insertAtIndex = ""
            },
            showExitConfirmDialog = showExitConfirmDialog,
            onExitConfirm = {
                showExitConfirmDialog = false
                pendingBack = false
                viewModel.clearSessionAndImages(uiState.roomId != null)
                shouldNavigateBackAfterClear = true
            },
            onExitDismiss = {
                showExitConfirmDialog = false
                pendingBack = false
            },
            showEditTitleDialog = showEditTitleDialog,
            editTitleText = editTitleText,
            onEditTitleChange = { editTitleText = it },
            onEditTitleConfirm = {
                if (editTitleText.isNotBlank()) {
                    uiState.roomId?.let { roomId ->
                        viewModel.updateRoomTitle(roomId, editTitleText)
                    }
                    showEditTitleDialog = false
                }
            },
            onEditTitleDismiss = { showEditTitleDialog = false },
            showImageMenu = showImageMenu,
            imageMenuUri = imageMenuUri,
            onImageMenuDismiss = { showImageMenu = false },
            onRemoveImage = { uri ->
                viewModel.removeImageFromRoom(uri)
                Toast.makeText(context, "Đã xóa ảnh khỏi trang", Toast.LENGTH_SHORT).show()
            },
            onRetranslateImage = { uri, mode ->
                viewModel.retranslateImage(uri, mode)
                coroutineScope.launch {
                    while (true) {
                        val status = viewModel.uiState.value.translatedStatus[uri]
                        if (status == true) break
                        delay(200)
                    }
                    Toast.makeText(context, "Dịch lại ảnh hoàn tất!", Toast.LENGTH_SHORT).show()
                }
            },
            imageUris = uiState.imageUris,
            viewModel = viewModel
        )

        // Dialog thay đổi font cho cả phòng
        if (showRoomFontDialog) {
            val allBlocks = uiState.translatedTexts.values.flatMap { it.second }
            val currentMostUsed = allBlocks.groupingBy { it.fontFamily }.eachCount().maxByOrNull { it.value }?.key ?: FontRegistry.fontOptions.first().first
            var selectedFontKey by remember { mutableStateOf(currentMostUsed) }

            AlertDialog(
                onDismissRequest = { showRoomFontDialog = false },
                title = { Text("Thay đổi font phòng") },
                text = {
                    Column {
                        Text("Chọn font sẽ áp dụng cho tất cả bản dịch trong phòng:")
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(FontRegistry.fontOptions.size) { idx ->
                                val (key, ff) = FontRegistry.fontOptions[idx]
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFontKey = key }
                                    .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = (selectedFontKey == key),
                                        onClick = { selectedFontKey = key }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(FontRegistry.displayNameFor(key), style = androidx.compose.ui.text.TextStyle(fontFamily = ff))
                                        Text(key, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.updateGlobalFont(selectedFontKey)
                        showRoomFontDialog = false
                    }) { Text("Áp dụng") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showRoomFontDialog = false }) { Text("Hủy") }
                }
            )
        }

    } // end Column

    // Text Removal Preview Dialog - shows mask overlay before confirming removal
    if (uiState.showTextRemovalPreview && uiState.textRemovalPreviewBitmap != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelTextRemovalPreview() },
            title = {
                Text(
                    "Xác nhận vùng xóa text",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Các vùng bôi đỏ sẽ được xóa text:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Preview image with mask overlay - scrollable
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = uiState.textRemovalPreviewBitmap!!.asImageBitmap(),
                            contentDescription = "Preview vùng xóa text",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "Ấn \"Xác nhận\" để bắt đầu xóa text gốc",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmTextRemoval() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Xác nhận xóa")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelTextRemovalPreview() }) {
                    Text("Hủy")
                }
            }
        )
    }

    // Text Removal Loading Popup - overlays on top of Column
    if (isRemovingText || uiState.isRemovingText) {
        // Determine progress text: prefer ViewModel progress (auto mode), fallback to local (brush mode)
        val progressText = when {
            uiState.removingTextProgress.isNotEmpty() -> uiState.removingTextProgress
            removingTextLocalProgress.isNotEmpty() -> removingTextLocalProgress
            else -> "Đang xóa text..."
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.padding(32.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Vui lòng đợi trong giây lát",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    } // end Box
}

@Composable
fun RoomNavigation(
    allRoomIds: List<Long>,
    currentRoomId: Long?,
    onRoomSelected: (Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (allRoomIds.isNotEmpty() && currentRoomId != null) {
            val currentIndex = allRoomIds.indexOf(currentRoomId)
            if (currentIndex > 0) {
                IconButton(onClick = { onRoomSelected(allRoomIds[currentIndex - 1]) }) {
                    Icon(Icons.Default.ArrowBack, "Quay lại", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
        Text(text = currentRoomId?.let { "Phòng $it" } ?: "Chưa có phòng")
        if (allRoomIds.isNotEmpty() && currentRoomId != null) {
            val currentIndex = allRoomIds.indexOf(currentRoomId)
            if (currentIndex < allRoomIds.size - 1) {
                IconButton(onClick = { onRoomSelected(allRoomIds[currentIndex + 1]) }) {
                    Icon(Icons.Default.ArrowForward, "Tiếp theo", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}