package com.example.ocrmanga.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.viewmodels.ViewerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.min
import kotlin.math.max
import android.graphics.Rect as AndroidRect
import com.example.ocrmanga.ui.screens.view.getImageDimensions
import com.example.ocrmanga.ui.screens.view.mergeOverlappingRegions
import com.example.ocrmanga.ui.screens.view.calculateOptimalFontSize
import com.example.ocrmanga.ui.screens.view.advancedTextRemoval
import com.example.ocrmanga.ui.screens.view.drawText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.snapshotFlow
import com.example.ocrmanga.ui.screens.view.splitNonOverlappingBoxes

data class DragBlockState(
    val block: TextBlockInfo,
    val offset: Offset = Offset.Zero,
    val fontSize: Float? = null,
    val rotation: Float = 0f,
    val whiteoutColor: Color? = null,
    val textColor: Color? = null
)

@Composable
fun ViewerScreen(
    imageUris: List<String> = emptyList(),
    roomId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel()
) {
    var imageToDelete by remember { mutableStateOf<Uri?>(null) }
    var imageMenuUri by remember { mutableStateOf<Uri?>(null) }
    var showImageMenu by remember { mutableStateOf(false) }
    var showRetranslateDialog by remember { mutableStateOf(false) }
    var retranslateUri by remember { mutableStateOf<Uri?>(null) }
    var selectedRetranslateMode by remember { mutableStateOf<TranslationMode?>(null) }
    val dragBlocksMap = remember { mutableStateMapOf<Any, List<DragBlockState>>() }
    val coroutineScope = rememberCoroutineScope()

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

    fun runOcrOnRegion(bitmap: Bitmap, onResult: (List<Rect>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val boxes = mutableListOf<Rect>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val box = element.boundingBox
                            if (box != null) {
                                boxes.add(Rect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()))
                            }
                        }
                    }
                }
                onResult(boxes)
            }
            .addOnFailureListener {
                onResult(emptyList())
            }
    }

    fun DrawScope.drawWhiteoutByOcr(boxes: List<Rect>, color: Color = Color.White) {
        for (rect in boxes) {
            drawRect(
                color = color,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Fill
            )
        }
    }
    val lazyListState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(false) }
    var scrollSpeed by remember { mutableStateOf(4f) }
    var showSpeedSlider by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showRoomNav by remember { mutableStateOf(false) }
    var showTranslationMenu by remember { mutableStateOf(false) }
    var editTranslationMode by remember { mutableStateOf(false) }
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

    var prevProgress by remember { mutableStateOf(0) }
    var hasShownTranslatingToast by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.translationProgress, uiState.isTranslating, uiState.translationEnabled) {
        val progress = uiState.translationProgress
        val total = uiState.totalImagesToTranslate
        val percentage = if (total > 0) (progress * 100 / total) else 0
        if (uiState.translationEnabled && uiState.isTranslating) {
            if (progress > prevProgress) {
                Toast.makeText(context, "Đã dịch xong $progress/$total ảnh ($percentage%)", Toast.LENGTH_SHORT).show()
                hasShownTranslatingToast = false
            } else if (!hasShownTranslatingToast && progress < total) {
                Toast.makeText(context, "Đang dịch ảnh ${progress + 1}/$total...", Toast.LENGTH_SHORT).show()
                hasShownTranslatingToast = true
            }
        } else {
            hasShownTranslatingToast = false
        }
        prevProgress = progress
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
                        dragBlocksMap.forEach { (uri, blocks) ->
                            if (uri is android.net.Uri) {
                                viewModel.updateTranslatedBlocks(
                                    uri,
                                    blocks.map { dragBlock ->
                                        dragBlock.block.copy(rotation = dragBlock.rotation)
                                    }
                                )
                            }
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
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Chỉnh sửa bản dịch") },
                            onClick = {
                                editTranslationMode = !editTranslationMode
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
        var translationVersion by remember { mutableStateOf(0) }
        if (editTranslationMode) {
            Button(
                onClick = {
                    android.util.Log.i("ViewerScreen", "[SAVE] Bắt đầu lưu chỉnh sửa bản dịch...")
                    dragBlocksMap.forEach { (uri, blocks) ->
                        if (uri is android.net.Uri) {
                            android.util.Log.i("ViewerScreen", "[SAVE] updateTranslatedBlocks $uri, blocks: ${blocks.size}")
                            viewModel.updateTranslatedBlocks(
                                uri,
                                blocks.map { dragBlock ->
                                    dragBlock.block.copy(rotation = dragBlock.rotation)
                                }
                            )
                        }
                    }
                    Toast.makeText(context, "Đã lưu thay đổi bản dịch!", Toast.LENGTH_SHORT).show()
                    editTranslationMode = false
                    translationVersion++
                    android.util.Log.i("ViewerScreen", "[SAVE] Đã chuyển về chế độ view sau khi lưu")
                },
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.End)
            ) {
                Icon(Icons.Default.Save, contentDescription = "Lưu bản dịch", modifier = Modifier.padding(end = 4.dp))
                Text("Lưu bản dịch")
            }
        }

        val maxPages = 10
        var loadedCount by remember { mutableStateOf(maxPages) }
        val loadedUris = uiState.imageUris.take(loadedCount)
        LaunchedEffect(lazyListState.firstVisibleItemIndex, loadedUris.size, uiState.imageUris.size) {
            if (loadedUris.isNotEmpty() && lazyListState.firstVisibleItemIndex >= loadedUris.size - 3 && loadedUris.size < uiState.imageUris.size) {
                loadedCount = (loadedCount + maxPages).coerceAtMost(uiState.imageUris.size)
            }
        }
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (editTranslationMode)
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    else Modifier
                ),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(loadedUris, key = { uri -> "$uri-$translationVersion" }) { uri ->
                val initialBlocks = dragBlocksMap[uri]
                    ?: uiState.translatedTexts[uri]?.second?.map { block ->
                        DragBlockState(
                            block = block,
                            rotation = block.rotation ?: 0f
                        )
                    } ?: emptyList()
                var dragBlocks by remember(uri, translationVersion, uiState.translatedTexts[uri]) {
                    mutableStateOf(initialBlocks)
                }
                LaunchedEffect(uri, uiState.translatedTexts[uri]) {
                    if (!editTranslationMode) {
                        val newBlocks = uiState.translatedTexts[uri]?.second?.map {
                            DragBlockState(
                                block = it,
                                rotation = it.rotation ?: 0f
                            )
                        } ?: emptyList()
                        dragBlocks = newBlocks
                        dragBlocksMap[uri] = newBlocks
                    }
                }
                LaunchedEffect(dragBlocks) {
                    dragBlocksMap[uri] = dragBlocks
                }
                var whiteoutShapes by remember(uri, translationVersion) { mutableStateOf(mutableMapOf<Int, Int>()) }
                fun getWhiteoutShape(idx: Int) = whiteoutShapes[idx] ?: 0
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(uri, editTranslationMode) {
                            detectTapGestures(
                                onLongPress = {
                                    if (!editTranslationMode) {
                                        imageMenuUri = uri
                                        showImageMenu = true
                                    }
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

                    LaunchedEffect(uri) {
                        try {
                            val (width, height) = getImageDimensions(context, uri)
                            originalImageWidth = width.toFloat()
                            originalImageHeight = height.toFloat()
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
                            Canvas(
                                modifier = Modifier
                                    .matchParentSize()
                                    .drawWithCache {
                                        data class RegionInfo(
                                            val block: TextBlockInfo,
                                            val rect: Rect,
                                            val fontSize: Float,
                                            val rotation: Float
                                        )
                                        val regions = dragBlocks.mapIndexedNotNull { i, dragBlock ->
                                            val block = dragBlock.block
                                            val blockImageWidth = block.originalImageWidth?.toFloat() ?: originalImageWidth
                                            val blockImageHeight = block.originalImageHeight?.toFloat() ?: originalImageHeight
                                            val scale = if (blockImageWidth > 0f) imageWidth / blockImageWidth else 1f
                                            val scaledBlockHeight = blockImageHeight * scale
                                            val offsetY = if (imageHeight > scaledBlockHeight) (imageHeight - scaledBlockHeight) / 2 else 0f
                                            val offsetX = 0f
                                            if (block.text.isNotBlank()) {
                                                val bounds = block.bounds
                                                val scaledLeft = (bounds.left * scale) + offsetX + dragBlock.offset.x
                                                val scaledTop = (bounds.top * scale) + offsetY + dragBlock.offset.y
                                                val scaledWidth = (bounds.width() * scale).toFloat()
                                                val scaledBlockHeight2 = (bounds.height() * scale).toFloat()
                                                val fontSize = dragBlock.fontSize ?: calculateOptimalFontSize(
                                                    block.text, scaledWidth, scaledBlockHeight2, 12f
                                                )
                                                RegionInfo(
                                                    block = block,
                                                    rect = Rect(
                                                        scaledLeft,
                                                        scaledTop,
                                                        scaledLeft + scaledWidth,
                                                        scaledTop + scaledBlockHeight2
                                                    ),
                                                    fontSize = fontSize,
                                                    rotation = dragBlock.rotation
                                                )
                                            } else null
                                        }
                                        onDrawBehind {
                                            regions.forEachIndexed { i, region ->
                                                val block = region.block
                                                val rect = region.rect
                                                val fontSize = region.fontSize
                                                val rotation = region.rotation
                                                if (block.text.isNotBlank()) {
                                                    withTransform({
                                                        rotate(rotation, Offset(rect.left + rect.width/2, rect.top + rect.height/2))
                                                    }) {
                                                        val isOval = (getWhiteoutShape(i) == 1)
                                                        if (isOval) {
                                                            drawOval(
                                                                color = Color.White,
                                                                topLeft = Offset(rect.left, rect.top),
                                                                size = Size(rect.width, rect.height),
                                                                style = Fill
                                                            )
                                                        } else {
                                                            drawRect(
                                                                color = Color.White,
                                                                topLeft = Offset(rect.left, rect.top),
                                                                size = Size(rect.width, rect.height),
                                                                style = Fill
                                                            )
                                                        }
                                                        val textPadding = if (isOval) 0.15f else 0f
                                                        val textLeft = rect.left + rect.width * textPadding
                                                        val textTop = rect.top + rect.height * textPadding
                                                        val textWidth = rect.width * (1 - 2 * textPadding)
                                                        val textHeight = rect.height * (1 - 2 * textPadding)
                                                        drawText(
                                                            text = block.text,
                                                            x = textLeft,
                                                            y = textTop,
                                                            width = textWidth,
                                                            height = textHeight,
                                                            color = Color.Black,
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

                    if (editTranslationMode && uiState.translationEnabled && uiState.translatedTexts.containsKey(uri) && isImageLoaded && imageLoadState is AsyncImagePainter.State.Success) {
                        val (fullText, translatedBlocks) = uiState.translatedTexts[uri] ?: ("" to emptyList())
                        if (translatedBlocks.isNotEmpty()) {
                            var draggingIndex by remember { mutableStateOf<Int?>(null) }
                            var lastDragPos by remember { mutableStateOf(Offset.Zero) }
                            var selectedIndex by remember(uri, editTranslationMode) { mutableStateOf<Int?>(null) }
                            val shrinkedBlocks = splitNonOverlappingBoxes(dragBlocks.map { it.block })
                            Column {
                                val isBlockSelected = selectedIndex != null
                                val headerScrollState = rememberScrollState()
                                var showShapeMenu by remember { mutableStateOf(false) }
                                val shapeLabels = listOf("Hình chữ nhật", "Hình oval")
                                val shapeIcons = listOf(Icons.Default.CropSquare, Icons.Default.Circle)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF0F0F0))
                                        .padding(8.dp)
                                        .horizontalScroll(headerScrollState),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box {
                                        IconButton(onClick = { showShapeMenu = true }) {
                                            Icon(
                                                imageVector = shapeIcons[selectedIndex?.let { getWhiteoutShape(it) } ?: 0],
                                                contentDescription = "Chọn hình dạng bôi trắng",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showShapeMenu,
                                            onDismissRequest = { showShapeMenu = false }
                                        ) {
                                            shapeLabels.forEachIndexed { i, label ->
                                                DropdownMenuItem(
                                                    text = { Text(label) },
                                                    leadingIcon = {
                                                        Icon(shapeIcons[i], contentDescription = null)
                                                    },
                                                    onClick = {
                                                        selectedIndex?.let { idx ->
                                                            whiteoutShapes = whiteoutShapes.toMutableMap().also { it[idx] = i }
                                                        }
                                                        showShapeMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    var resizeMode by remember { mutableStateOf(0) }
                                    val resizeOptions = listOf("Tất cả", "Chiều cao", "Chiều rộng")
                                    var resizeDropdownExpanded by remember { mutableStateOf(false) }
                                    IconButton(
                                        onClick = {
                                            selectedIndex?.let { idx ->
                                                dragBlocks = dragBlocks.toMutableList().also {
                                                    val old = it[idx]
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
                                                    it[idx] = old.copy(block = b.copy(bounds = bounds))
                                                }
                                            }
                                        },
                                        enabled = isBlockSelected
                                    ) { Icon(Icons.Default.AddBox, contentDescription = "Tăng kích thước") }
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
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
                                            resizeOptions.forEachIndexed { i, label ->
                                                DropdownMenuItem(
                                                    text = { Text(label) },
                                                    onClick = {
                                                        resizeMode = i
                                                        resizeDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            selectedIndex?.let { idx ->
                                                dragBlocks = dragBlocks.toMutableList().also {
                                                    val old = it[idx]
                                                    val b = old.block
                                                    val bounds = android.graphics.Rect(b.bounds)
                                                    when (resizeMode) {
                                                        0 -> bounds.inset(10, 10)
                                                        1 -> {
                                                            bounds.top += 10
                                                            bounds.bottom -= 10
                                                        }
                                                        2 -> {
                                                            bounds.left += 10
                                                            bounds.right -= 10
                                                        }
                                                    }
                                                    it[idx] = old.copy(block = b.copy(bounds = bounds))
                                                }
                                            }
                                        },
                                        enabled = isBlockSelected
                                    ) { Icon(Icons.Default.IndeterminateCheckBox, contentDescription = "Giảm kích thước") }
                                    IconButton(
                                        onClick = {
                                            selectedIndex?.let { idx ->
                                                dragBlocks = dragBlocks.toMutableList().also {
                                                    it.removeAt(idx)
                                                }
                                                selectedIndex = dragBlocks.indices.minOrNull()?.takeIf { dragBlocks.isNotEmpty() }
                                            }
                                        },
                                        enabled = isBlockSelected
                                    ) { Icon(Icons.Default.Delete, contentDescription = "Xóa vùng đã chọn", tint = if (isBlockSelected) Color.Red else Color.Gray) }
                                    var showEditBlockDialog by remember { mutableStateOf(false) }
                                    IconButton(
                                        onClick = {
                                            if (isBlockSelected) showEditBlockDialog = true
                                        },
                                        enabled = isBlockSelected
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Sửa bản dịch", tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    if (showEditBlockDialog && isBlockSelected && selectedIndex != null) {
                                        val idx = selectedIndex!!
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
                                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
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
                                                        dragBlocks = dragBlocks.toMutableList().also {
                                                            val old = it[idx]
                                                            val newBlocks = nonBlankParts.map { part ->
                                                                old.copy(block = old.block.copy(text = part))
                                                            }
                                                            it.removeAt(idx)
                                                            it.addAll(idx, newBlocks)
                                                        }
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
                                                dragBlocks = dragBlocks.toMutableList().also {
                                                    val old = it[idx]
                                                    val newFont = (old.fontSize ?: 16f) + 2f
                                                    it[idx] = old.copy(fontSize = newFont)
                                                }
                                            }
                                        },
                                        enabled = isBlockSelected
                                    ) { Icon(Icons.Default.TextIncrease, contentDescription = "Tăng cỡ chữ") }
                                    IconButton(
                                        onClick = {
                                            selectedIndex?.let { idx ->
                                                dragBlocks = dragBlocks.toMutableList().also {
                                                    val old = it[idx]
                                                    val newFont = (old.fontSize ?: 16f) - 2f
                                                    it[idx] = old.copy(fontSize = newFont.coerceAtLeast(8f))
                                                }
                                            }
                                        },
                                        enabled = isBlockSelected
                                    ) { Icon(Icons.Default.TextDecrease, contentDescription = "Giảm cỡ chữ") }
                                    Spacer(Modifier.width(16.dp))
                                    var isRotating by remember { mutableStateOf(false) }
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
                                                        isRotating = true
                                                        val job = coroutineScope.launch {
                                                            while (isRotating) {
                                                                selectedIndex?.let { idx ->
                                                                    dragBlocks = dragBlocks.toMutableList().also {
                                                                        val old = it[idx]
                                                                        val newRot = (old.rotation + rotationSpeed) % 360f
                                                                        it[idx] = old.copy(rotation = newRot)
                                                                    }
                                                                }
                                                                delay(rotationInterval)
                                                            }
                                                        }
                                                        waitForUpOrCancellation()
                                                        isRotating = false
                                                        job.cancel()
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.RotateRight,
                                            contentDescription = "Xoay tự động",
                                            tint = if (isBlockSelected) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                        if (isBlockSelected) {
                                            val rot = selectedIndex?.let { dragBlocks[it].rotation } ?: 0f
                                            Text(
                                                text = "${rot.toInt()}°",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.align(Alignment.BottomCenter)
                                            )
                                        }
                                    }
                                }
                                Box(modifier = Modifier.fillMaxWidth().aspectRatio(originalImageWidth / originalImageHeight)) {
                                    Canvas(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .pointerInput(shrinkedBlocks, editTranslationMode) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val dragEvent = event.changes.firstOrNull()
                                                        if (dragEvent == null) continue
                                                        if (dragEvent.pressed) {
                                                            if (draggingIndex == null) {
                                                                val offset = dragEvent.position
                                                                val blockIndex = dragBlocks.indexOfLast { dragBlock ->
                                                                    val block = dragBlock.block
                                                                    val blockImageWidth = block.originalImageWidth?.toFloat() ?: originalImageWidth
                                                                    val blockImageHeight = block.originalImageHeight?.toFloat() ?: originalImageHeight
                                                                    val scale = if (blockImageWidth > 0f) imageWidth / blockImageWidth else 1f
                                                                    val scaledBlockHeight = blockImageHeight * scale
                                                                    val offsetY = if (imageHeight > scaledBlockHeight) (imageHeight - scaledBlockHeight) / 2 else 0f
                                                                    val offsetX = 0f
                                                                    val bounds = block.bounds
                                                                    val scaledLeft = (bounds.left * scale) + offsetX + dragBlock.offset.x
                                                                    val scaledTop = (bounds.top * scale) + offsetY + dragBlock.offset.y
                                                                    val scaledWidth = (bounds.width() * scale).toFloat()
                                                                    val scaledBlockHeight2 = (bounds.height() * scale).toFloat()
                                                                    val rect = Rect(scaledLeft, scaledTop, scaledLeft + scaledWidth, scaledTop + scaledBlockHeight2)
                                                                    rect.contains(offset)
                                                                }
                                                                if (blockIndex != -1) {
                                                                    selectedIndex = blockIndex
                                                                    draggingIndex = blockIndex
                                                                    lastDragPos = offset
                                                                } else {
                                                                    selectedIndex = null
                                                                }
                                                            } else {
                                                                val idx = draggingIndex!!
                                                                val dragAmount = dragEvent.position - lastDragPos
                                                                dragBlocks = dragBlocks.toMutableList().also {
                                                                    val old = it[idx]
                                                                    it[idx] = old.copy(offset = old.offset + dragAmount)
                                                                }
                                                                lastDragPos = dragEvent.position
                                                            }
                                                        } else {
                                                            draggingIndex?.let { idx ->
                                                                val dragBlock = dragBlocks[idx]
                                                                val block = dragBlock.block
                                                                val blockImageWidth = block.originalImageWidth?.toFloat() ?: originalImageWidth
                                                                val blockImageHeight = block.originalImageHeight?.toFloat() ?: originalImageHeight
                                                                val scale = if (blockImageWidth > 0f) imageWidth / blockImageWidth else 1f
                                                                val dx = dragBlock.offset.x / scale
                                                                val dy = dragBlock.offset.y / scale
                                                                val newBounds = android.graphics.Rect(block.bounds)
                                                                newBounds.offset(dx.toInt(), dy.toInt())
                                                                dragBlocks = dragBlocks.toMutableList().also {
                                                                    it[idx] = it[idx].copy(
                                                                        block = it[idx].block.copy(bounds = newBounds),
                                                                        offset = Offset.Zero
                                                                    )
                                                                }
                                                            }
                                                            draggingIndex = null
                                                            lastDragPos = Offset.Zero
                                                        }
                                                    }
                                                }
                                            }
                                            .drawWithCache {
                                                data class RegionInfo(
                                                    val block: TextBlockInfo,
                                                    val rect: Rect,
                                                    val fontSize: Float,
                                                    val rotation: Float
                                                )
                                                val regions = dragBlocks.mapIndexedNotNull { i, dragBlock ->
                                                    val block = dragBlock.block
                                                    val blockImageWidth = block.originalImageWidth?.toFloat() ?: originalImageWidth
                                                    val blockImageHeight = block.originalImageHeight?.toFloat() ?: originalImageHeight
                                                    val scale = if (blockImageWidth > 0f) imageWidth / blockImageWidth else 1f
                                                    val scaledBlockHeight = blockImageHeight * scale
                                                    val offsetY = if (imageHeight > scaledBlockHeight) (imageHeight - scaledBlockHeight) / 2 else 0f
                                                    val offsetX = 0f
                                                    if (block.text.isNotBlank()) {
                                                        val bounds = block.bounds
                                                        val scaledLeft = (bounds.left * scale) + offsetX + dragBlock.offset.x
                                                        val scaledTop = (bounds.top * scale) + offsetY + dragBlock.offset.y
                                                        val scaledWidth = (bounds.width() * scale).toFloat()
                                                        val scaledBlockHeight2 = (bounds.height() * scale).toFloat()
                                                        val fontSize = dragBlock.fontSize ?: calculateOptimalFontSize(
                                                            block.text, scaledWidth, scaledBlockHeight2, 12f
                                                        )
                                                        RegionInfo(
                                                            block = block,
                                                            rect = Rect(
                                                                scaledLeft,
                                                                scaledTop,
                                                                scaledLeft + scaledWidth,
                                                                scaledTop + scaledBlockHeight2
                                                            ),
                                                            fontSize = fontSize,
                                                            rotation = dragBlock.rotation
                                                        )
                                                    } else null
                                                }
                                                onDrawBehind {
                                                    regions.forEachIndexed { i, region ->
                                                        val block = region.block
                                                        val rect = region.rect
                                                        val fontSize = region.fontSize
                                                        val rotation = region.rotation
                                                        if (block.text.isNotBlank()) {
                                                            withTransform({
                                                                rotate(rotation, Offset(rect.left + rect.width/2, rect.top + rect.height/2))
                                                            }) {
                                                                val isOval = (getWhiteoutShape(i) == 1)
                                                                if (isOval) {
                                                                    drawOval(
                                                                        color = Color.White,
                                                                        topLeft = Offset(rect.left, rect.top),
                                                                        size = Size(rect.width, rect.height),
                                                                        style = Fill
                                                                    )
                                                                } else {
                                                                    drawRect(
                                                                        color = Color.White,
                                                                        topLeft = Offset(rect.left, rect.top),
                                                                        size = Size(rect.width, rect.height),
                                                                        style = Fill
                                                                    )
                                                                }
                                                                val textPadding = if (isOval) 0.15f else 0f
                                                                val textLeft = rect.left + rect.width * textPadding
                                                                val textTop = rect.top + rect.height * textPadding
                                                                val textWidth = rect.width * (1 - 2 * textPadding)
                                                                val textHeight = rect.height * (1 - 2 * textPadding)
                                                                drawText(
                                                                    text = block.text,
                                                                    x = textLeft,
                                                                    y = textTop,
                                                                    width = textWidth,
                                                                    height = textHeight,
                                                                    color = if (i == draggingIndex) Color.Red else Color.Black,
                                                                    fontSize = fontSize,
                                                                    isVertical = block.isVertical
                                                                )
                                                                if (isOval) {
                                                                    drawOval(
                                                                        color = if (i == selectedIndex) Color.Red else Color.Blue,
                                                                        topLeft = Offset(rect.left, rect.top),
                                                                        size = Size(rect.width, rect.height),
                                                                        style = Stroke(width = 2f)
                                                                    )
                                                                } else {
                                                                    drawRect(
                                                                        color = if (i == selectedIndex) Color.Red else Color.Blue,
                                                                        topLeft = Offset(rect.left, rect.top),
                                                                        size = Size(rect.width, rect.height),
                                                                        style = Stroke(width = 2f)
                                                                    )
                                                                }
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
        if (showImageMenu && imageMenuUri != null) {
            AlertDialog(
                onDismissRequest = { showImageMenu = false },
                title = { Text("Tùy chọn ảnh") },
                text = {
                    Column {
                        Button(
                            onClick = {
                                imageMenuUri?.let {
                                    viewModel.removeImageFromRoom(it)
                                    Toast.makeText(context, "Đã xóa ảnh khỏi trang", Toast.LENGTH_SHORT).show()
                                }
                                showImageMenu = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Xóa ảnh khỏi trang") }
                        Spacer(Modifier.height(16.dp))
                        Text("Dịch lại ảnh với:", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        TranslationMode.values().forEach { mode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uri = imageMenuUri
                                        if (uri != null) {
                                            Toast.makeText(context, "Đang dịch lại ảnh...", Toast.LENGTH_SHORT).show()
                                            viewModel.retranslateImage(uri, mode)
                                            coroutineScope.launch {
                                                while (true) {
                                                    val status = viewModel.uiState.value.translatedStatus[uri]
                                                    if (status == true) break
                                                    delay(200)
                                                }
                                                Toast.makeText(context, "Dịch lại ảnh hoàn tất!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        showImageMenu = false
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(mode.name, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showImageMenu = false }) { Text("Đóng") }
                }
            )
        }
    }
}