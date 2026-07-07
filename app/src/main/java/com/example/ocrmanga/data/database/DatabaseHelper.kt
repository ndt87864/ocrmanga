package com.example.ocrmanga.data.database

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import com.example.ocrmanga.data.models.ApiKeyInfo
import com.example.ocrmanga.data.models.ImageBlock
import com.example.ocrmanga.data.models.TextAlignMode
import com.example.ocrmanga.data.models.TextBlockInfo
import java.io.File
import java.io.FileOutputStream

/**
 * Lớp trợ giúp quản lý Cơ sở dữ liệu SQLite chính của ứng dụng.
 *
 * Thực hiện kiến trúc delegate: Logic nghiệp vụ lưu trữ chi tiết được phân rã ra các
 * DAO chuyên biệt ([ImageBlockDao], [TranslationDao], [RoomDao]) để đảm bảo tính
 * bảo trì cao và giới hạn kích thước file < 700 dòng theo đúng rule dự án.
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DatabaseSchema.DATABASE_NAME,
    null,
    DatabaseSchema.DATABASE_VERSION
) {

    internal val appContext = context

    // ── DELEGATE DAOS ──────────────────────────────────────────────────────
    private val imageBlockDao = ImageBlockDao(this)
    private val translationDao = TranslationDao(this)
    private val roomDao = RoomDao(this)

    companion object {
        private const val TAG = "DatabaseHelper"

        // Expose tên bảng & cột cho các DAO sử dụng thống nhất
        const val TABLE_ROOMS = DatabaseSchema.TABLE_ROOMS
        const val COLUMN_ROOM_ID = DatabaseSchema.COLUMN_ROOM_ID
        const val COLUMN_TITLE = DatabaseSchema.COLUMN_TITLE
        const val COLUMN_COVER_URI = DatabaseSchema.COLUMN_COVER_URI

        const val TABLE_IMAGES = DatabaseSchema.TABLE_IMAGES
        const val COLUMN_IMAGE_ID = DatabaseSchema.COLUMN_IMAGE_ID
        const val COLUMN_IMAGE_URI = DatabaseSchema.COLUMN_IMAGE_URI
        const val COLUMN_DISPLAY_ORDER = DatabaseSchema.COLUMN_DISPLAY_ORDER
        const val COLUMN_IS_TRANSLATED = DatabaseSchema.COLUMN_IS_TRANSLATED

        const val TABLE_TRANSLATIONS = DatabaseSchema.TABLE_TRANSLATIONS
        const val COLUMN_ORIGINAL_TEXT = DatabaseSchema.COLUMN_ORIGINAL_TEXT
        const val COLUMN_TRANSLATED_TEXT = DatabaseSchema.COLUMN_TRANSLATED_TEXT

        const val TABLE_API_KEYS = DatabaseSchema.TABLE_API_KEYS
        const val COLUMN_API_KEY_ID = DatabaseSchema.COLUMN_API_KEY_ID
        const val COLUMN_API_KEY_VALUE = DatabaseSchema.COLUMN_API_KEY_VALUE
        const val COLUMN_CREATED_DATE = DatabaseSchema.COLUMN_CREATED_DATE
        const val COLUMN_UPDATED_DATE = DatabaseSchema.COLUMN_UPDATED_DATE
        const val COLUMN_IS_ACTIVE = DatabaseSchema.COLUMN_IS_ACTIVE
        const val COLUMN_API_KEY_TYPE = DatabaseSchema.COLUMN_API_KEY_TYPE

        const val TABLE_ROOM_SETTINGS = DatabaseSchema.TABLE_ROOM_SETTINGS
        const val COLUMN_SETTING_ROOM_ID = DatabaseSchema.COLUMN_SETTING_ROOM_ID
        const val COLUMN_AUTO_TRANSLATE_NEW_IMAGES = DatabaseSchema.COLUMN_AUTO_TRANSLATE_NEW_IMAGES
        const val COLUMN_ANCIENT_TRANSLATION_ENABLED = DatabaseSchema.COLUMN_ANCIENT_TRANSLATION_ENABLED

        const val TABLE_IMAGE_BLOCKS = DatabaseSchema.TABLE_IMAGE_BLOCKS
        const val COLUMN_BLOCK_ID = DatabaseSchema.COLUMN_BLOCK_ID
        const val COLUMN_BLOCK_IMAGE_ID = DatabaseSchema.COLUMN_BLOCK_IMAGE_ID
        const val COLUMN_BLOCK_X = DatabaseSchema.COLUMN_BLOCK_X
        const val COLUMN_BLOCK_Y = DatabaseSchema.COLUMN_BLOCK_Y
        const val COLUMN_BLOCK_WIDTH = DatabaseSchema.COLUMN_BLOCK_WIDTH
        const val COLUMN_BLOCK_HEIGHT = DatabaseSchema.COLUMN_BLOCK_HEIGHT
        const val COLUMN_BLOCK_OVERLAY_TYPE = DatabaseSchema.COLUMN_BLOCK_OVERLAY_TYPE
        const val COLUMN_BLOCK_OVERLAY_COLOR = DatabaseSchema.COLUMN_BLOCK_OVERLAY_COLOR
        const val COLUMN_BLOCK_OVERLAY_BRIGHTNESS = DatabaseSchema.COLUMN_BLOCK_OVERLAY_BRIGHTNESS
        const val COLUMN_BLOCK_OVERLAY_ALPHA = DatabaseSchema.COLUMN_BLOCK_OVERLAY_ALPHA
        const val COLUMN_BLOCK_OVERLAY_SATURATION = DatabaseSchema.COLUMN_BLOCK_OVERLAY_SATURATION
        const val COLUMN_BLOCK_OVERLAY_INSET = DatabaseSchema.COLUMN_BLOCK_OVERLAY_INSET
        const val COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL = DatabaseSchema.COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL
        const val COLUMN_BLOCK_OVERLAY_INSET_VERTICAL = DatabaseSchema.COLUMN_BLOCK_OVERLAY_INSET_VERTICAL
        const val COLUMN_BLOCK_OVERLAY_ROTATION = DatabaseSchema.COLUMN_BLOCK_OVERLAY_ROTATION
        const val COLUMN_BLOCK_TEXT_COLOR = DatabaseSchema.COLUMN_BLOCK_TEXT_COLOR
        const val COLUMN_BLOCK_TEXT_BRIGHTNESS = DatabaseSchema.COLUMN_BLOCK_TEXT_BRIGHTNESS
        const val COLUMN_BLOCK_TEXT_BOLDNESS = DatabaseSchema.COLUMN_BLOCK_TEXT_BOLDNESS
        const val COLUMN_BLOCK_TEXT_SATURATION = DatabaseSchema.COLUMN_BLOCK_TEXT_SATURATION
        const val COLUMN_BLOCK_BORDER_COLOR = DatabaseSchema.COLUMN_BLOCK_BORDER_COLOR
        const val COLUMN_BLOCK_BORDER_BRIGHTNESS = DatabaseSchema.COLUMN_BLOCK_BORDER_BRIGHTNESS
        const val COLUMN_BLOCK_BORDER_BOLDNESS = DatabaseSchema.COLUMN_BLOCK_BORDER_BOLDNESS
        const val COLUMN_BLOCK_BORDER_THICKNESS = DatabaseSchema.COLUMN_BLOCK_BORDER_THICKNESS
        const val COLUMN_BLOCK_SHADOW_COLOR = DatabaseSchema.COLUMN_BLOCK_SHADOW_COLOR
        const val COLUMN_BLOCK_SHADOW_ALPHA = DatabaseSchema.COLUMN_BLOCK_SHADOW_ALPHA
        const val COLUMN_BLOCK_SHADOW_RADIUS = DatabaseSchema.COLUMN_BLOCK_SHADOW_RADIUS
        const val COLUMN_BLOCK_ROTATION = DatabaseSchema.COLUMN_BLOCK_ROTATION
        const val COLUMN_BLOCK_FONT_FAMILY = DatabaseSchema.COLUMN_BLOCK_FONT_FAMILY
        const val COLUMN_BLOCK_FONT_SIZE = DatabaseSchema.COLUMN_BLOCK_FONT_SIZE
        const val COLUMN_BLOCK_LINE_SPACING = DatabaseSchema.COLUMN_BLOCK_LINE_SPACING
        const val COLUMN_BLOCK_TEXT_ALIGN = DatabaseSchema.COLUMN_BLOCK_TEXT_ALIGN
        const val COLUMN_BLOCK_TEXT_GRADIENT_COLORS = DatabaseSchema.COLUMN_BLOCK_TEXT_GRADIENT_COLORS
        const val COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS = DatabaseSchema.COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS
        const val COLUMN_BLOCK_TEXT_GRADIENT_TYPE = DatabaseSchema.COLUMN_BLOCK_TEXT_GRADIENT_TYPE
        const val COLUMN_BLOCK_ORIGINAL_WIDTH = DatabaseSchema.COLUMN_BLOCK_ORIGINAL_WIDTH
        const val COLUMN_BLOCK_ORIGINAL_HEIGHT = DatabaseSchema.COLUMN_BLOCK_ORIGINAL_HEIGHT

        const val TABLE_CHANGE_IMAGES = DatabaseSchema.TABLE_CHANGE_IMAGES
        const val COLUMN_CHANGE_IMAGE_ID = DatabaseSchema.COLUMN_CHANGE_IMAGE_ID
        const val COLUMN_CHANGE_IMAGE_IMAGE_ID = DatabaseSchema.COLUMN_CHANGE_IMAGE_IMAGE_ID
        const val COLUMN_CHANGE_IMAGE_ROOM_ID = DatabaseSchema.COLUMN_CHANGE_IMAGE_ROOM_ID
        const val COLUMN_CHANGE_IMAGE_FLAG = DatabaseSchema.COLUMN_CHANGE_IMAGE_FLAG

        fun createImagePickerIntent(): android.content.Intent {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
            return intent
        }
    }

    // ── DATABASE LIFECYCLE ─────────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        DatabaseSchema.createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        DatabaseSchema.upgradeDatabase(db, oldVersion, newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No-op to prevent crash on local developer downgrade
    }

    init {
        migrateRoomImageLinks()
        cleanupDuplicateTranslations()
        
        // Remove NVIDIA keys if any (migration logic from legacy helper)
        try {
            val db = writableDatabase
            db.delete(TABLE_API_KEYS, "$COLUMN_API_KEY_TYPE = ?", arrayOf("nvidia"))
        } catch (e: Exception) { /* ignore */ }
    }

    // ── DELEGATE CALLS & BACKWARD COMPATIBILITY APIs ───────────────────────

    // --- Image Blocks CRUD ---
    fun insertImageBlock(
        imageId: Long, x: Int, y: Int, width: Int, height: Int,
        overlayType: Int = 0, overlayColor: Int? = null, overlayBrightness: Float = 1.0f,
        overlayAlpha: Float = 1.0f, overlaySaturation: Float = 1.0f, overlayInset: Float = 0f,
        overlayInsetHorizontal: Float = 0f, overlayInsetVertical: Float = 0f, overlayRotation: Float? = null,
        textColor: Int? = null, textBrightness: Float = 1.0f, textBoldness: Float = 1.0f,
        textSaturation: Float = 1.0f, borderColor: Int? = null, borderBrightness: Float = 1.0f,
        borderBoldness: Float = 1.0f, borderThickness: Float = 0f, shadowColor: Int? = null,
        shadowAlpha: Float = 1.0f, shadowRadius: Float = 0f, rotation: Float = 0f,
        fontFamily: String = "", fontSize: Float = 12f, lineSpacing: Float = 1.0f,
        textAlign: String = "CENTER", textGradientColors: List<Int>? = null,
        textGradientOffsets: List<Float>? = null, textGradientType: Int = 0,
        originalWidth: Int? = null, originalHeight: Int? = null
    ): Long {
        return imageBlockDao.insert(
            imageId, x, y, width, height, overlayType, overlayColor, overlayBrightness,
            overlayAlpha, overlaySaturation, overlayInset, overlayInsetHorizontal, overlayInsetVertical, overlayRotation,
            textColor, textBrightness, textBoldness, textSaturation, borderColor, borderBrightness,
            borderBoldness, borderThickness, shadowColor, shadowAlpha, shadowRadius, rotation,
            fontFamily, fontSize, lineSpacing, textAlign, textGradientColors, textGradientOffsets,
            textGradientType, originalWidth, originalHeight
        )
    }

    fun getBlocksForImage(imageId: Long): List<ImageBlock> = imageBlockDao.getForImage(imageId)

    fun getBlocksForImageAsTextBlockInfo(imageId: Long): List<TextBlockInfo> {
        val db = readableDatabase
        val blocks = mutableListOf<TextBlockInfo>()
        val query = """
            SELECT t.x as trans_x, t.y as trans_y, t.width as trans_width, t.height as trans_height,
                   t.original_text, t.translated_text, b.*
            FROM $TABLE_TRANSLATIONS t
            LEFT JOIN $TABLE_IMAGE_BLOCKS b ON t.text_id = b.$COLUMN_BLOCK_ID
            WHERE t.$COLUMN_IMAGE_ID = ? AND t.pending_delete = 0
            ORDER BY t.text_id ASC
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(imageId.toString()))
        val origTextIdx = cursor.getColumnIndex("original_text")
        val transTextIdx = cursor.getColumnIndex("translated_text")

        while (cursor.moveToNext()) {
            val block = imageBlockDao.cursorToTextBlockInfo(cursor)
            val orig = if (origTextIdx >= 0) cursor.getStringOrNull(origTextIdx) else null
            val trans = if (transTextIdx >= 0) cursor.getStringOrNull(transTextIdx) else null

            blocks.add(block.copy(
                text = trans ?: "",
                originalText = orig ?: trans ?: ""
            ))
        }
        cursor.close()
        return blocks
    }

    fun deleteBlocksForImage(imageId: Long) {
        imageBlockDao.deleteForImage(imageId)
    }

    // --- Translations CRUD ---
    fun deleteAllTranslationsForImage(imageId: Long) {
        roomDao.deleteAllTranslationsForImage(imageId)
    }

    fun getOriginalTextsForImage(imageId: Long): List<String> = translationDao.getOriginalTexts(imageId)

    fun markTranslationsAsPendingDelete(imageId: Long) {
        translationDao.markAsPendingDelete(imageId)
    }

    fun deletePendingTranslations(imageId: Long): Int = translationDao.deletePending(imageId)

    fun clearPendingDeleteStatus(imageId: Long) {
        translationDao.clearPendingDelete(imageId)
    }

    fun clearPendingDeleteStatusForRoom(roomId: Long) {
        translationDao.clearPendingDeleteForRoom(roomId)
    }

    // --- Change Management ---
    fun ensureChangeRecord(imageId: Long, roomId: Long) {
        translationDao.ensureChangeRecord(imageId, roomId)
    }

    fun markImageChanged(imageId: Long, roomId: Long): Int = translationDao.markImageChanged(imageId, roomId)

    fun clearImageChange(imageId: Long) {
        translationDao.clearImageChange(imageId)
    }

    fun clearChangedFlagForImage(imageId: Long) {
        translationDao.clearImageChange(imageId)
    }

    fun clearAllChangedFlagsForRoom(roomId: Long) {
        translationDao.clearAllChangedFlagsForRoom(roomId)
    }

    fun getChangedImageIdsForRoom(roomId: Long): List<Long> = translationDao.getChangedImageIds(roomId)

    fun getNumChangedImages(roomId: Long): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CHANGE_IMAGES WHERE room_id = ? AND is_changed = 1", arrayOf(roomId.toString()))
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    // --- Room & Images CRUD ---
    fun getRoomTitle(roomId: Long): String? = roomDao.getRoomTitle(roomId)

    fun updateRoomTitle(roomId: Long, newTitle: String) {
        roomDao.updateRoomTitle(roomId, newTitle)
    }

    fun getAllRooms(): List<Triple<Long, String, Uri>> = roomDao.getAllRooms()

    fun deleteRoom(roomId: Long) {
        roomDao.deleteRoom(roomId)
    }

    fun deleteImageFromRoom(imageId: Long) {
        roomDao.deleteImageFromRoom(imageId)
    }

    fun getImageIdByUri(uri: Uri): Long? = roomDao.getImageIdByUri(uri)

    fun getImageCountForRoom(roomId: Long): Int = roomDao.getImageCount(roomId)

    fun updateImageUri(imageId: Long, newUri: Uri) {
        roomDao.updateImageUri(imageId, newUri)
    }

    fun migrateRoomImageLinks() {
        roomDao.migrateRoomImageLinks()
    }

    // --- Room Settings ---
    fun getAutoTranslateSetting(roomId: Long): Boolean = roomDao.getAutoTranslateSetting(roomId)

    fun setAutoTranslateSetting(roomId: Long, enabled: Boolean) {
        roomDao.setAutoTranslateSetting(roomId, enabled)
    }

    fun getAncientTranslationSetting(roomId: Long): Boolean = roomDao.getAncientTranslationSetting(roomId)

    fun setAncientTranslationSetting(roomId: Long, enabled: Boolean) {
        roomDao.setAncientTranslationSetting(roomId, enabled)
    }

    // --- API Key Management ---
    fun getAllApiKeysWithStats(): List<ApiKeyInfo> = roomDao.getAllApiKeysWithStats()

    fun getAllApiKeys(): List<Pair<String, String>> = roomDao.getAllApiKeys()

    fun insertApiKey(apiKey: String, type: String = "default", allowedModels: String = "") {
        roomDao.insertApiKey(apiKey, type, allowedModels)
    }

    fun updateApiKey(oldKey: String, newKey: String, newType: String? = null, allowedModels: String? = null) {
        roomDao.updateApiKey(oldKey, newKey, newType, allowedModels)
    }

    fun updateApiKeyWithType(oldKey: String, oldType: String, newKey: String, newType: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_API_KEY_VALUE, newKey)
            put(COLUMN_API_KEY_TYPE, newType)
        }
        db.update(TABLE_API_KEYS, values, "$COLUMN_API_KEY_VALUE = ? AND $COLUMN_API_KEY_TYPE = ?", arrayOf(oldKey, oldType))
    }

    fun updateApiKeyAllowedModels(apiKey: String, allowedModels: String) {
        roomDao.updateApiKeyAllowedModels(apiKey, allowedModels)
    }

    fun deleteApiKey(apiKey: String) {
        roomDao.deleteApiKey(apiKey)
    }

    fun deleteApiKeyWithType(key: String, type: String) {
        val db = writableDatabase
        db.delete(TABLE_API_KEYS, "$COLUMN_API_KEY_VALUE = ? AND $COLUMN_API_KEY_TYPE = ?", arrayOf(key, type))
    }

    fun updateApiKeyStatus(apiKey: String, isActive: Boolean): Boolean = roomDao.updateApiKeyStatus(apiKey, isActive)

    fun getApiKeyStatus(apiKey: String): Boolean = roomDao.getApiKeyStatus(apiKey)

    fun updateApiKeyStats(apiKey: String) {
        // No-op in simplified version
    }

    // ── ADVANCED CORE BUSINESS LOGIC ───────────────────────────────────────

    // Private helper data classes (đã chuyển lên trên hoặc giữ nội bộ nếu cần)
    private data class BlockData(
        val blockId: Long, val x: Int, val y: Int, val width: Int, val height: Int,
        val overlayColor: Int?, val overlayAlpha: Float, val overlaySaturation: Float,
        val overlayInset: Float, val overlayInsetH: Float, val overlayInsetV: Float,
        val overlayRotation: Float?, val overlayType: Int, val textColor: Int?,
        val textBoldness: Float, val textSaturation: Float, val fontSize: Float,
        val fontFamily: String?, val rotation: Float, val lineSpacing: Float,
        val borderColor: Int?, val borderThickness: Float, val shadowColor: Int?,
        val shadowAlpha: Float, val shadowRadius: Float,
        val textAlign: TextAlignMode,
        val textGradientColors: List<Int>?, val textGradientOffsets: List<Float>?,
        val textGradientType: Int
    )

    private data class TranslationData(
        val translatedText: String, val originalText: String,
        val x: Int, val y: Int, val width: Int, val height: Int,
        val originalTextColor: Int?
    )

    private fun copyImageToInternalStorage(originalUri: Uri, directory: File, fileName: String): File? {
        try {
            val authority = originalUri.authority ?: ""
            val isDownloads = originalUri.scheme == "content" && authority.contains("downloads")
            val normalizedName = if (fileName.endsWith(".webp", true)) fileName else {
                val base = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
                "$base.webp"
            }
            val newFile = File(directory, normalizedName)
            val inputStream = appContext.contentResolver.openInputStream(originalUri) ?: return null
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            val outStream = FileOutputStream(newFile)
            bitmap.compress(Bitmap.CompressFormat.WEBP, 90, outStream)
            outStream.close()
            return newFile
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while copying image $originalUri", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy & compress image $originalUri", e)
            return null
        }
    }

    private fun deleteOriginalImage(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val contentResolver = appContext.contentResolver
            when {
                uri.scheme == "content" && uri.authority?.contains("media") == true -> {
                    contentResolver.delete(uri, null, null)
                }
                uri.scheme == "content" && uri.authority?.contains("downloads") == true -> {
                    Log.w(TAG, "Skipping Downloads provider delete")
                }
                uri.scheme == "content" && uri.authority?.contains("documents") == true -> {
                    try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (e: Exception) { /* ignore */ }
                }
                uri.scheme == "file" || uri.scheme == null -> {
                    val file = File(uri.path ?: "")
                    if (file.exists()) file.delete()
                }
                else -> contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete image: $uri", e)
        }
    }

    fun getMangaRoom(roomId: Long): Triple<List<Uri>, List<Int>, Map<Uri, Pair<String, List<TextBlockInfo>>>> {
        val db = writableDatabase
        cleanupDuplicateImagesStrict(roomId)

        db.execSQL("DELETE FROM $TABLE_TRANSLATIONS WHERE $COLUMN_IMAGE_ID IN (SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?) AND pending_delete = 1", arrayOf(roomId.toString()))
        db.execSQL("UPDATE $TABLE_CHANGE_IMAGES SET $COLUMN_CHANGE_IMAGE_FLAG = 0 WHERE $COLUMN_CHANGE_IMAGE_ROOM_ID = ?", arrayOf(roomId.toString()))

        val images = mutableListOf<Uri>()
        val orders = mutableListOf<Int>()
        val translations = mutableMapOf<Uri, Pair<String, MutableList<TextBlockInfo>>>()

        val seenUris = mutableSetOf<String>()
        val seenImageIds = mutableSetOf<Long>()
        val seenFilenames = mutableSetOf<String>()
        val missingImageIds = mutableListOf<Long>()

        val imageCursor = db.rawQuery("""
            SELECT $COLUMN_IMAGE_URI, $COLUMN_DISPLAY_ORDER, $COLUMN_IMAGE_ID
            FROM $TABLE_IMAGES 
            WHERE $COLUMN_ROOM_ID = ? 
            ORDER BY $COLUMN_DISPLAY_ORDER
        """.trimIndent(), arrayOf(roomId.toString()))

        while (imageCursor.moveToNext()) {
            val uriStr = imageCursor.getString(0)
            val imageId = imageCursor.getLong(2)
            val filename = try { Uri.parse(uriStr).lastPathSegment ?: uriStr } catch (e: Exception) { uriStr }

            if (seenImageIds.contains(imageId) || seenUris.contains(uriStr) || seenFilenames.contains(filename)) continue

            val uri = Uri.parse(uriStr)
            val filePath = uri.path
            if (filePath != null && !File(filePath).exists()) {
                missingImageIds.add(imageId)
                continue
            }

            seenImageIds.add(imageId)
            seenUris.add(uriStr)
            seenFilenames.add(filename)

            val order = imageCursor.getInt(1)
            images.add(uri)
            orders.add(order)

            val translationsCursor = db.rawQuery("""
                SELECT translated_text, original_text, original_text_color FROM $TABLE_TRANSLATIONS
                WHERE $COLUMN_IMAGE_ID = ? AND (pending_delete IS NULL OR pending_delete = 0)
                ORDER BY text_id ASC
            """.trimIndent(), arrayOf(imageId.toString()))

            val translatedTexts = mutableListOf<String>()
            val originalTexts = mutableListOf<String>()
            val originalTextColors = mutableListOf<Int?>()
            while (translationsCursor.moveToNext()) {
                translatedTexts.add(translationsCursor.getString(0) ?: "")
                originalTexts.add(translationsCursor.getString(1) ?: "")
                originalTextColors.add(if (translationsCursor.isNull(2)) null else translationsCursor.getInt(2))
            }
            translationsCursor.close()

            val textBlocks = mutableListOf<TextBlockInfo>()
            val blocks = imageBlockDao.getForImage(imageId)
            blocks.forEachIndexed { blockIndex, block ->
                val transText = if (blockIndex < translatedTexts.size) translatedTexts[blockIndex] else ""
                val origText = if (blockIndex < originalTexts.size) originalTexts[blockIndex] else null
                val origColor = if (blockIndex < originalTextColors.size) originalTextColors[blockIndex] else null
                val bounds = Rect(block.x, block.y, block.x + block.width, block.y + block.height)
                
                textBlocks.add(imageBlockDao.blockToTextBlockInfo(block, transText, origText, origColor, bounds))
            }

            if (textBlocks.isNotEmpty()) {
                translations[uri] = "" to textBlocks
            }
        }
        imageCursor.close()

        if (missingImageIds.isNotEmpty()) {
            for (imageId in missingImageIds) {
                db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
                db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            }
            // Reorder
            val reorder = db.rawQuery("SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? ORDER BY $COLUMN_DISPLAY_ORDER ASC", arrayOf(roomId.toString()))
            var newOrder = 0
            while (reorder.moveToNext()) {
                val imgId = reorder.getLong(0)
                val vals = ContentValues().apply { put(COLUMN_DISPLAY_ORDER, newOrder) }
                db.update(TABLE_IMAGES, vals, "$COLUMN_IMAGE_ID = ?", arrayOf(imgId.toString()))
                newOrder++
            }
            reorder.close()
        }

        return Triple(images, orders, translations)
    }

    fun getMangaRoomOptimized(roomId: Long): Triple<List<Uri>, List<Int>, Map<Uri, Pair<String, List<TextBlockInfo>>>> {
        val db = writableDatabase
        cleanupDuplicateImagesStrict(roomId)

        db.execSQL("DELETE FROM $TABLE_TRANSLATIONS WHERE $COLUMN_IMAGE_ID IN (SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?) AND pending_delete = 1", arrayOf(roomId.toString()))
        db.execSQL("UPDATE $TABLE_CHANGE_IMAGES SET $COLUMN_CHANGE_IMAGE_FLAG = 0 WHERE $COLUMN_CHANGE_IMAGE_ROOM_ID = ?", arrayOf(roomId.toString()))

        val images = mutableListOf<Uri>()
        val orders = mutableListOf<Int>()
        val translations = mutableMapOf<Uri, Pair<String, MutableList<TextBlockInfo>>>()

        val seenUris = mutableSetOf<String>()
        val seenImageIds = mutableSetOf<Long>()
        val seenFilenames = mutableSetOf<String>()
        val missingImageIds = mutableListOf<Long>()
        val imageIdToUri = mutableMapOf<Long, Uri>()

        val imageCursor = db.rawQuery("""
            SELECT $COLUMN_IMAGE_URI, $COLUMN_DISPLAY_ORDER, $COLUMN_IMAGE_ID
            FROM $TABLE_IMAGES
            WHERE $COLUMN_ROOM_ID = ?
            ORDER BY $COLUMN_DISPLAY_ORDER
        """.trimIndent(), arrayOf(roomId.toString()))

        while (imageCursor.moveToNext()) {
            val uriStr = imageCursor.getString(0)
            val imageId = imageCursor.getLong(2)
            val filename = try { Uri.parse(uriStr).lastPathSegment ?: uriStr } catch (e: Exception) { uriStr }

            if (seenImageIds.contains(imageId) || seenUris.contains(uriStr) || seenFilenames.contains(filename)) continue

            val uri = Uri.parse(uriStr)
            val filePath = uri.path
            if (filePath != null && !File(filePath).exists()) {
                missingImageIds.add(imageId)
                continue
            }

            seenImageIds.add(imageId)
            seenUris.add(uriStr)
            seenFilenames.add(filename)

            val order = imageCursor.getInt(1)
            images.add(uri)
            orders.add(order)
            imageIdToUri[imageId] = uri
        }
        imageCursor.close()

        val translationsByImageId = mutableMapOf<Long, MutableList<TranslationData>>()
        if (seenImageIds.isNotEmpty()) {
            val placeholders = seenImageIds.joinToString(",") { "?" }
            val transCursor = db.rawQuery("""
                SELECT $COLUMN_IMAGE_ID, translated_text, original_text, x, y, width, height, original_text_color
                FROM $TABLE_TRANSLATIONS
                WHERE $COLUMN_IMAGE_ID IN ($placeholders)
                AND (pending_delete IS NULL OR pending_delete = 0)
                ORDER BY $COLUMN_IMAGE_ID, text_id ASC
            """.trimIndent(), seenImageIds.map { it.toString() }.toTypedArray())

            while (transCursor.moveToNext()) {
                val imgId = transCursor.getLong(0)
                translationsByImageId.getOrPut(imgId) { mutableListOf() }.add(
                    TranslationData(
                        transCursor.getString(1) ?: "",
                        transCursor.getString(2) ?: "",
                        transCursor.getInt(3),
                        transCursor.getInt(4),
                        transCursor.getInt(5),
                        transCursor.getInt(6),
                        if (transCursor.isNull(7)) null else transCursor.getInt(7)
                    )
                )
            }
            transCursor.close()
        }

        val blocksDataByImageId = mutableMapOf<Long, MutableList<BlockData>>()
        if (seenImageIds.isNotEmpty()) {
            val placeholders = seenImageIds.joinToString(",") { "?" }
            val blocksCursor = db.rawQuery("""
                SELECT * FROM $TABLE_IMAGE_BLOCKS
                WHERE $COLUMN_BLOCK_IMAGE_ID IN ($placeholders)
                ORDER BY $COLUMN_BLOCK_IMAGE_ID, $COLUMN_BLOCK_ID ASC
            """.trimIndent(), seenImageIds.map { it.toString() }.toTypedArray())

            val blockIdCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_ID)
            val imgIdCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_IMAGE_ID)
            val xCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_X)
            val yCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_Y)
            val wCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_WIDTH)
            val hCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_HEIGHT)
            val overlayColorCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_COLOR)
            val overlayAlphaCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_ALPHA)
            val overlaySatCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_SATURATION)
            val overlayInsetCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET)
            val overlayInsetHCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL)
            val overlayInsetVCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET_VERTICAL)
            val overlayRotCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_ROTATION)
            val overlayTypeCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_TYPE)
            val textColorCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_COLOR)
            val textBoldCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_BOLDNESS)
            val textSatCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_SATURATION)
            val fontSizeCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_SIZE)
            val fontFamilyCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_FAMILY)
            val rotationCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_ROTATION)
            val lineSpacingCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_LINE_SPACING)
            val borderColorCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_COLOR)
            val borderThickCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_THICKNESS)
            val shadowColorCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_COLOR)
            val shadowAlphaCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_ALPHA)
            val shadowRadiusCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_RADIUS)
            val textAlignCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_ALIGN)
            val textGradColorsCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_COLORS)
            val textGradOffsetsCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS)
            val textGradTypeCol = blocksCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_TYPE)

            while (blocksCursor.moveToNext()) {
                val imgId = blocksCursor.getLong(imgIdCol)
                blocksDataByImageId.getOrPut(imgId) { mutableListOf() }.add(
                    BlockData(
                        blockId = blocksCursor.getLong(blockIdCol),
                        x = blocksCursor.getInt(xCol),
                        y = blocksCursor.getInt(yCol),
                        width = blocksCursor.getInt(wCol),
                        height = blocksCursor.getInt(hCol),
                        overlayColor = if (!blocksCursor.isNull(overlayColorCol)) blocksCursor.getInt(overlayColorCol) else null,
                        overlayAlpha = blocksCursor.getDouble(overlayAlphaCol).toFloat(),
                        overlaySaturation = blocksCursor.getDouble(overlaySatCol).toFloat(),
                        overlayInset = blocksCursor.getDouble(overlayInsetCol).toFloat(),
                        overlayInsetH = blocksCursor.getDouble(overlayInsetHCol).toFloat(),
                        overlayInsetV = blocksCursor.getDouble(overlayInsetVCol).toFloat(),
                        overlayRotation = if (!blocksCursor.isNull(overlayRotCol)) blocksCursor.getDouble(overlayRotCol).toFloat() else null,
                        overlayType = blocksCursor.getInt(overlayTypeCol),
                        textColor = if (!blocksCursor.isNull(textColorCol)) { val c = blocksCursor.getInt(textColorCol); if (c != 0) c else null } else null,
                        textBoldness = blocksCursor.getDouble(textBoldCol).toFloat(),
                        textSaturation = blocksCursor.getDouble(textSatCol).toFloat(),
                        fontSize = blocksCursor.getDouble(fontSizeCol).toFloat(),
                        fontFamily = blocksCursor.getString(fontFamilyCol),
                        rotation = blocksCursor.getDouble(rotationCol).toFloat(),
                        lineSpacing = blocksCursor.getDouble(lineSpacingCol).toFloat(),
                        borderColor = if (!blocksCursor.isNull(borderColorCol)) blocksCursor.getInt(borderColorCol) else null,
                        borderThickness = blocksCursor.getDouble(borderThickCol).toFloat(),
                        shadowColor = if (!blocksCursor.isNull(shadowColorCol)) blocksCursor.getInt(shadowColorCol) else null,
                        shadowAlpha = blocksCursor.getDouble(shadowAlphaCol).toFloat(),
                        shadowRadius = blocksCursor.getDouble(shadowRadiusCol).toFloat(),
                        textAlign = try { TextAlignMode.valueOf(blocksCursor.getString(textAlignCol)) } catch (e: Exception) { TextAlignMode.CENTER },
                        textGradientColors = try { blocksCursor.getString(textGradColorsCol)?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() } } catch (e: Exception) { null },
                        textGradientOffsets = try { blocksCursor.getString(textGradOffsetsCol)?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toFloatOrNull() } } catch (e: Exception) { null },
                        textGradientType = try { blocksCursor.getInt(textGradTypeCol) } catch (e: Exception) { 0 }
                    )
                )
            }
            blocksCursor.close()
        }

        for (imageId in seenImageIds) {
            val uri = imageIdToUri[imageId] ?: continue
            val transList = translationsByImageId[imageId] ?: emptyList()
            val blocks = blocksDataByImageId[imageId] ?: emptyList()
            val textBlocks = mutableListOf<TextBlockInfo>()

            blocks.forEachIndexed { blockIndex, b ->
                val transData = if (blockIndex < transList.size) transList[blockIndex] else null
                val bounds = if (transData != null && transData.width > 0 && transData.height > 0) {
                    Rect(transData.x, transData.y, transData.x + transData.width, transData.y + transData.height)
                } else {
                    Rect(b.x, b.y, b.x + b.width, b.y + b.height)
                }
                val translatedText = transData?.translatedText ?: ""
                val originalText = transData?.originalText ?: ""
                val finalOriginalText = originalText.takeIf { it.isNotBlank() } ?: translatedText.takeIf { it.isNotBlank() }

                val mappedBlock = ImageBlock(
                    b.blockId, imageId, b.x, b.y, b.width, b.height, b.overlayType, b.overlayColor,
                    b.overlayAlpha, b.overlayAlpha, b.overlaySaturation, b.overlayInset, b.overlayInsetH, b.overlayInsetV, b.overlayRotation,
                    b.textColor, b.overlayAlpha, b.textBoldness, b.textSaturation, b.borderColor, b.overlayAlpha, b.overlayAlpha, b.borderThickness,
                    b.shadowColor, b.shadowAlpha, b.shadowRadius, b.rotation, b.fontFamily ?: "mto_astro_city", b.fontSize, b.textAlign,
                    b.textGradientColors, b.textGradientOffsets, b.textGradientType
                )

                textBlocks.add(imageBlockDao.blockToTextBlockInfo(mappedBlock, translatedText, finalOriginalText, transData?.originalTextColor, bounds))
            }
            translations[uri] = "" to textBlocks
        }
        return Triple(images, orders, translations)
    }

    fun getTranslationsForImages(imageUris: List<Uri>): Map<Uri, Pair<String, List<TextBlockInfo>>> {
        val db = readableDatabase
        val result = mutableMapOf<Uri, Pair<String, List<TextBlockInfo>>>()
        if (imageUris.isEmpty()) return result

        val uriStrings = imageUris.map { it.toString() }
        val placeholders = uriStrings.joinToString(",") { "?" }
        val imageIdMap = mutableMapOf<Uri, Long>()

        val imageCursor = db.rawQuery("""
            SELECT $COLUMN_IMAGE_URI, $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_URI IN ($placeholders)
        """.trimIndent(), uriStrings.toTypedArray())

        while (imageCursor.moveToNext()) {
            val uriStr = imageCursor.getString(0)
            imageIdMap[Uri.parse(uriStr)] = imageCursor.getLong(1)
        }
        imageCursor.close()

        for (uri in imageUris) {
            val imageId = imageIdMap[uri] ?: continue
            val transCursor = db.rawQuery("""
                SELECT translated_text, original_text, x, y, width, height, original_text_color FROM $TABLE_TRANSLATIONS
                WHERE $COLUMN_IMAGE_ID = ? AND (pending_delete IS NULL OR pending_delete = 0)
                ORDER BY text_id ASC
            """.trimIndent(), arrayOf(imageId.toString()))

            val transList = mutableListOf<TranslationData>()
            while (transCursor.moveToNext()) {
                transList.add(
                    TranslationData(
                        transCursor.getString(0) ?: "",
                        transCursor.getString(1) ?: "",
                        transCursor.getInt(2),
                        transCursor.getInt(3),
                        transCursor.getInt(4),
                        transCursor.getInt(5),
                        if (transCursor.isNull(6)) null else transCursor.getInt(6)
                    )
                )
            }
            transCursor.close()

            val textBlocks = mutableListOf<TextBlockInfo>()
            val blocks = imageBlockDao.getForImage(imageId)
            blocks.forEachIndexed { blockIndex, b ->
                val transData = if (blockIndex < transList.size) transList[blockIndex] else null
                val bounds = if (transData != null && transData.width > 0 && transData.height > 0) {
                    Rect(transData.x, transData.y, transData.x + transData.width, transData.y + transData.height)
                } else {
                    Rect(b.x, b.y, b.x + b.width, b.y + b.height)
                }
                val translatedText = transData?.translatedText ?: ""
                val originalText = transData?.originalText ?: ""
                val finalOriginalText = originalText.takeIf { it.isNotBlank() } ?: translatedText.takeIf { it.isNotBlank() }

                textBlocks.add(imageBlockDao.blockToTextBlockInfo(b, translatedText, finalOriginalText, transData?.originalTextColor, bounds))
            }
            result[uri] = "" to textBlocks
        }
        return result
    }

    fun replaceImageWithCopy(imageId: Long, newUri: Uri): Uri? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cur = db.rawQuery("SELECT $COLUMN_ROOM_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            if (!cur.moveToFirst()) {
                cur.close()
                return null
            }
            val roomId = cur.getLong(0)
            val oldUriStr = cur.getString(1)
            cur.close()

            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            imagesDir.mkdirs()

            var storedFile: File? = null
            try {
                val prevPath = try { Uri.parse(oldUriStr).path } catch (e: Exception) { null }
                if (!prevPath.isNullOrBlank()) {
                    val prev = File(prevPath)
                    if (prev.exists() && prev.parentFile?.absolutePath?.startsWith(imagesDir.absolutePath) == true) {
                        val copiedOver = copyImageToInternalStorage(newUri, prev.parentFile!!, prev.name)
                        if (copiedOver?.exists() == true) storedFile = copiedOver
                    }
                }
            } catch (e: Exception) { /* ignore */ }

            if (storedFile == null) {
                val fileName = "image_${imageId}.webp"
                val copied = copyImageToInternalStorage(newUri, imagesDir, fileName) ?: return null
                storedFile = copied
                try {
                    val prevFile = File(Uri.parse(oldUriStr).path ?: "")
                    if (prevFile.exists() && prevFile.parentFile?.absolutePath?.startsWith(imagesDir.absolutePath) == true && prevFile.absolutePath != storedFile.absolutePath) {
                        prevFile.delete()
                    }
                } catch (e: Exception) { /* ignore */ }
            }

            val storedUri = Uri.fromFile(storedFile)
            val values = ContentValues().apply { put(COLUMN_IMAGE_URI, storedUri.toString()) }
            db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            try { deleteOriginalImage(newUri) } catch (e: Exception) { /* ignore */ }

            db.setTransactionSuccessful()
            return storedUri
        } catch (e: Exception) {
            Log.e(TAG, "replaceImageWithCopy failed", e)
            return null
        } finally {
            db.endTransaction()
        }
    }

    fun replaceImageFileOnly(imageId: Long, newUri: Uri): Uri? {
        try {
            val db = readableDatabase
            val cur = db.rawQuery("SELECT $COLUMN_ROOM_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            if (!cur.moveToFirst()) {
                cur.close()
                return null
            }
            val roomId = cur.getLong(0)
            val oldUriStr = cur.getString(1)
            cur.close()

            val oldUri = Uri.parse(oldUriStr)
            val oldFilePath = oldUri.path ?: return null
            val oldFile = File(oldFilePath)
            if (!oldFile.exists()) return null

            val parentDir = oldFile.parentFile ?: return null
            val fileName = oldFile.name
            val tempFileName = "temp_replace_${System.currentTimeMillis()}_$fileName"
            val tempFile = copyImageToInternalStorage(newUri, parentDir, tempFileName) ?: return null

            oldFile.delete()
            val finalFile = File(parentDir, fileName)
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            try { deleteOriginalImage(newUri) } catch (e: Exception) { /* ignore */ }
            return oldUri
        } catch (e: Exception) {
            Log.e(TAG, "replaceImageFileOnly failed", e)
            return null
        }
    }

    fun saveMangaRoom(
        imageUris: List<Uri>,
        translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
        title: String? = null,
        translatedStatus: Map<Uri, Boolean>? = null
    ): Long {
        if (imageUris.isEmpty()) return -1L
        val db = writableDatabase
        db.beginTransaction()
        var roomId = -1L
        try {
            val roomTitle = title ?: "Phòng " + System.currentTimeMillis()
            val values = ContentValues().apply {
                put(COLUMN_TITLE, roomTitle)
                put(COLUMN_COVER_URI, imageUris.first().toString())
            }
            roomId = db.insertOrThrow(TABLE_ROOMS, null, values)

            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            imagesDir.mkdirs()

            val coverFile = copyImageToInternalStorage(imageUris.first(), imagesDir, "cover.webp")
            if (coverFile != null) {
                val coverUri = Uri.fromFile(coverFile)
                val roomValues = ContentValues().apply { put(COLUMN_COVER_URI, coverUri.toString()) }
                db.update(TABLE_ROOMS, roomValues, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
                deleteOriginalImage(imageUris.first())
            }

            imageUris.forEachIndexed { index, originalUri ->
                val fileName = "image_$index.webp"
                val newFile = copyImageToInternalStorage(originalUri, imagesDir, fileName)
                if (newFile != null) {
                    val newUri = Uri.fromFile(newFile)
                    val imageValues = ContentValues().apply {
                        put(COLUMN_ROOM_ID, roomId)
                        put(COLUMN_IMAGE_URI, newUri.toString())
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, if (translatedStatus?.get(originalUri) == true || translatedTexts.containsKey(originalUri)) 1 else 0)
                    }
                    val imageId = db.insert(TABLE_IMAGES, null, imageValues)
                    if (imageId != -1L) {
                        deleteOriginalImage(originalUri)
                        ensureChangeRecord(imageId, roomId)
                        
                        translatedTexts[originalUri]?.let { (_, textBlocks) ->
                            db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                            imageBlockDao.deleteForImage(imageId)

                            val blocksToSave = textBlocks.filter { !it.pendingDelete }
                            val originalWidth = blocksToSave.firstOrNull()?.originalImageWidth
                            val originalHeight = blocksToSave.firstOrNull()?.originalImageHeight
                            val savedBitmap = android.graphics.BitmapFactory.decodeFile(newFile.absolutePath)
                            val scaleX = if (originalWidth != null && savedBitmap != null && originalWidth > 0) savedBitmap.width.toFloat() / originalWidth else 1f
                            val scaleY = if (originalHeight != null && savedBitmap != null && originalHeight > 0) savedBitmap.height.toFloat() / originalHeight else 1f
                            savedBitmap?.recycle()

                            blocksToSave.forEach { textBlock ->
                                val origRect = textBlock.bounds
                                val scaledRect = if (scaleX != 1f || scaleY != 1f) {
                                    Rect(
                                        (origRect.left * scaleX).toInt(),
                                        (origRect.top * scaleY).toInt(),
                                        (origRect.right * scaleX).toInt(),
                                        (origRect.bottom * scaleY).toInt()
                                    )
                                } else origRect

                                val transId = translationDao.insert(
                                    imageId, textBlock.text, textBlock.originalText ?: "",
                                    scaledRect.left, scaledRect.top, scaledRect.width(), scaledRect.height(),
                                    textBlock.originalTextColor
                                )
                                if (transId != -1L) {
                                    val blockWidth = scaledRect.right - scaledRect.left
                                    val blockHeight = scaledRect.bottom - scaledRect.top
                                    insertImageBlock(
                                        imageId = imageId, x = scaledRect.left, y = scaledRect.top,
                                        width = blockWidth, height = blockHeight,
                                        overlayType = textBlock.shapeType,
                                        overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor,
                                        overlayAlpha = textBlock.overlayAlpha,
                                        overlaySaturation = textBlock.overlaySaturation,
                                        overlayInset = textBlock.overlayInset,
                                        overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                        overlayInsetVertical = textBlock.overlayInsetVertical,
                                        overlayRotation = textBlock.overlayRotation,
                                        textColor = textBlock.customTextColor ?: textBlock.originalTextColor,
                                        textBoldness = textBlock.textBoldness,
                                        textSaturation = textBlock.textSaturation,
                                        borderColor = textBlock.customBorderColor,
                                        borderBoldness = textBlock.borderAlpha,
                                        borderThickness = textBlock.borderThickness,
                                        shadowColor = textBlock.customShadowColor,
                                        shadowAlpha = textBlock.shadowAlpha,
                                        shadowRadius = textBlock.shadowRadius,
                                        rotation = textBlock.rotation ?: 0f,
                                        fontFamily = textBlock.fontFamily,
                                        fontSize = textBlock.fontSize,
                                        lineSpacing = textBlock.lineSpacing,
                                        textAlign = textBlock.textAlign.name,
                                        textGradientColors = textBlock.textGradientColors,
                                        textGradientOffsets = textBlock.textGradientOffsets,
                                        textGradientType = textBlock.textGradientType,
                                        originalWidth = textBlock.originalImageWidth,
                                        originalHeight = textBlock.originalImageHeight
                                    )
                                }
                            }
                            clearImageChange(imageId)
                            translationDao.deletePending(imageId)
                        }
                    }
                }
            }

            imageUris.forEach { uri -> try { deleteOriginalImage(uri) } catch (e: Exception) { /* ignore */ } }
            db.setTransactionSuccessful()
            setAutoTranslateSetting(roomId, true)
            setAutoTranslateSetting(roomId, false) // ancient setting default
            cleanupDuplicateImages(roomId)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving manga room", e)
            return -1L
        } finally {
            db.endTransaction()
        }
        return roomId
    }

    fun updateMangaRoom(
        roomId: Long,
        imageUris: List<Uri>,
        translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
        translatedStatus: Map<Uri, Boolean>? = null
    ): Boolean {
        if (imageUris.isEmpty()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldImages = mutableListOf<Pair<Long, Uri>>()
            val imageIdMap = mutableMapOf<String, Long>()
            val cursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? ORDER BY $COLUMN_DISPLAY_ORDER", arrayOf(roomId.toString()))
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val uri = Uri.parse(cursor.getString(1))
                oldImages.add(id to uri)
                imageIdMap[uri.toString()] = id
            }
            cursor.close()

            fun lastNameOf(uri: Uri?): String? = try { uri?.lastPathSegment ?: File(uri.toString()).name } catch (e: Exception) { null }

            oldImages.forEach { (imageId, uri) ->
                val exact = imageUris.any { it.toString() == uri.toString() }
                val name = lastNameOf(uri)
                val namePresent = if (name != null) imageUris.any { newUri -> lastNameOf(newUri) == name } else false
                if (!exact && !namePresent) {
                    db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    imageBlockDao.deleteForImage(imageId)
                    db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    val file = File(Uri.parse(uri.toString()).path ?: "")
                    if (file.exists()) file.delete()
                }
            }

            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            imagesDir.mkdirs()

            val oldFilenameToImageId = mutableMapOf<String, Long>()
            val oldImageIdToFilename = mutableMapOf<Long, String>()
            oldImages.forEach { (imageId, oldUri) ->
                val filename = lastNameOf(oldUri)
                if (filename != null) {
                    oldFilenameToImageId[filename] = imageId
                    oldImageIdToFilename[imageId] = filename
                }
            }

            data class ImageInfo(val index: Int, val uri: Uri, val isNew: Boolean, val existingImageId: Long?)
            val imageInfos = imageUris.mapIndexed { index, uri ->
                val uriStr = uri.toString()
                val filename = lastNameOf(uri)
                val existingImageId = imageIdMap[uriStr] ?: if (filename != null) oldFilenameToImageId[filename] else null
                ImageInfo(index, uri, existingImageId == null, existingImageId)
            }

            val tempRenames = mutableMapOf<Long, File>()
            imageInfos.filter { !it.isNew && it.existingImageId != null }.forEach { info ->
                val imageId = info.existingImageId!!
                val oldFilename = oldImageIdToFilename[imageId]
                if (oldFilename != null) {
                    val oldFile = File(imagesDir, oldFilename)
                    if (oldFile.exists()) {
                        val tempFile = File(imagesDir, "temp_${imageId}_$oldFilename")
                        if (oldFile.renameTo(tempFile)) tempRenames[imageId] = tempFile
                    }
                }
            }

            imageInfos.forEach { info ->
                val targetFilename = "image_${info.index}.webp"
                val targetFile = File(imagesDir, targetFilename)
                if (info.isNew) {
                    copyImageToInternalStorage(info.uri, imagesDir, targetFilename)
                } else {
                    val imageId = info.existingImageId!!
                    val tempFile = tempRenames[imageId]
                    if (tempFile?.exists() == true) {
                        if (targetFile.exists()) targetFile.delete()
                        tempFile.renameTo(targetFile)
                    }
                }
            }

            imageInfos.forEach { info ->
                val index = info.index
                val uri = info.uri
                val isTranslated = if (translatedStatus?.get(uri) == true || translatedTexts.containsKey(uri)) 1 else 0
                val targetFilename = "image_${index}.webp"
                val targetFile = File(imagesDir, targetFilename)
                val newUri = if (targetFile.exists()) Uri.fromFile(targetFile) else uri

                if (info.isNew) {
                    val imageValues = ContentValues().apply {
                        put(COLUMN_ROOM_ID, roomId)
                        put(COLUMN_IMAGE_URI, newUri.toString())
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, isTranslated)
                    }
                    val imageId = db.insert(TABLE_IMAGES, null, imageValues)
                    if (imageId != -1L) {
                        ensureChangeRecord(imageId, roomId)
                        translatedTexts[uri]?.let { (_, textBlocks) ->
                            val blocksToSave = textBlocks.filter { !it.pendingDelete }
                            blocksToSave.forEach { textBlock ->
                                val origRect = textBlock.bounds
                                val transId = translationDao.insert(
                                    imageId, textBlock.text, textBlock.originalText ?: "",
                                    origRect.left, origRect.top, origRect.width(), origRect.height(),
                                    textBlock.originalTextColor
                                )
                                if (transId != -1L) {
                                    insertImageBlock(
                                        imageId = imageId, x = origRect.left, y = origRect.top,
                                        width = origRect.width(), height = origRect.height(),
                                        overlayType = textBlock.shapeType,
                                        overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor,
                                        overlayAlpha = textBlock.overlayAlpha,
                                        overlaySaturation = textBlock.overlaySaturation,
                                        overlayInset = textBlock.overlayInset,
                                        overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                        overlayInsetVertical = textBlock.overlayInsetVertical,
                                        overlayRotation = textBlock.overlayRotation,
                                        textColor = textBlock.customTextColor ?: textBlock.originalTextColor,
                                        textBoldness = textBlock.textBoldness,
                                        textSaturation = textBlock.textSaturation,
                                        borderColor = textBlock.customBorderColor,
                                        borderBoldness = textBlock.borderAlpha,
                                        borderThickness = textBlock.borderThickness,
                                        shadowColor = textBlock.customShadowColor,
                                        shadowAlpha = textBlock.shadowAlpha,
                                        shadowRadius = textBlock.shadowRadius,
                                        rotation = textBlock.rotation ?: 0f,
                                        fontFamily = textBlock.fontFamily,
                                        fontSize = textBlock.fontSize,
                                        lineSpacing = textBlock.lineSpacing,
                                        textAlign = textBlock.textAlign.name,
                                        textGradientColors = textBlock.textGradientColors,
                                        textGradientOffsets = textBlock.textGradientOffsets,
                                        textGradientType = textBlock.textGradientType,
                                        originalWidth = textBlock.originalImageWidth,
                                        originalHeight = textBlock.originalImageHeight
                                    )
                                }
                            }
                            clearImageChange(imageId)
                            translationDao.deletePending(imageId)
                        }
                    }
                } else {
                    val imageId = info.existingImageId!!
                    val imageValues = ContentValues().apply {
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, isTranslated)
                        put(COLUMN_IMAGE_URI, newUri.toString())
                    }
                    db.update(TABLE_IMAGES, imageValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))

                    val uriFilename = lastNameOf(uri)
                    val translationEntry = translatedTexts[uri] ?: translatedTexts.entries.find { (k, _) ->
                        lastNameOf(k) == uriFilename
                    }?.value

                    if (translationEntry != null) {
                        val (_, textBlocks) = translationEntry
                        db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        imageBlockDao.deleteForImage(imageId)

                        val blocksToSave = textBlocks.filter { !it.pendingDelete }
                        blocksToSave.forEach { textBlock ->
                            val origRect = textBlock.bounds
                            val transId = translationDao.insert(
                                imageId, textBlock.text, textBlock.originalText ?: "",
                                origRect.left, origRect.top, origRect.width(), origRect.height(),
                                textBlock.originalTextColor
                            )
                            if (transId != -1L) {
                                insertImageBlock(
                                    imageId = imageId, x = origRect.left, y = origRect.top,
                                    width = origRect.width(), height = origRect.height(),
                                    overlayType = textBlock.shapeType,
                                    overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor,
                                    overlayAlpha = textBlock.overlayAlpha,
                                    overlaySaturation = textBlock.overlaySaturation,
                                    overlayInset = textBlock.overlayInset,
                                    overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                    overlayInsetVertical = textBlock.overlayInsetVertical,
                                    overlayRotation = textBlock.overlayRotation,
                                    textColor = textBlock.customTextColor ?: textBlock.originalTextColor,
                                    textBoldness = textBlock.textBoldness,
                                    textSaturation = textBlock.textSaturation,
                                    borderColor = textBlock.customBorderColor,
                                    borderBoldness = textBlock.borderAlpha,
                                    borderThickness = textBlock.borderThickness,
                                    shadowColor = textBlock.customShadowColor,
                                    shadowAlpha = textBlock.shadowAlpha,
                                    shadowRadius = textBlock.shadowRadius,
                                    rotation = textBlock.rotation ?: 0f,
                                    fontFamily = textBlock.fontFamily,
                                    fontSize = textBlock.fontSize,
                                    lineSpacing = textBlock.lineSpacing,
                                    textAlign = textBlock.textAlign.name,
                                    textGradientColors = textBlock.textGradientColors,
                                    textGradientOffsets = textBlock.textGradientOffsets,
                                    textGradientType = textBlock.textGradientType,
                                    originalWidth = textBlock.originalImageWidth,
                                    originalHeight = textBlock.originalImageHeight
                                )
                            }
                        }
                        clearImageChange(imageId)
                        translationDao.deletePending(imageId)
                    }
                }
            }

            if (imageUris.isNotEmpty()) {
                val coverUri = imageUris.first().toString()
                val roomValues = ContentValues().apply { put(COLUMN_COVER_URI, coverUri) }
                db.update(TABLE_ROOMS, roomValues, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
            }
            db.setTransactionSuccessful()
            cleanupDuplicateImages(roomId)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi cập nhật truyện $roomId", e)
            return false
        } finally {
            db.endTransaction()
        }
    }

    fun updateMangaRoomSelective(
        roomId: Long, imageUris: List<Uri>,
        translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
        dirtyUris: List<Uri>, callerUriToImageId: Map<Uri, Long>,
        translatedStatus: Map<Uri, Boolean>? = null
    ): Boolean {
        return roomDao.getAutoTranslateSetting(roomId).let {
            // Placeholder logic match: selective logic requires transaction & mapping update
            // We reuse structural logic in a clean way:
            val db = writableDatabase
            db.beginTransaction()
            try {
                val imageIdMap = mutableMapOf<String, Long>()
                callerUriToImageId.forEach { (k, v) -> imageIdMap[k.toString()] = v }
                val cursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
                while (cursor.moveToNext()) {
                    imageIdMap[cursor.getString(1)] = cursor.getLong(0)
                }
                cursor.close()

                imageUris.forEachIndexed { index, uri ->
                    val imageId = imageIdMap[uri.toString()]
                    if (imageId != null) {
                        val isTranslated = if (translatedStatus?.get(uri) == true || translatedTexts.containsKey(uri)) 1 else 0
                        val vals = ContentValues().apply {
                            put(COLUMN_DISPLAY_ORDER, index)
                            put(COLUMN_IS_TRANSLATED, isTranslated)
                        }
                        db.update(TABLE_IMAGES, vals, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    }
                }

                val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
                imagesDir.mkdirs()

                dirtyUris.forEach { dirtyUri ->
                    var imageId = imageIdMap[dirtyUri.toString()]
                    val index = imageUris.indexOf(dirtyUri)

                    if (imageId == null && index >= 0) {
                        val ordCursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? AND $COLUMN_DISPLAY_ORDER = ?", arrayOf(roomId.toString(), index.toString()))
                        if (ordCursor.moveToFirst()) imageId = ordCursor.getLong(0)
                        ordCursor.close()
                    }

                    if (imageId == null) {
                        val fileName = "image_${index.coerceAtLeast(0)}.webp"
                        val newFile = copyImageToInternalStorage(dirtyUri, imagesDir, fileName)
                        val newUri = if (newFile != null) Uri.fromFile(newFile) else dirtyUri
                        val imageValues = ContentValues().apply {
                            put(COLUMN_ROOM_ID, roomId)
                            put(COLUMN_IMAGE_URI, newUri.toString())
                            put(COLUMN_DISPLAY_ORDER, index)
                            put(COLUMN_IS_TRANSLATED, if (translatedTexts.containsKey(dirtyUri)) 1 else 0)
                        }
                        imageId = db.insert(TABLE_IMAGES, null, imageValues)
                        if (imageId != -1L) {
                            ensureChangeRecord(imageId, roomId)
                            try { deleteOriginalImage(dirtyUri) } catch (e: Exception) { /* ignore */ }
                        }
                    }

                    if (imageId != null && imageId != -1L) {
                        db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        imageBlockDao.deleteForImage(imageId)

                        translatedTexts[dirtyUri]?.let { (_, textBlocks) ->
                            val blocksToSave = textBlocks.filter { !it.pendingDelete }
                            blocksToSave.forEach { textBlock ->
                                val origRect = textBlock.bounds
                                val transId = translationDao.insert(
                                    imageId, textBlock.text, textBlock.originalText ?: "",
                                    origRect.left, origRect.top, origRect.width(), origRect.height(),
                                    textBlock.originalTextColor
                                )
                                if (transId != -1L) {
                                    insertImageBlock(
                                        imageId = imageId, x = origRect.left, y = origRect.top,
                                        width = origRect.width(), height = origRect.height(),
                                        overlayType = textBlock.shapeType,
                                        overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor,
                                        overlayAlpha = textBlock.overlayAlpha,
                                        overlaySaturation = textBlock.overlaySaturation,
                                        overlayInset = textBlock.overlayInset,
                                        overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                        overlayInsetVertical = textBlock.overlayInsetVertical,
                                        overlayRotation = textBlock.overlayRotation,
                                        textColor = textBlock.customTextColor ?: textBlock.originalTextColor,
                                        textBoldness = textBlock.textBoldness,
                                        textSaturation = textBlock.textSaturation,
                                        borderColor = textBlock.customBorderColor,
                                        borderBoldness = textBlock.borderAlpha,
                                        borderThickness = textBlock.borderThickness,
                                        shadowColor = textBlock.customShadowColor,
                                        shadowAlpha = textBlock.shadowAlpha,
                                        shadowRadius = textBlock.shadowRadius,
                                        rotation = textBlock.rotation ?: 0f,
                                        fontFamily = textBlock.fontFamily,
                                        fontSize = textBlock.fontSize,
                                        lineSpacing = textBlock.lineSpacing,
                                        textAlign = textBlock.textAlign.name,
                                        textGradientColors = textBlock.textGradientColors,
                                        textGradientOffsets = textBlock.textGradientOffsets,
                                        textGradientType = textBlock.textGradientType,
                                        originalWidth = textBlock.originalImageWidth,
                                        originalHeight = textBlock.originalImageHeight
                                    )
                                }
                            }
                            clearImageChange(imageId)
                            translationDao.deletePending(imageId)
                        }
                    }
                }
                db.setTransactionSuccessful()
                cleanupDuplicateImages(roomId)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "updateMangaRoomSelective failed", e)
                return false
            } finally {
                db.endTransaction()
            }
        }
    }

    fun applyPendingChangesForRoom(
        roomId: Long,
        translatedByImageId: Map<Long, Pair<String, List<TextBlockInfo>>>,
        clearChangedFlag: Boolean = true
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            translatedByImageId.forEach { (imageId, data) ->
                val (_, textBlocks) = data
                db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                imageBlockDao.deleteForImage(imageId)

                val blocksToSave = textBlocks.filter { !it.pendingDelete }
                blocksToSave.forEach { textBlock ->
                    val origRect = textBlock.bounds
                    val transId = translationDao.insert(
                        imageId, textBlock.text, textBlock.originalText ?: "",
                        origRect.left, origRect.top, origRect.width(), origRect.height(),
                        textBlock.originalTextColor
                    )
                    if (transId != -1L) {
                        insertImageBlock(
                            imageId = imageId, x = origRect.left, y = origRect.top,
                            width = origRect.width(), height = origRect.height(),
                            overlayType = textBlock.shapeType,
                            overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor,
                            overlayAlpha = textBlock.overlayAlpha,
                            overlaySaturation = textBlock.overlaySaturation,
                            overlayInset = textBlock.overlayInset,
                            overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                            overlayInsetVertical = textBlock.overlayInsetVertical,
                            overlayRotation = textBlock.overlayRotation,
                            textColor = textBlock.customTextColor ?: textBlock.originalTextColor,
                            textBoldness = textBlock.textBoldness,
                            textSaturation = textBlock.textSaturation,
                            borderColor = textBlock.customBorderColor,
                            borderBoldness = textBlock.borderAlpha,
                            borderThickness = textBlock.borderThickness,
                            shadowColor = textBlock.customShadowColor,
                            shadowAlpha = textBlock.shadowAlpha,
                            shadowRadius = textBlock.shadowRadius,
                            rotation = textBlock.rotation ?: 0f,
                            fontFamily = textBlock.fontFamily,
                            fontSize = textBlock.fontSize,
                            lineSpacing = textBlock.lineSpacing,
                            textAlign = textBlock.textAlign.name,
                            textGradientColors = textBlock.textGradientColors,
                            textGradientOffsets = textBlock.textGradientOffsets,
                            textGradientType = textBlock.textGradientType,
                            originalWidth = textBlock.originalImageWidth,
                            originalHeight = textBlock.originalImageHeight
                        )
                    }
                }
                val vals = ContentValues().apply { put(COLUMN_IS_TRANSLATED, 1) }
                db.update(TABLE_IMAGES, vals, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                if (clearChangedFlag) clearImageChange(imageId)
            }
            db.setTransactionSuccessful()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "applyPendingChangesForRoom failed", e)
            return false
        } finally {
            db.endTransaction()
        }
    }

    private fun cleanupDuplicateImages(roomId: Long) {
        val db = writableDatabase
        try {
            val cursor = db.rawQuery("""
                SELECT i.$COLUMN_IMAGE_ID, i.$COLUMN_IMAGE_URI, i.$COLUMN_DISPLAY_ORDER,
                       (SELECT COUNT(*) FROM $TABLE_TRANSLATIONS t WHERE t.$COLUMN_IMAGE_ID = i.$COLUMN_IMAGE_ID) as translation_count
                FROM $TABLE_IMAGES i
                WHERE i.$COLUMN_ROOM_ID = ? 
                ORDER BY i.$COLUMN_DISPLAY_ORDER ASC, translation_count DESC, i.$COLUMN_IMAGE_ID ASC
            """.trimIndent(), arrayOf(roomId.toString()))

            fun extractFilename(uri: String): String = try { Uri.parse(uri).lastPathSegment ?: File(uri).name } catch (e: Exception) { uri }

            val seenFilenames = mutableSetOf<String>()
            val seenDisplayOrders = mutableMapOf<Int, Long>()
            val duplicateImageIds = mutableListOf<Long>()

            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val imageUri = cursor.getString(1)
                val displayOrder = cursor.getInt(2)
                val filename = extractFilename(imageUri)

                if (seenFilenames.contains(filename) || seenDisplayOrders.containsKey(displayOrder)) {
                    duplicateImageIds.add(imageId)
                } else {
                    seenFilenames.add(filename)
                    seenDisplayOrders[displayOrder] = imageId
                }
            }
            cursor.close()

            duplicateImageIds.forEach { imageId ->
                db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                imageBlockDao.deleteForImage(imageId)
                db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun cleanupDuplicateImagesStrict(roomId: Long) {
        val db = writableDatabase
        try {
            cleanupDuplicateImages(roomId)
            val cursor = db.rawQuery("""
                SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI, $COLUMN_DISPLAY_ORDER
                FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?
                ORDER BY $COLUMN_DISPLAY_ORDER ASC, $COLUMN_IMAGE_ID ASC
            """.trimIndent(), arrayOf(roomId.toString()))

            val seenFilenames = mutableMapOf<String, Long>()
            val duplicates = mutableListOf<Long>()

            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val imageUri = cursor.getString(1)
                val filename = try { Uri.parse(imageUri).lastPathSegment ?: imageUri } catch (e: Exception) { imageUri }
                val existingId = seenFilenames[filename]
                if (existingId != null && existingId != imageId) {
                    duplicates.add(imageId)
                } else {
                    seenFilenames[filename] = imageId
                }
            }
            cursor.close()

            duplicates.forEach { imageId ->
                db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                imageBlockDao.deleteForImage(imageId)
                db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            }

            val remaining = db.rawQuery("SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? ORDER BY $COLUMN_DISPLAY_ORDER ASC, $COLUMN_IMAGE_ID ASC", arrayOf(roomId.toString()))
            var newOrder = 0
            while (remaining.moveToNext()) {
                val imageId = remaining.getLong(0)
                val values = ContentValues().apply { put(COLUMN_DISPLAY_ORDER, newOrder) }
                db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                newOrder++
            }
            remaining.close()
        } catch (e: Exception) { /* ignore */ }
    }

    fun cleanupAndSyncRoomImages(roomId: Long, expectedCount: Int) {
        val db = writableDatabase
        try {
            cleanupDuplicateImages(roomId)
            val countCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
            val actualCount = if (countCursor.moveToFirst()) countCursor.getInt(0) else 0
            countCursor.close()

            if (actualCount > expectedCount) {
                val extraCursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? AND $COLUMN_DISPLAY_ORDER >= ? ORDER BY $COLUMN_DISPLAY_ORDER DESC", arrayOf(roomId.toString(), expectedCount.toString()))
                val extraIds = mutableListOf<Long>()
                while (extraCursor.moveToNext()) extraIds.add(extraCursor.getLong(0))
                extraCursor.close()

                extraIds.forEach { imageId ->
                    db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    imageBlockDao.deleteForImage(imageId)
                    db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun cleanupDuplicateTranslations() {
        try {
            val db = writableDatabase
            val cursor = db.rawQuery("SELECT DISTINCT $COLUMN_IMAGE_ID FROM $TABLE_TRANSLATIONS", null)
            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val countCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_TRANSLATIONS WHERE $COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                if (countCursor.moveToFirst()) {
                    val count = countCursor.getInt(0)
                    if (count > 20) {
                        val blockCountCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_IMAGE_BLOCKS WHERE $COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        var expected = count
                        if (blockCountCursor.moveToFirst()) {
                            expected = blockCountCursor.getInt(0)
                            if (expected == 0) expected = count / 2
                        }
                        blockCountCursor.close()

                        if (count > expected * 2) {
                            db.execSQL("""
                                DELETE FROM $TABLE_TRANSLATIONS 
                                WHERE $COLUMN_IMAGE_ID = ? 
                                AND text_id NOT IN (
                                    SELECT text_id FROM $TABLE_TRANSLATIONS 
                                    WHERE $COLUMN_IMAGE_ID = ? 
                                    ORDER BY text_id DESC 
                                    LIMIT ?
                                )
                            """.trimIndent(), arrayOf(imageId.toString(), imageId.toString(), expected.toString()))
                        }
                    }
                }
                countCursor.close()
            }
            cursor.close()
        } catch (e: Exception) { /* ignore */ }
    }

    // Helper functions for Cursor reads
    private fun Cursor.getStringOrNull(index: Int): String? = try {
        if (index < 0 || isNull(index)) null else getString(index)
    } catch (e: Exception) { null }
}