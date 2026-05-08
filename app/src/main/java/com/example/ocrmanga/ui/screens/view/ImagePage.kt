package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.runtime.Composable
import com.example.ocrmanga.viewmodels.ViewerViewModel

@Composable
fun ImagePage(
    uri: Uri,
    viewModel: ViewerViewModel,
    onRequestOpenEditor: ((Uri) -> Unit)? = null
) {
    // Minimal wrapper: reuse existing ImageViewer by passing a single-element list.
    androidx.compose.runtime.Composable
    fun SingleImageWrapper(uri: Uri, viewModel: ViewerViewModel, onRequestOpenEditor: ((Uri) -> Unit)?) {
        // This wrapper creates a LazyListState with a single item to reuse ImageViewer's rendering path
        val list = listOf(uri)
        androidx.compose.foundation.lazy.rememberLazyListState().let { state ->
            ImageViewer(
                imageUris = list,
                translatedTexts = viewModel.uiState.value.translatedTexts,
                translationEnabled = viewModel.uiState.value.translationEnabled,
                translatedStatus = viewModel.uiState.value.translatedStatus,
                editTranslationMode = false,
                dragBlocksMap = java.util.Collections.synchronizedMap(mutableMapOf<Uri, List<com.example.ocrmanga.ui.screens.view.DragBlockState>>()) as MutableMap<Uri, List<com.example.ocrmanga.ui.screens.view.DragBlockState>>,
                onEditTranslationModeToggle = { },
                onSaveTranslation = { _, _ -> },
                onRetranslateImage = { _, _ -> },
                showImageMenu = false,
                imageMenuUri = null,
                onImageMenuDismiss = {},
                onShowImageMenuChange = {},
                onImageMenuUriChange = {},
                onRemoveImage = { _ -> },
                lazyListState = state,
                getImageIdForUri = viewModel::getImageIdForUri,
                getImageVersionForUri = viewModel::getImageVersionForUri,
                getReloadTokenForUri = viewModel::getReloadTokenForUri,
                isLoadingMoreImages = false,
                remainingImagesCount = 0,
                translatingImages = viewModel.uiState.value.translatingImages,
                recentlySavedUris = emptySet(),
                onClearRecentlySavedUri = null,
                reopenEditorUris = emptySet(),
                onClearReopenEditorUri = null,
                onRequestOpenEditor = onRequestOpenEditor,
                isTextRemovalMode = false,
                onToggleTextRemovalMode = {},
                onRemoveTextWithMask = { _, _ -> },
                brushSize = 40f,
                onBrushSizeChange = {}
            )
        }
    }

}
