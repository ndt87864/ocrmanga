package com.example.ocrmanga.ui.screens.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ocrmanga.data.models.TranslationStatus

/**
 * Composable overlay hiển thị trạng thái dịch phủ lên ảnh đang được dịch
 * Màu nền bán trong suốt 40%, hiển thị các trạng thái: quét, dịch, phân phối, hoàn tất
 */
@Composable
fun TranslationOverlay(
    status: TranslationStatus,
    modifier: Modifier = Modifier
) {
    // Chỉ hiển thị khi không phải IDLE
    AnimatedVisibility(
        visible = status != TranslationStatus.IDLE,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)), // 40% opacity
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                // Hiển thị loading indicator nếu chưa hoàn tất
                if (status != TranslationStatus.COMPLETED) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Text hiển thị trạng thái
                Text(
                    text = getStatusText(status),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                // Mô tả chi tiết
                Text(
                    text = getStatusDescription(status),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * Lấy text hiển thị cho từng trạng thái
 */
private fun getStatusText(status: TranslationStatus): String {
    return when (status) {
        TranslationStatus.SCANNING -> "Đang quét ảnh..."
        TranslationStatus.TRANSLATING -> "Đang dịch..."
        TranslationStatus.DISTRIBUTING -> "Đang xử lý..."
        TranslationStatus.COMPLETED -> "✓ Hoàn tất!"
        TranslationStatus.IDLE -> ""
    }
}

/**
 * Lấy mô tả chi tiết cho từng trạng thái
 */
private fun getStatusDescription(status: TranslationStatus): String {
    return when (status) {
        TranslationStatus.SCANNING -> "Nhận diện văn bản trong ảnh"
        TranslationStatus.TRANSLATING -> "Chuyển đổi ngôn ngữ"
        TranslationStatus.DISTRIBUTING -> "Phân phối bản dịch về tọa độ"
        TranslationStatus.COMPLETED -> "Bản dịch đã sẵn sàng"
        TranslationStatus.IDLE -> ""
    }
}
