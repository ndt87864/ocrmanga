package com.example.ocrmanga.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ocrmanga.services.OverlayTranslationService
import com.example.ocrmanga.services.ScreenCaptureService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTranslationScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var isScreenTranslationActive by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(checkOverlayPermission(context)) }
    
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                startScreenCapture(context, result.resultCode, data)
                isScreenTranslationActive = true
            }
        } else {
            Toast.makeText(context, "Cần cấp quyền screen capture để sử dụng tính năng này", Toast.LENGTH_LONG).show()
        }
    }
    
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        hasOverlayPermission = checkOverlayPermission(context)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen Translation") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Dịch Màn Hình Trực Tiếp",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Dịch văn bản trên màn hình theo thời gian thực. " +
                                "Ứng dụng sẽ chụp màn hình và hiển thị bản dịch dưới dạng overlay.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isScreenTranslationActive) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isScreenTranslationActive) "ĐANG HOẠT ĐỘNG" else "KHÔNG HOẠT ĐỘNG",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isScreenTranslationActive) 
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        text = if (isScreenTranslationActive) 
                            "Screen translation đang chạy" 
                        else "Nhấn nút bắt đầu để kích hoạt",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isScreenTranslationActive) 
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Permission Status
            if (!hasOverlayPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Quyền Overlay Cần Thiết",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        
                        Text(
                            text = "Ứng dụng cần quyền hiển thị overlay để hiển thị bản dịch trên màn hình.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = {
                                requestOverlayPermission(context, overlayPermissionLauncher)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cấp Quyền Overlay")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Control Buttons
            if (hasOverlayPermission) {
                if (!isScreenTranslationActive) {
                    Button(
                        onClick = {
                            requestScreenCapture(context, mediaProjectionLauncher)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Bắt Đầu Screen Translation", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(
                        onClick = {
                            stopScreenTranslation(context)
                            isScreenTranslationActive = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Dừng Screen Translation", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            
            // Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Hướng Dẫn Sử Dụng",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val instructions = listOf(
                        "1. Cấp quyền Overlay cho ứng dụng",
                        "2. Nhấn 'Bắt Đầu Screen Translation'",
                        "3. Cho phép ứng dụng capture màn hình",
                        "4. Văn bản dịch sẽ hiển thị overlay trên màn hình",
                        "5. Nhấn 'Dừng' để tắt tính năng"
                    )
                    
                    instructions.forEach { instruction ->
                        Text(
                            text = instruction,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

private fun requestOverlayPermission(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        launcher.launch(intent)
    }
}

private fun requestScreenCapture(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val intent = mediaProjectionManager.createScreenCaptureIntent()
    launcher.launch(intent)
}

private fun startScreenCapture(context: Context, resultCode: Int, data: Intent) {
    // Start overlay service first
    val overlayIntent = Intent(context, OverlayTranslationService::class.java).apply {
        action = OverlayTranslationService.ACTION_SHOW_OVERLAY
    }
    context.startService(overlayIntent)
    
    // Then start screen capture service
    val captureIntent = Intent(context, ScreenCaptureService::class.java).apply {
        action = ScreenCaptureService.ACTION_START_CAPTURE
        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
        putExtra(ScreenCaptureService.EXTRA_DATA, data)
    }
    context.startForegroundService(captureIntent)
}

private fun stopScreenTranslation(context: Context) {
    // Stop screen capture service
    val captureIntent = Intent(context, ScreenCaptureService::class.java).apply {
        action = ScreenCaptureService.ACTION_STOP_CAPTURE
    }
    context.startService(captureIntent)
    
    // Stop overlay service
    val overlayIntent = Intent(context, OverlayTranslationService::class.java).apply {
        action = OverlayTranslationService.ACTION_HIDE_OVERLAY
    }
    context.startService(overlayIntent)
}