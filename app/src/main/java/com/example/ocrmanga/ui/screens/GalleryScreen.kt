
package com.example.ocrmanga.ui.screens
// Kiểm tra có bản sao lưu nào trên Google Drive không
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.content.Context
import android.net.Uri
import com.example.ocrmanga.utils.AppLogger as Log
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ocrmanga.viewmodels.GalleryViewModel
import android.provider.MediaStore
import androidx.compose.ui.layout.ContentScale
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Palette
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
// ...existing code...
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import com.example.ocrmanga.ui.components.*
import kotlinx.coroutines.launch

// Google Drive API imports
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.model.File as GDriveFile
import java.io.InputStream
import java.io.OutputStream
import java.io.FileInputStream
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToViewer: (List<String>) -> Unit,
    onNavigateToRoom: (Long) -> Unit,
    onNavigateToApiKeyManagement: () -> Unit,
    onNavigateToThemeSettings: () -> Unit,
    viewModel: GalleryViewModel = viewModel()
) {
    // Khi quay lại gallery, luôn xóa dữ liệu session (selectedImages)
    LaunchedEffect(Unit) {
        viewModel.clearSelectedImages()
        viewModel.loadSavedRooms()
    }

    val uiState by viewModel.uiState.collectAsState()
    val TAG = "GalleryScreen"
    var showDeleteDialog by remember { mutableStateOf<Long?>(null) }
    var showCreateMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Google Sign-In state
    var googleAccount by remember { mutableStateOf<GoogleSignInAccount?>(null) }

    // Progress tracking for backup/restore
    var isBackupInProgress by remember { mutableStateOf(false) }
    var isRestoreInProgress by remember { mutableStateOf(false) }
    var backupProgress by remember { mutableStateOf(0f) }
    var restoreProgress by remember { mutableStateOf(0f) }

    // Timer effect for backup progress - no longer needed as we use real progress
    // LaunchedEffect removed since we now get real progress from backup operation

    // Timer effect for restore progress - no longer needed as we use real progress  
    // LaunchedEffect removed since we now get real progress from restore operation

    // On first launch, check if already signed in
    LaunchedEffect(Unit) {
        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        if (lastAccount != null) {
            googleAccount = lastAccount
        }
    }
    var hasBackup by remember { mutableStateOf<Boolean?>(null) }
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.file"))
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }
    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.result
            googleAccount = account
            Toast.makeText(context, "Đăng nhập Google Drive thành công: ${'$'}{account.email}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Đăng nhập Google Drive thất bại: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Google Sign-In failed", e)
        }
    }

    // Launcher chọn file để upload lên Drive
    val uploadFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && googleAccount != null) {
            coroutineScope.launch {
                try {
                    // Tạo credential từ account
                    val credential = GoogleAccountCredential.usingOAuth2(
                        context,
                        listOf(DriveScopes.DRIVE_FILE)
                    )
                    credential.selectedAccount = googleAccount!!.account
                    val driveService = Drive.Builder(
                        NetHttpTransport(),
                        GsonFactory.getDefaultInstance(),
                        credential
                    ).setApplicationName("OCR Manga").build()

                    // Lấy tên file
                    val fileName = getFileNameFromUri(context, uri) ?: "uploaded_file.webp"
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val fileMetadata = GDriveFile()
                        fileMetadata.name = fileName
                        val mediaType = if (fileName.endsWith(".webp", true)) "image/webp" else "image/jpeg"
                        val mediaContent = com.google.api.client.http.InputStreamContent(
                            mediaType, inputStream
                        )
                        val file = driveService.files().create(fileMetadata, mediaContent)
                            .setFields("id, name")
                            .execute()
                        Toast.makeText(context, "Đã upload lên Drive: ${'$'}{file.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Không đọc được file để upload", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Lỗi upload Drive: ${'$'}{e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else if (uri != null) {
            Toast.makeText(context, "Bạn cần đăng nhập Google Drive trước", Toast.LENGTH_SHORT).show()
        }
    }

    // Hàm tiện ích: lấy tên file không có phần mở rộng
    fun getFileNameWithoutExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // Comparator natural order mạnh hơn, bỏ phần mở rộng file
    fun naturalOrderComparator(a: String, b: String): Int {
        val aName = getFileNameWithoutExtension(a)
        val bName = getFileNameWithoutExtension(b)
        val regex = "\\d+|\\D+".toRegex()
        val aParts = regex.findAll(aName).map { it.value }.toList()
        val bParts = regex.findAll(bName).map { it.value }.toList()
        val len = minOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val x = aParts[i]
            val y = bParts[i]
            val xNum = x.toLongOrNull()
            val yNum = y.toLongOrNull()
            if (xNum != null && yNum != null) {
                if (xNum != yNum) return (xNum - yNum).toInt()
            } else {
                val cmp = x.compareTo(y)
                if (cmp != 0) return cmp
            }
        }
        return aParts.size - bParts.size
    }

    // Launcher để chọn ảnh từ file (OpenMultipleDocuments cho Android 13+)
    val multipleFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                // Sắp xếp theo tên file (natural order, bỏ phần mở rộng)
                val sortedUris = uris.sortedWith { u1, u2 ->
                    val n1 = getFileNameFromUri(context, u1) ?: ""
                    val n2 = getFileNameFromUri(context, u2) ?: ""
                    naturalOrderComparator(n1, n2)
                }
                viewModel.updateSelectedImages(sortedUris)
                Toast.makeText(
                    context,
                    "Đã chọn ${sortedUris.size} ảnh từ thư mục",
                    Toast.LENGTH_SHORT
                ).show()
                onNavigateToViewer(sortedUris.map { it.toString() })
            }
        }
    )
    // Launcher để chọn ảnh từ thư viện (PickMultipleVisualMedia cho Android thấp hơn)
    val multiplePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                // Sắp xếp theo tên file (natural order, bỏ phần mở rộng)
                val sortedUris = uris.sortedWith { u1, u2 ->
                    val n1 = getFileNameFromUri(context, u1) ?: ""
                    val n2 = getFileNameFromUri(context, u2) ?: ""
                    naturalOrderComparator(n1, n2)
                }
                viewModel.updateSelectedImages(sortedUris)
                Toast.makeText(
                    context,
                    "Đã chọn ${sortedUris.size} ảnh từ thư viện",
                    Toast.LENGTH_SHORT
                ).show()
                onNavigateToViewer(sortedUris.map { it.toString() })
            }
        }
    )

    Scaffold(
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var searchQuery by remember { mutableStateOf("") }
                var filterType by remember { mutableStateOf(0) } // 0: Mới nhất, 1: Cũ nhất
                val filterOptions = listOf("Mới nhất", "Cũ nhất")

                // Thanh tìm kiếm + Avatar Google (nếu đã đăng nhập)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Tìm kiếm", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Avatar Google hoặc avatar mặc định, dùng Button Material3, chiều cao bằng search
                    var showDriveMenu by remember { mutableStateOf(false) }
                    val buttonSize = 56.dp
    // Kiểm tra có backup khi đăng nhập Google
    LaunchedEffect(googleAccount) {
        if (googleAccount != null) {
            hasBackup = hasBackupOnDrive(context, googleAccount!!)
        } else {
            hasBackup = null
        }
    }

    if (googleAccount != null) {
                        Box {
                            IconButton(
                                onClick = { showDriveMenu = true },
                                modifier = Modifier
                                    .size(buttonSize)
                                    .aspectRatio(1f)
                            ) {
                                val photoUrl = googleAccount?.photoUrl?.toString()
                                if (photoUrl != null) {
                                    AsyncImage(
                                        model = photoUrl,
                                        contentDescription = "Avatar Google",
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(Color.LightGray, shape = CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = googleAccount?.displayName?.firstOrNull()?.toString() ?: "A",
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = showDriveMenu,
                                onDismissRequest = { showDriveMenu = false },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                DropdownMenuItem(
                                    text = { 
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Sao lưu")
                                            if (isBackupInProgress) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "${(backupProgress * 100).toInt()}%",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                CircularProgressIndicator(
                                                    progress = backupProgress,
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        showDriveMenu = false
                                        if (googleAccount != null && !isBackupInProgress) {
                                            isBackupInProgress = true
                                            backupProgress = 0f
                                            coroutineScope.launch {
                                                val backupResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    com.example.ocrmanga.backup.BackupManager(context, googleAccount!!).backupAppData { progress ->
                                                        // Cập nhật trực tiếp vì Compose state tự động handle UI thread
                                                        backupProgress = progress
                                                    }
                                                }
                                                isBackupInProgress = false
                                                backupProgress = 0f
                                                if (backupResult) {
                                                    Toast.makeText(context, "Sao lưu thành công lên Google Drive", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "Sao lưu thất bại!", Toast.LENGTH_LONG).show()
                                                }
                                                // Cập nhật lại trạng thái backup sau khi sao lưu
                                                hasBackup = hasBackupOnDrive(context, googleAccount!!)
                                            }
                                        } else if (isBackupInProgress) {
                                            Toast.makeText(context, "Đang thực hiện sao lưu, vui lòng đợi...", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Bạn cần đăng nhập Google Drive trước", Toast.LENGTH_SHORT).show()
                        }
                                    }
                                )
                                if (hasBackup == true) {
                                    DropdownMenuItem(
                                        text = { 
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Khôi phục")
                                                if (isRestoreInProgress) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "${(restoreProgress * 100).toInt()}%",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    CircularProgressIndicator(
                                                        progress = restoreProgress,
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            showDriveMenu = false
                                            if (googleAccount != null && !isRestoreInProgress) {
                                                isRestoreInProgress = true
                                                restoreProgress = 0f
                                                coroutineScope.launch {
                                                    val restoreResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        com.example.ocrmanga.backup.RestoreManager(context, googleAccount!!).restoreAppData { progress ->
                                                            // Cập nhật trực tiếp vì Compose state tự động handle UI thread
                                                            restoreProgress = progress
                                                        }
                                                    }
                                                    isRestoreInProgress = false
                                                    restoreProgress = 0f
                                                    if (restoreResult) {
                                                        Toast.makeText(context, "Khôi phục dữ liệu thành công!", Toast.LENGTH_LONG).show()
                                                        viewModel.loadSavedRooms() // reload lại dữ liệu phòng

                                                    } else {
                                                        Toast.makeText(context, "Khôi phục dữ liệu thất bại!", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            } else if (isRestoreInProgress) {
                                                Toast.makeText(context, "Đang thực hiện khôi phục, vui lòng đợi...", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Bạn cần đăng nhập Google Drive trước", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Đăng xuất") },
                                    onClick = {
                                        googleSignInClient.signOut()
                                        googleAccount = null
                                        Toast.makeText(context, "Đã đăng xuất Google Drive", Toast.LENGTH_SHORT).show()
                                        showDriveMenu = false
                                    }
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) },
                            modifier = Modifier
                                .size(buttonSize)
                                .aspectRatio(1f)
                                .background(
                                    color = Color(0xFF4285F4), // Google blue
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Avatar mặc định Google",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                // Bộ lọc phòng
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sắp xếp:", modifier = Modifier.padding(end = 8.dp))
                    filterOptions.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = filterType == idx,
                            onClick = { filterType = idx },
                            label = { Text(label) },
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }

                val filteredRooms = remember(uiState.savedRooms, searchQuery, filterType) {
                    val filtered =
                        if (searchQuery.isBlank()) uiState.savedRooms else uiState.savedRooms.filter {
                            it.second.contains(searchQuery, ignoreCase = true)
                        }
                    when (filterType) {
                        0 -> filtered // Mới nhất
                        1 -> filtered.reversed() // Cũ nhất
                        else -> filtered
                    }
                }

                if (filteredRooms.isNotEmpty()) {
                    Text(
                        text = "Danh sách đã lưu",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredRooms) { (roomId, title, coverUri) ->
                            Card(
                                modifier = Modifier
                                    .aspectRatio(0.7f)
                                    .padding(6.dp),
                                shape = RoundedCornerShape(10.dp), // giảm bo góc cho vuông hơn
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F5F1)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            viewModel.clearSelectedImages()
                                            onNavigateToRoom(roomId) }
                                ) {
                                    AsyncImage(
                                        model = coverUri,
                                        contentDescription = "Cover image",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(0.dp)
                                            .weight(1f),
                                        contentScale = ContentScale.Crop
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFEDE7DF), RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = title,
                                            color = Color(0xFF2D2D2D),
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f).padding(end = 4.dp),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(
                                                    color = Color(0xFFF3ECE3), // màu nhạt hơn màu bottom card
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .clickable { showDeleteDialog = roomId },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Xóa phòng",
                                                tint = Color.Red,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Đã chuyển icon Drive/avatar lên hàng search, bỏ nút + upload ở đây
            // Đã bỏ hiển thị text tên tài khoản nếu đã đăng nhập
            // Nút thêm (giữ nguyên nếu có)
            // ...existing code...
            // Nút thêm và nút settings ở góc phải dưới
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Nút thêm
                Box {
                    IconButton(
                        onClick = { showCreateMenu = true },
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.medium
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Tạo mới",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showCreateMenu,
                        onDismissRequest = { showCreateMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Chọn ảnh từ thư viện") },
                            onClick = {
                                multiplePhotoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                                showCreateMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Chọn ảnh từ file") },
                            onClick = {
                                multipleFilePickerLauncher.launch(arrayOf("image/*"))
                                showCreateMenu = false
                            }
                        )
                    }
                }
                // Combined settings button with dropdown menu
                Box {
                    var showSettingsMenu by remember { mutableStateOf(false) }
                    
                    ModernIconButton(
                        onClick = { showSettingsMenu = true },
                        icon = Icons.Default.Settings,
                        contentDescription = "Cài đặt",
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                    
                    DropdownMenu(
                        expanded = showSettingsMenu,
                        onDismissRequest = { showSettingsMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { 
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Palette,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text("Cài đặt giao diện")
                                }
                            },
                            onClick = {
                                onNavigateToThemeSettings()
                                showSettingsMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { 
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text("Quản lý API Key")
                                }
                            },
                            onClick = {
                                onNavigateToApiKeyManagement()
                                showSettingsMenu = false
                            }
                        )
                    }
                }
            }
        }

        showDeleteDialog?.let { roomId ->
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text("Xác nhận xóa") },
                text = { Text("Bạn có chắc chắn muốn xóa phòng này không? Thao tác này không thể hoàn tác.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteRoom(roomId)
                        showDeleteDialog = null
                    }) { Text("Xóa", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) { Text("Hủy") }
                }
            )
        }
    }
}
// Hàm tiện ích để lấy tên tệp từ URI
fun getFileNameFromUri(context: Context, uri: Uri): String? {
    val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            return cursor.getString(columnIndex)
        }
    }
    return null
}


private suspend fun hasBackupOnDrive(context: Context, googleAccount: GoogleSignInAccount): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            ).apply { selectedAccount = googleAccount.account }
            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("OCR Manga").build()
            val result = driveService.files().list()
                .setQ("mimeType='application/zip' and name contains 'ocrmanga_backup_'")
                .setFields("files(id)")
                .execute()
            result.files != null && result.files.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}