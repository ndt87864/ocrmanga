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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.core.graphics.ColorUtils
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
    val textColor: Color? = null,
    val overlayAlpha: Float = 1.0f, // Độ trong suốt của overlay (0.0 - 1.0)
    val textBoldness: Float = 1.0f, // Độ đậm của text (0.5 - 2.0)
    val overlaySaturation: Float = 1.0f, // Độ đậm màu overlay (0.0 - 2.0)
    val textSaturation: Float = 1.0f, // Độ đậm màu text (0.0 - 2.0)
    val textBorderColor: Color? = null, // Màu viền chữ
    val textBorderThickness: Float = 0.0f, // Độ dày viền chữ (0.0 - 5.0)
    val textBorderAlpha: Float = 1.0f // Độ trong suốt của viền chữ (0.0 - 1.0)
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
    lazyListState: LazyListState = rememberLazyListState(),
    isLoadingMoreImages: Boolean = false,
    remainingImagesCount: Int = 0
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
        itemsIndexed(items = imageUris, key = { index, uri -> uri.toString() + "-$index" }) { index, uri ->
            val initialBlocks = dragBlocksMap[uri]
                ?: translatedTexts[uri]?.second?.map { block ->
                    // Luôn dùng cỡ chữ gốc khi vào edit mode
                    DragBlockState(
                        block = block,
                        fontSize = block.fontSize,
                        rotation = block.rotation ?: 0f,
                        whiteoutColor = block.customOverlayColor?.let { androidx.compose.ui.graphics.Color(it) },
                        textColor = block.customTextColor?.let { androidx.compose.ui.graphics.Color(it) },
                        overlayAlpha = block.overlayAlpha,
                        textBoldness = block.textBoldness,
                        overlaySaturation = block.overlaySaturation,
                        textSaturation = block.textSaturation,
                        textBorderColor = block.customBorderColor?.let { androidx.compose.ui.graphics.Color(it) },
                        textBorderThickness = block.borderThickness,
                        textBorderAlpha = block.borderAlpha
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
                            fontSize = it.fontSize, // Sử dụng fontSize đã lưu từ database
                            rotation = it.rotation ?: 0f,
                            whiteoutColor = it.customOverlayColor?.let { color -> androidx.compose.ui.graphics.Color(color) },
                            textColor = it.customTextColor?.let { color -> androidx.compose.ui.graphics.Color(color) },
                            overlayAlpha = it.overlayAlpha,
                            textBoldness = it.textBoldness,
                            overlaySaturation = it.overlaySaturation,
                            textSaturation = it.textSaturation,
                            textBorderColor = it.customBorderColor?.let { color -> androidx.compose.ui.graphics.Color(color) },
                            textBorderThickness = it.borderThickness,
                            textBorderAlpha = it.borderAlpha
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
                                        val textColor: Color? = null,
                                        val overlayAlpha: Float = 1.0f,
                                        val textBoldness: Float = 1.0f,
                                        val overlaySaturation: Float = 1.0f,
                                        val textSaturation: Float = 1.0f,
                                        val textBorderColor: Color? = null,
                                        val textBorderThickness: Float = 0.0f,
                                        val textBorderAlpha: Float = 1.0f
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
                                            val fontSize = if (editTranslationMode) {
                                                // Khi edit mode, dùng fontSize đã lưu (nếu có)
                                                dragBlock.fontSize ?: dragBlock.block.fontSize ?: calculateOptimalFontSize(
                                                    text = dragBlock.block.text,
                                                    width = scaledWidth,
                                                    height = scaledBlockHeight2,
                                                    context = context,
                                                    fontFamilyName = dragBlock.block.fontFamily
                                                )
                                            } else {
                                                // Khi view mode, tính auto-fit như cũ
                                                val autoFont = calculateOptimalFontSize(
                                                    text = dragBlock.block.text,
                                                    width = scaledWidth,
                                                    height = scaledBlockHeight2,
                                                    context = context,
                                                    fontFamilyName = dragBlock.block.fontFamily
                                                )

                                                // 🔹 Cập nhật fontSize vào DragBlockState để lưu lại cho edit mode
                                                if (dragBlock.fontSize == null || dragBlock.fontSize != autoFont) {
                                                    dragBlocks = dragBlocks.toMutableList().also { list ->
                                                        val old = list[i]
                                                        list[i] = old.copy(fontSize = autoFont)
                                                    }
                                                }

                                                autoFont
                                            }



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
                                                textColor = dragBlock.textColor,
                                                overlayAlpha = dragBlock.overlayAlpha,
                                                textBoldness = dragBlock.textBoldness,
                                                overlaySaturation = dragBlock.overlaySaturation,
                                                textSaturation = dragBlock.textSaturation,
                                                textBorderColor = dragBlock.textBorderColor,
                                                textBorderThickness = dragBlock.textBorderThickness,
                                                textBorderAlpha = dragBlock.textBorderAlpha
                                            )
                                        } else null
                                    }
                                    onDrawBehind {
                                        regions.forEachIndexed { i, region ->
                                            val block = region.block
                                            val rect = region.rect
                                            val isOval = block.shapeType == 1

                                            // Xác định block đang được chọn bằng so sánh object hoặc thuộc tính duy nhất
                                            val isSelected = selectedIndex != null
                                                && selectedIndex!! < dragBlocks.size
                                                && dragBlocks[selectedIndex!!].block == block

                                            // Vẽ overlay với màu tùy chỉnh, độ trong suốt và saturation
                                            if (region.whiteoutColor != null) {
                                                val overlayAlpha = region.overlayAlpha
                                                val overlaySaturation = region.overlaySaturation
                                                
                                                // Áp dụng saturation cho màu
                                                val saturatedColor = if (overlaySaturation != 1.0f) {
                                                    val red = region.whiteoutColor.red
                                                    val green = region.whiteoutColor.green  
                                                    val blue = region.whiteoutColor.blue
                                                    
                                                    val max = maxOf(red, green, blue)
                                                    val min = minOf(red, green, blue)
                                                    val delta = max - min
                                                    
                                                    val saturation = if (max == 0f) 0f else delta / max
                                                    val newSaturation = saturation * overlaySaturation
                                                    
                                                    val factor = if (saturation == 0f) 1f else newSaturation / saturation
                                                    val newRed = min + (red - min) * factor
                                                    val newGreen = min + (green - min) * factor
                                                    val newBlue = min + (blue - min) * factor
                                                    
                                                    Color(newRed.coerceIn(0f, 1f), newGreen.coerceIn(0f, 1f), newBlue.coerceIn(0f, 1f))
                                                } else {
                                                    region.whiteoutColor
                                                }
                                                
                                                val finalOverlayColor = saturatedColor.copy(alpha = overlayAlpha)
                                                if (isOval) {
                                                    drawOval(
                                                        color = finalOverlayColor,
                                                        topLeft = Offset(rect.left, rect.top),
                                                        size = Size(rect.width, rect.height)
                                                    )
                                                } else {
                                                    drawRect(
                                                        color = finalOverlayColor,
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

                                            // Vẽ viền cho overlay
                                            if (editTranslationMode) {
                                                if (isOval) {
                                                    drawOval(
                                                        color = if (isSelected) Color.Red else Color.Blue,
                                                        topLeft = Offset(rect.left, rect.top),
                                                        size = Size(rect.width, rect.height),
                                                        style = Stroke(width = 2f)
                                                    )
                                                } else {
                                                    drawRect(
                                                        color = if (isSelected) Color.Red else Color.Blue,
                                                        topLeft = Offset(rect.left, rect.top),
                                                        size = Size(rect.width, rect.height),
                                                        style = Stroke(width = 2f)
                                                    )
                                                }
                                            }
                                            val fontSize = region.fontSize
                                            val rotation = region.rotation
                                            val customTextColor = region.textColor
                                            val textPadding = if (isOval) 0.15f else 0f
                                            val textLeft = rect.left + rect.width * textPadding
                                            val textTop = rect.top + rect.height * textPadding
                                            val textWidth = rect.width * (1 - 2 * textPadding)
                                            val textHeight = rect.height * (1 - 2 * textPadding)
                                            // Xác định màu text - ưu tiên màu tùy chỉnh với saturation
                                            val baseTextColor = when {
                                                i == draggingIndex -> Color.Red
                                                customTextColor != null -> customTextColor
                                                else -> {
                                                    // Tính độ sáng của overlay background
                                                    val overlayColor = if (region.whiteoutColor != null) {
                                                        region.whiteoutColor.toArgb()
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
                                            
                                            // Áp dụng saturation cho màu text nếu có
                                            val textColor = if (customTextColor != null && region.textSaturation != 1.0f) {
                                                val red = baseTextColor.red
                                                val green = baseTextColor.green  
                                                val blue = baseTextColor.blue
                                                
                                                val max = maxOf(red, green, blue)
                                                val min = minOf(red, green, blue)
                                                val delta = max - min
                                                
                                                val saturation = if (max == 0f) 0f else delta / max
                                                val newSaturation = saturation * region.textSaturation
                                                
                                                val factor = if (saturation == 0f) 1f else newSaturation / saturation
                                                val newRed = min + (red - min) * factor
                                                val newGreen = min + (green - min) * factor
                                                val newBlue = min + (blue - min) * factor
                                                
                                                Color(newRed.coerceIn(0f, 1f), newGreen.coerceIn(0f, 1f), newBlue.coerceIn(0f, 1f))
                                            } else {
                                                baseTextColor
                                            }
                                            
                                            // Áp dụng rotation khi vẽ text
                                            if (rotation != 0f) {
                                                withTransform({
                                                    // Xoay quanh tâm của text rect
                                                    val centerX = textLeft + textWidth / 2
                                                    val centerY = textTop + textHeight / 2
                                                    rotate(rotation, Offset(centerX, centerY))
                                                }) {
                                                    drawText(
                                                        text = block.text,
                                                        x = textLeft,
                                                        y = textTop,
                                                        width = textWidth,
                                                        height = textHeight,
                                                        color = textColor,
                                                        fontSize = fontSize,
                                                        isVertical = block.isVertical,
                                                        boldness = region.textBoldness,
                                                        context = context,
                                                        fontFamilyName = block.fontFamily,
                                                        borderColor = region.textBorderColor,
                                                        borderThickness = region.textBorderThickness,
                                                        borderAlpha = region.textBorderAlpha,
                                                        editMode = editTranslationMode
                                                    )
                                                }
                                            } else {
                                                drawText(
                                                    text = block.text,
                                                    x = textLeft,
                                                    y = textTop,
                                                    width = textWidth,
                                                    height = textHeight,
                                                    color = textColor,
                                                    fontSize = fontSize,
                                                    isVertical = block.isVertical,
                                                    boldness = region.textBoldness,
                                                    context = context,
                                                    fontFamilyName = block.fontFamily,
                                                    borderColor = region.textBorderColor,
                                                    borderThickness = region.textBorderThickness,
                                                    borderAlpha = region.textBorderAlpha,
                                                    editMode = editTranslationMode
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
        
        // Loading indicator ở cuối danh sách khi đang tải thêm ảnh
        if (isLoadingMoreImages && remainingImagesCount > 0) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Đang tải thêm $remainingImagesCount ảnh...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// Helper function để áp dụng saturation cho màu
private fun Color.applySaturation(saturation: Float): Color {
    val hsv = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsv)
    hsv[1] = (hsv[1] * saturation).coerceIn(0f, 1f) // Adjust saturation
    return Color(ColorUtils.HSLToColor(hsv))
}