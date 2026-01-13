package com.example.ocrmanga.ui.screens.view

import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex

@Composable
fun MagnifierPopup(
    magnifierPosition: Offset,
    imageWidth: Float,
    imageHeight: Float,
    imageUri: android.net.Uri? = null,
    modifier: Modifier = Modifier,
    sourcePosition: Offset = magnifierPosition // Tọa độ thực tế trên ảnh cần zoom
) {
    if (imageWidth <= 0f || imageHeight <= 0f) return

    val density = androidx.compose.ui.platform.LocalDensity.current
    
    val magnifierSizeDp = 120.dp
    val magnifierSizePx = with(density) { magnifierSizeDp.toPx() }
    val magnifierHalfPx = magnifierSizePx / 2
    
    // Tự động điều chỉnh vị trí popup: nếu chạm ở nửa phía trên (top) thì hiện ở dưới để không bị che
    // Tăng threshold lên 400dp để đảm bảo bao phủ vùng đủ rộng ở phía trên màn hình
    val thresholdTop = with(density) { 400.dp.toPx() }
    val currentOffsetY = with(density) { 
        if (magnifierPosition.y < thresholdTop) 180.dp.toPx() else -180.dp.toPx() 
    }
    
    // Tính toán vị trí hiển thị popup (Pixels)
    val displayX = magnifierPosition.x.coerceIn(magnifierHalfPx, imageWidth - magnifierHalfPx)
    val displayY = (magnifierPosition.y + currentOffsetY).coerceIn(magnifierHalfPx, imageHeight - magnifierHalfPx)
    
    // Chuyển đổi sang DP để dùng offset
    val displayX_dp = with(density) { (displayX - magnifierHalfPx).toDp() }
    val displayY_dp = with(density) { (displayY - magnifierHalfPx).toDp() }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Đường nối và tâm điểm tại ngón tay
        Canvas(modifier = Modifier.fillMaxSize().zIndex(1f)) {
            drawLine(
                color = Color.Red.copy(alpha = 0.6f),
                start = Offset(displayX, displayY),
                end = magnifierPosition,
                strokeWidth = 3f
            )
            drawCircle(Color.Red, radius = 8f, center = magnifierPosition)
        }
        
        // 2. MAGNIFIER SQUARE
        Box(
            modifier = Modifier
                .offset(x = displayX_dp, y = displayY_dp)
                .size(magnifierSizeDp)
                .zIndex(100f)
                .shadow(12.dp, RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(2.dp, Color.Red, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.TopStart // Cố định gốc tọa độ
        ) {
            if (imageUri != null) {
                val zoomFactor = 2.5f
                val baseScale = magnifierSizePx / imageWidth

                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(imageUri)
                        .size(coil.size.Size.ORIGINAL)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    alignment = Alignment.TopStart,
                    modifier = Modifier
                        .fillMaxWidth() // Chỉ chiếm width kính lúp
                        .wrapContentHeight(unbounded = true, align = Alignment.Top)
                        .graphicsLayer {
                            scaleX = zoomFactor
                            scaleY = zoomFactor
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            
                            // Dịch chuyển chính xác: Đưa sourcePosition về tâm kính lúp
                            translationX = magnifierHalfPx - (sourcePosition.x * baseScale * zoomFactor)
                            translationY = magnifierHalfPx - (sourcePosition.y * baseScale * zoomFactor)
                        },
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )
            }

            // Tâm ngắm Crosshair (Luôn ở chính giữa khung)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = size.width / 2
                val s = 20f
                drawLine(Color.Red, Offset(center - s, center), Offset(center + s, center), 2f)
                drawLine(Color.Red, Offset(center, center - s), Offset(center, center + s), 2f)
            }
        }
    }
}
