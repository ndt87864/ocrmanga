package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.ImageLoader
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.ui.screens.view.TranslationOverlay
import com.example.ocrmanga.ui.screens.view.MagnifierPopup
import com.example.ocrmanga.data.models.TranslationStatus
import java.io.IOException

data class DragBlockState(
    val block: TextBlockInfo,
    val offset: Offset = Offset.Zero,
    val fontSize: Float? = null,
    val rotation: Float = 0f,
    val overlayRotation: Float? = null, // Góc xoay riêng của overlay (độ), null = dùng rotation của text
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
    val textShadowRadius: Float = 0f, // Độ dày/blur radius của đổ bóng (px). 0 = tắt
    val overlayInset: Float = 0f, // Khoảng cách inset của overlay (0.0 - max) - deprecated
    val overlayInsetHorizontal: Float = 0f, // Inset theo chiều ngang
    val overlayInsetVertical: Float = 0f // Inset theo chiều dọc
)

// Precomputed region used for drawing; computed off the main composition pass to
// avoid repeated allocations and heavy calculations during scroll/recompose.
data class PrecomputedRegion(
    val block: TextBlockInfo,
    val rect: Rect,
    val fontSize: Float,
    val rotation: Float,
    val overlayRotation: Float? = null, // Góc xoay riêng của overlay
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
    val textShadowRadius: Float = 0f,
    val overlayInset: Float = 0f,
    val overlayInsetHorizontal: Float = 0f,
    val overlayInsetVertical: Float = 0f
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
    remainingImagesCount: Int = 0,
    // Map trạng thái dịch của từng ảnh để hiển thị overlay thông báo
    translatingImages: Map<Uri, com.example.ocrmanga.data.models.TranslationStatus> = emptyMap(),
    // Text Removal Params
    isTextRemovalMode: Boolean = false,
    onToggleTextRemovalMode: () -> Unit = {},
    onRemoveTextWithMask: (Uri, android.graphics.Bitmap) -> Unit = { _, _ -> }
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
            val currentTranslatedBlocks = if (isInWindow) translatedTexts[uri]?.second
                ?.filter { !it.pendingDelete }
                ?.map { block ->
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
                        overlayInset = block.overlayInset,
                        overlayInsetHorizontal = block.overlayInsetHorizontal,
                        overlayInsetVertical = block.overlayInsetVertical
                    )
                } else null

            // Keep dragBlocks lightweight when offscreen to avoid allocations and heavy updates
            var dragBlocks by remember(uri, translationVersion, translatedTexts[uri], isInWindow) {
                mutableStateOf(if (isInWindow) (currentTranslatedBlocks ?: emptyList()) else (dragBlocksMap[uri] ?: emptyList()))
            }

            // Only update dragBlocks when item becomes visible (isInWindow) or when translationVersion changes
            // Thêm translatedTexts[uri] và editTranslationMode vào key để rebuild khi save edit
            LaunchedEffect(uri, isInWindow, translationVersion, translatedTexts[uri], editTranslationMode) {
                if (isInWindow && !editTranslationMode) {
                    //Log.d("ImageViewer", "[REBUILD dragBlocks] uri=$uri editTranslationMode=$editTranslationMode")
                    val rawNewBlocks = translatedTexts[uri]?.second?.mapIndexed { idx, it ->
                        //Log.d("ImageViewer", "[REBUILD] idx=$idx overlayRotation=${it.overlayRotation} inset=${it.overlayInset} insetH=${it.overlayInsetHorizontal} insetV=${it.overlayInsetVertical} from translatedTexts")
                        DragBlockState(
                            block = it,
                            fontSize = null,
                            rotation = it.rotation ?: 0f,
                            overlayRotation = it.overlayRotation,
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
                            textShadowRadius = it.shadowRadius ?: 0f,
                            overlayInset = it.overlayInset,
                            overlayInsetHorizontal = it.overlayInsetHorizontal,
                            overlayInsetVertical = it.overlayInsetVertical
                        )
                    } ?: emptyList()

                    // Merge user-edited visual properties (if any) from previously stored dragBlocksMap
                    val existing = dragBlocksMap[uri]
                    //Log.d("ImageViewer", "[MERGE] uri=$uri existing=${existing?.size ?: 0} rawNewBlocks=${rawNewBlocks.size}")
                    val merged = if (existing != null && existing.isNotEmpty()) {
                        rawNewBlocks.mapIndexed { idx, nb ->
                            // try to find a matching existing block by bounds + text
                            val match = existing.find { eb ->
                                eb.block.bounds == nb.block.bounds && eb.block.text == nb.block.text
                            }
                            if (match != null) {
                                // Preserve applyMerge from edited block
                                val preserveApplyMerge = match.block.applyMerge
                                
                                
                                // Chỉ lấy từ match (old) nếu DB không có (= default) VÀ match có giá trị khác default
                                val finalInset = if (nb.overlayInset != 0f) nb.overlayInset else match.overlayInset
                                val finalInsetH = if (nb.overlayInsetHorizontal != 0f) nb.overlayInsetHorizontal else match.overlayInsetHorizontal
                                val finalInsetV = if (nb.overlayInsetVertical != 0f) nb.overlayInsetVertical else match.overlayInsetVertical
                                
                                //Log.d("ImageViewer", "[MERGE] idx=$idx nb.inset=${nb.overlayInset} match.inset=${match.overlayInset} final=$finalInset")
                                
                                val finalLineSpacing = if (nb.lineSpacing != 1.0f && nb.lineSpacing != 1.1f && nb.lineSpacing != 2f) nb.lineSpacing else match.lineSpacing
                                val finalShadowColor = nb.textShadowColor ?: match.textShadowColor
                                val finalShadowAlpha = if (nb.textShadowAlpha != 1.0f) nb.textShadowAlpha else match.textShadowAlpha
                                val finalShadowRadius = if (nb.textShadowRadius != 0f) nb.textShadowRadius else match.textShadowRadius
                                val finalRotation = if (nb.rotation != 0f) nb.rotation else match.rotation
                                val finalOverlayRotation = nb.overlayRotation ?: match.overlayRotation
                                val finalFontSize = nb.fontSize ?: match.fontSize
                                
                                nb.copy(
                                    block = nb.block.copy(
                                        applyMerge = preserveApplyMerge
                                    ),
                                    textShadowColor = finalShadowColor,
                                    textShadowAlpha = finalShadowAlpha,
                                    textShadowRadius = finalShadowRadius,
                                    lineSpacing = finalLineSpacing,
                                    overlayInset = finalInset,
                                    overlayInsetHorizontal = finalInsetH,
                                    overlayInsetVertical = finalInsetV,
                                    fontSize = finalFontSize,
                                    rotation = finalRotation,
                                    overlayRotation = finalOverlayRotation,
                                    // Chỉ preserve offset nếu user đã drag
                                    offset = if (match.offset != androidx.compose.ui.geometry.Offset.Zero) match.offset else nb.offset
                                )
                            } else {
                                // ✅ Không tìm thấy match - giữ nguyên giá trị từ DB
                                nb
                            }
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

            // State for text removal painting (Mask)
            // List of Path and StrokeWidth
            val textRemovalPaths = remember { mutableStateListOf<Pair<androidx.compose.ui.graphics.Path, Float>>() }
            val currentPaintingPath = remember { mutableStateOf<androidx.compose.ui.graphics.Path?>(null) }
            // Biến đếm để ép buộc Canvas vẽ lại khi Path thay đổi content bên trong
            var drawTrigger by remember { mutableStateOf(0) }
            // State cho magnifier popup
            var magnifierPosition by remember { mutableStateOf<Offset?>(null) }
            
            LaunchedEffect(isTextRemovalMode) {
                if (!isTextRemovalMode) {
                    textRemovalPaths.clear()
                    currentPaintingPath.value = null
                    magnifierPosition = null
                }
            }
            
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
                    .pointerInput(uri, editTranslationMode, isTextRemovalMode) {
                        if (!isTextRemovalMode) {
                            detectTapGestures(
                                onLongPress = {
                                    if (!editTranslationMode) {
                                        onImageMenuUriChange(uri)
                                        onShowImageMenuChange(true)
                                    }
                                }
                            )
                        }
                    }
            ) {
                if (editTranslationMode) {
                    TranslationEditor(
                        dragBlocks = dragBlocks,
                        selectedIndex = selectedIndex,
                        onDragBlocksChange = { newBlocks -> 
                            dragBlocks = newBlocks
                            dragBlocksMap[uri] = newBlocks
                        },
                        onSelectedIndexChange = { newIndex -> selectedIndex = newIndex },
                        onSave = {
                            // Lưu thay đổi và chuyển về chế độ xem
                            // Sử dụng dragBlocksMap[uri] thay vì dragBlocks để đảm bảo lấy giá trị mới nhất
                            val latestBlocks = dragBlocksMap[uri] ?: dragBlocks
                            latestBlocks.forEachIndexed { idx, block ->
                                Log.d("ImageViewer", "[SAVE EDIT] idx=$idx overlayRotation=${block.overlayRotation} rotation=${block.rotation}")
                            }
                            onSaveTranslation(uri, latestBlocks)
                            onEditTranslationModeToggle(false)
                        },
                        isTextRemovalMode = isTextRemovalMode,
                        onToggleTextRemovalMode = onToggleTextRemovalMode
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
                    
                    // Lấy trạng thái dịch của ảnh hiện tại
                    val translationStatus = translatingImages[uri] ?: com.example.ocrmanga.data.models.TranslationStatus.IDLE

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
                                Log.d("ImageViewer", "[IMAGE SIZE] Updated: ${imageWidth}x${imageHeight}")
                            },
                        contentScale = ContentScale.FillWidth,
                        onState = { state -> imageLoadState = state }
                    )
                    // No loading overlay: images without loaded translations are shown normally.
                    // Precompute drawing regions off the UI thread to avoid heavy work during
                    // fast scrolling/recomposition. The produced list is used by drawWithCache.
                    val precomputedRegionsState = remember(uri, translationVersion) { mutableStateOf<List<PrecomputedRegion>>(emptyList()) }
                    // Read configuration once in composable scope to avoid calling composable APIs inside coroutine
                    val _configuration_for_screen = LocalConfiguration.current
                    val _screenWidthDp_for_screen = _configuration_for_screen.screenWidthDp.toFloat()
                    val _screenHeightDp_for_screen = _configuration_for_screen.screenHeightDp.toFloat()

                    LaunchedEffect(uri, translationVersion, dragBlocks, imageWidth, imageHeight, isInWindow, _screenWidthDp_for_screen, _screenHeightDp_for_screen) {
                        if (!isInWindow) {
                            // clear when offscreen to reduce memory
                            precomputedRegionsState.value = emptyList()
                            return@LaunchedEffect
                        }
                        // Tính scale factor dựa trên screen width để font size tự động thay đổi khi xoay màn hình
                        val screenWidthDp = _screenWidthDp_for_screen
                        val baseWidthDp = 360f // standard phone width in dp
                        val screenScaleFactor = (screenWidthDp / baseWidthDp).coerceIn(0.5f, 2.0f) // clamp between 0.5 and 2.0
                        
                        withContext(kotlinx.coroutines.Dispatchers.Default) {
                            val list = dragBlocks.filter { !it.block.pendingDelete }.mapNotNull { dragBlock ->
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

                                // Áp dụng screen scale factor để font size tự động thay đổi khi xoay màn hình
                                val scaledFontSize = fontSize * screenScaleFactor

                                // Scale overlay inset theo cùng tỉ lệ với rect để giữ đúng tỉ lệ khi xoay màn hình
                                val scaledInsetH = dragBlock.overlayInsetHorizontal * scale
                                val scaledInsetV = dragBlock.overlayInsetVertical * scale
                                val scaledInset = dragBlock.overlayInset * scale

                                PrecomputedRegion(
                                    block = block,
                                    rect = Rect(scaledLeft, scaledTop, scaledLeft + scaledWidth, scaledTop + scaledBlockHeight2),
                                    fontSize = scaledFontSize,
                                    rotation = dragBlock.rotation,
                                    overlayRotation = dragBlock.overlayRotation,
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
                                    textShadowRadius = dragBlock.textShadowRadius,
                                    overlayInset = scaledInset,
                                    overlayInsetHorizontal = scaledInsetH,
                                    overlayInsetVertical = scaledInsetV
                                )
                            }
                            precomputedRegionsState.value = list
                        }
                    }
                    // Only draw heavy overlays when the item is inside the visible window
                    // Hiển thị Canvas khi có bản dịch HOẶC khi đang ở chế độ xóa text
                    if (isInWindow && isImageLoaded && imageLoadState is AsyncImagePainter.State.Success && (isTextRemovalMode || (translationEnabled && translatedTexts.containsKey(uri)))) {
                        Canvas(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(shrinkedBlocks, editTranslationMode, isTextRemovalMode) {
                            if (isTextRemovalMode) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitFirstDown()
                                        val startPos = down.position
                                        val path = androidx.compose.ui.graphics.Path().apply { 
                                            moveTo(startPos.x, startPos.y) 
                                        }
                                        currentPaintingPath.value = path
                                        magnifierPosition = startPos // Hiển thị magnifier
                                        Log.d("ImageViewer", "[MAGNIFIER] Show at position: $startPos")
                                        drawTrigger++ // Force initial draw
                                        
                                        down.consume()
                                        
                                        // Theo dõi chuyển động drag cho đến khi nhấc tay
                                        drag(down.id) { change ->
                                            val pos = change.position
                                            path.lineTo(pos.x, pos.y)
                                            magnifierPosition = pos // Cập nhật vị trí magnifier
                                            // Cập nhật trigger để Canvas vẽ lại nét đang vẽ
                                            drawTrigger++
                                            change.consume()
                                        }
                                        
                                        // Khi kết thúc (nhấc tay hoặc bị hủy)
                                        currentPaintingPath.value?.let { finalPath ->
                                            textRemovalPaths.add(finalPath to 40f)
                                        }
                                        currentPaintingPath.value = null
                                        magnifierPosition = null // Ẩn magnifier
                                        Log.d("ImageViewer", "[MAGNIFIER] Hide")
                                        drawTrigger++
                                    }
                                }
                            } else if (editTranslationMode) {
                                // Logic cho drag blocks
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
                                                
                                                val updatedBlock = dragBlock.copy(
                                                    block = block.copy(bounds = newBounds),
                                                    offset = Offset.Zero
                                                )
                                                val updatedList = dragBlocks.toMutableList()
                                                updatedList[idx] = updatedBlock
                                                
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

                                // Áp dụng overlayInset riêng cho từng chiều
                                val insetH = region.overlayInsetHorizontal
                                val insetV = region.overlayInsetVertical
                                val insetRect = if (insetH > 0f || insetV > 0f) {
                                    Rect(
                                        left = rect.left + insetH,
                                        top = rect.top + insetV,
                                        right = rect.right - insetH,
                                        bottom = rect.bottom - insetV
                                    ).takeIf { it.width > 0 && it.height > 0 } ?: rect
                                } else {
                                    rect
                                }

                                // Xác định block đang được chọn bằng so sánh object hoặc thuộc tính duy nhất
                                val isSelected = selectedIndex != null
                                    && selectedIndex!! < dragBlocks.size
                                    && dragBlocks[selectedIndex!!].block == block

                                // Lấy góc xoay overlay (dùng overlayRotation nếu có, fallback về 0)
                                val overlayRotationAngle = region.overlayRotation ?: 0f
                                
                                // Tính tâm xoay cho overlay
                                val overlayCenterX = rect.left + rect.width / 2
                                val overlayCenterY = rect.top + rect.height / 2

                                // Hàm vẽ overlay (được gọi trong hoặc ngoài withTransform)
                                fun drawOverlayContent() {
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
                                                topLeft = Offset(insetRect.left, insetRect.top),
                                                size = Size(insetRect.width, insetRect.height)
                                            )
                                        } else {
                                            drawRect(
                                                color = finalOverlayColor,
                                                topLeft = Offset(insetRect.left, insetRect.top),
                                                size = Size(insetRect.width, insetRect.height)
                                            )
                                        }
                                    } else {
                                        // Sử dụng overlay bán trong suốt cho nền có màu
                                        drawTranslucentOverlay(
                                            rect = insetRect,
                                            backgroundType = block.backgroundType,
                                            averageBackgroundColor = block.averageBackgroundColor,
                                            originalTextColor = block.originalTextColor,
                                            shapeType = block.shapeType
                                        )
                                    }
                                }

                                // Vẽ overlay với rotation nếu có
                                if (overlayRotationAngle != 0f) {
                                    withTransform({
                                        rotate(overlayRotationAngle, Offset(overlayCenterX, overlayCenterY))
                                    }) {
                                        drawOverlayContent()
                                    }
                                } else {
                                    drawOverlayContent()
                                }

                                // Vẽ viền cho overlay
                                if (editTranslationMode) {
                                    // Viền cũng cần xoay theo overlayRotation
                                    fun drawBorderContent() {
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
                                    
                                    if (overlayRotationAngle != 0f) {
                                        withTransform({
                                            rotate(overlayRotationAngle, Offset(overlayCenterX, overlayCenterY))
                                        }) {
                                            drawBorderContent()
                                        }
                                    } else {
                                        drawBorderContent()
                                    }
                                }
                                
                                 // (Đã di chuyển logic vẽ mask xóa text ra ngoài vòng lặp regions)

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
                                        drawTextOnCanvas(
                                            this,
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
                                    drawTextOnCanvas(
                                        this,
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

                            // VẼ MASK XÓA TEXT (Vẽ một lần duy nhất, bên ngoài vòng lặp regions)
                            if (isTextRemovalMode) {
                                val trigger = drawTrigger // Tham chiếu trigger để ép buộc vẽ lại
                                val strokeStyle = Stroke(width = 40f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                                textRemovalPaths.forEach { (path, _) ->
                                    drawPath(path, Color.Red.copy(alpha = 0.5f), style = strokeStyle)
                                }
                                currentPaintingPath.value?.let { path ->
                                    drawPath(path, Color.Red.copy(alpha = 0.5f), style = strokeStyle)
                                }
                            }
                        }
                    }
                    
                    // Hiển thị overlay thông báo khi đang dịch ảnh này
                    TranslationOverlay(
                        status = translationStatus,
                        modifier = Modifier.matchParentSize()
                    )

                    // Nút XÓA VÙNG CHỌN (hiển thị khi có path vẽ)
                    if (isTextRemovalMode && textRemovalPaths.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize() // Fill box cha để align
                                .padding(8.dp),
                            contentAlignment = androidx.compose.ui.Alignment.BottomCenter
                        ) {
                            androidx.compose.material3.Button(
                                onClick = {
                                    if (originalImageWidth > 0 && originalImageHeight > 0 && imageWidth > 0 && imageHeight > 0) {
                                        // Tạo Mask Bitmap
                                        val maskBmp = android.graphics.Bitmap.createBitmap(
                                            originalImageWidth.toInt(),
                                            originalImageHeight.toInt(),
                                            android.graphics.Bitmap.Config.ARGB_8888
                                        )
                                        val maskCanvas = android.graphics.Canvas(maskBmp)
                                        maskCanvas.drawColor(android.graphics.Color.BLACK) // Nền đen

                                        val scale = originalImageWidth / imageWidth
                                        val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                                        
                                        val paint = android.graphics.Paint().apply {
                                            color = android.graphics.Color.WHITE // Vùng xóa màu trắng
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeWidth = 40f * scale
                                            strokeCap = android.graphics.Paint.Cap.ROUND
                                            strokeJoin = android.graphics.Paint.Join.ROUND
                                        }

                                        textRemovalPaths.forEach { (path, _) ->
                                            val androidPath = path.asAndroidPath()
                                            androidPath.transform(matrix)
                                            maskCanvas.drawPath(androidPath, paint)
                                        }
                                        
                                        onRemoveTextWithMask(uri, maskBmp)
                                        textRemovalPaths.clear()
                                    }
                                },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Red)
                            ) {
                                Text("Xóa vùng này", color = Color.White)
                            }
                        }
                    }
                
                // Magnifier popup khi đang tô vùng xóa - ĐẶT CUỐI CÙNG ĐỂ CÓ Z-INDEX CAO NHẤT
                magnifierPosition?.let { pos ->
                    Log.d("ImageViewer", "[MAGNIFIER] Rendering MagnifierPopup at: $pos, imageSize: ${imageWidth}x${imageHeight}")
                    MagnifierPopup(
                        magnifierPosition = pos,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        imageUri = uri, // ✅ Truyền Uri ảnh vào
                        modifier = Modifier
                            .matchParentSize()
                            .zIndex(999f) // Z-index RẤT CAO để luôn hiển thị trên cùng
                    )
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