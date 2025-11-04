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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withContext
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
import coil.ImageLoader
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
    val lineSpacing: Float = 1.0f, // Khoảng cách dòng (multiplier)
    val textBorderColor: Color? = null, // Màu viền chữ
    val textBorderThickness: Float = 0.0f, // Độ dày viền chữ (0.0 - 5.0)
    val textBorderAlpha: Float = 1.0f, // Độ trong suốt của viền chữ (0.0 - 1.0)
    val textShadowColor: Color? = null, // Màu đổ bóng chữ
    val textShadowAlpha: Float = 1.0f, // Độ trong suốt của đổ bóng (0.0 - 1.0)
    val textShadowRadius: Float = 0f // Độ dày/blur radius của đổ bóng (px). 0 = tắt
)

// Precomputed region used for drawing; computed off the main composition pass to
// avoid repeated allocations and heavy calculations during scroll/recompose.
data class PrecomputedRegion(
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
    val lineSpacing: Float = 1.0f,
    val textBorderColor: Color? = null,
    val textBorderThickness: Float = 0.0f,
    val textBorderAlpha: Float = 1.0f
    ,
    val textShadowColor: Color? = null,
    val textShadowAlpha: Float = 1.0f,
    val textShadowRadius: Float = 0f
)

@Composable
fun ImageViewer(
    imageUris: List<Uri>,
    translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
    translationEnabled: Boolean,
    // Map indicating which URIs are expected to have translations (true = translated in DB or queued)
    translatedStatus: Map<Uri, Boolean> = emptyMap(),
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
    // function to retrieve DB imageId for a uri (may be null)
    getImageIdForUri: (Uri) -> Long? = { null },
    // accessor to retrieve per-image version to force cache invalidation when file replaced
    getImageVersionForUri: (Uri) -> Int? = { null },
    // accessor to retrieve an ad-hoc reload token (timestamp) for a uri so caller can force reloads
    getReloadTokenForUri: (Uri) -> Long? = { null },
    isLoadingMoreImages: Boolean = false,
    remainingImagesCount: Int = 0
) {
    val context = LocalContext.current
    // Determine appropriate read permission for the current OS
    val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }
    var translationVersion by remember { mutableStateOf(0) }
    val newlyTranslated = remember { mutableStateMapOf<Uri, Boolean>() }
    // Windowing state: only render heavy overlays for indices inside this range
    val visibleRange = remember { mutableStateOf(IntRange(0, -1)) }
    val prefetchBuffer = 2 // how many items before/after visible area to prefetch
    val imageLoader = ImageLoader(context)
    // Read persisted preference to determine whether disk cache should be suppressed
    val suppressDiskCachePref = remember {
        try {
            val prefs = context.getSharedPreferences("ocrmanga_prefs", android.content.Context.MODE_PRIVATE)
            prefs.getBoolean("suppress_coil_disk_cache", false)
        } catch (e: Exception) {
            false
        }
    }

    // Observe LazyListState visible items and compute expanded window + prefetch
    LaunchedEffect(lazyListState, imageUris) {
        snapshotFlow { lazyListState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { visibleIndices ->
                if (visibleIndices.isNotEmpty()) {
                    val min = visibleIndices.minOrNull() ?: 0
                    val max = visibleIndices.maxOrNull() ?: 0
                    val start = (min - prefetchBuffer).coerceAtLeast(0)
                    val end = (max + prefetchBuffer).coerceAtMost(imageUris.size - 1)
                    visibleRange.value = IntRange(start, end)
                    // Prefetch images inside expanded window
                    for (i in start..end) {
                        try {
                            // Create a lightweight request matching the one used by the item.
                            val reloadToken = try { getReloadTokenForUri(imageUris[i]) ?: 0L } catch (e: Exception) { 0L }
                            val prefetchReq = ImageRequest.Builder(context)
                                .data(imageUris[i])
                                // include the uri+reloadToken in the cache key so replacing the image invalidates the cache for that slot
                                .memoryCacheKey("image-index-$i:${imageUris[i].toString()}:rt$reloadToken")
                                .diskCacheKey("image-index-$i:${imageUris[i].toString()}:rt$reloadToken")
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                    .diskCachePolicy(if (suppressDiskCachePref) coil.request.CachePolicy.DISABLED else coil.request.CachePolicy.ENABLED)
                                .build()
                            imageLoader.enqueue(prefetchReq)
                        } catch (e: Exception) {
                            // ignore prefetch errors
                        }
                    }
                } else {
                    visibleRange.value = IntRange(0, -1)
                }
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
        // Use a stable key based on the image Uri only. Including the index in the key
        // causes Compose to reuse/replace items when the list grows, which led to
        // previously-last images being replaced by newly added images.
        itemsIndexed(items = imageUris, key = { index, uri ->
            // prefer stable DB imageId when available, fallback to index:uri
            val id = getImageIdForUri(uri)
            if (id != null) id.toString() else "$index:${uri.toString()}"
        }) { index, uri ->
            val isInWindow = index in visibleRange.value
            // Luôn ưu tiên translatedTexts mới từ translation mode
            // Only prepare translated blocks when the item is in window to avoid expensive work while scrolling
            val currentTranslatedBlocks = if (isInWindow) translatedTexts[uri]?.second?.map { block ->
                // ✅ Chuẩn hóa màu overlay & text, đảm bảo luôn có alpha
                val overlayInt = (block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()) or 0xFF000000.toInt()
                val textInt = (block.customTextColor ?: computeDefaultTextColor(overlayInt, block.averageBackgroundColor)) or 0xFF000000.toInt()
                // Keep original font sizes on the block; edit-mode scaling is applied when rendering.
                DragBlockState(
                    block = block.copy(
                        customOverlayColor = overlayInt,
                        customTextColor = textInt,
                        fontSize = block.fontSize
                    ),
                    // No explicit edited font size at load
                    fontSize = null,
                    rotation = block.rotation ?: 0f,
                    whiteoutColor = Color(overlayInt),
                    textColor = Color(textInt),
                    overlayAlpha = block.overlayAlpha,
                    textBoldness = block.textBoldness,
                    overlaySaturation = block.overlaySaturation,
                    textSaturation = block.textSaturation,
                    lineSpacing = block.lineSpacing,
                    textBorderColor = block.customBorderColor?.let { Color(it or 0xFF000000.toInt()) },
                    textBorderThickness = block.borderThickness,
                    textBorderAlpha = block.borderAlpha,
                    // Map saved shadow values from TextBlockInfo into DragBlockState so they are visible in view mode
                    textShadowColor = block.customShadowColor?.let { Color(it or 0xFF000000.toInt()) },
                    textShadowAlpha = block.shadowAlpha ?: 1.0f,
                    textShadowRadius = block.shadowRadius ?: 0f,
                    // lineSpacing already set above
                )
            } else null

            // Keep dragBlocks lightweight when offscreen to avoid allocations and heavy updates
            var dragBlocks by remember(uri, translationVersion, translatedTexts[uri], isInWindow) {
                mutableStateOf(if (isInWindow) (currentTranslatedBlocks ?: emptyList()) else (dragBlocksMap[uri] ?: emptyList()))
            }

            // Only update dragBlocks when item becomes visible (isInWindow) or when translationVersion changes
            LaunchedEffect(uri, isInWindow, translationVersion) {
                if (isInWindow && !editTranslationMode) {
                    val rawNewBlocks = translatedTexts[uri]?.second?.map {
                        DragBlockState(
                            block = it,
                            fontSize = null,
                            rotation = it.rotation ?: 0f,
                            whiteoutColor = it.customOverlayColor?.let { c -> Color(c) },
                            textColor = it.customTextColor?.let { c -> Color(c) },
                            overlayAlpha = it.overlayAlpha,
                            textBoldness = it.textBoldness,
                            overlaySaturation = it.overlaySaturation,
                            textSaturation = it.textSaturation,
                            lineSpacing = it.lineSpacing,
                            textBorderColor = it.customBorderColor?.let { c -> Color(c) },
                            textBorderThickness = it.borderThickness,
                            textBorderAlpha = it.borderAlpha,
                            textShadowColor = it.customShadowColor?.let { c -> Color(c or 0xFF000000.toInt()) },
                            textShadowAlpha = it.shadowAlpha ?: 1.0f,
                            textShadowRadius = it.shadowRadius ?: 0f
                        )
                    } ?: emptyList()

                    // Merge user-edited visual properties (if any) from previously stored dragBlocksMap
                    val existing = dragBlocksMap[uri]
                    val merged = if (existing != null && existing.isNotEmpty()) {
                        rawNewBlocks.map { nb ->
                            // try to find a matching existing block by bounds + text
                            val match = existing.find { eb ->
                                eb.block.bounds == nb.block.bounds && eb.block.text == nb.block.text
                            }
                            if (match != null) {
                                nb.copy(
                                    block = nb.block.copy(
                                        // Preserve applyMerge flag from edited block (should be false after edit)
                                        applyMerge = match.block.applyMerge
                                    ),
                                    // preserve any user-set shadow properties and custom lineSpacing
                                    textShadowColor = match.textShadowColor,
                                    textShadowAlpha = match.textShadowAlpha,
                                    textShadowRadius = match.textShadowRadius,
                                    lineSpacing = match.lineSpacing,
                                    // also preserve edited font size/offset if present
                                    fontSize = match.fontSize,
                                    rotation = match.rotation,
                                    // keep any manual offset made during editing
                                    offset = match.offset
                                )
                            } else nb
                        }
                    } else rawNewBlocks

                    dragBlocks = merged
                    dragBlocksMap[uri] = merged
                    newlyTranslated[uri] = true
                }
            }

            LaunchedEffect(dragBlocks, isInWindow) {
                if (isInWindow) dragBlocksMap[uri] = dragBlocks
            }
            var selectedIndex by remember(uri, editTranslationMode) { mutableStateOf<Int?>(null) }
            fun getWhiteoutShape(idx: Int) = if (idx < dragBlocks.size) dragBlocks[idx].block.shapeType else 0
            var draggingIndex by remember { mutableStateOf<Int?>(null) }
            var lastDragPos by remember { mutableStateOf(Offset.Zero) }
            
            // Only apply merge logic (splitNonOverlappingBoxes) during translation/view mode when applyMerge is true
            // Do NOT apply merge during edit mode or when blocks are manually edited
            val shouldApplyMerge = !editTranslationMode && dragBlocks.all { it.block.applyMerge }
            val shrinkedBlocks = if (shouldApplyMerge) {
                splitNonOverlappingBoxes(dragBlocks.map { it.block })
            } else {
                dragBlocks.map { it.block }
            }

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

                    // Only load image dimensions when visible to avoid I/O during fast scroll
                    if (isInWindow) {
                        // Wait for permissionGranted to be true before attempting to open content URIs
                        LaunchedEffect(uri, permissionGranted) {
                            if (!permissionGranted) {
                                // Request permission; the launcher will update permissionGranted.
                                try {
                                    permissionLauncher.launch(readPermission)
                                } catch (e: Exception) {
                                    // launcher may throw if called too early; fall back to defaults
                                    Log.w("ImageViewer", "Permission launcher failed to start for $uri", e)
                                }
                                // Use fallback sizes until permission is granted to avoid SecurityException
                                originalImageWidth = 1280f
                                originalImageHeight = 1808f
                                isImageLoaded = true
                                return@LaunchedEffect
                            }
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
                    }

                    // Build and remember ImageRequest per index so Compose doesn't recreate
                    // requests on every recomposition. Use the image index as the cache key
                    // so Coil can reuse the decoded bitmap when the same logical slot is shown.
                    // include image version (from ViewModel) in the cache key when available to force reloads
                    val imageVersion = try { getImageVersionForUri(uri) ?: 0 } catch (e: Exception) { 0 }
                    val reloadToken = try { getReloadTokenForUri(uri) ?: 0L } catch (e: Exception) { 0L }
                    val imageRequest = remember(index, uri, imageVersion) {
                        ImageRequest.Builder(context)
                            .data(uri)
                            // Use index+uri+version+reloadToken-based cache keys so when the uri for a slot changes the cache is invalidated
                            .memoryCacheKey("image-index-$index:${uri.toString()}:v$imageVersion:rt$reloadToken")
                            .diskCacheKey("image-index-$index:${uri.toString()}:v$imageVersion:rt$reloadToken")
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(if (suppressDiskCachePref) coil.request.CachePolicy.DISABLED else coil.request.CachePolicy.ENABLED)
                            .build()
                    }

                    AsyncImage(
                        model = imageRequest,
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
                    // No loading overlay: images without loaded translations are shown normally.
                    // Precompute drawing regions off the UI thread to avoid heavy work during
                    // fast scrolling/recomposition. The produced list is used by drawWithCache.
                    val precomputedRegionsState = remember(uri, translationVersion) { mutableStateOf<List<PrecomputedRegion>>(emptyList()) }
                    LaunchedEffect(uri, translationVersion, dragBlocks, imageWidth, imageHeight, isInWindow) {
                        if (!isInWindow) {
                            // clear when offscreen to reduce memory
                            precomputedRegionsState.value = emptyList()
                            return@LaunchedEffect
                        }
                        withContext(kotlinx.coroutines.Dispatchers.Default) {
                            val list = dragBlocks.mapNotNull { dragBlock ->
                                val block = dragBlock.block
                                if (block.text.isBlank()) return@mapNotNull null
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
                                val fontSize = if (editTranslationMode) {
                                    com.example.ocrmanga.ui.screens.view.computeEditModeFontSize(
                                        block = dragBlock.block,
                                        editedFontSize = dragBlock.fontSize
                                    )
                                } else dragBlock.block.fontSize

                                PrecomputedRegion(
                                    block = block,
                                    rect = Rect(scaledLeft, scaledTop, scaledLeft + scaledWidth, scaledTop + scaledBlockHeight2),
                                    fontSize = fontSize,
                                    rotation = dragBlock.rotation,
                                    whiteoutColor = dragBlock.whiteoutColor,
                                    textColor = dragBlock.textColor,
                                    overlayAlpha = dragBlock.overlayAlpha,
                                    textBoldness = dragBlock.textBoldness,
                                    overlaySaturation = dragBlock.overlaySaturation,
                                    textSaturation = dragBlock.textSaturation,
                                    lineSpacing = dragBlock.lineSpacing,
                                    textBorderColor = dragBlock.textBorderColor,
                                    textBorderThickness = dragBlock.textBorderThickness,
                                    textBorderAlpha = dragBlock.textBorderAlpha,
                                    textShadowColor = dragBlock.textShadowColor,
                                    textShadowAlpha = dragBlock.textShadowAlpha,
                                    textShadowRadius = dragBlock.textShadowRadius
                                )
                            }
                            precomputedRegionsState.value = list
                        }
                    }
                    // Only draw heavy overlays when the item is inside the visible window
                    if (isInWindow && translationEnabled && translatedTexts.containsKey(uri) && isImageLoaded && imageLoadState is AsyncImagePainter.State.Success) {
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
                                                        
                                                        // Cập nhật bounds mới và reset offset về Zero
                                                        val updatedBlock = dragBlock.copy(
                                                            block = block.copy(bounds = newBounds),
                                                            offset = Offset.Zero
                                                        )
                                                        val updatedList = dragBlocks.toMutableList()
                                                        updatedList[idx] = updatedBlock
                                                        
                                                        // QUAN TRỌNG: Cập nhật cả local dragBlocks và shared map
                                                        dragBlocks = updatedList
                                                        dragBlocksMap[uri] = updatedList
                                                        draggingIndex = null
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            // Canvas draw scope: draw overlays using precomputed regions
                            val regions = precomputedRegionsState.value
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
                                // Xác định màu text - ưu tiên màu tùy chỉnh
                                val baseTextColor = when {
                                    i == draggingIndex -> Color.Red
                                    customTextColor != null -> customTextColor
                                    else -> {
                                        // Khi chưa set màu custom, tính toán dựa trên brightness của overlay
                                        // Lấy overlay color (ưu tiên whiteoutColor, fallback sang averageBackgroundColor)
                                        val overlayColor = if (region.whiteoutColor != null) {
                                            region.whiteoutColor.toArgb()
                                        } else {
                                            block.averageBackgroundColor
                                        }

                                        if (overlayColor != null) {
                                            // Tính brightness sử dụng công thức chuẩn RGB
                                            val r = (overlayColor shr 16) and 0xFF
                                            val g = (overlayColor shr 8) and 0xFF
                                            val b = overlayColor and 0xFF
                                            val brightness = (r * 299 + g * 587 + b * 114) / 1000

                                            // Sử dụng text đen cho overlay sáng (brightness > 128)
                                            // Sử dụng text trắng cho overlay tối (brightness <= 128)
                                            if (brightness > 128) Color.Black else Color.White
                                        } else {
                                            // Mặc định cho nền trắng -> text đen
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
                                            lineSpacing = region.lineSpacing,
                                            borderColor = region.textBorderColor,
                                            borderThickness = region.textBorderThickness,
                                            borderAlpha = region.textBorderAlpha,
                                            editMode = editTranslationMode,
                                            shadowColor = region.textShadowColor,
                                            shadowAlpha = region.textShadowAlpha,
                                            shadowRadius = region.textShadowRadius
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
                                        lineSpacing = region.lineSpacing,
                                        borderColor = region.textBorderColor,
                                        borderThickness = region.textBorderThickness,
                                        borderAlpha = region.textBorderAlpha,
                                        editMode = editTranslationMode,
                                        shadowColor = region.textShadowColor,
                                        shadowAlpha = region.textShadowAlpha,
                                        shadowRadius = region.textShadowRadius
                                    )
                                }
                            }
                        }
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