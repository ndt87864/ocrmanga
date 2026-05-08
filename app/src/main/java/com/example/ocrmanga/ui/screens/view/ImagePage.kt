package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.runtime.Composable
import com.example.ocrmanga.viewmodels.ViewerViewModel

@Composable
import androidx.compose.ui.unit.Dp

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
    imageMaxHeight: Dp? = null
) {
    val list = listOf(uri)
    val state = androidx.compose.foundation.lazy.rememberLazyListState()

    ImageViewer(
        imageUris = list,
        translatedTexts = viewModel.uiState.value.translatedTexts,
        translationEnabled = viewModel.uiState.value.translationEnabled,
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
        imageMaxHeight = androidx.compose.ui.unit.Dp.Unspecified,
        onToggleTextRemovalMode = onToggleTextRemovalMode,
        onRemoveTextWithMask = onRemoveTextWithMask,
        brushSize = brushSize,
        onBrushSizeChange = onBrushSizeChange
    )
}
