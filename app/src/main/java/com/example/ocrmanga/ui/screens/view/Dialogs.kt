package com.example.ocrmanga.ui.screens.view

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
                    Text("Dịch lại ảnh với:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TranslationMode.values().forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val uri = imageMenuUri
                                    if (uri != null) {
                                        Toast.makeText(context, "Đang dịch lại ảnh...", Toast.LENGTH_SHORT).show()
                                        onRetranslateImage(uri, mode)
                                        coroutineScope.launch {
                                            while (true) {
                                                val status = viewModel.uiState.value.translatedStatus[uri]
                                                if (status == true) break
                                                delay(200)
                                            }
                                            Toast.makeText(context, "Dịch lại ảnh hoàn tất!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    onImageMenuDismiss()
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(mode.name, modifier = Modifier.padding(start = 8.dp))
                        }
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