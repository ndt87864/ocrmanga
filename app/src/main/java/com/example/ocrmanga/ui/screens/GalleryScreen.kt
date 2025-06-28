package com.example.ocrmanga.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ocrmanga.viewmodels.GalleryViewModel
import android.provider.MediaStore
import androidx.compose.ui.layout.ContentScale
import android.widget.Toast

// Hàm tiện ích để lấy tên tệp từ URI
fun getFileNameFromUri(context: Context, uri: Uri): String? {
    val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            return cursor.getString(columnIndex)
        }
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToViewer: (List<String>) -> Unit,
    onNavigateToRoom: (Long) -> Unit,
    viewModel: GalleryViewModel = viewModel()
) {
    // Khi quay lại gallery, luôn xóa dữ liệu session (selectedImages)
    LaunchedEffect(Unit) {
        viewModel.clearSelectedImages()
        viewModel.loadSavedRooms()
    }

    val uiState by viewModel.uiState.collectAsState()
    val TAG = "GalleryScreen"
    var showDeleteDialog by remember { mutableStateOf<Long?>(null) }
    var showCreateMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Hàm tiện ích: lấy tên file không có phần mở rộng
    fun getFileNameWithoutExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // Comparator natural order mạnh hơn, bỏ phần mở rộng file
    fun naturalOrderComparator(a: String, b: String): Int {
        val aName = getFileNameWithoutExtension(a)
        val bName = getFileNameWithoutExtension(b)
        val regex = "\\d+|\\D+".toRegex()
        val aParts = regex.findAll(aName).map { it.value }.toList()
        val bParts = regex.findAll(bName).map { it.value }.toList()
        val len = minOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val x = aParts[i]
            val y = bParts[i]
            val xNum = x.toLongOrNull()
            val yNum = y.toLongOrNull()
            if (xNum != null && yNum != null) {
                if (xNum != yNum) return (xNum - yNum).toInt()
            } else {
                val cmp = x.compareTo(y)
                if (cmp != 0) return cmp
            }
        }
        return aParts.size - bParts.size
    }

    // Launcher để chọn ảnh từ file (OpenMultipleDocuments cho Android 13+)
    val multipleFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                // Sắp xếp theo tên file (natural order, bỏ phần mở rộng)
                val sortedUris = uris.sortedWith { u1, u2 ->
                    val n1 = getFileNameFromUri(context, u1) ?: ""
                    val n2 = getFileNameFromUri(context, u2) ?: ""
                    naturalOrderComparator(n1, n2)
                }
                viewModel.updateSelectedImages(sortedUris)
                Toast.makeText(context, "Đã chọn ${sortedUris.size} ảnh từ thư mục", Toast.LENGTH_SHORT).show()
                onNavigateToViewer(sortedUris.map { it.toString() })
            }
        }
    )
    // Launcher để chọn ảnh từ thư viện (PickMultipleVisualMedia cho Android thấp hơn)
    val multiplePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                // Sắp xếp theo tên file (natural order, bỏ phần mở rộng)
                val sortedUris = uris.sortedWith { u1, u2 ->
                    val n1 = getFileNameFromUri(context, u1) ?: ""
                    val n2 = getFileNameFromUri(context, u2) ?: ""
                    naturalOrderComparator(n1, n2)
                }
                viewModel.updateSelectedImages(sortedUris)
                Toast.makeText(context, "Đã chọn ${sortedUris.size} ảnh từ thư viện", Toast.LENGTH_SHORT).show()
                onNavigateToViewer(sortedUris.map { it.toString() })
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thư viện") },
                actions = {
                    Box {
                        IconButton(onClick = { showCreateMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Tạo mới"
                            )
                        }
                        DropdownMenu(
                            expanded = showCreateMenu,
                            onDismissRequest = { showCreateMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Chọn ảnh từ thư viện") },
                                onClick = {
                                    multiplePhotoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                    showCreateMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Chọn ảnh từ file") },
                                onClick = {
                                    multipleFilePickerLauncher.launch(arrayOf("image/*"))
                                    showCreateMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var searchQuery by remember { mutableStateOf("") }
            var filterType by remember { mutableStateOf(0) } // 0: Mới nhất, 1: Cũ nhất
            val filterOptions = listOf("Mới nhất", "Cũ nhất")

            // Thanh tìm kiếm phòng
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Tìm kiếm phòng theo tên...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true
            )

            // Bộ lọc phòng
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sắp xếp:", modifier = Modifier.padding(end = 8.dp))
                filterOptions.forEachIndexed { idx, label ->
                    FilterChip(
                        selected = filterType == idx,
                        onClick = { filterType = idx },
                        label = { Text(label) },
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
            }

            val filteredRooms = remember(uiState.savedRooms, searchQuery, filterType) {
                val filtered = if (searchQuery.isBlank()) uiState.savedRooms else uiState.savedRooms.filter {
                    it.second.contains(searchQuery, ignoreCase = true)
                }
                when (filterType) {
                    0 -> filtered // Mới nhất
                    1 -> filtered.reversed() // Cũ nhất
                    else -> filtered
                }
            }

            if (filteredRooms.isNotEmpty()) {
                Text(
                    text = "Danh sách đã lưu",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredRooms) { (roomId, title, coverUri) ->
                        Card(modifier = Modifier.aspectRatio(0.7f)) {
                            Box {
                                AsyncImage(
                                    model = coverUri,
                                    contentDescription = "Cover image",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { onNavigateToRoom(roomId) },
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                                    )
                                    IconButton(
                                        onClick = { showDeleteDialog = roomId },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Xóa phòng",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showDeleteDialog?.let { roomId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Xác nhận xóa") },
            text = { Text("Bạn có chắc chắn muốn xóa phòng này không? Thao tác này không thể hoàn tác.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRoom(roomId)
                    showDeleteDialog = null
                }) { Text("Xóa", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Hủy") }
            }
        )
    }
}