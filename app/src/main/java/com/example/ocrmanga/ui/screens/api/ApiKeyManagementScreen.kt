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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    // Thêm biến để chọn loại key hiển thị
    var selectedDisplayType by remember { mutableStateOf(viewModel.getDefaultKeyType()) }
    // Biến lưu các id của card được chọn
    var selectedCardIds by remember { mutableStateOf(setOf<String>()) }
    // Biến kiểm soát chế độ chọn (khi có ít nhất 1 card được chọn)
    val isSelectionMode = selectedCardIds.isNotEmpty()
    // Biến popup xác nhận xóa nhiều
    var isDeleteMultiConfirmationVisible by remember { mutableStateOf(false) }

    val apiKeys = viewModel.apiKeys

    // Lấy danh sách các card đang hiển thị (đặt ngoài để dùng cho AlertDialog)
    val displayedApiKeys = apiKeys.filter { it.type == selectedDisplayType }
    val displayedCardIds = displayedApiKeys.map { it.key + ":" + it.type }

    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Text("API Key Management", style = MaterialTheme.typography.titleLarge)
            }

            Divider()
            // Dropdown chọn loại key hiển thị
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
                        selectedType = it // Đồng bộ loại key trong popup với loại đang hiển thị
                        viewModel.setDefaultKeyType(it)
                    })
            }

            // Khu vực cuộn cho danh sách key
            androidx.compose.foundation.rememberScrollState().let { scrollState ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    // Hiển thị checkbox chọn tất cả khi có ít nhất 1 card được chọn
                    if (isSelectionMode && displayedCardIds.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedCardIds.containsAll(displayedCardIds),
                                onCheckedChange = { checked ->
                                    selectedCardIds =
                                        if (checked) displayedCardIds.toSet() else selectedCardIds - displayedCardIds.toSet()
                                }
                            )
                            Text("Chọn tất cả", style = MaterialTheme.typography.bodyMedium)
                            IconButton(
                                onClick = {
                                    isDeleteMultiConfirmationVisible = true
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Xóa các key đã chọn",
                                    tint = Color.Red
                                )
                            }
                        }
                    }

                    // Lọc apiKeys theo selectedDisplayType
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
                                    onLongClick = {
                                        selectedCardIds = selectedCardIds + cardId
                                    }
                                ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F5F1))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(0.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFEDE7DF), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF2D2D2D),
                                        modifier = Modifier.weight(1f)
                                    )
                                    // Nút edit
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(
                                                color = Color(0xFFF3ECE3),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable {
                                                isPopupVisible = true
                                                isEditing = true
                                                editingApiKey = apiKey
                                                newApiKey = apiKey.key
                                                selectedType = apiKey.type
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Sửa",
                                            tint = Color.Blue,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Nút xóa
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(
                                                color = Color(0xFFF3ECE3),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable {
                                                isDeleteConfirmationVisible = true
                                                apiKeyToDelete = apiKey
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Xóa",
                                            tint = Color.Red,
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                                // Nội dung card
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    if (isSelectionMode) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(
                                                    color = Color(0xFFF3ECE3),
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .clickable {
                                                    selectedCardIds = if (selectedCardIds.contains(cardId))
                                                        selectedCardIds - cardId else selectedCardIds + cardId
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selectedCardIds.contains(cardId)) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Đã chọn",
                                                    tint = Color(0xFF2D2D2D),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                    Text(
                                        "Key: ${apiKey.key}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "Loại: ${if (apiKey.type == "gemini") "Gemini" else if (apiKey.type == "mistral") "Mistral" else apiKey.type}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
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
                                        Text(
                                            "Ngày thêm: ${apiKey.createdDate}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "Ngày cập nhật: ${apiKey.updatedDate}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Button thêm key nổi góc phải dưới
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            IconButton(
                onClick = {
                    isPopupVisible = true
                    isEditing = false
                    newApiKey = ""
                    selectedType = selectedDisplayType
                },
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.medium
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Thêm API Key",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Loại key:", modifier = Modifier.padding(end = 8.dp))
                            DropdownMenuType(
                                selectedType = selectedType,
                                onTypeSelected = { selectedType = it })
                        }
                    }
                },
                confirmButton = {
                    IconButton(onClick = {
                        if (isEditing && editingApiKey != null) {
                            // Kiểm tra type trước khi sửa
                            if (editingApiKey!!.type == selectedType) {
                                viewModel.editApiKeyWithType(editingApiKey!!.key, editingApiKey!!.type, newApiKey, selectedType)
                                android.widget.Toast.makeText(
                                    context,
                                    "Sửa API Key thành công",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "Không thể sửa: Loại key không khớp!",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            val shouldSwitch = viewModel.addApiKey(newApiKey, selectedType, selectedDisplayType)
                            android.widget.Toast.makeText(
                                context,
                                "Thêm API Key thành công",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            if (shouldSwitch) {
                                selectedDisplayType = selectedType
                            }
                        }
                        isPopupVisible = false
                    }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Xác nhận",
                            tint = Color.Blue
                        )
                    }
                },
                dismissButton = {
                    IconButton(onClick = { isPopupVisible = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Hủy", tint = Color.Gray)
                    }
                }
            )
        }

        if (isDeleteConfirmationVisible && apiKeyToDelete != null) {
            AlertDialog(
                onDismissRequest = { isDeleteConfirmationVisible = false },
                title = { Text("Xác nhận xóa") },
                text = { Text("Bạn có chắc chắn muốn xóa API Key này không?") },
                confirmButton = {
                    IconButton(onClick = {
                        // Kiểm tra type trước khi xóa
                        if (apiKeyToDelete!!.type == selectedType) {
                            viewModel.deleteApiKeyWithType(apiKeyToDelete!!.key, apiKeyToDelete!!.type)
                            android.widget.Toast.makeText(
                                context,
                                "Đã xóa API Key",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "Không thể xóa: Loại key không khớp!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        isDeleteConfirmationVisible = false
                    }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Xác nhận xóa",
                            tint = Color.Red
                        )
                    }
                },
                dismissButton = {
                    IconButton(onClick = { isDeleteConfirmationVisible = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Hủy", tint = Color.Gray)
                    }
                }
            )
        }

        // Popup xác nhận xóa nhiều key
        if (isDeleteMultiConfirmationVisible) {
            AlertDialog(
                onDismissRequest = { isDeleteMultiConfirmationVisible = false },
                title = { Text("Xác nhận xóa") },
                text = { Text("Bạn có chắc chắn muốn xóa tất cả các key đã chọn không?") },
                confirmButton = {
                    IconButton(onClick = {
                        // Chỉ xóa các key có type đúng selectedType và nằm trong selectedCardIds
                        val keysToDelete = displayedApiKeys.filter {
                            it.type == selectedType && selectedCardIds.contains(it.key + ":" + it.type)
                        }
                        keysToDelete.forEach { viewModel.deleteApiKeyWithType(it.key, it.type) }
                        // Chỉ loại bỏ các id đã xóa khỏi selectedCardIds
                        selectedCardIds =
                            selectedCardIds - keysToDelete.map { it.key + ":" + it.type }.toSet()
                        isDeleteMultiConfirmationVisible = false
                        android.widget.Toast.makeText(
                            context,
                            "Đã xóa các API Key đã chọn",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Xác nhận xóa",
                            tint = Color.Red
                        )
                    }
                },
                dismissButton = {
                    IconButton(onClick = { isDeleteMultiConfirmationVisible = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Hủy", tint = Color.Gray)
                    }
                }
            )
        }

    }



@Composable
fun DropdownMenuType(selectedType: String, onTypeSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(
                when (selectedType) {
                    "gemini" -> "Gemini"
                    "mistral" -> "Mistral"
                    else -> selectedType
                }
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Gemini") },
                onClick = {
                    onTypeSelected("gemini")
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Mistral") },
                onClick = {
                    onTypeSelected("mistral")
                    expanded = false
                }
            )
        }
    }

    // Đoạn AlertDialog xác nhận xóa không nên nằm trong DropdownMenuType, đã có ở trên
}
