package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import android.content.Intent
import android.os.Environment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Download
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
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
    viewModel: ViewerViewModel,
    showExternalTranslationDialog: Boolean = false,
    externalTranslationUri: Uri? = null,
    onExternalTranslationDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var currentUri by remember { mutableStateOf<Uri?>(null) }

    // State cho dialog chọn OCR lại / giữ OCR cũ khi retranslate
    var showReTranslateDialog by remember { mutableStateOf(false) }
    var pendingReTranslateMode by remember { mutableStateOf<TranslationMode?>(null) }
    var reTranslateRemember by remember { mutableStateOf(false) }
    val ocrPreference by ViewerPreferences.ocrPreferenceFlow(context).collectAsState(initial = false to true)

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
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Chèn ảnh vào vị trí", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
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
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = { Text("Xác nhận thoát", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = { Text("Bạn có chắc chắn muốn thoát?\nTất cả ảnh và dữ liệu phiên này sẽ bị xóa.") },
            confirmButton = {
                Button(
                    onClick = onExitConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Thoát", color = MaterialTheme.colorScheme.onError) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onExitDismiss,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Hủy") }
            }
        )
    }

    if (showEditTitleDialog) {
        AlertDialog(
            onDismissRequest = onEditTitleDismiss,
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = { Text("Đổi tên truyện", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editTitleText,
                    onValueChange = onEditTitleChange,
                    label = { Text("Tên mới") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = onEditTitleConfirm,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Lưu") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onEditTitleDismiss,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Hủy") }
            }
        )
    }

    if (showImageMenu && imageMenuUri != null) {
        val uri = imageMenuUri
        val blocks = viewModel.uiState.value.translatedTexts[uri]?.second ?: emptyList()

        AlertDialog(
            onDismissRequest = onImageMenuDismiss,
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = { Text("Tùy chọn ảnh", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                            imageMenuUri?.let {
                                viewModel.optimizeImageOverlay(it)
                                Toast.makeText(context, "Đang tối ưu hiển thị...", Toast.LENGTH_SHORT).show()
                            }
                            onImageMenuDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tối ưu hiển thị overlay") }

                    Spacer(Modifier.height(16.dp))

                    Text("Dịch lại ảnh với:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))

                    listOf(TranslationMode.OFFLINE, TranslationMode.ONLINE, TranslationMode.OFF, TranslationMode.GEMINI, TranslationMode.MISTRAL, TranslationMode.ZAI, TranslationMode.EXTERNAL).forEach { mode ->
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
                            val isNetworkAvailable = viewModel.isNetworkAvailable()
                            val (hasKey, modelName) = when(mode) {
                                TranslationMode.GEMINI -> viewModel.hasGeminiApiKeys() to "Gemini"
                                TranslationMode.MISTRAL -> viewModel.hasMistralApiKeys() to "Mistral"
                                TranslationMode.ZAI -> viewModel.hasZAiApiKeys() to "Z.AI"
                                else -> true to ""
                            }
                            
                            val isNetworkRequired = mode == TranslationMode.ONLINE || mode == TranslationMode.GEMINI || mode == TranslationMode.MISTRAL || mode == TranslationMode.ZAI
                            val isApiKeyRequired = mode == TranslationMode.GEMINI || mode == TranslationMode.MISTRAL || mode == TranslationMode.ZAI
                            
                            val isDimmed = (isNetworkRequired && !isNetworkAvailable) || (isApiKeyRequired && !hasKey)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isDimmed) 0.5f else 1.0f)
                                    .clickable {
                                        if (isNetworkRequired && !isNetworkAvailable) {
                                            Toast.makeText(context, "Vui lòng kiểm tra kết nối mạng", Toast.LENGTH_SHORT).show()
                                            return@clickable
                                        }
                                        if (isApiKeyRequired && !hasKey) {
                                            Toast.makeText(context, "Mô hình $modelName chưa có api key", Toast.LENGTH_SHORT).show()
                                            return@clickable
                                        }

                                        val targetUri = imageMenuUri
                                        if (targetUri != null) {
                                            val existingBlocks = viewModel.getReusableOcrBlocksForUri(targetUri)
                                            if (existingBlocks.isNotEmpty() && mode != TranslationMode.OFF) {
                                                val (remember, reuse) = ocrPreference
                                                if (remember) {
                                                    // Sử dụng lựa chọn đã nhớ
                                                    if (mode == TranslationMode.EXTERNAL) {
                                                        viewModel.openExternalTranslationDialog(targetUri, reuseExistingOcr = reuse)
                                                    } else {
                                                        Toast.makeText(context, "Đang dịch lại ảnh...", Toast.LENGTH_SHORT).show()
                                                        viewModel.retranslateImage(targetUri, mode, reuseExistingOcr = reuse, existingBlocks = if (reuse) existingBlocks else null)
                                                        // (Wait logic omitted for brevity as it's the same)
                                                    }
                                                } else {
                                                    // Có original đã lưu → hỏi user chọn OCR lại hay giữ
                                                    pendingReTranslateMode = mode
                                                    currentUri = targetUri
                                                    reTranslateRemember = false
                                                    showReTranslateDialog = true
                                                }
                                            } else {
                                                if (mode == TranslationMode.EXTERNAL) {
                                                    viewModel.openExternalTranslationDialog(targetUri)
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
                                                        onImageMenuDismiss()
                                                    }
                                                    return@clickable
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
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = { Text("Chọn cách dịch lại", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column {
                    Text("Bạn muốn sử dụng phương thức nào để dịch lại ảnh này?")
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { reTranslateRemember = !reTranslateRemember }
                    ) {
                        Checkbox(
                            checked = reTranslateRemember,
                            onCheckedChange = { reTranslateRemember = it }
                        )
                        Text("Nhớ tùy chọn này", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (reTranslateRemember) {
                            coroutineScope.launch {
                                ViewerPreferences.saveOcrPreference(context, true, false)
                            }
                        }
                        // OCR lại từ đầu
                        Toast.makeText(context, "Đang dịch lại ảnh (OCR mới)...", Toast.LENGTH_SHORT).show()
                        if (mode == TranslationMode.EXTERNAL) {
                            viewModel.openExternalTranslationDialog(uri, reuseExistingOcr = false)
                            showReTranslateDialog = false
                            return@TextButton
                        }
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
                        if (reTranslateRemember) {
                            coroutineScope.launch {
                                ViewerPreferences.saveOcrPreference(context, true, true)
                            }
                        }
                        // Giữ OCR cũ
                        val existingBlocks = viewModel.getReusableOcrBlocksForUri(uri)
                        Toast.makeText(context, "Đang dịch lại ảnh (giữ OCR cũ)...", Toast.LENGTH_SHORT).show()
                        if (mode == TranslationMode.EXTERNAL) {
                            viewModel.openExternalTranslationDialog(uri, reuseExistingOcr = true)
                            showReTranslateDialog = false
                            return@TextButton
                        }
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

    if (showExternalTranslationDialog) {
        ExternalTranslationDialog(
            uri = externalTranslationUri,
            viewModel = viewModel,
            onDismiss = onExternalTranslationDismiss
        )
    }
}

@Composable
fun ExternalTranslationDialog(
    uri: android.net.Uri?,
    viewModel: ViewerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uiState by viewModel.uiState.collectAsState()

    val isBulk = uri == null
    val bulkProgress = uiState.bulkScanningProgress
    val isBulkScanning = bulkProgress.isNotEmpty()

    // Theo dõi thay đổi của blocks để cập nhật nội dung JSON/Prompt
    val blocks = if (uri != null) uiState.translatedTexts[uri]?.second ?: emptyList() else emptyList()
    val isProcessing = if (uri != null) uiState.translatingImages.containsKey(uri) else false

    // Sử dụng translationVersion để force recalculate JSON khi có trang mới được quét OCR xong
    val jsonContent = remember(uri, blocks, uiState.imageUris.size, uiState.translationVersion) { viewModel.exportBlocksToJson(uri) }
    val promptContent = remember(uri, blocks, uiState.imageUris.size, uiState.translationVersion) { viewModel.getExternalTranslationPrompt(uri) }

    var translatedJson by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = { Text(if (isBulk) "Dịch ngoài hàng loạt" else "Dịch bằng bản dịch ngoài", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                if (isBulkScanning || isProcessing || (uri != null && blocks.isEmpty() && uiState.translatingImages.containsKey(uri))) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (isBulkScanning) bulkProgress else "Đang OCR để lấy text gốc...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else if (!isBulk && blocks.isEmpty()) {
                    Text("Không tìm thấy văn bản nào trên ảnh này để dịch.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Bước 1: Copy file JSON OCR", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = jsonContent,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                            maxLines = 5
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(jsonContent))
                            Toast.makeText(context, "Đã copy JSON", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy JSON")
                        }

                        IconButton(onClick = {
                            try {
                                val fileName = if (isBulk) "ocr_bulk_data.json" else "ocr_data.json"
                                val file = File(context.cacheDir, fileName)
                                FileOutputStream(file).use { it.write(jsonContent.toByteArray()) }
                                val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, fileUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Tải file JSON về"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Lỗi khi lưu file: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "Tải JSON")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Bước 2: Gửi prompt cho AI (GPT, Gemini, Grok...)", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(promptContent))
                            Toast.makeText(context, "Đã copy Prompt", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Prompt mẫu")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Bước 3: Dán kết quả JSON đã dịch vào đây", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = translatedJson,
                        onValueChange = { translatedJson = it },
                        label = { Text("JSON bản dịch từ AI") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                        placeholder = { Text("Dán JSON kết quả từ AI vào đây...") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (translatedJson.isNotBlank()) {
                        viewModel.importTranslatedJson(uri, translatedJson)
                    } else if (isBulk || blocks.isNotEmpty()) {
                        Toast.makeText(context, "Vui lòng dán JSON bản dịch", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isBulkScanning && (isBulk || blocks.isNotEmpty()),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Áp dụng bản dịch")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Hủy")
            }
        }
    )
}
