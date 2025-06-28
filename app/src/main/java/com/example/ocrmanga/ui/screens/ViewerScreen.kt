package com.example.ocrmanga.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Rect
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.viewmodels.ViewerViewModel
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.min
import kotlin.math.max

@Composable
fun ViewerScreen(
    imageUris: List<String> = emptyList(),
    roomId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel()
) {
    var imageToDelete by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(key1 = imageUris, key2 = roomId) {
        if (imageUris.isNotEmpty()) {
            viewModel.setImageUris(imageUris.map { Uri.parse(it) }, isNew = true)
        } else if (roomId != null) {
            viewModel.loadRoom(roomId)
        }
    }
    val uiState by viewModel.uiState.collectAsState()
    val allRoomIds by viewModel.allRoomIds.collectAsState()
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(false) }
    var scrollSpeed by remember { mutableStateOf(4f) }
    var showSpeedSlider by remember { mutableStateOf(false) }
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

    LaunchedEffect(shouldNavigateBackAfterClear, uiState.imageUris) {
        if (shouldNavigateBackAfterClear && uiState.imageUris.isEmpty()) {
            shouldNavigateBackAfterClear = false
            onNavigateBack()
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

    LaunchedEffect(autoScrollEnabled, scrollSpeed) {
        if (autoScrollEnabled) {
            while (true) {
                val currentIndex = lazyListState.firstVisibleItemIndex
                val currentOffset = lazyListState.firstVisibleItemScrollOffset
                val totalItems = uiState.imageUris.size

                if (currentIndex >= totalItems - 1 && currentOffset >= 0) {
                    autoScrollEnabled = false
                    break
                }

                val speed = (scrollSpeed * 2).toInt()
                lazyListState.scrollToItem(
                    currentIndex,
                    currentOffset + if (speed > 0) speed else 1
                )
                delay(16)
            }
        }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }.collect { index ->
            if (index >= uiState.imageUris.size - ViewerViewModel.BATCH_SIZE / 2 && uiState.remainingImages.isNotEmpty()) {
                viewModel.loadMoreImages()
            }
        }
    }

    LaunchedEffect(uiState.translationEnabled, uiState.isTranslating, uiState.translationProgress, uiState.totalImagesToTranslate) {
        if (uiState.translationEnabled && uiState.isTranslating) {
            while (uiState.isTranslating) {
                val progress = uiState.translationProgress
                val total = uiState.totalImagesToTranslate
                val percentage = if (total > 0) (progress * 100 / total) else 0
                Toast.makeText(context, "Đang dịch... ($progress/$total, $percentage%)", Toast.LENGTH_SHORT).show()
                delay(1000)
            }
        }
    }

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
                Box(
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { showSpeedSlider = !showSpeedSlider },
                            onTap = { autoScrollEnabled = !autoScrollEnabled }
                        )
                    }
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = if (autoScrollEnabled) "Dừng cuộn" else "Tự động cuộn",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
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
                                    Icon(Icons.Default.Save, null, modifier = Modifier.padding(end = 8.dp))
                                    Text("Lưu bộ ảnh")
                                }
                            },
                            onClick = { 
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (allRoomIds.isNotEmpty() && uiState.roomId != null) {
                    val currentIndex = allRoomIds.indexOf(uiState.roomId)
                    if (currentIndex > 0) {
                        IconButton(onClick = { viewModel.loadRoom(allRoomIds[currentIndex - 1]) }) {
                            Icon(Icons.Default.ArrowBack, "Quay lại", tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(48.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
                Text(text = uiState.roomId?.let { "Phòng $it" } ?: "Chưa có phòng")
                if (allRoomIds.isNotEmpty() && uiState.roomId != null) {
                    val currentIndex = allRoomIds.indexOf(uiState.roomId)
                    if (currentIndex < allRoomIds.size - 1) {
                        IconButton(onClick = { viewModel.loadRoom(allRoomIds[currentIndex + 1]) }) {
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

        if (showSpeedSlider) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tốc độ cuộn:", modifier = Modifier.padding(end = 8.dp))
                Slider(
                    value = scrollSpeed,
                    onValueChange = { scrollSpeed = it },
                    valueRange = 0f..9f,
                    steps = 8,
                    modifier = Modifier.weight(1f)
                )
                Text(text = scrollSpeed.toInt().toString(), modifier = Modifier.padding(start = 8.dp))
            }
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(uiState.imageUris) { uri ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(uri) {
                            detectTapGestures(
                                onLongPress = {
                                    imageToDelete = uri
                                }
                            )
                        }
                ) {
                    var imageWidth by remember { mutableStateOf(0f) }
                    var imageHeight by remember { mutableStateOf(0f) }
                    var originalImageWidth by remember { mutableStateOf(0f) }
                    var originalImageHeight by remember { mutableStateOf(0f) }
                    var isImageLoaded by remember { mutableStateOf(false) }
                    var imageLoadState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
                    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

                    LaunchedEffect(uri) {
                        try {
                            val (width, height) = getImageDimensions(context, uri)
                            originalImageWidth = width.toFloat()
                            originalImageHeight = height.toFloat()
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                bitmap = BitmapFactory.decodeStream(inputStream)
                            }
                            isImageLoaded = true
                        } catch (e: IOException) {
                            originalImageWidth = 1280f
                            originalImageHeight = 1808f
                            isImageLoaded = true
                            Log.e("ViewerScreen", "Failed to load image dimensions for $uri", e)
                        }
                    }

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uri)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build(),
                        contentDescription = "Ảnh manga",
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                imageWidth = coordinates.size.width.toFloat()
                                imageHeight = coordinates.size.height.toFloat()
                            },
                        contentScale = ContentScale.FillWidth,
                        onState = { state -> imageLoadState = state }
                    )

                    if (uiState.translationEnabled && uiState.translatedTexts.containsKey(uri) && isImageLoaded && imageLoadState is AsyncImagePainter.State.Success) {
                        val (fullText, translatedBlocks) = uiState.translatedTexts[uri] ?: ("" to emptyList())
                        if (translatedBlocks.isNotEmpty()) {
                            val blocksByBubble = translatedBlocks.filter { it.bubbleId != null }
                                .groupBy { it.bubbleId }
                                .values
                                .ifEmpty { listOf(translatedBlocks) }

                            Canvas(
                                modifier = Modifier.matchParentSize().drawWithCache {
                                    val allBubbleRegions = blocksByBubble.map { bubbleBlocks ->
                                        mergeOverlappingRegions(
                                            bubbleBlocks.mapNotNull { block ->
                                                val blockImageWidth = block.originalImageWidth?.toFloat() ?: originalImageWidth
                                                val blockImageHeight = block.originalImageHeight?.toFloat() ?: originalImageHeight
                                                val scaleX = imageWidth / blockImageWidth
                                                val scaleY = imageHeight / blockImageHeight
                                                // Tính toán offset để căn giữa ảnh nếu cần
                                                val scaledImageHeight = blockImageHeight * scaleX
                                                val scaledImageWidth = blockImageWidth * scaleY
                                                val offsetY = if (imageHeight > scaledImageHeight) (imageHeight - scaledImageHeight) / 2 else 0f
                                                val offsetX = if (imageWidth > scaledImageWidth) (imageWidth - scaledImageWidth) / 2 else 0f
                                                if (block.text.isNotBlank()) {
                                                    val bounds = block.bounds
                                                    val scaledLeft = (bounds.left * scaleX) + offsetX
                                                    val scaledTop = (bounds.top * scaleY) + offsetY
                                                    val scaledWidth = (bounds.width() * scaleX).toFloat()
                                                    val scaledHeight = (bounds.height() * scaleY).toFloat()
                                                    val minFontSize = if (blockImageWidth < 1500f) 12f else 16f
                                                    val optimalFontSize = calculateOptimalFontSize(
                                                        block.text, scaledWidth, scaledHeight, minFontSize
                                                    )
                                                    val padding = optimalFontSize * if (blockImageWidth < 1500f) 0.15f else 0.2f
                                                    Triple(
                                                        block,
                                                        Rect(
                                                            scaledLeft - padding,
                                                            scaledTop - padding,
                                                            scaledLeft + scaledWidth + padding,
                                                            scaledTop + scaledHeight + padding
                                                        ),
                                                        optimalFontSize
                                                    )
                                                } else null
                                            },
                                            imageWidth,
                                            bitmap
                                        )
                                    }
                                    onDrawBehind {
                                        allBubbleRegions.forEach { regions ->
                                            regions.forEach { triple ->
                                                val rect = triple.component2()
                                                advancedTextRemoval(rect, originalImageWidth, originalImageHeight, bitmap)
                                            }
                                            regions.forEach { triple ->
                                                val block = triple.component1()
                                                val rect = triple.component2()
                                                val fontSize = triple.component3()
                                                if (block.text.isNotBlank()) {
                                                    val bgColor = bitmap?.let { estimateBackgroundColorFromBitmap(rect, it) }
                                                        ?: estimateBackgroundColor(rect)
                                                    val textColor = getTextColorBasedOnBackground(bgColor)
                                                    drawText(
                                                        text = block.text,
                                                        x = rect.left,
                                                        y = rect.top,
                                                        width = rect.width,
                                                        height = rect.height,
                                                        color = textColor,
                                                        fontSize = fontSize,
                                                        isVertical = block.isVertical
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {}
                        }
                    }
                }
            }
        }

        if (showInsertAtIndexDialog) {
            AlertDialog(
                onDismissRequest = { 
                    showInsertAtIndexDialog = false
                    insertAtIndex = ""
                },
                title = { Text("Chèn ảnh vào vị trí") },
                text = {
                    Column {
                        Text("Nhập vị trí (1-${uiState.imageUris.size + 1}):")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = insertAtIndex,
                            onValueChange = { value -> 
                                val filtered = value.filter { it.isDigit() }
                                insertAtIndex = filtered
                            },
                            label = { Text("Vị trí") },
                            placeholder = { Text("1") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (insertAtIndex.isNotEmpty()) {
                            val index = insertAtIndex.toIntOrNull()
                            if (index == null || index !in 1..(uiState.imageUris.size + 1)) {
                                Text(
                                    text = "Vị trí phải từ 1 đến ${uiState.imageUris.size + 1}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val index = insertAtIndex.toIntOrNull()
                            if (index != null && index in 1..(uiState.imageUris.size + 1)) {
                                insertAtIndex = (index - 1).toString()
                                pickImagesAtIndexLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                showInsertAtIndexDialog = false
                            } else {
                                Toast.makeText(context, "Vui lòng nhập vị trí hợp lệ (1-${uiState.imageUris.size + 1})", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = insertAtIndex.isNotEmpty() && 
                                  insertAtIndex.toIntOrNull()?.let { it in 1..(uiState.imageUris.size + 1) } == true
                    ) {
                        Text("Chọn ảnh")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showInsertAtIndexDialog = false
                        insertAtIndex = ""
                    }) {
                        Text("Hủy")
                    }
                }
            )
        }

        if (showExitConfirmDialog) {
            AlertDialog(
                onDismissRequest = {
                    showExitConfirmDialog = false
                    pendingBack = false
                },
                title = { Text("Xác nhận thoát") },
                text = { Text("Bạn có chắc chắn muốn thoát? Tất cả ảnh và dữ liệu phiên này sẽ bị xóa.") },
                confirmButton = {
                    TextButton(onClick = {
                        showExitConfirmDialog = false
                        pendingBack = false
                        viewModel.clearSessionAndImages()
                        shouldNavigateBackAfterClear = true
                    }) { Text("Thoát") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showExitConfirmDialog = false
                        pendingBack = false
                    }) { Text("Hủy") }
                }
            )
        }

        if (uiState.roomId != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = { showEditTitleDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Đổi tên", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Đổi tên phòng")
                }
            }
        }

        if (showEditTitleDialog && uiState.roomId != null) {
            AlertDialog(
                onDismissRequest = { showEditTitleDialog = false },
                title = { Text("Đổi tên phòng") },
                text = {
                    OutlinedTextField(
                        value = editTitleText,
                        onValueChange = { editTitleText = it },
                        label = { Text("Tên mới") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (editTitleText.isNotBlank()) {
                            uiState.roomId?.let { roomId ->
                                viewModel.updateRoomTitle(roomId, editTitleText)
                            }
                            showEditTitleDialog = false
                        }
                    }) { Text("Lưu") }
                },
                dismissButton = {
                    TextButton(onClick = { showEditTitleDialog = false }) { Text("Hủy") }
                }
            )
        }

        if (imageToDelete != null) {
            AlertDialog(
                onDismissRequest = { imageToDelete = null },
                title = { Text("Xóa ảnh khỏi phòng?") },
                text = { Text("Bạn có chắc chắn muốn xóa ảnh này khỏi phòng không?") },
                confirmButton = {
                    TextButton(onClick = {
                        imageToDelete?.let { viewModel.removeImageFromRoom(it) }
                        imageToDelete = null
                    }) { Text("Xóa") }
                },
                dismissButton = {
                    TextButton(onClick = { imageToDelete = null }) { Text("Hủy") }
                }
            )
        }
    }
}

private fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) {
            return options.outWidth to options.outHeight
        }
        throw IOException("Kích thước ảnh không hợp lệ")
    } catch (e: Exception) {
        Log.e("ViewerScreen", "Error getting image dimensions for $uri", e)
        return 1280 to 1808
    }
}

private fun estimateBackgroundColorFromBitmap(
    rect: Rect,
    bitmap: Bitmap,
    margin: Int = 8
): Color {
    val left = rect.left.toInt().coerceAtLeast(0)
    val top = rect.top.toInt().coerceAtLeast(0)
    val right = rect.right.toInt().coerceAtMost(bitmap.width - 1)
    val bottom = rect.bottom.toInt().coerceAtMost(bitmap.height - 1)

    var totalRed = 0L
    var totalGreen = 0L
    var totalBlue = 0L
    var pixelCount = 0

    for (x in left - margin..right + margin step 2) {
        for (y in top - margin..bottom + margin step 2) {
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                totalRed += android.graphics.Color.red(pixel)
                totalGreen += android.graphics.Color.green(pixel)
                totalBlue += android.graphics.Color.blue(pixel)
                pixelCount++
            }
        }
    }

    return if (pixelCount > 0) {
        Color(
            red = (totalRed / pixelCount).toInt().coerceIn(0, 255),
            green = (totalGreen / pixelCount).toInt().coerceIn(0, 255),
            blue = (totalBlue / pixelCount).toInt().coerceIn(0, 255)
        )
    } else {
        Color.White
    }
}

private fun findBackgroundRegion(rect: Rect, bitmap: Bitmap): Rect {
    val margin = 8
    val left = rect.left.toInt().coerceAtLeast(0)
    val top = rect.top.toInt().coerceAtLeast(0)
    val right = rect.right.toInt().coerceAtMost(bitmap.width - 1)
    val bottom = rect.bottom.toInt().coerceAtMost(bitmap.height - 1)

    val centerX = ((left + right) / 2).coerceIn(0, bitmap.width - 1)
    val centerY = ((top + bottom) / 2).coerceIn(0, bitmap.height - 1)
    val bgColor = bitmap.getPixel(centerX, centerY)

    var bgLeft = left
    var bgRight = right
    var bgTop = top
    var bgBottom = bottom

    for (x in left downTo 0) {
        if (x < bitmap.width && bitmap.getPixel(x, centerY) != bgColor) break
        bgLeft = x
    }
    for (x in right until bitmap.width) {
        if (x < bitmap.width && bitmap.getPixel(x, centerY) != bgColor) break
        bgRight = x
    }
    for (y in top downTo 0) {
        if (y < bitmap.height && bitmap.getPixel(centerX, y) != bgColor) break
        bgTop = y
    }
    for (y in bottom until bitmap.height) {
        if (y < bitmap.height && bitmap.getPixel(centerX, y) != bgColor) break
        bgBottom = y
    }

    return Rect(
        bgLeft.toFloat(),
        bgTop.toFloat(),
        (bgRight + 1).toFloat(),
        (bgBottom + 1).toFloat()
    )
}

private fun getTextColorBasedOnBackground(bgColor: Color): Color {
    val luminance = 0.299f * bgColor.red + 0.587f * bgColor.green + 0.114f * bgColor.blue
    return if (luminance > 0.5f) Color.Black else Color.White
}

private fun mergeOverlappingRegions(
    regions: List<Triple<TextBlockInfo, Rect, Float>>,
    imageWidth: Float,
    bitmap: Bitmap? = null
): List<Triple<TextBlockInfo, Rect, Float>> {
    if (regions.isEmpty()) return emptyList()

    val sortedRegions = regions.sortedWith { (blockA, rectA, _), (blockB, rectB, _) ->
        if (blockA.isVertical == blockB.isVertical && blockA.isVertical) {
            compareValuesBy(rectB, rectA, { it.right }, { it.top })
        } else {
            compareValuesBy(rectA, rectB, { it.top }, { it.left }) // Fixed: Changed 'Douit.top' to 'rectA.top'
        }
    }.toMutableList()

    val resultRegions = mutableListOf<Triple<TextBlockInfo, Rect, Float>>()
    val processed = BooleanArray(sortedRegions.size) { false }

    fun isIntersect(a: Rect, b: Rect): Boolean {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    }

    fun isIntersectOrSameLine(a: Rect, b: Rect, isVertical: Boolean): Boolean {
        if (isVertical) {
            return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
        } else {
            val verticalOverlap = a.top < b.bottom && a.bottom > b.top
            val topDiff = kotlin.math.abs(a.top - b.top)
            val avgHeight = ((a.height + b.height) / 2f).coerceAtLeast(1f)
            val sameLine = topDiff < avgHeight * 0.2f
            return (a.left < b.right && a.right > b.left && verticalOverlap) || sameLine
        }
    }

    for (i in sortedRegions.indices) {
        if (processed[i]) continue
        var (currentBlock, currentRect, currentFontSize) = sortedRegions[i]
        processed[i] = true
        var merged = false

        val bgRect = bitmap?.let { findBackgroundRegion(currentRect, it) } ?: currentRect

        for (j in sortedRegions.indices) {
            if (processed[j] || i == j) continue
            val (otherBlock, otherRect, otherFontSize) = sortedRegions[j]
            if (currentBlock.isVertical != otherBlock.isVertical) continue

            val isOverlap = isIntersect(currentRect, otherRect)
            val isSameLine = if (!currentBlock.isVertical) {
                val topDiff = kotlin.math.abs(currentRect.top - otherRect.top)
                val avgHeight = ((currentRect.height + otherRect.height) / 2f).coerceAtLeast(1f)
                topDiff < avgHeight * 0.2f
            } else {
                false
            }

            if (isOverlap && !isSameLine) {
                val offset = if (!currentBlock.isVertical) currentRect.width * 0.1f + 2f else currentRect.height * 0.1f + 2f
                if (!currentBlock.isVertical) {
                    val newRect = Rect(
                        otherRect.left + offset,
                        otherRect.top,
                        otherRect.right + offset,
                        otherRect.bottom
                    )
                    sortedRegions[j] = Triple(
                        otherBlock.copy(
                            bounds = android.graphics.Rect(
                                (otherBlock.bounds.left + offset).toInt(),
                                otherBlock.bounds.top,
                                (otherBlock.bounds.right + offset).toInt(),
                                otherBlock.bounds.bottom
                            )
                        ),
                        newRect,
                        otherFontSize
                    )
                } else {
                    val newRect = Rect(
                        otherRect.left,
                        otherRect.top + offset,
                        otherRect.right,
                        otherRect.bottom + offset
                    )
                    sortedRegions[j] = Triple(
                        otherBlock.copy(
                            bounds = android.graphics.Rect(
                                otherBlock.bounds.left,
                                (otherBlock.bounds.top + offset).toInt(),
                                otherBlock.bounds.right,
                                (otherBlock.bounds.bottom + offset).toInt()
                            )
                        ),
                        newRect,
                        otherFontSize
                    )
                }
                continue
            }

            if (isIntersectOrSameLine(currentRect, otherRect, currentBlock.isVertical)) {
                if (regions.isNotEmpty() && !regions.first().first.isVertical) {
                    return regions
                }
                val mergedRect = Rect(
                    min(currentRect.left, otherRect.left),
                    min(currentRect.top, otherRect.top),
                    max(currentRect.right, otherRect.right),
                    max(currentRect.bottom, otherRect.bottom)
                )
                val limitedMergedRect = bitmap?.let { limitRectToBackground(mergedRect, findBackgroundRegion(mergedRect, it)) } ?: mergedRect
                val mergedWidth = limitedMergedRect.width
                val mergedHeight = limitedMergedRect.height
                val minFontSize = min(currentFontSize, otherFontSize)

                val mergedText: String
                val primaryBlock: TextBlockInfo

                if (currentBlock.isVertical) {
                    val isCurrentRighter = currentRect.right > otherRect.right
                    val rightBlock = if (isCurrentRighter) currentBlock else otherBlock
                    val leftBlock = if (isCurrentRighter) otherBlock else currentBlock
                    mergedText = "${rightBlock.text} ${leftBlock.text}"
                    primaryBlock = rightBlock
                } else {
                    val isCurrentUpper = currentRect.top < otherRect.top
                    val upperBlock = if (isCurrentUpper) currentBlock else otherBlock
                    val lowerBlock = if (isCurrentUpper) otherBlock else currentBlock
                    mergedText = "${upperBlock.text}\n${lowerBlock.text}"
                    primaryBlock = upperBlock
                }

                val optimalFontSize = calculateOptimalFontSize(
                    text = mergedText,
                    width = if (currentBlock.isVertical) mergedHeight else mergedWidth,
                    height = if (currentBlock.isVertical) mergedWidth else mergedHeight,
                    minFontSize = minFontSize
                )

                currentBlock = primaryBlock.copy(
                    text = mergedText,
                    bounds = android.graphics.Rect(
                        limitedMergedRect.left.toInt(),
                        limitedMergedRect.top.toInt(),
                        limitedMergedRect.right.toInt(),
                        limitedMergedRect.bottom.toInt()
                    ),
                    fontSize = optimalFontSize
                )
                currentRect = limitedMergedRect
                currentFontSize = optimalFontSize
                processed[j] = true
                merged = true
            }
        }

        resultRegions.add(Triple(currentBlock, currentRect, currentFontSize))
        if (merged) {
            processed[i] = false
            sortedRegions[i] = Triple(currentBlock, currentRect, currentFontSize)
        }
    }

    var changed: Boolean
    var loopCount = 0
    var dynamicOffset = 16f
    do {
        changed = false
        for (i in resultRegions.indices) {
            val (blockA, rectA, fontSizeA) = resultRegions[i]
            val (wrappedTextA, fontSizeFixedA) = adjustWhiteoutBounds(blockA.text, rectA.width, rectA.height, fontSizeA, blockA.isVertical)
            val linesA = wrapText(wrappedTextA, rectA.width, fontSizeFixedA)
            val lineHeightA = fontSizeFixedA * 1.2f
            val textHeightA = linesA.size * lineHeightA
            val safeRectA = Rect(
                rectA.left - 12f,
                rectA.top - 12f,
                rectA.right + 12f,
                rectA.top + textHeightA + 12f
            )
            for (j in resultRegions.indices) {
                if (i == j) continue
                val (blockB, rectB, fontSizeB) = resultRegions[j]
                if (blockA.isVertical != blockB.isVertical) continue
                val (wrappedTextB, fontSizeFixedB) = adjustWhiteoutBounds(blockB.text, rectB.width, rectB.height, fontSizeB, blockB.isVertical)
                val linesB = wrapText(wrappedTextB, rectB.width, fontSizeFixedB)
                val lineHeightB = fontSizeFixedB * 1.2f
                val textHeightB = linesB.size * lineHeightB
                val safeRectB = Rect(
                    rectB.left - 12f,
                    rectB.top - 12f,
                    rectB.right + 12f,
                    rectB.top + textHeightB + 12f
                )
                val isOverlap = isIntersect(safeRectA, safeRectB)
                if (isOverlap) {
                    val offset = dynamicOffset + max(safeRectA.height, safeRectB.height) * 0.2f
                    val tryDownRect = Rect(
                        rectB.left,
                        rectB.top + offset,
                        rectB.right,
                        rectB.bottom + offset
                    )
                    val tryDownSafe = Rect(
                        tryDownRect.left - 12f,
                        tryDownRect.top - 12f,
                        tryDownRect.right + 12f,
                        tryDownRect.top + textHeightB + 12f
                    )
                    val stillOverlap = isIntersect(safeRectA, tryDownSafe)
                    val newRect = if (!stillOverlap) {
                        tryDownRect
                    } else {
                        Rect(
                            rectB.left + offset,
                            rectB.top,
                            rectB.right + offset,
                            rectB.bottom
                        )
                    }
                    val limitedNewRect = bitmap?.let { limitRectToBackground(newRect, findBackgroundRegion(newRect, it)) } ?: newRect
                    resultRegions[j] = Triple(
                        blockB.copy(
                            bounds = android.graphics.Rect(
                                limitedNewRect.left.toInt(),
                                limitedNewRect.top.toInt(),
                                limitedNewRect.right.toInt(),
                                limitedNewRect.bottom.toInt()
                            ),
                            fontSize = fontSizeFixedB
                        ),
                        limitedNewRect,
                        fontSizeFixedB
                    )
                    changed = true
                }
            }
        }
        loopCount++
        if (loopCount > 10 && changed) dynamicOffset *= 1.5f
    } while (changed && loopCount < 30)
    return resultRegions
}

private fun calculateOptimalFontSize(
    text: String,
    width: Float,
    height: Float,
    minFontSize: Float = 12f,
    maxFontSize: Float = 100f
): Float {
    if (text.isBlank() || width <= 0 || height <= 0) return minFontSize

    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textAlign = android.graphics.Paint.Align.LEFT
    }

    var low = minFontSize
    var high = maxFontSize
    var optimalFontSize = minFontSize

    repeat(10) {
        val mid = (low + high) / 2
        paint.textSize = mid
        val wrappedLines = wrapText(text, width * 0.95f, mid)
        val fontMetrics = paint.fontMetrics
        val lineHeight = fontMetrics.descent - fontMetrics.ascent
        val textHeight = wrappedLines.size * lineHeight
        val maxLineWidth = wrappedLines.maxOfOrNull { line ->
            val bounds = android.graphics.Rect()
            paint.getTextBounds(line, 0, line.length, bounds)
            bounds.width().toFloat()
        } ?: 0f

        if (maxLineWidth <= width * 0.95f && textHeight <= height * 0.95f) {
            optimalFontSize = mid
            low = mid + 0.1f
        } else {
            high = mid - 0.1f
        }
    }

    return optimalFontSize.coerceAtLeast(minFontSize)
}

private fun DrawScope.drawText(
    text: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    color: Color,
    fontSize: Float,
    isVertical: Boolean
) {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.color = color.toArgb()
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.LEFT
    }

    val lines = wrapText(text, width, fontSize)
    val fontMetrics = paint.fontMetrics
    val lineHeight = fontMetrics.descent - fontMetrics.ascent

    drawIntoCanvas { canvas ->
        if (isVertical) {
            var currentX = x + width - lineHeight
            for (line in lines) {
                if (line.isNotBlank() && currentX >= x) {
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.translate(currentX, y)
                    canvas.nativeCanvas.rotate(90f)
                    canvas.nativeCanvas.drawText(line, 0f, -fontMetrics.ascent, paint)
                    canvas.nativeCanvas.restore()
                    currentX -= lineHeight
                }
            }
        } else {
            var currentY = y - fontMetrics.ascent
            for (line in lines) {
                if (line.isNotBlank() && currentY + fontMetrics.descent <= y + height) {
                    canvas.nativeCanvas.drawText(line, x, currentY, paint)
                    currentY += lineHeight
                }
            }
        }
    }
}

private fun adjustWhiteoutBounds(
    text: String,
    initialWidth: Float,
    initialHeight: Float,
    fontSize: Float,
    isVertical: Boolean
): Pair<String, Float> {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textAlign = android.graphics.Paint.Align.LEFT
        this.textSize = fontSize
    }

    val effectiveWidth = if (isVertical) initialHeight else initialWidth
    val effectiveHeight = if (isVertical) initialWidth else initialHeight
    val wrappedLines = wrapText(text, effectiveWidth * 0.95f, fontSize)
    val fontMetrics = paint.fontMetrics
    val lineHeight = fontMetrics.descent - fontMetrics.ascent
    val textHeight = wrappedLines.size * lineHeight

    val finalFontSize = if (textHeight > effectiveHeight * 0.95f) {
        fontSize * (effectiveHeight * 0.95f / textHeight)
    } else {
        fontSize
    }

    paint.textSize = finalFontSize
    val finalWrappedLines = wrapText(text, effectiveWidth * 0.95f, finalFontSize)
    return finalWrappedLines.joinToString("\n") to finalFontSize
}

private fun wrapText(text: String, width: Float, fontSize: Float): List<String> {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.LEFT
    }

    val lines = mutableListOf<String>()
    val normalizedText = text.replace(Regex("\\s+"), " ").trim()
    val words = normalizedText.split(" ").filter { it.isNotBlank() }
    var currentLine = StringBuilder()

    for (word in words) {
        val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
        val bounds = android.graphics.Rect()
        paint.getTextBounds(testLine, 0, testLine.length, bounds)
        if (bounds.width().toFloat() <= width || currentLine.isEmpty()) {
            currentLine = StringBuilder(testLine)
        } else {
            if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
            currentLine = StringBuilder(word)
        }
    }
    if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
    return lines
}

private fun DrawScope.estimateBackgroundColor(rect: Rect): Color {
    val centerX = rect.center.x / size.width
    val centerY = rect.center.y / size.height
    return when {
        centerY < 0.8f && rect.width < size.width * 0.7f -> Color.White
        rect.width < size.width * 0.4f && rect.height < size.height * 0.15f -> Color(0xFFF8F8F8)
        rect.width > size.width * 0.5f -> {
            val gray = (0.95f - (centerY * 0.1f)).coerceIn(0.85f, 0.98f)
            Color(gray, gray, gray, 1f)
        }
        else -> Color(0xFFFAFAFA)
    }
}

private fun DrawScope.advancedTextRemoval(
    rect: Rect,
    originalImageWidth: Float = 0f,
    originalImageHeight: Float = 0f,
    bitmap: Bitmap? = null
) {
    // rect đã là toạ độ hiển thị, không scale lại nữa
    val bgColor = bitmap?.let { estimateBackgroundColorFromBitmap(rect, it) } ?: estimateBackgroundColor(rect)
    drawRect(
        color = bgColor,
        topLeft = rect.topLeft,
        size = rect.size
    )
}

private fun DrawScope.drawRoundedTextRemoval(
    rect: Rect,
    color: Color,
    cornerRadius: Float,
    originalImageWidth: Float = 0f,
    originalImageHeight: Float = 0f
) {
    // rect đã là toạ độ hiển thị, không scale lại nữa
    val roundRect = RoundRect(rect, cornerRadius, cornerRadius)
    val path = Path().apply { addRoundRect(roundRect) }
    drawPath(path, color)
}

private fun DrawScope.drawBoxTextRemoval(
    rect: Rect,
    color: Color,
    hasFrame: Boolean,
    originalImageWidth: Float = 0f,
    originalImageHeight: Float = 0f
) {
    // rect đã là toạ độ hiển thị, không scale lại nữa
    drawRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size,
    )
    if (hasFrame) {
        drawRect(
            color = Color.Black.copy(alpha = 0.3f),
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = 2f)
        )
    }
}

private fun DrawScope.drawCleanRemoval(
    rect: Rect,
    color: Color,
    originalImageWidth: Float = 0f,
    originalImageHeight: Float = 0f
) {
    // rect đã là toạ độ hiển thị, không scale lại nữa
    drawRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size
    )
}

private fun limitRectToBackground(rect: Rect, bgRect: Rect): Rect {
    return Rect(
        max(rect.left, bgRect.left),
        max(rect.top, bgRect.top),
        min(rect.right, bgRect.right),
        min(rect.bottom, bgRect.bottom)
    )
}