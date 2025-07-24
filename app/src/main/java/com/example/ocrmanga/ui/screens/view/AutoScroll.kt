package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
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
import com.example.ocrmanga.viewmodels.ViewerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AutoScroll(
    lazyListState: LazyListState,
    autoScrollEnabled: Boolean,
    scrollSpeed: Float,
    onAutoScrollToggle: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    imageUris: List<Uri>,
    onLoadMoreImages: () -> Unit,
    onShowSpeedSliderChange: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    
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

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }.collect { index ->
            if (index >= imageUris.size - ViewerViewModel.BATCH_SIZE / 2 && imageUris.isNotEmpty()) {
                onLoadMoreImages()
            }
        }
    }

    // Chỉ trả về icon, không bao gồm slider
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

@Composable 
fun AutoScrollSpeedSlider(
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