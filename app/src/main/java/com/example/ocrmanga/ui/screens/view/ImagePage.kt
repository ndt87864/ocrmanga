package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.runtime.Composable
import com.example.ocrmanga.viewmodels.ViewerViewModel

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState

@Composable
fun ImagePage(
    uri: Uri,
    viewModel: ViewerViewModel,
    editTranslationMode: Boolean,
    dragBlocksMap: MutableMap<Uri, List<DragBlockState>>,
    onEditTranslationModeToggle: (Boolean) -> Unit,
    onSaveTranslation: (Uri, List<DragBlockState>) -> Unit,
    onRetranslateImage: (Uri, com.example.ocrmanga.data.models.TranslationMode) -> Unit,
    showImageMenu: Boolean,
    imageMenuUri: Uri?,
    onImageMenuDismiss: () -> Unit,
    onShowImageMenuChange: (Boolean) -> Unit,
    onImageMenuUriChange: (Uri?) -> Unit,
    onRemoveImage: (Uri) -> Unit,
    getImageIdForUri: (Uri) -> Long?,
    getImageVersionForUri: (Uri) -> Int?,
    getReloadTokenForUri: (Uri) -> Long?,
    translatingImages: Map<Uri, com.example.ocrmanga.data.models.TranslationStatus>,
    recentlySavedUris: Set<Uri>,
    onClearRecentlySavedUri: ((Uri) -> Unit)?,
    reopenEditorUris: Set<Uri>,
    onClearReopenEditorUri: ((Uri) -> Unit)?,
    onRequestOpenEditor: ((Uri) -> Unit)?,
    isTextRemovalMode: Boolean,
    onToggleTextRemovalMode: () -> Unit,
    onRemoveTextWithMask: (Uri, android.graphics.Bitmap) -> Unit,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    imageMaxHeight: Dp? = null,
    lazyListState: LazyListState = rememberLazyListState(),
    translationVersion: Int = 0,
    translatedTexts: Map<Uri, Pair<String, List<com.example.ocrmanga.data.models.TextBlockInfo>>> = emptyMap(),
    translationEnabled: Boolean = false
) {
    val list = listOf(uri)
    val state = lazyListState

    ImageViewer(
        imageUris = list,
        translatedTexts = translatedTexts,
        translationEnabled = translationEnabled,
        translatedStatus = viewModel.uiState.value.translatedStatus,
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
        lazyListState = state,
        getImageIdForUri = getImageIdForUri,
        getImageVersionForUri = getImageVersionForUri,
        getReloadTokenForUri = getReloadTokenForUri,
        isLoadingMoreImages = false,
        remainingImagesCount = 0,
        translatingImages = translatingImages,
        recentlySavedUris = recentlySavedUris,
        onClearRecentlySavedUri = onClearRecentlySavedUri,
        reopenEditorUris = reopenEditorUris,
        onClearReopenEditorUri = onClearReopenEditorUri,
        onRequestOpenEditor = onRequestOpenEditor,
        isTextRemovalMode = isTextRemovalMode,
        imageMaxHeight = imageMaxHeight ?: androidx.compose.ui.unit.Dp.Unspecified,
        onToggleTextRemovalMode = onToggleTextRemovalMode,
        onRemoveTextWithMask = onRemoveTextWithMask,
        brushSize = brushSize,
        onBrushSizeChange = onBrushSizeChange,
        translationVersion = translationVersion,
        verticalArrangement = Arrangement.Center,
        contentScale = ContentScale.FillWidth
    )
}
