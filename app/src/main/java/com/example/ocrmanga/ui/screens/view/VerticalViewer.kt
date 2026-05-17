package com.example.ocrmanga.ui.screens.view

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.models.TranslationStatus
import com.example.ocrmanga.viewmodels.ViewerViewModel
import kotlinx.coroutines.delay

/**
 * Chế độ xem dọc tối ưu hóa — kiến trúc giống HorizontalViewer.
 *
 * Mỗi ảnh được bọc trong [ImagePage] riêng biệt bên trong [LazyColumn].
 * Điều này đảm bảo:
 *  - State, Canvas drawing, và text precomputation được scope riêng theo từng item.
 *  - Các item ngoài viewport sẽ bị dispose và giải phóng bộ nhớ ngay lập tức.
 *  - Không có LaunchedEffect hay derivedStateOf "nặng" chạy cho toàn bộ danh sách.
 */
@Composable
fun VerticalViewer(
    imageUris: List<Uri>,
    viewModel: ViewerViewModel = viewModel(),
    lazyListState: LazyListState = rememberLazyListState(),
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
    initialPageIndex: Int? = null,
    translationVersion: Int = 0,
    translatedTexts: Map<Uri, Pair<String, List<com.example.ocrmanga.data.models.TextBlockInfo>>> = emptyMap(),
    translationEnabled: Boolean = false,
    onTagReported: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    isLoadingMoreImages: Boolean = false,
    remainingImagesCount: Int = 0,
    removingTextImages: Map<Uri, String> = emptyMap()
) {
    val conf = LocalConfiguration.current
    // Mỗi page có thể chứa ảnh dài hơn màn hình — dùng LazyListState riêng để cuộn nội dung
    val pageListStates = remember { mutableStateMapOf<Uri, LazyListState>() }

    // Cuộn tới trang ban đầu khi được yêu cầu
    LaunchedEffect(initialPageIndex, imageUris) {
        val target = initialPageIndex
        if (target != null && imageUris.isNotEmpty()) {
            val safeIndex = target.coerceIn(0, imageUris.lastIndex)
            delay(100)
            try {
                lazyListState.scrollToItem(safeIndex)
            } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = imageUris,
                key = { index, uri ->
                    val id = getImageIdForUri(uri)
                    if (id != null) id.toString() else "$index:${uri}"
                },
                contentType = { _, _ -> "image" }
            ) { _, uri ->
                val pageState = pageListStates.getOrPut(uri) { LazyListState() }
                // Tính chiều cao tối đa cho ảnh
                val imageMaxHeight = run {
                    val screenH = conf.screenHeightDp.dp
                    if (isTextRemovalMode) {
                        (screenH - 56.dp - 72.dp - 32.dp).coerceAtLeast(100.dp)
                    } else {
                        // Cho phép ảnh dài hơn màn hình để cuộn dọc trong ngang
                        screenH
                    }
                }
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
                    imageMaxHeight = imageMaxHeight,
                    lazyListState = pageState,
                    translationVersion = translationVersion,
                    translatedTexts = translatedTexts,
                    translationEnabled = translationEnabled,
                    onTagReported = onTagReported,
                    isScrollable = false,
                    removingTextImages = removingTextImages
                )
            }

            // Item loading ở cuối danh sách
            if (isLoadingMoreImages && remainingImagesCount > 0) {
                item(key = "loading_indicator") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary
                            )
                            androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.height(12.dp)
                            )
                            Text(
                                text = "Đang tải thêm $remainingImagesCount ảnh...",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
