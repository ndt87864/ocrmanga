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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Rect // <-- Chỉ dùng cho Canvas, DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
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
import android.graphics.Rect as AndroidRect // <-- Alias cho data/model nếu cần


import com.example.ocrmanga.ui.screens.view.getImageDimensions
import com.example.ocrmanga.ui.screens.view.mergeOverlappingRegions
import com.example.ocrmanga.ui.screens.view.calculateOptimalFontSize
import com.example.ocrmanga.ui.screens.view.advancedTextRemoval
import com.example.ocrmanga.ui.screens.view.drawText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch

@Composable
fun ViewerScreen(
    imageUris: List<String> = emptyList(),
    roomId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel()
) {
    // State cho dialog xác nhận xóa ảnh (phải đặt ở đầu hàm)
    var imageToDelete by remember { mutableStateOf<Uri?>(null) }
    // State cho menu tùy chọn ảnh (long-press)
    var imageMenuUri by remember { mutableStateOf<Uri?>(null) }
    var showImageMenu by remember { mutableStateOf(false) }
    // State cho dialog chọn loại dịch khi retranslate
    var showRetranslateDialog by remember { mutableStateOf(false) }
    var retranslateUri by remember { mutableStateOf<Uri?>(null) }
    var selectedRetranslateMode by remember { mutableStateOf<TranslationMode?>(null) }

    // Sử dụng coroutineScope cho Toast khi dịch lại ảnh
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

    // Hàm chạy lại OCR trên bitmap vùng chọn, trả về bounding box các ký tự
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

    // Hàm vẽ whiteout chỉ trên các bounding box ký tự OCR
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
    var showMainMenu by remember { mutableStateOf(false) }
    var showInsertAtIndexDialog by remember { mutableStateOf(false) }
    var insertAtIndex by remember { mutableStateOf("") }
    // Di chuyển lên trên để tránh lỗi unresolved reference
    var showEditTitleDialog by remember { mutableStateOf(false) }
    var editTitleText by remember { mutableStateOf("") }

    // State để kiểm soát dialog xác nhận khi thoát session ảnh mới
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var pendingBack by remember { mutableStateOf(false) }

    // State để kiểm soát việc đã xác nhận xóa session và cần điều hướng về gallery
    var shouldNavigateBackAfterClear by remember { mutableStateOf(false) }

    // Theo dõi khi nào cần điều hướng về gallery sau khi đã xóa session
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

    // Toast báo tiến trình dịch
    var prevProgress by remember { mutableStateOf(0) }
    var hasShownTranslatingToast by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.translationProgress, uiState.isTranslating, uiState.translationEnabled) {
        val progress = uiState.translationProgress
        val total = uiState.totalImagesToTranslate
        val percentage = if (total > 0) (progress * 100 / total) else 0
        if (uiState.translationEnabled && uiState.isTranslating) {
            if (progress > prevProgress) {
                // Dịch xong 1 ảnh
                Toast.makeText(context, "Đã dịch xong $progress/$total ảnh ($percentage%)", Toast.LENGTH_SHORT).show()
                hasShownTranslatingToast = false // Reset để dịch ảnh tiếp theo sẽ hiện lại toast "Đang dịch"
            } else if (!hasShownTranslatingToast && progress < total) {
                // Chỉ hiện 1 lần khi bắt đầu dịch ảnh này
                Toast.makeText(context, "Đang dịch ảnh ${progress + 1}/$total...", Toast.LENGTH_SHORT).show()
                hasShownTranslatingToast = true
            }
        } else {
            hasShownTranslatingToast = false // Reset khi dừng dịch
        }
        prevProgress = progress
    }

    // Thay đổi onNavigateBack để kiểm tra nếu đang ở session ảnh mới thì hỏi xác nhận
    val handleBack: () -> Unit = {
        if (uiState.roomId == null && uiState.imageUris.isNotEmpty()) {
            // Đang ở session ảnh mới, hỏi xác nhận
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
            // Left side - Back button and image counter close together
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
            
            // Group the control icons together on the right side
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
                        tint = MaterialTheme.colorScheme.primary                    )
                }
                
                IconButton(onClick = { showRoomNav = !showRoomNav }) {
                    Icon(
                        imageVector = if (showRoomNav) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showRoomNav) "Ẩn thanh điều hướng" else "Hiện thanh điều hướng",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Main menu with three dots
                Box {
                    IconButton(onClick = { showMainMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Tùy chọn", tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(
                        expanded = showMainMenu,
                        onDismissRequest = { showMainMenu = false }
                    ) {
                        // Translation submenu
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
                        // Save option
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
                        // Add images submenu
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
                        // Đổi tên phòng (chỉ hiện khi đang ở trong phòng)
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
                    
                    // Translation submenu
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
                    
                    // Add images submenu
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
                                    imageMenuUri = uri
                                    showImageMenu = true
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
    // Thu nhỏ bounding box toàn bộ block để loại bỏ chồng lấn
    val shrinkedBlocks = splitNonOverlappingBoxes(translatedBlocks)
    Canvas(
        modifier = Modifier.matchParentSize().drawWithCache {
            val regions = shrinkedBlocks.mapNotNull { block ->
                val blockImageWidth = block.originalImageWidth?.toFloat() ?: originalImageWidth
                val blockImageHeight = block.originalImageHeight?.toFloat() ?: originalImageHeight
                val scale = if (blockImageWidth > 0f) imageWidth / blockImageWidth else 1f
                val scaledHeight = blockImageHeight * scale
                val offsetY = if (imageHeight > scaledHeight) (imageHeight - scaledHeight) / 2 else 0f
                val offsetX = 0f
                if (block.text.isNotBlank()) {
                    val bounds = block.bounds
                    val scaledLeft = (bounds.left * scale) + offsetX
                    val scaledTop = (bounds.top * scale) + offsetY
                    val scaledWidth = (bounds.width() * scale).toFloat()
                    val scaledHeight = (bounds.height() * scale).toFloat()
                    val optimalFontSize = calculateOptimalFontSize(
                        block.text, scaledWidth, scaledHeight, 12f
                    )
                    Triple(
                        block,
                        Rect(
                            scaledLeft,
                            scaledTop,
                            scaledLeft + scaledWidth,
                            scaledTop + scaledHeight
                        ),
                        optimalFontSize
                    )
                } else null
            }
            onDrawBehind {
                // Xóa từng vùng text đã shrink
                regions.forEach { triple ->
                    val rect = triple.component2()
                    advancedTextRemoval(rect, originalImageWidth)
                }
                // Vẽ text dịch đúng vùng đã shrink
                regions.forEach { triple ->
                    val block = triple.component1()
                    val rect = triple.component2()
                    val fontSize = triple.component3()
                    if (block.text.isNotBlank()) {
                        drawText(
                            text = block.text,
                            x = rect.left,
                            y = rect.top,
                            width = rect.width,
                            height = rect.height,
                            color = Color.Black,
                            fontSize = fontSize,
                            isVertical = block.isVertical
                        )
                    }
                }
            }
        }
    ) {}
                        }
                    }
                }
            }
        }        // Insert at index dialog
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
                                // Only allow digits and limit the input
                                val filtered = value.filter { it.isDigit() }
                                insertAtIndex = filtered
                            },
                            label = { Text("Vị trí") },                            placeholder = { Text("1") },
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
                                // Convert to 0-based index for internal use
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

        // Dialog xác nhận khi thoát session ảnh mới
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

        // Hiển thị nút đổi tên phòng nếu đang ở room
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

        // Dialog đổi tên phòng
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

        // Dialog menu tùy chọn ảnh khi long-press
        if (showImageMenu && imageMenuUri != null) {
            AlertDialog(
                onDismissRequest = { showImageMenu = false },
                title = { Text("Tùy chọn ảnh") },
                text = {
                    Column {
                        // Xóa ảnh
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
                                        // Khi chọn 1 type dịch, thực hiện dịch lại ảnh
                                        val uri = imageMenuUri
                                        if (uri != null) {
                                            Toast.makeText(context, "Đang dịch lại ảnh...", Toast.LENGTH_SHORT).show()
                                            viewModel.retranslateImage(uri, mode)
                                            coroutineScope.launch {
                                                // Đợi trạng thái translatedStatus của uri chuyển sang true
                                                while (true) {
                                                    val status = viewModel.uiState.value.translatedStatus[uri]
                                                    if (status == true) break
                                                    kotlinx.coroutines.delay(200)
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

// Hàm kiểm tra và thu nhỏ bounding box nếu bị chồng lấn
private fun shrinkOverlappingBoxes(blocks: List<com.example.ocrmanga.data.models.TextBlockInfo>): List<com.example.ocrmanga.data.models.TextBlockInfo> {
    val result = blocks.map { it.copy() }.toMutableList()
    for (i in result.indices) {
        val boxA = result[i].bounds
        for (j in result.indices) {
            if (i == j) continue
            val boxB = result[j].bounds
            // Kiểm tra overlap
            if (android.graphics.Rect.intersects(boxA, boxB)) {
                // Tìm vùng giao nhau
                val intersect = android.graphics.Rect(
                    maxOf(boxA.left, boxB.left),
                    maxOf(boxA.top, boxB.top),
                    minOf(boxA.right, boxB.right),
                    minOf(boxA.bottom, boxB.bottom)
                )
                // Nếu vùng giao nhau nhỏ hơn 40% diện tích boxA thì bỏ qua (chỉ cắt khi chồng lấn lớn)
                val areaA = (boxA.width() * boxA.height()).toFloat()
                val areaIntersect = (intersect.width() * intersect.height()).toFloat()
                if (areaA > 0 && areaIntersect / areaA > 0.15f) {
                    // Thu nhỏ boxA bằng cách cắt bớt vùng giao nhau ở mép (ưu tiên giữ vùng giữa)
                    // Nếu boxA rộng hơn, cắt chiều ngang; nếu cao hơn, cắt chiều dọc
                    val shrinkLeft = if (intersect.left == boxA.left) intersect.width() / 2 else 0
                    val shrinkRight = if (intersect.right == boxA.right) intersect.width() / 2 else 0
                    val shrinkTop = if (intersect.top == boxA.top) intersect.height() / 2 else 0
                    val shrinkBottom = if (intersect.bottom == boxA.bottom) intersect.height() / 2 else 0
                    result[i] = result[i].copy(
                        bounds = android.graphics.Rect(
                            boxA.left + shrinkLeft,
                            boxA.top + shrinkTop,
                            boxA.right - shrinkRight,
                            boxA.bottom - shrinkBottom
                        )
                    )
                }
            }
        }
    }
    return result
}

// Hàm tách các bounding box không chồng lấn
private fun splitNonOverlappingBoxes(blocks: List<TextBlockInfo>): List<TextBlockInfo> {
    val result = mutableListOf<TextBlockInfo>()
    val used = BooleanArray(blocks.size)
    for (i in blocks.indices) {
        var boxA = blocks[i].bounds
        var keep = true
        for (j in blocks.indices) {
            if (i == j) continue
            val boxB = blocks[j].bounds
            if (android.graphics.Rect.intersects(boxA, boxB)) {
                // Nếu boxA nằm hoàn toàn trong boxB thì bỏ boxA
                if (boxB.contains(boxA)) {
                    keep = false
                    break
                }
                // Nếu chỉ giao một phần, cắt phần giao nhau khỏi boxA
                val intersect = android.graphics.Rect(
                    maxOf(boxA.left, boxB.left),
                    maxOf(boxA.top, boxB.top),
                    minOf(boxA.right, boxB.right),
                    minOf(boxA.bottom, boxB.bottom)
                )
                // Cắt phần giao nhau ở mép dưới hoặc phải
                if (intersect.width() > 0 && intersect.height() > 0) {
                    if (intersect.right == boxA.right) boxA.right = intersect.left
                    if (intersect.left == boxA.left) boxA.left = intersect.right
                    if (intersect.bottom == boxA.bottom) boxA.bottom = intersect.top
                    if (intersect.top == boxA.top) boxA.top = intersect.bottom
                }
            }
        }
        if (keep && boxA.width() > 0 && boxA.height() > 0) {
            result.add(blocks[i].copy(bounds = android.graphics.Rect(boxA)))
        }
    }
    return result
}

