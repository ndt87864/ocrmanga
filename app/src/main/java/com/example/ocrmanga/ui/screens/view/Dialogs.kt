package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import android.content.Intent
import com.example.ocrmanga.utils.AppLogger as Log
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.viewmodels.ViewerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Dialogs(
    showInsertAtIndexDialog: Boolean,
    insertAtIndex: String,
    onInsertAtIndexChange: (String) -> Unit,
    onInsertAtIndexConfirm: () -> Unit,
    onInsertAtIndexDismiss: () -> Unit,
    showExitConfirmDialog: Boolean,
    onExitConfirm: () -> Unit,
    onExitDismiss: () -> Unit,
    showEditTitleDialog: Boolean,
    editTitleText: String,
    onEditTitleChange: (String) -> Unit,
    onEditTitleConfirm: () -> Unit,
    onEditTitleDismiss: () -> Unit,
    showImageMenu: Boolean,
    imageMenuUri: Uri?,
    onImageMenuDismiss: () -> Unit,
    onRemoveImage: (Uri) -> Unit,
    onRetranslateImage: (Uri, TranslationMode) -> Unit,
    imageUris: List<Uri>,
    viewModel: ViewerViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Launcher to pick a single image from file picker (OpenDocument)
    val replaceImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri != null && imageMenuUri != null) {
                try {
                    // Persist read permission so we can access this URI later
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w("Dialogs", "Could not persist permission for $uri", e)
                }
                // Call ViewModel to replace uri
                viewModel.replaceImageUri(imageMenuUri, uri)
                Toast.makeText(context, "Đã chọn ảnh thay thế", Toast.LENGTH_SHORT).show()
            } else if (uri == null) {
                Toast.makeText(context, "Không có ảnh được chọn", Toast.LENGTH_SHORT).show()
            }
            onImageMenuDismiss()
        }
    )
    
    // Launcher to pick a single image from gallery (PickVisualMedia - photo picker)
    val replaceImageFromGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            if (uri != null && imageMenuUri != null) {
                try {
                    // Persist read permission so we can access this URI later
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w("Dialogs", "Could not persist permission for gallery URI $uri", e)
                }
                // Call ViewModel to replace uri
                viewModel.replaceImageUri(imageMenuUri, uri)
                Toast.makeText(context, "Đã chọn ảnh thay thế từ thư viện", Toast.LENGTH_SHORT).show()
            } else if (uri == null) {
                Toast.makeText(context, "Không có ảnh được chọn", Toast.LENGTH_SHORT).show()
            }
            onImageMenuDismiss()
        }
    )

    if (showInsertAtIndexDialog) {
        AlertDialog(
            onDismissRequest = onInsertAtIndexDismiss,
            title = { Text("Chèn ảnh vào vị trí") },
            text = {
                Column {
                    Text("Nhập vị trí (1-${imageUris.size + 1}):")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = insertAtIndex,
                        onValueChange = { value ->
                            val filtered = value.filter { it.isDigit() }
                            onInsertAtIndexChange(filtered)
                        },
                        label = { Text("Vị trí") },
                        placeholder = { Text("1") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (insertAtIndex.isNotEmpty()) {
                        val index = insertAtIndex.toIntOrNull()
                        if (index == null || index !in 1..(imageUris.size + 1)) {
                            Text(
                                text = "Vị trí phải từ 1 đến ${imageUris.size + 1}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onInsertAtIndexConfirm,
                    enabled = insertAtIndex.isNotEmpty() &&
                            insertAtIndex.toIntOrNull()?.let { it in 1..(imageUris.size + 1) } == true
                ) {
                    Text("Chọn ảnh")
                }
            },
            dismissButton = {
                TextButton(onClick = onInsertAtIndexDismiss) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = onExitDismiss,
            title = { Text("Xác nhận thoát") },
            text = { Text("Bạn có chắc chắn muốn thoát? Tất cả ảnh và dữ liệu phiên này sẽ bị xóa.") },
            confirmButton = {
                TextButton(onClick = onExitConfirm) { Text("Thoát") }
            },
            dismissButton = {
                TextButton(onClick = onExitDismiss) { Text("Hủy") }
            }
        )
    }

    if (showEditTitleDialog) {
        AlertDialog(
            onDismissRequest = onEditTitleDismiss,
            title = { Text("Đổi tên phòng") },
            text = {
                OutlinedTextField(
                    value = editTitleText,
                    onValueChange = onEditTitleChange,
                    label = { Text("Tên mới") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = onEditTitleConfirm) { Text("Lưu") }
            },
            dismissButton = {
                TextButton(onClick = onEditTitleDismiss) { Text("Hủy") }
            }
        )
    }

    if (showImageMenu && imageMenuUri != null) {
        val uri = imageMenuUri
        // Lấy danh sách block của ảnh này
        val blocks = viewModel.uiState.value.translatedTexts[uri]?.second ?: emptyList()
        // Nếu có block, lấy trạng thái pendingDelete của block đầu tiên (hoặc có thể chọn block cụ thể nếu cần)
        val hasPendingDelete = blocks.any { it.pendingDelete }
        val blockId = blocks.firstOrNull()?.bounds?.hashCode()
        AlertDialog(
            onDismissRequest = onImageMenuDismiss,
            title = { Text("Tùy chọn ảnh") },
            text = {
                Column {
                    Button(
                        onClick = {
                            imageMenuUri?.let {
                                onRemoveImage(it)
                                Toast.makeText(context, "Đã xóa ảnh khỏi trang", Toast.LENGTH_SHORT).show()
                            }
                            onImageMenuDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Xóa ảnh khỏi trang") }
                    Spacer(Modifier.height(16.dp))
                    // Nút xóa text gốc trên ảnh
                    Button(
                        onClick = {
                            imageMenuUri?.let { uri ->
                                viewModel.removeOriginalText(uri)
                                Toast.makeText(context, "Đang xóa text gốc trên ảnh...", Toast.LENGTH_SHORT).show()
                            }
                            onImageMenuDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Xóa text gốc trên ảnh") }
                    Spacer(Modifier.height(16.dp))
                    // ...không còn nút ON/OFF riêng biệt...
                    Text("Dịch lại ảnh với:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    listOf(TranslationMode.OFFLINE, TranslationMode.ONLINE, TranslationMode.OFF, TranslationMode.GEMINI, TranslationMode.MISTRAL, TranslationMode.ZAI).forEach { mode ->
                        if (mode == TranslationMode.OFF) {
                            // Kiểm tra xem tất cả block đã bị ẩn chưa để quyết định hiển thị ON hay OFF
                            val allBlocksHidden = blocks.isNotEmpty() && blocks.all { it.pendingDelete }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uri = imageMenuUri
                                        if (uri != null && blocks.isNotEmpty()) {
                                            if (allBlocksHidden) {
                                                // Hiện lại toàn bộ bản dịch (đang ẩn -> bật lại)
                                                blocks.forEach { block ->
                                                    viewModel.togglePendingDelete(uri, block.bounds.hashCode(), false)
                                                }
                                                Toast.makeText(context, "Đã bật lại bản dịch", Toast.LENGTH_SHORT).show()
                                            } else {
                                                // Ẩn toàn bộ bản dịch (đang hiện -> tắt)
                                                blocks.forEach { block ->
                                                    viewModel.togglePendingDelete(uri, block.bounds.hashCode(), true)
                                                }
                                                Toast.makeText(context, "Đã tắt bản dịch", Toast.LENGTH_SHORT).show()
                                            }
                                        } else if (uri != null && blocks.isEmpty()) {
                                            Toast.makeText(context, "Ảnh này chưa có bản dịch", Toast.LENGTH_SHORT).show()
                                        }
                                        onImageMenuDismiss()
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(20.dp))
                                // Hiển thị "ON" nếu tất cả đang ẩn (để người dùng biết bấm sẽ bật lại)
                                // Hiển thị "OFF" nếu đang hiển thị (để người dùng biết bấm sẽ tắt)
                                Text(if (allBlocksHidden) "ON" else "OFF", modifier = Modifier.padding(start = 8.dp))
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uri = imageMenuUri
                                        // Check API key availability for Gemini and Mistral modes
                                        if (mode == TranslationMode.GEMINI && !viewModel.hasGeminiApiKeys()) {
                                            Toast.makeText(
                                                context,
                                                "Không có API key Gemini. Vui lòng thêm ít nhất một API key Gemini trong cài đặt để dùng tính năng dịch Gemini.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            onImageMenuDismiss()
                                            return@clickable
                                        }
                                        if (mode == TranslationMode.MISTRAL && !viewModel.hasMistralApiKeys()) {
                                            Toast.makeText(
                                                context,
                                                "Không có API key Mistral. Vui lòng thêm ít nhất một API key Mistral trong cài đặt để dùng tính năng dịch Mistral.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            onImageMenuDismiss()
                                            return@clickable
                                        }
                                        if (mode == TranslationMode.ZAI && !viewModel.hasZAiApiKeys()) {
                                            Toast.makeText(
                                                context,
                                                "Không có API key Z.AI. Vui lòng thêm ít nhất một API key Z.AI trong cài đặt để dùng tính năng dịch Z.AI.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            onImageMenuDismiss()
                                            return@clickable
                                        }

                                        if (uri != null) {
                                            Toast.makeText(context, "Đang dịch lại ảnh...", Toast.LENGTH_SHORT).show()
                                            onRetranslateImage(uri, mode)
                                            coroutineScope.launch {
                                                while (true) {
                                                    val status = viewModel.uiState.value.translatedStatus[uri]
                                                    if (status == true) break
                                                    delay(200)
                                                }
                                                // Kiểm tra nếu ảnh này là ảnh thứ 5 đã thay đổi thì không thông báo
                                                val rid = viewModel.uiState.value.roomId
                                                val imageId = viewModel.uriToImageId[uri]
                                                var showToast = true
                                                if (rid != null && imageId != null) {
                                                    val numChanged = viewModel.getNumChangedImages(rid)
                                                    if (numChanged >= 5) showToast = false
                                                }
                                                if (showToast) {
                                                    Toast.makeText(context, "Dịch lại ảnh hoàn tất!", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                        onImageMenuDismiss()
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(mode.getDisplayName(), modifier = Modifier.padding(start = 8.dp).weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Replace image from file option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Launch file picker to replace current image
                                try {
                                    replaceImageLauncher.launch(arrayOf("image/*"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Không thể mở trình chọn file", Toast.LENGTH_SHORT).show()
                                    onImageMenuDismiss()
                                }
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Thay thế ảnh (từ file)", modifier = Modifier.padding(start = 8.dp))
                    }
                    // Replace image from gallery option (photo picker)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Launch photo picker to replace current image
                                try {
                                    replaceImageFromGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Không thể mở thư viện ảnh", Toast.LENGTH_SHORT).show()
                                    onImageMenuDismiss()
                                }
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Thay thế ảnh (từ thư viện)", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onImageMenuDismiss) { Text("Đóng") }
            }
        )
    }
}