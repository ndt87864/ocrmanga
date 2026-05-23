package com.example.ocrmanga.ui.screens.api

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.ocrmanga.viewmodels.ApiKey
import com.example.ocrmanga.viewmodels.ApiKeyManagementViewModel
import com.example.ocrmanga.ui.components.AppTutorialOverlay
import com.example.ocrmanga.ui.components.TutorialStep
import com.example.ocrmanga.ui.components.tutorialTag
import com.example.ocrmanga.ui.screens.view.ViewerPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    var isManageModelsDialogVisible by remember { mutableStateOf(false) }

    val apiKeys = viewModel.apiKeys
    val displayedApiKeys = apiKeys.filter { it.type == selectedDisplayType }
    val displayedCardIds = displayedApiKeys.map { it.key + ":" + it.type }

    val context = LocalContext.current

    // Dropdown expanded state
    var dropdownExpanded by remember { mutableStateOf(false) }

    val testResults = remember { mutableStateMapOf<String, Pair<Boolean, String?>>() }
    
    // Tutorial State
    var tutorialDone by remember { mutableStateOf(true) }
    val targetPositions = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        ViewerPreferences.isTutorialDoneFlow(context, "api").collect { tutorialDone = it }
    }

    val tutorialSteps = listOf(
        TutorialStep("Quản lý API", "Để app có thể dịch được, bạn cần thêm API Key từ các dịch vụ như Google Gemini."),
        TutorialStep("Chọn loại AI", "Chọn dịch vụ AI bạn có Key tại đây.", "api_type"),
        TutorialStep("Thêm Key mới", "Nhấn vào nút '+' để nhập Key mới vào hệ thống.", "api_add"),
        TutorialStep("Danh sách Key", "Các Key của bạn sẽ hiển thị ở khu vực này.", "api_list")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Quản lý API Key",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    isPopupVisible = true
                    isEditing = false
                    newApiKey = ""
                    selectedType = selectedDisplayType
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .size(64.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .tutorialTag("api_add") { tag, rect -> targetPositions[tag] = rect }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Thêm API Key",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
                // ===== Filter row =====
                Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Hiển thị:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )

                // Polished dropdown
                Box {
                    OutlinedButton(
                        onClick = { dropdownExpanded = true },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.tutorialTag("api_type") { tag, rect -> targetPositions[tag] = rect },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Text(
                            text = selectedDisplayType.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        listOf("gemini", "mistral", "zai").forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            type.uppercase(),
                                            modifier = Modifier.weight(1f),
                                            fontWeight = if (type == selectedDisplayType) FontWeight.Bold
                                                else FontWeight.Normal
                                        )
                                        if (type == selectedDisplayType) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedDisplayType = type
                                    selectedType = type
                                    viewModel.setDefaultKeyType(type)
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = { isManageModelsDialogVisible = true },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Quản lý mô hình",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ===== Select-all bar =====
            if (isSelectionMode && displayedCardIds.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedCardIds.containsAll(displayedCardIds),
                            onCheckedChange = { checked ->
                                selectedCardIds = if (checked) displayedCardIds.toSet()
                                    else selectedCardIds - displayedCardIds.toSet()
                            }
                        )
                        Text(
                            "Chọn tất cả (${displayedCardIds.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalIconButton(
                            onClick = { isDeleteMultiConfirmationVisible = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Xóa đã chọn",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ===== API Key list =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 96.dp) // space for FAB
                    .tutorialTag("api_list") { tag, rect -> targetPositions[tag] = rect }
            ) {
                if (displayedApiKeys.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Chưa có API Key nào",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Nhấn nút + để thêm API Key mới",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                displayedApiKeys.forEachIndexed { index, apiKey ->
                    val cardId = apiKey.key + ":" + apiKey.type
                    val isSelected = selectedCardIds.contains(cardId)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedCardIds = if (selectedCardIds.contains(cardId))
                                            selectedCardIds - cardId else selectedCardIds + cardId
                                    }
                                },
                                onLongClick = { selectedCardIds = selectedCardIds + cardId }
                            ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Header row with index + actions
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Select checkbox in selection mode
                                if (isSelectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedCardIds = if (checked) selectedCardIds + cardId
                                                else selectedCardIds - cardId
                                        },
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }

                                // Index badge
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                // Active indicator
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (apiKey.isActive)
                                        Color(0xFF4CAF50).copy(alpha = 0.15f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = if (apiKey.isActive) "Hoạt động" else "Tắt",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (apiKey.isActive) Color(0xFF2E7D32)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Edit button
                                FilledIconButton(
                                    onClick = {
                                        isPopupVisible = true
                                        isEditing = true
                                        editingApiKey = apiKey
                                        newApiKey = apiKey.key
                                        selectedType = apiKey.type
                                    },
                                    modifier = Modifier.size(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Sửa",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Delete button
                                FilledIconButton(
                                    onClick = {
                                        isDeleteConfirmationVisible = true
                                        apiKeyToDelete = apiKey
                                    },
                                    modifier = Modifier.size(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Xóa",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Body content
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Key: ${apiKey.key}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        text = "Loại: ${apiKey.type.uppercase()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Ngày thêm: ${apiKey.createdDate}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )

                                Text(
                                    text = "Mô hình được phép sử dụng:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                val modelsList = viewModel.getAvailableModels(apiKey.type)

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    modelsList.forEach { modelName ->
                                        val isAllowed = apiKey.isModelAllowed(modelName)
                                        val testKey = "${apiKey.key}:$modelName"
                                        val testState = testResults[testKey]

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isAllowed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                                    else Color.Transparent,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Checkbox(
                                                checked = isAllowed,
                                                onCheckedChange = { checked ->
                                                    val currentAllowed = apiKey.allowedModels.split(",")
                                                        .map { it.trim() }
                                                        .filter { it.isNotEmpty() }
                                                        .toMutableList()
                                                    if (isAllowed) {
                                                        currentAllowed.remove(modelName)
                                                    } else {
                                                        currentAllowed.add(modelName)
                                                    }
                                                    viewModel.updateAllowedModels(apiKey, currentAllowed.joinToString(","))
                                                },
                                                modifier = Modifier.size(24.dp)
                                            )

                                            Text(
                                                text = modelName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f),
                                                fontWeight = if (isAllowed) FontWeight.Medium else FontWeight.Normal,
                                                color = if (isAllowed) MaterialTheme.colorScheme.onSurface
                                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                            )

                                            // Nút test riêng lẻ cho model này
                                            IconButton(
                                                onClick = {
                                                    testResults[testKey] = Pair(true, "Đang kết nối...")
                                                    viewModel.testConnection(
                                                        apiKey = apiKey.key,
                                                        type = apiKey.type,
                                                        model = modelName,
                                                        onResult = { success, msg ->
                                                            testResults[testKey] = Pair(false, msg)
                                                        }
                                                    )
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Test",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            // Trạng thái test của model này
                                            if (testState != null) {
                                                val isTesting = testState.first
                                                val result = testState.second
                                                if (isTesting) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                } else if (result != null) {
                                                    val isSuccess = result == "Kết nối thành công!"
                                                    val icon = if (isSuccess) Icons.Default.Check else Icons.Default.Close
                                                    val color = if (isSuccess) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        tint = color,
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .clickable {
                                                                if (!isSuccess) {
                                                                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                                                                }
                                                            }
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
            }
        }
    }

    // ===== ADD/EDIT DIALOG =====
    if (isPopupVisible) {
        AlertDialog(
            onDismissRequest = { isPopupVisible = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = if (isEditing) "Sửa API Key" else "Thêm API Key",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newApiKey,
                        onValueChange = { newApiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("Nhập API Key...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // Type selector
                    var typeDropdownExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = typeDropdownExpanded,
                        onExpandedChange = { typeDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedType.uppercase(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Loại") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = typeDropdownExpanded,
                            onDismissRequest = { typeDropdownExpanded = false }
                        ) {
                            listOf("gemini", "mistral", "zai").forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.uppercase()) },
                                    onClick = {
                                        selectedType = type
                                        typeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isEditing && editingApiKey != null) {
                            viewModel.editApiKey(editingApiKey!!, newApiKey, selectedType)
                        } else {
                            viewModel.addApiKey(newApiKey, selectedType, selectedDisplayType)
                        }
                        isPopupVisible = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    enabled = newApiKey.isNotBlank()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lưu")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { isPopupVisible = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Hủy")
                }
            }
        )
    }

    // ===== DELETE SINGLE DIALOG =====
    if (isDeleteConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { isDeleteConfirmationVisible = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Xác nhận xóa",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            },
            text = {
                Text(
                    text = "Bạn có chắc chắn muốn xóa API Key này không?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        apiKeyToDelete?.let { viewModel.deleteApiKey(it) }
                        isDeleteConfirmationVisible = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Xóa")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { isDeleteConfirmationVisible = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Hủy")
                }
            }
        )
    }

    // ===== DELETE MULTI DIALOG =====
    if (isDeleteMultiConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { isDeleteMultiConfirmationVisible = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Xác nhận xóa hàng loạt",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            },
            text = {
                Text(
                    text = "Bạn có chắc chắn muốn xóa ${selectedCardIds.size} API Key đã chọn không?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteApiKeys(selectedCardIds.toList())
                        selectedCardIds = emptySet()
                        isDeleteMultiConfirmationVisible = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Xóa ${selectedCardIds.size} Key")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { isDeleteMultiConfirmationVisible = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Hủy")
                }
            }
        )
    }

    // ===== MANAGE MODELS DIALOG =====
    if (isManageModelsDialogVisible) {
        val availableModels = viewModel.getAvailableModels(selectedDisplayType)
        val historyModels = viewModel.getHistoryModels(selectedDisplayType)
        val deletedModels = historyModels.filterNot { availableModels.contains(it) }
        var newModelName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { isManageModelsDialogVisible = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Quản lý mô hình ${selectedDisplayType.uppercase()}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Current Active Models
                    Text(
                        text = "Mô hình đang hoạt động:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (availableModels.isEmpty()) {
                        Text(
                            text = "Không có mô hình nào đang hoạt động.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableModels.forEach { modelName ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = modelName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Medium
                                        )
                                        IconButton(
                                            onClick = {
                                                viewModel.removeAvailableModel(selectedDisplayType, modelName)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Xóa",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // 2. Quick Re-add from History
                    if (deletedModels.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Thêm nhanh lại từ lịch sử:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                deletedModels.forEach { deletedModel ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.addAvailableModel(selectedDisplayType, deletedModel)
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Thêm lại",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = deletedModel,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }

                    // 3. Custom Model Addition
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Thêm mô hình tùy chỉnh:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newModelName,
                                onValueChange = { newModelName = it },
                                placeholder = { Text("Tên mô hình...") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            Button(
                                onClick = {
                                    if (newModelName.isNotBlank()) {
                                        viewModel.addAvailableModel(selectedDisplayType, newModelName)
                                        newModelName = ""
                                    }
                                },
                                enabled = newModelName.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text("Thêm")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { isManageModelsDialogVisible = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Đóng")
                }
            }
        )
    }

    // Display Tutorial Overlay if not done
    if (!tutorialDone) {
        AppTutorialOverlay(
            steps = tutorialSteps,
            onComplete = {
                coroutineScope.launch {
                    ViewerPreferences.setTutorialDone(context, "api")
                }
            },
            targetPositions = targetPositions
        )
    }
}
