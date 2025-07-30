package com.example.ocrmanga.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ocrmanga.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: ThemeSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Observe theme state
    val themeState by viewModel.themeState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt giao diện") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Dark/Light Mode Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Chế độ hiển thị",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Chế độ tối")
                            Switch(
                                checked = themeState.isDarkMode,
                                onCheckedChange = { 
                                    scope.launch { 
                                        viewModel.setDarkMode(it) 
                                    }
                                }
                            )
                        }
                    }
                }
            }
            
            // Dynamic Color Section (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Màu động",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Sử dụng màu từ hình nền thiết bị",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Bật màu động")
                                Switch(
                                    checked = themeState.isDynamicColorEnabled,
                                    onCheckedChange = { 
                                        scope.launch { 
                                            viewModel.setDynamicColorEnabled(it) 
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            // Theme Variants Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Chọn chủ đề màu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        ThemeVariantSelector(
                            selectedVariant = themeState.themeVariant,
                            onVariantSelected = { variant ->
                                scope.launch {
                                    viewModel.setThemeVariant(variant)
                                }
                            }
                        )
                    }
                }
            }
            
            // Custom Color Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Màu tùy chỉnh",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Sử dụng màu tùy chỉnh")
                            Switch(
                                checked = themeState.useCustomColor,
                                onCheckedChange = { 
                                    scope.launch { 
                                        viewModel.setUseCustomColor(it) 
                                    }
                                }
                            )
                        }
                        
                        if (themeState.useCustomColor) {
                            Spacer(modifier = Modifier.height(12.dp))
                            CustomColorPicker(
                                selectedColor = themeState.customPrimaryColor?.toColor(),
                                onColorSelected = { color ->
                                    scope.launch {
                                        viewModel.setCustomPrimaryColor(
                                            color?.let { "#${Integer.toHexString(it.toArgb()).substring(2)}" }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
            
            // Preview Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Xem trước",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        ThemePreviewCard()
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeVariantSelector(
    selectedVariant: ThemeVariant,
    onVariantSelected: (ThemeVariant) -> Unit
) {
    val variants = listOf(
        ThemeVariant.DEFAULT_PURPLE to "Tím",
        ThemeVariant.BLUE to "Xanh dương", 
        ThemeVariant.GREEN to "Xanh lá",
        ThemeVariant.ORANGE to "Cam",
        ThemeVariant.RED to "Đỏ"
    )
    
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(variants) { (variant, name) ->
            ThemeVariantItem(
                variant = variant,
                name = name,
                isSelected = selectedVariant == variant,
                onClick = { onVariantSelected(variant) }
            )
        }
    }
}

@Composable
private fun ThemeVariantItem(
    variant: ThemeVariant,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val primaryColor = when (variant) {
        ThemeVariant.DEFAULT_PURPLE -> MaterialColors.Purple40
        ThemeVariant.BLUE -> MaterialColors.Blue40
        ThemeVariant.GREEN -> MaterialColors.Green40
        ThemeVariant.ORANGE -> MaterialColors.Orange40
        ThemeVariant.RED -> MaterialColors.Red40
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(primaryColor)
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun CustomColorPicker(
    selectedColor: Color?,
    onColorSelected: (Color?) -> Unit
) {
    val customColors = listOf(
        Color(0xFFE91E63), // Pink
        Color(0xFF9C27B0), // Purple
        Color(0xFF673AB7), // Deep Purple
        Color(0xFF3F51B5), // Indigo
        Color(0xFF2196F3), // Blue
        Color(0xFF03A9F4), // Light Blue
        Color(0xFF00BCD4), // Cyan
        Color(0xFF009688), // Teal
        Color(0xFF4CAF50), // Green
        Color(0xFF8BC34A), // Light Green
        Color(0xFFCDDC39), // Lime
        Color(0xFFFFEB3B), // Yellow
        Color(0xFFFFC107), // Amber
        Color(0xFFFF9800), // Orange
        Color(0xFFFF5722), // Deep Orange
        Color(0xFF795548), // Brown
    )
    
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(customColors) { color ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onColorSelected(color) }
                    .then(
                        if (selectedColor == color) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selectedColor == color) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Ứng dụng OCR Manga",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Đây là bản xem trước giao diện với chủ đề đã chọn",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Nút chính")
                }
                OutlinedButton(
                    onClick = { },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Nút phụ")
                }
            }
        }
    }
}

@Preview
@Composable
private fun ThemeSettingsScreenPreview() {
    OCRMangaThemePreview {
        ThemeSettingsScreen()
    }
}