package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Row
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import com.example.ocrmanga.ui.screens.view.ViewMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.graphics.Color


import com.example.ocrmanga.viewmodels.ViewerViewModel

@Composable
fun HorizontalViewer(
    imageUris: List<Uri>,
    viewModel: ViewerViewModel = viewModel(),
    onRequestOpenEditor: ((Uri) -> Unit)? = null,
    // reuse other callbacks as needed - minimal for now
) {
    val pagerState = rememberPagerState(initialPage = 0)
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            pageCount = imageUris.size,
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val uri = imageUris[page]
            SingleImageWrapper(uri = uri, viewModel = viewModel, onRequestOpenEditor = onRequestOpenEditor)
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
