package com.example.ocrmanga.viewmodels

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.repositories.TranslationRepository
import com.example.ocrmanga.ui.screens.view.computeDefaultTextColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Build
import android.os.Environment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
            // Trả về số lượng ảnh đã thay đổi trong room
            fun getNumChangedImages(roomId: Long): Int {
                return try {
                    databaseHelper.getNumChangedImages(roomId)
                } catch (e: Exception) {
                    0
                }
            }
        // Chuyển đổi trạng thái pendingDelete cho block của một ảnh
    fun togglePendingDelete(uri: Uri, blockId: Int, setPending: Boolean) {
        _uiState.update { state ->
            val oldPair = state.translatedTexts[uri] ?: ("" to emptyList<TextBlockInfo>())
            val blocks = oldPair.second.map {
                if (it.bounds.hashCode() == blockId) it.copy(pendingDelete = setPending) else it
            }
            state.copy(
                translatedTexts = state.translatedTexts.toMutableMap().apply {
                    put(uri, oldPair.first to blocks)
                }
            )
        }
    }
    // Dịch lại 1 ảnh (re-translate single image)
    // IMPORTANT: This will DELETE all existing translations for this image before creating new ones
    fun retranslateImage(uri: Uri, mode: TranslationMode) {
        viewModelScope.launch {
            // Early validation: if user requests Gemini or Mistral but there are no API keys, notify and skip
            if (mode == TranslationMode.GEMINI && !hasGeminiApiKeys()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Không có API key Gemini. Vui lòng thêm ít nhất một API key Gemini trong cài đặt để dùng tính năng dịch Gemini.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            if (mode == TranslationMode.MISTRAL && !hasMistralApiKeys()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Không có API key Mistral. Vui lòng thêm ít nhất một API key Mistral trong cài đặt để dùng tính năng dịch Mistral.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            
            // Đánh dấu bản dịch cũ là pending_delete trước khi dịch mới
            val imageId = uriToImageId[uri]
            if (imageId != null) {
                try {
                    databaseHelper.markTranslationsAsPendingDelete(imageId)
                    Log.i(TAG, "[RETRANSLATE] Marked old translations as pending_delete for imageId=$imageId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to mark pending delete for imageId=$imageId", e)
                }
            }
            
            // Cập nhật trạng thái dịch - bắt đầu quét ảnh
            updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.SCANNING)
            _uiState.update { it.copy(translatedStatus = it.translatedStatus + (uri to false)) }

            if (mode == TranslationMode.OFF) {
                // User chọn OFF → XÓA HOÀN TOÀN tất cả translations của ảnh này khỏi DB
                if (imageId != null) {
                    try {
                        // Xóa tất cả translations cho image này
                        val db = databaseHelper.writableDatabase
                        val deletedCount = db.delete("translations", "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
                        Log.i(TAG, "[RETRANSLATE-OFF] Deleted $deletedCount translations from DB for imageId=$imageId")
                        
                        // Xóa luôn các image_blocks
                        try {
                            databaseHelper.deleteBlocksForImage(imageId)
                            Log.i(TAG, "[RETRANSLATE-OFF] Deleted image_blocks for imageId=$imageId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete image_blocks for imageId=$imageId", e)
                        }
                        
                        // Đánh dấu ảnh là chưa dịch trong TABLE_IMAGES
                        val imageValues = android.content.ContentValues().apply {
                            put(DatabaseHelper.COLUMN_IS_TRANSLATED, 0)
                            put(DatabaseHelper.COLUMN_ORIGINAL_TEXT, "") // Clear original text too
                        }
                        db.update(DatabaseHelper.TABLE_IMAGES, imageValues, "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
                        Log.i(TAG, "[RETRANSLATE-OFF] Marked image as untranslated in TABLE_IMAGES for imageId=$imageId")
                        
                        // Clear is_changed flag to prevent this from counting as changed
                        databaseHelper.clearChangedFlagForImage(imageId)
                        
                        // Track this deletion for save count
                        deletedTranslationUris.add(uri)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete translations for imageId=$imageId", e)
                    }
                }
                
                _uiState.update { state ->
                    state.copy(
                        translatedTexts = state.translatedTexts + (uri to ("" to emptyList())),
                        translatedStatus = state.translatedStatus + (uri to false), // Mark as not translated
                        sourceLanguages = state.sourceLanguages - uri,
                        // Tăng translationVersion để force UI xóa blocks
                        translationVersion = state.translationVersion + 1
                    )
                }
                // Xóa trạng thái dịch cho ảnh này
                clearTranslationStatus(uri)
                return@launch
            }

            // Nếu không phải OFF, tiến hành dịch bình thường
            if (mode != TranslationMode.OFF) {
                try {
                    // Callback để cập nhật trạng thái từ repository
                    val statusCallback: (com.example.ocrmanga.data.models.TranslationStatus) -> Unit = { status ->
                        updateTranslationStatus(uri, status)
                    }
                    
                    val result = translationRepository.translateImage(uri, mode, statusCallback)
                    
                    // Ensure blocks have overlay/text colors set similarly to queued translations
                    val (originalText, blocks) = result
                    val fixedBlocks = blocks.map { block ->
                        val baseOverlay = block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()
                        val textColor = block.customTextColor ?: computeDefaultTextColor(baseOverlay, block.averageBackgroundColor)
                        block.copy(
                            customOverlayColor = baseOverlay,
                            customTextColor = textColor,
                            // Set applyMerge = true khi retranslate để áp dụng logic chống chồng lấn
                            applyMerge = true
                        )
                    }
                    
                    _uiState.update {
                        it.copy(
                            translatedTexts = it.translatedTexts + (uri to (originalText to fixedBlocks)),
                            translatedStatus = it.translatedStatus + (uri to true),
                            translationEnabled = true, // Bật hiển thị dịch cho UI nếu cần
                            // Tăng translationVersion để force UI update blocks mới
                            translationVersion = it.translationVersion + 1
                        )
                    }
                    
                    // Cập nhật trạng thái: hoàn tất
                    updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.COMPLETED)
                    // Delay ngắn để hiển thị trạng thái hoàn tất trước khi xóa
                    delay(1000)
                    clearTranslationStatus(uri)
                    
                    // Mark as dirty and set DB change flag if this image belongs to a saved room
                    dirtyUris.add(uri)
                    val rid = _uiState.value.roomId
                    Log.i(TAG, "[RETRANSLATE] Checking auto-save: uri=$uri imageId=$imageId roomId=$rid")
                    if (rid != null && imageId != null) {
                        try {
                            val numChanged = databaseHelper.markImageChanged(imageId, rid)
                            Log.i(TAG, "[RETRANSLATE] After markImageChanged: numChanged=$numChanged for imageId=$imageId")
                            if (numChanged >= 5) {
                                Log.i(TAG, "[RETRANSLATE] Threshold reached! Calling maybeAutoSaveChangedImages")
                                maybeAutoSaveChangedImages(rid)
                            }
                        } catch (e: Exception) { 
                            Log.w(TAG, "Failed to markImageChanged for imageId=$imageId", e) 
                        }
                    } else {
                        Log.w(TAG, "[RETRANSLATE] Cannot mark image changed: roomId=$rid imageId=$imageId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[RETRANSLATE] Translation failed for uri=$uri", e)
                    // Xóa trạng thái dịch khi lỗi
                    clearTranslationStatus(uri)
                    // Nếu dịch thất bại, hủy trạng thái pending_delete để giữ bản dịch cũ
                    if (imageId != null) {
                        try {
                            databaseHelper.clearPendingDeleteStatus(imageId)
                            Log.i(TAG, "[RETRANSLATE-FAIL] Cleared pending_delete status for imageId=$imageId")
                        } catch (ex: Exception) {
                            Log.w(TAG, "Failed to clear pending delete after translation failure for imageId=$imageId", ex)
                        }
                    }
                }
            }
        }
    }

    // Thêm hàm mới để cập nhật translatedTexts cho một uri cụ thể (sửa lỗi unresolved reference)
    fun updateTranslatedBlocks(uri: Uri, blocks: List<TextBlockInfo>) {
        // Get current blocks to check if there's any actual change
        val current = _uiState.value.translatedTexts[uri] ?: ("" to emptyList())
        val currentBlocks = current.second
        
        // Chỉ log và update các block thực sự thay đổi
        val updatedBlocks = blocks.mapIndexed { idx, block ->
            val oldBlock = currentBlocks.getOrNull(idx)
            val rot = block.rotation ?: 0f
            val blockWithRotation = if (block.rotation == null) block.copy(rotation = 0f) else block
            val finalBlock = blockWithRotation.copy(applyMerge = false)
            if (oldBlock == null ||
                oldBlock.text != finalBlock.text ||
                oldBlock.bounds != finalBlock.bounds ||
                oldBlock.rotation != finalBlock.rotation ||
                oldBlock.fontSize != finalBlock.fontSize) {
                Log.i(TAG, "[UPDATE] Block text='${finalBlock.text}' rotation=$rot for uri=$uri")
            }
            finalBlock
        }
        
        // Check if blocks actually changed (size or content)
        val hasChanges = currentBlocks.size != updatedBlocks.size ||
            currentBlocks.zip(updatedBlocks).any { (old, new) ->
                old.text != new.text ||
                old.bounds != new.bounds ||
                old.rotation != new.rotation ||
                old.fontSize != new.fontSize ||
                old.lineSpacing != new.lineSpacing ||
                old.overlayInset != new.overlayInset ||
                old.overlayAlpha != new.overlayAlpha ||
                old.textBoldness != new.textBoldness ||
                old.overlaySaturation != new.overlaySaturation ||
                old.textSaturation != new.textSaturation ||
                old.customOverlayColor != new.customOverlayColor ||
                old.customTextColor != new.customTextColor ||
                old.customBorderColor != new.customBorderColor ||
                old.borderThickness != new.borderThickness ||
                old.borderAlpha != new.borderAlpha ||
                old.customShadowColor != new.customShadowColor ||
                old.shadowAlpha != new.shadowAlpha ||
                old.shadowRadius != new.shadowRadius ||
                old.fontFamily != new.fontFamily ||
                old.shapeType != new.shapeType
            }
        
        // Only mark as dirty and changed if there are actual changes
        if (!hasChanges) {
            return
        }
        
        val newPair = current.first to updatedBlocks
        _uiState.update {
            it.copy(
                translatedTexts = it.translatedTexts + (uri to newPair),
                translatedStatus = it.translatedStatus + (uri to true)
            )
        }
        // Mark this uri as dirty (edited) so later saveRoom can update only changed images
        dirtyUris.add(uri)
        // Also mark DB change flag if this URI is associated with a saved image
        val rid = _uiState.value.roomId
        val imageId = uriToImageId[uri]
        if (rid != null && imageId != null) {
            try {
                val numChanged = databaseHelper.markImageChanged(imageId, rid)
                Log.i(TAG, "Updated translated blocks for image: $uri, total changed images: $numChanged")
                if (numChanged >= 5) {
                    Log.i(TAG, "Triggering auto-save after editing image: $uri, changed images: $numChanged")
                    maybeAutoSaveChangedImages(rid)
                }
            } catch (e: Exception) { Log.w(TAG, "Failed to markImageChanged for imageId=$imageId", e) }
        }
        //log.i(TAG, "Đã cập nhật blocks bản dịch cho ảnh $uri với ${updatedBlocks.size} blocks")
        updatedBlocks.forEachIndexed { idx, block ->
            //log.i(TAG, "[UPDATE] Block[$idx] rotation=${block.rotation} text='${block.text}' uri=$uri")
        }
    }

    private val translationRepository = TranslationRepository(application)
    // prevent parallel auto-save runs
    private val autoSaveInProgress = AtomicBoolean(false)
    // prevent auto-save when manual save is in progress
    private val isManualSaving = AtomicBoolean(false)
    // Track current auto-save job so we can cancel it when manual save starts
    private var autoSaveJob: Job? = null
    private val databaseHelper = DatabaseHelper(application)
    // Track active jobs (translation / timer / io) so we can force-cancel them when clearing session
    private val activeJobs = ConcurrentLinkedQueue<Job>()
    // Keep the last loaded/saved room id so clear can delete it even if uiState.roomId was cleared
    private var lastLoadedRoomId: Long? = null
    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()
    private val _allRoomIds = MutableStateFlow<List<Long>>(emptyList())
    val allRoomIds: StateFlow<List<Long>> = _allRoomIds.asStateFlow()
    private val TAG = "ViewerViewModel"
    private val galleryViewModel = GalleryViewModel(application)
    private val newImageUris = mutableListOf<Uri>()
    private val translationQueue = ConcurrentLinkedQueue<Uri>()
    // Track which image URIs were edited since last save
    private val dirtyUris = mutableSetOf<Uri>()
    // Map from URI (string) to image_id in DB for current loaded room
    val uriToImageId = mutableMapOf<Uri, Long>()
    // Track images that had translations deleted (OFF mode)
    private val deletedTranslationUris = mutableSetOf<Uri>()
    // Track images removed from room
    private val removedImageIds = mutableSetOf<Long>()
    private var translationJob: Job? = null
    private var timerJob: Job? = null

    /**
     * Cập nhật trạng thái dịch cho một ảnh cụ thể
     */
    fun updateTranslationStatus(uri: Uri, status: com.example.ocrmanga.data.models.TranslationStatus) {
        _uiState.update { state ->
            state.copy(translatingImages = state.translatingImages + (uri to status))
        }
    }

    /**
     * Xóa trạng thái dịch của một ảnh (khi dịch xong hoặc lỗi)
     */
    fun clearTranslationStatus(uri: Uri) {
        _uiState.update { state ->
            state.copy(translatingImages = state.translatingImages - uri)
        }
    }

    /**
     * Xóa tất cả trạng thái dịch
     */
    fun clearAllTranslationStatus() {
        _uiState.update { state ->
            state.copy(translatingImages = emptyMap())
        }
    }

    /**
     * Register a Job created by this ViewModel so it can be cancelled when clearing session.
     * Call registerJob(job) right after launching a new Job in this ViewModel.
     */
    private fun registerJob(job: Job) {
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
    }

    // If a room accumulates >=5 changed images, automatically persist their pending edits.
    private fun maybeAutoSaveChangedImages(roomId: Long) {
        autoSaveJob?.cancel() // Cancel any existing auto-save
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            // prevent concurrent auto-save runs
            if (!autoSaveInProgress.compareAndSet(false, true)) {
                Log.i(TAG, "Auto-save already in progress, skipping")
                return@launch
            }
            try {
                val changedIds = databaseHelper.getChangedImageIdsForRoom(roomId)
                Log.i(TAG, "Auto-save check: ${changedIds.size} images marked as changed for room $roomId")
                if (changedIds.size >= 5) {
                    val mapping = mutableMapOf<Long, Pair<String, List<TextBlockInfo>>>()
                    val currentTranslated = _uiState.value.translatedTexts
                    currentTranslated.forEach { (uri, pair) ->
                        val imgId = uriToImageId[uri]
                        if (imgId != null && changedIds.contains(imgId)) {
                            mapping[imgId] = pair
                        }
                    }
                    Log.i(TAG, "Auto-save: Will save ${mapping.size} images (threshold: 5, changed: ${changedIds.size})")
                    if (mapping.isNotEmpty()) {
                        // Pass clearChangedFlag=true to clear is_changed flag after auto-save.
                        // If user wants to modify further, they can:
                        // - Edit manually → adds to dirtyUris
                        // - Retranslate → calls markImageChanged again
                        val ok = databaseHelper.applyPendingChangesForRoom(roomId, mapping, clearChangedFlag = true)
                        if (ok) {
                            // Clear dirtyUris for images that were auto-saved
                            // NOTE: Do NOT clear deletedTranslationUris here because applyPendingChangesForRoom
                            // only saves NEW translations, it does NOT handle deletion of old translations.
                            // Deletion is handled separately in saveCurrentRoom's selective save (case 3).
                            val currentTranslated = _uiState.value.translatedTexts
                            currentTranslated.forEach { (uri, _) ->
                                val imgId = uriToImageId[uri]
                                if (imgId != null && imgId in changedIds) {
                                    dirtyUris.remove(uri)
                                }
                            }
                            Log.i(TAG, "Auto-saved ${mapping.size} changed images for room $roomId (threshold reached), cleared from dirtyUris")
                        } else {
                            Log.w(TAG, "Auto-save failed for room $roomId mappingSize=${mapping.size}")
                        }
                    }
                } else {
                    Log.i(TAG, "Auto-save: Not enough changed images (${changedIds.size}/5)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "maybeAutoSaveChangedImages failed for room $roomId", e)
            } finally {
                autoSaveInProgress.set(false)
            }
        }
    }
    companion object {
        const val BATCH_SIZE = 10 // Số ảnh tải mỗi lần
    }

    init {
        loadAllRoomIds()
    }

    // Bắt đầu bộ đếm thời gian dịch
    private fun startTranslationTimer(uri: Uri) {
        timerJob?.cancel()
        val imageIndex = uiState.value.imageUris.indexOf(uri) + 1 // 1-based index
        _uiState.update { 
            it.copy(
                translationTimer = 0,
                currentTranslatingImage = uri,
                currentTranslatingImageIndex = imageIndex
            ) 
        }
        timerJob = viewModelScope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive && uiState.value.currentTranslatingImage == uri) {
                delay(1000) // Đợi 1 giây
                _uiState.update { 
                    it.copy(translationTimer = it.translationTimer + 1) 
                }
            }
        }
        // Register timer job so it will be cancelled on clear
        timerJob?.let { registerJob(it) }
        //log.i(TAG, "Bắt đầu đếm thời gian dịch cho ảnh $imageIndex: $uri")
    }

    // Dừng và reset bộ đếm thời gian dịch
    private fun stopTranslationTimer() {
        timerJob?.cancel()
        timerJob = null
        _uiState.update { 
            it.copy(
                translationTimer = 0,
                currentTranslatingImage = null,
                currentTranslatingImageIndex = 0
            ) 
        }
        //log.i(TAG, "Dừng bộ đếm thời gian dịch")
    }

    private fun loadAllRoomIds() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = databaseHelper.readableDatabase
                val cursor = db.rawQuery("SELECT ${DatabaseHelper.COLUMN_ROOM_ID} FROM ${DatabaseHelper.TABLE_ROOMS}", null)
                val roomIds = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    roomIds.add(cursor.getLong(0))
                }
                cursor.close()
                _allRoomIds.value = roomIds.sorted()
                //log.i(TAG, "Đã tải ${roomIds.size} ID phòng")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tải room IDs", e)
            }
        }
    }

    fun setImageUris(uris: List<Uri>, isNew: Boolean = false) {
        // Basic UI state reset for the provided URIs
        _uiState.update {
            it.copy(
                imageUris = uris,
                translatedTexts = emptyMap(),
                sourceLanguages = emptyMap(),
                translatedStatus = uris.associateWith { false }.toMutableMap(),
                roomId = null,
                translationEnabled = false,
                translationMode = TranslationMode.OFF,
                isTranslating = false,
                translationProgress = 0,
                totalImagesToTranslate = 0,
                remainingImages = emptyList(),
                // Reset translation version so UI clears blocks
                translationVersion = it.translationVersion + 1
            )
        }

        // Clear in-memory session lists and jobs for a truly new session
        newImageUris.clear()
        if (isNew) {
            // Cancel any ongoing translation work and timers
            try { translationJob?.cancel() } catch (e: Throwable) { /* ignore */ }
            try { stopTranslationTimer() } catch (e: Throwable) { /* ignore */ }

            translationQueue.clear()
            dirtyUris.clear()
            uriToImageId.clear()
            newImageUris.addAll(uris)
            // this is a new session, forget last loaded room id so we don't fall back
            lastLoadedRoomId = null
            Log.i(TAG, "setImageUris(isNew=true): cleared translationQueue, dirtyUris, uriToImageId and lastLoadedRoomId")
        }
        //log.i(TAG, "Đã đặt ${uris.size} URI ảnh, isNew: $isNew")
    }

    fun addNewImageUris(uris: List<Uri>) {
        val currentUris = uiState.value.imageUris.toMutableList()
        // Lọc uris mới để loại bỏ những cái đã có trong currentUris
        val newUris = uris.filter { newUri -> currentUris.none { it.toString() == newUri.toString() } }
        currentUris.addAll(newUris)
        _uiState.update {
            it.copy(
                imageUris = currentUris,
                translatedTexts = it.translatedTexts.filterKeys { uri -> uri in currentUris },
                translatedStatus = it.translatedStatus + newUris.associateWith { false }
            )
        }
        newImageUris.clear()
        newImageUris.addAll(newUris)
        //log.i(TAG, "Đã thêm ${newUris.size} URI ảnh mới vào cuối")

        // Only auto-translate if both translation is enabled AND auto-translate setting is ON
        if (uiState.value.translationEnabled && 
            uiState.value.translationMode != TranslationMode.OFF && 
            uiState.value.autoTranslateEnabled) {
            enqueueTranslation(newUris)
        }
    }

    fun addNewImageUrisAtStart(uris: List<Uri>) {
        val currentUris = uiState.value.imageUris.toMutableList()
        // Lọc uris mới để loại bỏ những cái đã có trong currentUris
        val newUris = uris.filter { newUri -> currentUris.none { it.toString() == newUri.toString() } }
        currentUris.addAll(0, newUris)
        _uiState.update {
            it.copy(
                imageUris = currentUris,
                translatedTexts = it.translatedTexts.filterKeys { uri -> uri in currentUris },
                translatedStatus = it.translatedStatus + newUris.associateWith { false }
            )
        }
        newImageUris.clear()
        newImageUris.addAll(newUris)
        //log.i(TAG, "Đã thêm ${newUris.size} URI ảnh mới vào đầu")

        // Only auto-translate if both translation is enabled AND auto-translate setting is ON
        if (uiState.value.translationEnabled && 
            uiState.value.translationMode != TranslationMode.OFF && 
            uiState.value.autoTranslateEnabled) {
            enqueueTranslation(newUris)
        }
    }

    fun addNewImageUrisAtIndex(uris: List<Uri>, index: Int) {
        val currentUris = uiState.value.imageUris.toMutableList()
        val insertIndex = index.coerceIn(0, currentUris.size)
        // Lọc uris mới để loại bỏ những cái đã có trong currentUris
        val newUris = uris.filter { newUri -> currentUris.none { it.toString() == newUri.toString() } }
        currentUris.addAll(insertIndex, newUris)
        _uiState.update {
            it.copy(
                imageUris = currentUris,
                translatedTexts = it.translatedTexts.filterKeys { uri -> uri in currentUris },
                translatedStatus = it.translatedStatus + newUris.associateWith { false }
            )
        }
        newImageUris.clear()
        newImageUris.addAll(newUris)
        //log.i(TAG, "Đã thêm ${newUris.size} URI ảnh mới vào vị trí $insertIndex")

        // Only auto-translate if both translation is enabled AND auto-translate setting is ON
        if (uiState.value.translationEnabled && 
            uiState.value.translationMode != TranslationMode.OFF && 
            uiState.value.autoTranslateEnabled) {
            enqueueTranslation(newUris)
        }
    }

    fun loadRoom(roomId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Clear all is_changed flags for this room to start fresh
                databaseHelper.clearAllChangedFlagsForRoom(roomId)
                
                // Clear tracking variables when loading a room
                dirtyUris.clear()
                deletedTranslationUris.clear()
                removedImageIds.clear()
                newImageUris.clear()
                uriToImageId.clear()
                
                // Load auto-translate setting for this room
                val autoTranslate = databaseHelper.getAutoTranslateSetting(roomId)
                
                //log.i(TAG, "Đang tải phòng $roomId")
                val (allImages, _, translations) = databaseHelper.getMangaRoom(roomId)
                
                // Filter out duplicate URIs, keeping only the first occurrence
                val uniqueImages = mutableListOf<Uri>()
                val seenUris = mutableSetOf<String>()
                allImages.forEach { uri ->
                    val uriString = uri.toString()
                    if (!seenUris.contains(uriString)) {
                        uniqueImages.add(uri)
                        seenUris.add(uriString)
                    } else {
                        Log.w(TAG, "loadRoom: Skipping duplicate URI: $uriString")
                    }
                }
                
                if (uniqueImages.size < allImages.size) {
                    Log.i(TAG, "loadRoom: Filtered ${allImages.size - uniqueImages.size} duplicate images from room $roomId")
                }
                
                // Sort images by numeric order in filename (e.g., image_1, image_2, ..., image_10, image_11)
                // Extract number from filename like "image_10.jpg" -> 10
                fun extractImageNumber(uri: Uri): Int {
                    val filename = uri.lastPathSegment ?: return Int.MAX_VALUE
                    val match = """image_(\d+)""".toRegex().find(filename)
                    return match?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
                }
                
                val sortedImages = uniqueImages.sortedBy { extractImageNumber(it) }
                
                if (sortedImages != uniqueImages) {
                    Log.i(TAG, "loadRoom: Reordered images by numeric filename")
                }
                
                if (sortedImages != uniqueImages) {
                    Log.i(TAG, "loadRoom: Reordered images by numeric filename")
                }
                
                // ĐẢM BẢO: KHÔNG loại bỏ ảnh đầu (coverUri) khỏi danh sách ảnh phòng!
                // Nếu coverUri trùng với ảnh đầu, vẫn giữ nguyên trong danh sách hiển thị.
                val translatedStatus = mutableMapOf<Uri, Boolean>()
                val initialBatch = sortedImages.take(BATCH_SIZE)
                val remainingImages = sortedImages.drop(BATCH_SIZE)

                // Load initial batch
                val db = databaseHelper.readableDatabase
                val cursor = db.rawQuery(
                    """
                    SELECT ${DatabaseHelper.COLUMN_IMAGE_ID}, ${DatabaseHelper.COLUMN_IMAGE_URI}, ${DatabaseHelper.COLUMN_IS_TRANSLATED} 
                    FROM ${DatabaseHelper.TABLE_IMAGES} 
                    WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ? 
                    ORDER BY ${DatabaseHelper.COLUMN_DISPLAY_ORDER} 
                    LIMIT $BATCH_SIZE
                    """, arrayOf(roomId.toString())
                )

                while (cursor.moveToNext()) {
                    val imageId = cursor.getLong(0)
                    val uriStr = cursor.getString(1)
                    val isTranslated = cursor.getInt(2) == 1
                    val uri = Uri.parse(uriStr)
                    translatedStatus[uri] = isTranslated
                    // populate map for later selective save
                    uriToImageId[uri] = imageId
                    try {
                        uriToImageId[Uri.parse(uriStr)] = imageId
                    } catch (e: Exception) { /* ignore */ }
                    // also index by lastPathSegment / filename to help match content:// vs file://
                    try {
                        val last = Uri.parse(uriStr).lastPathSegment
                        if (!last.isNullOrBlank()) {
                            uriToImageId[Uri.fromParts("filename", last, null)] = imageId
                        }
                    } catch (e: Exception) { /* ignore */ }
                }
                cursor.close()

                // Tự động set rotation = 0f cho block chưa có rotation (phòng cũ)
                val fixedTranslations = translations.mapValues { (uri, pair) ->
                    val (originalText, blocks) = pair
                    val fixedBlocks = blocks.map { block ->
                        val withRotation = if (block.rotation == null) block.copy(rotation = 0f) else block
                        val baseOverlay = withRotation.customOverlayColor ?: 0xFFFFFFFF.toInt()
                        val textColor = withRotation.customTextColor ?: computeDefaultTextColor(baseOverlay, withRotation.averageBackgroundColor)
                        // QUAN TRỌNG: Set applyMerge = false khi load từ DB để không áp dụng logic chống chồng lấn
                        withRotation.copy(
                            customOverlayColor = baseOverlay, 
                            customTextColor = textColor,
                            applyMerge = false
                        )
                    }
                    // Log loaded shadow values for each block to verify persistence
                    fixedBlocks.forEachIndexed { idx, b ->
                        if (b.customShadowColor != null || b.shadowRadius > 0f || b.shadowAlpha != 1.0f) {
                            Log.i(TAG, "loadRoom: uri=$uri blockIndex=$idx shadowColor=${b.customShadowColor?.toString() ?: "null"} shadowAlpha=${b.shadowAlpha} shadowRadius=${b.shadowRadius}")
                        }
                    }
                    originalText to fixedBlocks
                }
                // Only include translations that actually exist in DB for the initial batch.
                // Do NOT insert empty translation entries for images that have no saved
                // translations; those images should simply be displayed without overlays.
                val translationsForBatch: Map<Uri, Pair<String, List<TextBlockInfo>>> =
                    fixedTranslations.filterKeys { uri -> uri in initialBatch }

                // Keep sourceLanguages only for those URIs that had translations loaded.
                val sourceLangsForBatch: Map<Uri, String> = translationsForBatch.keys.associateWith { "zh" }

                // Ensure translatedStatus contains an explicit value for each uri in the batch.
                // If DB indicated the image was translated (or we loaded a translation), honor that;
                // otherwise leave the image as not-translated (false) so UI will just show the image.
                val statusForBatch = initialBatch.associateWith { uri ->
                    translatedStatus[uri] ?: translationsForBatch.containsKey(uri)
                }

                _uiState.update {
                    it.copy(
                        imageUris = initialBatch, // Ảnh bìa vẫn nằm trong danh sách này
                        translatedTexts = it.translatedTexts + translationsForBatch,
                        translationEnabled = fixedTranslations.isNotEmpty(),
                        translationMode = if (fixedTranslations.isNotEmpty()) TranslationMode.OFFLINE else TranslationMode.OFF,
                        isTranslating = false,
                        roomId = roomId,
                        translatedStatus = it.translatedStatus + statusForBatch,
                        sourceLanguages = it.sourceLanguages + sourceLangsForBatch,
                        remainingImages = remainingImages,
                        // Tăng translationVersion để force UI update dragBlocksMap từ DB
                        translationVersion = it.translationVersion + 1,
                        autoTranslateEnabled = autoTranslate
                    )
                }
                // record last loaded room id so clear can delete files even if uiState changes later
                lastLoadedRoomId = roomId
                //log.i(TAG, "Đã tải batch đầu tiên của phòng $roomId với ${initialBatch.size} ảnh")
                // Log độ nghiêng (rotation) cho từng block bản dịch
                fixedTranslations.forEach { (uri, pair) ->
                    val blocks = pair.second
                    blocks.forEachIndexed { idx, block ->
                        //log.i(TAG, "[LOAD] Block[$idx] uri=$uri rotation=${block.rotation} text='${block.text}'")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tải phòng $roomId", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Tải phòng thất bại!", Toast.LENGTH_SHORT).show()
                }
                // On failure, ensure we don't keep a stale lastLoadedRoomId
                lastLoadedRoomId = null
            }
        }
    }

    fun loadMoreImages() {
        val remainingImages = uiState.value.remainingImages
        if (remainingImages.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            // Bắt đầu trạng thái loading
            _uiState.update { it.copy(isLoadingMoreImages = true) }
            
            val batch = remainingImages.take(BATCH_SIZE)
            val newRemaining = remainingImages.drop(BATCH_SIZE)
            val translatedStatus = mutableMapOf<Uri, Boolean>()
            val translations = mutableMapOf<Uri, Pair<String, List<TextBlockInfo>>>()

            try {
                val db = databaseHelper.readableDatabase
                batch.forEach { uri ->
                    val cursor = db.rawQuery(
                        """
                        SELECT ${DatabaseHelper.COLUMN_IS_TRANSLATED} 
                        FROM ${DatabaseHelper.TABLE_IMAGES} 
                        WHERE ${DatabaseHelper.COLUMN_IMAGE_URI} = ?
                        """, arrayOf(uri.toString())
                    )
                    if (cursor.moveToFirst()) {
                        translatedStatus[uri] = cursor.getInt(0) == 1
                    }
                    cursor.close()

                    // Get original_text from images table (once per image)
                    var originalTextForImage = ""
                    val originalTextCursor = db.rawQuery(
                        """
                        SELECT ${DatabaseHelper.COLUMN_ORIGINAL_TEXT}
                        FROM ${DatabaseHelper.TABLE_IMAGES}
                        WHERE ${DatabaseHelper.COLUMN_IMAGE_URI} = ?
                        """, arrayOf(uri.toString())
                    )
                    if (originalTextCursor.moveToFirst()) {
                        originalTextForImage = originalTextCursor.getString(0) ?: ""
                    }
                    originalTextCursor.close()

                    val textCursor = db.rawQuery(
                        """
                        SELECT translated_text, bounds_left, bounds_top, bounds_right, bounds_bottom, font_size
                        FROM translations 
                        WHERE (${DatabaseHelper.COLUMN_IMAGE_ID} IN (
                            SELECT ${DatabaseHelper.COLUMN_IMAGE_ID} FROM ${DatabaseHelper.TABLE_IMAGES} 
                            WHERE ${DatabaseHelper.COLUMN_IMAGE_URI} = ?
                        )) AND (pending_delete IS NULL OR pending_delete = 0)
                        """, arrayOf(uri.toString())
                    )
                    val textBlocks = mutableListOf<TextBlockInfo>()
                    while (textCursor.moveToNext()) {
                        val translatedText = textCursor.getString(0)
                        val bounds = android.graphics.Rect(
                            textCursor.getInt(1),
                            textCursor.getInt(2),
                            textCursor.getInt(3),
                            textCursor.getInt(4)
                        )
                        val fontSize = textCursor.getFloat(5)
                        // Try to read optional columns (average_background_color, custom_overlay_color, custom_text_color, overlay_alpha, etc.) if present
                        fun colInt(name: String): Int? {
                            return try {
                                val idx = textCursor.getColumnIndex(name)
                                if (idx >= 0 && !textCursor.isNull(idx)) textCursor.getInt(idx) else null
                            } catch (e: Exception) { null }
                        }
                        fun colFloat(name: String, default: Float): Float {
                            return try {
                                val idx = textCursor.getColumnIndex(name)
                                if (idx >= 0 && !textCursor.isNull(idx)) textCursor.getFloat(idx) else default
                            } catch (e: Exception) { default }
                        }

                        val avgBg = colInt("average_background_color")
                        val customOverlay = colInt("custom_overlay_color")
                        val customText = colInt("custom_text_color")
                        val overlayAlpha = colFloat("overlay_alpha", 1.0f)
                        val textBoldness = colFloat("text_boldness", 1.0f)
                        val overlaySat = colFloat("overlay_saturation", 1.0f)
                        val textSat = colFloat("text_saturation", 1.0f)

                        // Try to find image_id for this uri and then check image_blocks overrides for persistent styling
                        val baseOverlay = customOverlay ?: avgBg ?: 0xFFFFFFFF.toInt()
                        val textColorFallback = customText ?: computeDefaultTextColor(baseOverlay, avgBg)

                        // Resolve image_id for this uri (there should be only one)
                        var foundImageId: Long? = null
                        try {
                            val c2 = db.rawQuery("SELECT ${DatabaseHelper.COLUMN_IMAGE_ID} FROM ${DatabaseHelper.TABLE_IMAGES} WHERE ${DatabaseHelper.COLUMN_IMAGE_URI} = ? LIMIT 1", arrayOf(uri.toString()))
                            if (c2.moveToFirst()) {
                                foundImageId = c2.getLong(0)
                            }
                            c2.close()
                        } catch (e: Exception) { /* ignore */ }

                        var finalOverlay = customOverlay ?: avgBg
                        var finalTextColor = if (customText != null) customText else textColorFallback
                        var finalOverlayAlpha = overlayAlpha
                        var finalTextBold = textBoldness
                        var finalOverlaySat = overlaySat
                        var finalFontSize = fontSize
                        var finalRotation: Float? = null
                        var finalShapeType = 0
                        var finalBorderColor: Int? = null
                        var finalBorderThickness = 0f
                        var finalFontFamily: String? = null
                        // SHADOW: khai báo ngoài để dùng khi tạo TextBlockInfo
                        var finalShadowColor: Int? = null
                        var finalShadowAlpha: Float? = null
                        var finalShadowRadius: Float? = null

                        if (foundImageId != null) {
                            try {
                                val bw = bounds.right - bounds.left
                                val bh = bounds.bottom - bounds.top
                                val blockCursor = db.rawQuery(
                                    "SELECT * FROM ${DatabaseHelper.TABLE_IMAGE_BLOCKS} WHERE ${DatabaseHelper.COLUMN_BLOCK_IMAGE_ID} = ? AND ${DatabaseHelper.COLUMN_BLOCK_X} = ? AND ${DatabaseHelper.COLUMN_BLOCK_Y} = ? AND ${DatabaseHelper.COLUMN_BLOCK_WIDTH} = ? AND ${DatabaseHelper.COLUMN_BLOCK_HEIGHT} = ?",
                                    arrayOf(foundImageId.toString(), bounds.left.toString(), bounds.top.toString(), bw.toString(), bh.toString())
                                )
                                if (blockCursor.moveToFirst()) {
                                    // read overrides from image_blocks
                                    val overlayColorBlockIdx = blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_OVERLAY_COLOR)
                                    if (!blockCursor.isNull(overlayColorBlockIdx)) finalOverlay = blockCursor.getInt(overlayColorBlockIdx)
                                    try { finalOverlayAlpha = blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_OVERLAY_ALPHA)).toFloat() } catch (e: Exception) { /* ignore */ }
                                    try { finalOverlaySat = blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_OVERLAY_SATURATION)).toFloat() } catch (e: Exception) { /* ignore */ }
                                    val textColorIdx = blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_TEXT_COLOR)
                                    if (!blockCursor.isNull(textColorIdx)) {
                                        val col = blockCursor.getInt(textColorIdx)
                                        if (col != 0) finalTextColor = col
                                    }
                                    try { finalTextBold = blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_TEXT_BOLDNESS)).toFloat() } catch (e: Exception) { /* ignore */ }
                                    try { finalFontSize = blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_FONT_SIZE)).toFloat() } catch (e: Exception) { /* ignore */ }
                                    try { finalRotation = blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_ROTATION)).toFloat() } catch (e: Exception) { /* ignore */ }
                                    try { finalShapeType = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_OVERLAY_TYPE)) } catch (e: Exception) { /* ignore */ }
                                    val borderIdx = blockCursor.getColumnIndex(DatabaseHelper.COLUMN_BLOCK_BORDER_COLOR)
                                    if (borderIdx >= 0 && !blockCursor.isNull(borderIdx)) finalBorderColor = blockCursor.getInt(borderIdx)
                                    try { finalBorderThickness = blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_BORDER_THICKNESS)).toFloat() } catch (e: Exception) { /* ignore */ }
                                    finalFontFamily = try { blockCursor.getString(blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_FONT_FAMILY)) } catch (e: Exception) { null }
                           // SHADOW: lấy các thuộc tính shadow từ DB
                           try {
                               val idx = blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_SHADOW_COLOR)
                               if (!blockCursor.isNull(idx)) finalShadowColor = blockCursor.getInt(idx)
                           } catch (_: Exception) {}
                           try {
                               val idx = blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_SHADOW_ALPHA)
                               if (!blockCursor.isNull(idx)) finalShadowAlpha = blockCursor.getDouble(idx).toFloat()
                           } catch (_: Exception) {}
                           try {
                               val idx = blockCursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BLOCK_SHADOW_RADIUS)
                               if (!blockCursor.isNull(idx)) finalShadowRadius = blockCursor.getDouble(idx).toFloat()
                           } catch (_: Exception) {}
                                }
                                blockCursor.close()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error while querying image_blocks for image uri=$uri", e)
                            }
                        }

                        if (finalShadowColor != null || (finalShadowAlpha ?: 1.0f) != 1.0f || (finalShadowRadius ?: 0f) != 0f) {
                            Log.i("ViewerViewModel", "LẤY SHADOW: uri=$uri shadowColor=$finalShadowColor shadowAlpha=${finalShadowAlpha ?: 1.0f} shadowRadius=${finalShadowRadius ?: 0f}")
                        }
                        textBlocks.add(TextBlockInfo(
                            text = translatedText,
                            bounds = bounds,
                            fontSize = finalFontSize,
                            rotation = finalRotation,
                            originalImageWidth = null,
                            originalImageHeight = null,
                            originalText = originalTextForImage, // Set original text from image level
                            shapeType = finalShapeType,
                            backgroundType = com.example.ocrmanga.data.models.BackgroundType.WHITE,
                            averageBackgroundColor = finalOverlay,
                            originalTextColor = null,
                            customOverlayColor = finalOverlay,
                            customTextColor = finalTextColor,
                            overlayAlpha = finalOverlayAlpha,
                            textBoldness = finalTextBold,
                            overlaySaturation = finalOverlaySat,
                            textSaturation = textSat,
                            customBorderColor = finalBorderColor,
                            borderThickness = finalBorderThickness,
                            fontFamily = finalFontFamily ?: "mto_astro_city",
                            customShadowColor = finalShadowColor,
                            shadowAlpha = finalShadowAlpha ?: 1.0f,
                            shadowRadius = finalShadowRadius ?: 0f,
                            // QUAN TRỌNG: Set applyMerge = false khi load từ DB
                            applyMerge = false
                        ))
                    }
                    textCursor.close()
                    if (textBlocks.isNotEmpty()) {
                        // Use original text from image level (already fetched above)
                        // IMPORTANT: Ensure we only have ONE translation per image by checking if it already exists
                        if (!translations.containsKey(uri)) {
                            translations[uri] = originalTextForImage to textBlocks
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        imageUris = it.imageUris + batch,
                        translatedTexts = it.translatedTexts + translations,
                        translatedStatus = it.translatedStatus + translatedStatus,
                        remainingImages = newRemaining,
                        isLoadingMoreImages = false, // Kết thúc trạng thái loading
                        // Tăng translationVersion để force UI update blocks mới từ DB
                        translationVersion = it.translationVersion + 1
                    )
                }
                //log.i(TAG, "Đã tải thêm ${batch.size} ảnh, còn lại ${newRemaining.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tải thêm ảnh", e)
                // Tắt loading ngay cả khi có lỗi
                _uiState.update { it.copy(isLoadingMoreImages = false) }
            }
        }
    }

    fun setTranslationMode(mode: TranslationMode) {
        val currentMode = uiState.value.translationMode

        // Prevent switching to Gemini or Mistral if API keys are missing
        if (mode == TranslationMode.GEMINI && !hasGeminiApiKeys()) {
            viewModelScope.launch(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Không có API key Gemini. Vui lòng thêm ít nhất một API key Gemini trong cài đặt để dùng tính năng dịch Gemini.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (mode == TranslationMode.MISTRAL && !hasMistralApiKeys()) {
            viewModelScope.launch(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Không có API key Mistral. Vui lòng thêm ít nhất một API key Mistral trong cài đặt để dùng tính năng dịch Mistral.", Toast.LENGTH_LONG).show()
            }
            return
        }

        _uiState.update {
            it.copy(
                translationMode = mode,
                translationEnabled = mode != TranslationMode.OFF
            )
        }
        //log.i(TAG, "Chế độ dịch được đặt thành $mode")

        if (mode != TranslationMode.OFF) {
            // If switching between different translation modes (OFFLINE <-> ONLINE <-> GEMINI)
            // or turning on translation for the first time, retranslate all images
            val shouldRetranslate = currentMode != mode &&
                    (currentMode == TranslationMode.OFF || uiState.value.imageUris.isNotEmpty())

            if (shouldRetranslate) {
                // Clear existing translations and retranslate all images
                val imagesToRetranslate = uiState.value.imageUris

                _uiState.update {
                    it.copy(
                        isTranslating = true,
                        totalImagesToTranslate = imagesToRetranslate.size,
                        translationProgress = 0,
                        translatedTexts = emptyMap(),
                        sourceLanguages = emptyMap(),
                        translatedStatus = imagesToRetranslate.associateWith { false }.toMutableMap(),
                        // Tăng translationVersion để force UI update dragBlocksMap
                        translationVersion = it.translationVersion + 1
                    )
                }

                translationQueue.clear()
                translationQueue.addAll(imagesToRetranslate)
                translationJob?.cancel()
                translationJob = viewModelScope.launch(Dispatchers.IO) {
                    processTranslationQueue()
                }
                translationJob?.let { registerJob(it) }
                //log.i(TAG, "Đang dịch lại tất cả ${imagesToRetranslate.size} ảnh với chế độ $mode")
            } else {
                // Only translate new images that haven't been translated yet
                val imagesToTranslate = uiState.value.imageUris.filter { uri ->
                    !(uiState.value.translatedStatus[uri] ?: false)
                }
                if (imagesToTranslate.isNotEmpty()) {
                    enqueueTranslation(imagesToTranslate)
                } else {
                    //log.i(TAG, "Không có ảnh mới để dịch")
                }
            }
        } else { // TranslationMode.OFF
            _uiState.update {
                it.copy(
                    isTranslating = false,
                    translatedTexts = emptyMap(),
                    sourceLanguages = emptyMap(),
                    translatedStatus = uiState.value.imageUris.associateWith { false }.toMutableMap(),
                    // Tăng translationVersion để force UI xóa tất cả blocks
                    translationVersion = it.translationVersion + 1
                )
            }
            newImageUris.clear()
            translationQueue.clear()
            translationJob?.cancel()
            //log.i(TAG, "Đã tắt dịch và reset trạng thái")
        }
    }

    fun saveCurrentRoom() {
        viewModelScope.launch(Dispatchers.IO) {
            isManualSaving.set(true)
            // Set loading state
            _uiState.update { it.copy(isSavingRoom = true) }
            // Cancel any auto-save in progress to avoid race condition
            autoSaveJob?.cancel()
            autoSaveJob = null
            try {
            val imageCount = uiState.value.imageUris.size
            //log.i(TAG, "Đang lưu phòng hiện tại với $imageCount ảnh")
            if (imageCount == 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Không có ảnh để lưu!", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            try {
                // Loại bỏ duplicate URIs trước khi lưu
                val uniqueImageUris = uiState.value.imageUris.distinctBy { it.toString() }
                val uniqueTranslatedTexts = uiState.value.translatedTexts.filterKeys { uri ->
                    uniqueImageUris.contains(uri)
                }
                val uniqueTranslatedStatus = uiState.value.translatedStatus.filterKeys { uri ->
                    uniqueImageUris.contains(uri)
                }
                val uniqueSourceLanguages = uiState.value.sourceLanguages.filterKeys { uri ->
                    uniqueImageUris.contains(uri)
                }

                // Update UI state với unique lists
                _uiState.update { currentState ->
                    currentState.copy(
                        imageUris = uniqueImageUris,
                        translatedTexts = uniqueTranslatedTexts,
                        translatedStatus = uniqueTranslatedStatus,
                        sourceLanguages = uniqueSourceLanguages
                    )
                }

                val currentRoomId = uiState.value.roomId
                
                // XÓA translations của các ảnh có translatedStatus = false TRƯỚC KHI SAVE
                if (currentRoomId != null) {
                    uniqueImageUris.forEach { uri ->
                        val isTranslated = uniqueTranslatedStatus[uri] ?: false
                        if (!isTranslated) {
                            // Ảnh này đã tắt translation → XÓA tất cả translations khỏi DB
                            val imageId = uriToImageId[uri]
                            if (imageId != null) {
                                try {
                                    databaseHelper.deleteAllTranslationsForImage(imageId)
                                    Log.i(TAG, "Deleted all translations for imageId=$imageId uri=$uri (translatedStatus=false)")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to delete translations for imageId=$imageId", e)
                                }
                            }
                        }
                    }
                }
                
                // Log all rotation values before saving
                uniqueTranslatedTexts.forEach { (uri, pair) ->
                    pair.second.forEachIndexed { idx, block ->
                        //log.i(TAG, "[SAVE ROOM] Block[$idx] uri=$uri rotation=${block.rotation} text='${block.text}'")
                    }
                }
                val roomId: Long
                val savedCount: Int
                val wasRemoval: Boolean
                
                if (currentRoomId != null) {
                    // Nếu đã có roomId, update phòng
                    
                    // Check xem có ảnh nào thay đổi không
                    val changedImageIds = databaseHelper.getChangedImageIdsForRoom(currentRoomId)
                    val hasDirtyUris = dirtyUris.isNotEmpty()
                    val hasChangedImages = changedImageIds.isNotEmpty()
                    val hasNewImages = newImageUris.isNotEmpty()
                    val hasDeletedTranslations = deletedTranslationUris.isNotEmpty()
                    val hasRemovedImages = removedImageIds.isNotEmpty()
                    
                    Log.i(TAG, "Save check: dirtyUris=${dirtyUris.size} changedImageIds=${changedImageIds.size} newImageUris=${newImageUris.size} deletedTranslations=${deletedTranslationUris.size} removedImages=${removedImageIds.size}")
                    
                    var tempSavedCount = 0 // Track số lượng ảnh được save
                    var isRemovalOperation = false // Track if this is a removal operation
                    val updated: Boolean = when {
                        // Case 1: Có ảnh mới được thêm vào phòng → full update để add new images
                        hasNewImages -> {
                            Log.i(TAG, "Full update: Adding ${newImageUris.size} new images to room")
                            tempSavedCount = newImageUris.size
                            val ok = databaseHelper.updateMangaRoom(currentRoomId, uniqueImageUris, uniqueTranslatedTexts)
                            if (ok) {
                                newImageUris.clear()
                                dirtyUris.clear()
                                deletedTranslationUris.clear()
                            }
                            ok
                        }
                        // Case 2: Có ảnh bị xóa khỏi phòng → update to remove images
                        hasRemovedImages -> {
                            Log.i(TAG, "Removing ${removedImageIds.size} images from room")
                            tempSavedCount = removedImageIds.size
                            isRemovalOperation = true
                            val ok = databaseHelper.updateMangaRoom(currentRoomId, uniqueImageUris, uniqueTranslatedTexts)
                            if (ok) removedImageIds.clear()
                            ok
                        }
                        // Case 3: Có dirtyUris (từ edit manual) hoặc deletedTranslations → selective save
                        hasDirtyUris || hasDeletedTranslations -> {
                            val affectedUris = (dirtyUris + deletedTranslationUris).toSet()
                            tempSavedCount = affectedUris.size
                            val ok = databaseHelper.updateMangaRoomSelective(currentRoomId, uniqueImageUris, uniqueTranslatedTexts, affectedUris.toList(), uriToImageId)
                            if (ok) {
                                dirtyUris.clear()
                                deletedTranslationUris.clear()
                            }
                            ok
                        }
                        // Case 4: Có changedImageIds (từ retranslate) nhưng chưa được auto-save
                        hasChangedImages -> {
                            val mapping = mutableMapOf<Long, Pair<String, List<TextBlockInfo>>>()
                            uniqueImageUris.forEach { uri ->
                                val imgId = uriToImageId[uri]
                                if (imgId != null && imgId in changedImageIds) {
                                    uniqueTranslatedTexts[uri]?.let { pair -> mapping[imgId] = pair }
                                }
                            }
                            tempSavedCount = mapping.size
                            Log.i(TAG, "Partial save: ${tempSavedCount} retranslated images (out of ${changedImageIds.size} changed)")
                            if (mapping.isNotEmpty()) {
                                // Only process images in mapping, not all changedImageIds
                                databaseHelper.applyPendingChangesForRoom(currentRoomId, mapping)
                            } else {
                                Log.w(TAG, "Changed images found but no mapping created")
                                false
                            }
                        }
                        // Case 5: Không có gì thay đổi → skip save
                        else -> {
                            Log.i(TAG, "No changes detected, skipping save")
                            tempSavedCount = -1 // Signal no changes
                            true // Không có gì để save nhưng cũng không phải lỗi
                        }
                    }
                    
                    roomId = if (updated) currentRoomId else -1L
                    savedCount = tempSavedCount
                    wasRemoval = isRemovalOperation
                } else {
                    // Nếu chưa có roomId, tạo phòng mới
                    roomId = databaseHelper.saveMangaRoom(
                        uniqueImageUris,
                        uniqueTranslatedTexts
                    )
                    savedCount = uniqueImageUris.size
                    wasRemoval = false
                }
                
                if (roomId != -1L) {
                    withContext(Dispatchers.Main) {
                        val message = when {
                            savedCount > 0 && wasRemoval -> "Đã xóa $savedCount ảnh!"
                            savedCount > 0 -> "Đã lưu thành công $savedCount ảnh!"
                            savedCount == -1 -> "Không có thay đổi để lưu"
                            else -> "Đã lưu thành công!"
                        }
                        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
                    }
                    _uiState.update { it.copy(roomId = roomId) }
                    lastLoadedRoomId = roomId
                    galleryViewModel.notifyDataSaved()
                    loadAllRoomIds()
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Lưu thất bại!", Toast.LENGTH_SHORT).show()
                    }
                    Log.e(TAG, "Lưu phòng thất bại")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi lưu phòng", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Lưu thất bại!", Toast.LENGTH_SHORT).show()
                }
            }
            } finally {
                isManualSaving.set(false)
                // Reset loading state
                _uiState.update { it.copy(isSavingRoom = false) }
            }
        }
    }

    /**
     * Xóa một ảnh khỏi phòng hiện tại (và DB)
     */
    fun removeImageFromRoom(uri: Uri) {
        val currentUris = uiState.value.imageUris.toMutableList()
        if (!currentUris.contains(uri)) return
        
        // Track the removed image ID
        val imageId = uriToImageId[uri]
        if (imageId != null) {
            removedImageIds.add(imageId)
            Log.i(TAG, "Marked image for removal: imageId=$imageId uri=$uri")
        }
        
        currentUris.remove(uri)
        val newTranslatedTexts = uiState.value.translatedTexts.filterKeys { it != uri }
        val newStatus = uiState.value.translatedStatus.filterKeys { it != uri }.toMutableMap()
        val newSourceLangs = uiState.value.sourceLanguages.filterKeys { it != uri }
        // Loại ảnh khỏi hàng đợi dịch nếu có
        translationQueue.remove(uri)
        
        // Just update UI state - save will handle DB update
        _uiState.update {
            it.copy(
                imageUris = currentUris,
                translatedTexts = newTranslatedTexts,
                translatedStatus = newStatus,
                sourceLanguages = newSourceLangs
            )
        }
    }

    /**
     * Replace an existing image URI in the current room/session with a new URI.
     * Keeps the existing image_id (if any), translations and image_blocks intact by
     * updating the stored URI in DB. Also updates in-memory mappings and UI state.
     * If the image was not associated with a stored image_id, the ViewModel will
     * simply swap the URI in the UI state.
     */
    fun replaceImageUri(oldUri: Uri, newUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Update DB if we have an imageId mapping. Ask DB helper to copy the
                // new image into the room's images folder and return the stored app URI.
                val imageId = uriToImageId.entries.find { it.key.toString() == oldUri.toString() }?.value
                val finalUri: Uri = if (imageId != null && _uiState.value.roomId != null) {
                    try {
                        val stored = databaseHelper.replaceImageWithCopy(imageId, newUri)
                        val result = stored ?: newUri
                        if (stored != null) {
                            // bump version so UI invalidates Coil cache and reloads the new file
                            bumpImageVersion(imageId)
                            // bump reload token for the oldUri and the resulting stored uri string
                            bumpReloadTokenForUri(oldUri)
                            stored?.let { bumpReloadTokenForUri(it) }
                        }
                        // update uriToImageId mapping by string equality (remove old entries)
                        val keysToRemove = uriToImageId.keys.filter { it.toString() == oldUri.toString() }
                        keysToRemove.forEach { uriToImageId.remove(it) }
                        uriToImageId[ Uri.parse(result.toString()) ] = imageId
                        result
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update DB image uri for imageId=$imageId", e)
                        newUri
                    }
                } else {
                    // Not a stored image; just use newUri directly
                    newUri
                }

                // Atomically update UI state so we don't race with other updates
                _uiState.update { state ->
                    val current = state.imageUris.toMutableList()
                    val indexInState = current.indexOfFirst { it.toString() == oldUri.toString() }
                    if (indexInState == -1) {
                        Log.w(TAG, "replaceImageUri: oldUri not found in state during update: $oldUri")
                        return@update state
                    }
                    current[indexInState] = finalUri

                    val newTranslatedTexts = state.translatedTexts.toMutableMap()
                    val oldTextKey = newTranslatedTexts.keys.find { it.toString() == oldUri.toString() }
                    if (oldTextKey != null) {
                        newTranslatedTexts[finalUri] = newTranslatedTexts.remove(oldTextKey)!!
                    }

                    val newTranslatedStatus = state.translatedStatus.toMutableMap()
                    val oldStatusKey = newTranslatedStatus.keys.find { it.toString() == oldUri.toString() }
                    if (oldStatusKey != null) {
                        newTranslatedStatus[finalUri] = newTranslatedStatus.remove(oldStatusKey) ?: false
                    }

                    val newSourceLangs = state.sourceLanguages.toMutableMap()
                    val oldLangKey = newSourceLangs.keys.find { it.toString() == oldUri.toString() }
                    if (oldLangKey != null) {
                        newSourceLangs[finalUri] = newSourceLangs.remove(oldLangKey) ?: ""
                    }

                    // Return updated state with preserved ordering
                    state.copy(
                        imageUris = current,
                        translatedTexts = newTranslatedTexts,
                        translatedStatus = newTranslatedStatus,
                        sourceLanguages = newSourceLangs
                    )
                }

                // Mark as dirty so caller may save if desired
                val oldKeys = dirtyUris.filter { it.toString() == oldUri.toString() }
                oldKeys.forEach { dirtyUris.remove(it) }
                dirtyUris.add(finalUri)

                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Đã thay thế ảnh", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "replaceImageUri failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Thay thế ảnh thất bại", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun enqueueTranslation(images: List<Uri>) {
        translationQueue.addAll(images)
        if (translationJob == null || translationJob?.isActive != true) {
            translationJob = viewModelScope.launch(Dispatchers.IO) {
                processTranslationQueue()
            }
            translationJob?.let { registerJob(it) }
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private suspend fun processTranslationQueue() {
    // Khi dịch bằng Mistral/Gemini cho toàn bộ phòng, dịch song song 2 ảnh, mỗi ảnh dùng 1 key khác nhau trong lượt đó
    val isParallelKeyMode = uiState.value.translationMode == TranslationMode.MISTRAL || uiState.value.translationMode == TranslationMode.GEMINI
    val maxBatchSize = if (isParallelKeyMode) 2 else 1
    val translatedTexts = mutableMapOf<Uri, Pair<String, List<TextBlockInfo>>>()
    val sourceLanguages = mutableMapOf<Uri, String>()
    var completedCount = 0
    val totalToTranslate = translationQueue.size
        _uiState.update {
            it.copy(
                isTranslating = true,
                totalImagesToTranslate = totalToTranslate,
                translationProgress = 0
            )
        }
        while (translationQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            val batch = mutableListOf<Uri>()
            repeat(maxBatchSize) {
                translationQueue.poll()?.let { batch.add(it) }
            }
            if (batch.isEmpty()) break

            // Đặt trạng thái SCANNING cho tất cả ảnh trong batch
            batch.forEach { uri ->
                updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.SCANNING)
            }

            // Lấy key cho từng ảnh trong batch (nếu là Mistral/Gemini)
            val keysForBatch: List<String?> = if (isParallelKeyMode) {
                val repo = translationRepository
                if (uiState.value.translationMode == TranslationMode.MISTRAL) {
                    (0 until batch.size).map { repo.getNextMistralApiKey() }
                } else if (uiState.value.translationMode == TranslationMode.GEMINI) {
                    (0 until batch.size).map { repo.getNextGeminiApiKey() }
                } else {
                    List(batch.size) { null }
                }
            } else {
                List(batch.size) { null }
            }

            // Dịch song song, truyền key tương ứng cho từng ảnh
            val results = kotlinx.coroutines.coroutineScope {
                batch.mapIndexed { idx, uri ->
                    val key = keysForBatch.getOrNull(idx)
                    // Callback để cập nhật trạng thái từ repository
                    val statusCallback: (com.example.ocrmanga.data.models.TranslationStatus) -> Unit = { status ->
                        updateTranslationStatus(uri, status)
                    }
                    async(Dispatchers.IO) {
                        try {
                            val (original, translatedBlocks, sourceLang) = translationRepository.recognizeAndTranslateText(
                                uri,
                                uiState.value.translationMode,
                                key,
                                statusCallback
                            )
                            Triple(uri, original, translatedBlocks to sourceLang)
                        } catch (e: Exception) {
                            Log.e(TAG, "Lỗi khi dịch ảnh $uri", e)
                            Triple(uri, "", Pair(emptyList<TextBlockInfo>(), ""))
                        }
                    }
                }.awaitAll()
            }
            // Cập nhật kết quả cho từng ảnh nếu ảnh còn tồn tại
            results.forEach { result ->
                val (uri, original, pair) = result
                val (translatedBlocks, sourceLang) = pair
                
                if (uiState.value.imageUris.contains(uri)) {
                    if (original.isNotEmpty() || (translatedBlocks as? List<*>)?.isNotEmpty() == true) {
                                val fixedBlocks = (translatedBlocks as List<TextBlockInfo>).map { block ->
                                        // Prefer an explicit custom overlay color; otherwise use detected average background color; fallback to white
                                        val baseOverlay = block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()
                                        val textColor = block.customTextColor ?: computeDefaultTextColor(baseOverlay, block.averageBackgroundColor)
                                        block.copy(
                                            customOverlayColor = baseOverlay,
                                            customTextColor = textColor,
                                            // Set applyMerge = true khi translation mới để áp dụng logic chống chồng lấn
                                            applyMerge = true
                                        )
                                }
                                

                        translatedTexts[uri] = original to fixedBlocks

                        sourceLanguages[uri] = sourceLang as String
                        completedCount++
                        _uiState.update {
                            it.copy(
                                translatedTexts = it.translatedTexts + translatedTexts,
                                sourceLanguages = it.sourceLanguages + sourceLanguages,
                                translatedStatus = it.translatedStatus + (uri to true),
                                translationProgress = completedCount,
                                // Tăng translationVersion để force UI update blocks mới
                                translationVersion = it.translationVersion + 1
                            )
                        }
                        
                        // Cập nhật trạng thái COMPLETED
                        updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.COMPLETED)
                    } else {
                        Log.w(TAG, "Không nhận diện được văn bản trong ảnh $uri")
                        // Nếu không nhận diện được văn bản, đánh dấu ảnh đã được xử lý
                        // bằng một entry rỗng trong translatedTexts để tránh UI vẫn
                        // hiển thị overlay "Đang tải bản dịch..." vô thời hạn.
                        _uiState.update {
                            it.copy(
                                translatedTexts = it.translatedTexts + (uri to ("" to emptyList())),
                                translatedStatus = it.translatedStatus + (uri to true),
                                // tăng phiên bản để ép UI cập nhật ngay
                                translationVersion = it.translationVersion + 1
                            )
                        }
                        // Xóa trạng thái vì không có văn bản để dịch
                        clearTranslationStatus(uri)
                    }
                } else {
                    // Ảnh đã bị xóa, clear trạng thái
                    clearTranslationStatus(uri)
                }
            }
            
            // Delay ngắn để hiển thị trạng thái COMPLETED trước khi xóa
            delay(800)
            results.forEach { (uri, _, _) ->
                clearTranslationStatus(uri)
            }
            
            // Sau mỗi đợt, delay ngắn hơn để tăng tốc
            if (translationQueue.isNotEmpty()) {
                delay(200) // Giảm xuống còn 0.2s
            }
        }
        _uiState.update {
            it.copy(
                isTranslating = false,
                translationProgress = 0,
                totalImagesToTranslate = 0
            )
        }
        newImageUris.clear()
        // Xóa tất cả trạng thái dịch còn lại
        clearAllTranslationStatus()
    }

    /**
     * Toggle auto-translate setting for current room
     */
    fun toggleAutoTranslate() {
        val roomId = uiState.value.roomId ?: return
        val newValue = !uiState.value.autoTranslateEnabled
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                databaseHelper.setAutoTranslateSetting(roomId, newValue)
                _uiState.update { it.copy(autoTranslateEnabled = newValue) }
                withContext(Dispatchers.Main) {
                    val message = if (newValue) "Đã BẬT tự động dịch ảnh mới" else "Đã TẮT tự động dịch ảnh mới"
                    Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling auto-translate", e)
            }
        }
    }

    /**
     * Xóa toàn bộ session, ảnh, trạng thái dịch, trạng thái phòng, v.v. (reset sạch ViewModel)
     */
    /**
     * Clear session and images. If deleteSavedRoom == true and a roomId is loaded,
     * also delete the room from database and remove its image folder (permanent removal).
     */
    fun clearSessionAndImages(deleteSavedRoom: Boolean = false) {
        // 1) Cancel timer and any tracked jobs
        try {
            stopTranslationTimer()
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping timer during clearSessionAndImages", e)
        }

        // Cancel and clear all active jobs we registered
        try {
            while (true) {
                val j = activeJobs.poll() ?: break
                try {
                    if (j.isActive) j.cancel()
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to cancel active job during clear", t)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error while cancelling activeJobs", e)
        }

        // Cancel primary translation job as well
        try {
            translationJob?.cancel()
            translationJob = null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel translationJob", e)
        }

        // 2) Reset UI state to an empty session so UI navigators don't accidentally save
        _uiState.update {
            it.copy(
                imageUris = emptyList(),
                translatedTexts = emptyMap(),
                sourceLanguages = emptyMap(),
                translatedStatus = emptyMap(),
                roomId = null,
                translationEnabled = false,
                translationMode = TranslationMode.OFF,
                isTranslating = false,
                translationProgress = 0,
                totalImagesToTranslate = 0,
                remainingImages = emptyList(),
                translationTimer = 0,
                currentTranslatingImage = null,
                currentTranslatingImageIndex = 0,
                isLoadingMoreImages = false
            )
        }

        // 3) Clear queues and local in-memory lists
        newImageUris.clear()
        translationQueue.clear()
        dirtyUris.clear()
        deletedTranslationUris.clear()
        removedImageIds.clear()
        uriToImageId.clear()
    // Ensure we forget any remembered room id so temporary sessions don't fall back
    // to a previously loaded room. This fixes cases where selecting images creates
    // a temporary "room" but the ViewModel later reloads the lastSaved room.
    lastLoadedRoomId = null
    Log.i(TAG, "clearSessionAndImages: cleared lastLoadedRoomId")

        // 4) Best-effort remove temporary/cache files created by the app
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // If requested, delete the saved room from DB and remove its files
                if (deleteSavedRoom) {
                    try {
                        val rid = uiState.value.roomId ?: lastLoadedRoomId
                        if (rid != null) {
                            Log.i(TAG, "clearSessionAndImages: deleting saved room $rid as requested")
                            try { databaseHelper.deleteRoom(rid) } catch (e: Throwable) { Log.w(TAG, "Failed to delete room $rid", e) }
                            // Also remove images folder if exists
                            val imagesDir = File(getApplication<Application>().getExternalFilesDir(null), "images/$rid")
                            if (imagesDir.exists()) {
                                try { imagesDir.deleteRecursively() } catch (e: Throwable) { Log.w(TAG, "Failed to delete images folder for room $rid", e) }
                            }
                            // clear remembered id
                            lastLoadedRoomId = null
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error deleting saved room during clearSessionAndImages", e)
                    }
                }

                // Clear translationRepository caches/session
                try {
                    translationRepository.clearSession()
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to clear translationRepository session", e)
                }
                val app = getApplication<Application>()
                // Safer cache cleanup: only remove dedicated app subfolders to avoid deleting unrelated cache
                try {
                    // Prefer removing a dedicated subfolder (ocrmanga_cache) in cache directories
                    val privateCacheFolder = File(app.cacheDir, "ocrmanga_cache")
                    if (privateCacheFolder.exists()) {
                        try { privateCacheFolder.deleteRecursively() } catch (e: Throwable) { Log.w(TAG, "Failed to delete private cache folder ${privateCacheFolder.absolutePath}", e) }
                    }

                    val externalCacheFolder = app.externalCacheDir?.let { File(it, "ocrmanga_cache") }
                    if (externalCacheFolder != null && externalCacheFolder.exists()) {
                        try { externalCacheFolder.deleteRecursively() } catch (e: Throwable) { Log.w(TAG, "Failed to delete external cache folder ${externalCacheFolder.absolutePath}", e) }
                    }

                    // Legacy: if old code stored files directly under cache with specific suffix, only delete those files
                    app.cacheDir?.listFiles()?.forEach { f ->
                        try {
                            if (f.name.endsWith(".ocr_cache") || f.name.endsWith(".tmp") ) {
                                // Only delete files (not directories) with these suffixes
                                if (f.isFile) f.delete()
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to delete legacy cache file ${f.name}", e)
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Error cleaning cache folders", e)
                }
                    // Persist a flag so the app won't immediately rebuild disk caches (e.g., Coil disk cache)
                    // This prevents the cache from being repopulated on next startup until the user performs
                    // an explicit action that re-enables caching. Keep this small and safe (SharedPreferences).
                    try {
                        val prefs = app.getSharedPreferences("ocrmanga_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("suppress_coil_disk_cache", true).apply()
                        Log.i(TAG, "Set suppress_coil_disk_cache=true after clearing session cache")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to persist cache-suppress flag", e)
                    }
                // app files/ocrmanga_temp
                File(app.filesDir, "ocrmanga_temp").takeIf { it.exists() }?.let { tmpDir ->
                    try {
                        tmpDir.deleteRecursively()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to delete ocrmanga_temp folder", e)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error clearing temp files during clearSessionAndImages", e)
            }
        }
    }

    fun updateRoomTitle(roomId: Long, newTitle: String) {
        databaseHelper.updateRoomTitle(roomId, newTitle)
    }

    fun convertRoomToPDF() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val pdfDocument = PdfDocument()
                val imageUris = _uiState.value.imageUris

                imageUris.forEachIndexed { index, uri ->
                    val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width, bitmap.height, true)
                    val pageInfo = PdfDocument.PageInfo.Builder(scaledBitmap.width, scaledBitmap.height, index + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val paint = Paint().apply {
                        isAntiAlias = true
                        isFilterBitmap = true
                        isDither = true
                    }
                    page.canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)
                    pdfDocument.finishPage(page)
                }
                val pdfFile = File(context.getExternalFilesDir(null), "room_${_uiState.value.roomId}.pdf")
                pdfDocument.writeTo(FileOutputStream(pdfFile))
                pdfDocument.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "PDF created: ${pdfFile.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating PDF", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to create PDF", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Export all translated images of a room into a zip file placed under
     * <externalFilesDir>/exports/room_<roomId>_<timestamp>.zip
     * Returns the absolute path to the zip file on success, or null on failure / no translated images.
     */
    suspend fun exportRoomAsZip(roomId: Long): String? {
        return withContext(Dispatchers.IO) {
            // Set loading state
            _uiState.update { it.copy(isExportingRoom = true) }
            try {
                val (allImages, _, translations) = databaseHelper.getMangaRoom(roomId)
                if (allImages.isEmpty()) return@withContext null
                val app = getApplication<Application>()
                val timestamp = System.currentTimeMillis()
                val fileName = "room_${roomId}_$timestamp.zip"

                // Try to write into the public Downloads folder first. If not possible, fall back to app's external files/exports
                var zipFile: File? = null
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (downloadsDir != null) {
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        if (downloadsDir.exists() && downloadsDir.canWrite()) {
                            zipFile = File(downloadsDir, fileName)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to prepare Downloads dir, will fallback to app exports", e)
                    zipFile = null
                }

                if (zipFile == null) {
                    val exportsDir = File(app.getExternalFilesDir(null), "exports")
                    if (!exportsDir.exists()) exportsDir.mkdirs()
                    zipFile = File(exportsDir, fileName)
                }

                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    val buffer = ByteArray(8 * 1024)
                    var idx = 0
                    // iterate all images to preserve order; if translation exists for a uri, bake it into the image
                    for (uri in allImages) {
                        try {
                            val entryName = try { File(uri.path ?: "image_${idx}.jpg").name } catch (e: Exception) { "image_${idx}.jpg" }

                            val pair = translations[uri]
                            if (pair != null) {
                                // render translated blocks onto bitmap
                                try {
                                    val cr = app.contentResolver
                                    cr.openInputStream(uri)?.use { input ->
                                        val src = BitmapFactory.decodeStream(input) ?: return@use
                                        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                                        val canvas = Canvas(bmp)

                                        for (block in pair.second) {
                                            try {
                                                val bounds = block.bounds
                                                val boundsWidth = (bounds.right - bounds.left).toFloat()
                                                val boundsHeight = (bounds.bottom - bounds.top).toFloat()
                                                
                                                // Apply overlay saturation to overlay color
                                                val rawOverlayColor = block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()
                                                val overlayColor = if (block.overlaySaturation != 1.0f) {
                                                    val hsv = FloatArray(3)
                                                    androidx.core.graphics.ColorUtils.colorToHSL(rawOverlayColor, hsv)
                                                    hsv[1] = (hsv[1] * block.overlaySaturation).coerceIn(0f, 1f)
                                                    androidx.core.graphics.ColorUtils.HSLToColor(hsv)
                                                } else {
                                                    rawOverlayColor
                                                }
                                                
                                                val overlayPaint = Paint().apply {
                                                    isAntiAlias = true
                                                    style = Paint.Style.FILL
                                                    color = overlayColor
                                                    alpha = (block.overlayAlpha * 255).toInt().coerceIn(0, 255)
                                                }
                                                val rectF = RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
                                                if (block.shapeType == 1) {
                                                    canvas.drawOval(rectF, overlayPaint)
                                                } else {
                                                    canvas.drawRect(rectF, overlayPaint)
                                                }
                                                val displayMetrics = app.resources.displayMetrics
                                                val screenWidthPx = displayMetrics.widthPixels.toFloat()
                                                val bitmapToViewScale = screenWidthPx / src.width.toFloat()
                                                
                                                // Scale bounds từ bitmap coordinate → view coordinate
                                                val scaledWidth = boundsWidth * bitmapToViewScale
                                                val scaledHeight = boundsHeight * bitmapToViewScale
                                                
                                                // Calculate text area with padding EXACTLY like view mode does
                                                val isOval = block.shapeType == 1
                                                val textPadding = if (isOval) 0.15f else 0f
                                                val textWidth = scaledWidth * (1 - 2 * textPadding)
                                                val textHeight = scaledHeight * (1 - 2 * textPadding)
                                                
                                                // Tính screenScaleFactor để điều chỉnh theo màn hình device
                                                val screenWidthDp = screenWidthPx / displayMetrics.density
                                                val baseWidthDp = 360f
                                                val screenScaleFactor = (screenWidthDp / baseWidthDp).coerceIn(0.5f, 2.0f)
                                                
                                                // fontSize = base * screenScale (giống view mode)
                                                val baseFontSize = block.fontSize
                                                val scaledFontSize = baseFontSize * screenScaleFactor

                                                // Áp dụng adjustWhiteoutBounds với scaled fontSize và scaled textArea
                                                val effectiveWidth = if (block.isVertical) textHeight else textWidth
                                                val effectiveHeight = if (block.isVertical) textWidth else textHeight
                                                
                                                val (wrappedText, optimalFontSize) = com.example.ocrmanga.ui.screens.view.adjustWhiteoutBounds(
                                                    text = block.text,
                                                    initialWidth = effectiveWidth,
                                                    initialHeight = effectiveHeight,
                                                    fontSize = scaledFontSize,
                                                    isVertical = block.isVertical,
                                                    context = app,
                                                    fontFamilyName = block.fontFamily,
                                                    shapeType = block.shapeType
                                                )
                                                
                                                // Dùng optimalFontSize trực tiếp, nhưng scale lên cho bitmap coordinates
                                                val finalFontSizeForBitmap = optimalFontSize / bitmapToViewScale

                                                // Draw text with all properties (font, boldness, border, shadow, line spacing)
                                                val rawTextColor = block.customTextColor ?: computeDefaultTextColor(overlayColor or 0xFF000000.toInt(), block.averageBackgroundColor)
                                                var textColor = if (block.textSaturation != 1.0f) {
                                                    val hsv = FloatArray(3)
                                                    androidx.core.graphics.ColorUtils.colorToHSL(rawTextColor, hsv)
                                                    hsv[1] = (hsv[1] * block.textSaturation).coerceIn(0f, 1f)
                                                    androidx.core.graphics.ColorUtils.HSLToColor(hsv)
                                                } else {
                                                    rawTextColor
                                                }
                                                
                                                // Load custom font typeface
                                                val typeface = try {
                                                    com.example.ocrmanga.ui.screens.view.getCachedTypefaceForExport(app, block.fontFamily)
                                                } catch (e: Exception) {
                                                    null
                                                }

                                                // Create text paint with boldness
                                                val tp = TextPaint().apply {
                                                    isAntiAlias = true
                                                    color = textColor
                                                    textSize = finalFontSizeForBitmap
                                                    textAlign = Paint.Align.CENTER
                                                    this.typeface = typeface ?: Typeface.DEFAULT
                                                    
                                                    // Apply boldness
                                                    if (block.textBoldness > 1.0f) {
                                                        style = Paint.Style.FILL_AND_STROKE
                                                        strokeWidth = (block.textBoldness - 1.0f) * 2.0f
                                                    } else if (block.textBoldness < 1.0f) {
                                                        alpha = (255 * block.textBoldness).toInt().coerceIn(50, 255)
                                                    }
                                                }

                                                // Create border paint if needed
                                                var borderPaint = if (block.customBorderColor != null && block.borderThickness > 0f) {
                                                    TextPaint().apply {
                                                        isAntiAlias = true
                                                        color = block.customBorderColor
                                                        alpha = (block.borderAlpha * 255).toInt().coerceIn(0, 255)
                                                        textSize = finalFontSizeForBitmap
                                                        textAlign = Paint.Align.CENTER
                                                        style = Paint.Style.STROKE
                                                        // Scale borderThickness from view to bitmap coordinates
                                                        strokeWidth = block.borderThickness / bitmapToViewScale
                                                        this.typeface = typeface ?: Typeface.DEFAULT
                                                    }
                                                } else null

                                                // Create shadow paint if needed
                                                var shadowPaint = if (block.customShadowColor != null) {
                                                    TextPaint().apply {
                                                        isAntiAlias = true
                                                        color = block.customShadowColor
                                                        alpha = (block.shadowAlpha * 255).toInt().coerceIn(0, 255)
                                                        textSize = finalFontSizeForBitmap
                                                        textAlign = Paint.Align.CENTER
                                                        style = Paint.Style.FILL
                                                        this.typeface = typeface ?: Typeface.DEFAULT
                                                        // Scale shadow parameters from view to bitmap coordinates
                                                        val radius = if (block.shadowRadius > 0f) {
                                                            block.shadowRadius / bitmapToViewScale
                                                        } else {
                                                            (finalFontSizeForBitmap * 0.14f).coerceAtLeast(1f)
                                                        }
                                                        val dx = finalFontSizeForBitmap * 0.04f
                                                        val dy = finalFontSizeForBitmap * 0.04f
                                                        setShadowLayer(radius, dx, dy, block.customShadowColor)
                                                    }
                                                } else null

                                                canvas.save()
                                                // Rotate around center of the block if rotation specified
                                                val cx = bounds.left + boundsWidth / 2f
                                                val cy = bounds.top + boundsHeight / 2f
                                                val rotation = block.rotation ?: 0f
                                                if (rotation != 0f) canvas.rotate(rotation, cx, cy)

                                                // Draw text line by line with proper positioning
                                                val lines = wrappedText.split("\n")
                                                val fontMetrics = tp.fontMetrics
                                                val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * block.lineSpacing

                                                // Calculate text drawing area with padding (exactly like view mode)
                                                // textPadding is already calculated as ratio (0.15 for oval, 0 for rect)
                                                val textPaddingPx = boundsWidth * textPadding
                                                val textLeft = bounds.left.toFloat() + textPaddingPx
                                                val textTop = bounds.top.toFloat() + textPaddingPx
                                                val textDrawWidth = boundsWidth - 2 * textPaddingPx
                                                val textDrawHeight = boundsHeight - 2 * textPaddingPx

                                                if (block.isVertical) {
                                                    // Vertical text rendering with padding
                                                    var currentX = textLeft + textDrawWidth - lineHeight
                                                    for (line in lines) {
                                                        if (line.isNotBlank() && currentX >= textLeft) {
                                                            canvas.save()
                                                            canvas.translate(currentX, textTop)
                                                            canvas.rotate(90f)
                                                            val lineWidth = tp.measureText(line)
                                                            val centeredY = (textDrawHeight - lineWidth) / 2
                                                            // Draw shadow, then border, then text
                                                            shadowPaint?.let { canvas.drawText(line, centeredY, -fontMetrics.ascent, it) }
                                                            borderPaint?.let { canvas.drawText(line, centeredY, -fontMetrics.ascent, it) }
                                                            canvas.drawText(line, centeredY, -fontMetrics.ascent, tp)
                                                            canvas.restore()
                                                            currentX -= lineHeight
                                                        }
                                                    }
                                                } else {
                                                    // Horizontal text rendering - center text vertically with equal top/bottom margins
                                                    val totalTextHeight = lines.size * lineHeight
                                                    // Calculate margin to center text block vertically (equal spacing top and bottom)
                                                    val verticalMargin = (textDrawHeight - totalTextHeight) / 2f
                                                    // Start Y position: top of text area + vertical margin - ascent to position baseline correctly
                                                    val startY = textTop + verticalMargin - fontMetrics.ascent
                                                    var currentY = startY
                                                    
                                                    for (line in lines) {
                                                        if (line.isNotBlank()) {
                                                            val centerX = textLeft + textDrawWidth / 2f
                                                            // Draw shadow, then border, then text
                                                            shadowPaint?.let { canvas.drawText(line, centerX, currentY, it) }
                                                            borderPaint?.let { canvas.drawText(line, centerX, currentY, it) }
                                                            canvas.drawText(line, centerX, currentY, tp)
                                                        }
                                                        currentY += lineHeight
                                                        // Stop if we exceed available space
                                                        if (currentY + fontMetrics.descent > textTop + textDrawHeight - verticalMargin) break
                                                    }
                                                }

                                                canvas.restore()
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Failed to render block for uri=$uri", e)
                                            }
                                        }

                                        // write bitmap to zip entry
                                        val baos = ByteArrayOutputStream()
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, baos)
                                        val bytes = baos.toByteArray()
                                        zos.putNextEntry(ZipEntry(entryName))
                                        zos.write(bytes)
                                        zos.closeEntry()
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to render image with translations for $uri", e)
                                }
                            } else {
                                // no translations for this uri: stream original file
                                try {
                                    val path = uri.path
                                    if (!path.isNullOrBlank()) {
                                        val f = File(path)
                                        if (f.exists()) {
                                            zos.putNextEntry(ZipEntry(entryName))
                                            BufferedInputStream(FileInputStream(f)).use { bis ->
                                                var len = bis.read(buffer)
                                                while (len > 0) {
                                                    zos.write(buffer, 0, len)
                                                    len = bis.read(buffer)
                                                }
                                            }
                                            zos.closeEntry()
                                        } else {
                                            // fallback to content resolver
                                            app.contentResolver.openInputStream(uri)?.use { input ->
                                                zos.putNextEntry(ZipEntry(entryName))
                                                BufferedInputStream(input).use { bis ->
                                                    var len = bis.read(buffer)
                                                    while (len > 0) {
                                                        zos.write(buffer, 0, len)
                                                        len = bis.read(buffer)
                                                    }
                                                }
                                                zos.closeEntry()
                                            }
                                        }
                                    } else {
                                        app.contentResolver.openInputStream(uri)?.use { input ->
                                            zos.putNextEntry(ZipEntry(entryName))
                                            BufferedInputStream(input).use { bis ->
                                                var len = bis.read(buffer)
                                                while (len > 0) {
                                                    zos.write(buffer, 0, len)
                                                    len = bis.read(buffer)
                                                }
                                            }
                                            zos.closeEntry()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to add original image to zip: $uri", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to add image to zip: $uri", e)
                        }
                        idx++
                    }
                }

                Log.i(TAG, "Exported room $roomId to ${zipFile.absolutePath}")
                zipFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "exportRoomAsZip failed for room $roomId", e)
                null
            } finally {
                // Reset loading state
                _uiState.update { it.copy(isExportingRoom = false) }
            }
        }
    }

    fun saveRoom(dragBlocksMap: Map<Uri, List<com.example.ocrmanga.ui.screens.view.DragBlockState>>) {
        val roomId = uiState.value.roomId ?: return
        val currentState = uiState.value
        val updatedTranslatedTexts = currentState.translatedTexts.toMutableMap()
        dragBlocksMap.forEach { (uri, blocks) ->
            updatedTranslatedTexts[uri] = Pair(
                updatedTranslatedTexts[uri]?.first ?: "",
                blocks.map { state ->
                    // copy visual edits from DragBlockState into TextBlockInfo so they persist
                    val b = state.block
                    b.copy(
                        rotation = state.rotation,
                        shapeType = b.shapeType,
                        customOverlayColor = state.whiteoutColor?.toArgb() ?: b.customOverlayColor,
                        customTextColor = state.textColor?.toArgb()
                            ?: b.customTextColor
                            ?: computeDefaultTextColor(state.whiteoutColor?.toArgb() ?: b.customOverlayColor, b.averageBackgroundColor),
                        overlayAlpha = state.overlayAlpha,
                        textBoldness = state.textBoldness,
                        overlaySaturation = state.overlaySaturation,
                        textSaturation = state.textSaturation,
                        customBorderColor = state.textBorderColor?.toArgb() ?: b.customBorderColor,
                        borderThickness = state.textBorderThickness,
                        borderAlpha = state.textBorderAlpha,
                        // Persist shadow edits as well so they aren't lost after save
                        customShadowColor = state.textShadowColor?.toArgb(),
                        shadowAlpha = state.textShadowAlpha,
                        shadowRadius = state.textShadowRadius,
                        lineSpacing = state.lineSpacing,
                        // Log the resulting TextBlockInfo shadow values for debugging
                        // (log after copy isn't trivial here; include in-line values)
                        fontSize = state.fontSize ?: b.fontSize,
                        // Set applyMerge = false vì đây là save sau khi edit
                        applyMerge = false
                    )
                }
            )
        }
        // If room already exists, update selectively by image_id for only edited images
        if (dirtyUris.isNotEmpty() && uiState.value.roomId != null) {
            databaseHelper.updateMangaRoomSelective(roomId, currentState.imageUris, updatedTranslatedTexts, dirtyUris.toList(), uriToImageId)
            // clear dirty set after saving
            dirtyUris.clear()
        } else {
            databaseHelper.updateMangaRoom(roomId, currentState.imageUris, updatedTranslatedTexts)
        }
    }

    // Expose API key availability checks for UI
    fun hasGeminiApiKeys(): Boolean {
        return translationRepository.hasGeminiApiKeys()
    }

    fun hasMistralApiKeys(): Boolean {
        return translationRepository.hasMistralApiKeys()
    }

    // Public accessor for UI to get imageId for a given uri if available
    fun getImageIdForUri(uri: Uri): Long? {
        return uriToImageId[uri]
    }

    // Per-image version counter used to force image reloads when the underlying file is replaced
    private val imageVersions = mutableMapOf<Long, Int>()

    // Bump version for an imageId (call after replacing file content)
    private fun bumpImageVersion(imageId: Long) {
        imageVersions[imageId] = (imageVersions[imageId] ?: 0) + 1
        // Also bump translationVersion to ensure overlays re-evaluate if needed
        _uiState.update { it.copy(translationVersion = it.translationVersion + 1) }
    }

    // Public accessor for UI to get version for a uri (based on mapped imageId)
    fun getImageVersionForUri(uri: Uri): Int? {
        val id = uriToImageId.entries.find { it.key.toString() == uri.toString() }?.value
        return id?.let { imageVersions[it] }
    }

    // Per-URI reload tokens (timestamp) to force reload even when the Uri string doesn't change
    private val uriReloadTokens = mutableMapOf<String, Long>()

    private fun bumpReloadTokenForUri(uri: Uri) {
        uriReloadTokens[uri.toString()] = System.currentTimeMillis()
    }

    fun getReloadTokenForUri(uri: Uri): Long? {
        return uriReloadTokens[uri.toString()]
    }

    /**
     * Re-enable disk cache writes after the user previously cleared cache.
     * Call this from UI when the user explicitly wants caching back (optional).
     */
    fun enableDiskCacheWrites() {
        try {
            val app = getApplication<Application>()
            val prefs = app.getSharedPreferences("ocrmanga_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("suppress_coil_disk_cache", false).apply()
            Log.i(TAG, "Cleared suppress_coil_disk_cache flag (disk caching re-enabled)")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear suppress_coil_disk_cache flag", e)
        }
    }
}
data class ViewerUiState(
    val imageUris: List<Uri> = emptyList(),
    val translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>> = emptyMap(),
    val sourceLanguages: Map<Uri, String> = emptyMap(),
    val translatedStatus: Map<Uri, Boolean> = emptyMap(),
    val translationVersion: Int = 0,
    val translationMode: TranslationMode = TranslationMode.OFF,
    val translationEnabled: Boolean = false,
    val isTranslating: Boolean = false,
    val translationProgress: Int = 0,
    val totalImagesToTranslate: Int = 0,
    val currentTranslatingImage: Uri? = null,
    val currentTranslatingImageIndex: Int = 0,
    val translationTimer: Int = 0,
    val isLoadingMoreImages: Boolean = false,
    val remainingImages: List<Uri> = emptyList(),
    val roomId: Long? = null,
    val autoTranslateEnabled: Boolean = true, // Auto-translate new images when adding to room
    val isSavingRoom: Boolean = false, // Loading state for room saving
    val isExportingRoom: Boolean = false, // Loading state for room exporting
    // Map theo dõi trạng thái dịch của từng ảnh (Uri -> TranslationStatus)
    val translatingImages: Map<Uri, com.example.ocrmanga.data.models.TranslationStatus> = emptyMap()
)