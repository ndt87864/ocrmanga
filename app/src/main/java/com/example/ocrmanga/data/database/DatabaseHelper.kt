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
    internal val imageBlockDao = ImageBlockDao(this)
    internal val translationDao = TranslationDao(this)
    internal val roomDao = RoomDao(this)

    companion object {
        internal const val TAG = "DatabaseHelper"

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

    fun getMangaRoom(roomId: Long): Triple<List<Uri>, List<Int>, Map<Uri, Pair<String, List<TextBlockInfo>>>> =
        getMangaRoomImpl(roomId)

    fun getMangaRoomOptimized(roomId: Long): Triple<List<Uri>, List<Int>, Map<Uri, Pair<String, List<TextBlockInfo>>>> =
        getMangaRoomOptimizedImpl(roomId)

    fun getTranslationsForImages(imageUris: List<Uri>): Map<Uri, Pair<String, List<TextBlockInfo>>> =
        getTranslationsForImagesImpl(imageUris)

    fun replaceImageWithCopy(imageId: Long, newUri: Uri): Uri? =
        replaceImageWithCopyImpl(imageId, newUri)

    fun replaceImageFileOnly(imageId: Long, newUri: Uri): Uri? =
        replaceImageFileOnlyImpl(imageId, newUri)

    fun saveMangaRoom(
        imageUris: List<Uri>,
        translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
        roomId: Long? = null,
        title: String? = null,
        deletedUris: Set<Uri> = emptySet(),
        insertAtStart: Boolean = false,
        insertAtIndex: Int? = null,
        translatedStatus: Map<Uri, Boolean>? = null
    ): Long = saveMangaRoomImpl(imageUris, translatedTexts, title, translatedStatus)

    fun updateMangaRoom(
        roomId: Long,
        imageUris: List<Uri>,
        translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
        translatedStatus: Map<Uri, Boolean>? = null
    ) = updateMangaRoomImpl(roomId, imageUris, translatedTexts, translatedStatus)

    fun updateMangaRoomSelective(
        roomId: Long,
        imageUris: List<Uri>,
        translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
        dirtyUris: List<Uri> = emptyList(),
        callerUriToImageId: Map<Uri, Long> = emptyMap(),
        translatedStatus: Map<Uri, Boolean>? = null
    ) = updateMangaRoomSelectiveImpl(roomId, imageUris, translatedTexts, dirtyUris, callerUriToImageId, translatedStatus)

    fun applyPendingChangesForRoom(
        roomId: Long,
        translatedByImageId: Map<Long, Pair<String, List<TextBlockInfo>>>,
        clearChangedFlag: Boolean = true
    ) = applyPendingChangesForRoomImpl(roomId, translatedByImageId, clearChangedFlag)

    fun cleanupAndSyncRoomImages(roomId: Long, expectedCount: Int) =
        cleanupAndSyncRoomImagesImpl(roomId, expectedCount)

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
