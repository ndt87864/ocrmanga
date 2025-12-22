package com.example.ocrmanga.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.graphics.ColorUtils
import com.example.ocrmanga.ui.theme.*
import com.example.ocrmanga.R
import com.example.ocrmanga.ui.components.*
import com.example.ocrmanga.ui.components.AdvancedColorPicker
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
                        // State tạm thời cho màu custom, chỉ lưu khi nhấn xác nhận
                        var tempColor by remember(themeState.customPrimaryColor) {
                            mutableStateOf(themeState.customPrimaryColor?.toColor())
                        }
                        AdvancedColorPicker(
                            selectedColor = tempColor,
                            onColorSelected = { color ->
                                tempColor = color
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        viewModel.setCustomPrimaryColor(
                                            tempColor?.let { "#${String.format("%08X", it.toArgb())}" }
                                        )
                                    }
                                },
                                enabled = tempColor != null && "#${String.format("%08X", tempColor?.toArgb() ?: 0)}" != themeState.customPrimaryColor
                            ) {
                                Text("Xác nhận màu")
                            }
                        }
                    }
                }
            }
            
            // Default Translation Font Section
            item {
                ModernCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionHeader(
                        title = "Font dịch mặc định",
                        subtitle = "Font chữ sử dụng khi dịch ảnh"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    DefaultFontSelector(
                        selectedFont = themeState.defaultTranslationFont,
                        lineSpacing = themeState.defaultLineSpacing,
                        textBoldness = themeState.defaultTextBoldness,
                        textColor = themeState.defaultTextColor,
                        onFontSelected = { fontFamily ->
                            scope.launch {
                                viewModel.setDefaultTranslationFont(fontFamily)
                            }
                        },
                        onLineSpacingChanged = { spacing ->
                            scope.launch {
                                viewModel.setDefaultLineSpacing(spacing)
                            }
                        },
                        onTextBoldnessChanged = { boldness ->
                            scope.launch {
                                viewModel.setDefaultTextBoldness(boldness)
                            }
                        },
                        onTextColorChanged = { color ->
                            scope.launch {
                                viewModel.setDefaultTextColor(color)
                            }
                        },
                        onOverlayBrightnessChanged = { brightness ->
                            scope.launch {
                                viewModel.setDefaultOverlayBrightness(brightness)
                            }
                        }
                    )
                }
            }
            
            // Default Overlay Settings Section
            item {
                ModernCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionHeader(
                        title = "Overlay mặc định",
                        subtitle = "Thiết lập overlay cho bản dịch"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    DefaultOverlaySelector(
                        overlayAlpha = themeState.defaultOverlayAlpha,
                        overlayBrightness = themeState.defaultOverlayBrightness,
                        onOverlayAlphaChanged = { alpha ->
                            scope.launch {
                                viewModel.setDefaultOverlayAlpha(alpha)
                            }
                        },
                        onOverlayBrightnessChanged = { brightness ->
                            scope.launch {
                                viewModel.setDefaultOverlayBrightness(brightness)
                            }
                        },
                        onTextColorChanged = { color ->
                            scope.launch {
                                viewModel.setDefaultTextColor(color)
                            }
                        }
                    )
                }
            }
            
            // Default Border Settings Section
            item {
                ModernCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionHeader(
                        title = "Viền chữ mặc định",
                        subtitle = "Thiết lập viền cho chữ dịch"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    DefaultBorderSelector(
                        borderColor = themeState.defaultBorderColor,
                        borderThickness = themeState.defaultBorderThickness,
                        textColor = themeState.defaultTextColor,
                        onBorderColorChanged = { color ->
                            scope.launch {
                                viewModel.setDefaultBorderColor(color)
                            }
                        },
                        onBorderThicknessChanged = { thickness ->
                            scope.launch {
                                viewModel.setDefaultBorderThickness(thickness)
                            }
                        }
                    )
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

@Composable
private fun DefaultFontSelector(
    selectedFont: String,
    lineSpacing: Float,
    textBoldness: Float,
    textColor: String?,
    onFontSelected: (String) -> Unit,
    onLineSpacingChanged: (Float) -> Unit,
    onTextBoldnessChanged: (Float) -> Unit,
    onTextColorChanged: (String?) -> Unit,
    onOverlayBrightnessChanged: (Float) -> Unit
) {
    val context = LocalContext.current
    
    // Load fonts safely
    val fontOptions = remember {
        try {
            listOf(
                "mto_comic_1" to FontFamily(Font(R.font.mto_comic_1)),
                "mto_comic_2" to FontFamily(Font(R.font.mto_comic_2)),
                "mto_astro_city" to FontFamily(Font(R.font.mto_astro_city)),
                "mto_augie" to FontFamily(Font(R.font.mto_augie)),
                "mighty_zero" to FontFamily(Font(R.font.mighty_zero)),
                "mto_chancery" to FontFamily(Font(R.font.mto_chancery)),
                "mto_dom" to FontFamily(Font(R.font.mto_dom)),
                "mto_mikes" to FontFamily(Font(R.font.mto_mikes)),
                "mto_sans" to FontFamily(Font(R.font.mto_sans)),
                "mto_shadow" to FontFamily(Font(R.font.mto_shadow))
            )
        } catch (e: Exception) {
            android.util.Log.e("DefaultFontSelector", "Failed to load fonts", e)
            listOf("Default" to FontFamily.Default)
        }
    }
    
    val fontNames = listOf(
        "mto_comic_1" to "Comic 1",
        "mto_comic_2" to "Comic 2",
        "mto_astro_city" to "Astro City",
        "mto_augie" to "Augie",
        "mighty_zero" to "Mighty Zero",
        "mto_chancery" to "Chancery",
        "mto_dom" to "Dom",
        "mto_mikes" to "Mikes",
        "mto_sans" to "Sans",
        "mto_shadow" to "Shadow"
    )
    
    var showTextColorPicker by remember { mutableStateOf(false) }
    var tempTextColor by remember(textColor) {
        mutableStateOf(textColor?.let { Color(android.graphics.Color.parseColor(it)) })
    }
    
    var expanded by remember { mutableStateOf(false) }
    val selectedFontName = fontNames.find { it.first == selectedFont }?.second ?: "Comic 2"
    val selectedFontFamily = fontOptions.find { it.first == selectedFont }?.second ?: FontFamily.Default
    
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Font selector
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Font: $selectedFontName")
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            fontNames.forEach { (fontKey, fontName) ->
                DropdownMenuItem(
                    text = { 
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(fontName)
                            if (fontKey == selectedFont) {
                                Spacer(modifier = Modifier.width(8.dp))
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
                        onFontSelected(fontKey)
                        expanded = false
                    }
                )
            }
        }
        
        // Line spacing slider
        Column {
            Text(
                text = "Khoảng cách dòng: ${String.format("%.1f", lineSpacing)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = lineSpacing,
                onValueChange = onLineSpacingChanged,
                valueRange = 0.5f..2.0f,
                steps = 15
            )
        }
        
        // Text boldness slider
        Column {
            Text(
                text = "Độ đậm chữ: ${String.format("%.1f", textBoldness)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = textBoldness,
                onValueChange = onTextBoldnessChanged,
                valueRange = 0.5f..2.0f,
                steps = 15
            )
        }
        
        // Text color picker
        Column {
            Text(
                text = "Màu chữ:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            tempTextColor ?: Color.White,
                            shape = CircleShape
                        )
                        .border(
                            2.dp,
                            MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                        .clickable { showTextColorPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (tempTextColor == null) {
                        Text("?", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                
                Text(
                    text = if (tempTextColor != null) "Màu tùy chỉnh" else "Mặc định",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                OutlinedButton(
                    onClick = {
                        tempTextColor = null
                        onTextColorChanged(null)
                        onOverlayBrightnessChanged(1.0f) // Reset overlay to default bright
                    }
                ) {
                    Text("Reset")
                }
                
                Button(
                    onClick = {
                        val hex = tempTextColor?.let { "#${String.format("%08X", it.toArgb())}" }
                        onTextColorChanged(hex)
                        if (tempTextColor != null) {
                            val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(
                                tempTextColor!!.toArgb())
                            if (luminance > 0.5f) {
                                onOverlayBrightnessChanged(0.0f) // Dark overlay for light text
                            } else {
                                onOverlayBrightnessChanged(1.0f) // Bright overlay for dark text
                            }
                        }
                    },
                    enabled = tempTextColor?.let { "#${String.format("%08X", it.toArgb())}" } != textColor
                ) {
                    Text("Xác nhận")
                }
            }
            
            if (showTextColorPicker) {
                AdvancedColorPicker(
                    selectedColor = tempTextColor,
                    onColorSelected = { color ->
                        tempTextColor = color
                    }
                )
            }
        }
        
        // Font preview
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Xem trước font:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "Đây là văn bản mẫu\nđể xem trước font\nvà các thiết lập.",
                    fontFamily = selectedFontFamily,
                    fontWeight = FontWeight((textBoldness * 400).toInt().coerceIn(100, 900)),
                    lineHeight = (16.sp.value * lineSpacing).sp,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = (16.sp.value * lineSpacing).sp
                    ),
                    color = tempTextColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DefaultOverlaySelector(
    overlayAlpha: Float,
    overlayBrightness: Float,
    onOverlayAlphaChanged: (Float) -> Unit,
    onOverlayBrightnessChanged: (Float) -> Unit,
    onTextColorChanged: (String?) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Overlay Alpha slider
        Column {
            Text(
                text = "Độ trong suốt overlay: ${String.format("%.0f", overlayAlpha * 100)}%",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = overlayAlpha,
                onValueChange = onOverlayAlphaChanged,
                valueRange = 0.0f..1.0f,
                steps = 10
            )
        }
        
        // Overlay Brightness slider
        Column {
            Text(
                text = "Độ sáng overlay: ${String.format("%.1f", overlayBrightness)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = overlayBrightness,
                onValueChange = { newValue ->
                    onOverlayBrightnessChanged(newValue)
                    if (newValue > 1.0f) {
                        onTextColorChanged("#FF000000") // Dark text for bright overlay
                    } else {
                        onTextColorChanged("#FFFFFFFF") // Light text for dark overlay
                    }
                },
                valueRange = 0.0f..2.0f,
                steps = 20
            )
        }
        
        // Preview
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Xem trước overlay:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(
                            // Apply overlay saturation to a default white overlay color
                            if (overlayBrightness != 1.0f) {
                                val hsv = FloatArray(3)
                                androidx.core.graphics.ColorUtils.colorToHSL(0xFFFFFFFF.toInt(), hsv) // White color
                                hsv[1] = (hsv[1] * overlayBrightness).coerceIn(0f, 1f) // Modify saturation
                                Color(androidx.core.graphics.ColorUtils.HSLToColor(hsv)).copy(alpha = overlayAlpha)
                            } else {
                                Color.White.copy(alpha = overlayAlpha)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Overlay mẫu",
                        color = Color.Black,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultBorderSelector(
    borderColor: String?,
    borderThickness: Float,
    textColor: String?,
    onBorderColorChanged: (String?) -> Unit,
    onBorderThicknessChanged: (Float) -> Unit
) {
    val context = LocalContext.current
    
    var showColorPicker by remember { mutableStateOf(false) }
    var tempColor by remember(borderColor) {
        mutableStateOf(borderColor?.let { Color(android.graphics.Color.parseColor(it)) })
    }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Border Color picker
        Column {
            Text(
                text = "Màu viền chữ:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            tempColor ?: Color.Transparent,
                            shape = CircleShape
                        )
                        .border(
                            2.dp,
                            MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                        .clickable { showColorPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (tempColor == null) {
                        Text("?", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                
                Text(
                    text = if (tempColor != null) "Màu tùy chỉnh" else "Không có viền",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Button(
                    onClick = {
                        val hex = tempColor?.let { "#${String.format("%08X", it.toArgb())}" }
                        onBorderColorChanged(hex)
                    },
                    enabled = tempColor?.let { "#${String.format("%08X", it.toArgb())}" } != borderColor
                ) {
                    Text("Xác nhận")
                }
            }
            
            if (showColorPicker) {
                AdvancedColorPicker(
                    selectedColor = tempColor,
                    onColorSelected = { color ->
                        tempColor = color
                    },
                    showPredefinedColors = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = { showColorPicker = false }) {
                        Text("Đóng")
                    }
                }
            }
        }
        
        // Border Thickness slider
        Column {
            Text(
                text = "Độ dày viền: ${String.format("%.1f", borderThickness)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = borderThickness,
                onValueChange = onBorderThicknessChanged,
                valueRange = 0.0f..20.0f,
                steps = 40
            )
        }
        
        // Preview
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Xem trước viền chữ:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                val density = androidx.compose.ui.platform.LocalDensity.current
                val textStyle = MaterialTheme.typography.bodyLarge
                val textSizePx = with(density) { textStyle.fontSize.toPx() }
                
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)) {
                    val native = drawContext.canvas.nativeCanvas
                    // center coordinates
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    
                    // Android Paints
                    val textColorInt = textColor?.let { android.graphics.Color.parseColor(it) } ?: android.graphics.Color.WHITE
                    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = textSizePx
                        color = textColorInt
                        style = android.graphics.Paint.Style.FILL
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    
                    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = textSizePx
                        color = tempColor?.toArgb() ?: android.graphics.Color.TRANSPARENT
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = borderThickness
                        strokeJoin = android.graphics.Paint.Join.ROUND
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    
                    // Compute baseline so text is vertically centered
                    val fm = fillPaint.fontMetrics
                    val textHeight = fm.descent - fm.ascent
                    val baseline = cy + textHeight / 2f - fm.descent
                    
                    // Draw stroke / outline
                    if (tempColor != null && borderThickness > 0f) {
                        native.drawText("Văn bản mẫu", cx, baseline, strokePaint)
                    }
                    
                    // Draw fill text on top
                    native.drawText("Văn bản mẫu", cx, baseline, fillPaint)
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