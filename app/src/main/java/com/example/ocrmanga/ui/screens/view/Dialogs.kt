package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import android.widget.Toast
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

    var currentUri by remember { mutableStateOf<Uri?>(null) }

    // State cho dialog chọn OCR lại / giữ OCR cũ khi retranslate
    var showReTranslateDialog by remember { mutableStateOf(false) }
    var pendingReTranslateMode by remember { mutableStateOf<TranslationMode?>(null) }

    // Launcher to pick a single image from file picker (OpenDocument)
    val replaceImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri != null && imageMenuUri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w("Dialogs", "Could not persist permission for $uri", e)
                }
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
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w("Dialogs", "Could not persist permission for gallery URI $uri", e)
                }
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                ) { Text("Chọn ảnh") }
            },
            dismissButton = {
                TextButton(onClick = onInsertAtIndexDismiss) { Text("Hủy") }
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
        val blocks = viewModel.uiState.value.translatedTexts[uri]?.second ?: emptyList()

        AlertDialog(
            onDismissRequest = onImageMenuDismiss,
            title = { Text("Tùy chọn ảnh") },
            text = {
                Column {
                    Button(
                        onClick = {
                            imageMenuUri?.let { onRemoveImage(it) }
                            Toast.makeText(context, "Đã xóa ảnh khỏi trang", Toast.LENGTH_SHORT).show()
                            onImageMenuDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Xóa ảnh khỏi trang") }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            imageMenuUri?.let { viewModel.removeOriginalText(it) }
                            onImageMenuDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Xóa text gốc trên ảnh") }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            imageMenuUri?.let { viewModel.optimizeImageOverlay(it) }
                            onImageMenuDismiss()
                            Toast.makeText(context, "Đã tối ưu hiển thị overlay", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tối ưu hiển thị overlay") }

                    Spacer(Modifier.height(16.dp))

                    Text("Dịch lại ảnh với:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))

                    listOf(TranslationMode.OFFLINE, TranslationMode.ONLINE, TranslationMode.OFF, TranslationMode.GEMINI, TranslationMode.MISTRAL, TranslationMode.ZAI).forEach { mode ->
                        if (mode == TranslationMode.OFF) {
                            val allBlocksHidden = blocks.isNotEmpty() && blocks.all { it.pendingDelete }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val targetUri = imageMenuUri
                                        if (targetUri != null && blocks.isNotEmpty()) {
                                            if (allBlocksHidden) {
                                                blocks.forEach { block ->
                                                    viewModel.togglePendingDelete(targetUri, block.bounds.hashCode(), false)
                                                }
                                                Toast.makeText(context, "Đã bật lại bản dịch", Toast.LENGTH_SHORT).show()
                                            } else {
                                                blocks.forEach { block ->
                                                    viewModel.togglePendingDelete(targetUri, block.bounds.hashCode(), true)
                                                }
                                                Toast.makeText(context, "Đã tắt bản dịch", Toast.LENGTH_SHORT).show()
                                            }
                                        } else if (targetUri != null && blocks.isEmpty()) {
                                            Toast.makeText(context, "Ảnh này chưa có bản dịch", Toast.LENGTH_SHORT).show()
                                        }
                                        onImageMenuDismiss()
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(if (allBlocksHidden) "ON" else "OFF", modifier = Modifier.padding(start = 8.dp))
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val targetUri = imageMenuUri
                                        when {
                                            mode == TranslationMode.GEMINI && !viewModel.hasGeminiApiKeys() -> {
                                                Toast.makeText(context, "Không có API key Gemini", Toast.LENGTH_LONG).show()
                                                onImageMenuDismiss()
                                                return@clickable
                                            }
                                            mode == TranslationMode.MISTRAL && !viewModel.hasMistralApiKeys() -> {
                                                Toast.makeText(context, "Không có API key Mistral", Toast.LENGTH_LONG).show()
                                                onImageMenuDismiss()
                                                return@clickable
                                            }
                                            mode == TranslationMode.ZAI && !viewModel.hasZAiApiKeys() -> {
                                                Toast.makeText(context, "Không có API key Z.AI", Toast.LENGTH_LONG).show()
                                                onImageMenuDismiss()
                                                return@clickable
                                            }
                                            targetUri != null -> {
                                                val existingBlocks = viewModel.getExistingBlocksForUri(targetUri)
                                                val hasOriginalText = existingBlocks.any { !it.originalText.isNullOrBlank() }
                                                if (existingBlocks.isNotEmpty() && hasOriginalText && mode != TranslationMode.OFF) {
                                                    // Có original đã lưu → hỏi user chọn OCR lại hay giữ
                                                    pendingReTranslateMode = mode
                                                    currentUri = targetUri
                                                    showReTranslateDialog = true
                                                } else {
                                                    Toast.makeText(context, "Đang dịch lại ảnh...", Toast.LENGTH_SHORT).show()
                                                    viewModel.retranslateImage(targetUri, mode)
                                                    coroutineScope.launch {
                                                        while (true) {
                                                            val status = viewModel.uiState.value.translatedStatus[targetUri]
                                                            if (status == true) break
                                                            delay(200)
                                                        }
                                                        val rid = viewModel.uiState.value.roomId
                                                        val imageId = viewModel.uriToImageId[targetUri]
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
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



    // Dialog chọn OCR lại từ đầu hoặc giữ OCR cũ khi retranslate
    if (showReTranslateDialog && currentUri != null && pendingReTranslateMode != null) {
        val mode = pendingReTranslateMode!!
        val uri = currentUri!!
        AlertDialog(
            onDismissRequest = { showReTranslateDialog = false },
            title = { Text("Chọn cách dịch lại") },
            text = {
                Column {
                    Text("Bạn muốn sử dụng phương thức nào để dịch lại ảnh này?")
                    Spacer(Modifier.height(16.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // OCR lại từ đầu
                        Toast.makeText(context, "Đang dịch lại ảnh (OCR mới)...", Toast.LENGTH_SHORT).show()
                        viewModel.retranslateImage(uri, mode, reuseExistingOcr = false)
                        coroutineScope.launch {
                            while (true) {
                                val status = viewModel.uiState.value.translatedStatus[uri]
                                if (status == true) break
                                delay(200)
                            }
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
                        showReTranslateDialog = false
                    }
                ) {
                    Text("OCR lại từ đầu")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Giữ OCR cũ
                        val existingBlocks = viewModel.getExistingBlocksForUri(uri)
                        Toast.makeText(context, "Đang dịch lại ảnh (giữ OCR cũ)...", Toast.LENGTH_SHORT).show()
                        viewModel.retranslateImage(uri, mode, reuseExistingOcr = true, existingBlocks = existingBlocks)
                        coroutineScope.launch {
                            while (true) {
                                val status = viewModel.uiState.value.translatedStatus[uri]
                                if (status == true) break
                                delay(200)
                            }
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
                        showReTranslateDialog = false
                    }
                ) {
                    Text("Giữ OCR cũ")
                }
            }
        )
    }
}