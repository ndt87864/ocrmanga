package com.example.ocrmanga.viewmodels

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import com.example.ocrmanga.utils.AppLogger as Log
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.models.BackgroundType
import com.example.ocrmanga.data.repositories.TranslationRepository
import com.example.ocrmanga.ui.screens.view.computeDefaultTextColor
import com.example.ocrmanga.ui.screens.view.getImageDimensions
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
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Build
import android.os.Environment
import com.example.ocrmanga.data.constant.AppConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val _viewMode = MutableStateFlow(com.example.ocrmanga.ui.screens.view.ViewMode.VERTICAL)
    val viewModeFlow: StateFlow<com.example.ocrmanga.ui.screens.view.ViewMode> = _viewMode.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                com.example.ocrmanga.ui.screens.view.ViewerPreferences.viewModeFlow(getApplication()).collect { mode ->
                    _viewMode.value = mode
                }
            } catch (_: Exception) {
            }
        }
    }

    fun setViewMode(mode: com.example.ocrmanga.ui.screens.view.ViewMode) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTransitioningMode = true) }
            // Cho UI 100ms để hiển thị overlay mượt mà trước khi thực hiện chuyển đổi nặng
            delay(100)
            try {
                com.example.ocrmanga.ui.screens.view.ViewerPreferences.saveViewMode(getApplication(), mode)
            } catch (_: Exception) {
            }
            _viewMode.value = mode
            // Thêm delay để giữ overlay trong khi UI đang tái cấu trúc
            delay(600)
            _uiState.update { it.copy(isTransitioningMode = false) }
        }
    }

    fun setLoading(loading: Boolean) {
        _uiState.update { it.copy(isLoading = loading) }
    }

    fun setTransitioningMode(transitioning: Boolean) {
        _uiState.update { it.copy(isTransitioningMode = transitioning) }
    }
            // Trả về số lượng ảnh đã thay đổi trong room
            fun getNumChangedImages(roomId: Long): Int {
                return try {
                    databaseHelper.getNumChangedImages(roomId)
                } catch (e: Exception) {
                    0
                }
            }
            // Lấy danh sách blocks hiện tại cho một URI
            fun getExistingBlocksForUri(uri: Uri): List<TextBlockInfo> {
                // Ưu tiên lấy từ memory (uiState) nếu đã load
                val fromMemory = _uiState.value.translatedTexts[uri]?.second
                if (!fromMemory.isNullOrEmpty()) return fromMemory
                
                // Nếu chưa có trong memory, thử lấy từ DB
                return try {
                    var imageId = uriToImageId[uri]
                    
                    // Nếu không có trong cache, thử tìm trong DB bằng URI
                    if (imageId == null) {
                        imageId = databaseHelper.getImageIdByUri(uri)
                        if (imageId != null) {
                            uriToImageId[uri] = imageId
                        }
                    }
                    
                    if (imageId != null) {
                        // Trả về list blocks đầy đủ từ DB
                        val blocks = databaseHelper.getBlocksForImageAsTextBlockInfo(imageId)
                        //Log.i("ViewerViewModel", "getExistingBlocksForUri: Found ${blocks.size} blocks in DB for imageId=$imageId, uri=$uri")
                        blocks
                    } else {
                        //Log.i("ViewerViewModel", "getExistingBlocksForUri: imageId not found for uri=$uri")
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e("ViewerViewModel", "Error in getExistingBlocksForUri for $uri", e)
                    emptyList()
                }
            }

            private fun isReusableOcrBlock(block: TextBlockInfo): Boolean {
                return !block.originalText.isNullOrBlank() &&
                        block.bounds.width() > 0 &&
                        block.bounds.height() > 0
            }

            fun getReusableOcrBlocksForUri(uri: Uri): List<TextBlockInfo> {
                return getExistingBlocksForUri(uri).filter(::isReusableOcrBlock)
            }

            fun hasReusableOcrForUri(uri: Uri): Boolean {
                return getReusableOcrBlocksForUri(uri).isNotEmpty()
            }

            fun hasReusableOcrForAny(uris: List<Uri>): Boolean {
                return uris.distinctBy { it.toString() }.any(::hasReusableOcrForUri)
            }
    // Chuyển đổi trạng thái pendingDelete cho block của một ảnh
    fun toggleTranslationVisibility(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                translationEnabled = enabled,
                translationMode = if (enabled) state.lastTranslationMode else TranslationMode.OFF,
                translationVersion = state.translationVersion + 1
            )
        }
    }

    fun togglePendingDelete(uri: Uri, blockId: Int, setPending: Boolean) {
        _uiState.update { state ->
            val oldPair = state.translatedTexts[uri] ?: ("" to emptyList<TextBlockInfo>())
            val blocks = oldPair.second.map {
                if (it.bounds.hashCode() == blockId) it.copy(pendingDelete = setPending) else it
            }
            state.copy(
                translatedTexts = state.translatedTexts.toMutableMap().apply {
                    put(uri, oldPair.first to blocks)
                },
                translationVersion = state.translationVersion + 1
            )
        }
        // Mark this URI as dirty so save will detect the change
        dirtyUris.add(uri)
        //Log.i(TAG, "togglePendingDelete: Marked uri=$uri as dirty (pendingDelete=$setPending)")
    }
    
    // Xóa text gốc trên ảnh sử dụng LaMa inpainting
    // Bước 1: Tạo preview mask overlay để user xác nhận trước khi xóa
    fun removeOriginalText(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(removingTextProgress = "Đang OCR vùng text...") }
                val blocks = translationRepository.recognizeTextRegionsForRemoval(uri)

                if (blocks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            getApplication(),
                            "Không tìm thấy vùng text OCR trên ảnh",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    _uiState.update { it.copy(removingTextProgress = "") }
                    return@launch
                }

                // Tạo preview bitmap với mask overlay từ OCR bounds gốc
                _uiState.update { it.copy(removingTextProgress = "Đang tạo preview...") }
                val previewBitmap = com.example.ocrmanga.utils.TextRemovalHelper.createMaskPreview(
                    getApplication(), uri, blocks
                )

                if (previewBitmap != null) {
                    _uiState.update {
                        it.copy(
                            showTextRemovalPreview = true,
                            textRemovalPreviewUri = uri,
                            textRemovalPreviewBitmap = previewBitmap,
                            textRemovalPreviewBlocks = blocks,
                            removingTextProgress = ""
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            getApplication(),
                            "Lỗi khi tạo preview mask",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    _uiState.update { it.copy(removingTextProgress = "") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing text removal preview", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        getApplication(),
                        "Lỗi: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    // Bước 2: User xác nhận xóa text sau khi xem preview
    fun confirmTextRemoval() {
        val state = _uiState.value
        val uri = state.textRemovalPreviewUri ?: return
        val previewBlocks = state.textRemovalPreviewBlocks
        // Recycle preview bitmap
        state.textRemovalPreviewBitmap?.recycle()
        _uiState.update {
            it.copy(
                showTextRemovalPreview = false,
                textRemovalPreviewUri = null,
                textRemovalPreviewBitmap = null,
                textRemovalPreviewBlocks = emptyList()
            )
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isRemovingText = true) }

                val blocks = previewBlocks
                if (blocks.isEmpty()) {
                    _uiState.update { it.copy(isRemovingText = false) }
                    return@launch
                }

                val onProgress: (String) -> Unit = { progress ->
                    _uiState.update { it.copy(removingTextProgress = progress) }
                }
                val resultUri = com.example.ocrmanga.utils.TextRemovalHelper.removeTextFromImage(
                    getApplication(),
                    uri,
                    blocks,
                    onProgress
                )

                if (resultUri != null) {
                    replaceImageUri(uri, resultUri, persist = false)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            getApplication(),
                            "Đã tạm xóa text gốc (chưa lưu). Lưu truyện hoặc chờ autosave để ghi vào DB.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            getApplication(),
                            "Lỗi khi xóa text gốc",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing original text", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        getApplication(),
                        "Lỗi: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                _uiState.update { it.copy(isRemovingText = false, removingTextProgress = "") }
            }
        }
    }

    // Hủy preview xóa text
    fun cancelTextRemovalPreview() {
        _uiState.value.textRemovalPreviewBitmap?.recycle()
        _uiState.update {
            it.copy(
                showTextRemovalPreview = false,
                textRemovalPreviewUri = null,
                textRemovalPreviewBitmap = null,
                textRemovalPreviewBlocks = emptyList()
            )
        }
    }

    fun setTextRemovalMode(enabled: Boolean) {
        _uiState.update { it.copy(isTextRemovalMode = enabled) }
    }

    fun removeTextWithMask(uri: Uri, maskBitmap: Bitmap) {
        viewModelScope.launch {
            try {
                // Call helper
                val resultUri = com.example.ocrmanga.utils.TextRemovalHelper.removeTextWithMask(
                    getApplication(),
                    uri,
                    maskBitmap
                )

                if (resultUri != null) {
                    replaceImageUri(uri, resultUri, persist = false)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Đã xóa vùng chọn.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Lỗi khi xóa vùng chọn.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing text with mask", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // Dịch lại 1 ảnh (re-translate single image)
    // IMPORTANT: This will DELETE all existing translations for this image before creating new ones
    fun retranslateImage(uri: Uri, mode: TranslationMode, reuseExistingOcr: Boolean = false, existingBlocks: List<TextBlockInfo>? = null) {
        viewModelScope.launch {
            val hasApiKeys = when(mode) {
                TranslationMode.GEMINI -> hasGeminiApiKeys()
                TranslationMode.MISTRAL -> hasMistralApiKeys()
                TranslationMode.ZAI -> hasZAiApiKeys()
                else -> true
            }

            if (!hasApiKeys) {
                withContext(Dispatchers.Main) {
                    val msg = when(mode) {
                        TranslationMode.GEMINI -> "Không có API key Gemini. Vui lòng thêm trong cài đặt."
                        TranslationMode.MISTRAL -> "Không có API key Mistral. Vui lòng thêm trong cài đặt."
                        TranslationMode.ZAI -> "Không có API key Z.AI. Vui lòng thêm trong cài đặt."
                        else -> "Không có API key. Vui lòng thêm trong cài đặt."
                    }
                    Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            
            // Đánh dấu bản dịch cũ là pending_delete trước khi dịch mới
            val imageId = uriToImageId[uri]
            if (imageId != null) {
                try {
                    databaseHelper.markTranslationsAsPendingDelete(imageId)
                    ////Log.i(TAG, "[RETRANSLATE] Marked old translations as pending_delete for imageId=$imageId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to mark pending delete for imageId=$imageId", e)
                }
            }
            
            // Cập nhật trạng thái dịch - bắt đầu quét ảnh
            updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.SCANNING)
            _uiState.update { it.copy(
                translatedStatus = it.translatedStatus + (uri to false),
                translatedTexts = it.translatedTexts + (uri to ("" to emptyList())) // Clear old data immediately
            ) }

            if (mode == TranslationMode.OFF) {
                // User chọn OFF → XÓA HOÀN TOÀN tất cả translations của ảnh này khỏi DB
                if (imageId != null) {
                    try {
                        // Xóa tất cả translations cho image này
                        val db = databaseHelper.writableDatabase
                        val deletedCount = db.delete("translations", "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
                        //Log.i(TAG, "[RETRANSLATE-OFF] Deleted $deletedCount translations from DB for imageId=$imageId")
                        
                        // Xóa luôn các image_blocks
                        try {
                            databaseHelper.deleteBlocksForImage(imageId)
                            //Log.i(TAG, "[RETRANSLATE-OFF] Deleted image_blocks for imageId=$imageId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete image_blocks for imageId=$imageId", e)
                        }
                        
                        // Đánh dấu ảnh là chưa dịch trong TABLE_IMAGES (original_text giờ lưu trong translations, không cần clear ở đây)
                        val imageValues = android.content.ContentValues().apply {
                            put(DatabaseHelper.COLUMN_IS_TRANSLATED, 0)
                        }
                        db.update(DatabaseHelper.TABLE_IMAGES, imageValues, "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
                        //Log.i(TAG, "[RETRANSLATE-OFF] Marked image as untranslated in TABLE_IMAGES for imageId=$imageId")
                        
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
                    
                    // Lấy bản dịch của ảnh trước để tham khảo (nếu dịch bằng Gemini/Mistral/ZAI)
                    // Tìm ảnh GẦN NHẤT đã được dịch trước ảnh hiện tại (không chỉ ảnh liền kề)
                    val previousTranslation: List<TextBlockInfo>? = if (mode == TranslationMode.GEMINI || mode == TranslationMode.MISTRAL || mode == TranslationMode.ZAI) {
                        val imageUris = uiState.value.imageUris
                        val currentIndex = imageUris.indexOf(uri)
                        var foundTranslation: List<TextBlockInfo>? = null
                        if (currentIndex > 0) {
                            for (i in (currentIndex - 1) downTo 0) {
                                val prevUri = imageUris[i]
                                val translation = uiState.value.translatedTexts[prevUri]?.second
                                if (translation != null && translation.isNotEmpty()) {
                                    foundTranslation = translation
                                    //Log.i(TAG, "[RETRANSLATE-PREV] Tìm thấy bản dịch tham khảo từ ảnh index=$i")
                                    break
                                }
                            }
                        }
                        foundTranslation
                    } else null
                    
                    // If this image has a stored imageId in DB, prefer the canonical DB-stored URI
                    val canonicalUri = try {
                        val imgId = uriToImageId[uri]
                        if (imgId != null) {
                            val db = databaseHelper.readableDatabase
                            val cur = db.rawQuery(
                                "SELECT ${DatabaseHelper.COLUMN_IMAGE_URI} FROM ${DatabaseHelper.TABLE_IMAGES} WHERE ${DatabaseHelper.COLUMN_IMAGE_ID} = ?",
                                arrayOf(imgId.toString())
                            )
                            val stored = if (cur.moveToFirst()) Uri.parse(cur.getString(0)) else null
                            cur.close()
                            stored ?: uri
                        } else uri
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query canonical URI for $uri", e)
                        uri
                    }

                    //Log.i(TAG, "Calling translateImage for uri=$uri (canonical=$canonicalUri, imageId=${uriToImageId[uri]}) mode=$mode reuseExistingOcr=$reuseExistingOcr")

                    // Xóa cache cũ của ảnh này để đảm bảo nó chạy lại quá trình dịch/OCR mới nhất
                    translationRepository.clearCacheForImage(canonicalUri, mode)

                    val result = translationRepository.translateImage(canonicalUri, mode, statusCallback, previousTranslation, isAncientMode = uiState.value.isAncientTranslationMode, reuseExistingBlocks = if (reuseExistingOcr) existingBlocks else null)
                    
                    ////Log.i(TAG, "[RETRANSLATE] Translation completed: uri=$uri, originalText=${result.first.take(50)}, blocks=${result.second.size}")
                    
                    // Ensure blocks have overlay/text colors set similarly to queued translations
                    val (originalText, blocks) = result
                    
                    if (blocks.isEmpty()) {
                        Log.w(TAG, "[RETRANSLATE] WARNING: No blocks returned from translation!")
                    }
                    
                    val fixedBlocks = blocks.map { block ->
                        val baseOverlay = block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()
                        val textColor = block.customTextColor ?: block.originalTextColor ?: computeDefaultTextColor(baseOverlay, block.averageBackgroundColor)
                        block.copy(
                            customOverlayColor = baseOverlay,
                            customTextColor = textColor,
                            // Set applyMerge = true khi retranslate để áp dụng logic chống chồng lấn
                            applyMerge = true
                        )
                    }

                    try {
                        fixedBlocks.forEachIndexed { i, fb ->
                            val origHex = fb.originalTextColor?.let { String.format("#%08X", it) } ?: "null"
                            val custHex = fb.customTextColor?.let { String.format("#%08X", it) } ?: "null"
                            ////Log.i(TAG, "[RETRANSLATE] Block #$i: origColor=$origHex customColor=$custHex text='${fb.text.take(40)}'")
                        }
                    } catch (_: Exception) { }
                    
                    ////Log.i(TAG, "[RETRANSLATE] About to update UI state with ${fixedBlocks.size} blocks")
                    
                    // Không tự động tối ưu hóa overlay nữa theo yêu cầu người dùng
                    // val optimizedBlocks = autoOptimizeOverlay(uri, fixedBlocks)

                    withContext(Dispatchers.Main) {
                        // Sử dụng updateTranslatedBlocks để đồng bộ logic state và tránh tự động mở edit mode
                        updateTranslatedBlocks(uri, fixedBlocks, reopenEditor = false, originalText = originalText)
                    }

                    ////Log.i(TAG, "[RETRANSLATE] UI state updated successfully. translationVersion=${_uiState.value.translationVersion}")
                    
                    // Cập nhật trạng thái: hoàn tất
                    updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.COMPLETED)
                    // Delay ngắn để hiển thị trạng thái hoàn tất trước khi xóa
                    delay(1000)
                    clearTranslationStatus(uri)
                    
                    // Mark as dirty and set DB change flag if this image belongs to a saved room
                    dirtyUris.add(uri)
                    val rid = _uiState.value.roomId
                    ////Log.i(TAG, "[RETRANSLATE] Checking auto-save: uri=$uri imageId=$imageId roomId=$rid")
                    if (rid != null && imageId != null) {
                        try {
                            val numChanged = databaseHelper.markImageChanged(imageId, rid)
                            ////Log.i(TAG, "[RETRANSLATE] After markImageChanged: numChanged=$numChanged for imageId=$imageId")
                            if (numChanged >= 5) {
                                ////Log.i(TAG, "[RETRANSLATE] Threshold reached! Calling maybeAutoSaveChangedImages")
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
                            //Log.i(TAG, "[RETRANSLATE-FAIL] Cleared pending_delete status for imageId=$imageId")
                        } catch (ex: Exception) {
                            Log.w(TAG, "Failed to clear pending delete after translation failure for imageId=$imageId", ex)
                        }
                    }
                }
            }
        }
    }

    // Thêm hàm mới để cập nhật translatedTexts cho một uri cụ thể (sửa lỗi unresolved reference)
    fun updateTranslatedBlocks(uri: Uri, blocks: List<TextBlockInfo>, reopenEditor: Boolean? = null, originalText: String? = null) {
        // Get current blocks to check if there's any actual change
        val current = _uiState.value.translatedTexts[uri] ?: ("" to emptyList())
        val currentBlocks = current.second
        val finalOriginalText = originalText ?: current.first

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
                //Log.i(TAG, "[UPDATE] Block text='${finalBlock.text}' rotation=$rot for uri=$uri")
            }
            finalBlock
        }

        // Check if blocks actually changed (size or content)
        val hasChanges = currentBlocks.size != updatedBlocks.size ||
            current.first != finalOriginalText ||
            currentBlocks.zip(updatedBlocks).any { (old, new) ->
                old.text != new.text ||
                old.bounds != new.bounds ||
                old.rotation != new.rotation ||
                old.overlayRotation != new.overlayRotation ||
                old.fontSize != new.fontSize ||
                old.lineSpacing != new.lineSpacing ||
                old.overlayInset != new.overlayInset ||
                old.overlayInsetHorizontal != new.overlayInsetHorizontal ||
                old.overlayInsetVertical != new.overlayInsetVertical ||
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
                old.shapeType != new.shapeType ||
                old.textAlign != new.textAlign ||
                old.textGradientColors != new.textGradientColors ||
                old.textGradientOffsets != new.textGradientOffsets ||
                old.textGradientType != new.textGradientType
            }

        // Only mark as dirty and changed if there are actual changes
        if (!hasChanges) {
            // Even if no changes, we must mark as translated and increment version
            // so UI/JSON export knows scanning is finished
            _uiState.update {
                it.copy(
                    translatedStatus = it.translatedStatus + (uri to true),
                    translationVersion = it.translationVersion + 1
                )
            }
            return
        }

        val newPair = finalOriginalText to updatedBlocks
        // Chỉ tự động mở editor nếu chưa có blocks và không được set explicit false
        val shouldReopenEditor = reopenEditor ?: (currentBlocks.isEmpty() && blocks.isNotEmpty())
        _uiState.update {
            it.copy(
                translatedTexts = it.translatedTexts + (uri to newPair),
                translatedStatus = it.translatedStatus + (uri to true),
                translationEnabled = true, // Ensure UI shows translations immediately after manual edit
                translationVersion = it.translationVersion + 1, // Force UI update
                recentlySavedUris = it.recentlySavedUris + uri, // Mark uri so ImageViewer can apply blocks immediately
                // Nếu explicit false, xóa khỏi list reopen. Nếu true thì thêm vào.
                reopenEditorUris = if (reopenEditor == false) it.reopenEditorUris - uri
                                   else if (shouldReopenEditor) it.reopenEditorUris + uri
                                   else it.reopenEditorUris
            )
        }
        // Log old vs new text colors / gradients for debugging persistence issues
        try {
            updatedBlocks.forEachIndexed { idx, newBlock ->
                val oldBlock = currentBlocks.getOrNull(idx)
                
                // Chi tiết màu text
                val oldTextColor = oldBlock?.customTextColor
                val newTextColor = newBlock.customTextColor
                val oldColorHex = oldTextColor?.let { String.format("#%08X", it) } ?: "null"
                val newColorHex = newTextColor?.let { String.format("#%08X", it) } ?: "null"
                
                // Chi tiết Gradient
                val oldGrad = oldBlock?.textGradientColors
                val newGrad = newBlock.textGradientColors
                val oldGradStr = oldGrad?.joinToString(",") { c -> String.format("#%08X", c) } ?: "null"
                val newGradStr = newGrad?.joinToString(",") { c -> String.format("#%08X", c) } ?: "null"
                val oldGradType = oldBlock?.textGradientType ?: 0
                val newGradType = newBlock.textGradientType
                
                //Log.i(TAG, "[SAVE-BLOCK-COLORS] Block[$idx] uri=$uri")
                //Log.i(TAG, "  -> TEXT COLOR: $oldColorHex -> $newColorHex")
                //Log.i(TAG, "  -> GRADIENT: $oldGradStr (Type:$oldGradType) -> $newGradStr (Type:$newGradType)")
                
                if (oldGrad != newGrad || oldGradType != newGradType) {
                    //Log.i(TAG, "  -> GRADIENT CHANGED detected for block $idx")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed logging color comparison after updateTranslatedBlocks for uri=$uri", e)
        }

        // Mark this uri as dirty (edited) so later saveRoom can update only changed images
        dirtyUris.add(uri)
        // If this image belongs to a saved room, also set image-level is_translated=1 immediately (temporary)
        val rid = _uiState.value.roomId
        val imageId = uriToImageId[uri]
        if (imageId != null) {
            try {
                val db = databaseHelper.writableDatabase
                val values = android.content.ContentValues().apply { put(DatabaseHelper.COLUMN_IS_TRANSLATED, 1) }
                db.update(DatabaseHelper.TABLE_IMAGES, values, "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
                //Log.i(TAG, "Marked image as temporarily translated in TABLE_IMAGES for imageId=$imageId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set is_translated in DB for imageId=$imageId", e)
            }
        }

        // Also mark DB change flag if this URI is associated with a saved room so auto-save/count works
        if (rid != null && imageId != null) {
            try {
                val numChanged = databaseHelper.markImageChanged(imageId, rid)
                //Log.i(TAG, "Updated translated blocks for image: $uri, total changed images: $numChanged")
                if (numChanged >= 5) {
                    //Log.i(TAG, "Triggering auto-save after editing image: $uri, changed images: $numChanged")
                    maybeAutoSaveChangedImages(rid)
                }
            } catch (e: Exception) { Log.w(TAG, "Failed to markImageChanged for imageId=$imageId", e) }
        }
        ////Log.i(TAG, "Đã cập nhật blocks bản dịch cho ảnh $uri với ${updatedBlocks.size} blocks")
        updatedBlocks.forEachIndexed { idx, block ->
            ////Log.i(TAG, "[UPDATE] Block[$idx] rotation=${block.rotation} text='${block.text}' uri=$uri")
        }
    }

    fun clearRecentlySavedUri(uri: android.net.Uri) {
        _uiState.update { it.copy(recentlySavedUris = it.recentlySavedUris - uri) }
    }

    fun clearReopenEditorUri(uri: android.net.Uri) {
        _uiState.update { it.copy(reopenEditorUris = it.reopenEditorUris - uri) }
    }

    fun updateGlobalFont(fontName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentTranslated = _uiState.value.translatedTexts
            val newTranslated = currentTranslated.toMutableMap()
            val urisToUpdate = mutableListOf<Uri>()

            currentTranslated.forEach { (uri, pair) ->
                val (originalText, blocks) = pair
                if (blocks.isNotEmpty()) {
                    val newBlocks = blocks.map { block ->
                        block.copy(fontFamily = fontName)
                    }
                    
                    // Check if actually changed (simple check)
                    val hasChange = blocks.any { it.fontFamily != fontName }

                    if (hasChange) {
                        newTranslated[uri] = originalText to newBlocks
                        urisToUpdate.add(uri)
                    }
                }
            }
            
            if (urisToUpdate.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Không có bản dịch nào cần cập nhật font.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                _uiState.update { 
                     it.copy(translatedTexts = newTranslated, translationVersion = it.translationVersion + 1)
                }
                
                // Mark dirty and DB changes
                val rid = _uiState.value.roomId
                urisToUpdate.forEach { uri ->
                    dirtyUris.add(uri)
                    val imageId = uriToImageId[uri]
                    if (rid != null && imageId != null) {
                        databaseHelper.markImageChanged(imageId, rid)
                    }
                }
                Toast.makeText(getApplication(), "Đã cập nhật font cho ${urisToUpdate.size} trang!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Áp dụng kiểu overlay cho toàn bộ truyện
     */
    fun applyGlobalOverlayStyle(style: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentTranslated = _uiState.value.translatedTexts
            val newTranslated = currentTranslated.toMutableMap()
            val urisToUpdate = mutableListOf<Uri>()

            currentTranslated.forEach { (uri, pair) ->
                val (originalText, blocks) = pair
                if (blocks.isNotEmpty()) {
                    val newBlocks = if (style == "SMART_AUTO") {
                        autoOptimizeOverlay(uri, blocks)
                    } else {
                        blocks.map { block ->
                            when (style) {
                                "CLASSIC" -> block.copy(
                                    overlayAlpha = 1.0f,
                                    shapeType = 0, // Rect
                                    overlayInsetHorizontal = 0f,
                                    overlayInsetVertical = 0f
                                )
                                "BUBBLES" -> block.copy(
                                    overlayAlpha = 0.75f,
                                    shapeType = 1, // Oval
                                    overlayInsetHorizontal = 0f,
                                    overlayInsetVertical = 0f
                                )
                                "TRANSPARENT" -> block.copy(
                                    overlayAlpha = 0.0f,
                                    shapeType = 0,
                                    overlayInsetHorizontal = 0f,
                                    overlayInsetVertical = 0f
                                )
                                "SMART_FIT" -> block.copy(
                                    overlayAlpha = 1.0f,
                                    shapeType = 1, // Oval
                                    overlayInsetHorizontal = 4f,
                                    overlayInsetVertical = 4f
                                )
                                else -> block
                            }
                        }
                    }
                    newTranslated[uri] = originalText to newBlocks
                    urisToUpdate.add(uri)
                }
            }

            if (urisToUpdate.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _uiState.update { 
                        it.copy(translatedTexts = newTranslated, translationVersion = it.translationVersion + 1)
                    }
                    
                    val rid = _uiState.value.roomId
                    urisToUpdate.forEach { uri ->
                        dirtyUris.add(uri)
                        val imageId = uriToImageId[uri]
                        if (rid != null && imageId != null) {
                            databaseHelper.markImageChanged(imageId, rid)
                        }
                    }
                    Toast.makeText(getApplication(), "Đã tối ưu hiển thị cho ${urisToUpdate.size} trang.", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Không có trang nào cần cập nhật.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Tối ưu hiển thị overlay cho một ảnh cụ thể
     */
    fun optimizeImageOverlay(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentPair = _uiState.value.translatedTexts[uri] ?: return@launch
            val (originalText, blocks) = currentPair
            if (blocks.isEmpty()) return@launch

            val optimizedBlocks = autoOptimizeOverlay(uri, blocks, forceSolid = false)

            withContext(Dispatchers.Main) {
                _uiState.update { state ->
                    val newMap = state.translatedTexts.toMutableMap()
                    newMap[uri] = originalText to optimizedBlocks
                    state.copy(
                        translatedTexts = newMap,
                        translationVersion = state.translationVersion + 1
                    )
                }

                dirtyUris.add(uri)
                val rid = _uiState.value.roomId
                val imageId = uriToImageId[uri]
                if (rid != null && imageId != null) {
                    databaseHelper.markImageChanged(imageId, rid)
                }
            }
        }
    }

    fun openBulkExternalTranslationDialog(reuseExistingOcr: Boolean = true) {
        _uiState.update { it.copy(
            showExternalTranslationDialog = true,
            isBulkExternalTranslation = true,
            externalTranslationUri = null,
            translationMode = TranslationMode.EXTERNAL,
            translationEnabled = true
        ) }

        // Kiểm tra xem có ảnh nào chưa được quét/dịch không (bao gồm cả ảnh chưa load hết - Lazy Loading)
        val allUris = _uiState.value.imageUris + _uiState.value.remainingImages
        val imagesToScan = if (reuseExistingOcr) {
            allUris.filter { !(_uiState.value.translatedStatus[it] ?: false) }
        } else {
            allUris
        }
        if (imagesToScan.isNotEmpty()) {
            runBulkOcrScanning(imagesToScan)
        }
    }

    private fun runBulkOcrScanning(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val total = uris.size
            _uiState.update { it.copy(bulkScanningProgress = "Đang chuẩn bị quét $total trang...") }

            uris.chunked(2).forEachIndexed { index, chunk ->
                if (!isActive) return@launch
                val processedCount = index * 2
                _uiState.update { it.copy(bulkScanningProgress = "Đang quét $processedCount/$total trang...") }

                // Chạy song song 2 ảnh trong chunk
                chunk.map { uri ->
                    async { retranslateImageSync(uri, TranslationMode.OCR) }
                }.awaitAll()
            }

            _uiState.update { it.copy(bulkScanningProgress = "") }
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Đã hoàn tất quét OCR cho $total trang.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun retranslateImageSync(uri: Uri, mode: TranslationMode) {
        updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.SCANNING)
        _uiState.update { state ->
            state.copy(
                translatedStatus = state.translatedStatus + (uri to false),
                translatedTexts = state.translatedTexts + (uri to ("" to emptyList())) // Clear old data
            )
        }
        try {
            //Log.i(TAG, "[BULK-OCR] Scanning $uri")
            val result = translationRepository.translateImage(uri, mode)
            val originalText = result.first
            val blocks = result.second

            withContext(Dispatchers.Main) {
                // Tắt tự động mở editor khi quét hàng loạt
                updateTranslatedBlocks(uri, blocks, reopenEditor = false)

                val rid = _uiState.value.roomId
                val imageId = uriToImageId[uri]
                if (rid != null && imageId != null) {
                    databaseHelper.markImageChanged(imageId, rid)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[BULK-OCR] Error scanning $uri", e)
        } finally {
            clearTranslationStatus(uri)
        }
    }

    fun openExternalTranslationDialog(uri: android.net.Uri, reuseExistingOcr: Boolean = true) {
        _uiState.update { it.copy(
            showExternalTranslationDialog = true, 
            externalTranslationUri = uri, 
            isBulkExternalTranslation = false,
            translationMode = TranslationMode.EXTERNAL,
            translationEnabled = true
        ) }

        // Nếu chưa được quét hoặc yêu cầu OCR mới, tiến hành quét
        val isAlreadyScanned = _uiState.value.translatedStatus[uri] ?: false
        if (!isAlreadyScanned || !reuseExistingOcr) {
            retranslateImage(uri, TranslationMode.OCR)
        }
    }

    fun closeExternalTranslationDialog() {
        _uiState.update { it.copy(showExternalTranslationDialog = false, externalTranslationUri = null, isBulkExternalTranslation = false, bulkScanningProgress = "") }
    }

    fun exportBlocksToJson(uri: android.net.Uri?): String {
        val uris = if (uri != null) listOf(uri) else (_uiState.value.imageUris + _uiState.value.remainingImages)

        if (uri != null) {
            // Single image export (legacy format for compatibility)
            val blocks = getExistingBlocksForUri(uri)
            if (blocks.isEmpty()) return "[]"
            val exportList = blocks.mapIndexed { index, block ->
                mapOf(
                    "index" to index + 1,
                    "original_text" to (block.originalText ?: block.text),
                    "bounds" to mapOf(
                        "left" to block.bounds.left,
                        "top" to block.bounds.top,
                        "right" to block.bounds.right,
                        "bottom" to block.bounds.bottom
                    )
                )
            }
            return GsonBuilder().setPrettyPrinting().create().toJson(exportList)
        } else {
            // Bulk export (new format with image_id)
            val result = uris.mapIndexed { imgIdx, imageUri ->
                val blocks = getExistingBlocksForUri(imageUri)
                mapOf(
                    "image_id" to imgIdx + 1,
                    "blocks" to blocks.mapIndexed { blockIdx, block ->
                        mapOf(
                            "index" to blockIdx + 1,
                            "original_text" to (block.originalText ?: block.text),
                            "bounds" to mapOf(
                                "left" to block.bounds.left,
                                "top" to block.bounds.top,
                                "right" to block.bounds.right,
                                "bottom" to block.bounds.bottom
                            )
                        )
                    }
                )
            }
            return GsonBuilder().setPrettyPrinting().create().toJson(result)
        }
    }

    fun getExternalTranslationPrompt(uri: android.net.Uri?): String {
        val isBulk = uri == null
        val json = exportBlocksToJson(uri)
        val isAncient = uiState.value.isAncientTranslationMode

        val ancientInstruction = if (isAncient) {
            """
            [CHẾ ĐỘ CỔ TRANG - ƯU TIÊN CAO NHẤT]
            - Bối cảnh: Cổ đại, tiên hiệp, kiếm hiệp, lịch sử.
            - Văn phong: Sử dụng từ Hán Việt trang trọng, nhã nhặn hoặc uy dũng tùy nhân vật. Tuyệt đối tránh từ ngữ hiện đại, từ lóng gen Z.
            - Xưng hô (Dialogue Pronouns):
                + Ngôi thứ nhất: Ta, tại hạ, bần đạo, lão phu, bổn tọa, bổn cung, trẫm, thần, muội, tỷ, huynh.
                + Ngôi thứ hai: Ngươi, các hạ, vị này, huynh đệ, nương tử, phu quân, cô nương, công tử, đại hiệp, tiểu hữu, chư vị.
                + Ngôi thứ ba: Hắn, thị, y, bọn chúng, chúng nhân.
            - CẤM DÙNG: anh, em, cậu, tớ, mình, bạn, mày, tao (trừ khi có quan hệ gia đình cực kỳ gần gũi như huynh-muội).
            - SFX: Chuyển sang âm Hán Việt (ví dụ: "Bùm" -> "Oanh", "Xoẹt" -> "Xoát", "Vèo" -> "Tốc", "Choảng" -> "Keng").
            """.trimIndent()
        } else ""

        return if (!isBulk) {
            """
                Bạn là một phiên dịch viên chuyên nghiệp chuyên về manga.
                $ancientInstruction
                Hãy dịch các đoạn văn bản này sang tiếng Việt theo phong cách truyện tranh manga gần gũi với ngôn ngữ nói của người Việt Nam, giữ nguyên cấu trúc JSON và số thứ tự (index).
                Chỉ trả về file JSON duy nhất, không thêm giải thích.

                Cấu trúc yêu cầu:
                [
                  {
                    "index": 1,
                    "translated_text": "bản dịch ở đây"
                  },
                  ...
                ]

                Dữ liệu gốc:
                $json
            """.trimIndent()
        } else {
            """
                Bạn là một phiên dịch viên chuyên nghiệp chuyên về manga.
                $ancientInstruction
                Dưới đây là dữ liệu văn bản từ nhiều trang truyện tranh (được đánh dấu bằng image_id).
                Hãy dịch các đoạn văn bản này sang tiếng Việt theo phong cách truyện tranh manga gần gũi với ngôn ngữ nói của người Việt Nam, giữ nguyên cấu trúc JSON, image_id và index của từng block.
                Chỉ trả về file JSON duy nhất, không thêm giải thích.

                Cấu trúc yêu cầu:
                [
                  {
                    "image_id": 1,
                    "blocks": [
                      {
                        "index": 1,
                        "translated_text": "bản dịch ở đây"
                      },
                      ...
                    ]
                  },
                  ...
                ]

                Dữ liệu gốc:
                $json
            """.trimIndent()
        }
    }

    fun importTranslatedJson(uri: android.net.Uri?, json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gson = Gson()
                if (uri != null) {
                    // Single image import
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val importedData = gson.fromJson<List<Map<String, Any>>>(json, type)
                    processImportSingle(uri, importedData)
                    
                    withContext(Dispatchers.Main) {
                        closeExternalTranslationDialog()
                    }
                } else {
                    // Bulk import
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val bulkData = gson.fromJson<List<Map<String, Any>>>(json, type)
                    val allUris = _uiState.value.imageUris + _uiState.value.remainingImages
                    val total = bulkData.size

                    // HIỆU ỨNG POPUP: Đang chuẩn bị...
                    _uiState.update { it.copy(bulkScanningProgress = "Đang chuẩn bị áp dụng bản dịch...") }

                    try {
                        bulkData.chunked(2).forEachIndexed { index, chunk ->
                            if (!isActive) return@launch
                            val processedCount = index * 2
                            _uiState.update { it.copy(bulkScanningProgress = "Đang áp dụng bản dịch $processedCount/$total trang...") }

                            coroutineScope {
                                chunk.map { pageData ->
                                    async {
                                        val imageId = (pageData["image_id"] as? Double)?.toInt() ?: return@async
                                        val pageUri = allUris.getOrNull(imageId - 1) ?: return@async
                                        val blocksData = pageData["blocks"] as? List<Map<String, Any>> ?: return@async
                                        processImportSingle(pageUri, blocksData, isBulk = true)
                                    }
                                }.awaitAll()
                            }
                        }
                    } finally {
                        _uiState.update { it.copy(bulkScanningProgress = "") }
                    }

                    withContext(Dispatchers.Main) {
                        // Lưu toàn bộ thay đổi sau khi nhập hàng loạt
                        saveCurrentRoom()
                        Toast.makeText(getApplication(), "Đã nhập bản dịch hàng loạt thành công", Toast.LENGTH_SHORT).show()
                        closeExternalTranslationDialog()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi nhập JSON bản dịch", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Lỗi định dạng JSON: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun processImportSingle(uri: Uri, importedData: List<Map<String, Any>>, isBulk: Boolean = false) {
        var currentBlocks = getExistingBlocksForUri(uri)
        val isAlreadyScanned = _uiState.value.translatedStatus[uri] ?: false

        if (currentBlocks.isEmpty() && !isAlreadyScanned) {
            //Log.i("ViewerViewModel", "[IMPORT-JSON] No blocks found and not scanned yet, triggering OCR for $uri")
            if (!isBulk) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Đang quét OCR để áp dụng bản dịch...", Toast.LENGTH_SHORT).show()
                }
            }
            val result = translationRepository.translateImage(uri, TranslationMode.OCR)
            currentBlocks = result.second

            // Nếu vẫn trống (không có text), vẫn phải update status để tránh quét lại vô tận
            if (currentBlocks.isEmpty()) {
                withContext(Dispatchers.Main) {
                    updateTranslatedBlocks(uri, emptyList(), reopenEditor = false)
                }
            }
        }

        if (currentBlocks.isEmpty()) {
            Log.w("ViewerViewModel", "[IMPORT-JSON] No blocks to apply translation for $uri")
            return
        }

        val newBlocks = currentBlocks.mapIndexed { index, block ->
            val blockIndex = index + 1

            // Tìm bản dịch khớp theo index trước
            var match = importedData.find { (it["index"] as? Double)?.toInt() == blockIndex }

            // Nếu không khớp index, thử khớp theo tọa độ (bounds) nếu có
            if (match == null) {
                match = importedData.find { data ->
                    val b = data["bounds"] as? Map<String, Double> ?: return@find false
                    val left = b["left"]?.toInt() ?: -1
                    val top = b["top"]?.toInt() ?: -1
                    val right = b["right"]?.toInt() ?: -1
                    val bottom = b["bottom"]?.toInt() ?: -1

                    // Kiểm tra xem tọa độ có khớp tương đối không (sai số 10 pixel)
                    Math.abs(block.bounds.left - left) < 15 &&
                    Math.abs(block.bounds.top - top) < 15 &&
                    Math.abs(block.bounds.right - right) < 15 &&
                    Math.abs(block.bounds.bottom - bottom) < 15
                }
                if (match != null) {
                    //Log.i("ViewerViewModel", "[IMPORT-JSON] Matched block index $blockIndex by coordinates for $uri")
                }
            }

            val translatedText = match?.get("translated_text") as? String

            if (translatedText != null) {
                block.copy(text = translatedText, originalText = block.originalText ?: block.text)
            } else {
                block
            }
        }

        withContext(Dispatchers.Main) {
            updateTranslatedBlocks(uri, newBlocks, reopenEditor = false)

            // Cập nhật status để UI biết đã có bản dịch
            _uiState.update {
                it.copy(translatedStatus = it.translatedStatus + (uri to true))
            }

            val rid = _uiState.value.roomId
            val imgId = uriToImageId[uri]

            if (rid != null && imgId != null) {
                // Đảm bảo đánh dấu là đã thay đổi để lưu trong DB
                databaseHelper.markImageChanged(imgId, rid)
            }

            if (!isBulk) {
                Toast.makeText(getApplication(), "Đã nhập bản dịch thành công", Toast.LENGTH_SHORT).show()
                closeExternalTranslationDialog()
                // Lưu ngay nếu là nhập đơn lẻ
                saveCurrentRoom()
            }
        }
    }

    private suspend fun autoOptimizeOverlay(uri: Uri, blocks: List<TextBlockInfo>, forceSolid: Boolean = false): List<TextBlockInfo> {
        val context = getApplication<Application>()
        val bitmap = try {
            val inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }

        val imgW = bitmap?.width ?: 360
        val imgH = bitmap?.height ?: 640

        val (optimizedBlocks, _) = com.example.ocrmanga.utils.OverlayOptimizer.analyzeAndOptimize(
            blocks, bitmap, imgW, imgH, forceSolid
        )

        return optimizedBlocks
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
        // Skip new triggers while an auto-save is running to avoid cancelling mid-reload
        if (!autoSaveInProgress.compareAndSet(false, true)) {
            //Log.i(TAG, "Auto-save already in progress, skipping")
            return
        }
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val changedIds = databaseHelper.getChangedImageIdsForRoom(roomId)
                //Log.i(TAG, "Auto-save check: ${changedIds.size} images marked as changed for room $roomId")
                if (changedIds.size >= 5) {
                    val mapping = mutableMapOf<Long, Pair<String, List<TextBlockInfo>>>()
                    val currentTranslated = _uiState.value.translatedTexts
                    currentTranslated.forEach { (uri, pair) ->
                        val imgId = uriToImageId[uri]
                        if (imgId != null && changedIds.contains(imgId)) {
                            mapping[imgId] = pair
                        }
                    }
                    //Log.i(TAG, "Auto-save: Will save ${mapping.size} images (threshold: 5, changed: ${changedIds.size})")
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
                            val savedTranslated = _uiState.value.translatedTexts
                            savedTranslated.forEach { (uri, _) ->
                                val imgId = uriToImageId[uri]
                                if (imgId != null && imgId in changedIds) {
                                    dirtyUris.remove(uri)
                                }
                            }
                            //Log.i(TAG, "Auto-saved ${mapping.size} changed images for room $roomId (threshold reached), cleared from dirtyUris; skip reload to keep UI stable")
                        } else {
                            Log.w(TAG, "Auto-save failed for room $roomId mappingSize=${mapping.size}")
                        }
                    }
                } else {
                    //Log.i(TAG, "Auto-save: Not enough changed images (${changedIds.size}/5)")
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
        ////Log.i(TAG, "Bắt đầu đếm thời gian dịch cho ảnh $imageIndex: $uri")
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
        ////Log.i(TAG, "Dừng bộ đếm thời gian dịch")
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
                ////Log.i(TAG, "Đã tải ${roomIds.size} ID truyện")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tải room IDs", e)
            }
        }
    }

    fun setImageUris(uris: List<Uri>, isNew: Boolean = false) {
        // Basic UI state reset for the provided URIs
        _uiState.update {
            it.copy(
                isLoading = true,
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
        }
        
        // Ensure loading stays visible for at least 300ms for visual continuity
        viewModelScope.launch {
            delay(300)
            _uiState.update { it.copy(isLoading = false) }
        }
        ////Log.i(TAG, "Đã đặt ${uris.size} URI ảnh, isNew: $isNew")
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
        // Không clear, chỉ add thêm vào để track tất cả ảnh mới
        newImageUris.addAll(newUris)
        //Log.i(TAG, "addNewImageUris: Added ${newUris.size} new URIs, total newImageUris=${newImageUris.size}")

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
        // Không clear, chỉ add thêm vào để track tất cả ảnh mới
        newImageUris.addAll(newUris)
        //Log.i(TAG, "addNewImageUrisAtStart: Added ${newUris.size} new URIs, total newImageUris=${newImageUris.size}")

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
        // Không clear, chỉ add thêm vào để track tất cả ảnh mới
        newImageUris.addAll(newUris)
        //Log.i(TAG, "addNewImageUrisAtIndex: Added ${newUris.size} new URIs at index $insertIndex, total newImageUris=${newImageUris.size}")

        // Only auto-translate if both translation is enabled AND auto-translate setting is ON
        if (uiState.value.translationEnabled && 
            uiState.value.translationMode != TranslationMode.OFF && 
            uiState.value.autoTranslateEnabled) {
            enqueueTranslation(newUris)
        }
    }

    // Vị trí scroll hiện tại, được set từ UI trước khi save/reload
    private var currentScrollIndex: Int = 0
    
    /**
     * Set vị trí scroll hiện tại từ UI. Gọi trước khi save để lưu lại vị trí.
     */
    fun setCurrentScrollIndex(index: Int) {
        currentScrollIndex = index
        //Log.d(TAG, "setCurrentScrollIndex: $index")
    }
    
    /**
     * Clear scroll index sau khi UI đã scroll đến vị trí
     */
    fun clearScrollToIndex() {
        _uiState.update { it.copy(scrollToIndexAfterReload = null) }
    }
    
    /**
     * Clear all in-memory caches and reload room from DB to ensure clean state.
     * Called after manual save or auto-save to free memory and sync with DB.
     */
    private suspend fun clearMemoryAndReloadRoom(roomId: Long) {
        // Lưu lại vị trí scroll để nhảy lại sau khi reload
        val scrollIndexToRestore = currentScrollIndex
        //Log.i(TAG, "clearMemoryAndReloadRoom: Clearing memory and reloading room $roomId from DB, scrollIndex=$scrollIndexToRestore")
        
        // 1) Clear in-memory translation repository cache
        try {
            translationRepository.clearSession()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear translationRepository session", e)
        }
        
        // 2) Clear tracking variables
        dirtyUris.clear()
        deletedTranslationUris.clear()
        removedImageIds.clear()
        newImageUris.clear()
        uriToImageId.clear()
        translationQueue.clear()
        
        // 3) Clear Coil image cache to free memory
        try {
            val context = getApplication<Application>()
            val imageLoader = coil.Coil.imageLoader(context)
            imageLoader.memoryCache?.clear()
            //Log.i(TAG, "Cleared Coil memory cache")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear Coil memory cache", e)
        }
        
        // 4) Clear UI state temporarily
        _uiState.update { currentState ->
            currentState.copy(
                imageUris = emptyList(),
                translatedTexts = emptyMap(),
                sourceLanguages = emptyMap(),
                translatedStatus = emptyMap(),
                remainingImages = emptyList()
            )
        }
        
        // 5) Force garbage collection to reclaim memory
        System.gc()
        
        // 6) Small delay to allow UI to update and GC to run
        delay(100)
        
        // 7) Reload room from DB with fresh state
        loadRoomInternal(roomId)
        
        // 8) Nếu scroll index vượt quá số ảnh đã load (BATCH_SIZE), load thêm ảnh
        val currentImageCount = _uiState.value.imageUris.size
        if (scrollIndexToRestore >= currentImageCount && _uiState.value.remainingImages.isNotEmpty()) {
            // Tính số ảnh cần load thêm
            val additionalNeeded = scrollIndexToRestore - currentImageCount + 1
            val batchesToLoad = (additionalNeeded + BATCH_SIZE - 1) / BATCH_SIZE // Ceiling division
            //Log.i(TAG, "clearMemoryAndReloadRoom: Need to load $batchesToLoad more batches to reach scroll index $scrollIndexToRestore")
            
            // Load các batch cần thiết
            repeat(batchesToLoad) {
                if (_uiState.value.remainingImages.isNotEmpty()) {
                    loadMoreImagesSync()
                }
            }
        }
        
        // 9) Set scroll index để UI nhảy đến vị trí trước khi reload
        //Log.i(TAG, "clearMemoryAndReloadRoom: Setting scrollToIndexAfterReload=$scrollIndexToRestore")
        _uiState.update { 
            val newState = it.copy(scrollToIndexAfterReload = scrollIndexToRestore)
            //Log.i(TAG, "clearMemoryAndReloadRoom: State updated, scrollToIndexAfterReload=${newState.scrollToIndexAfterReload}")
            newState
        }
        
        //Log.i(TAG, "clearMemoryAndReloadRoom: Completed reload of room $roomId, will scroll to index $scrollIndexToRestore (total images: ${_uiState.value.imageUris.size})")
    }
    
    /**
     * Synchronous version of loadMoreImages for use in clearMemoryAndReloadRoom
     */
    private suspend fun loadMoreImagesSync() {
        val remainingImages = _uiState.value.remainingImages
        if (remainingImages.isEmpty()) return

        val batch = remainingImages.take(BATCH_SIZE)
        val newRemaining = remainingImages.drop(BATCH_SIZE)
        val translatedStatus = mutableMapOf<Uri, Boolean>()

        // Use DatabaseHelper.getTranslationsForImages
        val translations = databaseHelper.getTranslationsForImages(batch)
        
        // Get translatedStatus and uriToImageId mapping
        val db = databaseHelper.readableDatabase
        batch.forEach { uri ->
            val cursor = db.rawQuery(
                """
                SELECT ${DatabaseHelper.COLUMN_IS_TRANSLATED}, ${DatabaseHelper.COLUMN_IMAGE_ID}
                FROM ${DatabaseHelper.TABLE_IMAGES} 
                WHERE ${DatabaseHelper.COLUMN_IMAGE_URI} = ?
                """, arrayOf(uri.toString())
            )
            if (cursor.moveToFirst()) {
                translatedStatus[uri] = cursor.getInt(0) == 1
                val imageId = cursor.getLong(1)
                uriToImageId[uri] = imageId
            }
            cursor.close()
        }

        _uiState.update {
            val existingUriStrings = it.imageUris.map { u -> u.toString() }.toSet()
            val newBatch = batch.filter { uri -> !existingUriStrings.contains(uri.toString()) }
            
            it.copy(
                imageUris = it.imageUris + newBatch,
                translatedTexts = it.translatedTexts + translations,
                translatedStatus = it.translatedStatus + translatedStatus,
                remainingImages = newRemaining,
                translationVersion = it.translationVersion + 1
            )
        }
        //Log.i(TAG, "loadMoreImagesSync completed: total imageUris=${_uiState.value.imageUris.size}")
    }

    /**
     * Internal function to load room from DB. Used by both loadRoom() and clearMemoryAndReloadRoom().
     */
    private suspend fun loadRoomInternal(roomId: Long) {
        _uiState.update { it.copy(isLoading = true) }
        delay(100) // Give UI time to show overlay
        try {
            // Clear all is_changed flags for this room to start fresh
            databaseHelper.clearAllChangedFlagsForRoom(roomId)
            
            // Clear pendingDelete status for all translations in this room to ensure blocks return to normal state
            databaseHelper.clearPendingDeleteStatusForRoom(roomId)
            
            // Clear tracking variables when loading a room
            dirtyUris.clear()
            deletedTranslationUris.clear()
            removedImageIds.clear()
            newImageUris.clear()
            uriToImageId.clear()
            isLoadingMore.set(false) // Reset loading guard
            
            // Load auto-translate setting for this room
            val autoTranslate = databaseHelper.getAutoTranslateSetting(roomId)
            // Load ancient/"cổ trang" translation setting for this room
            val ancientMode = databaseHelper.getAncientTranslationSetting(roomId)
            // Load room title
            val roomTitle = databaseHelper.getRoomTitle(roomId)
            
            // getMangaRoomOptimized uses batch queries (3 queries total instead of N+2)
            val (allImages, _, translations) = databaseHelper.getMangaRoomOptimized(roomId)
            
            //Log.i(TAG, "loadRoomInternal: Room $roomId has ${allImages.size} images after DB cleanup")
            
            // Sort images by numeric order in filename
            fun extractImageNumber(uri: Uri): Int {
                val filename = uri.lastPathSegment ?: return Int.MAX_VALUE
                val match = """image_(\d+)""".toRegex().find(filename)
                return match?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
            }
            
            val sortedImages = allImages.sortedBy { extractImageNumber(it) }
            
            // Load metadata for ALL images in the room to support bulk operations
            // while keeping the UI lazy-loading (initialBatch vs remainingImages)
            val db = databaseHelper.readableDatabase
            val cursor = db.rawQuery(
                """
                SELECT ${DatabaseHelper.COLUMN_IMAGE_ID}, ${DatabaseHelper.COLUMN_IMAGE_URI}, ${DatabaseHelper.COLUMN_IS_TRANSLATED}
                FROM ${DatabaseHelper.TABLE_IMAGES}
                WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ?
                ORDER BY ${DatabaseHelper.COLUMN_DISPLAY_ORDER}
                """, arrayOf(roomId.toString())
            )

            val allImageUris = mutableListOf<Uri>()
            val allTranslatedStatus = mutableMapOf<Uri, Boolean>()

            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val uriStr = cursor.getString(1)
                val isTranslated = cursor.getInt(2) == 1
                val uri = Uri.parse(uriStr)

                allImageUris.add(uri)
                allTranslatedStatus[uri] = isTranslated
                uriToImageId[uri] = imageId

                // Also map filename for convenience
                try {
                    val last = uri.lastPathSegment
                    if (!last.isNullOrBlank()) {
                        uriToImageId[Uri.fromParts("filename", last, null)] = imageId
                    }
                } catch (e: Exception) { /* ignore */ }
            }
            cursor.close()

            // Calculate initial batch and remaining for UI lazy loading
            val initialBatch = allImageUris.take(BATCH_SIZE)
            val remainingImages = allImageUris.drop(BATCH_SIZE)

            // Fix rotation and colors for blocks
            val fixedTranslations = translations.mapValues { (uri, pair) ->
                val (originalText, blocks) = pair
                val fixedBlocks = blocks.map { block ->
                    val withRotation = if (block.rotation == null) block.copy(rotation = 0f) else block
                    val baseOverlay = withRotation.customOverlayColor ?: 0xFFFFFFFF.toInt()
                    val textColor = withRotation.customTextColor ?: withRotation.originalTextColor ?: computeDefaultTextColor(baseOverlay, withRotation.averageBackgroundColor)
                    withRotation.copy(
                        customOverlayColor = baseOverlay, 
                        customTextColor = textColor,
                        applyMerge = false
                    )
                }
                originalText to fixedBlocks
            }
            
            // Combine database status with any translations found in the optimized load
            val finalStatusMap = allTranslatedStatus.toMutableMap()
            fixedTranslations.keys.forEach { uri ->
                finalStatusMap[uri] = true
            }

            val translationsForBatch: Map<Uri, Pair<String, List<TextBlockInfo>>> =
                fixedTranslations.filterKeys { uri -> uri in initialBatch }

            val sourceLangsForBatch: Map<Uri, String> = translationsForBatch.keys.associateWith { "zh" }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    imageUris = initialBatch,
                    translatedTexts = translationsForBatch,
                    translationEnabled = fixedTranslations.isNotEmpty(),
                    translationMode = if (fixedTranslations.isNotEmpty()) TranslationMode.OFFLINE else TranslationMode.OFF,
                    isTranslating = false,
                    roomId = roomId,
                    translatedStatus = finalStatusMap,
                    sourceLanguages = sourceLangsForBatch,
                    remainingImages = remainingImages,
                    translationVersion = it.translationVersion + 1,
                    autoTranslateEnabled = autoTranslate,
                    isAncientTranslationMode = ancientMode,
                    roomTitle = roomTitle
                )
            }
            //Log.i(TAG, "loadRoomInternal completed: initialBatch=${initialBatch.size} remainingImages=${remainingImages.size} total=${initialBatch.size + remainingImages.size}")
            lastLoadedRoomId = roomId
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi tải truyện $roomId", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Tải truyện thất bại!", Toast.LENGTH_SHORT).show()
            }
            _uiState.update { it.copy(isLoading = false) }
            lastLoadedRoomId = null
        }
    }

    fun loadRoom(roomId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            loadRoomInternal(roomId)
        }
    }

    // Guard against concurrent loadMoreImages calls
    private val isLoadingMore = AtomicBoolean(false)
    
    fun loadMoreImages() {
        val remainingImages = uiState.value.remainingImages
        if (remainingImages.isEmpty()) return
        
        // Prevent concurrent calls
        if (!isLoadingMore.compareAndSet(false, true)) {
            Log.w(TAG, "loadMoreImages: Already loading, skipping")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Bắt đầu trạng thái loading
                _uiState.update { it.copy(isLoadingMoreImages = true) }
                
                val batch = remainingImages.take(BATCH_SIZE)
                val newRemaining = remainingImages.drop(BATCH_SIZE)
                val translatedStatus = mutableMapOf<Uri, Boolean>()

                // Use DatabaseHelper.getTranslationsForImages - same logic as getMangaRoom
                // This ensures ALL properties (inset, overlayRotation, etc.) are loaded correctly
                val translations = databaseHelper.getTranslationsForImages(batch)
                
                // Get translatedStatus and uriToImageId mapping
                val db = databaseHelper.readableDatabase
                batch.forEach { uri ->
                    val cursor = db.rawQuery(
                        """
                        SELECT ${DatabaseHelper.COLUMN_IS_TRANSLATED}, ${DatabaseHelper.COLUMN_IMAGE_ID}
                        FROM ${DatabaseHelper.TABLE_IMAGES} 
                        WHERE ${DatabaseHelper.COLUMN_IMAGE_URI} = ?
                        """, arrayOf(uri.toString())
                    )
                    if (cursor.moveToFirst()) {
                        translatedStatus[uri] = cursor.getInt(0) == 1
                        val imageId = cursor.getLong(1)
                        uriToImageId[uri] = imageId
                    }
                    cursor.close()
                }

                _uiState.update {
                    // Filter out any URIs that already exist to prevent duplicates
                    val existingUriStrings = it.imageUris.map { u -> u.toString() }.toSet()
                    val newBatch = batch.filter { uri -> !existingUriStrings.contains(uri.toString()) }
                    
                    //Log.i(TAG, "loadMoreImages: batch=${batch.size} newBatch=${newBatch.size} existing=${it.imageUris.size} remaining=${newRemaining.size} translations=${translations.size}")
                    
                    it.copy(
                        imageUris = it.imageUris + newBatch,
                        translatedTexts = it.translatedTexts + translations,
                        translatedStatus = it.translatedStatus + translatedStatus,
                        remainingImages = newRemaining,
                        isLoadingMoreImages = false, // Kết thúc trạng thái loading
                        // Tăng translationVersion để force UI update blocks mới từ DB
                        translationVersion = it.translationVersion + 1
                    )
                }
                //Log.i(TAG, "loadMoreImages completed: total imageUris=${uiState.value.imageUris.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tải thêm ảnh", e)
                // Tắt loading ngay cả khi có lỗi
                _uiState.update { it.copy(isLoadingMoreImages = false) }
            } finally {
                isLoadingMore.set(false)
            }
        }
    }

    fun setTranslationMode(mode: TranslationMode, reuseExistingOcr: Boolean = false) {
        val currentMode = uiState.value.translationMode

        // Prevent switching to Gemini or Mistral if API keys are missing
        val hasApiKeys = when(mode) {
            TranslationMode.GEMINI -> hasGeminiApiKeys()
            TranslationMode.MISTRAL -> hasMistralApiKeys()
            TranslationMode.ZAI -> hasZAiApiKeys()
            else -> true
        }

        if (!hasApiKeys) {
            viewModelScope.launch(Dispatchers.Main) {
                val msg = when(mode) {
                    TranslationMode.GEMINI -> "Không có API key Gemini. Vui lòng thêm trong cài đặt."
                    TranslationMode.MISTRAL -> "Không có API key Mistral. Vui lòng thêm trong cài đặt."
                    else -> "Không có API key. Vui lòng thêm trong cài đặt."
                }
                Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
            }
            return
        }

        _uiState.update {
            it.copy(
                translationMode = mode,
                translationEnabled = mode != TranslationMode.OFF,
                lastTranslationMode = if (mode != TranslationMode.OFF) mode else it.lastTranslationMode,
                translationVersion = it.translationVersion + 1
            )
        }
        ////Log.i(TAG, "Chế độ dịch được đặt thành $mode")

        if (mode != TranslationMode.OFF) {
            val allUris = (uiState.value.imageUris + uiState.value.remainingImages).distinctBy { it.toString() }
            // If switching between different translation modes (OFFLINE <-> ONLINE <-> GEMINI)
            // or turning on translation for the first time, retranslate all images
            // Skip automatic retranslation for EXTERNAL mode as it's a manual process
            val shouldRetranslate = currentMode != mode &&
                    mode != TranslationMode.EXTERNAL &&
                    (currentMode == TranslationMode.OFF || allUris.isNotEmpty())

            if (shouldRetranslate) {
                if (allUris.isNotEmpty()) {
                    enqueueTranslation(allUris, reuseExistingOcr)
                }
                // ... (giữ nguyên logic cũ)
            } else if (mode != TranslationMode.EXTERNAL) {
                // Only translate new images that haven't been translated yet
                // Skip this for EXTERNAL mode
                val imagesToTranslate = allUris.filter { uri ->
                    !(uiState.value.translatedStatus[uri] ?: false)
                }
                if (imagesToTranslate.isNotEmpty()) {
                    enqueueTranslation(imagesToTranslate, reuseExistingOcr)
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
            ////Log.i(TAG, "Đã tắt dịch và reset trạng thái")
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
            val imageCount = (uiState.value.imageUris.size + uiState.value.remainingImages.size)
            ////Log.i(TAG, "Đang lưu truyện hiện tại với $imageCount ảnh")
            if (imageCount == 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Không có ảnh để lưu!", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            try {
                // Lấy toàn bộ URIs trong truyện (bao gồm cả ảnh chưa load hết - Lazy Loading)
                val allUris = uiState.value.imageUris + uiState.value.remainingImages
                // Loại bỏ duplicate URIs trước khi lưu
                val uniqueImageUris = allUris.distinctBy { it.toString() }

                val uniqueTranslatedTexts = uiState.value.translatedTexts.filterKeys { uri ->
                    uniqueImageUris.contains(uri)
                }
                val uniqueTranslatedStatus = uiState.value.translatedStatus.filterKeys { uri ->
                    uniqueImageUris.contains(uri)
                }
                val uniqueSourceLanguages = uiState.value.sourceLanguages.filterKeys { uri ->
                    uniqueImageUris.contains(uri)
                }

                // Update UI state (giữ nguyên phân trang cho imageUris, chỉ cập nhật metadata maps)
                _uiState.update { currentState ->
                    currentState.copy(
                        translatedTexts = uniqueTranslatedTexts,
                        translatedStatus = uniqueTranslatedStatus,
                        sourceLanguages = uniqueSourceLanguages
                    )
                }

                val currentRoomId = uiState.value.roomId
                
                // NOTE: KHÔNG xóa translations dựa trên translatedStatus
                // translatedStatus chỉ là flag hiển thị overlay trên UI
                // KHÔNG có nghĩa là ảnh không có translations trong DB
                // Chỉ xóa translations khi user explicitly yêu cầu (qua deletedTranslationUris)
                
                // Log all rotation values before saving
                uniqueTranslatedTexts.forEach { (uri, pair) ->
                    pair.second.forEachIndexed { idx, block ->
                        ////Log.i(TAG, "[SAVE ROOM] Block[$idx] uri=$uri rotation=${block.rotation} text='${block.text}'")
                    }
                }
                val roomId: Long
                val savedCount: Int
                val wasRemoval: Boolean
                
                if (currentRoomId != null) {
                    // Nếu đã có roomId, update truyện
                    
                    // Check xem có ảnh nào thay đổi không
                    val changedImageIds = databaseHelper.getChangedImageIdsForRoom(currentRoomId)
                    val hasDirtyUris = dirtyUris.isNotEmpty()
                    val hasChangedImages = changedImageIds.isNotEmpty()
                    val hasNewImages = newImageUris.isNotEmpty()
                    val hasDeletedTranslations = deletedTranslationUris.isNotEmpty()
                    val hasRemovedImages = removedImageIds.isNotEmpty()
                    
                    //Log.i(TAG, "Save check: dirtyUris=${dirtyUris.size} changedImageIds=${changedImageIds.size} newImageUris=${newImageUris.size} deletedTranslations=${deletedTranslationUris.size} removedImages=${removedImageIds.size}")
                    
                    var tempSavedCount = 0 // Track số lượng ảnh được save
                    var isRemovalOperation = false // Track if this is a removal operation
                    val updated: Boolean = when {
                        // Case 1: Có ảnh mới được thêm vào truyện → full update để add new images
                        hasNewImages -> {
                            //Log.i(TAG, "Full update: Adding ${newImageUris.size} new images to room")
                            tempSavedCount = newImageUris.size
                            val ok = databaseHelper.updateMangaRoom(currentRoomId, uniqueImageUris, uniqueTranslatedTexts, uiState.value.translatedStatus)
                            if (ok) {
                                newImageUris.clear()
                                dirtyUris.clear()
                                deletedTranslationUris.clear()
                            }
                            ok
                        }
                        // Case 2: Có ảnh bị xóa khỏi truyện → xóa trực tiếp từ DB trước rồi update
                        hasRemovedImages -> {
                            //Log.i(TAG, "Removing ${removedImageIds.size} images from room: $removedImageIds")
                            tempSavedCount = removedImageIds.size
                            isRemovalOperation = true
                            
                            // Xóa trực tiếp các ảnh trong removedImageIds từ DB
                            removedImageIds.forEach { imageId ->
                                try {
                                    databaseHelper.deleteImageFromRoom(imageId)
                                    //Log.i(TAG, "Deleted image from DB: imageId=$imageId")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to delete image $imageId", e)
                                }
                            }
                            
                            // Sau đó update room để đảm bảo consistency
                            val ok = databaseHelper.updateMangaRoom(currentRoomId, uniqueImageUris, uniqueTranslatedTexts, uiState.value.translatedStatus)
                            if (ok) removedImageIds.clear()
                            ok
                        }
                        // Case 3: Có dirtyUris (từ edit manual) hoặc deletedTranslations → selective save
                        hasDirtyUris || hasDeletedTranslations -> {
                            val affectedUris = (dirtyUris + deletedTranslationUris).toSet()
                            tempSavedCount = affectedUris.size
                            val ok = databaseHelper.updateMangaRoomSelective(currentRoomId, uniqueImageUris, uniqueTranslatedTexts, affectedUris.toList(), uriToImageId, uiState.value.translatedStatus)
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
                            //Log.i(TAG, "Partial save: ${tempSavedCount} retranslated images (out of ${changedImageIds.size} changed)")
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
                            //Log.i(TAG, "No changes detected, skipping save")
                            tempSavedCount = -1 // Signal no changes
                            true // Không có gì để save nhưng cũng không phải lỗi
                        }
                    }
                    
                    roomId = if (updated) currentRoomId else -1L
                    savedCount = tempSavedCount
                    wasRemoval = isRemovalOperation
                } else {
                    // Nếu chưa có roomId, tạo truyện mới
                    roomId = databaseHelper.saveMangaRoom(
                        uniqueImageUris,
                        uniqueTranslatedTexts,
                        translatedStatus = uiState.value.translatedStatus
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
                    
                    // Clear memory and reload from DB to ensure clean state
                    // Only reload if there were actual changes saved (not skipped)
                    if (savedCount != -1) {
                        //Log.i(TAG, "saveCurrentRoom: Clearing memory and reloading room $roomId from DB")
                        clearMemoryAndReloadRoom(roomId)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Lưu thất bại!", Toast.LENGTH_SHORT).show()
                    }
                    Log.e(TAG, "Lưu truyện thất bại")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi lưu truyện", e)
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
     * Xóa một ảnh khỏi truyện hiện tại (và DB)
     */
    fun removeImageFromRoom(uri: Uri) {
        val currentUris = uiState.value.imageUris.toMutableList()
        if (!currentUris.contains(uri)) return
        
        // Track the removed image ID
        val imageId = uriToImageId[uri]
        if (imageId != null) {
            removedImageIds.add(imageId)
            //Log.i(TAG, "Marked image for removal: imageId=$imageId uri=$uri")
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
     * Tối ưu bản dịch của một ảnh sử dụng AI
     * @param uri URI của ảnh cần tối ưu
     * @param style Phong cách tối ưu (Cân bằng, Nâng cao, Sáng tạo)
     * @param onResult Callback khi hoàn thành
     */
    /**
     * Replace an existing image URI in the current room/session with a new URI.
     * Keeps the existing image_id (if any), translations and image_blocks intact by
     * updating the stored URI in DB. Also updates in-memory mappings and UI state.
     * If the image was not associated with a stored image_id, the ViewModel will
     * simply swap the URI in the UI state.
     * 
     * IMPORTANT: When replacing an image, we update the originalImageWidth/Height 
     * in translation blocks to match the new image dimensions so that coordinates
     * remain consistent after saving and reloading from DB.
     */
    /**
     * Replace an existing image URI in the current room/session with a new URI.
     *
     * If persist == true and the image has an imageId associated with a stored room,
     * the function will write the new file content into DB (replaceImageFileOnly) so the
     * change is persisted immediately.
     *
     * If persist == false, the function performs a UI-only swap: it replaces the URI
     * in memory and mapping (uriToImageId), marks the URI as dirty so a later call to
     * saveRoom or an autosave will persist the change, and marks the DB row as changed
     * (markImageChanged) so autosave threshold detection works.
     */
    fun replaceImageUri(oldUri: Uri, newUri: Uri, persist: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Get image dimensions for scaling calculations
                val oldDims = getImageDimensions(getApplication(), oldUri)
                val newDims = getImageDimensions(getApplication(), newUri)
                
                //Log.d(TAG, "replaceImageUri: Scaling from ${oldDims.first}x${oldDims.second} to ${newDims.first}x${newDims.second}")
                
                // Calculate scale factors
                val scaleX = if (oldDims.first > 0) newDims.first.toFloat() / oldDims.first.toFloat() else 1.0f
                val scaleY = if (oldDims.second > 0) newDims.second.toFloat() / oldDims.second.toFloat() else 1.0f
                
                // If dimensions are valid and changed, perform scaling
                val shouldScale = (scaleX != 1.0f || scaleY != 1.0f) && oldDims.first > 0 && newDims.first > 0
                
                val imageId = uriToImageId.entries.find { it.key.toString() == oldUri.toString() }?.value
                
                var transformedBlocks: List<TextBlockInfo>? = null

                if (shouldScale) {
                    // 1. Scale blocks in memory
                    _uiState.update { state ->
                        val newTranslatedTexts = state.translatedTexts.toMutableMap()
                        val pair = newTranslatedTexts[oldUri]
                        if (pair != null) {
                            val scaledBlocks = pair.second.map { block ->
                                block.copyAndScale(scaleX, scaleY, newDims.first, newDims.second)
                            }
                            transformedBlocks = scaledBlocks
                            newTranslatedTexts[oldUri] = pair.first to scaledBlocks
                        }
                        state.copy(translatedTexts = newTranslatedTexts)
                    }
                }

                if (imageId != null && _uiState.value.roomId != null && persist) {
                    // Call DB helper to overwrite the old file with new content
                    // The stored URI remains the same, so all translations/blocks are preserved
                    val stored = databaseHelper.replaceImageFileOnly(imageId, newUri)
                    if (stored != null) {
                        // 2. If we scaled, we MUST save the new coordinates to the DB immediately
                        // because we just replaced the file but kept the old URI as the key.
                        if (transformedBlocks != null) {
                            val originalText = _uiState.value.translatedTexts[oldUri]?.first ?: ""
                            val updateMap = mapOf(imageId to (originalText to transformedBlocks!!))
                            databaseHelper.applyPendingChangesForRoom(_uiState.value.roomId!!, updateMap)
                            //Log.i(TAG, "replaceImageUri: Persisted ${transformedBlocks!!.size} scaled blocks for imageId=$imageId")
                        }

                        // bump version so UI invalidates Coil cache and reloads the new file
                        bumpImageVersion(imageId)
                        // bump reload token for the URI to force UI refresh
                        bumpReloadTokenForUri(oldUri)
                    } else {
                        Log.e(TAG, "replaceImageUri: Failed to replace file for imageId=$imageId")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(getApplication(), "Thay thế ảnh thất bại", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                } else if (imageId != null && _uiState.value.roomId != null && !persist) {
                    // Temporary replacement for a stored image: do not write to DB. This
                    // mirrors the behavior of replacing a non-stored image in UI only.
                    //Log.i(TAG, "replaceImageUri: temporary swap for stored image imageId=$imageId")
                    // Swap URI trong imageUris list
                    val oldIndex = _uiState.value.imageUris.indexOfFirst { it.toString() == oldUri.toString() }
                    if (oldIndex == -1) {
                        Log.e(TAG, "replaceImageUri: oldUri not found in imageUris list (temp)")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(getApplication(), "Không tìm thấy ảnh", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    // Swap translations từ oldUri sang newUri
                    val oldTranslation = _uiState.value.translatedTexts[oldUri]
                    // Update UI state: swap URIs and translation data and bump version
                    _uiState.update { state ->
                        val newImageUris = state.imageUris.toMutableList()
                        newImageUris[oldIndex] = newUri

                        val newTranslatedTexts = state.translatedTexts.toMutableMap()
                        newTranslatedTexts.remove(oldUri)
                        if (oldTranslation != null) {
                            newTranslatedTexts[newUri] = oldTranslation
                        }

                        // Maintain URI->imageId mapping for use on save: map newUri to the same
                        // imageId so updateMangaRoomSelective knows which DB row to update later.
                        uriToImageId.remove(oldUri)
                        uriToImageId[newUri] = imageId

                        state.copy(
                            imageUris = newImageUris,
                            translatedTexts = newTranslatedTexts,
                            translationVersion = state.translationVersion + 1
                        )
                    }

                    // Mark this uri as dirty so saveRoom will persist the file later
                    dirtyUris.add(newUri)
                    // Also notify DB to mark image changed for autosave threshold if needed
                    val rid = _uiState.value.roomId
                    try {
                        val numChanged = databaseHelper.markImageChanged(imageId, rid!!)
                        //Log.i(TAG, "replaceImageUri temp: marked image changed imageId=$imageId, numChanged=$numChanged")
                        if (numChanged >= 5) {
                            maybeAutoSaveChangedImages(rid)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "replaceImageUri temp: failed to markImageChanged for imageId=$imageId", e)
                    }
                    // Bump reload token for UI refresh (use newUri)
                    bumpReloadTokenForUri(newUri)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Đã tạm thay ảnh (chưa lưu)", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } else {
                    // Not a stored image - swap URI directly in UI state
                    //Log.i(TAG, "replaceImageUri: imageId not found for $oldUri, swapping URI in UI state")
                    
                    // Swap URI trong imageUris list
                    val oldIndex = _uiState.value.imageUris.indexOfFirst { it.toString() == oldUri.toString() }
                    if (oldIndex == -1) {
                        Log.e(TAG, "replaceImageUri: oldUri not found in imageUris list")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(getApplication(), "Không tìm thấy ảnh", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    
                    // Swap translations từ oldUri sang newUri
                    val oldTranslation = _uiState.value.translatedTexts[oldUri]
                    
                    _uiState.update { state ->
                        val newImageUris = state.imageUris.toMutableList()
                        newImageUris[oldIndex] = newUri
                        
                        val newTranslatedTexts = state.translatedTexts.toMutableMap()
                        newTranslatedTexts.remove(oldUri)
                        if (oldTranslation != null) {
                            newTranslatedTexts[newUri] = oldTranslation
                        }
                        
                        state.copy(
                            imageUris = newImageUris,
                            translatedTexts = newTranslatedTexts,
                            translationVersion = state.translationVersion + 1
                        )
                    }
                    
                    // If this replacement was requested as temporary (persist=false), mark dirtyUris
                    // so the new image will be saved during saveRoom/auto-save.
                    if (!persist) {
                        dirtyUris.add(newUri)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Đã thay thế ảnh", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Update UI state - just bump version to trigger refresh, no URI changes needed
                _uiState.update { state ->
                    state.copy(
                        translationVersion = state.translationVersion + 1
                    )
                }

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

    private fun enqueueTranslation(images: List<Uri>, reuseExistingOcr: Boolean = false) {
        translationQueue.addAll(images)
        if (translationJob == null || translationJob?.isActive != true) {
            translationJob = viewModelScope.launch(Dispatchers.IO) {
                processTranslationQueue(reuseExistingOcr)
            }
            translationJob?.let { registerJob(it) }
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private suspend fun processTranslationQueue(reuseExistingOcr: Boolean = false) {
    // Khi dịch bằng Mistral/Gemini cho toàn bộ truyện, dịch song song 2 ảnh, mỗi ảnh dùng 1 key khác nhau trong lượt đó
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
        
        // Biến lưu bản dịch của ảnh trước để truyền cho ảnh tiếp theo
        var lastTranslatedBlocks: List<TextBlockInfo>? = null
        
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

            // Lấy bản dịch ảnh trước cho tất cả ảnh trong batch
            // Khi dịch 2 ảnh cùng lúc (parallel): cả 2 đều tham khảo từ ảnh đã dịch trước đó (ngoài batch)
            // Để đảm bảo đồng nhất xưng hô giữa các ảnh trong cùng batch
            val sharedPreviousTranslation: List<TextBlockInfo>? = run {
                // Lấy bản dịch từ lastTranslatedBlocks (batch trước) hoặc từ ảnh GẦN NHẤT đã được dịch trước ảnh đầu tiên trong batch
                lastTranslatedBlocks ?: run {
                    val imageUris = uiState.value.imageUris
                    val firstUriInBatch = batch.firstOrNull()
                    val firstIndex = if (firstUriInBatch != null) imageUris.indexOf(firstUriInBatch) else -1
                    
                    // Tìm ảnh gần nhất đã được dịch (có trong translatedTexts) trước ảnh đầu tiên trong batch
                    // Không chỉ tìm ảnh liền kề mà tìm ảnh gần nhất có bản dịch
                    var foundTranslation: List<TextBlockInfo>? = null
                    if (firstIndex > 0) {
                        for (i in (firstIndex - 1) downTo 0) {
                            val prevUri = imageUris[i]
                            val translation = uiState.value.translatedTexts[prevUri]?.second
                            if (translation != null && translation.isNotEmpty()) {
                                foundTranslation = translation
                                //Log.i(TAG, "[PREV-TRANSLATION] Tìm thấy bản dịch tham khảo từ ảnh index=$i (uri=$prevUri)")
                                break
                            }
                        }
                    }
                    foundTranslation
                }
            }
            
            // Tất cả ảnh trong batch dùng chung previousTranslation để đảm bảo đồng nhất xưng hô
            val previousTranslationsForBatch: List<List<TextBlockInfo>?> = batch.map { sharedPreviousTranslation }

            // Dịch song song, truyền bản dịch ảnh trước tương ứng cho từng ảnh
            val results = kotlinx.coroutines.coroutineScope {
                batch.mapIndexed { idx, uri ->
                    val prevTranslation = previousTranslationsForBatch.getOrNull(idx)
                    // Callback để cập nhật trạng thái từ repository
                    val statusCallback: (com.example.ocrmanga.data.models.TranslationStatus) -> Unit = { status ->
                        updateTranslationStatus(uri, status)
                    }
                    async(Dispatchers.IO) {
                        try {
                            val reusableBlocks = if (reuseExistingOcr) getReusableOcrBlocksForUri(uri) else emptyList()
                            val (original, translatedBlocks, sourceLang) = translationRepository.recognizeAndTranslateText(
                                uri,
                                uiState.value.translationMode,
                                statusCallback,
                                prevTranslation, // Truyền bản dịch ảnh trước để tham khảo
                                isAncientMode = uiState.value.isAncientTranslationMode,
                                reuseExistingBlocks = reusableBlocks.takeIf { it.isNotEmpty() },
                                onOcrCompleted = { ocrBlocks ->
                                    // Lưu kết quả OCR vào DB ngay lập tức trước khi gửi cho AI
                                    _uiState.update { state ->
                                        val newTexts = state.translatedTexts.toMutableMap().apply {
                                            put(uri, "" to ocrBlocks)
                                        }
                                        state.copy(translatedTexts = newTexts)
                                    }
                                    dirtyUris.add(uri)
                                    // Lưu xuống DB (không kèm DragBlockState vì chưa có)
                                    saveRoom(emptyMap())
                                    //Log.i(TAG, "[OCR-COMPLETE] Đã lưu ${ocrBlocks.size} blocks OCR cho $uri vào DB")
                                }
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
                
                if ((uiState.value.imageUris + uiState.value.remainingImages).contains(uri)) {
                    if (original.isNotEmpty() || (translatedBlocks as? List<*>)?.isNotEmpty() == true) {
                                // Try to preserve per-block customizations (including gradients) from any existing translation
                                val existingBlocks = _uiState.value.translatedTexts[uri]?.second ?: emptyList()

                                fun intersectionRatio(a: android.graphics.Rect, b: android.graphics.Rect): Float {
                                    val left = maxOf(a.left, b.left)
                                    val top = maxOf(a.top, b.top)
                                    val right = minOf(a.right, b.right)
                                    val bottom = maxOf(a.bottom, b.bottom)
                                    if (right <= left || bottom <= top) return 0f
                                    val inter = (right - left).toFloat() * (bottom - top).toFloat()
                                    val minArea = minOf((a.width()).toFloat() * (a.height()).toFloat(), (b.width()).toFloat() * (b.height()).toFloat())
                                    return if (minArea <= 0f) 0f else inter / minArea
                                }

                                val fixedBlocks = (translatedBlocks as List<TextBlockInfo>).map { block ->
                                    // Prefer an explicit custom overlay color; otherwise use detected average background color; fallback to white
                                    val baseOverlay = block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()
                                    val textColor = block.customTextColor ?: block.originalTextColor ?: computeDefaultTextColor(baseOverlay, block.averageBackgroundColor)

                                    // Find best matching existing block by bounding-box overlap to preserve gradient & offset settings
                                    val bestMatch = existingBlocks.maxByOrNull { existing ->
                                        intersectionRatio(existing.bounds, block.bounds)
                                    }
                                    val overlap = if (bestMatch != null) intersectionRatio(bestMatch.bounds, block.bounds) else 0f

                                    val preservedGradientColors = if (overlap >= 0.35f) bestMatch?.textGradientColors ?: block.textGradientColors else block.textGradientColors
                                    val preservedGradientOffsets = if (overlap >= 0.35f) bestMatch?.textGradientOffsets ?: block.textGradientOffsets else block.textGradientOffsets
                                    val preservedGradientType = if (overlap >= 0.35f) bestMatch?.textGradientType ?: block.textGradientType else block.textGradientType

                                    block.copy(
                                        customOverlayColor = baseOverlay,
                                        customTextColor = textColor,
                                        // preserve any existing gradient customizations when a matching block is found
                                        textGradientColors = preservedGradientColors,
                                        textGradientOffsets = preservedGradientOffsets,
                                        textGradientType = preservedGradientType,
                                        // Set applyMerge = true khi translation mới để áp dụng logic chống chồng lấn
                                        applyMerge = true
                                    )
                                }
                                
                        // Không tự động tối ưu hóa overlay nữa để giữ bounds gốc
                        // val optimizedBlocks = autoOptimizeOverlay(uri, fixedBlocks)
                        val resultBlocks = fixedBlocks

                        // Lưu lại bản dịch mới nhất để truyền cho ảnh tiếp theo trong batch sau
                        lastTranslatedBlocks = resultBlocks

                        translatedTexts[uri] = original to resultBlocks

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
                        // Mark as dirty để hệ thống nhận ra có thay đổi khi lưu
                        dirtyUris.add(uri)
                        //Log.i(TAG, "[QUEUE] Added uri=$uri to dirtyUris after translation")
                        
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
        // KHÔNG clear newImageUris ở đây vì cần giữ để save
        // newImageUris sẽ được clear sau khi save thành công
        //Log.i(TAG, "processTranslationQueue completed, keeping newImageUris=${newImageUris.size} for save")
        // Lưu toàn bộ kết quả final sau khi dịch xong
        saveRoom(emptyMap())
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
     * Bật/tắt chế độ dịch cổ trang
     */
    fun toggleAncientTranslationMode() {
        val roomId = uiState.value.roomId ?: return
        val newValue = !uiState.value.isAncientTranslationMode
        viewModelScope.launch(Dispatchers.IO) {
            try {
                databaseHelper.setAncientTranslationSetting(roomId, newValue)
                _uiState.update { it.copy(isAncientTranslationMode = newValue) }
                withContext(Dispatchers.Main) {
                    val message = if (newValue) "Đã BẬT chế độ dịch cổ trang" else "Đã TẮT chế độ dịch cổ trang"
                    Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling ancient translation mode", e)
            }
        }
    }

    /**
     * Xóa toàn bộ session, ảnh, trạng thái dịch, trạng thái truyện, v.v. (reset sạch ViewModel)
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
    //Log.i(TAG, "clearSessionAndImages: cleared lastLoadedRoomId")

        // 4) Best-effort remove temporary/cache files created by the app
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // If requested, delete the saved room from DB and remove its files
                if (deleteSavedRoom) {
                    try {
                        val rid = uiState.value.roomId ?: lastLoadedRoomId
                        if (rid != null) {
                            //Log.i(TAG, "clearSessionAndImages: deleting saved room $rid as requested")
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
                        //Log.i(TAG, "Set suppress_coil_disk_cache=true after clearing session cache")
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
        _uiState.update { it.copy(roomTitle = newTitle) }
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
                val (allImages, _, translations) = databaseHelper.getMangaRoomOptimized(roomId)
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
                            val entryName = try { File(uri.path ?: "image_${idx}.webp").name } catch (e: Exception) { "image_${idx}.webp" }

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
                                            // Skip exporting overlays that have no text
                                            if (block.text.isBlank()) continue
                                            try {
                                                // Calculate scale relation between current bitmap and original image to match ImageViewer logic
                                                val blockOriginalW = block.originalImageWidth?.toFloat() ?: src.width.toFloat()
                                                val srcScaleX = if (blockOriginalW > 0) src.width.toFloat() / blockOriginalW else 1f
                                                
                                                // Calculate vertical offset (centering) if aspect ratio changed (FillWidth logic)
                                                val blockOriginalH = block.originalImageHeight?.toFloat() ?: src.height.toFloat()
                                                val scaledBlockH = blockOriginalH * srcScaleX
                                                val offsetY = if (src.height.toFloat() > scaledBlockH) (src.height.toFloat() - scaledBlockH) / 2f else 0f

                                                // Adjust bounds from original coordinates to current src bitmap coordinates
                                                val adjBoundsLeft = block.bounds.left * srcScaleX
                                                val adjBoundsTop = block.bounds.top * srcScaleX + offsetY
                                                val adjBoundsWidth = (block.bounds.right - block.bounds.left) * srcScaleX
                                                val adjBoundsHeight = (block.bounds.bottom - block.bounds.top) * srcScaleX
                                                
                                                val bounds = block.bounds // Keep for reference if needed, but use adj... values
                                                val boundsWidth = adjBoundsWidth
                                                val boundsHeight = adjBoundsHeight
                                                
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
                                                
                                                // Tính windowed overlay bounds (trong bitmap coordinates)
                                                val bitmapRect = androidx.compose.ui.geometry.Rect(
                                                    adjBoundsLeft,
                                                    adjBoundsTop,
                                                    adjBoundsLeft + adjBoundsWidth,
                                                    adjBoundsTop + adjBoundsHeight
                                                )

                                                val displayMetrics = app.resources.displayMetrics
                                                // Tính toán exportFontScale để đồng nhất với hiển thị trên View
                                                // View hiển thị dựa trên màn hình tham chiếu 360dp
                                                val refScreenWidthPx = 360f * displayMetrics.density
                                                val exportFontScale = src.width.toFloat() / refScreenWidthPx

                                                // Inset nên được scale theo exportFontScale để đồng nhất với cảm quan trên View
                                                val insetH = (if (block.overlayInsetHorizontal != 0f) block.overlayInsetHorizontal else block.overlayInset) * exportFontScale
                                                val insetV = (if (block.overlayInsetVertical != 0f) block.overlayInsetVertical else block.overlayInset) * exportFontScale

                                                val windowedResult = com.example.ocrmanga.ui.screens.view.calculateWindowedOverlayBounds(
                                                    originalBounds = bitmapRect,
                                                    text = block.text,
                                                    baseFontSize = block.fontSize * exportFontScale,
                                                    isVertical = block.isVertical,
                                                    context = app,
                                                    fontFamilyName = block.fontFamily,
                                                    lineSpacing = block.lineSpacing,
                                                    shapeType = block.shapeType,
                                                    overlayInsetHorizontal = insetH,
                                                    overlayInsetVertical = insetV,
                                                    horizontalPadding = 4f * exportFontScale,
                                                    verticalPadding = 4f * exportFontScale
                                                )
                                                val outerBounds = windowedResult.outerBounds
                                                val innerBounds = windowedResult.innerBounds
                                                val finalFontSizeForBitmap = windowedResult.optimalFontSize

                                                // Vẽ overlay chỉ trên INNER bounds
                                                val overlayRectF = RectF(
                                                    innerBounds.left,
                                                    innerBounds.top,
                                                    innerBounds.right,
                                                    innerBounds.bottom
                                                )

                                                // Apply overlayRotation
                                                val overlayRotationAngle = block.overlayRotation ?: 0f
                                                val cx = outerBounds.center.x
                                                val cy = outerBounds.center.y

                                                if (overlayRotationAngle != 0f) {
                                                    canvas.save()
                                                    canvas.rotate(overlayRotationAngle, cx, cy)
                                                }

                                                if (block.shapeType == 1) {
                                                    canvas.drawOval(overlayRectF, overlayPaint)
                                                } else {
                                                    canvas.drawRect(overlayRectF, overlayPaint)
                                                }

                                                if (overlayRotationAngle != 0f) {
                                                    canvas.restore()
                                                }

                                                // Text rendering area = INNER bounds (vùng bôi trắng thực tế)
                                                val textRenderLeft = innerBounds.left + innerBounds.width * (if (block.shapeType == 1) 0.15f else 0f)
                                                val textRenderTop = innerBounds.top + innerBounds.height * (if (block.shapeType == 1) 0.15f else 0f)
                                                val textRenderWidth = innerBounds.width * (if (block.shapeType == 1) 0.7f else 1f)
                                                val textRenderHeight = innerBounds.height * (if (block.shapeType == 1) 0.7f else 1f)

                                                // Wrap text với font size tối ưu đã được tính toán ở trên
                                                // Sử dụng width đã trừ padding để đồng nhất với logic sizing
                                                val safeTextRenderWidth = (textRenderWidth - 8f * exportFontScale).coerceAtLeast(1f)
                                                val wrappedTextLines = com.example.ocrmanga.ui.screens.view.wrapText(
                                                    text = block.text,
                                                    width = safeTextRenderWidth,
                                                    fontSize = finalFontSizeForBitmap,
                                                    context = app,
                                                    fontFamilyName = block.fontFamily
                                                )
                                                val wrappedText = wrappedTextLines.joinToString("\n")

                                                // Draw text with all properties (font, boldness, border, shadow, line spacing)
                                                val rawTextColor = block.customTextColor ?: block.originalTextColor ?: computeDefaultTextColor(overlayColor or 0xFF000000.toInt(), block.averageBackgroundColor)
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
                                                fun mapAlign(a: com.example.ocrmanga.data.models.TextAlignMode): Paint.Align = when(a) {
                                                    com.example.ocrmanga.data.models.TextAlignMode.LEFT -> Paint.Align.LEFT
                                                    com.example.ocrmanga.data.models.TextAlignMode.CENTER -> Paint.Align.CENTER
                                                }
                                                val tp = TextPaint().apply {
                                                    isAntiAlias = true
                                                    color = textColor
                                                    textSize = finalFontSizeForBitmap
                                                    textAlign = mapAlign(block.textAlign)
                                                    this.typeface = typeface ?: Typeface.DEFAULT

                                                    // Apply boldness
                                                    if (block.textBoldness > 1.0f) {
                                                        style = Paint.Style.FILL_AND_STROKE
                                                        // Sử dụng exportFontScale để đồng nhất với font size
                                                        strokeWidth = ((block.textBoldness - 1.0f) * 2.0f) * exportFontScale
                                                    } else if (block.textBoldness < 1.0f) {
                                                        alpha = (255 * block.textBoldness).toInt().coerceIn(50, 255)
                                                    }
                                                }

                                                val gradientColorsArr = block.textGradientColors?.toIntArray()
                                                val gradientPositionsArr = block.textGradientOffsets?.toFloatArray()
                                                val gradientType = block.textGradientType

                                                // Create border paint if needed
                                                var borderPaint = if (block.customBorderColor != null && block.borderThickness > 0f) {
                                                    TextPaint().apply {
                                                        isAntiAlias = true
                                                        color = block.customBorderColor
                                                        alpha = (block.borderAlpha * 255).toInt().coerceIn(0, 255)
                                                        textSize = finalFontSizeForBitmap
                                                        textAlign = mapAlign(block.textAlign)
                                                        style = Paint.Style.STROKE
                                                        // Sử dụng exportFontScale để đồng nhất với font size
                                                        strokeWidth = block.borderThickness * exportFontScale
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
                                                        textAlign = mapAlign(block.textAlign)
                                                        style = Paint.Style.FILL
                                                        this.typeface = typeface ?: Typeface.DEFAULT
                                                        // Sử dụng exportFontScale cho shadow radius
                                                        val radius = if (block.shadowRadius > 0f) {
                                                            block.shadowRadius * exportFontScale
                                                        } else {
                                                            (finalFontSizeForBitmap * 0.14f).coerceAtLeast(1f)
                                                        }
                                                        val dx = finalFontSizeForBitmap * 0.04f
                                                        val dy = finalFontSizeForBitmap * 0.04f
                                                        setShadowLayer(radius, dx, dy, block.customShadowColor)
                                                    }
                                                } else null

                                                canvas.save()
                                                // Rotate around center of the OUTER bounds (text rotation, different from overlay rotation)
                                                val rotation = block.rotation ?: 0f
                                                if (rotation != 0f) canvas.rotate(rotation, cx, cy)

                                                // Draw text line by line with proper positioning (matching UI drawTextOnCanvas exactly)
                                                val lines = wrappedText.split("\n")
                                                val fontMetrics = tp.fontMetrics
                                                // Use actual lineSpacing value (may be < 1f) to match what adjustWhiteoutBounds calculated
                                                val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * block.lineSpacing

                                                // Use OUTER bounds for text drawing (bitmap coordinates)
                                                val textLeft = textRenderLeft
                                                val textTop = textRenderTop
                                                val textDrawWidth = textRenderWidth
                                                val textDrawHeight = textRenderHeight

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
                                                             if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                                                                 com.example.ocrmanga.ui.screens.view.drawTextPerCharacter(
                                                                     canvas, line, centeredY, -fontMetrics.ascent, tp,
                                                                     gradientColorsArr, gradientPositionsArr, gradientType, fontMetrics
                                                                 )
                                                             } else {
                                                                 canvas.drawText(line, centeredY, -fontMetrics.ascent, tp)
                                                             }
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
                                                            // Logic alignment ngang (Horizontal Alignment)
                                                            // Fix lỗi lệch text sang phải: Nếu là LEFT thì vẽ từ mép trái, CENTER thì vẽ từ tâm
                                                            when (block.textAlign) {
                                                                com.example.ocrmanga.data.models.TextAlignMode.LEFT -> {
                                                                    // Trong View (ImageTextUtils), có padding hardcode là 4f
                                                                    // Cần scale 4f này về bitmap coordinate sử dụng exportFontScale để đồng nhất
                                                                    val paddingLeft = 4f * exportFontScale
                                                                    val drawX = textLeft + paddingLeft

                                                                    // Ensure Paint is set to LEFT
                                                                    tp.textAlign = Paint.Align.LEFT
                                                                    shadowPaint?.textAlign = Paint.Align.LEFT
                                                                    borderPaint?.textAlign = Paint.Align.LEFT

                                                                    shadowPaint?.let { canvas.drawText(line, drawX, currentY, it) }
                                                                    borderPaint?.let { canvas.drawText(line, drawX, currentY, it) }
                                                                     if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                                                                         com.example.ocrmanga.ui.screens.view.drawTextPerCharacter(
                                                                             canvas, line, drawX, currentY, tp,
                                                                             gradientColorsArr, gradientPositionsArr, gradientType, fontMetrics
                                                                         )
                                                                     } else {
                                                                         canvas.drawText(line, drawX, currentY, tp)
                                                                     }
                                                                }
                                                                else -> { // CENTER or others
                                                                    // Ensure Paint is set to CENTER
                                                                    tp.textAlign = Paint.Align.CENTER
                                                                    shadowPaint?.textAlign = Paint.Align.CENTER
                                                                    borderPaint?.textAlign = Paint.Align.CENTER
                                                                    
                                                                    shadowPaint?.let { canvas.drawText(line, centerX, currentY, it) }
                                                                    borderPaint?.let { canvas.drawText(line, centerX, currentY, it) }
                                                                     if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                                                                         com.example.ocrmanga.ui.screens.view.drawTextPerCharacter(
                                                                             canvas, line, centerX, currentY, tp,
                                                                             gradientColorsArr, gradientPositionsArr, gradientType, fontMetrics
                                                                         )
                                                                     } else {
                                                                         canvas.drawText(line, centerX, currentY, tp)
                                                                     }
                                                                }
                                                            }
                                                        }
                                                        currentY += lineHeight
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

                //Log.i(TAG, "Exported room $roomId to ${zipFile.absolutePath}")
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
                    
                    // Logic: Đảm bảo font size được lưu vào DB là font size đã được giới hạn để "nằm lọt" trong khung.
                    // Chỉ thực hiện nếu người dùng không chỉnh sửa font size thủ công (state.fontSize == null)
                    // và đây là một "solid bubble" (bong bóng thoại có nền).
                    var persistedFontSize = state.fontSize ?: b.fontSize
                    if (state.fontSize == null && state.overlayAlpha >= 0.95f && b.text.isNotBlank()) {
                        try {
                            val context = getApplication<Application>()
                            // Tính toán scale factor tham chiếu dựa trên chiều rộng ảnh gốc so với màn hình tham chiếu 360dp
                            val originalW = b.originalImageWidth?.toFloat() ?: 1280f
                            val referenceScale = originalW / 360f
                            
                            // Chuyển đổi bounds sang Compose Rect
                            val composeRect = androidx.compose.ui.geometry.Rect(
                                b.bounds.left.toFloat(),
                                b.bounds.top.toFloat(),
                                b.bounds.right.toFloat(),
                                b.bounds.bottom.toFloat()
                            )
                            
                            // Gọi logic tính toán optimal font size (đã bao gồm giới hạn co nhỏ)
                            val windowedResult = com.example.ocrmanga.ui.screens.view.calculateWindowedOverlayBounds(
                                originalBounds = composeRect,
                                text = b.text,
                                baseFontSize = b.fontSize,
                                isVertical = b.isVertical,
                                context = context,
                                fontFamilyName = b.fontFamily,
                                lineSpacing = state.lineSpacing,
                                shapeType = b.shapeType,
                                // Inset đã ở đơn vị gốc (original pixels), không cần scale thêm.
                                overlayInsetHorizontal = state.overlayInsetHorizontal,
                                overlayInsetVertical = state.overlayInsetVertical,
                                horizontalPadding = 4f * referenceScale,
                                verticalPadding = 4f * referenceScale,
                                boldness = state.textBoldness,
                                originalFontSize = b.originalFontSize,
                                isSolidBubble = true
                            )
                            persistedFontSize = windowedResult.optimalFontSize
                            // //Log.i("ViewerViewModel", "Persisting optimized fontSize: ${b.fontSize} -> $persistedFontSize for block in ${uri}")
                        } catch (e: Exception) {
                            Log.e("ViewerViewModel", "Error calculating optimal font size for persistence", e)
                        }
                    }

                    b.copy(
                        rotation = state.rotation,
                        overlayRotation = state.overlayRotation, // ✅ Copy overlay rotation
                        shapeType = b.shapeType,
                        customOverlayColor = state.whiteoutColor?.toArgb() ?: b.customOverlayColor,
                        customTextColor = state.textColor?.toArgb()
                            ?: b.customTextColor
                            ?: computeDefaultTextColor(state.whiteoutColor?.toArgb() ?: b.customOverlayColor, b.averageBackgroundColor),
                        overlayAlpha = state.overlayAlpha,
                        textBoldness = state.textBoldness,
                        overlaySaturation = state.overlaySaturation,
                        textSaturation = state.textSaturation,
                        // ✅ Copy overlay inset properties
                        overlayInset = state.overlayInset,
                        overlayInsetHorizontal = state.overlayInsetHorizontal,
                        overlayInsetVertical = state.overlayInsetVertical,
                        customBorderColor = state.textBorderColor?.toArgb() ?: b.customBorderColor,
                        borderThickness = state.textBorderThickness,
                        borderAlpha = state.textBorderAlpha,
                        // Persist shadow edits as well so they aren't lost after save
                        customShadowColor = state.textShadowColor?.toArgb(),
                        shadowAlpha = state.textShadowAlpha,
                        shadowRadius = state.textShadowRadius,
                        lineSpacing = state.lineSpacing,
                        // Persist alignment
                        textAlign = state.textAlign,
                        fontSize = persistedFontSize,
                        // Set applyMerge = false vì đây là save sau khi edit
                        applyMerge = false
                    )
                }
            )
        }
        // If room already exists, update selectively by image_id for only edited images
        if (dirtyUris.isNotEmpty() && uiState.value.roomId != null) {
            databaseHelper.updateMangaRoomSelective(roomId, currentState.imageUris, updatedTranslatedTexts, dirtyUris.toList(), uriToImageId, currentState.translatedStatus)
            // clear dirty set after saving
            dirtyUris.clear()
        } else if (uiState.value.roomId == null || currentState.imageUris.size != databaseHelper.getImageCountForRoom(roomId)) {
            // Only call updateMangaRoom when creating new room or when image count changed
            // This prevents unnecessary deletion and re-insertion of translations
            databaseHelper.updateMangaRoom(roomId, currentState.imageUris, updatedTranslatedTexts, currentState.translatedStatus)
        }
        // Update UI state with persisted values and increment version to trigger UI refresh
        _uiState.update { state ->
            state.copy(
                translatedTexts = updatedTranslatedTexts,
                translationVersion = state.translationVersion + 1
            )
        }
        // If dirtyUris is empty and image count hasn't changed, no update needed
    }

    // Expose API key availability checks for UI
    fun hasGeminiApiKeys(): Boolean {
        return translationRepository.hasGeminiApiKeys()
    }

    fun hasMistralApiKeys(): Boolean {
        return translationRepository.hasMistralApiKeys()
    }

    fun hasZAiApiKeys(): Boolean {
        return translationRepository.hasZAiApiKeys()
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Public accessor for UI to get imageId for a given uri if available
    fun getImageIdForUri(uri: Uri): Long? {
        return uriToImageId[uri]
    }

    fun translateAllImagesInRoom(mode: TranslationMode, reuseExistingOcr: Boolean = false) {
        viewModelScope.launch {
            val hasApiKeys = when(mode) {
                TranslationMode.GEMINI -> hasGeminiApiKeys()
                TranslationMode.MISTRAL -> hasMistralApiKeys()
                TranslationMode.ZAI -> hasZAiApiKeys()
                else -> true
            }

            if (!hasApiKeys) {
                withContext(Dispatchers.Main) {
                    val msg = when(mode) {
                        TranslationMode.GEMINI -> "Không có API key Gemini. Vui lòng thêm trong cài đặt."
                        TranslationMode.MISTRAL -> "Không có API key Mistral. Vui lòng thêm trong cài đặt."
                        TranslationMode.ZAI -> "Không có API key Z.AI. Vui lòng thêm trong cài đặt."
                        else -> "Không có API key. Vui lòng thêm trong cài đặt."
                    }
                    Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val imageUris = uiState.value.imageUris
            if (imageUris.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Đang bắt đầu dịch tất cả ${imageUris.size} ảnh...", Toast.LENGTH_SHORT).show()
            }

            _uiState.update { it.copy(isTranslating = true, totalImagesToTranslate = imageUris.size, translationProgress = 0) }

            // Dịch tuần tự để giữ ngữ cảnh (previousTranslation)
            for ((index, uri) in imageUris.withIndex()) {
                _uiState.update { it.copy(translationProgress = index + 1) }
                // Chúng ta gọi logic dịch tương tự như retranslateImage nhưng không launch coroutine mới cho mỗi ảnh
                // để đảm bảo tính tuần tự và tránh quá tải API

                val imageId = uriToImageId[uri]
                if (imageId != null) {
                    try {
                        databaseHelper.markTranslationsAsPendingDelete(imageId)
                    } catch (e: Exception) { }
                }

                updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.SCANNING)
                _uiState.update { it.copy(translatedStatus = it.translatedStatus + (uri to false)) }

                try {
                    val statusCallback: (com.example.ocrmanga.data.models.TranslationStatus) -> Unit = { status ->
                        updateTranslationStatus(uri, status)
                    }

                    val previousTranslation: List<TextBlockInfo>? = if (mode == TranslationMode.GEMINI || mode == TranslationMode.MISTRAL || mode == TranslationMode.ZAI) {
                        val currentIndex = imageUris.indexOf(uri)
                        var foundTranslation: List<TextBlockInfo>? = null
                        if (currentIndex > 0) {
                            for (i in (currentIndex - 1) downTo 0) {
                                val prevUri = imageUris[i]
                                val translation = uiState.value.translatedTexts[prevUri]?.second
                                if (translation != null && translation.isNotEmpty()) {
                                    foundTranslation = translation
                                    break
                                }
                            }
                        }
                        foundTranslation
                    } else null

                    val canonicalUri = try {
                        val imgId = uriToImageId[uri]
                        if (imgId != null) {
                            val db = databaseHelper.readableDatabase
                            val cur = db.rawQuery(
                                "SELECT ${DatabaseHelper.COLUMN_IMAGE_URI} FROM ${DatabaseHelper.TABLE_IMAGES} WHERE ${DatabaseHelper.COLUMN_IMAGE_ID} = ?",
                                arrayOf(imgId.toString())
                            )
                            val stored = if (cur.moveToFirst()) Uri.parse(cur.getString(0)) else null
                            cur.close()
                            stored ?: uri
                        } else uri
                    } catch (e: Exception) { uri }

                    // Xóa cache cũ trước khi dịch để đảm bảo không lấy nhầm dữ liệu cũ chưa có originalFontSize
                    translationRepository.clearCacheForImage(canonicalUri, mode)

                    val result = translationRepository.translateImage(
                        canonicalUri,
                        mode,
                        statusCallback,
                        previousTranslation,
                        isAncientMode = uiState.value.isAncientTranslationMode,
                        reuseExistingBlocks = if (reuseExistingOcr) getReusableOcrBlocksForUri(uri).takeIf { it.isNotEmpty() } else null
                    )

                    val (originalText, blocks) = result
                    val fixedBlocks = blocks.map { block ->
                        val baseOverlay = block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()
                        val textColor = block.customTextColor ?: block.originalTextColor ?: computeDefaultTextColor(baseOverlay, block.averageBackgroundColor)
                        block.copy(
                            customOverlayColor = baseOverlay,
                            customTextColor = textColor,
                            applyMerge = true
                        )
                    }

                    // Không tự động tối ưu hóa overlay nữa
                    // val optimizedBlocks = autoOptimizeOverlay(uri, fixedBlocks)

                    _uiState.update {
                        it.copy(
                            translatedTexts = it.translatedTexts + (uri to (originalText to fixedBlocks)),
                            translatedStatus = it.translatedStatus + (uri to true),
                            translationEnabled = true,
                            translationVersion = it.translationVersion + 1
                        )
                    }

                    updateTranslationStatus(uri, com.example.ocrmanga.data.models.TranslationStatus.COMPLETED)
                    delay(500)
                    clearTranslationStatus(uri)

                    dirtyUris.add(uri)
                    val rid = _uiState.value.roomId
                    if (rid != null && imageId != null) {
                        try {
                            databaseHelper.markImageChanged(imageId, rid)
                        } catch (e: Exception) { }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi khi dịch ảnh $uri trong chế độ dịch tất cả", e)
                    clearTranslationStatus(uri)
                }
            }

            _uiState.update { it.copy(isTranslating = false) }

            val rid = _uiState.value.roomId
            if (rid != null) {
                maybeAutoSaveChangedImages(rid)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Đã hoàn thành dịch tất cả ảnh trong truyện.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val imageVersions = mutableMapOf<Long, Int>()

    private fun bumpImageVersion(imageId: Long) {
        imageVersions[imageId] = (imageVersions[imageId] ?: 0) + 1
        // Also bump translationVersion to ensure overlays re-evaluate if needed
        _uiState.update { it.copy(translationVersion = it.translationVersion + 1) }
    }

    fun setIsTransitioningMode(isTransitioning: Boolean) {
        _uiState.update { it.copy(isTransitioningMode = isTransitioning) }
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
            //Log.i(TAG, "Cleared suppress_coil_disk_cache flag (disk caching re-enabled)")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear suppress_coil_disk_cache flag", e)
        }
    }
}
data class ViewerUiState(
    val isLoading: Boolean = false,
    val imageUris: List<Uri> = emptyList(),
    val translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>> = emptyMap(),
    val sourceLanguages: Map<Uri, String> = emptyMap(),
    val translatedStatus: Map<Uri, Boolean> = emptyMap(),
    val translationVersion: Int = 0,
    val translationMode: TranslationMode = TranslationMode.OFF,
    val translationEnabled: Boolean = false,
    val lastTranslationMode: TranslationMode = TranslationMode.ONLINE,
    val isTranslating: Boolean = false,
    val translationProgress: Int = 0,
    val totalImagesToTranslate: Int = 0,
    val currentTranslatingImage: Uri? = null,
    val currentTranslatingImageIndex: Int = 0,
    val translationTimer: Int = 0,
    val isLoadingMoreImages: Boolean = false,
    val remainingImages: List<Uri> = emptyList(),
    val roomId: Long? = null,
    val roomTitle: String? = null,
    val autoTranslateEnabled: Boolean = true, // Auto-translate new images when adding to room
    val isAncientTranslationMode: Boolean = false, // Chế độ dịch cổ trang
    val isSavingRoom: Boolean = false, // Loading state for room saving
    val isExportingRoom: Boolean = false, // Loading state for room exporting
    // Map theo dõi trạng thái dịch của từng ảnh (Uri -> TranslationStatus)
    val translatingImages: Map<Uri, com.example.ocrmanga.data.models.TranslationStatus> = emptyMap(),
    // Vị trí scroll cần nhảy đến sau khi reload (null = không nhảy)
    val scrollToIndexAfterReload: Int? = null,
    val isTextRemovalMode: Boolean = false, // Chế độ xóa text thủ công (vẽ mask)
    val isRemovingText: Boolean = false, // Loading state for text removal
    val removingTextProgress: String = "", // Progress text for text removal popup
    val showTextRemovalPreview: Boolean = false, // Show mask preview dialog before removal
    val textRemovalPreviewUri: Uri? = null, // Uri of image being previewed for removal
    val textRemovalPreviewBitmap: android.graphics.Bitmap? = null, // Preview bitmap with mask overlay
    val textRemovalPreviewBlocks: List<TextBlockInfo> = emptyList(), // OCR regions used for preview and removal
    val recentlySavedUris: Set<android.net.Uri> = emptySet(), // URIs saved via editor but not yet applied in UI
    val reopenEditorUris: Set<android.net.Uri> = emptySet(), // URIs for which editor should reopen after blocks are applied
    val showExternalTranslationDialog: Boolean = false,
    val externalTranslationUri: android.net.Uri? = null,
    val isBulkExternalTranslation: Boolean = false,
    val bulkExternalTranslationUris: List<android.net.Uri> = emptyList(),
    val bulkScanningProgress: String = "",
    val isTransitioningMode: Boolean = false
)
