package com.example.ocrmanga.ui.screens.api

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.surfaceColorAtElevation
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox

@OptIn(ExperimentalFoundationApi::class)
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
    var selectedType by remember { mutableStateOf("gemini") }
    var selectedDisplayType by remember { mutableStateOf(viewModel.getDefaultKeyType()) }
    var selectedCardIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedCardIds.isNotEmpty()
    var isDeleteMultiConfirmationVisible by remember { mutableStateOf(false) }

    val apiKeys = viewModel.apiKeys
    val displayedApiKeys = apiKeys.filter { it.type == selectedDisplayType }
    val displayedCardIds = displayedApiKeys.map { it.key + ":" + it.type }

    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("API Key Management", style = MaterialTheme.typography.titleLarge)
            Divider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chọn loại key hiển thị:", modifier = Modifier.padding(end = 8.dp))
                DropdownMenuType(
                    selectedType = selectedDisplayType,
                    onTypeSelected = {
                        selectedDisplayType = it
                        selectedType = it
                        viewModel.setDefaultKeyType(it)
                    })
            }

            androidx.compose.foundation.rememberScrollState().let { scrollState ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    if (isSelectionMode && displayedCardIds.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedCardIds.containsAll(displayedCardIds),
                                onCheckedChange = { checked ->
                                    selectedCardIds = if (checked) displayedCardIds.toSet() else selectedCardIds - displayedCardIds.toSet()
                                }
                            )
                            Text("Chọn tất cả", style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { isDeleteMultiConfirmationVisible = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color.Red)
                            }
                        }
                    }

                    displayedApiKeys.forEachIndexed { index, apiKey ->
                        val cardId = apiKey.key + ":" + apiKey.type
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedCardIds = if (selectedCardIds.contains(cardId))
                                                selectedCardIds - cardId else selectedCardIds + cardId
                                        }
                                    },
                                    onLongClick = { selectedCardIds = selectedCardIds + cardId }
                                ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(0.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // Nút edit
                                    Box(
                                        modifier = Modifier.size(28.dp).background(Color.White, RoundedCornerShape(6.dp)).clickable {
                                            isPopupVisible = true
                                            isEditing = true
                                            editingApiKey = apiKey
                                            newApiKey = apiKey.key
                                            selectedType = apiKey.type
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Sửa", tint = Color.Blue, modifier = Modifier.size(14.dp))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Nút xóa
                                    Box(
                                        modifier = Modifier.size(28.dp).background(Color.White, RoundedCornerShape(6.dp)).clickable {
                                            isDeleteConfirmationVisible = true
                                            apiKeyToDelete = apiKey
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = Color.Red, modifier = Modifier.size(10.dp))
                                    }
                                }
                                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text("Key: ${apiKey.key}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Loại: ${apiKey.type}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "Trạng thái: ${if (apiKey.isActive) "Hoạt động" else "Tắt"}",
                                        color = if (apiKey.isActive) Color(0xFF4CAF50) else Color.Gray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text("Ngày thêm: ${apiKey.createdDate}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            IconButton(
                onClick = {
                    isPopupVisible = true
                    isEditing = false
                    newApiKey = ""
                    selectedType = selectedDisplayType
                },
                modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Thêm", tint = Color.White)
            }
        }
    }

    if (isPopupVisible) {
        AlertDialog(
            onDismissRequest = { isPopupVisible = false },
            title = { Text(if (isEditing) "Sửa API Key" else "Thêm API Key") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newApiKey,
                        onValueChange = { newApiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DropdownMenuType(selectedType = selectedType, onTypeSelected = { selectedType = it })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (isEditing && editingApiKey != null) {
                        viewModel.editApiKey(editingApiKey!!, newApiKey, selectedType)
                    } else {
                        viewModel.addApiKey(newApiKey, selectedType, selectedDisplayType)
                    }
                    isPopupVisible = false
                }) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { isPopupVisible = false }) { Text("Hủy") } }
        )
    }

    if (isDeleteConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { isDeleteConfirmationVisible = false },
            title = { Text("Xác nhận xóa") },
            text = { Text("Bạn có chắc chắn muốn xóa API Key này không?") },
            confirmButton = {
                Button(onClick = {
                    apiKeyToDelete?.let { viewModel.deleteApiKey(it) }
                    isDeleteConfirmationVisible = false
                }) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { isDeleteConfirmationVisible = false }) { Text("Hủy") } }
        )
    }
}

@Composable
fun DropdownMenuType(selectedType: String, onTypeSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) { Text(selectedType.uppercase()) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("gemini", "mistral").forEach { type ->
                DropdownMenuItem(text = { Text(type.uppercase()) }, onClick = {
                    onTypeSelected(type)
                    expanded = false
                })
            }
        }
    }
}
