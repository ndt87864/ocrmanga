package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val uiState by viewModel.uiState.collectAsState()
    val allRoomIds by viewModel.allRoomIds.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(key1 = imageUris, key2 = roomId) {
        if (imageUris.isNotEmpty()) {
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

    val pickImagesAtStartLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 1000),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.addNewImageUrisAtStart(uris)
                Toast.makeText(context, "Đã thêm ${uris.size} ảnh vào đầu", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val pickImagesAtEndLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 1000),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.addNewImageUris(uris)
                Toast.makeText(context, "Đã thêm ${uris.size} ảnh vào cuối", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val pickImagesAtIndexLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 1000),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                val index = insertAtIndex.toIntOrNull() ?: 0
                viewModel.addNewImageUrisAtIndex(uris, index)
                Toast.makeText(context, "Đã thêm ${uris.size} ảnh vào vị trí ${index + 1}", Toast.LENGTH_SHORT).show()
                insertAtIndex = ""
            }
        }
    )

    val handleBack: () -> Unit = {
        if (uiState.roomId == null && uiState.imageUris.isNotEmpty()) {
            showExitConfirmDialog = true
            pendingBack = true
        } else {
            onNavigateBack()
        }
    }

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
                AutoScroll(
                    lazyListState = lazyListState,
                    autoScrollEnabled = autoScrollEnabled,
                    scrollSpeed = scrollSpeed,
                    onAutoScrollToggle = { autoScrollEnabled = it },
                    onSpeedChange = { scrollSpeed = it },
                    imageUris = uiState.imageUris,
                    onLoadMoreImages = { viewModel.loadMoreImages() },
                    onShowSpeedSliderChange = { showSpeedSlider = !showSpeedSlider }
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
                                    Icon(Icons.Default.Save, null, modifier = Modifier.padding(end = 8.dp))
                                    Text("Lưu bộ ảnh")
                                }
                            },
                            onClick = {
                                dragBlocksMap.forEach { (uri: Uri, blocks: List<DragBlockState>) ->
                                    viewModel.updateTranslatedBlocks(
                                        uri,
                                        blocks.map { dragBlock ->
                                            dragBlock.block.copy(
                                                rotation = dragBlock.rotation,
                                                shapeType = dragBlock.block.shapeType
                                            )
                                        }
                                    )
                                }
                                viewModel.saveCurrentRoom()
                                showMainMenu = false
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
                            text = { Text("Dịch với Mistral AI") },
                            onClick = {
                                viewModel.setTranslationMode(TranslationMode.MISTRAL)
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
                                pickImagesAtStartLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                showAddMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Thêm vào cuối") },
                            onClick = {
                                pickImagesAtEndLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        
        // Hiển thị bộ đếm thời gian khi đang dịch
        if (uiState.translationTimer > 0 && uiState.currentTranslatingImage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    val minutes = uiState.translationTimer / 60
                    val seconds = uiState.translationTimer % 60
                    val timeString = if (minutes > 0) {
                        "${minutes}m ${seconds}s"
                    } else {
                        "${seconds}s"
                    }
                    val imageProgress = "${uiState.currentTranslatingImageIndex}/${uiState.imageUris.size}"
                    Text(
                        text = "Đang dịch ảnh $imageProgress... ($timeString)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        
        ImageViewer(
            imageUris = uiState.imageUris,
            translatedTexts = uiState.translatedTexts,
            translationEnabled = uiState.translationEnabled,
            editTranslationMode = editTranslationMode,
            dragBlocksMap = dragBlocksMap,
            onEditTranslationModeToggle = { editTranslationMode = it },
            onSaveTranslation = { uri, blocks ->
                viewModel.updateTranslatedBlocks(uri, blocks.map { 
                    it.block.copy(
                        rotation = it.rotation,
                        shapeType = it.block.shapeType
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
            lazyListState = lazyListState
        )
        Dialogs(
            showInsertAtIndexDialog = showInsertAtIndexDialog,
            insertAtIndex = insertAtIndex,
            onInsertAtIndexChange = { insertAtIndex = it },
            onInsertAtIndexConfirm = {
                val index = insertAtIndex.toIntOrNull()
                if (index != null && index in 1..(uiState.imageUris.size + 1)) {
                    insertAtIndex = (index - 1).toString()
                    pickImagesAtIndexLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                viewModel.clearSessionAndImages()
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
    }
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