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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
                if (!isBulk && blocks.isEmpty() && !isProcessing) {
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
