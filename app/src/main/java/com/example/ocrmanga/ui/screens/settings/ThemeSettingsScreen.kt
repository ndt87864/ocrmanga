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
import com.example.ocrmanga.ui.components.*
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
                ModernCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionHeader(
                        title = "Chế độ hiển thị",
                        subtitle = "Tùy chỉnh chế độ sáng hoặc tối"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ModernSwitch(
                        checked = themeState.isDarkMode,
                        onCheckedChange = { 
                            scope.launch { 
                                viewModel.setDarkMode(it) 
                            }
                        },
                        label = "Chế độ tối",
                        description = "Sử dụng giao diện tối để bảo vệ mắt"
                    )
                }
            }
            
            // Dynamic Color Section (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    ModernCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SectionHeader(
                            title = "Màu động",
                            subtitle = "Sử dụng màu từ hình nền thiết bị (Android 12+)"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        ModernSwitch(
                            checked = themeState.isDynamicColorEnabled,
                            onCheckedChange = { 
                                scope.launch { 
                                    viewModel.setDynamicColorEnabled(it) 
                                }
                            },
                            label = "Bật màu động",
                            description = "Tự động thay đổi màu chủ đề theo hình nền"
                        )
                    }
                }
            }
            
            // Theme Variants Section
            item {
                ModernCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionHeader(
                        title = "Chọn chủ đề màu",
                        subtitle = "Lựa chọn bảng màu cho ứng dụng"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
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
            
            // Custom Color Section
            item {
                ModernCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionHeader(
                        title = "Màu tùy chỉnh",
                        subtitle = "Tự tạo màu chính theo ý thích"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ModernSwitch(
                        checked = themeState.useCustomColor,
                        onCheckedChange = { 
                            scope.launch { 
                                viewModel.setUseCustomColor(it) 
                            }
                        },
                        label = "Sử dụng màu tùy chỉnh",
                        description = if (themeState.useCustomColor) "Chọn màu yêu thích bên dưới" else "Bật để tùy chỉnh màu chính"
                    )
                    
                    if (themeState.useCustomColor) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CustomColorPicker(
                            selectedColor = themeState.customPrimaryColor?.toColor(),
                            onColorSelected = { color ->
                                scope.launch {
                                    viewModel.setCustomPrimaryColor(
                                        color?.let { "#${String.format("%08X", it.toArgb())}" }
                                    )
                                }
                            }
                        )
                    }
                }
            }
            
            // Preview Section
            item {
                ModernCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionHeader(
                        title = "Xem trước",
                        subtitle = "Kiểm tra giao diện với thiết lập hiện tại"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    ThemePreviewCard()
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Primary preview card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "OCR Manga",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ứng dụng đọc truyện với AI OCR",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        // Secondary components preview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Đọc ngay")
            }
            OutlinedButton(
                onClick = { },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Tìm hiểu")
            }
        }
        
        // Surface components preview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Giao diện hiện đại với Material 3",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            FilledTonalIconButton(
                onClick = { },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
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