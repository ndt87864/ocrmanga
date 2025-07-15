package com.example.ocrmanga.ui.screens.api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.ocrmanga.viewmodels.ApiKey
import com.example.ocrmanga.viewmodels.ApiKeyManagementViewModel

@Composable
fun ApiKeyManagementScreen(
    viewModel: ApiKeyManagementViewModel = ApiKeyManagementViewModel(LocalContext.current)
) {
    var newApiKey by remember { mutableStateOf("") }
    var isPopupVisible by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editingApiKey by remember { mutableStateOf<ApiKey?>(null) }
    var isDeleteConfirmationVisible by remember { mutableStateOf(false) }
    var apiKeyToDelete by remember { mutableStateOf<ApiKey?>(null) }

    val apiKeys = viewModel.apiKeys

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("API Key Management", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = {
                isPopupVisible = true
                isEditing = false
                newApiKey = ""
            }, modifier = Modifier.size(16.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Thêm API Key", tint = Color.Black)
            }
        }

        Divider()

        apiKeys.forEach { apiKey ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                elevation = CardDefaults.elevatedCardElevation(8.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                        )
                        .padding(16.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Key: ${apiKey.key}", style = MaterialTheme.typography.bodyMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Trạng thái:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = if (apiKey.isActive) "Hoạt động" else "Tắt",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .background(
                                        color = if (apiKey.isActive) Color.Red else Color.Gray,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .height(16.dp)
                                    .padding(horizontal = 8.dp)
                                    .clickable {
                                        viewModel.toggleApiKeyStatus(apiKey)
                                    },
                                color = Color.White
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Ngày thêm: ${apiKey.createdDate}", style = MaterialTheme.typography.bodySmall)
                            Text("Ngày cập nhật: ${apiKey.updatedDate}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = {
                            isPopupVisible = true
                            isEditing = true
                            editingApiKey = apiKey
                            newApiKey = apiKey.key
                        }, modifier = Modifier.size(12.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Sửa", tint = Color.Blue)
                        }
                        IconButton(onClick = {
                            isDeleteConfirmationVisible = true
                            apiKeyToDelete = apiKey
                        }, modifier = Modifier.size(12.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color.Red)
                        }
                    }
                }
            }
        }
    }

    if (isPopupVisible) {
        AlertDialog(
            onDismissRequest = { isPopupVisible = false },
            title = { Text(if (isEditing) "Sửa API Key" else "Thêm API Key") },
            text = {
                OutlinedTextField(
                    value = newApiKey,
                    onValueChange = { newApiKey = it },
                    label = { Text("API Key") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                IconButton(onClick = {
                    if (isEditing && editingApiKey != null) {
                        viewModel.editApiKey(editingApiKey!!, newApiKey)
                    } else {
                        viewModel.addApiKey(newApiKey)
                    }
                    isPopupVisible = false
                }) {
                    Icon(Icons.Default.Check, contentDescription = "Xác nhận", tint = Color.Blue)
                }
            },
            dismissButton = {
                IconButton(onClick = { isPopupVisible = false }) {
                    Icon(Icons.Default.Close, contentDescription = "Hủy", tint = Color.Gray)
                }
            }
        )
    }

    if (isDeleteConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { isDeleteConfirmationVisible = false },
            title = { Text("Xác nhận xóa API Key") },
            text = { Text("Bạn có chắc chắn muốn xóa API Key này không?") },
            confirmButton = {
                IconButton(onClick = {
                    apiKeyToDelete?.let { viewModel.deleteApiKey(it) }
                    isDeleteConfirmationVisible = false
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color.Red)
                }
            },
            dismissButton = {
                IconButton(onClick = { isDeleteConfirmationVisible = false }) {
                    Icon(Icons.Default.Close, contentDescription = "Hủy", tint = Color.Gray)
                }
            }
        )
    }
}