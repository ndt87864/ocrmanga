package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AutoScrollFixed(
    lazyListState: LazyListState,
    autoScrollEnabled: Boolean,
    scrollSpeed: Float,
    onAutoScrollToggle: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    imageUris: List<Uri>,
    onLoadMoreImages: () -> Unit,
    onShowSpeedSliderChange: (() -> Unit)? = null,
    isLoadingMoreImages: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    
    // Auto scroll logic
    LaunchedEffect(autoScrollEnabled, scrollSpeed) {
        while (autoScrollEnabled) {
            val currentIndex = lazyListState.firstVisibleItemIndex
            val currentOffset = lazyListState.firstVisibleItemScrollOffset
            val totalItems = imageUris.size

            if (currentIndex >= totalItems - 1 && currentOffset >= 0) {
                onAutoScrollToggle(false)
                break
            }

            val speed = (scrollSpeed * 2).toInt()
            lazyListState.scrollToItem(
                currentIndex,
                currentOffset + if (speed > 0) speed else 1
            )
            delay(16)
        }
    }

    // Track when to load more images - improved logic
    LaunchedEffect(lazyListState, imageUris.size) {
        snapshotFlow { 
            val layoutInfo = lazyListState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isNotEmpty()) {
                visibleItems.last().index
            } else {
                0
            }
        }.collect { lastVisibleIndex: Int ->
            // Load more when user is viewing the last 3 images
            val shouldLoadMore = lastVisibleIndex >= imageUris.size - 3 && imageUris.isNotEmpty()
            if (shouldLoadMore) {
                onLoadMoreImages()
            }
        }
    }

    // Auto scroll icon/button
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Loading indicator khi đang tải thêm ảnh
        if (isLoadingMoreImages) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Box(
            modifier = Modifier.pointerInput(autoScrollEnabled) {
                detectTapGestures(
                    onLongPress = { 
                        onShowSpeedSliderChange?.invoke()
                    },
                    onTap = { 
                        if (autoScrollEnabled) {
                            onAutoScrollToggle(false)
                        } else {
                            onAutoScrollToggle(true)
                        }
                    }
                )
            }
        ) {
            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = if (autoScrollEnabled) "Dừng cuộn" else "Tự động cuộn",
                tint = if (autoScrollEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable 
fun AutoScrollSpeedSliderFixed(
    scrollSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    showSpeedSlider: Boolean
) {
    if (showSpeedSlider) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tốc độ cuộn:", modifier = Modifier.padding(end = 8.dp))
            Slider(
                value = scrollSpeed,
                onValueChange = onSpeedChange,
                valueRange = 1f..10f,
                steps = 8,
                modifier = Modifier.weight(1f)
            )
            Text(text = scrollSpeed.toInt().toString(), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
