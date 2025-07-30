package com.example.ocrmanga.ui.previews

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ocrmanga.ui.theme.*
import com.example.ocrmanga.ui.components.*

@Composable
fun ThemeShowcase(
    themeVariant: ThemeVariant,
    isDark: Boolean,
    title: String
) {
    OCRMangaThemePreview(themeVariant = themeVariant, darkTheme = isDark) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                ModernCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "OCR Manga App",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ứng dụng đọc truyện với công nghệ AI OCR hiện đại",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Đọc ngay")
                        }
                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Khám phá")
                        }
                    }
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ModernIconButton(
                        onClick = { },
                        icon = Icons.Default.Home,
                        contentDescription = "Trang chủ",
                        modifier = Modifier.weight(1f)
                    )
                    ModernIconButton(
                        onClick = { },
                        icon = Icons.Default.Favorite,
                        contentDescription = "Yêu thích",
                        containerColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    ModernIconButton(
                        onClick = { },
                        icon = Icons.Default.Settings,
                        contentDescription = "Cài đặt",
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Material 3 Design",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Giao diện hiện đại tuân thủ chuẩn Material Design 3",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Purple Light Theme", showBackground = true)
@Composable
fun PreviewPurpleLight() {
    ThemeShowcase(
        themeVariant = ThemeVariant.DEFAULT_PURPLE,
        isDark = false,
        title = "Chủ đề Tím - Sáng"
    )
}

@Preview(name = "Purple Dark Theme", showBackground = true)
@Composable
fun PreviewPurpleDark() {
    ThemeShowcase(
        themeVariant = ThemeVariant.DEFAULT_PURPLE,
        isDark = true,
        title = "Chủ đề Tím - Tối"
    )
}

@Preview(name = "Blue Light Theme", showBackground = true)
@Composable
fun PreviewBlueLight() {
    ThemeShowcase(
        themeVariant = ThemeVariant.BLUE,
        isDark = false,
        title = "Chủ đề Xanh Dương - Sáng"
    )
}

@Preview(name = "Blue Dark Theme", showBackground = true)
@Composable
fun PreviewBlueDark() {
    ThemeShowcase(
        themeVariant = ThemeVariant.BLUE,
        isDark = true,
        title = "Chủ đề Xanh Dương - Tối"
    )
}

@Preview(name = "Green Light Theme", showBackground = true)
@Composable
fun PreviewGreenLight() {
    ThemeShowcase(
        themeVariant = ThemeVariant.GREEN,
        isDark = false,
        title = "Chủ đề Xanh Lá - Sáng"
    )
}

@Preview(name = "Green Dark Theme", showBackground = true)
@Composable
fun PreviewGreenDark() {
    ThemeShowcase(
        themeVariant = ThemeVariant.GREEN,
        isDark = true,
        title = "Chủ đề Xanh Lá - Tối"
    )
}

@Preview(name = "Orange Light Theme", showBackground = true)
@Composable
fun PreviewOrangeLight() {
    ThemeShowcase(
        themeVariant = ThemeVariant.ORANGE,
        isDark = false,
        title = "Chủ đề Cam - Sáng"
    )
}

@Preview(name = "Orange Dark Theme", showBackground = true)
@Composable
fun PreviewOrangeDark() {
    ThemeShowcase(
        themeVariant = ThemeVariant.ORANGE,
        isDark = true,
        title = "Chủ đề Cam - Tối"
    )
}

@Preview(name = "Red Light Theme", showBackground = true)
@Composable
fun PreviewRedLight() {
    ThemeShowcase(
        themeVariant = ThemeVariant.RED,
        isDark = false,
        title = "Chủ đề Đỏ - Sáng"
    )
}

@Preview(name = "Red Dark Theme", showBackground = true)
@Composable
fun PreviewRedDark() {
    ThemeShowcase(
        themeVariant = ThemeVariant.RED,
        isDark = true,
        title = "Chủ đề Đỏ - Tối"
    )
}