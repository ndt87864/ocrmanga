package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
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



@Composable
fun HorizontalViewer(
    imageUris: List<Uri>,
    viewModel: ViewerViewModel = viewModel(),
    onRequestOpenEditor: ((Uri) -> Unit)? = null,
    // reuse other callbacks as needed - minimal for now
) {
    // Use LazyRow with snap fling to approximate pager behavior (foundation.pager may not be available)
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(lazyListState = state), modifier = Modifier.fillMaxSize()) {
            val conf = LocalConfiguration.current
            val screenW = conf.screenWidthDp.dp
            itemsIndexed(imageUris) { index, uri ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(screenW)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    ImagePage(uri = uri, viewModel = viewModel, onRequestOpenEditor = onRequestOpenEditor)
                }
            }
        }

        // Simple indicator + optional controls (can be expanded)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            IconButton(onClick = {
                scope.launch { /* future: toggle UI elements */ }
            }) {
                Icon(Icons.Default.Fullscreen, contentDescription = "")
            }
        }
    }
}

@Composable
fun SingleImageWrapper(uri: Uri, viewModel: ViewerViewModel, onRequestOpenEditor: ((Uri) -> Unit)?) {

}
