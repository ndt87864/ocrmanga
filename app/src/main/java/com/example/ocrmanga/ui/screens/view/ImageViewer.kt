package com.example.ocrmanga.ui.screens.view

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.ocrmanga.utils.AppLogger as Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.models.TranslationStatus
import kotlinx.coroutines.withContext
import java.io.IOException

data class DragBlockState(
    val block: TextBlockInfo,
    val offset: Offset = Offset.Zero,
    val fontSize: Float? = null,
    val rotation: Float = 0f,
    val overlayRotation: Float? = null,
    val whiteoutColor: Color? = null,
    val textColor: Color? = null,
    val overlayAlpha: Float = 1.0f,
    val textBoldness: Float = 1.0f,
    val overlaySaturation: Float = 1.0f,
    val textSaturation: Float = 1.0f,
    val lineSpacing: Float = 1.0f,
    val textBorderColor: Color? = null,
    val textBorderThickness: Float = 0.0f,
    val textBorderAlpha: Float = 1.0f,
    val textShadowColor: Color? = null,
    val textShadowAlpha: Float = 1.0f,
    val textShadowRadius: Float = 0f,
    val overlayInset: Float = 0f,
    val overlayInsetHorizontal: Float = 0f,
    val overlayInsetVertical: Float = 0f,
    val textAlign: com.example.ocrmanga.data.models.TextAlignMode = com.example.ocrmanga.data.models.TextAlignMode.CENTER
)

data class PrecomputedRegion(
    val block: TextBlockInfo,
    val rect: Rect,
    val fontSize: Float,
    val rotation: Float,
    val overlayRotation: Float? = null,
    val whiteoutColor: Color? = null,
    val textColor: Color? = null,
    val overlayAlpha: Float = 1.0f,
    val textBoldness: Float = 1.0f,
    val overlaySaturation: Float = 1.0f,
    val textSaturation: Float = 1.0f,
    val lineSpacing: Float = 1.0f,
    val textBorderColor: Color? = null,
    val textBorderThickness: Float = 0.0f,
    val textBorderAlpha: Float = 1.0f,
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
    getImageIdForUri: (Uri) -> Long? = { null },
    getImageVersionForUri: (Uri) -> Int? = { null },
    getReloadTokenForUri: (Uri) -> Long? = { null },
    isLoadingMoreImages: Boolean = false,
    remainingImagesCount: Int = 0,
    translatingImages: Map<Uri, TranslationStatus> = emptyMap(),
    // URIs recently saved from the editor; used to apply saved blocks immediately
    recentlySavedUris: Set<Uri> = emptySet(),
    // Callback to clear the recently-saved marker for a URI after it's been applied
    onClearRecentlySavedUri: ((Uri) -> Unit)? = null,
    // URIs for which we should re-open the editor after saved blocks are applied
    reopenEditorUris: Set<Uri> = emptySet(),
    // Callback to clear the reopen-editor marker for a URI after it's been handled
    onClearReopenEditorUri: ((Uri) -> Unit)? = null,
    // Callback to request that the caller open the editor for a URI (invoked after blocks applied)
    onRequestOpenEditor: ((Uri) -> Unit)? = null,
    isTextRemovalMode: Boolean = false,
    onToggleTextRemovalMode: () -> Unit = {},
    onRemoveTextWithMask: (Uri, android.graphics.Bitmap) -> Unit = { _, _ -> },
    brushSize: Float = 40f
) {
    val context = LocalContext.current
    val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }
    var translationVersion by remember { mutableStateOf(0) }
    val newlyTranslated = remember { mutableStateMapOf<Uri, Boolean>() }
    val visibleRange = remember { mutableStateOf(IntRange(0, -1)) }
    val prefetchBuffer = 2
    val imageLoader = ImageLoader(context)
    val suppressDiskCachePref = remember {
        try {
            val prefs = context.getSharedPreferences("ocrmanga_prefs", android.content.Context.MODE_PRIVATE)
            prefs.getBoolean("suppress_coil_disk_cache", false)
        } catch (e: Exception) { false }
    }

    LaunchedEffect(lazyListState, imageUris) {
        snapshotFlow { lazyListState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { visibleIndices ->
                if (visibleIndices.isNotEmpty()) {
                    val min = visibleIndices.minOrNull() ?: 0
                    val max = visibleIndices.maxOrNull() ?: 0
                    val start = (min - prefetchBuffer).coerceAtLeast(0)
                    val end = (max + prefetchBuffer).coerceAtMost(imageUris.size - 1)
                    visibleRange.value = IntRange(start, end)
                    for (i in start..end) {
                        try {
                            val reloadToken = try { getReloadTokenForUri(imageUris[i]) ?: 0L } catch (e: Exception) { 0L }
                            val prefetchReq = ImageRequest.Builder(context)
                                .data(imageUris[i])
                                .memoryCacheKey("image-index-$i:${imageUris[i].toString()}:rt$reloadToken")
                                .diskCacheKey("image-index-$i:${imageUris[i].toString()}:rt$reloadToken")
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(if (suppressDiskCachePref) coil.request.CachePolicy.DISABLED else coil.request.CachePolicy.ENABLED)
                                .build()
                            imageLoader.enqueue(prefetchReq)
                        } catch (e: Exception) {}
                    }
                } else {
                    visibleRange.value = IntRange(0, -1)
                }
            }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize().then(
            if (editTranslationMode) Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.pressed }) event.changes.forEach { it.consume() }
                    }
                }
            } else Modifier
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        itemsIndexed(items = imageUris, key = { index, uri ->
            val id = getImageIdForUri(uri)
            if (id != null) id.toString() else "$index:${uri.toString()}"
        }) { index, uri ->
            val isInWindow = index in visibleRange.value
            
            // Initialization Logic
            val currentTranslatedBlocks = if (isInWindow) translatedTexts[uri]?.second
                ?.filter { !it.pendingDelete }
                ?.map { block ->
                    val overlayInt = (block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()) or 0xFF000000.toInt()
                    val textInt = (block.customTextColor ?: block.originalTextColor ?: computeDefaultTextColor(overlayInt, block.averageBackgroundColor)) or 0xFF000000.toInt()
                    DragBlockState(
                        block = block.copy(customOverlayColor = overlayInt, customTextColor = textInt, fontSize = block.fontSize),
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
                        textShadowColor = block.customShadowColor?.let { Color(it or 0xFF000000.toInt()) },
                        textShadowAlpha = block.shadowAlpha ?: 1.0f,
                        textShadowRadius = block.shadowRadius ?: 0f,
                        overlayInset = block.overlayInset,
                        overlayInsetHorizontal = block.overlayInsetHorizontal,
                        overlayInsetVertical = block.overlayInsetVertical,
                        textAlign = block.textAlign
                    )
                } else null

            var dragBlocks by remember(uri, translationVersion, translatedTexts[uri], isInWindow) {
                mutableStateOf(if (isInWindow) (currentTranslatedBlocks ?: emptyList()) else (dragBlocksMap[uri] ?: emptyList()))
            }

            LaunchedEffect(uri, isInWindow, translationVersion, translatedTexts[uri], editTranslationMode) {
                if (isInWindow && !editTranslationMode) {
                    val rawNewBlocks = translatedTexts[uri]?.second?.map { it ->
                        DragBlockState(
                            block = it,
                            fontSize = null,
                            rotation = it.rotation ?: 0f,
                            overlayRotation = it.overlayRotation,
                            whiteoutColor = it.customOverlayColor?.let { c -> Color(c) },
                            textColor = (it.customTextColor ?: it.originalTextColor)?.let { c -> Color(c) },
                            overlayAlpha = it.overlayAlpha,
                            textBoldness = it.textBoldness,
                            overlaySaturation = it.overlaySaturation,
                            textAlign = it.textAlign,
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

                    val existing = dragBlocksMap[uri]
                    val merged = if (existing != null && existing.isNotEmpty()) {
                        rawNewBlocks.map { nb ->
                            val match = existing.find { eb -> eb.block.bounds == nb.block.bounds && eb.block.text == nb.block.text }
                            if (match != null) {
                                nb.copy(
                                    block = nb.block.copy(applyMerge = match.block.applyMerge),
                                    textShadowColor = nb.textShadowColor ?: match.textShadowColor,
                                    textShadowAlpha = if (nb.textShadowAlpha != 1.0f) nb.textShadowAlpha else match.textShadowAlpha,
                                    textShadowRadius = if (nb.textShadowRadius != 0f) nb.textShadowRadius else match.textShadowRadius,
                                    lineSpacing = if (nb.lineSpacing != 1.0f) nb.lineSpacing else match.lineSpacing,
                                    overlayInset = if (nb.overlayInset != 0f) nb.overlayInset else match.overlayInset,
                                    overlayInsetHorizontal = if (nb.overlayInsetHorizontal != 0f) nb.overlayInsetHorizontal else match.overlayInsetHorizontal,
                                    overlayInsetVertical = if (nb.overlayInsetVertical != 0f) nb.overlayInsetVertical else match.overlayInsetVertical,
                                    fontSize = nb.fontSize ?: match.fontSize,
                                    rotation = if (nb.rotation != 0f) nb.rotation else match.rotation,
                                    overlayRotation = nb.overlayRotation ?: match.overlayRotation,
                                    offset = if (match.offset != Offset.Zero) match.offset else nb.offset
                                )
                            } else nb
                        }
                    } else rawNewBlocks
                    dragBlocks = merged
                    dragBlocksMap[uri] = merged
                    newlyTranslated[uri] = true
                }
            }

            LaunchedEffect(dragBlocks, isInWindow) { if (isInWindow) dragBlocksMap[uri] = dragBlocks }
            
            var selectedIndex by remember(uri, editTranslationMode) { mutableStateOf<Int?>(null) }
            var draggingIndex by remember { mutableStateOf<Int?>(null) }
            var lastDragPos by remember { mutableStateOf(Offset.Zero) }

            val textRemovalPaths = remember { mutableStateListOf<Pair<Path, Float>>() }
            val currentPaintingPath = remember { mutableStateOf<Path?>(null) }
            var drawTrigger by remember { mutableStateOf(0) }
            var magnifierPosition by remember { mutableStateOf<Offset?>(null) }
            var magnifierSourcePosition by remember { mutableStateOf<Offset?>(null) }
            
            var zoomScale by remember(uri, isTextRemovalMode) { mutableStateOf(1f) }
            var zoomOffset by remember(uri, isTextRemovalMode) { mutableStateOf(Offset.Zero) }
            
            LaunchedEffect(isTextRemovalMode) {
                if (!isTextRemovalMode) {
                    textRemovalPaths.clear()
                    currentPaintingPath.value = null
                    magnifierPosition = null
                    magnifierSourcePosition = null
                    zoomScale = 1f
                    zoomOffset = Offset.Zero
                }
            }

            Column(modifier = Modifier.fillMaxWidth().pointerInput(uri, editTranslationMode, isTextRemovalMode) {
                if (!isTextRemovalMode && !editTranslationMode) {
                    detectTapGestures(onLongPress = { onImageMenuUriChange(uri); onShowImageMenuChange(true) })
                }
            }) {
                if (editTranslationMode) {
                    TranslationEditor(
                        dragBlocks = dragBlocks, selectedIndex = selectedIndex,
                        onDragBlocksChange = { dragBlocks = it; dragBlocksMap[uri] = it },
                        onSelectedIndexChange = { selectedIndex = it },
                        onSave = { onSaveTranslation(uri, dragBlocksMap[uri] ?: dragBlocks); onEditTranslationModeToggle(false) },
                        isTextRemovalMode = isTextRemovalMode, onToggleTextRemovalMode = onToggleTextRemovalMode
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                    var imageWidth by remember { mutableStateOf(0f) }
                    var imageHeight by remember { mutableStateOf(0f) }
                    var originalImageWidth by remember { mutableStateOf(0f) }
                    var originalImageHeight by remember { mutableStateOf(0f) }
                    var isImageLoaded by remember { mutableStateOf(false) }
                    var imageLoadState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
                    val translationStatus = translatingImages[uri] ?: TranslationStatus.IDLE

                    if (isInWindow) {
                        LaunchedEffect(uri, permissionGranted) {
                            if (!permissionGranted) {
                                try { permissionLauncher.launch(readPermission) } catch (e: Exception) {}
                                originalImageWidth = 1280f; originalImageHeight = 1808f; isImageLoaded = true
                                return@LaunchedEffect
                            }
                            try {
                                val (w, h) = getImageDimensions(context, uri)
                                originalImageWidth = w.toFloat(); originalImageHeight = h.toFloat(); isImageLoaded = true
                            } catch (e: Exception) {
                                originalImageWidth = 1280f; originalImageHeight = 1808f; isImageLoaded = true
                            }
                        }
                    }

                    val imageVersion = try { getImageVersionForUri(uri) ?: 0 } catch (e: Exception) { 0 }
                    val reloadToken = try { getReloadTokenForUri(uri) ?: 0L } catch (e: Exception) { 0L }
                    val imageRequest = remember(index, uri, imageVersion) {
                        ImageRequest.Builder(context).data(uri).memoryCacheKey("image-index-$index:${uri.toString()}:v$imageVersion:rt$reloadToken")
                            .diskCacheKey("image-index-$index:${uri.toString()}:v$imageVersion:rt$reloadToken")
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(if (suppressDiskCachePref) coil.request.CachePolicy.DISABLED else coil.request.CachePolicy.ENABLED)
                            .build()
                    }

                    val precomputedRegionsState = remember(uri, translationVersion) { mutableStateOf<List<PrecomputedRegion>>(emptyList()) }
                    val _conf = LocalConfiguration.current; val _sw = _conf.screenWidthDp.toFloat()

                    LaunchedEffect(uri, translationVersion, dragBlocks, imageWidth, imageHeight, isInWindow, _sw) {
                        // Allow immediate apply when either not in edit mode OR this uri was recently saved via editor
                        val isRecentSave = recentlySavedUris.contains(uri)
                        if (!isInWindow) { precomputedRegionsState.value = emptyList(); return@LaunchedEffect }
                        // Allow recalculation during editing to show real-time changes
                        val screenScaleFactor = (_sw / 360f).coerceIn(0.5f, 2.0f)
                        withContext(kotlinx.coroutines.Dispatchers.Default) {
                            val list = dragBlocks.filter { !it.block.pendingDelete }.mapNotNull { dragBlock ->
                                val block = dragBlock.block; if (block.text.isBlank()) return@mapNotNull null
                                val blockImageWidth = block.originalImageWidth?.toFloat() ?: originalImageWidth
                                val scale = if (blockImageWidth > 0f) imageWidth / blockImageWidth else 1f
                                val offsetY = if (imageHeight > (block.originalImageHeight?.toFloat() ?: originalImageHeight) * scale) (imageHeight - (block.originalImageHeight?.toFloat() ?: originalImageHeight) * scale) / 2 else 0f
                                val rect = Rect((block.bounds.left * scale) + dragBlock.offset.x, (block.bounds.top * scale) + offsetY + dragBlock.offset.y, (block.bounds.right * scale) + dragBlock.offset.x, (block.bounds.bottom * scale) + offsetY + dragBlock.offset.y)
                                val fontSize = (if (editTranslationMode) computeEditModeFontSize(block, dragBlock.fontSize) else block.fontSize) * screenScaleFactor
                                PrecomputedRegion(block, rect, fontSize, dragBlock.rotation, dragBlock.overlayRotation, dragBlock.whiteoutColor, dragBlock.textColor, dragBlock.overlayAlpha, dragBlock.textBoldness, dragBlock.overlaySaturation, dragBlock.textSaturation, dragBlock.lineSpacing, dragBlock.textBorderColor, dragBlock.textBorderThickness, dragBlock.textBorderAlpha, dragBlock.textShadowColor, dragBlock.textShadowAlpha, dragBlock.textShadowRadius, dragBlock.overlayInset * scale, dragBlock.overlayInsetHorizontal * scale, dragBlock.overlayInsetVertical * scale)
                            }
                            precomputedRegionsState.value = list
                        }
                        // If this was a recent save, clear the flag so we don't reapply repeatedly
                        if (isRecentSave) {
                            try { onClearRecentlySavedUri?.invoke(uri) } catch (e: Exception) {}
                        }
                        // If caller requested reopen editor for this uri, invoke callback and clear flag
                        val shouldReopen = reopenEditorUris.contains(uri)
                        if (shouldReopen) {
                            try { onRequestOpenEditor?.invoke(uri) } catch (e: Exception) {}
                            try { onClearReopenEditorUri?.invoke(uri) } catch (e: Exception) {}
                        }
                    }

                    // Content Layer (Zoomable)
                    Box(modifier = Modifier.fillMaxWidth().graphicsLayer {
                        scaleX = zoomScale; scaleY = zoomScale
                        translationX = zoomOffset.x; translationY = zoomOffset.y
                    }) {
                        AsyncImage(model = imageRequest, contentDescription = null, modifier = Modifier.fillMaxWidth().onGloballyPositioned { imageWidth = it.size.width.toFloat(); imageHeight = it.size.height.toFloat() }, contentScale = ContentScale.FillWidth, onState = { imageLoadState = it })
                        if (isInWindow && isImageLoaded && imageLoadState is AsyncImagePainter.State.Success && (isTextRemovalMode || (translationEnabled && translatedTexts.containsKey(uri)))) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                precomputedRegionsState.value.forEach { region ->
                                    val block = region.block; val rect = region.rect; val isOval = block.shapeType == 1
                                    val insetRect = Rect(rect.left + region.overlayInsetHorizontal, rect.top + region.overlayInsetVertical, rect.right - region.overlayInsetHorizontal, rect.bottom - region.overlayInsetVertical).takeIf { it.width > 0 && it.height > 0 } ?: rect
                                    val overlayRotationAngle = region.overlayRotation ?: 0f
                                    fun drawOverlayContent() {
                                        if (region.whiteoutColor != null) {
                                            val finalColor = region.whiteoutColor.copy(alpha = region.overlayAlpha)
                                            if (isOval) drawOval(finalColor, Offset(insetRect.left, insetRect.top), Size(insetRect.width, insetRect.height))
                                            else drawRect(finalColor, Offset(insetRect.left, insetRect.top), Size(insetRect.width, insetRect.height))
                                        } else drawTranslucentOverlay(insetRect, block.backgroundType, block.averageBackgroundColor, block.originalTextColor, block.shapeType)
                                    }
                                    if (overlayRotationAngle != 0f) withTransform({ rotate(overlayRotationAngle, rect.center) }) { drawOverlayContent() } else drawOverlayContent()
                                    if (editTranslationMode) {
                                        val isSelected = selectedIndex != null && selectedIndex!! < dragBlocks.size && dragBlocks[selectedIndex!!].block == block
                                        fun drawBorder() {
                                            val color = if (isSelected) Color.Red else Color.Blue
                                            if (isOval) drawOval(color, Offset(rect.left, rect.top), Size(rect.width, rect.height), style = Stroke(2f))
                                            else drawRect(color, Offset(rect.left, rect.top), Size(rect.width, rect.height), style = Stroke(2f))
                                        }
                                        if (overlayRotationAngle != 0f) withTransform({ rotate(overlayRotationAngle, rect.center) }) { drawBorder() } else drawBorder()
                                    }
                                    val tL = rect.left + rect.width * (if (isOval) 0.15f else 0f); val tT = rect.top + rect.height * (if (isOval) 0.15f else 0f)
                                    val tW = rect.width * (if (isOval) 0.7f else 1f); val tH = rect.height * (if (isOval) 0.7f else 1f)
                                    withTransform({ if (region.rotation != 0f) rotate(region.rotation, Offset(tL + tW / 2, tT + tH / 2)) }) {
                                        drawTextOnCanvas(
                                            drawScope = this,
                                            text = block.text,
                                            x = tL,
                                            y = tT,
                                            width = tW,
                                            height = tH,
                                            color = region.textColor ?: Color.Black,
                                            fontSize = region.fontSize,
                                            isVertical = block.isVertical,
                                            boldness = region.textBoldness,
                                            context = context,
                                            fontFamilyName = block.fontFamily,
                                            borderColor = region.textBorderColor,
                                            borderThickness = region.textBorderThickness,
                                            borderAlpha = region.textBorderAlpha,
                                            editMode = editTranslationMode,
                                            shapeType = block.shapeType,
                                            lineSpacing = region.lineSpacing,
                                            shadowColor = region.textShadowColor,
                                            shadowAlpha = region.textShadowAlpha,
                                            shadowRadius = region.textShadowRadius,
                                            textAlign = block.textAlign
                                        )
                                    }
                                }
                                if (isTextRemovalMode) {
                                    val dummy = drawTrigger
                                    val strokeStyle = Stroke(width = brushSize, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                                    textRemovalPaths.forEach { drawPath(it.first, Color.Red.copy(alpha = 0.5f), style = Stroke(width = it.second, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)) }
                                    currentPaintingPath.value?.let { drawPath(it, Color.Red.copy(alpha = 0.5f), style = strokeStyle) }
                                }
                            }
                        }
                    }

                    // Touch Layer
                    Box(modifier = Modifier.matchParentSize().pointerInput(isTextRemovalMode, editTranslationMode, zoomScale, zoomOffset, imageWidth, imageHeight) {
                        if (isTextRemovalMode) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size > 1) {
                                        currentPaintingPath.value = null; magnifierPosition = null; magnifierSourcePosition = null
                                        val zoom = event.calculateZoom(); val pan = event.calculatePan()
                                        zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                                        val maxOX = (imageWidth * (zoomScale - 1f)) / 2; val maxOY = (imageHeight * (zoomScale - 1f)) / 2
                                        zoomOffset = Offset((zoomOffset.x + pan.x).coerceIn(-maxOX, maxOX), (zoomOffset.y + pan.y).coerceIn(-maxOY, maxOY))
                                        event.changes.forEach { it.consume() }
                                    } else {
                                        val change = event.changes.first(); val screenPos = change.position
                                        val centerX = imageWidth / 2; val centerY = imageHeight / 2
                                        val internalPos = Offset((screenPos.x - centerX - zoomOffset.x) / zoomScale + centerX, (screenPos.y - centerY - zoomOffset.y) / zoomScale + centerY)
                                        if (change.pressed) {
                                            if (change.changedToDown()) {
                                                currentPaintingPath.value = Path().apply { moveTo(internalPos.x, internalPos.y) }
                                                magnifierPosition = screenPos; magnifierSourcePosition = internalPos
                                            } else if (currentPaintingPath.value != null) {
                                                currentPaintingPath.value?.lineTo(internalPos.x, internalPos.y)
                                                magnifierPosition = screenPos; magnifierSourcePosition = internalPos
                                            }
                                            drawTrigger++; change.consume()
                                        } else if (change.changedToUp()) {
                                            currentPaintingPath.value?.let { textRemovalPaths.add(it to brushSize) }
                                            currentPaintingPath.value = null; magnifierPosition = null; magnifierSourcePosition = null
                                            drawTrigger++; change.consume()
                                        }
                                    }
                                }
                            }
                        } else if (editTranslationMode) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(); val dragEvent = event.changes.firstOrNull() ?: continue
                                    if (dragEvent.pressed) {
                                        if (draggingIndex == null) {
                                            val pos = dragEvent.position
                                            val idx = dragBlocks.indexOfLast { db ->
                                                val b = db.block; val bw = b.originalImageWidth?.toFloat() ?: originalImageWidth; val s = if (bw > 0f) imageWidth / bw else 1f
                                                val h = (b.originalImageHeight?.toFloat() ?: originalImageHeight) * s; val oY = if (imageHeight > h) (imageHeight - h) / 2 else 0f
                                                Rect((b.bounds.left * s) + db.offset.x, (b.bounds.top * s) + oY + db.offset.y, (b.bounds.right * s) + db.offset.x, (b.bounds.bottom * s) + oY + db.offset.y).contains(pos)
                                            }
                                            if (idx != -1) { selectedIndex = idx; draggingIndex = idx; lastDragPos = pos } else selectedIndex = null
                                            dragEvent.consume()
                                        } else {
                                            val amt = dragEvent.position - lastDragPos
                                            dragBlocks = dragBlocks.toMutableList().also { it[draggingIndex!!] = it[draggingIndex!!].copy(offset = it[draggingIndex!!].offset + amt) }
                                            lastDragPos = dragEvent.position; dragEvent.consume()
                                        }
                                    } else {
                                        draggingIndex?.let { idx ->
                                            val db = dragBlocks[idx]; val bw = db.block.originalImageWidth?.toFloat() ?: originalImageWidth; val s = if (bw > 0f) imageWidth / bw else 1f
                                            val nb = android.graphics.Rect(db.block.bounds).apply { offset((db.offset.x / s).toInt(), (db.offset.y / s).toInt()) }
                                            val updated = dragBlocks.toMutableList().also { it[idx] = db.copy(block = db.block.copy(bounds = nb), offset = Offset.Zero) }
                                            dragBlocks = updated; dragBlocksMap[uri] = updated; draggingIndex = null
                                        }
                                    }
                                }
                            }
                        }
                    }) { }

                    TranslationOverlay(status = translationStatus, modifier = Modifier.matchParentSize())
                    if (isTextRemovalMode && textRemovalPaths.isNotEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                            Button(onClick = {
                                if (originalImageWidth > 0 && imageWidth > 0) {
                                    val maskBmp = android.graphics.Bitmap.createBitmap(originalImageWidth.toInt(), originalImageHeight.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(maskBmp).apply { drawColor(android.graphics.Color.BLACK) }
                                    val s = originalImageWidth / imageWidth; val matrix = android.graphics.Matrix().apply { setScale(s, s) }
                                    val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
                                    textRemovalPaths.forEach { pair ->
                                        paint.strokeWidth = pair.second * s
                                        canvas.drawPath(pair.first.asAndroidPath().apply { transform(matrix) }, paint)
                                    }
                                    onRemoveTextWithMask(uri, maskBmp); textRemovalPaths.clear()
                                }
                            }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Xóa vùng này", color = Color.White) }
                        }
                    }
                    magnifierPosition?.let { pos ->
                        MagnifierPopup(magnifierPosition = pos, sourcePosition = magnifierSourcePosition ?: pos, imageWidth = imageWidth, imageHeight = imageHeight, imageUri = uri, modifier = Modifier.matchParentSize().zIndex(999f))
                    }
                }
            }
        }
        if (isLoadingMoreImages && remainingImagesCount > 0) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Đang tải thêm $remainingImagesCount ảnh...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

private fun Color.applySaturation(saturation: Float): Color {
    val hsv = FloatArray(3); ColorUtils.colorToHSL(this.toArgb(), hsv)
    hsv[1] = (hsv[1] * saturation).coerceIn(0f, 1f); return Color(ColorUtils.HSLToColor(hsv))
}