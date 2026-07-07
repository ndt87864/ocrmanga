package com.example.ocrmanga.ui.screens

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.ocrmanga.ui.components.LoadingOverlay
import com.example.ocrmanga.viewmodels.GalleryViewModel
import com.example.ocrmanga.ui.components.TutorialStep
import com.example.ocrmanga.ui.components.tutorialTag
import com.example.ocrmanga.ui.screens.view.ViewerPreferences
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as GDriveFile
import kotlinx.coroutines.launch
import java.io.InputStream

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
    
    // Tutorial State
    var tutorialDone by remember { mutableStateOf(true) }
    val targetPositions = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    
    LaunchedEffect(Unit) {
        ViewerPreferences.isTutorialDoneFlow(context, "gallery").collect { tutorialDone = it }
    }

    val tutorialSteps = listOf(
        TutorialStep("Chào mừng!", "Đây là nơi quản lý tất cả bộ truyện manga của bạn. Hãy cùng khám phá các tính năng chính nhé!"),
        TutorialStep("Thêm truyện mới", "Nhấn vào nút '+' này để thêm truyện từ thư viện ảnh hoặc thư mục trong máy.", "gallery_add"),
        TutorialStep("Sao lưu Drive", "Bạn có thể sao lưu dữ liệu lên Google Drive để không bị mất khi đổi máy.", "gallery_drive"),
        TutorialStep("Tìm kiếm", "Dễ dàng tìm thấy bộ truyện yêu thích bằng cách nhập tên tại đây.", "gallery_search")
    )
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
                        fileMetadata.setName(fileName)
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

    // Scroll to top when filter or search changes
    LaunchedEffect(filterType, searchQuery) {
        if (filteredRooms.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    // ============ MAIN UI ============
    Scaffold(
        floatingActionButton = {
            GalleryCreateFab(
                show = filteredRooms.isNotEmpty() || searchQuery.isNotBlank(),
                expanded = showCreateMenu,
                onExpandedChange = { showCreateMenu = it },
                onPickImages = {
                    multiplePhotoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPickFiles = { multipleFilePickerLauncher.launch(arrayOf("image/*")) },
                onTagPosition = { tag, rect -> targetPositions[tag] = rect }
            )
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
                    modifier = Modifier.weight(1f)
                        .tutorialTag("gallery_search") { tag, rect -> targetPositions[tag] = rect },
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
                                .tutorialTag("gallery_drive") { tag, rect -> targetPositions[tag] = rect }
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

            GalleryFilterSection(
                show = uiState.savedRooms.isNotEmpty(),
                roomCount = filteredRooms.size,
                filterOptions = filterOptions,
                filterType = filterType,
                onFilterChange = { filterType = it }
            )

            GalleryContentArea(
                isLoading = uiState.isLoading,
                hasRooms = uiState.savedRooms.isNotEmpty(),
                filteredRooms = filteredRooms,
                searchQuery = searchQuery,
                gridState = gridState,
                onAddFromGallery = {
                    multiplePhotoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onAddFromFiles = { multipleFilePickerLauncher.launch(arrayOf("image/*")) },
                onOpenRoom = { roomId ->
                    viewModel.clearSelectedImages()
                    onNavigateToRoom(roomId)
                },
                onDeleteRoom = { showDeleteDialog = it }
            )
        }
    }

    DeleteRoomDialog(
        roomId = showDeleteDialog,
        onDismiss = { showDeleteDialog = null },
        onConfirm = { roomId ->
            viewModel.deleteRoom(roomId)
            showDeleteDialog = null
        }
    )

    GalleryTutorialOverlay(
        tutorialDone = tutorialDone,
        tutorialSteps = tutorialSteps,
        targetPositions = targetPositions,
        onComplete = {
            coroutineScope.launch {
                ViewerPreferences.setTutorialDone(context, "gallery")
            }
        }
    )
}

// ==========================================
// ROOM CARD COMPONENT
// ==========================================
