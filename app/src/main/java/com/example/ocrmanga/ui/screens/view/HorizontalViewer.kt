package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ocrmanga.viewmodels.ViewerViewModel
import kotlinx.coroutines.delay



import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.models.TranslationStatus

@Composable
fun HorizontalViewer(
    imageUris: List<Uri>,
    viewModel: ViewerViewModel = viewModel(),
    horizontalListState: androidx.compose.foundation.lazy.LazyListState? = null,
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
    getImageIdForUri: (Uri) -> Long?,
    getImageVersionForUri: (Uri) -> Int?,
    getReloadTokenForUri: (Uri) -> Long?,
    translatingImages: Map<Uri, TranslationStatus>,
    recentlySavedUris: Set<Uri>,
    onClearRecentlySavedUri: ((Uri) -> Unit)?,
    reopenEditorUris: Set<Uri>,
    onClearReopenEditorUri: ((Uri) -> Unit)?,
    onRequestOpenEditor: ((Uri) -> Unit)? = null,
    isTextRemovalMode: Boolean,
    onToggleTextRemovalMode: () -> Unit,
    onRemoveTextWithMask: (Uri, Bitmap) -> Unit,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    onOptimizeImageOverlay: (Uri) -> Unit = {},
    autoScrollEnabled: Boolean = false,
    scrollSpeed: Float = 5f,
    onAutoScrollToggle: (Boolean) -> Unit = {},
    initialPageIndex: Int? = null,
    translationVersion: Int = 0
) {
    // Use LazyRow with snap fling to approximate pager behavior (foundation.pager may not be available)
    val state = horizontalListState ?: rememberLazyListState()
    val pageListStates = remember { mutableStateMapOf<Uri, LazyListState>() }
    val conf = LocalConfiguration.current

    // Scroll to initial page once LazyRow is laid out
    LaunchedEffect(initialPageIndex, imageUris) {
        val target = initialPageIndex
        if (target != null && imageUris.isNotEmpty()) {
            val safeIndex = target.coerceIn(0, imageUris.lastIndex)
            // wait for LazyRow to compose
            delay(100)
            try {
                state.scrollToItem(safeIndex)
            } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(lazyListState = state), modifier = Modifier.fillMaxSize()) {
            val screenW = conf.screenWidthDp.dp
            itemsIndexed(imageUris) { index, uri ->
                val pageState = pageListStates.getOrPut(uri) { LazyListState() }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(screenW)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    ImagePage(
                        uri = uri,
                        viewModel = viewModel,
                        editTranslationMode = editTranslationMode,
                        dragBlocksMap = dragBlocksMap,
                        onEditTranslationModeToggle = onEditTranslationModeToggle,
                        onSaveTranslation = onSaveTranslation,
                        onRetranslateImage = onRetranslateImage,
                        showImageMenu = showImageMenu,
                        imageMenuUri = imageMenuUri,
                        onImageMenuDismiss = onImageMenuDismiss,
                        onShowImageMenuChange = onShowImageMenuChange,
                        onImageMenuUriChange = onImageMenuUriChange,
                        onRemoveImage = onRemoveImage,
                        getImageIdForUri = getImageIdForUri,
                        getImageVersionForUri = getImageVersionForUri,
                        getReloadTokenForUri = getReloadTokenForUri,
                        translatingImages = translatingImages,
                        recentlySavedUris = recentlySavedUris,
                        onClearRecentlySavedUri = onClearRecentlySavedUri,
                        reopenEditorUris = reopenEditorUris,
                        onClearReopenEditorUri = onClearReopenEditorUri,
                        onRequestOpenEditor = onRequestOpenEditor,
                        isTextRemovalMode = isTextRemovalMode,
                        onToggleTextRemovalMode = onToggleTextRemovalMode,
                        onRemoveTextWithMask = onRemoveTextWithMask,
                        brushSize = brushSize,
                        onBrushSizeChange = onBrushSizeChange,
                        onOptimizeImageOverlay = onOptimizeImageOverlay,
                        // reserve space so controls can appear below image without being clipped
                        // when isTextRemovalMode=true, ImageViewer will use imageMaxHeight to shrink image
                        // so controls fit under it.
                        imageMaxHeight = if (isTextRemovalMode) {
                            // compute available height: screen height - toolbar (56dp) - controls (72dp) - padding
                            val conf = LocalConfiguration.current
                            val screenH = conf.screenHeightDp.dp
                            (screenH - 56.dp - 72.dp - 32.dp).coerceAtLeast(100.dp)
                        } else androidx.compose.ui.unit.Dp.Unspecified,
                        lazyListState = pageState,
                        translationVersion = translationVersion
                    )
                }
            }
        }

        val lastPageState = remember { mutableStateOf(0) }

        LaunchedEffect(autoScrollEnabled, scrollSpeed, imageUris) {
            if (imageUris.isEmpty()) return@LaunchedEffect
            var currentPage = state.firstVisibleItemIndex.coerceIn(0, imageUris.lastIndex)
            while (autoScrollEnabled && imageUris.isNotEmpty()) {
                val currentUri = imageUris[currentPage]
                val pageState = pageListStates.getOrPut(currentUri) { LazyListState() }
                val visibleItems = pageState.layoutInfo.visibleItemsInfo
                val canScrollVertically = visibleItems.any { item ->
                    item.offset < pageState.layoutInfo.viewportStartOffset || item.offset + item.size > pageState.layoutInfo.viewportEndOffset
                } || pageState.canScrollForward

                if (canScrollVertically) {
                    val step = (scrollSpeed * 2f).coerceAtLeast(1f)
                    while (autoScrollEnabled && pageState.canScrollForward) {
                        pageState.dispatchRawDelta(step)
                        delay(16)
                    }
                    delay(1000)
                } else {
                    val holdMillis = ((11 - scrollSpeed.toInt().coerceIn(1, 10)) * 1000L)
                    delay(holdMillis)
                }

                val nextPage = currentPage + 1
                if (nextPage <= imageUris.lastIndex) {
                    currentPage = nextPage
                    lastPageState.value = nextPage
                    state.scrollToItem(nextPage)
                } else {
                    onAutoScrollToggle(false)
                    break
                }
            }
        }

        // Enforce single-step paging for manual fling only. Auto-scroll may advance many pages over time.
        // Skip enforcement during initial 600ms to allow initialScroll to settle.
        var enforcePaging by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(600)
            enforcePaging = true
        }
        androidx.compose.runtime.LaunchedEffect(state, autoScrollEnabled, enforcePaging) {
            androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex }
                .collect { idx ->
                    if (!enforcePaging || autoScrollEnabled) {
                        lastPageState.value = idx
                        return@collect
                    }

                    val lastPage = lastPageState.value
                    if (kotlin.math.abs(idx - lastPage) > 1) {
                        val target = lastPage + if (idx > lastPage) 1 else -1
                        try {
                            state.scrollToItem(target)
                        } catch (_: Exception) {
                        }
                        lastPageState.value = target
                    } else {
                        lastPageState.value = idx
                    }
                }
        }
    }
}

@Composable
fun SingleImageWrapper(uri: Uri, viewModel: ViewerViewModel, onRequestOpenEditor: ((Uri) -> Unit)?) {

}
