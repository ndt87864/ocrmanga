package com.example.ocrmanga.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.ocrmanga.R

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import java.io.OutputStream

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

import java.io.File
import java.io.FileOutputStream
import android.media.MediaScannerConnection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            saveImageToGallery(context, R.drawable.bill, "manga_ocr_donation_bill_${System.currentTimeMillis()}.jpg")
        } else {
            Toast.makeText(context, "Cần quyền bộ nhớ để tải ảnh", Toast.LENGTH_SHORT).show()
        }
    }

    // Pulse animation for heart icon
    val infiniteTransition = rememberInfiniteTransition(label = "heartPulse")
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartScale"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Ủng hộ",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Donation image card
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.bill),
                    contentDescription = "Thông tin ủng hộ",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Heart icon with gradient
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .graphicsLayer(
                        scaleX = heartScale,
                        scaleY = heartScale
                    )
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Thank you message
            Text(
                text = "Cảm ơn bạn đã sử dụng ứng dụng!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Sự ủng hộ của bạn giúp chúng tôi duy trì và phát triển ứng dụng tốt hơn.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Download button - styled like gallery FilledTonalButton
            Button(
                onClick = {
                    val finalFileName = "manga_ocr_donation_bill_${System.currentTimeMillis()}.jpg"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveImageToGallery(context, R.drawable.bill, finalFileName)
                    } else {
                        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
                        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                            saveImageToGallery(context, R.drawable.bill, finalFileName)
                        } else {
                            requestPermissionLauncher.launch(permission)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 8.dp
                )
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Tải ảnh bill ủng hộ",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

private fun saveImageToGallery(context: android.content.Context, resourceId: Int, fileName: String) {
    try {
        val bitmap = BitmapFactory.decodeResource(context.resources, resourceId)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, contentValues)

            uri?.let {
                resolver.openOutputStream(it)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
                Toast.makeText(context, "Đã lưu ảnh vào thư mục Tải về!", Toast.LENGTH_SHORT).show()
            } ?: throw Exception("Failed to create new MediaStore record.")
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }

            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)

            Toast.makeText(context, "Đã lưu ảnh vào thư mục Tải về: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Lỗi khi lưu ảnh: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
