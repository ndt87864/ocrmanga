package com.example.ocrmanga.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TranslationMode
import com.example.ocrmanga.data.repositories.TranslationRepository
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
import java.util.concurrent.ConcurrentLinkedQueue

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val translationRepository = TranslationRepository(application)
    private val databaseHelper = DatabaseHelper(application)
    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()
    private val _allRoomIds = MutableStateFlow<List<Long>>(emptyList())
    val allRoomIds: StateFlow<List<Long>> = _allRoomIds.asStateFlow()
    private val TAG = "ViewerViewModel"
    private val galleryViewModel = GalleryViewModel(application)
    private val newImageUris = mutableListOf<Uri>()
    private val translationQueue = ConcurrentLinkedQueue<Uri>()
    private var translationJob: Job? = null
    companion object {
        const val BATCH_SIZE = 10 // Số ảnh tải mỗi lần
    }

    init {
        loadAllRoomIds()
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
                Log.i(TAG, "Đã tải ${roomIds.size} ID phòng")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tải room IDs", e)
            }
        }
    }

    fun setImageUris(uris: List<Uri>, isNew: Boolean = false) {
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
                totalImagesToTranslate = 0
            )
        }
        newImageUris.clear()
        if (isNew) {
            newImageUris.addAll(uris)
        }
        Log.i(TAG, "Đã đặt ${uris.size} URI ảnh, isNew: $isNew")
    }

    fun addNewImageUris(uris: List<Uri>) {
        val currentUris = uiState.value.imageUris.toMutableList()
        currentUris.addAll(uris)
        _uiState.update {
            it.copy(
                imageUris = currentUris,
                translatedTexts = it.translatedTexts.filterKeys { uri -> uri in currentUris },
                translatedStatus = it.translatedStatus + uris.associateWith { false }
            )
        }
        newImageUris.clear()
        newImageUris.addAll(uris)
        Log.i(TAG, "Đã thêm ${uris.size} URI ảnh mới vào cuối")

        if (uiState.value.translationEnabled && uiState.value.translationMode != TranslationMode.OFF) {
            enqueueTranslation(uris)
        }
    }

    fun addNewImageUrisAtStart(uris: List<Uri>) {
        val currentUris = uiState.value.imageUris.toMutableList()
        currentUris.addAll(0, uris)
        _uiState.update {
            it.copy(
                imageUris = currentUris,
                translatedTexts = it.translatedTexts.filterKeys { uri -> uri in currentUris },
                translatedStatus = it.translatedStatus + uris.associateWith { false }
            )
        }
        newImageUris.clear()
        newImageUris.addAll(uris)
        Log.i(TAG, "Đã thêm ${uris.size} URI ảnh mới vào đầu")

        if (uiState.value.translationEnabled && uiState.value.translationMode != TranslationMode.OFF) {
            enqueueTranslation(uris)
        }
    }

    fun addNewImageUrisAtIndex(uris: List<Uri>, index: Int) {
        val currentUris = uiState.value.imageUris.toMutableList()
        val insertIndex = index.coerceIn(0, currentUris.size)
        currentUris.addAll(insertIndex, uris)
        _uiState.update {
            it.copy(
                imageUris = currentUris,
                translatedTexts = it.translatedTexts.filterKeys { uri -> uri in currentUris },
                translatedStatus = it.translatedStatus + uris.associateWith { false }
            )
        }
        newImageUris.clear()
        newImageUris.addAll(uris)
        Log.i(TAG, "Đã thêm ${uris.size} URI ảnh mới vào vị trí $insertIndex")

        if (uiState.value.translationEnabled && uiState.value.translationMode != TranslationMode.OFF) {
            enqueueTranslation(uris)
        }
    }

    fun loadRoom(roomId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Đang tải phòng $roomId")
                val (allImages, _, translations) = databaseHelper.getMangaRoom(roomId)
                val translatedStatus = mutableMapOf<Uri, Boolean>()
                val initialBatch = allImages.take(BATCH_SIZE)
                val remainingImages = allImages.drop(BATCH_SIZE)

                // Load initial batch
                val db = databaseHelper.readableDatabase
                val cursor = db.rawQuery(
                    """
                    SELECT ${DatabaseHelper.COLUMN_IMAGE_URI}, ${DatabaseHelper.COLUMN_IS_TRANSLATED} 
                    FROM ${DatabaseHelper.TABLE_IMAGES} 
                    WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ? 
                    ORDER BY ${DatabaseHelper.COLUMN_DISPLAY_ORDER} 
                    LIMIT $BATCH_SIZE
                    """, arrayOf(roomId.toString())
                )

                while (cursor.moveToNext()) {
                    val uri = Uri.parse(cursor.getString(0))
                    val isTranslated = cursor.getInt(1) == 1
                    translatedStatus[uri] = isTranslated
                }
                cursor.close()

                _uiState.update {
                    it.copy(
                        imageUris = initialBatch,
                        translatedTexts = translations.filterKeys { it in initialBatch },
                        translationEnabled = translations.isNotEmpty(),
                        translationMode = if (translations.isNotEmpty()) TranslationMode.OFFLINE else TranslationMode.OFF,
                        isTranslating = false,
                        roomId = roomId,
                        translatedStatus = translatedStatus,
                        sourceLanguages = translations.mapValues { "zh" },
                        remainingImages = remainingImages
                    )
                }
                Log.i(TAG, "Đã tải batch đầu tiên của phòng $roomId với ${initialBatch.size} ảnh")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tải phòng $roomId", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Tải phòng thất bại!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun loadMoreImages() {
        val remainingImages = uiState.value.remainingImages
        if (remainingImages.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
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

                    val textCursor = db.rawQuery(
                        """
                        SELECT original_text, translated_text, bounds_left, bounds_top, bounds_right, bounds_bottom, font_size
                        FROM translations 
                        WHERE ${DatabaseHelper.COLUMN_IMAGE_ID} IN (
                            SELECT ${DatabaseHelper.COLUMN_IMAGE_ID} FROM ${DatabaseHelper.TABLE_IMAGES} 
                            WHERE ${DatabaseHelper.COLUMN_IMAGE_URI} = ?
                        )
                        """, arrayOf(uri.toString())
                    )
                    val textBlocks = mutableListOf<TextBlockInfo>()
                    var originalText = ""
                    while (textCursor.moveToNext()) {
                        originalText = textCursor.getString(0) ?: ""
                        val translatedText = textCursor.getString(1)
                        val bounds = android.graphics.Rect(
                            textCursor.getInt(2),
                            textCursor.getInt(3),
                            textCursor.getInt(4),
                            textCursor.getInt(5)
                        )
                        val fontSize = textCursor.getFloat(6)
                        textBlocks.add(TextBlockInfo(translatedText, bounds, fontSize))
                    }
                    textCursor.close()
                    if (textBlocks.isNotEmpty()) {
                        translations[uri] = originalText to textBlocks
                    }
                }

                _uiState.update {
                    it.copy(
                        imageUris = it.imageUris + batch,
                        translatedTexts = it.translatedTexts + translations,
                        translatedStatus = it.translatedStatus + translatedStatus,
                        remainingImages = newRemaining
                    )
                }
                Log.i(TAG, "Đã tải thêm ${batch.size} ảnh, còn lại ${newRemaining.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi tải thêm ảnh", e)
            }
        }
    }    
    
    fun setTranslationMode(mode: TranslationMode) {
        val currentMode = uiState.value.translationMode
        
        _uiState.update {
            it.copy(
                translationMode = mode,
                translationEnabled = mode != TranslationMode.OFF
            )
        }
        Log.i(TAG, "Chế độ dịch được đặt thành $mode")

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
                        translatedStatus = imagesToRetranslate.associateWith { false }.toMutableMap()
                    )
                }

                translationQueue.clear()
                translationQueue.addAll(imagesToRetranslate)
                translationJob?.cancel()
                translationJob = viewModelScope.launch(Dispatchers.IO) {
                    processTranslationQueue()
                }
                Log.i(TAG, "Đang dịch lại tất cả ${imagesToRetranslate.size} ảnh với chế độ $mode")
            } else {
                // Only translate new images that haven't been translated yet
                val imagesToTranslate = uiState.value.imageUris.filter { uri ->
                    !(uiState.value.translatedStatus[uri] ?: false)
                }
                if (imagesToTranslate.isNotEmpty()) {
                    enqueueTranslation(imagesToTranslate)
                } else {
                    Log.i(TAG, "Không có ảnh mới để dịch")
                }
            }
        } else { // TranslationMode.OFF
            _uiState.update {
                it.copy(
                    isTranslating = false,
                    translatedTexts = emptyMap(),
                    sourceLanguages = emptyMap(),
                    translatedStatus = uiState.value.imageUris.associateWith { false }.toMutableMap()
                )
            }
            newImageUris.clear()
            translationQueue.clear()
            translationJob?.cancel()
            Log.i(TAG, "Đã tắt dịch và reset trạng thái")
        }
    }

    fun saveCurrentRoom() {
        viewModelScope.launch(Dispatchers.IO) {
            val imageCount = uiState.value.imageUris.size
            Log.i(TAG, "Đang lưu phòng hiện tại với $imageCount ảnh")
            if (imageCount == 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Không có ảnh để lưu!", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            try {
                val currentRoomId = uiState.value.roomId
                val roomId: Long = if (currentRoomId != null) {
                    // Nếu đã có roomId, update phòng
                    val updated = databaseHelper.updateMangaRoom(
                        currentRoomId,
                        uiState.value.imageUris,
                        uiState.value.translatedTexts
                    )
                    if (updated) currentRoomId else -1L
                } else {
                    // Nếu chưa có roomId, tạo phòng mới
                    databaseHelper.saveMangaRoom(
                        uiState.value.imageUris,
                        uiState.value.translatedTexts
                    )
                }
                if (roomId != -1L) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Đã lưu thành công!", Toast.LENGTH_SHORT).show()
                    }
                    _uiState.update { it.copy(roomId = roomId) }
                    galleryViewModel.notifyDataSaved()
                    loadAllRoomIds()
                    Log.i(TAG, "Phòng đã được lưu với ID: $roomId")
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
        }
    }

    /**
     * Xóa một ảnh khỏi phòng hiện tại (và DB)
     */
    fun removeImageFromRoom(uri: Uri) {
        val currentUris = uiState.value.imageUris.toMutableList()
        if (!currentUris.contains(uri)) return
        currentUris.remove(uri)
        val newTranslatedTexts = uiState.value.translatedTexts.filterKeys { it != uri }
        val newStatus = uiState.value.translatedStatus.filterKeys { it != uri }.toMutableMap()
        val newSourceLangs = uiState.value.sourceLanguages.filterKeys { it != uri }
        // Loại ảnh khỏi hàng đợi dịch nếu có
        translationQueue.remove(uri)
        // Nếu có roomId thì cập nhật DB, nếu không thì chỉ cập nhật UI
        val roomId = uiState.value.roomId
        if (roomId != null) {
            databaseHelper.updateMangaRoom(roomId, currentUris, newTranslatedTexts)
        }
        // Luôn cập nhật UI state
        _uiState.update {
            it.copy(
                imageUris = currentUris,
                translatedTexts = newTranslatedTexts,
                translatedStatus = newStatus,
                sourceLanguages = newSourceLangs
            )
        }
    }

    private fun enqueueTranslation(images: List<Uri>) {
        translationQueue.addAll(images)
        if (translationJob == null || translationJob?.isActive != true) {
            translationJob = viewModelScope.launch(Dispatchers.IO) {
                processTranslationQueue()
            }
        }
    }

    private suspend fun processTranslationQueue() {
        val maxBatchSize = 2 // Dịch song song 2 trang mỗi lượt
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
            // Dịch song song 2 ảnh bằng async/awaitAll
            val results = kotlinx.coroutines.coroutineScope {
                batch.map { uri ->
                    async(Dispatchers.IO) {
                        try {
                            val (original, translatedBlocks, sourceLang) = translationRepository.recognizeAndTranslateText(
                                uri,
                                uiState.value.translationMode
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
                        translatedTexts[uri] = original to (translatedBlocks as List<TextBlockInfo>)
                        sourceLanguages[uri] = sourceLang as String
                        completedCount++
                        _uiState.update {
                            it.copy(
                                translatedTexts = it.translatedTexts + translatedTexts,
                                sourceLanguages = it.sourceLanguages + sourceLanguages,
                                translatedStatus = it.translatedStatus + (uri to true),
                                translationProgress = completedCount
                            )
                        }
                        Log.i(TAG, "Đã dịch ảnh $uri")
                    } else {
                        Log.w(TAG, "Không nhận diện được văn bản trong ảnh $uri")
                    }
                } // Nếu ảnh đã bị xóa thì bỏ qua
            }
            // Sau mỗi đợt, delay 2 giây trước khi tiếp tục đợt mới nếu còn ảnh
            if (translationQueue.isNotEmpty()) {
                delay(2000)
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
        withContext(Dispatchers.Main) {
            Toast.makeText(getApplication(), "Dịch hoàn tất!", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Xóa toàn bộ session, ảnh, trạng thái dịch, trạng thái phòng, v.v. (reset sạch ViewModel)
     */
    fun clearSessionAndImages() {
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
                remainingImages = emptyList()
            )
        }
        newImageUris.clear()
        translationQueue.clear()
        translationJob?.cancel()
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
}

data class ViewerUiState(
    val imageUris: List<Uri> = emptyList(),
    val translationEnabled: Boolean = false,
    val translationMode: TranslationMode = TranslationMode.OFF,
    val isTranslating: Boolean = false,
    val translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>> = emptyMap(),
    val roomId: Long? = null,
    val translatedStatus: Map<Uri, Boolean> = emptyMap(),
    val translationProgress: Int = 0,
    val totalImagesToTranslate: Int = 0,
    val sourceLanguages: Map<Uri, String> = emptyMap(),
    val remainingImages: List<Uri> = emptyList()
)
