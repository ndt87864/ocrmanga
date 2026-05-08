package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.example.ocrmanga.viewmodels.ViewerViewModel
import com.example.ocrmanga.ui.screens.view.ViewMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement



import android.graphics.Bitmap
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.models.TranslationStatus

@Composable
fun HorizontalViewer(
    imageUris: List<Uri>,
    viewModel: ViewerViewModel = viewModel(),
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
    onBrushSizeChange: (Float) -> Unit
) {
    // Use LazyRow with snap fling to approximate pager behavior (foundation.pager may not be available)
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val conf = LocalConfiguration.current
    Box(modifier = Modifier.fillMaxSize()) {
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(lazyListState = state), modifier = Modifier.fillMaxSize()) {
            val screenW = conf.screenWidthDp.dp
            itemsIndexed(imageUris) { index, uri ->
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
                        // reserve space so controls can appear below image without being clipped
                        // when isTextRemovalMode=true, ImageViewer will use imageMaxHeight to shrink image
                        // so controls fit under it.
                        imageMaxHeight = if (isTextRemovalMode) {
                            // compute available height: screen height - toolbar (56dp) - controls (72dp) - padding
                            val conf = LocalConfiguration.current
                            val screenH = conf.screenHeightDp.dp
                            (screenH - 56.dp - 72.dp - 32.dp).coerceAtLeast(100.dp)
                        } else androidx.compose.ui.unit.Dp.Unspecified
                    )
                }
            }
        }

        // Enforce single-step paging: if LazyRow jumps more than 1 index (fast fling), correct to adjacent page
        val lastPageState = remember { mutableStateOf(0) }
        val lastPage = lastPageState.value
        androidx.compose.runtime.LaunchedEffect(state) {
            androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex }
                .collect { idx ->
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
