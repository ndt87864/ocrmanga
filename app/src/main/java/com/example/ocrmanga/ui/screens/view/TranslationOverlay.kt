package com.example.ocrmanga.ui.screens.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * Overlay hiển thị tiến trình xóa text đè lên ảnh đang được xử lý.
 *
 * @param progressMessage  Thông báo tiến trình hiện tại (rỗng = ẩn overlay)
 * @param modifier         Modifier cho overlay (nên là Modifier.matchParentSize())
 */
@Composable
fun TextRemovalOverlay(
    progressMessage: String,
    modifier: Modifier = Modifier
) {
    val visible = progressMessage.isNotEmpty()
    val isCompleted = progressMessage == "COMPLETED"

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)), // 40% opacity giống hệt TranslationOverlay
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                // Hiển thị loading indicator nếu chưa hoàn tất
                if (!isCompleted) {
                    CircularProgressIndicator(
                        color = Color.White, // Màu trắng giống TranslationOverlay
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Text hiển thị trạng thái
                Text(
                    text = if (isCompleted) "✓ Hoàn tất!" else "Đang xóa text...",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                // Mô tả chi tiết
                Text(
                    text = if (isCompleted) "Xóa text thành công" else progressMessage,
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

