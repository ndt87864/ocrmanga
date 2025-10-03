package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import java.io.IOException

data class DragBlockState(
    val block: TextBlockInfo,
    val offset: Offset = Offset.Zero,
    val fontSize: Float? = null,
    val rotation: Float = 0f,
    val whiteoutColor: Color? = null,
    val textColor: Color? = null
)

@Composable
fun ImageViewer(
    imageUris: List<Uri>,
    translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
    translationEnabled: Boolean,
    editTranslationMode: Boolean,
    dragBlocksMap: MutableMap<Uri, List<DragBlockState>>,
    onEditTranslationModeToggle: (Boolean) -> Unit,
    onSaveTranslation: (Uri, List<DragBlockState>) -> Unit,
    onRetranslateImage: (Uri, TranslationMode) -> Unit,
    showImageMenu: Boolean,
    imageMenuUri: Uri?,
    onImageMenuDismiss: () -> Unit,
    onShowImageMenuChange: (Boolean) -> Unit,
    onImageMenuUriChange: (Uri?) -> Unit,
    onRemoveImage: (Uri) -> Unit,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val context = LocalContext.current
    var translationVersion by remember { mutableStateOf(0) }

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
                                if (event.changes.any { it.pressed }) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                else Modifier
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(items = imageUris, key = { uri -> uri.toString() + "-$translationVersion" }) { uri ->
            val initialBlocks = dragBlocksMap[uri]
                ?: translatedTexts[uri]?.second?.map { block ->
                    DragBlockState(
                        block = block,
                        rotation = block.rotation ?: 0f
                    )
                } ?: emptyList()
            var dragBlocks by remember(uri, translationVersion, translatedTexts[uri]) {
                mutableStateOf(initialBlocks)
            }
            LaunchedEffect(uri, translatedTexts[uri]) {
                if (!editTranslationMode) {
                    val newBlocks = translatedTexts[uri]?.second?.map {
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
            var selectedIndex by remember(uri, editTranslationMode) { mutableStateOf<Int?>(null) }
            fun getWhiteoutShape(idx: Int) = if (idx < dragBlocks.size) dragBlocks[idx].block.shapeType else 0
            var draggingIndex by remember { mutableStateOf<Int?>(null) }
            var lastDragPos by remember { mutableStateOf(Offset.Zero) }
            val shrinkedBlocks = splitNonOverlappingBoxes(dragBlocks.map { it.block })

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(uri, editTranslationMode) {
                        detectTapGestures(
                            onLongPress = {
                                if (!editTranslationMode) {
                                    onImageMenuUriChange(uri)
                                    onShowImageMenuChange(true)
                                }
                            }
                        )
                    }
            ) {
                if (editTranslationMode) {
                    TranslationEditor(
                        dragBlocks = dragBlocks,
                        selectedIndex = selectedIndex,
                        onDragBlocksChange = { newBlocks -> dragBlocks = newBlocks },
                        onSelectedIndexChange = { newIndex -> selectedIndex = newIndex },
                        onSave = {
                            // Lưu thay đổi và chuyển về chế độ xem
                            onSaveTranslation(uri, dragBlocks)
                            onEditTranslationModeToggle(false)
                        }
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth()
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
                            Log.e("ImageViewer", "Failed to load image dimensions for $uri", e)
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
                    if (translationEnabled && translatedTexts.containsKey(uri) && isImageLoaded && imageLoadState is AsyncImagePainter.State.Success) {
                        Canvas(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(shrinkedBlocks, editTranslationMode) {
                                    if (editTranslationMode) {
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
                                                        dragEvent.consume()
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
                                                        dragBlocks = dragBlocks.toMutableList().also { list ->
                                                            val old = list[idx]
                                                            list[idx] = old.copy(offset = old.offset + dragAmount)
                                                        }
                                                        lastDragPos = dragEvent.position
                                                        dragEvent.consume()
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
                                                        dragBlocks = dragBlocks.toMutableList().also { list ->
                                                            list[idx] = list[idx].copy(
                                                                block = list[idx].block.copy(bounds = newBounds),
                                                                offset = Offset.Zero
                                                            )
                                                        }
                                                        dragEvent.consume()
                                                    }
                                                    draggingIndex = null
                                                    lastDragPos = Offset.Zero
                                                }
                                            }
                                        }
                                    }
                                }
                                .drawWithCache {
                                    data class RegionInfo(
                                        val block: TextBlockInfo,
                                        val rect: Rect,
                                        val fontSize: Float,
                                        val rotation: Float,
                                        val whiteoutColor: Color? = null,
                                        val textColor: Color? = null
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
                                                block.text, scaledWidth, scaledBlockHeight2, 12f, shapeType = block.shapeType
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
                                                rotation = dragBlock.rotation,
                                                whiteoutColor = dragBlock.whiteoutColor,
                                                textColor = dragBlock.textColor
                                            )
                                        } else null
                                    }
                                    onDrawBehind {
                                        regions.forEachIndexed { i, region ->
                                            val block = region.block
                                            val rect = region.rect
                                            val fontSize = region.fontSize
                                            val rotation = region.rotation
                                            val customWhiteoutColor = region.whiteoutColor
                                            val customTextColor = region.textColor
                                            if (block.text.isNotBlank()) {
                                                withTransform({
                                                    rotate(rotation, Offset(rect.left + rect.width/2, rect.top + rect.height/2))
                                                }) {
                                                    // Sử dụng màu tùy chỉnh nếu có, không thì dùng overlay mặc định
                                                    if (customWhiteoutColor != null) {
                                                        // Vẽ overlay với màu tùy chỉnh
                                                        val isOval = (block.shapeType == 1)
                                                        if (isOval) {
                                                            drawOval(
                                                                color = customWhiteoutColor,
                                                                topLeft = Offset(rect.left, rect.top),
                                                                size = Size(rect.width, rect.height)
                                                            )
                                                        } else {
                                                            drawRect(
                                                                color = customWhiteoutColor,
                                                                topLeft = Offset(rect.left, rect.top),
                                                                size = Size(rect.width, rect.height)
                                                            )
                                                        }
                                                    } else {
                                                        // Sử dụng overlay bán trong suốt cho nền có màu
                                                        drawTranslucentOverlay(
                                                            rect = rect,
                                                            backgroundType = block.backgroundType,
                                                            averageBackgroundColor = block.averageBackgroundColor,
                                                            originalTextColor = block.originalTextColor,
                                                            shapeType = block.shapeType
                                                        )
                                                    }
                                                    val isOval = (block.shapeType == 1)
                                                    val textPadding = if (isOval) 0.15f else 0f
                                                    val textLeft = rect.left + rect.width * textPadding
                                                    val textTop = rect.top + rect.height * textPadding
                                                    val textWidth = rect.width * (1 - 2 * textPadding)
                                                    val textHeight = rect.height * (1 - 2 * textPadding)
                                                    // Xác định màu text - ưu tiên màu tùy chỉnh
                                                    val textColor = when {
                                                        i == draggingIndex -> Color.Red
                                                        customTextColor != null -> customTextColor
                                                        else -> {
                                                            // Tính độ sáng của overlay background
                                                            val overlayColor = if (customWhiteoutColor != null) {
                                                                customWhiteoutColor.toArgb()
                                                            } else {
                                                                block.averageBackgroundColor
                                                            }
                                                            if (overlayColor != null) {
                                                                val r = (overlayColor shr 16) and 0xFF
                                                                val g = (overlayColor shr 8) and 0xFF
                                                                val b = overlayColor and 0xFF
                                                                val brightness = (r + g + b) / 3
                                                                // Sử dụng text đen nếu nền sáng, text trắng nếu nền tối
                                                                if (brightness > 127) Color.Black else Color.White
                                                            } else {
                                                                // Mặc định cho nền trắng
                                                                Color.Black
                                                            }
                                                        }
                                                    }
                                                    drawText(
                                                        text = block.text,
                                                        x = textLeft,
                                                        y = textTop,
                                                        width = textWidth,
                                                        height = textHeight,
                                                        color = textColor,
                                                        fontSize = fontSize,
                                                        isVertical = block.isVertical
                                                    )
                                                    if (editTranslationMode) {
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
                                }
                        ) {}
                    }
                }
            }
        }
    }
}