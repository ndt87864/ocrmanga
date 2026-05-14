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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.ocrmanga.ui.components.LoadingOverlay
import com.example.ocrmanga.viewmodels.GalleryViewModel
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onNavigateToViewer: (List<String>) -> Unit,
    onNavigateToRoom: (Long) -> Unit,
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

    // LoadingOverlay hiển thị khi đang backup/restore Google Drive
    val isDriveOperationInProgress = isBackupInProgress || isRestoreInProgress
    val driveProgressText = when {
        isBackupInProgress -> "Đang sao lưu lên Google Drive..."
        isRestoreInProgress -> "Đang khôi phục từ Google Drive..."
        else -> ""
    }
    val driveProgressSubText = when {
        isBackupInProgress -> "${(backupProgress * 100).toInt()}% hoàn thành"
        isRestoreInProgress -> "${(restoreProgress * 100).toInt()}% hoàn thành"
        else -> ""
    }
    LoadingOverlay(
        isLoading = isDriveOperationInProgress,
        progress = driveProgressText,
        subText = driveProgressSubText
    )

    // Launcher chọn file để upload lên Drive
    val uploadFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && googleAccount != null) {
            coroutineScope.launch {
                try {
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

    // Search & Filter state
    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf(0) } // 0: Mới nhất, 1: Cũ nhất
    val filterOptions = listOf("Mới nhất", "Cũ nhất")

    // Check backup availability on Google login
    LaunchedEffect(googleAccount) {
        if (googleAccount != null) {
            hasBackup = hasBackupOnDrive(context, googleAccount!!)
        } else {
            hasBackup = null
        }
    }

    // Drive menu state
    var showDriveMenu by remember { mutableStateOf(false) }

    // Filtered rooms
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

    val gridState = rememberLazyGridState()

    // ============ MAIN UI ============
    Scaffold(
        floatingActionButton = {
            if (filteredRooms.isNotEmpty() || searchQuery.isNotBlank()) {
                Box {
                    FloatingActionButton(
                        onClick = { showCreateMenu = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(8.dp, RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Tạo mới",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showCreateMenu,
                        onDismissRequest = { showCreateMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text("Chọn ảnh từ thư viện")
                                }
                            },
                            onClick = {
                                multiplePhotoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                                showCreateMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text("Chọn ảnh từ file")
                                }
                            },
                            onClick = {
                                multipleFilePickerLauncher.launch(arrayOf("image/*"))
                                showCreateMenu = false
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ===== SEARCH SECTION =====
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search bar
                var isSearchFocused by remember { mutableStateOf(false) }
                val searchBorderColor by animateColorAsState(
                    targetValue = if (isSearchFocused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    label = "searchBorder"
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            "Tìm kiếm truyện...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Tìm kiếm",
                            tint = if (isSearchFocused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Xóa tìm kiếm",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    interactionSource = remember { MutableInteractionSource() }.also { src ->
                        LaunchedEffect(src) {
                            src.interactions.collect { interaction ->
                                isSearchFocused = interaction is androidx.compose.foundation.interaction.FocusInteraction.Focus
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Google Avatar / Drive button
                if (googleAccount != null) {
                    Box {
                        IconButton(
                            onClick = { showDriveMenu = true },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                )
                        ) {
                            val photoUrl = googleAccount?.photoUrl?.toString()
                            if (photoUrl != null) {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(photoUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Avatar Google",
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                    loading = {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                    }
                                )
                            } else {
                                Text(
                                    text = googleAccount?.displayName?.firstOrNull()?.toString() ?: "A",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }

                        // Backup in progress indicator dot
                        if (isBackupInProgress || isRestoreInProgress) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                            )
                        }

                        DropdownMenu(
                            expanded = showDriveMenu,
                            onDismissRequest = { showDriveMenu = false }
                        ) {
                            // User info header
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = googleAccount?.displayName ?: "Người dùng",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = googleAccount?.email ?: "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {},
                                enabled = false
                            )
                            HorizontalDivider()

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CloudUpload,
                                            contentDescription = "Sao lưu lên Drive",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text("Sao lưu")
                                        if (isBackupInProgress) {
                                            Spacer(Modifier.width(8.dp))
                                            CircularProgressIndicator(
                                                progress = backupProgress,
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "${(backupProgress * 100).toInt()}%",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = "Khôi phục từ Drive",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.tertiary
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text("Khôi phục")
                                            if (isRestoreInProgress) {
                                                Spacer(Modifier.width(8.dp))
                                                CircularProgressIndicator(
                                                    progress = restoreProgress,
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.tertiary
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = "${(restoreProgress * 100).toInt()}%",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.tertiary
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
                                                        restoreProgress = progress
                                                    }
                                                }
                                                isRestoreInProgress = false
                                                restoreProgress = 0f
                                                if (restoreResult) {
                                                    Toast.makeText(context, "Khôi phục dữ liệu thành công!", Toast.LENGTH_LONG).show()
                                                    viewModel.loadSavedRooms()
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
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ExitToApp,
                                            contentDescription = "Đăng xuất",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text("Đăng xuất", color = MaterialTheme.colorScheme.error)
                                    }
                                },
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
                    FilledIconButton(
                        onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) },
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF4285F4)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Đăng nhập Google Drive",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // ===== FILTER SECTION =====
            if (uiState.savedRooms.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Room count
                    Text(
                        text = "${filteredRooms.size} truyện",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Filter chips
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        filterOptions.forEachIndexed { idx, label ->
                            val isSelected = filterType == idx
                            val chipAlpha by animateFloatAsState(
                                targetValue = if (isSelected) 1f else 0.6f,
                                label = "chipAlpha"
                            )
                            FilterChip(
                                selected = isSelected,
                                onClick = { filterType = idx },
                                label = {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.alpha(chipAlpha),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        else Color.Transparent,
                                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                                    enabled = true,
                                    selected = isSelected
                                )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ===== CONTENT AREA =====
            if (uiState.savedRooms.isEmpty()) {
                // Empty state - no rooms at all
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyGalleryState(
                        onAddFromGallery = {
                            multiplePhotoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onAddFromFiles = {
                            multipleFilePickerLauncher.launch(arrayOf("image/*"))
                        }
                    )
                }
            } else if (filteredRooms.isEmpty() && searchQuery.isNotBlank()) {
                // Empty search results
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Không tìm thấy truyện nào",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Thử tìm kiếm với từ khóa khác",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                // Gallery grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.weight(1f)
                    .fillMaxWidth()
                    .animateContentSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 88.dp // space for FAB
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    state = gridState
                ) {
                    items(
                        items = filteredRooms,
                        key = { (roomId, _, _) -> roomId }
                    ) { (roomId, title, coverUri) ->
                        RoomCard(
                            roomId = roomId,
                            title = title,
                            coverUri = coverUri,
                            onClick = {
                                viewModel.clearSelectedImages()
                                onNavigateToRoom(roomId)
                            },
                            onDeleteClick = { showDeleteDialog = roomId },
                            modifier = Modifier.animateItemPlacement()
                        )
                    }
                }
            }
        }
    }

    // ===== DELETE DIALOG =====
    showDeleteDialog?.let { roomId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Xóa truyện",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            },
            text = {
                Text(
                    text = "Bạn có chắc chắn muốn xóa truyện này không?\nThao tác này không thể hoàn tác.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRoom(roomId)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Xóa")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = null },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Hủy")
                }
            }
        )
    }
}

// ==========================================
// ROOM CARD COMPONENT
// ==========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomCard(
    roomId: Long,
    title: String?,
    coverUri: android.net.Uri,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardElevation by animateDpAsState(
        targetValue = if (isPressed) 12.dp else 4.dp,
        label = "cardElevation"
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "cardScale"
    )

    var showDeleteOverlay by remember { mutableStateOf(false) }

    val displayTitle = remember(title, roomId) {
        if (!title.isNullOrBlank() && !title.matches(Regex("truyện \\d+"))) title else "truyện $roomId"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                shadowElevation = cardElevation.value
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (showDeleteOverlay) {
                        showDeleteOverlay = false
                    } else {
                        onClick()
                    }
                },
                onLongClick = { showDeleteOverlay = true }
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = cardElevation
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
            // Cover image with shimmer placeholder
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUri)
                    .crossfade(300)
                    .build(),
                contentDescription = displayTitle,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {
                    ShimmerPlaceholder(
                        modifier = Modifier.fillMaxSize()
                    )
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            )

            // Bottom gradient overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.75f)
                            )
                        )
                    )
            )

            // Room title
            Text(
                text = displayTitle,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .padding(end = 38.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // ===== DELETE OVERLAY on Long-Press =====
            if (showDeleteOverlay) {
                // Semi-transparent dark overlay covering the card
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showDeleteOverlay = false
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Red trash can icon
                        IconButton(
                            onClick = {
                                showDeleteOverlay = false
                                onDeleteClick()
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .shadow(4.dp, CircleShape)
                                .background(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = CircleShape
                                )
                                .clip(CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Xóa truyện",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Xóa truyện",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// SHIMMER PLACEHOLDER
// ==========================================
@Composable
private fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}

// ==========================================
// EMPTY GALLERY STATE
// ==========================================
@Composable
private fun EmptyGalleryState(
    onAddFromGallery: () -> Unit,
    onAddFromFiles: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with gradient background
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Chưa có truyện nào",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Thêm ảnh để bắt đầu dịch truyện tranh",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = onAddFromGallery,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Từ thư viện")
            }

            FilledTonalButton(
                onClick = onAddFromFiles,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Từ file")
            }
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
