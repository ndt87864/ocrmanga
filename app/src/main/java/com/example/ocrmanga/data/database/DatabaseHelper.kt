package com.example.ocrmanga.data.database

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.ocrmanga.utils.AppLogger as Log
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.BackgroundType
import java.io.File
import java.io.FileOutputStream

// Helper data class for batch block loading
private data class BlockData(
    val blockId: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val overlayColor: Int?,
    val overlayAlpha: Float,
    val overlaySaturation: Float,
    val overlayInset: Float,
    val overlayInsetH: Float,
    val overlayInsetV: Float,
    val overlayRotation: Float?,
    val overlayType: Int,
    val textColor: Int?,
    val textBoldness: Float,
    val textSaturation: Float,
    val fontSize: Float,
    val fontFamily: String?,
    val rotation: Float,
    val lineSpacing: Float,
    val borderColor: Int?,
    val borderThickness: Float,
    val shadowColor: Int?,
    val shadowAlpha: Float,
    val shadowRadius: Float,
    val textAlign: com.example.ocrmanga.data.models.TextAlignMode,
    val textGradientColors: List<Int>?,
    val textGradientOffsets: List<Float>?,
    val textGradientType: Int
)

private data class TranslationData(
    val translatedText: String,
    val originalText: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        // Trả về số lượng ảnh đã thay đổi trong room
        fun getNumChangedImages(roomId: Long): Int {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM change_images WHERE room_id = ? AND is_changed = 1", arrayOf(roomId.toString()))
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            return count
        }
    // Sửa API key theo key và type cũ
    fun updateApiKeyWithType(oldKey: String, oldType: String, newKey: String, newType: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_API_KEY_VALUE, newKey)
            put(COLUMN_API_KEY_TYPE, newType)
        }
        db.update(
            TABLE_API_KEYS,
            values,
            "$COLUMN_API_KEY_VALUE = ? AND $COLUMN_API_KEY_TYPE = ?",
            arrayOf(oldKey, oldType)
        )
    }

    // Xóa API key theo key và type
    fun deleteApiKeyWithType(key: String, type: String) {
        val db = writableDatabase
        db.delete(
            TABLE_API_KEYS,
            "$COLUMN_API_KEY_VALUE = ? AND $COLUMN_API_KEY_TYPE = ?",
            arrayOf(key, type)
        )
    }

    /**
     * Clean up duplicate translations for the same image.
     * Keep only the most recent translation per image (highest translation_id).
     */
    private fun cleanupDuplicateTranslations() {
        try {
            val db = writableDatabase
            // For each image_id, keep only the translations that are part of the latest set
            // (i.e., delete older duplicate translations)
            val cursor = db.rawQuery("""
                SELECT DISTINCT $COLUMN_IMAGE_ID FROM translations
            """, null)
            
            var totalDeleted = 0
            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                // Count how many translations exist for this image
                val countCursor = db.rawQuery(
                    "SELECT COUNT(*) FROM translations WHERE $COLUMN_IMAGE_ID = ?",
                    arrayOf(imageId.toString())
                )
                if (countCursor.moveToFirst()) {
                    val count = countCursor.getInt(0)
                    // If there are suspiciously many translations (e.g., > 20), likely duplicates
                    // Keep only the most recent ones by deleting older entries
                    if (count > 20) {
                        // Get expected number of blocks from image_blocks table
                        val blockCountCursor = db.rawQuery("""
                            SELECT COUNT(*) FROM $TABLE_IMAGE_BLOCKS 
                            WHERE $COLUMN_BLOCK_IMAGE_ID = ?
                        """, arrayOf(imageId.toString()))
                        var expectedBlocks = count
                        if (blockCountCursor.moveToFirst()) {
                            expectedBlocks = blockCountCursor.getInt(0)
                            if (expectedBlocks == 0) expectedBlocks = count / 2 // fallback
                        }
                        blockCountCursor.close()
                        
                        // If actual count is much more than expected blocks, we have duplicates
                        if (count > expectedBlocks * 2) {
                            db.execSQL("""
                                DELETE FROM translations 
                                WHERE $COLUMN_IMAGE_ID = ? 
                                AND text_id NOT IN (
                                    SELECT text_id FROM translations 
                                    WHERE $COLUMN_IMAGE_ID = ? 
                                    ORDER BY text_id DESC 
                                    LIMIT ?
                                )
                            """, arrayOf(imageId.toString(), imageId.toString(), expectedBlocks.toString()))
                            totalDeleted += (count - expectedBlocks)
                        }
                    }
                }
                countCursor.close()
            }
            cursor.close()
            
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up duplicate translations", e)
        }
    }

    private val appContext = context

    init {
        migrateRoomImageLinks()
        // Clean up any duplicate translations that might exist
        cleanupDuplicateTranslations()

        // Loại bỏ hoàn toàn API key NVIDIA khỏi DB
        try {
            val db = writableDatabase
            val deletedCount = db.delete(TABLE_API_KEYS, "$COLUMN_API_KEY_TYPE = ?", arrayOf("nvidia"))
            if (deletedCount > 0) {
                Log.i(TAG, "Đã dọn dẹp $deletedCount API key NVIDIA khỏi database")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi dọn dẹp API key NVIDIA", e)
        }

        // Bảng translations giờ chỉ có: text_id, image_id, translated_text, pending_delete
        // Tất cả thông tin khác (rotation, shape_type, colors, v.v.) đã chuyển sang image_blocks
        try {
            val db = writableDatabase
            
            // Kiểm tra nếu bảng translations còn các cột thừa (rotation, shape_type, overlay_rotation, v.v.)
            // thì recreate bảng với cấu trúc đơn giản
            val cursor = db.rawQuery("PRAGMA table_info(translations)", null)
            var hasRotation = false
            var hasShapeType = false
            var hasOverlayRotation = false
            while (cursor.moveToNext()) {
                val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (columnName == "rotation") hasRotation = true
                if (columnName == "shape_type") hasShapeType = true
                if (columnName == "overlay_rotation") hasOverlayRotation = true
            }
            cursor.close()
            
            // Nếu còn cột thừa, recreate bảng translations
            if (hasRotation || hasShapeType || hasOverlayRotation) {
                Log.i(TAG, "Phát hiện cột thừa trong translations, recreate bảng...")
                try {
                    // 1. Tạo bảng mới với cấu trúc đơn giản
                    db.execSQL("DROP TABLE IF EXISTS translations_new")
                    db.execSQL("""
                        CREATE TABLE translations_new (
                            text_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            $COLUMN_IMAGE_ID INTEGER,
                            translated_text TEXT,
                            original_text TEXT,
                            pending_delete INTEGER DEFAULT 0,
                            apply_merge INTEGER DEFAULT 1,
                            x INTEGER DEFAULT 0,
                            y INTEGER DEFAULT 0,
                            width INTEGER DEFAULT 0,
                            height INTEGER DEFAULT 0,
                            FOREIGN KEY ($COLUMN_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
                        )
                    """)
                    
                    // 2. Copy dữ liệu từ bảng cũ sang bảng mới
                    // Check if original_text exists in old table
                    val pragmaCursor = db.rawQuery("PRAGMA table_info(translations)", null)
                    var hasOrigText = false
                    while (pragmaCursor.moveToNext()) {
                        if (pragmaCursor.getString(pragmaCursor.getColumnIndexOrThrow("name")) == "original_text") hasOrigText = true
                    }
                    pragmaCursor.close()
                    
                    if (hasOrigText) {
                        db.execSQL("""
                            INSERT INTO translations_new (text_id, $COLUMN_IMAGE_ID, translated_text, original_text, pending_delete, apply_merge)
                            SELECT text_id, $COLUMN_IMAGE_ID, translated_text, original_text, COALESCE(pending_delete, 0), COALESCE(apply_merge, 1)
                            FROM translations
                        """)
                    } else {
                        db.execSQL("""
                            INSERT INTO translations_new (text_id, $COLUMN_IMAGE_ID, translated_text, pending_delete, apply_merge)
                            SELECT text_id, $COLUMN_IMAGE_ID, translated_text, COALESCE(pending_delete, 0), COALESCE(apply_merge, 1)
                            FROM translations
                        """)
                    }
                    
                    // 3. Xóa bảng cũ
                    db.execSQL("DROP TABLE translations")
                    
                    // 4. Đổi tên bảng mới
                    db.execSQL("ALTER TABLE translations_new RENAME TO translations")
                    
                    // 5. Tạo lại index
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_image_id ON translations($COLUMN_IMAGE_ID)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_pending_delete ON translations($COLUMN_IMAGE_ID, pending_delete)")
                    
                    Log.i(TAG, "Đã recreate bảng translations thành công")
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi khi recreate bảng translations", e)
                }
            }
            
            // Kiểm tra và thêm cột pending_delete nếu chưa có (cho backward compatibility)
            var hasPendingDelete = false
            var hasApplyMerge = false
            val cursor3 = db.rawQuery("PRAGMA table_info(translations)", null)
            while (cursor3.moveToNext()) {
                val colName = cursor3.getString(cursor3.getColumnIndexOrThrow("name"))
                if (colName == "pending_delete") hasPendingDelete = true
                if (colName == "apply_merge") hasApplyMerge = true
            }
            cursor3.close()
            if (!hasPendingDelete) {
                db.execSQL("ALTER TABLE translations ADD COLUMN pending_delete INTEGER DEFAULT 0")
            }
            if (!hasApplyMerge) {
                db.execSQL("ALTER TABLE translations ADD COLUMN apply_merge INTEGER DEFAULT 1")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể tự động xử lý bảng translations", e)
        }
        // Ensure image_blocks has newer shadow / font columns when upgrading from older DBs
        try {
            val db = writableDatabase
            val c = db.rawQuery("PRAGMA table_info($TABLE_IMAGE_BLOCKS)", null)
            var hasShadowColor = false
            var hasShadowAlpha = false
            var hasShadowRadius = false
            var hasFontFamily = false
            var hasFontSize = false
            var hasOverlayInset = false
            var hasOverlayInsetHorizontal = false
            var hasOverlayInsetVertical = false
            var hasOverlayRotation = false
            while (c.moveToNext()) {
                val columnName = c.getString(c.getColumnIndexOrThrow("name"))
                when (columnName) {
                    COLUMN_BLOCK_SHADOW_COLOR -> hasShadowColor = true
                    COLUMN_BLOCK_SHADOW_ALPHA -> hasShadowAlpha = true
                    COLUMN_BLOCK_SHADOW_RADIUS -> hasShadowRadius = true
                    COLUMN_BLOCK_FONT_FAMILY -> hasFontFamily = true
                    COLUMN_BLOCK_FONT_SIZE -> hasFontSize = true
                    COLUMN_BLOCK_OVERLAY_INSET -> hasOverlayInset = true
                    COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL -> hasOverlayInsetHorizontal = true
                    COLUMN_BLOCK_OVERLAY_INSET_VERTICAL -> hasOverlayInsetVertical = true
                    COLUMN_BLOCK_OVERLAY_ROTATION -> hasOverlayRotation = true
                }
            }
            c.close()

            if (!hasShadowColor) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_SHADOW_COLOR INTEGER") } catch (e: Exception) { /* ignore */ }
            }
            if (!hasShadowAlpha) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_SHADOW_ALPHA REAL DEFAULT 1.0") } catch (e: Exception) { /* ignore */ }
            }
            if (!hasShadowRadius) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_SHADOW_RADIUS REAL DEFAULT 0.0") } catch (e: Exception) { /* ignore */ }
            }
            if (!hasFontFamily) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_FONT_FAMILY TEXT DEFAULT ''") } catch (e: Exception) { /* ignore */ }
            }
            if (!hasFontSize) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_FONT_SIZE REAL DEFAULT 12.0") } catch (e: Exception) { /* ignore */ }
            }
            if (!hasOverlayInset) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_OVERLAY_INSET REAL DEFAULT 0.0") } catch (e: Exception) { /* ignore */ }
            }
            // Thêm cột overlay_inset_horizontal và overlay_inset_vertical
            if (!hasOverlayInsetHorizontal) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL REAL DEFAULT 0.0") } catch (e: Exception) { /* ignore */ }
            }
            if (!hasOverlayInsetVertical) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_OVERLAY_INSET_VERTICAL REAL DEFAULT 0.0") } catch (e: Exception) { /* ignore */ }
            }
            // Thêm cột overlay_rotation cho xoay overlay riêng biệt
            if (!hasOverlayRotation) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_OVERLAY_ROTATION REAL") } catch (e: Exception) { /* ignore */ }
            }
            // Thêm các cột kích thước ảnh gốc cho bảng image_blocks (original_width, original_height)
            var hasOrigWidth = false
            var hasOrigHeight = false
            val c2 = db.rawQuery("PRAGMA table_info($TABLE_IMAGE_BLOCKS)", null)
            while (c2.moveToNext()) {
                val col = c2.getString(c2.getColumnIndexOrThrow("name"))
                if (col == COLUMN_BLOCK_ORIGINAL_WIDTH) hasOrigWidth = true
                if (col == COLUMN_BLOCK_ORIGINAL_HEIGHT) hasOrigHeight = true
            }
            c2.close()
            if (!hasOrigWidth) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_ORIGINAL_WIDTH INTEGER") } catch (e: Exception) { /* ignore */ }
            }
            if (!hasOrigHeight) {
                try { db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_ORIGINAL_HEIGHT INTEGER") } catch (e: Exception) { /* ignore */ }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể tự động thêm cột vào bảng image_blocks", e)
        }
    }

    companion object {
        private const val DATABASE_NAME = "MangaDownloader.db"
    private const val DATABASE_VERSION = 30
        private const val TAG = "DatabaseHelper"
        
            /**
             * Hàm tạo Intent chọn ảnh, hỗ trợ cả webp
             */
            fun createImagePickerIntent(): android.content.Intent {
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
                intent.type = "image/*"
                // Thêm MIME type cho webp
                intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
                return intent
            }

        // Manga Rooms table
        const val TABLE_ROOMS = "manga_rooms"
        const val COLUMN_ROOM_ID = "room_id"
        const val COLUMN_TITLE = "title" // Thêm trường title
        private const val COLUMN_COVER_URI = "cover_uri"

        // Images table
        const val TABLE_IMAGES = "images"
        const val COLUMN_IMAGE_ID = "image_id"
        const val COLUMN_IMAGE_URI = "image_uri"
        const val COLUMN_DISPLAY_ORDER = "display_order"
        const val COLUMN_IS_TRANSLATED = "is_translated"

        // Translations table
        const val TABLE_TRANSLATIONS = "translations"
        const val COLUMN_ORIGINAL_TEXT = "original_text" // OCR text before translation
        const val COLUMN_TRANSLATED_TEXT = "translated_text"

        // API Keys table
        private const val TABLE_API_KEYS = "api_keys"
        private const val COLUMN_API_KEY_ID = "api_key_id"
        private const val COLUMN_API_KEY_VALUE = "api_key_value"
        private const val COLUMN_CREATED_DATE = "created_date"
        private const val COLUMN_UPDATED_DATE = "updated_date"
        private const val COLUMN_IS_ACTIVE = "is_active"
        private const val COLUMN_API_KEY_TYPE = "type" // Thêm trường type

    // Room settings table
    const val TABLE_ROOM_SETTINGS = "room_settings"
    const val COLUMN_SETTING_ROOM_ID = "room_id"
    const val COLUMN_AUTO_TRANSLATE_NEW_IMAGES = "auto_translate_new_images" // 0 = disabled, 1 = enabled
    const val COLUMN_ANCIENT_TRANSLATION_ENABLED = "ancient_translation_enabled" // 0 = disabled, 1 = enabled

    // Image blocks table (per-image text/overlay blocks)
    const val TABLE_IMAGE_BLOCKS = "image_blocks"
    const val COLUMN_BLOCK_ID = "block_id"
    const val COLUMN_BLOCK_IMAGE_ID = "image_id"
    const val COLUMN_BLOCK_X = "x"
    const val COLUMN_BLOCK_Y = "y"
    const val COLUMN_BLOCK_WIDTH = "width"
    const val COLUMN_BLOCK_HEIGHT = "height"
    const val COLUMN_BLOCK_OVERLAY_TYPE = "overlay_type" // 0=rect,1=oval
    // Overlay color properties
    const val COLUMN_BLOCK_OVERLAY_COLOR = "overlay_color"
    const val COLUMN_BLOCK_OVERLAY_BRIGHTNESS = "overlay_brightness"
    const val COLUMN_BLOCK_OVERLAY_ALPHA = "overlay_alpha"
    const val COLUMN_BLOCK_OVERLAY_SATURATION = "overlay_saturation"
    const val COLUMN_BLOCK_OVERLAY_INSET = "overlay_inset"
    const val COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL = "overlay_inset_horizontal"
    const val COLUMN_BLOCK_OVERLAY_INSET_VERTICAL = "overlay_inset_vertical"
    const val COLUMN_BLOCK_OVERLAY_ROTATION = "overlay_rotation"
    // Text color properties
    const val COLUMN_BLOCK_TEXT_COLOR = "text_color"
    const val COLUMN_BLOCK_TEXT_BRIGHTNESS = "text_brightness"
    const val COLUMN_BLOCK_TEXT_BOLDNESS = "text_boldness"
    const val COLUMN_BLOCK_TEXT_SATURATION = "text_saturation"
    // Border properties
    const val COLUMN_BLOCK_BORDER_COLOR = "border_color"
    const val COLUMN_BLOCK_BORDER_BRIGHTNESS = "border_brightness"
    const val COLUMN_BLOCK_BORDER_BOLDNESS = "border_boldness"
    const val COLUMN_BLOCK_BORDER_THICKNESS = "border_thickness"
    // Shadow properties
    const val COLUMN_BLOCK_SHADOW_COLOR = "shadow_color"
    const val COLUMN_BLOCK_SHADOW_ALPHA = "shadow_alpha"
    const val COLUMN_BLOCK_SHADOW_RADIUS = "shadow_radius"
    const val COLUMN_BLOCK_ROTATION = "rotation"
    const val COLUMN_BLOCK_FONT_FAMILY = "font_family"
    const val COLUMN_BLOCK_FONT_SIZE = "font_size"
    const val COLUMN_BLOCK_LINE_SPACING = "line_spacing"
    const val COLUMN_BLOCK_TEXT_ALIGN = "text_align"
        // Gradient text properties
        const val COLUMN_BLOCK_TEXT_GRADIENT_COLORS = "text_gradient_colors" // Comma-separated hex or int
        const val COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS = "text_gradient_offsets" // Comma-separated floats
        const val COLUMN_BLOCK_TEXT_GRADIENT_TYPE = "text_gradient_type" // 0, 1, 2
        
        // Original image dimensions for scaling
        const val COLUMN_BLOCK_ORIGINAL_WIDTH = "original_width"
        const val COLUMN_BLOCK_ORIGINAL_HEIGHT = "original_height"

        // change_images table to track whether an image has been interacted with
        const val TABLE_CHANGE_IMAGES = "change_images"
        const val COLUMN_CHANGE_IMAGE_ID = "change_image_id"
        const val COLUMN_CHANGE_IMAGE_IMAGE_ID = "image_id"
        const val COLUMN_CHANGE_IMAGE_ROOM_ID = "room_id"
        const val COLUMN_CHANGE_IMAGE_FLAG = "is_changed" // 0 = false, 1 = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_ROOMS (
                $COLUMN_ROOM_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL DEFAULT '',
                $COLUMN_COVER_URI TEXT NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_IMAGES (
                $COLUMN_IMAGE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_ROOM_ID INTEGER,
                $COLUMN_IMAGE_URI TEXT NOT NULL,
                $COLUMN_DISPLAY_ORDER INTEGER,
                $COLUMN_IS_TRANSLATED INTEGER DEFAULT 0,
                FOREIGN KEY ($COLUMN_ROOM_ID) REFERENCES $TABLE_ROOMS($COLUMN_ROOM_ID)
            )
        """)

        // Index để tăng tốc truy vấn images theo room_id
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_id ON $TABLE_IMAGES($COLUMN_ROOM_ID)")
        
        // Index để tăng tốc truy vấn images theo display_order trong room
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_order ON $TABLE_IMAGES($COLUMN_ROOM_ID, $COLUMN_DISPLAY_ORDER)")

        // translations: lưu translated_text VÀ original_text (per-block)
        db.execSQL("""
            CREATE TABLE translations (
                text_id INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_IMAGE_ID INTEGER,
                translated_text TEXT,
                original_text TEXT,
                pending_delete INTEGER DEFAULT 0,
                apply_merge INTEGER DEFAULT 1,
                x INTEGER DEFAULT 0,
                y INTEGER DEFAULT 0,
                width INTEGER DEFAULT 0,
                height INTEGER DEFAULT 0,
                FOREIGN KEY ($COLUMN_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
            )
        """)

        // Index để tăng tốc truy vấn translations theo image_id
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_image_id ON translations($COLUMN_IMAGE_ID)")
        
        // Index để tăng tốc truy vấn translations pending delete
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_pending_delete ON translations($COLUMN_IMAGE_ID, pending_delete)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_API_KEYS (
                $COLUMN_API_KEY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_API_KEY_VALUE TEXT NOT NULL,
                $COLUMN_API_KEY_TYPE TEXT NOT NULL DEFAULT 'default',
                created_date TEXT NOT NULL,
                updated_date TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1
            )
            """
        )
        // Create table for image blocks (per-image overlay/text blocks)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_IMAGE_BLOCKS (
                $COLUMN_BLOCK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_BLOCK_IMAGE_ID INTEGER NOT NULL,
                $COLUMN_BLOCK_X INTEGER NOT NULL,
                $COLUMN_BLOCK_Y INTEGER NOT NULL,
                $COLUMN_BLOCK_WIDTH INTEGER NOT NULL,
                $COLUMN_BLOCK_HEIGHT INTEGER NOT NULL,
                $COLUMN_BLOCK_OVERLAY_TYPE INTEGER DEFAULT 0,
                $COLUMN_BLOCK_OVERLAY_COLOR INTEGER,
                $COLUMN_BLOCK_OVERLAY_BRIGHTNESS REAL DEFAULT 1.0,
                $COLUMN_BLOCK_OVERLAY_ALPHA REAL DEFAULT 1.0,
                $COLUMN_BLOCK_OVERLAY_SATURATION REAL DEFAULT 1.0,
                $COLUMN_BLOCK_OVERLAY_INSET REAL DEFAULT 0.0,
                $COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL REAL DEFAULT 0.0,
                $COLUMN_BLOCK_OVERLAY_INSET_VERTICAL REAL DEFAULT 0.0,
                $COLUMN_BLOCK_OVERLAY_ROTATION REAL,
                $COLUMN_BLOCK_TEXT_COLOR INTEGER,
                $COLUMN_BLOCK_TEXT_BRIGHTNESS REAL DEFAULT 1.0,
                $COLUMN_BLOCK_TEXT_BOLDNESS REAL DEFAULT 1.0,
                $COLUMN_BLOCK_TEXT_SATURATION REAL DEFAULT 1.0,
                $COLUMN_BLOCK_BORDER_COLOR INTEGER,
                $COLUMN_BLOCK_BORDER_BRIGHTNESS REAL DEFAULT 1.0,
                $COLUMN_BLOCK_BORDER_BOLDNESS REAL DEFAULT 1.0,
                $COLUMN_BLOCK_BORDER_THICKNESS REAL DEFAULT 0.0,
                $COLUMN_BLOCK_SHADOW_COLOR INTEGER,
                $COLUMN_BLOCK_SHADOW_ALPHA REAL DEFAULT 1.0,
                $COLUMN_BLOCK_SHADOW_RADIUS REAL DEFAULT 0.0,
                $COLUMN_BLOCK_ROTATION REAL DEFAULT 0.0,
                $COLUMN_BLOCK_FONT_FAMILY TEXT DEFAULT '',
                $COLUMN_BLOCK_FONT_SIZE REAL DEFAULT 12.0,
                line_spacing REAL DEFAULT 1.0,
                $COLUMN_BLOCK_TEXT_ALIGN TEXT DEFAULT 'CENTER',
                $COLUMN_BLOCK_TEXT_GRADIENT_COLORS TEXT,
                $COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS TEXT,
                $COLUMN_BLOCK_TEXT_GRADIENT_TYPE INTEGER DEFAULT 0,
                FOREIGN KEY ($COLUMN_BLOCK_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
            )
            """
        )
        
        // Index để tăng tốc truy vấn image_blocks theo image_id
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_image_blocks_image_id ON $TABLE_IMAGE_BLOCKS($COLUMN_BLOCK_IMAGE_ID)")
        
        // Create table for tracking changed images
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CHANGE_IMAGES (
                $COLUMN_CHANGE_IMAGE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CHANGE_IMAGE_IMAGE_ID INTEGER NOT NULL,
                $COLUMN_CHANGE_IMAGE_ROOM_ID INTEGER NOT NULL,
                $COLUMN_CHANGE_IMAGE_FLAG INTEGER NOT NULL DEFAULT 0,
                UNIQUE($COLUMN_CHANGE_IMAGE_IMAGE_ID)
            )
            """
        )
        
        // Index để tăng tốc truy vấn change_images theo room_id và flag
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_change_images_room_flag ON $TABLE_CHANGE_IMAGES($COLUMN_CHANGE_IMAGE_ROOM_ID, $COLUMN_CHANGE_IMAGE_FLAG)")
        
        // Index để tăng tốc truy vấn change_images theo image_id (đã có UNIQUE constraint, có thể skip)
        // db.execSQL("CREATE INDEX IF NOT EXISTS idx_change_images_image_id ON $TABLE_CHANGE_IMAGES($COLUMN_CHANGE_IMAGE_IMAGE_ID)")
        
        // Create table for room settings
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ROOM_SETTINGS (
                $COLUMN_SETTING_ROOM_ID INTEGER PRIMARY KEY,
                $COLUMN_AUTO_TRANSLATE_NEW_IMAGES INTEGER NOT NULL DEFAULT 1,
                $COLUMN_ANCIENT_TRANSLATION_ENABLED INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY ($COLUMN_SETTING_ROOM_ID) REFERENCES $TABLE_ROOMS($COLUMN_ROOM_ID) ON DELETE CASCADE
            )
            """
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_IMAGES ADD COLUMN $COLUMN_IS_TRANSLATED INTEGER DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_ROOMS ADD COLUMN $COLUMN_TITLE TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE translations ADD COLUMN original_image_width INTEGER")
            db.execSQL("ALTER TABLE translations ADD COLUMN original_image_height INTEGER")
            // Chỉ thêm cột rotation nếu chưa tồn tại
            try {
                val cursor = db.rawQuery("PRAGMA table_info(translations)", null)
                var hasRotation = false
                while (cursor.moveToNext()) {
                    val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (columnName == "rotation") {
                        hasRotation = true
                        break
                    }
                }
                cursor.close()
                if (!hasRotation) {
                    db.execSQL("ALTER TABLE translations ADD COLUMN rotation REAL DEFAULT 0")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể kiểm tra/thêm cột rotation cho translations", e)
            }
            // Update các bản ghi cũ có rotation IS NULL về 0
            try {
                db.execSQL("UPDATE translations SET rotation = 0 WHERE rotation IS NULL")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể update rotation cho các bản ghi cũ", e)
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS api_keys ( api_key_value TEXT PRIMARY KEY )")
        }
        // Thêm cột type cho bảng api_keys nếu chưa có (version 5)
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE $TABLE_API_KEYS ADD COLUMN $COLUMN_API_KEY_TYPE TEXT NOT NULL DEFAULT 'default'")
            } catch (e: Exception) {
                // Có thể cột đã tồn tại
            }
            
            // Thêm cột shape_type cho bảng translations
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN shape_type INTEGER DEFAULT 0")
                // Cập nhật tất cả dữ liệu cũ có shape_type NULL về 0 (hình chữ nhật)
                db.execSQL("UPDATE translations SET shape_type = 0 WHERE shape_type IS NULL")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột shape_type vào bảng translations", e)
            }
        }
        
        // Thêm cột background_type và average_background_color cho bảng translations (version 6)
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN background_type INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE translations ADD COLUMN average_background_color INTEGER")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm các cột màu nền vào bảng translations", e)
            }
        }
        
        // Thêm cột original_text_color cho bảng translations (version 7)
        if (oldVersion < 7) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN original_text_color INTEGER")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột original_text_color vào bảng translations", e)
            }
        }
        
        // Thêm các cột màu sắc tùy chỉnh cho bảng translations (version 8)
        if (oldVersion < 8) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN custom_overlay_color INTEGER")
                db.execSQL("ALTER TABLE translations ADD COLUMN custom_text_color INTEGER")
                db.execSQL("ALTER TABLE translations ADD COLUMN overlay_alpha REAL DEFAULT 1.0")
                db.execSQL("ALTER TABLE translations ADD COLUMN text_boldness REAL DEFAULT 1.0")
                db.execSQL("ALTER TABLE translations ADD COLUMN overlay_saturation REAL DEFAULT 1.0")
                db.execSQL("ALTER TABLE translations ADD COLUMN text_saturation REAL DEFAULT 1.0")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm các cột màu sắc tùy chỉnh vào bảng translations", e)
            }
        }
        // Add original_text column to images table in version 15
        if (oldVersion < 15) {
            try {
                db.execSQL("ALTER TABLE $TABLE_IMAGES ADD COLUMN $COLUMN_ORIGINAL_TEXT TEXT")
               
                // Migrate existing original_text from translations to images (take first distinct original_text per image)
                try {
                    db.execSQL("""
                        UPDATE $TABLE_IMAGES
                        SET $COLUMN_ORIGINAL_TEXT = (
                            SELECT original_text FROM translations 
                            WHERE translations.$COLUMN_IMAGE_ID = $TABLE_IMAGES.$COLUMN_IMAGE_ID 
                            LIMIT 1
                        )
                        WHERE EXISTS (
                            SELECT 1 FROM translations 
                            WHERE translations.$COLUMN_IMAGE_ID = $TABLE_IMAGES.$COLUMN_IMAGE_ID
                        )
                    """)
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Không thể migrate original_text", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột original_text vào images", e)
            }
        }
        // Add new table for image blocks in version 10
        if (oldVersion < 10) {
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_IMAGE_BLOCKS (
                        $COLUMN_BLOCK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COLUMN_BLOCK_IMAGE_ID INTEGER NOT NULL,
                        $COLUMN_BLOCK_X INTEGER NOT NULL,
                        $COLUMN_BLOCK_Y INTEGER NOT NULL,
                        $COLUMN_BLOCK_WIDTH INTEGER NOT NULL,
                        $COLUMN_BLOCK_HEIGHT INTEGER NOT NULL,
                        $COLUMN_BLOCK_OVERLAY_TYPE INTEGER DEFAULT 0,
                        $COLUMN_BLOCK_OVERLAY_COLOR INTEGER,
                        $COLUMN_BLOCK_OVERLAY_BRIGHTNESS REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_OVERLAY_ALPHA REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_OVERLAY_SATURATION REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_OVERLAY_INSET REAL DEFAULT 0.0,
                        $COLUMN_BLOCK_TEXT_COLOR INTEGER,
                        $COLUMN_BLOCK_TEXT_BRIGHTNESS REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_TEXT_BOLDNESS REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_TEXT_SATURATION REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_BORDER_COLOR INTEGER,
                        $COLUMN_BLOCK_BORDER_BRIGHTNESS REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_BORDER_BOLDNESS REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_BORDER_THICKNESS REAL DEFAULT 0.0,
                        $COLUMN_BLOCK_SHADOW_COLOR INTEGER,
                        $COLUMN_BLOCK_SHADOW_ALPHA REAL DEFAULT 1.0,
                        $COLUMN_BLOCK_SHADOW_RADIUS REAL DEFAULT 0.0,
                        $COLUMN_BLOCK_ROTATION REAL DEFAULT 0.0,
                        $COLUMN_BLOCK_FONT_FAMILY TEXT DEFAULT '',
                        $COLUMN_BLOCK_FONT_SIZE REAL DEFAULT 12.0,
                        FOREIGN KEY ($COLUMN_BLOCK_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
                    )
                    """
                )
                // Migrate existing translation styling into image_blocks so older data keeps styling
                try {
                    val cursor = db.rawQuery("SELECT * FROM translations", null)
                    while (cursor.moveToNext()) {
                        // Safe column lookup
                        fun idx(name: String) = try { cursor.getColumnIndex(name) } catch (e: Exception) { -1 }
                        val imgIdIdx = idx(COLUMN_IMAGE_ID)
                        if (imgIdIdx < 0) continue
                        val imageId = cursor.getLong(imgIdIdx)
                        val left = cursor.getIntOrNull(idx("bounds_left")) ?: continue
                        val top = cursor.getIntOrNull(idx("bounds_top")) ?: continue
                        val right = cursor.getIntOrNull(idx("bounds_right")) ?: continue
                        val bottom = cursor.getIntOrNull(idx("bounds_bottom")) ?: continue
                        val bw = right - left
                        val bh = bottom - top
                        val customOverlayColor = cursor.getIntOrNull(idx("custom_overlay_color"))
                        val avgBgColor = cursor.getIntOrNull(idx("average_background_color"))
                        val overlayColor = customOverlayColor ?: avgBgColor
                        val customTextColor = cursor.getIntOrNull(idx("custom_text_color"))
                        val origTextColor = cursor.getIntOrNull(idx("original_text_color"))
                        val textColor = customTextColor ?: origTextColor
                        val overlayAlpha = cursor.getFloatOrDefault(idx("overlay_alpha"), 1.0f)
                        val overlaySat = cursor.getFloatOrDefault(idx("overlay_saturation"), 1.0f)
                        val textBold = cursor.getFloatOrDefault(idx("text_boldness"), 1.0f)
                        val textSat = cursor.getFloatOrDefault(idx("text_saturation"), 1.0f)
                        val rotation = cursor.getFloatOrDefault(idx("rotation"), 0f)
                        val fontSize = cursor.getFloatOrDefault(idx("font_size"), 12f)
                        val shapeType = cursor.getIntOrDefault(idx("shape_type"), 0)
                        val values = ContentValues().apply {
                            put(COLUMN_BLOCK_IMAGE_ID, imageId)
                            put(COLUMN_BLOCK_X, left)
                            put(COLUMN_BLOCK_Y, top)
                            put(COLUMN_BLOCK_WIDTH, bw)
                            put(COLUMN_BLOCK_HEIGHT, bh)
                            put(COLUMN_BLOCK_OVERLAY_TYPE, shapeType)
                            overlayColor?.let { put(COLUMN_BLOCK_OVERLAY_COLOR, it) }
                            put(COLUMN_BLOCK_OVERLAY_ALPHA, overlayAlpha)
                            put(COLUMN_BLOCK_OVERLAY_SATURATION, overlaySat)
                            textColor?.let { put(COLUMN_BLOCK_TEXT_COLOR, it) }
                            put(COLUMN_BLOCK_TEXT_BOLDNESS, textBold)
                            put(COLUMN_BLOCK_TEXT_SATURATION, textSat)
                            put(COLUMN_BLOCK_ROTATION, rotation)
                            put(COLUMN_BLOCK_FONT_SIZE, fontSize)
                        }
                        db.insert(TABLE_IMAGE_BLOCKS, null, values)
                    }
                    cursor.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Không thể migrate translations sang $TABLE_IMAGE_BLOCKS", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể tạo bảng $TABLE_IMAGE_BLOCKS", e)
            }
        }
        // Add change_images table in version 11
        if (oldVersion < 11) {
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_CHANGE_IMAGES (
                        $COLUMN_CHANGE_IMAGE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COLUMN_CHANGE_IMAGE_IMAGE_ID INTEGER NOT NULL,
                        $COLUMN_CHANGE_IMAGE_ROOM_ID INTEGER NOT NULL,
                        $COLUMN_CHANGE_IMAGE_FLAG INTEGER NOT NULL DEFAULT 0,
                        UNIQUE($COLUMN_CHANGE_IMAGE_IMAGE_ID)
                    )
                    """
                )
            } catch (e: Exception) {
                Log.w(TAG, "Không thể tạo bảng $TABLE_CHANGE_IMAGES", e)
            }
        }
        // Add lineSpacing column in version 12
        if (oldVersion < 12) {
            try {
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN line_spacing REAL DEFAULT 1.0")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột line_spacing (có thể đã tồn tại)", e)
            }
        }
        // Add text_align column in version 21
        if (oldVersion < 21) {
            try {
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_TEXT_ALIGN TEXT DEFAULT 'CENTER'")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột text_align (có thể đã tồn tại)", e)
            }
        }
        // Add pending_delete column to translations in version 14
        if (oldVersion < 14) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN pending_delete INTEGER DEFAULT 0")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột pending_delete (có thể đã tồn tại)", e)
            }
        }
        
        // Create indexes for better query performance in version 15
        if (oldVersion < 15) {
            try {
                // Index cho bảng images
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_id ON $TABLE_IMAGES($COLUMN_ROOM_ID)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_order ON $TABLE_IMAGES($COLUMN_ROOM_ID, $COLUMN_DISPLAY_ORDER)")
                
                // Index cho bảng translations
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_image_id ON translations($COLUMN_IMAGE_ID)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_pending_delete ON translations($COLUMN_IMAGE_ID, pending_delete)")
                
                // Index cho bảng image_blocks
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_image_blocks_image_id ON $TABLE_IMAGE_BLOCKS($COLUMN_BLOCK_IMAGE_ID)")
                
                // Index cho bảng change_images
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_change_images_room_flag ON $TABLE_CHANGE_IMAGES($COLUMN_CHANGE_IMAGE_ROOM_ID, $COLUMN_CHANGE_IMAGE_FLAG)")
                
            } catch (e: Exception) {
                Log.w(TAG, "Không thể tạo indexes", e)
            }
        }
        
        // Add room_settings table in version 16
        if (oldVersion < 16) {
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE_ROOM_SETTINGS (
                        $COLUMN_SETTING_ROOM_ID INTEGER PRIMARY KEY,
                        $COLUMN_AUTO_TRANSLATE_NEW_IMAGES INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY ($COLUMN_SETTING_ROOM_ID) REFERENCES $TABLE_ROOMS($COLUMN_ROOM_ID) ON DELETE CASCADE
                    )
                    """
                )
                // Initialize settings for existing rooms with default enabled (1)
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO $TABLE_ROOM_SETTINGS ($COLUMN_SETTING_ROOM_ID, $COLUMN_AUTO_TRANSLATE_NEW_IMAGES)
                    SELECT $COLUMN_ROOM_ID, 1 FROM $TABLE_ROOMS
                    """
                )
            } catch (e: Exception) {
                Log.w(TAG, "Không thể tạo bảng $TABLE_ROOM_SETTINGS", e)
            }
        }
        
        // Add overlay_rotation column to translations table in version 17
        if (oldVersion < 17) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN overlay_rotation REAL")
                Log.i(TAG, "Đã thêm cột overlay_rotation vào bảng translations")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột overlay_rotation (có thể đã tồn tại)", e)
            }
        }
        
        // Version 18: Đơn giản hóa bảng translations - chỉ giữ text_id, image_id, translated_text, pending_delete, apply_merge
        // Tất cả thông tin khác (bounds, colors, fonts...) đã có trong image_blocks
        if (oldVersion < 18) {
            try {
                // 1. Tạo bảng mới với cấu trúc đơn giản
                db.execSQL("""
                    CREATE TABLE translations_new (
                        text_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COLUMN_IMAGE_ID INTEGER,
                        translated_text TEXT,
                        pending_delete INTEGER DEFAULT 0,
                        apply_merge INTEGER DEFAULT 1,
                        FOREIGN KEY ($COLUMN_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
                    )
                """)
                
                // 2. Copy dữ liệu từ bảng cũ sang bảng mới (chỉ lấy các cột cần thiết)
                db.execSQL("""
                    INSERT INTO translations_new (text_id, $COLUMN_IMAGE_ID, translated_text, pending_delete, apply_merge)
                    SELECT text_id, $COLUMN_IMAGE_ID, translated_text, COALESCE(pending_delete, 0), COALESCE(apply_merge, 1)
                    FROM translations
                """)
                
                // 3. Xóa bảng cũ
                db.execSQL("DROP TABLE translations")
                
                // 4. Đổi tên bảng mới
                db.execSQL("ALTER TABLE translations_new RENAME TO translations")
                
                // 5. Tạo lại index
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_image_id ON translations($COLUMN_IMAGE_ID)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_pending_delete ON translations($COLUMN_IMAGE_ID, pending_delete)")
                
                Log.i(TAG, "Đã migrate bảng translations sang cấu trúc mới (text_id, image_id, translated_text, pending_delete, apply_merge)")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi migrate bảng translations", e)
            }
        }

        // Add ancient translation setting for rooms (version 19)
        if (oldVersion < 19) {
            try {
                db.execSQL("ALTER TABLE $TABLE_ROOM_SETTINGS ADD COLUMN $COLUMN_ANCIENT_TRANSLATION_ENABLED INTEGER NOT NULL DEFAULT 0")
                // Ensure existing rows have a default value of 0
                try {
                    db.execSQL("UPDATE $TABLE_ROOM_SETTINGS SET $COLUMN_ANCIENT_TRANSLATION_ENABLED = 0 WHERE $COLUMN_ANCIENT_TRANSLATION_ENABLED IS NULL")
                } catch (e: Exception) {
                    // ignore if update fails
                }
                Log.i(TAG, "Đã thêm cột $COLUMN_ANCIENT_TRANSLATION_ENABLED vào bảng $TABLE_ROOM_SETTINGS")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột ancient_translation_enabled vào $TABLE_ROOM_SETTINGS", e)
            }
        }
        // Version 22: Thêm các cột gradient cho text trong image_blocks
        if (oldVersion < 22) {
            try {
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_TEXT_GRADIENT_COLORS TEXT")
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS TEXT")
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_TEXT_GRADIENT_TYPE INTEGER DEFAULT 0")
                Log.i(TAG, "Đã thêm các cột gradient vào bảng $TABLE_IMAGE_BLOCKS")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm các cột gradient vào $TABLE_IMAGE_BLOCKS", e)
            }
        }

        if (oldVersion < 25) {
            try {
                // Kiểm tra nếu bảng api_keys có các cột quota thừa (consecutive_failures, remaining_quota, v.v.)
                // thì recreate bảng với cấu trúc đơn giản (Fail-and-Switch)
                val cursor = db.rawQuery("PRAGMA table_info($TABLE_API_KEYS)", null)
                var hasQuotaColumns = false
                while (cursor.moveToNext()) {
                    val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (columnName == "remaining_quota" || columnName == "consecutive_failures") {
                        hasQuotaColumns = true
                        break
                    }
                }
                cursor.close()

                if (hasQuotaColumns) {
                    Log.i(TAG, "Phát hiện cột quota thừa trong api_keys, recreate bảng sang bản đơn giản...")
                    // 1. Tạo bảng tạm
                    db.execSQL("DROP TABLE IF EXISTS api_keys_new")
                    db.execSQL("""
                        CREATE TABLE api_keys_new (
                            $COLUMN_API_KEY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                            $COLUMN_API_KEY_VALUE TEXT NOT NULL,
                            $COLUMN_API_KEY_TYPE TEXT NOT NULL DEFAULT 'default',
                            created_date TEXT NOT NULL,
                            updated_date TEXT NOT NULL,
                            is_active INTEGER NOT NULL DEFAULT 1
                        )
                    """)

                    // 2. Copy dữ liệu (chỉ lấy các cột cơ bản)
                    db.execSQL("""
                        INSERT INTO api_keys_new ($COLUMN_API_KEY_VALUE, $COLUMN_API_KEY_TYPE, created_date, updated_date, is_active)
                        SELECT $COLUMN_API_KEY_VALUE, $COLUMN_API_KEY_TYPE,
                               COALESCE(created_date, '2025-07-15'),
                               COALESCE(updated_date, '2025-07-15'),
                               COALESCE(is_active, 1)
                        FROM $TABLE_API_KEYS
                    """)

                    // 3. Đổi tên
                    db.execSQL("DROP TABLE $TABLE_API_KEYS")
                    db.execSQL("ALTER TABLE api_keys_new RENAME TO $TABLE_API_KEYS")
                    Log.i(TAG, "Đã dọn dẹp bảng api_keys thành công")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi dọn dẹp bảng api_keys", e)
            }
        }

        // Version 26: Thêm cột original_text vào bảng translations (per-block)
        if (oldVersion < 26) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN original_text TEXT")
                Log.i(TAG, "Đã thêm cột original_text vào bảng translations")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột original_text vào translations", e)
            }
        }

        // Version 27: Xóa cột original_text từ bảng images (giờ lưu trong translations)
        // Lưu ý: Dùng PRAGMA defer_foreign_keys = ON để tạm bỏ qua foreign keys constraint
        // khi DROP TABLE images (có khóa ngoại từ translations).
        if (oldVersion < 27) {
            try {
                // Tắt kiểm tra khóa ngoại tạm thời cho transaction này
                db.execSQL("PRAGMA defer_foreign_keys = ON;")
                
                // Dọn dẹp images_new nếu còn sót
                db.execSQL("DROP TABLE IF EXISTS images_new")

                // Tạo bảng mới giống hệt images gốc nhưng KHÔNG có original_text
                db.execSQL("""
                    CREATE TABLE images_new (
                        image_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        room_id INTEGER,
                        image_uri TEXT NOT NULL,
                        display_order INTEGER,
                        is_translated INTEGER DEFAULT 0,
                        FOREIGN KEY (room_id) REFERENCES manga_rooms(room_id)
                    )
                """)
                
                // Copy dữ liệu (chỉ lấy các cột có thật)
                db.execSQL("""
                    INSERT INTO images_new (image_id, room_id, image_uri, display_order, is_translated)
                    SELECT image_id, room_id, image_uri, display_order, is_translated FROM images
                """)
                
                // Xóa bảng cũ
                db.execSQL("DROP TABLE images")
                
                // Đổi tên bảng mới
                db.execSQL("ALTER TABLE images_new RENAME TO images")
                
                // Tạo lại index
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_id ON images(room_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_order ON images(room_id, display_order)")

                Log.i(TAG, "Đã xóa cột original_text khỏi bảng images (recreate table) với defer_foreign_keys")
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi khi xử lý migration version 27", e)
            }
        }
        
        // Version 28: Thử lại việc xóa cột original_text từ bảng images
        // Do migration 27 có thể đã fail (vì lỗi Foreign Key) nhưng vẫn được đánh dấu là thành công,
        // chúng ta cần một phiên bản 28 để đảm bảo cột original_text thực sự bị xóa ở các user đã lỡ lên 27.
        if (oldVersion < 28) {
            try {
                db.execSQL("PRAGMA defer_foreign_keys = ON;")
                db.execSQL("DROP TABLE IF EXISTS images_new")

                // Tạo bảng mới giống hệt images gốc nhưng KHÔNG có original_text
                db.execSQL("""
                    CREATE TABLE images_new (
                        image_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        room_id INTEGER,
                        image_uri TEXT NOT NULL,
                        display_order INTEGER,
                        is_translated INTEGER DEFAULT 0,
                        FOREIGN KEY (room_id) REFERENCES manga_rooms(room_id)
                    )
                """)
                
                // Trích xuất dữ liệu, đề phòng bảng cũ có hoặc không có original_text
                db.execSQL("""
                    INSERT INTO images_new (image_id, room_id, image_uri, display_order, is_translated)
                    SELECT image_id, room_id, image_uri, display_order, is_translated FROM images
                """)
                
                db.execSQL("DROP TABLE images")
                db.execSQL("ALTER TABLE images_new RENAME TO images")
                
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_id ON images(room_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_order ON images(room_id, display_order)")

                Log.i(TAG, "Đã xóa cột original_text khỏi bảng images ở version 28")
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi khi xử lý migration version 28", e)
            }
        }
        
        // Version 29: Add coordinates to translations table
        if (oldVersion < 29) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN x INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE translations ADD COLUMN y INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE translations ADD COLUMN width INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE translations ADD COLUMN height INTEGER DEFAULT 0")
                
                // Migrate data from image_blocks
                db.execSQL("""
                    UPDATE translations 
                    SET 
                        x = (SELECT x FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = translations.text_id),
                        y = (SELECT y FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = translations.text_id),
                        width = (SELECT width FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = translations.text_id),
                        height = (SELECT height FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = translations.text_id)
                    WHERE EXISTS (SELECT 1 FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = translations.text_id)
                """)
                Log.i(TAG, "Đã thêm cột tọa độ vào translations và migrate dữ liệu")
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi migration version 29", e)
            }
        }

        // Version 30: Add original_font_size to translations table
        if (oldVersion < 30) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN original_font_size REAL DEFAULT -1")
                Log.i(TAG, "Đã thêm cột original_font_size vào bảng translations ở version 30")
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi migration version 30", e)
            }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Hạ cấp database từ $oldVersion xuống $newVersion. Không làm gì để tránh crash.")
    }

    // --- Helper methods for image blocks CRUD ---
    fun insertImageBlock(imageId: Long,
                         x: Int, y: Int, width: Int, height: Int,
                         overlayType: Int = 0,
                         overlayColor: Int? = null,
                         overlayBrightness: Float = 1.0f,
                         overlayAlpha: Float = 1.0f,
                         overlaySaturation: Float = 1.0f,
                         overlayInset: Float = 0f,
                         overlayInsetHorizontal: Float = 0f,
                         overlayInsetVertical: Float = 0f,
                         overlayRotation: Float? = null,
                         textColor: Int? = null,
                         textBrightness: Float = 1.0f,
                         textBoldness: Float = 1.0f,
                         textSaturation: Float = 1.0f,
                         borderColor: Int? = null,
                         borderBrightness: Float = 1.0f,
                         borderBoldness: Float = 1.0f,
                         borderThickness: Float = 0f,
                         // Shadow properties
                         shadowColor: Int? = null,
                         shadowAlpha: Float = 1.0f,
                         shadowRadius: Float = 0f,
                         rotation: Float = 0f,
                         fontFamily: String = "",
                         fontSize: Float = 12f,
                         lineSpacing: Float = 1.0f,
                         textAlign: String = "CENTER",
                         textGradientColors: List<Int>? = null,
                         textGradientOffsets: List<Float>? = null,
                         textGradientType: Int = 0,
                         originalWidth: Int? = null,
                         originalHeight: Int? = null
    ): Long {
        val db = writableDatabase
        if (shadowColor != null || shadowAlpha != 1.0f || shadowRadius != 0f) {
            Log.i("DatabaseHelper", "LƯU SHADOW: imageId=$imageId shadowColor=$shadowColor shadowAlpha=$shadowAlpha shadowRadius=$shadowRadius")
        }
        // Log inset values for debugging
        if (overlayInset != 0f || overlayInsetHorizontal != 0f || overlayInsetVertical != 0f) {
            Log.i("DatabaseHelper", "LƯU INSET: imageId=$imageId x=$x y=$y w=$width h=$height inset=$overlayInset insetH=$overlayInsetHorizontal insetV=$overlayInsetVertical")
        }
        val values = ContentValues().apply {
                            put(COLUMN_BLOCK_IMAGE_ID, imageId)
                            put(COLUMN_BLOCK_X, x)
                            put(COLUMN_BLOCK_Y, y)
                            put(COLUMN_BLOCK_WIDTH, width)
                            put(COLUMN_BLOCK_HEIGHT, height)
                            put(COLUMN_BLOCK_OVERLAY_TYPE, overlayType)
                            overlayColor?.let { put(COLUMN_BLOCK_OVERLAY_COLOR, it) }
                            put(COLUMN_BLOCK_OVERLAY_BRIGHTNESS, overlayBrightness)
                            put(COLUMN_BLOCK_OVERLAY_ALPHA, overlayAlpha)
                            put(COLUMN_BLOCK_OVERLAY_SATURATION, overlaySaturation)
                            put(COLUMN_BLOCK_OVERLAY_INSET, overlayInset)
                            put(COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL, overlayInsetHorizontal)
                            put(COLUMN_BLOCK_OVERLAY_INSET_VERTICAL, overlayInsetVertical)
                            overlayRotation?.let { put(COLUMN_BLOCK_OVERLAY_ROTATION, it) }
                            put(COLUMN_BLOCK_TEXT_COLOR, textColor ?: 0xFF000000.toInt()) // Mặc định màu đen nếu null
                            put(COLUMN_BLOCK_TEXT_BRIGHTNESS, textBrightness)
                            put(COLUMN_BLOCK_TEXT_BOLDNESS, textBoldness)
                            put(COLUMN_BLOCK_TEXT_SATURATION, textSaturation)
                            borderColor?.let { put(COLUMN_BLOCK_BORDER_COLOR, it) }
                            put(COLUMN_BLOCK_BORDER_BRIGHTNESS, borderBrightness)
                            put(COLUMN_BLOCK_BORDER_BOLDNESS, borderBoldness)
                            put(COLUMN_BLOCK_BORDER_THICKNESS, borderThickness)
                            // Store shadow properties
                            shadowColor?.let { put(COLUMN_BLOCK_SHADOW_COLOR, it) }
                            put(COLUMN_BLOCK_SHADOW_ALPHA, shadowAlpha)
                            put(COLUMN_BLOCK_SHADOW_RADIUS, shadowRadius)
                            put(COLUMN_BLOCK_ROTATION, rotation)
                            put(COLUMN_BLOCK_FONT_FAMILY, fontFamily)
                            put(COLUMN_BLOCK_FONT_SIZE, fontSize)
                            put(COLUMN_BLOCK_LINE_SPACING, lineSpacing)
                            put(COLUMN_BLOCK_TEXT_ALIGN, textAlign)
                            val gradientColorsStr = textGradientColors?.joinToString(",") { it.toString() }
                            val gradientOffsetsStr = textGradientOffsets?.joinToString(",") { it.toString() }
                            
                            put(COLUMN_BLOCK_TEXT_GRADIENT_COLORS, gradientColorsStr)
                            put(COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS, gradientOffsetsStr)
                            put(COLUMN_BLOCK_TEXT_GRADIENT_TYPE, textGradientType)
                            
                            originalWidth?.let { put(COLUMN_BLOCK_ORIGINAL_WIDTH, it) }
                            originalHeight?.let { put(COLUMN_BLOCK_ORIGINAL_HEIGHT, it) }
                            
                            if (gradientColorsStr != null) {
                                Log.i(TAG, "[DB-WRITE-GRADIENT] imageId=$imageId colors=$gradientColorsStr type=$textGradientType")
                            }
                        }
                        val id = db.insert(TABLE_IMAGE_BLOCKS, null, values)
        // Explicit log when shadow properties are present to make it easy to spot
        if (shadowColor != null || (shadowRadius > 0f) || shadowAlpha != 1.0f) {
        }
        return id
    }

    fun getBlocksForImage(imageId: Long): List<com.example.ocrmanga.data.models.ImageBlock> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_IMAGE_BLOCKS WHERE $COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
        val result = mutableListOf<com.example.ocrmanga.data.models.ImageBlock>()
        while (cursor.moveToNext()) {
            result.add(cursorToImageBlock(cursor))
        }
        cursor.close()
        return result
    }

    /**
     * Lấy danh sách TextBlockInfo đầy đủ từ DB cho một ảnh (bao gồm cả original_text và tọa độ)
     * Sử dụng JOIN để đảm bảo map đúng tọa độ với text theo ID (text_id = block_id)
     */
    fun getBlocksForImageAsTextBlockInfo(imageId: Long): List<TextBlockInfo> {
        val db = readableDatabase
        val blocks = mutableListOf<TextBlockInfo>()
        
        // Join 2 bảng để lấy đầy đủ thông tin spatial (bảng blocks) và content (bảng translations)
        // translations là source of truth để đảm bảo không mất text nếu image_blocks bị xóa hoặc lỗi
        val query = """
            SELECT t.x as trans_x, t.y as trans_y, t.width as trans_width, t.height as trans_height,
                   t.original_text, t.translated_text, b.*
            FROM translations t
            LEFT JOIN $TABLE_IMAGE_BLOCKS b ON t.text_id = b.$COLUMN_BLOCK_ID
            WHERE t.$COLUMN_IMAGE_ID = ? AND t.pending_delete = 0
            ORDER BY t.text_id ASC
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(imageId.toString()))

        // Lấy indices các cột content bổ sung
        val origTextIdx = cursor.getColumnIndex("original_text")
        val transTextIdx = cursor.getColumnIndex("translated_text")

        while (cursor.moveToNext()) {
            val block = cursorToTextBlockInfo(cursor)
            val orig = if (origTextIdx >= 0) cursor.getStringOrNull(origTextIdx) else null
            val trans = if (transTextIdx >= 0) cursor.getStringOrNull(transTextIdx) else null

            // Gán nội dung vào block (ưu tiên original_text, fallback sang translated_text nếu cần)
            val finalBlock = block.copy(
                text = trans ?: "",
                originalText = orig ?: trans ?: ""
            )
            blocks.add(finalBlock)
        }
        cursor.close()
        
        return blocks
    }

    fun getImageIdByUri(uri: Uri): Long? {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT $COLUMN_IMAGE_ID
            FROM $TABLE_IMAGES
            WHERE $COLUMN_IMAGE_URI = ?
        """, arrayOf(uri.toString()))
        
        return if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            cursor.close()
            id
        } else {
            cursor.close()
            null
        }
    }

    private fun cursorToTextBlockInfo(cursor: android.database.Cursor): TextBlockInfo {
        fun idx(name: String) = try { cursor.getColumnIndexOrThrow(name) } catch (e: Exception) { -1 }
        
        var x = cursor.getIntOrDefault(idx("trans_x"), 0)
        var y = cursor.getIntOrDefault(idx("trans_y"), 0)
        var w = cursor.getIntOrDefault(idx("trans_width"), 0)
        var h = cursor.getIntOrDefault(idx("trans_height"), 0)

        // Fallback to image_blocks if translations coordinates are empty (e.g. older data)
        if (w == 0 || h == 0) {
            x = cursor.getIntOrDefault(idx(COLUMN_BLOCK_X), x)
            y = cursor.getIntOrDefault(idx(COLUMN_BLOCK_Y), y)
            w = cursor.getIntOrDefault(idx(COLUMN_BLOCK_WIDTH), w)
            h = cursor.getIntOrDefault(idx(COLUMN_BLOCK_HEIGHT), h)
        }
        
        return TextBlockInfo(
            text = "",
            originalText = "",
            bounds = android.graphics.Rect(x, y, x + w, y + h),
            fontSize = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_FONT_SIZE), 14f),
            fontFamily =  cursor.getStringOrNull(idx(COLUMN_BLOCK_FONT_FAMILY)) ?: "mto_comic_2",
            rotation = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_ROTATION), 0f),
            lineSpacing = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_LINE_SPACING), 1f),
            customOverlayColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_OVERLAY_COLOR)),
            overlayAlpha = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_ALPHA), 1f),
            overlaySaturation = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_SATURATION), 1f),
            overlayInset = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_INSET), 0f),
            overlayInsetHorizontal = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL), 0f),
            overlayInsetVertical = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_INSET_VERTICAL), 0f),
            overlayRotation = cursor.getFloatOrNull(idx(COLUMN_BLOCK_OVERLAY_ROTATION)),
            shapeType = cursor.getIntOrDefault(idx(COLUMN_BLOCK_OVERLAY_TYPE), 0),
            customTextColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_TEXT_COLOR)),
            textBoldness = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_TEXT_BOLDNESS), 1f),
            textSaturation = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_TEXT_SATURATION), 1f),
            customBorderColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_BORDER_COLOR)),
            borderThickness = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_BORDER_THICKNESS), 0f),
            customShadowColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_SHADOW_COLOR)),
            shadowAlpha = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_SHADOW_ALPHA), 1f),
            shadowRadius = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_SHADOW_RADIUS), 0f),
            textAlign = try { com.example.ocrmanga.data.models.TextAlignMode.valueOf(cursor.getString(idx(COLUMN_BLOCK_TEXT_ALIGN))) } catch (e: Exception) { com.example.ocrmanga.data.models.TextAlignMode.CENTER },
            textGradientColors = try { cursor.getString(idx(COLUMN_BLOCK_TEXT_GRADIENT_COLORS))?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() } } catch (e: Exception) { null },
            textGradientOffsets = try { cursor.getString(idx(COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS))?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toFloatOrNull() } } catch (e: Exception) { null },
            textGradientType = try { cursor.getInt(idx(COLUMN_BLOCK_TEXT_GRADIENT_TYPE)) } catch (e: Exception) { 0 },
            originalImageWidth = cursor.getIntOrNull(idx(COLUMN_BLOCK_ORIGINAL_WIDTH)),
            originalImageHeight = cursor.getIntOrNull(idx(COLUMN_BLOCK_ORIGINAL_HEIGHT))
        )
    }

    private fun cursorToImageBlock(cursor: android.database.Cursor): com.example.ocrmanga.data.models.ImageBlock {
        fun idx(name: String) = try { cursor.getColumnIndexOrThrow(name) } catch (e: Exception) { -1 }
        val id = if (idx(COLUMN_BLOCK_ID) >= 0) cursor.getLong(idx(COLUMN_BLOCK_ID)) else -1L
        val imageId = if (idx(COLUMN_BLOCK_IMAGE_ID) >= 0) cursor.getLong(idx(COLUMN_BLOCK_IMAGE_ID)) else -1L
        val x = cursor.getIntOrDefault(idx(COLUMN_BLOCK_X), 0)
        val y = cursor.getIntOrDefault(idx(COLUMN_BLOCK_Y), 0)
        val width = cursor.getIntOrDefault(idx(COLUMN_BLOCK_WIDTH), 0)
        val height = cursor.getIntOrDefault(idx(COLUMN_BLOCK_HEIGHT), 0)
        val overlayType = cursor.getIntOrDefault(idx(COLUMN_BLOCK_OVERLAY_TYPE), 0)
        val overlayColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_OVERLAY_COLOR))
        val overlayBrightness = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_BRIGHTNESS), 1.0f)
        val overlayAlpha = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_ALPHA), 1.0f)
        val overlaySat = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_SATURATION), 1.0f)
        val overlayInset = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_INSET), 0f)
        val overlayInsetH = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL), 0f)
        val overlayInsetV = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_OVERLAY_INSET_VERTICAL), 0f)
        val overlayRotation = cursor.getFloatOrNull(idx(COLUMN_BLOCK_OVERLAY_ROTATION))
        val textColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_TEXT_COLOR))
        val textGradientColors = cursor.getString(idx(COLUMN_BLOCK_TEXT_GRADIENT_COLORS))?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() }
        val textGradientOffsets = cursor.getString(idx(COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS))?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toFloatOrNull() }
        val textGradientType = cursor.getIntOrDefault(idx(COLUMN_BLOCK_TEXT_GRADIENT_TYPE), 0)
        val textBrightness = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_TEXT_BRIGHTNESS), 1.0f)
        val textBoldness = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_TEXT_BOLDNESS), 1.0f)
        val textSat = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_TEXT_SATURATION), 1.0f)
        val borderColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_BORDER_COLOR))
        val borderBrightness = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_BORDER_BRIGHTNESS), 1.0f)
        val borderBoldness = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_BORDER_BOLDNESS), 1.0f)
        val borderThickness = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_BORDER_THICKNESS), 0f)
        val shadowColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_SHADOW_COLOR))
        val shadowAlpha = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_SHADOW_ALPHA), 1.0f)
        val shadowRadius = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_SHADOW_RADIUS), 0f)
        if (shadowColor != null || (shadowRadius > 0f) || shadowAlpha != 1.0f) {
        }
        val rotation = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_ROTATION), 0f)
        val fontFamily = cursor.getString(idx(COLUMN_BLOCK_FONT_FAMILY)) ?: "mto_astro_city"
        val fontSize = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_FONT_SIZE), 12f)
        val textAlignStr = try { cursor.getString(idx(COLUMN_BLOCK_TEXT_ALIGN)) } catch (e: Exception) { "CENTER" }
        val textAlign = try { com.example.ocrmanga.data.models.TextAlignMode.valueOf(textAlignStr) } catch (e: Exception) { com.example.ocrmanga.data.models.TextAlignMode.CENTER }
        return com.example.ocrmanga.data.models.ImageBlock(
            blockId = id,
            imageId = imageId,
            x = x,
            y = y,
            width = width,
            height = height,
            overlayType = overlayType,
            overlayColor = overlayColor,
            overlayBrightness = overlayBrightness,
            overlayAlpha = overlayAlpha,
            overlaySaturation = overlaySat,
            overlayInset = overlayInset,
            overlayInsetHorizontal = overlayInsetH,
            overlayInsetVertical = overlayInsetV,
            overlayRotation = overlayRotation,
            textColor = textColor,
            textBrightness = textBrightness,
            textBoldness = textBoldness,
            textSaturation = textSat,
            borderColor = borderColor,
            borderBrightness = borderBrightness,
            borderBoldness = borderBoldness,
            borderThickness = borderThickness,
            shadowColor = shadowColor,
            shadowAlpha = shadowAlpha,
            shadowRadius = shadowRadius,
            rotation = rotation,
            fontFamily = if (fontFamily.isNullOrBlank()) "mto_astro_city" else fontFamily,
            fontSize = fontSize,
            textAlign = textAlign,
            textGradientColors = textGradientColors,
            textGradientOffsets = textGradientOffsets,
            textGradientType = textGradientType
        )
    }

    fun deleteBlocksForImage(imageId: Long) {
        val db = writableDatabase
        db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
    }

    /**
     * Xóa HOÀN TOÀN tất cả translations của một image khỏi DB.
     * Được gọi khi user chọn OFF (tắt bản dịch) và sau đó SAVE.
     */
    fun deleteAllTranslationsForImage(imageId: Long) {
        val db = writableDatabase
        try {
            db.beginTransaction()
            // Xóa tất cả translations
            val deletedTranslations = db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            // Xóa tất cả image_blocks
            val deletedBlocks = db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
            // Xóa pending translations nếu có
            try { deletePendingTranslations(imageId) } catch (e: Exception) { /* ignore */ }
            // Clear change flag
            try { clearImageChange(imageId) } catch (e: Exception) { /* ignore */ }
            // Update is_translated = 0 cho image
            val imageValues = ContentValues().apply {
                put(COLUMN_IS_TRANSLATED, 0)
            }
            db.update(TABLE_IMAGES, imageValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all translations for imageId=$imageId", e)
        } finally {
            db.endTransaction()
        }
    }
    
    /**
     * Xóa hoàn toàn một ảnh khỏi room, bao gồm:
     * - Translations của ảnh
     * - Image blocks của ảnh
     * - Record trong bảng images
     * - File vật lý của ảnh
     */
    fun deleteImageFromRoom(imageId: Long) {
        val db = writableDatabase
        try {
            db.beginTransaction()
            
            // Lấy URI của ảnh để xóa file vật lý sau
            var imageUri: String? = null
            val cursor = db.rawQuery(
                "SELECT $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_ID = ?",
                arrayOf(imageId.toString())
            )
            if (cursor.moveToFirst()) {
                imageUri = cursor.getString(0)
            }
            cursor.close()
            
            // Xóa tất cả translations của ảnh
            val deletedTranslations = db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            Log.i(TAG, "deleteImageFromRoom: Deleted $deletedTranslations translations for imageId=$imageId")
            
            // Xóa tất cả image_blocks của ảnh
            val deletedBlocks = db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
            Log.i(TAG, "deleteImageFromRoom: Deleted $deletedBlocks image_blocks for imageId=$imageId")
            
            // Xóa change record nếu có
            try {
                db.delete(TABLE_CHANGE_IMAGES, "$COLUMN_CHANGE_IMAGE_ID = ?", arrayOf(imageId.toString()))
            } catch (e: Exception) { /* ignore */ }
            
            // Xóa pending translations nếu có
            try { deletePendingTranslations(imageId) } catch (e: Exception) { /* ignore */ }
            
            // Xóa record trong bảng images
            val deletedImages = db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            Log.i(TAG, "deleteImageFromRoom: Deleted $deletedImages image record for imageId=$imageId")
            
            db.setTransactionSuccessful()
            
            // Xóa file vật lý sau khi transaction thành công
            if (imageUri != null) {
                try {
                    val file = File(Uri.parse(imageUri).path ?: "")
                    if (file.exists()) {
                        val deleted = file.delete()
                        Log.i(TAG, "deleteImageFromRoom: Physical file deleted=$deleted for $imageUri")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "deleteImageFromRoom: Failed to delete physical file $imageUri", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting image from room: imageId=$imageId", e)
            throw e
        } finally {
            db.endTransaction()
        }
    }

    // Ensure a change_images record exists for an image (initially is_changed = 0)
    fun ensureChangeRecord(imageId: Long, roomId: Long) {
        val db = writableDatabase
        try {
            val cursor = db.rawQuery("SELECT $COLUMN_CHANGE_IMAGE_ID FROM $TABLE_CHANGE_IMAGES WHERE $COLUMN_CHANGE_IMAGE_IMAGE_ID = ?", arrayOf(imageId.toString()))
            val exists = cursor.moveToFirst()
            cursor.close()
            if (!exists) {
                val values = ContentValues().apply {
                    put(COLUMN_CHANGE_IMAGE_IMAGE_ID, imageId)
                    put(COLUMN_CHANGE_IMAGE_ROOM_ID, roomId)
                    put(COLUMN_CHANGE_IMAGE_FLAG, 0)
                }
                db.insert(TABLE_CHANGE_IMAGES, null, values)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureChangeRecord failed for imageId=$imageId", e)
        }
    }

    // Mark an image as changed (is_changed = 1). If record doesn't exist, create it.
    // Returns the current number of images with is_changed = 1 for the given room after marking.
    fun markImageChanged(imageId: Long, roomId: Long): Int {
        val db = writableDatabase
        try {
            val values = ContentValues().apply { put(COLUMN_CHANGE_IMAGE_FLAG, 1) }
            val updated = db.update(TABLE_CHANGE_IMAGES, values, "$COLUMN_CHANGE_IMAGE_IMAGE_ID = ?", arrayOf(imageId.toString())) ?: -1
            if (updated <= 0) {
                // Insert new record
                val ins = ContentValues().apply {
                    put(COLUMN_CHANGE_IMAGE_IMAGE_ID, imageId)
                    put(COLUMN_CHANGE_IMAGE_ROOM_ID, roomId)
                    put(COLUMN_CHANGE_IMAGE_FLAG, 1)
                }
                db.insert(TABLE_CHANGE_IMAGES, null, ins)
            }

            // Count how many images in this room are marked changed
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CHANGE_IMAGES WHERE $COLUMN_CHANGE_IMAGE_ROOM_ID = ? AND $COLUMN_CHANGE_IMAGE_FLAG = 1", arrayOf(roomId.toString()))
            var count = 0
            if (cursor.moveToFirst()) {
                try { count = cursor.getInt(0) } catch (e: Exception) { count = 0 }
            }
            cursor.close()
            return count
        } catch (e: Exception) {
            Log.w(TAG, "markImageChanged failed for imageId=$imageId", e)
        }
        return 0
    }

    // Clear change flag for an image (set to 0)
    fun clearImageChange(imageId: Long) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply { put(COLUMN_CHANGE_IMAGE_FLAG, 0) }
            db.update(TABLE_CHANGE_IMAGES, values, "$COLUMN_CHANGE_IMAGE_IMAGE_ID = ?", arrayOf(imageId.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "clearImageChange failed for imageId=$imageId", e)
        }
    }
    
    // Alias for clearImageChange with more descriptive name
    fun clearChangedFlagForImage(imageId: Long) = clearImageChange(imageId)

    /**
     * Clear all is_changed flags for all images in a room.
     * Called when loading a room to start fresh.
     */
    fun clearAllChangedFlagsForRoom(roomId: Long) {
        val db = writableDatabase
        try {
            db.execSQL(
                """
                UPDATE $TABLE_CHANGE_IMAGES 
                SET $COLUMN_CHANGE_IMAGE_FLAG = 0 
                WHERE $COLUMN_CHANGE_IMAGE_IMAGE_ID IN (
                    SELECT $COLUMN_IMAGE_ID 
                    FROM $TABLE_IMAGES 
                    WHERE $COLUMN_ROOM_ID = ?
                )
                """,
                arrayOf(roomId.toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "clearAllChangedFlagsForRoom failed for roomId=$roomId", e)
        }
    }

    /**
     * Đánh dấu tất cả bản dịch cũ của một imageId là pending_delete = 1
     * Được gọi khi bắt đầu retranslate một ảnh
     */
    fun markTranslationsAsPendingDelete(imageId: Long) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply { put("pending_delete", 1) }
            val rowsUpdated = db.update("translations", values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            
        } catch (e: Exception) {
            Log.w(TAG, "markTranslationsAsPendingDelete failed for imageId=$imageId", e)
        }
    }

    /**
     * Xóa tất cả bản dịch pending_delete của một imageId
     * Được gọi sau khi lưu bản dịch mới thành công
     */
    fun deletePendingTranslations(imageId: Long): Int {
        val db = writableDatabase
        try {
            val rowsDeleted = db.delete("translations", "$COLUMN_IMAGE_ID = ? AND pending_delete = 1", arrayOf(imageId.toString()))
            
            return rowsDeleted
        } catch (e: Exception) {
            Log.w(TAG, "deletePendingTranslations failed for imageId=$imageId", e)
            return 0
        }
    }

    /**
     * Hủy trạng thái pending_delete cho tất cả bản dịch của một imageId
     * Được gọi khi retranslate thất bại hoặc bị hủy
     */
    fun clearPendingDeleteStatus(imageId: Long) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply { put("pending_delete", 0) }
            val rowsUpdated = db.update("translations", values, "$COLUMN_IMAGE_ID = ? AND pending_delete = 1", arrayOf(imageId.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "clearPendingDeleteStatus failed for imageId=$imageId", e)
        }
    }

    fun clearPendingDeleteStatusForRoom(roomId: Long) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply { put("pending_delete", 0) }
            val rowsUpdated = db.update("translations", values, "$COLUMN_IMAGE_ID IN (SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?) AND pending_delete = 1", arrayOf(roomId.toString()))
            Log.i(TAG, "Cleared pending_delete for $rowsUpdated translations in room $roomId")
        } catch (e: Exception) {
            Log.w(TAG, "clearPendingDeleteStatusForRoom failed for roomId=$roomId", e)
        }
    }

    // Get list of image IDs with is_changed = 1 for a specific room
    fun getChangedImageIdsForRoom(roomId: Long): List<Long> {
        val db = readableDatabase
        val result = mutableListOf<Long>()
        try {
            val cursor = db.rawQuery("SELECT $COLUMN_CHANGE_IMAGE_IMAGE_ID FROM $TABLE_CHANGE_IMAGES WHERE $COLUMN_CHANGE_IMAGE_ROOM_ID = ? AND $COLUMN_CHANGE_IMAGE_FLAG = 1", arrayOf(roomId.toString()))
            while (cursor.moveToNext()) {
                result.add(cursor.getLong(0))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.w(TAG, "getChangedImageIdsForRoom failed for roomId=$roomId", e)
        }
        return result
    }

    /**
     * Apply pending changes for all images in a room where is_changed = 1.
     * For each changed imageId: re-save translations and image_blocks from provided translatedTexts map if present,
     * or leave as-is if no translated data is provided.
     *
     * @param clearChangedFlag If true, clears is_changed=0 after saving (for manual save).
     *                         If false, keeps the flag (for auto-save, so user can manually save later).
     */
    fun applyPendingChangesForRoom(
        roomId: Long, 
        translatedByImageId: Map<Long, Pair<String, List<TextBlockInfo>>>,
        clearChangedFlag: Boolean = true
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Only process imageIds that are in the mapping, not all changed images
            val imageIdsToProcess = translatedByImageId.keys
            
            for (imageId in imageIdsToProcess) {
                // delete old translations and blocks (including pending_delete ones)
                val deletedCount = db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                
                try { deleteBlocksForImage(imageId) } catch (e: Exception) { /* ignore */ }

                // insert new translations if available
                translatedByImageId[imageId]?.let { (originalText, textBlocks) ->
                    // original_text giờ chỉ lưu trong bảng translations (per-block), không cần update images

                    // Filter out blocks marked for deletion (pendingDelete = true)
                    val blocksToSave = textBlocks.filter { !it.pendingDelete }
                    Log.i(TAG, "applyPendingChangesForRoom: imageId=$imageId totalBlocks=${textBlocks.size} blocksToSave=${blocksToSave.size}")
                    
                    // Persist each translation + image_block
                    blocksToSave.forEach { textBlock ->
                        val bounds = textBlock.bounds
                        // Lưu translated_text VÀ original_text (per-block)
                        val textValues = ContentValues().apply {
                            put(COLUMN_IMAGE_ID, imageId)
                            put("translated_text", textBlock.text)
                            val safeOrigText = textBlock.originalText?.trim()
                            val finalOrigToSave = if (safeOrigText == "[]") "" else safeOrigText ?: ""
                            put("original_text", finalOrigToSave)
                            put("x", bounds.left)
                            put("y", bounds.top)
                            put("width", bounds.width())
                            put("height", bounds.height())
                        }
                            val inserted = db.insert("translations", null, textValues)
                            if (inserted != -1L) {
                            try {
                                val blockWidth = bounds.right - bounds.left
                                val blockHeight = bounds.bottom - bounds.top
                                insertImageBlock(
                                    imageId = imageId,
                                    x = bounds.left,
                                    y = bounds.top,
                                    width = blockWidth,
                                    height = blockHeight,
                                    overlayType = textBlock.shapeType,
                                    overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor,
                                    overlayBrightness = 1.0f,
                                    overlayAlpha = textBlock.overlayAlpha,
                                    overlaySaturation = textBlock.overlaySaturation,
                                    overlayInset = textBlock.overlayInset,
                                    overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                    overlayInsetVertical = textBlock.overlayInsetVertical,
                                    overlayRotation = textBlock.overlayRotation,
                                    textColor = textBlock.customTextColor ?: textBlock.originalTextColor,
                                    textBrightness = 1.0f,
                                    textBoldness = textBlock.textBoldness,
                                    textSaturation = textBlock.textSaturation,
                                    borderColor = textBlock.customBorderColor,
                                    borderBrightness = 1.0f,
                                    borderBoldness = textBlock.borderAlpha,
                                    borderThickness = textBlock.borderThickness,
                                    // Persist shadow properties if present on the TextBlockInfo
                                    shadowColor = textBlock.customShadowColor,
                                    shadowAlpha = textBlock.shadowAlpha ?: 1.0f,
                                    shadowRadius = textBlock.shadowRadius ?: 0f,
                                    rotation = textBlock.rotation ?: 0f,
                                    fontFamily = textBlock.fontFamily,
                                    fontSize = textBlock.fontSize,
                                    // ✅ Truyền lineSpacing từ TextBlockInfo
                                    lineSpacing = textBlock.lineSpacing,
                                    textAlign = textBlock.textAlign.name,
                                    textGradientColors = textBlock.textGradientColors,
                                    textGradientOffsets = textBlock.textGradientOffsets,
                                    textGradientType = textBlock.textGradientType,
                                    originalWidth = textBlock.originalImageWidth,
                                    originalHeight = textBlock.originalImageHeight
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Không thể lưu image_block cho image $imageId khi applyPendingChanges", e)
                            }
                        }
                    }

                    // Set image-level is_translated flag
                    // Luôn đặt là 1 khi đã qua xử lý (kể cả 0 blocks) để tránh quét lại vô tận
                    try {
                        val isTranslatedValue = 1
                        val isTranslatedValues = ContentValues().apply { put(COLUMN_IS_TRANSLATED, isTranslatedValue) }
                        db.update(TABLE_IMAGES, isTranslatedValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        Log.i(TAG, "applyPendingChangesForRoom: Updated is_translated=1 (processed) for imageId=$imageId")
                    } catch (e: Exception) {
                        Log.w(TAG, "applyPendingChangesForRoom: Failed to update is_translated for imageId=$imageId", e)
                    }
                }

                // clear change flag only if requested (manual save)
                // For auto-save, keep the flag so user can still see changes and manually save
                if (clearChangedFlag) {
                    clearImageChange(imageId)
                }
            }
            db.setTransactionSuccessful()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "applyPendingChangesForRoom failed for roomId=$roomId", e)
            return false
        } finally {
            db.endTransaction()
        }
    }

    fun saveMangaRoom(imageUris: List<Uri>, translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>, title: String? = null, translatedStatus: Map<Uri, Boolean>? = null): Long {
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
                val roomValues = ContentValues().apply {
                    put(COLUMN_COVER_URI, coverUri.toString())
                }
                db.update(TABLE_ROOMS, roomValues, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
                deleteOriginalImage(imageUris.first()) // Xóa ảnh gốc của cover
            } else {
                Log.e(TAG, "Failed to copy cover image for room $roomId")
            }

            imageUris.forEachIndexed { index, originalUri ->
                val fileName = "image_$index.webp"
                val newFile = copyImageToInternalStorage(originalUri, imagesDir, fileName)
                if (newFile != null) {
                    val newUri = Uri.fromFile(newFile)
                    // Get original OCR text for this image (first element of Pair)
                    val originalOcrText = translatedTexts[originalUri]?.first ?: ""
                    val imageValues = ContentValues().apply {
                        put(COLUMN_ROOM_ID, roomId)
                        put(COLUMN_IMAGE_URI, newUri.toString())
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, if (translatedStatus?.get(originalUri) == true || translatedTexts.containsKey(originalUri)) 1 else 0)
                        // original_text giờ lưu trong bảng translations (per-block), không cần ở images
                    }
                    val imageId = db.insert(TABLE_IMAGES, null, imageValues)
                    if (imageId == -1L) {
                        Log.e(TAG, "Failed to insert image $newUri at index $index for room $roomId")
                    } else {
                        deleteOriginalImage(originalUri) // Xóa ảnh gốc sau khi lưu
                        // Ensure change record exists for this image (default is_changed = 0)
                        try { ensureChangeRecord(imageId, roomId) } catch (e: Exception) { /* ignore */ }
                    }

                    // Tự động scale lại bounds nếu ảnh đã bị resize
                    translatedTexts[originalUri]?.let { (originalText, textBlocks) ->
                        // IMPORTANT: Delete ALL existing translations for this image to prevent duplicates
                        val deletedCount = db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        try { deleteBlocksForImage(imageId) } catch (e: Exception) { /* ignore */ }
                        
                        // Filter out blocks marked for deletion (pendingDelete = true)
                        val blocksToSave = textBlocks.filter { !it.pendingDelete }
                        Log.i(TAG, "saveMangaRoom: imageId=$imageId totalBlocks=${textBlocks.size} blocksToSave=${blocksToSave.size}")
                        
                        // Lấy kích thước gốc từ textBlock đầu tiên (nếu có)
                        val originalWidth = blocksToSave.firstOrNull()?.originalImageWidth
                        val originalHeight = blocksToSave.firstOrNull()?.originalImageHeight
                        // Lấy kích thước ảnh đã lưu
                        val savedBitmap = android.graphics.BitmapFactory.decodeFile(newFile.absolutePath)
                        val savedWidth = savedBitmap?.width
                        val savedHeight = savedBitmap?.height
                        val scaleX = if (originalWidth != null && savedWidth != null && originalWidth > 0) savedWidth.toFloat() / originalWidth else 1f
                        val scaleY = if (originalHeight != null && savedHeight != null && originalHeight > 0) savedHeight.toFloat() / originalHeight else 1f
                        var insertedCount = 0
                        blocksToSave.forEach { textBlock ->
                            val origRect = textBlock.bounds
                            val scaledRect = if (scaleX != 1f || scaleY != 1f) {
                                android.graphics.Rect(
                                    (origRect.left * scaleX).toInt(),
                                    (origRect.top * scaleY).toInt(),
                                    (origRect.right * scaleX).toInt(),
                                    (origRect.bottom * scaleY).toInt()
                                )
                            } else origRect
                            // Lưu translated_text VÀ original_text (per-block)
                            val textValues = ContentValues().apply {
                                put(COLUMN_IMAGE_ID, imageId)
                                put("translated_text", textBlock.text)
                                val safeOrigText = textBlock.originalText?.trim()
                                val finalOrigToSave = if (safeOrigText == "[]") "" else safeOrigText ?: ""
                                put("original_text", finalOrigToSave)
                                put("x", scaledRect.left)
                                put("y", scaledRect.top)
                                put("width", scaledRect.width())
                                put("height", scaledRect.height())
                            }
                            val textId = db.insert("translations", null, textValues)
                            if (textId == -1L) {
                                Log.e(TAG, "Failed to insert translation for image $imageId")
                            } else {
                                insertedCount++
                                // Also save equivalent block to image_blocks so styling persists independently
                                try {
                                    val blockWidth = scaledRect.right - scaledRect.left
                                    val blockHeight = scaledRect.bottom - scaledRect.top
                                    val overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor
                                    val textColor = textBlock.customTextColor ?: textBlock.originalTextColor
                                    
                                    // After inserting translations for this image, ensure change flag cleared (applied)
                                    try { clearImageChange(imageId) } catch (e: Exception) { /* ignore */ }
                                    // Delete pending translations after successful save
                                    try { deletePendingTranslations(imageId) } catch (e: Exception) { /* ignore */ }
                                        insertImageBlock(
                                            imageId = imageId,
                                            x = scaledRect.left,
                                            y = scaledRect.top,
                                            width = blockWidth,
                                            height = blockHeight,
                                            overlayType = textBlock.shapeType,
                                            overlayColor = overlayColor,
                                            overlayBrightness = 1.0f,
                                            overlayAlpha = textBlock.overlayAlpha,
                                            overlaySaturation = textBlock.overlaySaturation,
                                            overlayInset = textBlock.overlayInset,
                                            overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                            overlayInsetVertical = textBlock.overlayInsetVertical,
                                            overlayRotation = textBlock.overlayRotation,
                                            textColor = textColor,
                                            textBrightness = 1.0f,
                                            textBoldness = textBlock.textBoldness,
                                            textSaturation = textBlock.textSaturation,
                                            borderColor = textBlock.customBorderColor,
                                            borderBrightness = 1.0f,
                                            borderBoldness = textBlock.borderAlpha,
                                            borderThickness = textBlock.borderThickness,
                                            // Persist shadow properties if present on the TextBlockInfo
                                            shadowColor = textBlock.customShadowColor,
                                            shadowAlpha = textBlock.shadowAlpha ?: 1.0f,
                                            shadowRadius = textBlock.shadowRadius ?: 0f,
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
                                } catch (e: Exception) {
                                    Log.w(TAG, "Không thể lưu image_block cho image $imageId", e)
                                }
                            }
                        }
                        savedBitmap?.recycle()
                    }
                } else {
                    Log.e(TAG, "Failed to copy image $originalUri to internal storage")
                }
            }

            // Sau khi copy ảnh vào thư mục mới, xóa ảnh gốc
            imageUris.forEach { uri ->
                try {
                    deleteOriginalImage(uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Không thể xóa ảnh gốc: $uri", e)
                }
            }
            db.setTransactionSuccessful()
            
            // Initialize room settings with default auto-translate enabled
            try {
                setAutoTranslateSetting(roomId, true)
                // Initialize ancient translation setting as disabled by default
                try { setAncientTranslationSetting(roomId, false) } catch (e: Exception) { /* ignore */ }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize room settings for roomId=$roomId", e)
            }
            
            // Clean up duplicate image_id entries after save
            cleanupDuplicateImages(roomId)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving manga room", e)
            return -1L
        } finally {
            db.endTransaction()
        }
        return roomId
    }

    /**
     * Cập nhật lại ảnh và bản dịch cho phòng đã có roomId, chỉ thay đổi những gì khác biệt
     */
    fun updateMangaRoom(roomId: Long, imageUris: List<Uri>, translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>, translatedStatus: Map<Uri, Boolean>? = null): Boolean {
        if (imageUris.isEmpty()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Lấy danh sách ảnh cũ trong DB
            val oldImages = mutableListOf<Pair<Long, Uri>>() // Pair<imageId, uri>
            val imageIdMap = mutableMapOf<String, Long>() // uri.toString() -> imageId
            val cursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI, $COLUMN_DISPLAY_ORDER FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? ORDER BY $COLUMN_DISPLAY_ORDER", arrayOf(roomId.toString()))
            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val uri = Uri.parse(cursor.getString(1))
                oldImages.add(imageId to uri)
                imageIdMap[uri.toString()] = imageId
            }
            cursor.close()

            // Helper function to extract filename
            fun lastNameOf(uri: Uri?): String? {
                return try {
                    uri?.lastPathSegment ?: java.io.File(uri.toString()).name
                } catch (e: Exception) { null }
            }
            
            // Helper function to extract image number from filename (e.g., "image_5.jpg" -> 5)
            fun extractImageNumber(uri: Uri?): Int? {
                val filename = lastNameOf(uri) ?: return null
                val match = Regex("image_(\\d+)\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE).find(filename)
                return match?.groupValues?.get(1)?.toIntOrNull()
            }

            // Xóa ảnh đã bị loại khỏi danh sách mới
            oldImages.forEach { (imageId, uri) ->
                val exactPresent = imageUris.any { it.toString() == uri.toString() }
                val name = lastNameOf(uri)
                val namePresent = if (name != null) imageUris.any { newUri ->
                    val newName = lastNameOf(newUri)
                    newName != null && (newName == name || newUri.toString().endsWith(name))
                } else false
                if (!exactPresent && !namePresent) {
                    Log.i(TAG, "updateMangaRoom: Deleting removed image imageId=$imageId uri=$uri")
                    db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    // Xóa file vật lý
                    val file = File(Uri.parse(uri.toString()).path ?: "")
                    if (file.exists()) file.delete()
                }
            }

            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            imagesDir.mkdirs()
            
            // Build map of old filenames to imageId
            val oldFilenameToImageId = mutableMapOf<String, Long>()
            val oldImageIdToFilename = mutableMapOf<Long, String>()
            oldImages.forEach { (imageId, oldUri) ->
                val filename = lastNameOf(oldUri)
                if (filename != null) {
                    oldFilenameToImageId[filename] = imageId
                    oldImageIdToFilename[imageId] = filename
                }
            }
            
            // Xác định ảnh mới và ảnh cũ
            data class ImageInfo(val index: Int, val uri: Uri, val isNew: Boolean, val existingImageId: Long?)
            val imageInfos = mutableListOf<ImageInfo>()
            
            imageUris.forEachIndexed { index, uri ->
                val uriStr = uri.toString()
                val uriFilename = lastNameOf(uri)
                
                // Check if this URI matches an existing image
                val exactMatch = imageIdMap[uriStr]
                val filenameMatch = if (uriFilename != null) oldFilenameToImageId[uriFilename] else null
                val existingImageId = exactMatch ?: filenameMatch
                
                val isNew = existingImageId == null
                imageInfos.add(ImageInfo(index, uri, isNew, existingImageId))
                
                if (isNew) {
                    Log.i(TAG, "updateMangaRoom: NEW image detected at index $index, uri=$uriStr")
                } else {
                    Log.i(TAG, "updateMangaRoom: EXISTING image at index $index, imageId=$existingImageId, uri=$uriStr")
                }
            }
            
            // ====== RENAME LOGIC: Đổi tên file để đảm bảo thứ tự đúng ======
            // Step 1: Rename all existing files to temp names to avoid conflicts
            val tempRenames = mutableMapOf<Long, File>() // imageId -> tempFile
            imageInfos.filter { !it.isNew && it.existingImageId != null }.forEach { info ->
                val imageId = info.existingImageId!!
                val oldFilename = oldImageIdToFilename[imageId]
                if (oldFilename != null) {
                    val oldFile = File(imagesDir, oldFilename)
                    if (oldFile.exists()) {
                        val tempFile = File(imagesDir, "temp_${imageId}_$oldFilename")
                        if (oldFile.renameTo(tempFile)) {
                            tempRenames[imageId] = tempFile
                        }
                    }
                }
            }
            
            // Step 2: Rename temp files and new files to final names based on new index
            imageInfos.forEach { info ->
                val targetFilename = "image_${info.index}.webp"
                val targetFile = File(imagesDir, targetFilename)
                
                if (info.isNew) {
                    // Copy new image to target location
                    val copied = copyImageToInternalStorage(info.uri, imagesDir, targetFilename)
                    if (copied != null) {
                        Log.i(TAG, "updateMangaRoom: Copied new image to $targetFilename")
                    } else {
                        Log.e(TAG, "updateMangaRoom: Failed to copy new image ${info.uri} to $targetFilename")
                    }
                } else {
                    // Rename from temp to final
                    val imageId = info.existingImageId!!
                    val tempFile = tempRenames[imageId]
                    if (tempFile != null && tempFile.exists()) {
                        // Delete target if exists (shouldn't happen but just in case)
                        if (targetFile.exists()) targetFile.delete()
                        if (tempFile.renameTo(targetFile)) {
                            Log.i(TAG, "updateMangaRoom: Renamed ${tempFile.name} to $targetFilename for imageId=$imageId")
                        } else {
                            Log.e(TAG, "updateMangaRoom: Failed to rename ${tempFile.name} to $targetFilename")
                        }
                    }
                }
            }
            
            // ====== DATABASE UPDATE ======
            imageInfos.forEach { info ->
                val index = info.index
                val uri = info.uri
                val isTranslated = if (translatedStatus?.get(uri) == true || translatedTexts.containsKey(uri)) 1 else 0
                val targetFilename = "image_${index}.webp"
                val targetFile = File(imagesDir, targetFilename)
                val newUri = if (targetFile.exists()) Uri.fromFile(targetFile) else uri
                
                if (info.isNew) {
                    // Insert new image
                    val imageValues = ContentValues().apply {
                        put(COLUMN_ROOM_ID, roomId)
                        put(COLUMN_IMAGE_URI, newUri.toString())
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, isTranslated)
                        // original_text giờ lưu trong bảng translations (per-block)
                    }
                    val imageId = db.insert(TABLE_IMAGES, null, imageValues)
                    if (imageId != -1L) {
                        Log.i(TAG, "updateMangaRoom: Inserted new image with imageId=$imageId at index $index")
                        try { ensureChangeRecord(imageId, roomId) } catch (e: Exception) { /* ignore */ }

                        // Insert translations if any
                        translatedTexts[uri]?.let { (originalText, textBlocks) ->
                            // original_text giờ lưu trong bảng translations (per-block), không cần update images

                            // Filter out blocks marked for deletion
                            val blocksToSave = textBlocks.filter { !it.pendingDelete }
                            Log.i(TAG, "updateMangaRoom (new image): totalBlocks=${textBlocks.size} blocksToSave=${blocksToSave.size}")
                            
                            blocksToSave.forEach { textBlock ->
                                val origRect = textBlock.bounds
                                // Lưu translated_text VÀ original_text (per-block)
                                val textValues = ContentValues().apply {
                                    put(COLUMN_IMAGE_ID, imageId)
                                    put("translated_text", textBlock.text)
                                    val safeOrigText = textBlock.originalText?.trim()
                                    val finalOrigToSave = if (safeOrigText == "[]") "" else safeOrigText ?: ""
                                    put("original_text", finalOrigToSave)
                                    put("x", origRect.left)
                                    put("y", origRect.top)
                                    put("width", origRect.width())
                                    put("height", origRect.height())
                                }
                                val inserted = db.insert("translations", null, textValues)
                                if (inserted != -1L) {
                                    try {
                                        val blockWidth = origRect.right - origRect.left
                                        val blockHeight = origRect.bottom - origRect.top
                                        val overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor
                                        val textColor = textBlock.customTextColor ?: (textBlock.originalTextColor ?: 0xFF000000.toInt())
                                        insertImageBlock(
                                            imageId = imageId,
                                            x = origRect.left,
                                            y = origRect.top,
                                            width = blockWidth,
                                            height = blockHeight,
                                            overlayType = textBlock.shapeType,
                                            overlayColor = overlayColor,
                                            overlayBrightness = 1.0f,
                                            overlayAlpha = textBlock.overlayAlpha,
                                            overlaySaturation = textBlock.overlaySaturation,
                                            overlayInset = textBlock.overlayInset,
                                            overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                            overlayInsetVertical = textBlock.overlayInsetVertical,
                                            overlayRotation = textBlock.overlayRotation,
                                            textColor = textColor,
                                            textBrightness = 1.0f,
                                            textBoldness = textBlock.textBoldness,
                                            textSaturation = textBlock.textSaturation,
                                            borderColor = textBlock.customBorderColor,
                                            borderBrightness = 1.0f,
                                            borderBoldness = textBlock.borderAlpha,
                                            borderThickness = textBlock.borderThickness,
                                            shadowColor = textBlock.customShadowColor,
                                            shadowAlpha = textBlock.shadowAlpha ?: 1.0f,
                                            shadowRadius = textBlock.shadowRadius ?: 0f,
                                            rotation = textBlock.rotation ?: 0f,
                                            fontFamily = textBlock.fontFamily,
                                            fontSize = textBlock.fontSize,
                                            lineSpacing = textBlock.lineSpacing,
                                            textAlign = textBlock.textAlign.name,
                                            textGradientColors = textBlock.textGradientColors,
                                            textGradientOffsets = textBlock.textGradientOffsets,
                                            textGradientType = textBlock.textGradientType
                                        )
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Không thể lưu image_block cho image $imageId", e)
                                    }
                                }
                            }
                            // Clear change flag after saving
                            try { clearImageChange(imageId) } catch (e: Exception) { /* ignore */ }
                            try { deletePendingTranslations(imageId) } catch (e: Exception) { /* ignore */ }
                        }
                    }
                } else {
                    // Ảnh cũ: cập nhật thứ tự, trạng thái dịch, và URI mới (sau khi rename)
                    val imageId = info.existingImageId!!
                    
                    Log.i(TAG, "updateMangaRoom: EXISTING image at index $index, imageId=$imageId")
                    
                    // Update display_order, is_translated, AND the new URI (file was renamed)
                    val imageValues = ContentValues().apply {
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, isTranslated)
                        put(COLUMN_IMAGE_URI, newUri.toString()) // Update to new URI after rename
                    }
                    db.update(TABLE_IMAGES, imageValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    
                    // Nếu có bản dịch mới, xóa bản dịch cũ và thêm lại
                    // IMPORTANT: Only update translations if this URI has translations in the input map
                    // Try to find translations by exact URI match first, then by filename match
                    val uriFilename = lastNameOf(uri)
                    val translationEntry = translatedTexts[uri] ?: translatedTexts.entries.find { (k, _) ->
                        val kFilename = lastNameOf(k)
                        kFilename != null && uriFilename != null && kFilename == uriFilename
                    }?.value
                    
                    if (translationEntry != null) {
                        val (originalText, textBlocks) = translationEntry
                        val resolvedId = imageId
                        val deletedCount = db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(resolvedId.toString()))

                        // original_text giờ lưu trong bảng translations (per-block), không cần update images

                        try { deleteBlocksForImage(resolvedId) } catch (e: Exception) { /* ignore */ }
                        // DO NOT scale blocks - keep original coordinates relative to originalImageWidth/Height
                        // ImageViewer handles scaling at display time
                        
                        // Filter out blocks marked for deletion (pendingDelete = true)
                        val blocksToSave = textBlocks.filter { !it.pendingDelete }
                        Log.i(TAG, "updateMangaRoom (existing image): totalBlocks=${textBlocks.size} blocksToSave=${blocksToSave.size}")

                        var insertedCount = 0
                        blocksToSave.forEach { textBlock ->
                            val origRect = textBlock.bounds
                            // Use original bounds without scaling
                            val finalRect = origRect
                            // Lưu translated_text VÀ original_text (per-block)
                            val textValues = ContentValues().apply {
                                put(COLUMN_IMAGE_ID, resolvedId)
                                put("translated_text", textBlock.text)
                                val safeOrigText = textBlock.originalText?.trim()
                                val finalOrigToSave = if (safeOrigText == "[]") "" else safeOrigText ?: ""
                                put("original_text", finalOrigToSave)
                                put("x", finalRect.left)
                                put("y", finalRect.top)
                                put("width", finalRect.width())
                                put("height", finalRect.height())
                            }
                            val inserted = db.insert("translations", null, textValues)
                            if (inserted != -1L) {
                                insertedCount++
                                try {
                                    val blockWidth = finalRect.right - finalRect.left
                                    val blockHeight = finalRect.bottom - finalRect.top
                                    val overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor
                                    val textColor = textBlock.customTextColor ?: (textBlock.originalTextColor ?: 0xFF000000.toInt())
                                    insertImageBlock(
                                        imageId = resolvedId,
                                            x = finalRect.left,
                                            y = finalRect.top,
                                            width = blockWidth,
                                            height = blockHeight,
                                            overlayType = textBlock.shapeType,
                                            overlayColor = overlayColor,
                                            overlayBrightness = 1.0f,
                                            overlayAlpha = textBlock.overlayAlpha,
                                            overlaySaturation = textBlock.overlaySaturation,
                                            overlayInset = textBlock.overlayInset,
                                            overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                            overlayInsetVertical = textBlock.overlayInsetVertical,
                                            overlayRotation = textBlock.overlayRotation,
                                            textColor = textColor,
                                            textBrightness = 1.0f,
                                            textBoldness = textBlock.textBoldness,
                                            textSaturation = textBlock.textSaturation,
                                            borderColor = textBlock.customBorderColor,
                                            borderBrightness = 1.0f,
                                            borderBoldness = textBlock.borderAlpha,
                                            borderThickness = textBlock.borderThickness,
                                            shadowColor = textBlock.customShadowColor,
                                            shadowAlpha = textBlock.shadowAlpha ?: 1.0f,
                                            shadowRadius = textBlock.shadowRadius ?: 0f,
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
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Không thể lưu image_block cho image $imageId", e)
                                    }
                                }
                            }
                            // Clear change flag after saving
                            try { clearImageChange(resolvedId) } catch (e: Exception) { /* ignore */ }
                            try { deletePendingTranslations(resolvedId) } catch (e: Exception) { /* ignore */ }
                    }
                }
            }
            // Cập nhật cover_uri nếu ảnh đầu tiên thay đổi
            if (imageUris.isNotEmpty()) {
                val coverUri = imageUris.first().toString()
                val roomValues = ContentValues().apply { put(COLUMN_COVER_URI, coverUri) }
                db.update(TABLE_ROOMS, roomValues, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
            }
            db.setTransactionSuccessful()
            
            // Clean up duplicate image_id entries after update
            cleanupDuplicateImages(roomId)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi cập nhật phòng $roomId", e)
            return false
        } finally {
            db.endTransaction()
        }
    }
    
    /**
     * Remove duplicate image entries in a room, keeping only the first occurrence
     * of each image_uri and deleting all subsequent duplicates.
     */
    private fun cleanupDuplicateImages(roomId: Long) {
        val db = writableDatabase
        try {
            // Find all images in this room with translation counts
            // Order by display_order first, then by translation_count DESC (keep the one with more translations)
            // then by image_id ASC (keep the older one if same translations)
            val cursor = db.rawQuery(
                """
                SELECT i.$COLUMN_IMAGE_ID, i.$COLUMN_IMAGE_URI, i.$COLUMN_DISPLAY_ORDER,
                       (SELECT COUNT(*) FROM translations t WHERE t.$COLUMN_IMAGE_ID = i.$COLUMN_IMAGE_ID) as translation_count
                FROM $TABLE_IMAGES i
                WHERE i.$COLUMN_ROOM_ID = ? 
                ORDER BY i.$COLUMN_DISPLAY_ORDER ASC, translation_count DESC, i.$COLUMN_IMAGE_ID ASC
                """,
                arrayOf(roomId.toString())
            )
            
            // Extract filename from URI for comparison
            fun extractFilename(uri: String): String {
                return try {
                    Uri.parse(uri).lastPathSegment ?: java.io.File(uri).name
                } catch (e: Exception) {
                    uri
                }
            }
            
            val seenFilenames = mutableSetOf<String>() // Track by filename to catch URI format differences
            val seenDisplayOrders = mutableMapOf<Int, Long>() // display_order -> kept imageId
            val duplicateImageIds = mutableListOf<Long>()
            
            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val imageUri = cursor.getString(1)
                val displayOrder = cursor.getInt(2)
                val translationCount = cursor.getInt(3)
                val filename = extractFilename(imageUri)
                
                // Check for duplicate filename (catches same file with different URI formats)
                val isDuplicateFilename = seenFilenames.contains(filename)
                // Check for duplicate display_order
                val existingIdAtOrder = seenDisplayOrders[displayOrder]
                val isDuplicateOrder = existingIdAtOrder != null
                
                if (isDuplicateFilename) {
                    // Duplicate filename - mark as duplicate
                    duplicateImageIds.add(imageId)
                    Log.w(TAG, "cleanupDuplicateImages: Marking imageId=$imageId as duplicate (duplicate filename: $filename, uri=$imageUri)")
                } else if (isDuplicateOrder) {
                    // Duplicate display_order - the first one we saw (with more translations due to ORDER BY) is kept
                    duplicateImageIds.add(imageId)
                    Log.w(TAG, "cleanupDuplicateImages: Marking imageId=$imageId as duplicate (duplicate order: $displayOrder, keeping imageId=${existingIdAtOrder}, this has $translationCount translations)")
                } else {
                    seenFilenames.add(filename)
                    seenDisplayOrders[displayOrder] = imageId
                }
            }
            cursor.close()
            
            // Delete all duplicate entries
            if (duplicateImageIds.isNotEmpty()) {
                Log.w(TAG, "cleanupDuplicateImages: Found ${duplicateImageIds.size} duplicate images in room $roomId, deleting...")
                duplicateImageIds.forEach { imageId ->
                    Log.w(TAG, "cleanupDuplicateImages: Deleting duplicate imageId=$imageId")
                    // Delete translations for this duplicate
                    db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    // Delete image_blocks for this duplicate
                    db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    // Delete the image record itself
                    db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                }
                Log.i(TAG, "cleanupDuplicateImages: Deleted ${duplicateImageIds.size} duplicate images from room $roomId")
            } else {
                Log.i(TAG, "cleanupDuplicateImages: No duplicate images found in room $roomId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up duplicate images for room $roomId", e)
        }
    }
    
    /**
     * Strict cleanup: Xóa tất cả ảnh duplicate trong room trước khi load.
     * Đảm bảo:
     * 1. Không có 2 image_id nào dùng chung 1 URI
     * 2. Không có 2 image_id nào dùng chung 1 filename
     * 3. Không có image_id nào xuất hiện 2 lần
     */
    private fun cleanupDuplicateImagesStrict(roomId: Long) {
        val db = writableDatabase
        try {
            Log.i(TAG, "cleanupDuplicateImagesStrict: Starting strict cleanup for room $roomId")
            
            // First, run normal cleanup
            cleanupDuplicateImages(roomId)
            
            // Second, check for any remaining duplicates by filename
            val cursor = db.rawQuery(
                """
                SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI, $COLUMN_DISPLAY_ORDER
                FROM $TABLE_IMAGES
                WHERE $COLUMN_ROOM_ID = ?
                ORDER BY $COLUMN_DISPLAY_ORDER ASC, $COLUMN_IMAGE_ID ASC
                """,
                arrayOf(roomId.toString())
            )
            
            val seenFilenames = mutableMapOf<String, Long>() // filename -> kept imageId
            val duplicatesToDelete = mutableListOf<Long>()
            
            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val imageUri = cursor.getString(1)
                val filename = try {
                    Uri.parse(imageUri).lastPathSegment ?: imageUri
                } catch (e: Exception) { imageUri }
                
                val existingId = seenFilenames[filename]
                if (existingId != null && existingId != imageId) {
                    // Found duplicate filename with different image_id
                    duplicatesToDelete.add(imageId)
                    Log.w(TAG, "cleanupDuplicateImagesStrict: Found duplicate filename=$filename, keeping imageId=$existingId, deleting imageId=$imageId")
                } else if (existingId == null) {
                    seenFilenames[filename] = imageId
                }
            }
            cursor.close()
            
            // Delete duplicates
            if (duplicatesToDelete.isNotEmpty()) {
                Log.w(TAG, "cleanupDuplicateImagesStrict: Deleting ${duplicatesToDelete.size} strict duplicates")
                duplicatesToDelete.forEach { imageId ->
                    db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                }
            }
            
            // Third, re-index display_order to be sequential (0, 1, 2, ...)
            val remainingCursor = db.rawQuery(
                """
                SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES
                WHERE $COLUMN_ROOM_ID = ?
                ORDER BY $COLUMN_DISPLAY_ORDER ASC, $COLUMN_IMAGE_ID ASC
                """,
                arrayOf(roomId.toString())
            )
            var newOrder = 0
            while (remainingCursor.moveToNext()) {
                val imageId = remainingCursor.getLong(0)
                val values = ContentValues().apply {
                    put(COLUMN_DISPLAY_ORDER, newOrder)
                }
                db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                newOrder++
            }
            remainingCursor.close()
            
            Log.i(TAG, "cleanupDuplicateImagesStrict: Completed, room $roomId now has $newOrder images")
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanupDuplicateImagesStrict for room $roomId", e)
        }
    }
    
    /**
     * Cleanup and sync room images to ensure DB count matches expected count.
     * Call this when loading a room to fix any inconsistencies.
     */
    fun cleanupAndSyncRoomImages(roomId: Long, expectedCount: Int) {
        val db = writableDatabase
        try {
            // First run normal duplicate cleanup
            cleanupDuplicateImages(roomId)
            
            // Count images after cleanup
            val countCursor = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?",
                arrayOf(roomId.toString())
            )
            val actualCount = if (countCursor.moveToFirst()) countCursor.getInt(0) else 0
            countCursor.close()
            
            Log.i(TAG, "cleanupAndSyncRoomImages: Room $roomId has $actualCount images in DB, expected $expectedCount")
            
            if (actualCount > expectedCount) {
                // More images in DB than expected - remove extras
                // Keep only images with display_order < expectedCount
                val extraCursor = db.rawQuery(
                    """
                    SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES 
                    WHERE $COLUMN_ROOM_ID = ? AND $COLUMN_DISPLAY_ORDER >= ?
                    ORDER BY $COLUMN_DISPLAY_ORDER DESC
                    """,
                    arrayOf(roomId.toString(), expectedCount.toString())
                )
                val extraIds = mutableListOf<Long>()
                while (extraCursor.moveToNext()) {
                    extraIds.add(extraCursor.getLong(0))
                }
                extraCursor.close()
                
                if (extraIds.isNotEmpty()) {
                    Log.w(TAG, "cleanupAndSyncRoomImages: Removing ${extraIds.size} extra images with display_order >= $expectedCount")
                    extraIds.forEach { imageId ->
                        db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanupAndSyncRoomImages for room $roomId", e)
        }
    }

    /**
     * Update only a subset of images (by their URIs) for an existing room.
     * This avoids reprocessing all images when user only edited some images.
     * dirtyUris: list of original URIs that were edited (these should match the input imageUris values)
     */
    @SuppressLint("SuspiciousIndentation")
    fun updateMangaRoomSelective(
        roomId: Long,
        imageUris: List<Uri>,
        translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
        dirtyUris: List<Uri>,
        callerUriToImageId: Map<Uri, Long>,
        translatedStatus: Map<Uri, Boolean>? = null
    ): Boolean {
        if (imageUris.isEmpty()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            // build map uri->imageId for images belonging to this room
            val imageIdMap = mutableMapOf<String, Long>()
            // start with caller-provided mapping (if available) to improve matching
            callerUriToImageId.forEach { (k, v) -> imageIdMap[k.toString()] = v }
            Log.i(TAG, "updateMangaRoomSelective: callerUriToImageId has ${callerUriToImageId.size} entries")
            
            val cursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
            var dbImageCount = 0
            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val uri = cursor.getString(1)
                imageIdMap[uri] = imageId
                dbImageCount++
            }
            cursor.close()
            Log.i(TAG, "updateMangaRoomSelective: DB has $dbImageCount images for room $roomId, total imageIdMap size=${imageIdMap.size}")
            Log.i(TAG, "updateMangaRoomSelective: dirtyUris=${dirtyUris.map { it.toString() }}")

            // Update display order and is_translated flags for all images
            imageUris.forEachIndexed { index, uri ->
                val uriStr = uri.toString()
                val isTranslated = if (translatedStatus?.get(uri) == true || translatedTexts.containsKey(uri)) 1 else 0
                val imageId = imageIdMap[uriStr]
                if (imageId != null) {
                    val imageValues = ContentValues().apply {
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, isTranslated)
                    }
                    db.update(TABLE_IMAGES, imageValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                }
            }

            // Prepare images directory reference for potential new images
            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            imagesDir.mkdirs()

            // For each dirty uri, delete and re-insert translations + image_blocks
            dirtyUris.forEach { dirtyUri ->
                val uriStr = dirtyUri.toString()
                var imageId = imageIdMap[uriStr]
                Log.i(TAG, "updateMangaRoomSelective: Processing dirtyUri=$uriStr exactMatch=${imageId != null}")
                
                if (imageId == null) {
                    try {
                        val fileName = Uri.parse(uriStr).lastPathSegment ?: java.io.File(uriStr).name
                        val entry = imageIdMap.entries.find { (k, _) ->
                            val kName = try { Uri.parse(k).lastPathSegment ?: java.io.File(k).name } catch (e: Exception) { java.io.File(k).name }
                            k == uriStr || kName == fileName || k.endsWith(fileName)
                        }
                        if (entry != null) {
                            imageId = entry.value
                            Log.i(TAG, "updateMangaRoomSelective: Found imageId=$imageId via filename match (fileName=$fileName, matchedKey=${entry.key})")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "updateMangaRoomSelective: Exception during filename matching for $uriStr", e)
                    }
                }
                
                // Try to match by display_order/index position as last resort
                if (imageId == null) {
                    val index = imageUris.indexOf(dirtyUri)
                    if (index >= 0) {
                        // Try to find image with this display_order
                        val orderCursor = db.rawQuery(
                            "SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? AND $COLUMN_DISPLAY_ORDER = ?",
                            arrayOf(roomId.toString(), index.toString())
                        )
                        if (orderCursor.moveToFirst()) {
                            imageId = orderCursor.getLong(0)
                            Log.i(TAG, "updateMangaRoomSelective: Found imageId=$imageId via display_order=$index")
                        }
                        orderCursor.close()
                    }
                }

                // If imageId is null, treat this as a new image: copy into room folder and insert into images
                if (imageId == null) {
                    Log.w(TAG, "updateMangaRoomSelective: No imageId found for dirtyUri=$uriStr, treating as NEW image (this may cause duplicates!)")
                    
                    // SAFETY CHECK: Before inserting a new image, verify there's no existing image at this index
                    // to prevent duplicate entries
                    val index = imageUris.indexOf(dirtyUri).coerceAtLeast(0)
                    val existingCursor = db.rawQuery(
                        "SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? AND $COLUMN_DISPLAY_ORDER = ?",
                        arrayOf(roomId.toString(), index.toString())
                    )
                    if (existingCursor.moveToFirst()) {
                        // There's already an image at this index - use it instead of creating a new one
                        imageId = existingCursor.getLong(0)
                        val existingUri = existingCursor.getString(1)
                        Log.w(TAG, "updateMangaRoomSelective: Found existing image at index $index (imageId=$imageId, uri=$existingUri), using it instead of creating new")
                        existingCursor.close()
                    } else {
                        existingCursor.close()
                        
                        // No existing image at this index - create new one
                        try {
                            val fileName = "image_${index}.webp"
                            val newFile = copyImageToInternalStorage(dirtyUri, imagesDir, fileName)
                            val newUri = if (newFile != null && newFile.exists()) Uri.fromFile(newFile) else dirtyUri
                            // Get original OCR text for this image
                            val imageValues = ContentValues().apply {
                                put(COLUMN_ROOM_ID, roomId)
                                put(COLUMN_IMAGE_URI, newUri.toString())
                                put(COLUMN_DISPLAY_ORDER, index)
                                put(COLUMN_IS_TRANSLATED, if (translatedTexts.containsKey(dirtyUri)) 1 else 0)
                                // original_text giờ lưu trong bảng translations (per-block)
                            }
                            val insertedImageId = db.insert(TABLE_IMAGES, null, imageValues)
                            if (insertedImageId != -1L) {
                                imageId = insertedImageId
                                imageIdMap[newUri.toString()] = imageId
                                // remove original file if necessary
                                try { deleteOriginalImage(dirtyUri) } catch (e: Exception) { /* ignore */ }
                                Log.i(TAG, "updateMangaRoomSelective: Successfully inserted new image at index $index (imageId=$imageId)")
                            } else {
                                Log.e(TAG, "Không thể chèn ảnh mới cho uri $uriStr vào phòng $roomId")
                                // skip processing this dirtyUri
                                return@forEach
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Lỗi khi chèn ảnh mới cho uri $uriStr", e)
                            return@forEach
                        }
                    }
                }
                val deletedCount = db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                try { 
                    deleteBlocksForImage(imageId)
                } catch (e: Exception) { 
                    Log.w(TAG, "Failed to delete image_blocks for imageId=$imageId", e)
                }

                // insert new translations if present
                // Try to find by exact URI first, then by filename match
                val dirtyFilename = try { Uri.parse(uriStr).lastPathSegment ?: java.io.File(uriStr).name } catch (e: Exception) { null }
                val translationEntry = translatedTexts[dirtyUri] ?: translatedTexts.entries.find { (k, _) ->
                    val kFilename = try { k.lastPathSegment ?: java.io.File(k.toString()).name } catch (e: Exception) { null }
                    kFilename != null && dirtyFilename != null && kFilename == dirtyFilename
                }?.value
                
                translationEntry?.let { (originalText, textBlocks) ->
                    // DO NOT scale blocks based on saved file size anymore.
                    // Blocks retain their original coordinates relative to originalImageWidth/Height.
                    // ImageViewer handles scaling at display time.
                    
                    // Filter out blocks that are marked for deletion (pendingDelete = true)
                    val blocksToSave = textBlocks.filter { !it.pendingDelete }
                    Log.i(TAG, "updateMangaRoomSelective: uri=$dirtyUri totalBlocks=${textBlocks.size} blocksToSave=${blocksToSave.size} (filtered ${textBlocks.size - blocksToSave.size} pendingDelete blocks)")

                    var insertedCount = 0
                    blocksToSave.forEachIndexed { idx, textBlock ->
                        // Log inset values being saved
                        if (textBlock.overlayInset != 0f || textBlock.overlayInsetHorizontal != 0f || textBlock.overlayInsetVertical != 0f) {
                            Log.i(TAG, "updateMangaRoomSelective: Block[$idx] inset=${textBlock.overlayInset} insetH=${textBlock.overlayInsetHorizontal} insetV=${textBlock.overlayInsetVertical}")
                        }
                        val origRect = textBlock.bounds
                        // Use original bounds without scaling
                        val finalRect = origRect

                        // Lưu translated_text VÀ original_text (per-block)
                        val textValues = ContentValues().apply {
                            put(COLUMN_IMAGE_ID, imageId)
                            put("translated_text", textBlock.text)
                            val safeOrigText = textBlock.originalText?.trim()
                            val finalOrigToSave = if (safeOrigText == "[]") "" else safeOrigText ?: ""
                            put("original_text", finalOrigToSave)
                            put("x", finalRect.left)
                            put("y", finalRect.top)
                            put("width", finalRect.width())
                            put("height", finalRect.height())
                        }
                                val inserted = db.insert("translations", null, textValues)
                                if (inserted != -1L) {
                                insertedCount++
                            try {
                                val blockWidth = finalRect.right - finalRect.left
                                val blockHeight = finalRect.bottom - finalRect.top
                                val overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor
                                val textColor = textBlock.customTextColor ?: textBlock.originalTextColor
                                insertImageBlock(
                                    imageId = imageId,
                                    x = finalRect.left,
                                    y = finalRect.top,
                                    width = blockWidth,
                                    height = blockHeight,
                                    overlayType = textBlock.shapeType,
                                    overlayColor = overlayColor,
                                    overlayBrightness = 1.0f,
                                    overlayAlpha = textBlock.overlayAlpha,
                                    overlaySaturation = textBlock.overlaySaturation,
                                    overlayInset = textBlock.overlayInset,
                                    overlayInsetHorizontal = textBlock.overlayInsetHorizontal,
                                    overlayInsetVertical = textBlock.overlayInsetVertical,
                                    overlayRotation = textBlock.overlayRotation,
                                    textColor = textColor,
                                    textBrightness = 1.0f,
                                    textBoldness = textBlock.textBoldness,
                                    textSaturation = textBlock.textSaturation,
                                    borderColor = textBlock.customBorderColor,
                                    borderBrightness = 1.0f,
                                    borderBoldness = textBlock.borderAlpha,
                                    borderThickness = textBlock.borderThickness,
                                    // Persist shadow properties if present on the TextBlockInfo
                                    shadowColor = textBlock.customShadowColor,
                                    shadowAlpha = textBlock.shadowAlpha ?: 1.0f,
                                    shadowRadius = textBlock.shadowRadius ?: 0f,
                                    rotation = textBlock.rotation ?: 0f,
                                    fontFamily = textBlock.fontFamily,
                                    fontSize = textBlock.fontSize,
                                    // ✅ Truyền lineSpacing từ TextBlockInfo
                                    lineSpacing = textBlock.lineSpacing,
                                    textAlign = textBlock.textAlign.name,
                                     textGradientColors = textBlock.textGradientColors,
                                     textGradientOffsets = textBlock.textGradientOffsets,
                                     textGradientType = textBlock.textGradientType,
                                     originalWidth = textBlock.originalImageWidth,
                                     originalHeight = textBlock.originalImageHeight
                                 )
                                // After inserting blocks, update image-level metadata: is_translated only
                                // (original_text giờ lưu trong bảng translations per-block)
                                try {
                                    val updatedImageValues = ContentValues().apply {
                                        // Mark as translated (1) even if no blocks found, to prevent redundant OCR scans
                                        put(COLUMN_IS_TRANSLATED, 1)
                                    }
                                    db.update(TABLE_IMAGES, updatedImageValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                                } catch (e: Exception) {
                                    Log.w(TAG, "updateMangaRoomSelective: Failed to update image metadata for imageId=$imageId", e)
                                }

                                // Clear change flag after successful save
                                try { clearImageChange(imageId) } catch (e: Exception) { /* ignore */ }
                                // Delete pending translations after successful save
                                try { deletePendingTranslations(imageId) } catch (e: Exception) { /* ignore */ }
                            } catch (e: Exception) {
                                Log.w(TAG, "Không thể lưu image_block cho image $imageId", e)
                            }
                        }
                    }
                }
            }

            db.setTransactionSuccessful()
            
            // Clean up duplicate image_id entries after selective update
            cleanupDuplicateImages(roomId)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi cập nhật phòng selective $roomId", e)
            return false
        } finally {
            db.endTransaction()
        }
    }

    private fun copyImageToInternalStorage(originalUri: Uri, directory: File, fileName: String): File? {
        // Some content URIs (notably the Downloads provider: com.android.providers.downloads.documents)
        // require the app to have a persistable URI permission obtained via
        // ACTION_OPEN_DOCUMENT / ActivityResultContracts.OpenDocument and
        // ContentResolver.takePersistableUriPermission(...). If the app doesn't hold
        // that permission, openInputStream can throw SecurityException.
        try {
            // Quick check to provide a clearer log and to avoid throwing for known providers
            val authority = originalUri.authority ?: ""
            if (originalUri.scheme == "content" && authority.contains("downloads")) {
                // Attempt to open, but be prepared to fail with SecurityException
                try {
                    val normalizedFileName = if (fileName.endsWith(".webp", true)) fileName else {
                        val base = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
                        "${base}.webp"
                    }
                    val newFile = File(directory, normalizedFileName)
                    val inputStream = appContext.contentResolver.openInputStream(originalUri)
                        ?: return null
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    val outStream = FileOutputStream(newFile)
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 90, outStream)
                    outStream.close()
                    return newFile
                } catch (se: SecurityException) {
                    Log.e(TAG, "Permission denied when trying to read Downloads provider URI $originalUri - app must take persistable permissions when the URI is obtained (use ACTION_OPEN_DOCUMENT / takePersistableUriPermission).", se)
                    return null
                }
            }

            // Default path for other URIs
            val normalizedFileName = if (fileName.endsWith(".webp", true)) fileName else {
                val base = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
                "${base}.webp"
            }
            val newFile = File(directory, normalizedFileName)
            val inputStream = appContext.contentResolver.openInputStream(originalUri)
            if (inputStream != null) {
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                val outStream = FileOutputStream(newFile)
                bitmap.compress(Bitmap.CompressFormat.WEBP, 90, outStream)
                outStream.close()
                return newFile
            }
            return null
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while copying image $originalUri - missing permission to read this URI. Consider using ACTION_OPEN_DOCUMENT and calling takePersistableUriPermission when the URI is picked.", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy & compress image $originalUri", e)
            return null
        }
    }

    private fun deleteOriginalImage(uri: Uri) {
        try {
            // On Android 9 and below, deleting MediaStore URIs requires WRITE_EXTERNAL_STORAGE
            // which we don't request at runtime. Skip deletion to avoid SecurityException.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return
            }

            val contentResolver = appContext.contentResolver
            val scheme = uri.scheme
            when {
                // Nếu là MediaStore uri (content://media/external...)
                scheme == "content" && uri.authority?.contains("media") == true -> {
                    contentResolver.delete(uri, null, null)
                }
                // If it's the Downloads provider, we should not attempt delete here because
                // deleting a document from Downloads requires MANAGE_DOCUMENTS or a
                // granted URI permission (grantUriPermission) for write. Skip deletion and log.
                scheme == "content" && uri.authority?.contains("downloads") == true -> {
                    Log.w(TAG, "Skipping attempt to delete Downloads provider URI $uri - app likely does not have MANAGE_DOCUMENTS or persisted write permission.")
                }
                // Nếu là Document uri (SAF) - other document providers
                scheme == "content" && uri.authority?.contains("documents") == true -> {
                    try {
                        DocumentsContract.deleteDocument(contentResolver, uri)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Không thể xóa bằng DocumentsContract (permission denied): $uri", e)
                    } catch (e: Exception) {
                        Log.w(TAG, "Không thể xóa bằng DocumentsContract: $uri", e)
                    }
                }
                // Nếu là file vật lý (file:// hoặc path)
                scheme == "file" || scheme == null -> {
                    val file = File(uri.path ?: "")
                    if (file.exists()) file.delete()
                    // Nếu file nằm trong external files dir, cũng thử xóa
                    val externalDir = appContext.getExternalFilesDir(null)
                    if (externalDir != null && file.absolutePath.startsWith(externalDir.absolutePath)) {
                        if (file.exists()) file.delete()
                    }
                }
                else -> {
                    // Thử xóa bằng contentResolver như fallback
                    contentResolver.delete(uri, null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể xóa ảnh: $uri", e)
        }
    }

    fun getMangaRoom(roomId: Long): Triple<List<Uri>, List<Int>, Map<Uri, Pair<String, List<TextBlockInfo>>>> {
        val db = writableDatabase
        
        // ===== CLEANUP DUPLICATES TRƯỚC KHI LOAD =====
        cleanupDuplicateImagesStrict(roomId)
        
        // Xóa tất cả bản dịch có pending_delete = 1 cho room này
        db.execSQL("""
            DELETE FROM translations 
            WHERE image_id IN (
                SELECT image_id FROM images WHERE room_id = ?
            ) AND pending_delete = 1
        """, arrayOf(roomId.toString()))
        
        // Chuyển tất cả is_changed về 0 cho images trong room
        db.execSQL("""
            UPDATE $TABLE_CHANGE_IMAGES 
            SET $COLUMN_CHANGE_IMAGE_FLAG = 0 
            WHERE $COLUMN_CHANGE_IMAGE_ROOM_ID = ?
        """, arrayOf(roomId.toString()))
        
        val images = mutableListOf<Uri>()
        val orders = mutableListOf<Int>()
        val translations = mutableMapOf<Uri, Pair<String, MutableList<TextBlockInfo>>>()

        val seenUris = mutableSetOf<String>() // Track URIs đã thấy
        val seenImageIds = mutableSetOf<Long>() // Track image_id đã thấy
        val seenFilenames = mutableSetOf<String>() // Track filename đã thấy
        val missingImageIds = mutableListOf<Long>() // Track images with missing files
        
        // 1. Lấy thông tin từ bảng images
        val imageCursor = db.rawQuery("""
            SELECT $COLUMN_IMAGE_URI, $COLUMN_DISPLAY_ORDER, $COLUMN_IMAGE_ID
            FROM $TABLE_IMAGES 
            WHERE $COLUMN_ROOM_ID = ? 
            ORDER BY $COLUMN_DISPLAY_ORDER
        """, arrayOf(roomId.toString()))

        while (imageCursor.moveToNext()) {
            val uriStr = imageCursor.getString(0)
            val imageId = imageCursor.getLong(2)
            val filename = try { Uri.parse(uriStr).lastPathSegment ?: uriStr } catch (e: Exception) { uriStr }
            
            // Skip nếu đã thấy image_id này
            if (seenImageIds.contains(imageId)) {
                Log.w(TAG, "getMangaRoom: Skip duplicate image_id=$imageId")
                continue
            }
            // Skip nếu đã thấy URI này
            if (seenUris.contains(uriStr)) {
                Log.w(TAG, "getMangaRoom: Skip duplicate uri=$uriStr")
                continue
            }
            // Skip nếu đã thấy filename này (tránh trường hợp URI khác nhưng cùng file)
            if (seenFilenames.contains(filename)) {
                Log.w(TAG, "getMangaRoom: Skip duplicate filename=$filename")
                continue
            }
            
            // Check if the file actually exists on disk
            val uri = Uri.parse(uriStr)
            val filePath = uri.path
            if (filePath != null) {
                val file = File(filePath)
                if (!file.exists()) {
                    Log.w(TAG, "getMangaRoom: File missing for imageId=$imageId, uri=$uriStr - will be removed from DB")
                    missingImageIds.add(imageId)
                    continue // Skip this image
                }
            }
            
            seenImageIds.add(imageId)
            seenUris.add(uriStr)
            seenFilenames.add(filename)
            
            val order = imageCursor.getInt(1)
            val originalTextForImage = imageCursor.getString(3) ?: ""

            images.add(uri)
            orders.add(order)

            // 2. Lấy TẤT CẢ translated_text VÀ original_text từ bảng translations (theo thứ tự text_id)
            val translationsCursor = db.rawQuery("""
                SELECT translated_text, original_text FROM translations
                WHERE $COLUMN_IMAGE_ID = ?
                AND (pending_delete IS NULL OR pending_delete = 0)
                ORDER BY text_id ASC
            """, arrayOf(imageId.toString()))
            val translatedTexts = mutableListOf<String>()
            val originalTexts = mutableListOf<String>()
            while (translationsCursor.moveToNext()) {
                translatedTexts.add(translationsCursor.getString(0) ?: "")
                originalTexts.add(translationsCursor.getString(1) ?: "")
            }
            translationsCursor.close()

            // 3. Lấy TẤT CẢ dữ liệu từ bảng image_blocks (theo thứ tự block_id)
            val blockCursor = db.rawQuery("""
                SELECT * FROM $TABLE_IMAGE_BLOCKS 
                WHERE $COLUMN_BLOCK_IMAGE_ID = ?
                ORDER BY $COLUMN_BLOCK_ID ASC
            """, arrayOf(imageId.toString()))
            
            val textBlocks = mutableListOf<TextBlockInfo>()
            var blockIndex = 0
            while (blockCursor.moveToNext()) {
                // Lấy bounds từ image_blocks
                val x = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_X))
                val y = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_Y))
                val width = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_WIDTH))
                val height = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_HEIGHT))
                val bounds = Rect(x, y, x + width, y + height)
                
                // Match translated_text VÀ original_text theo thứ tự index (translations và blocks được insert cùng thứ tự)
                // Đảm bảo không ghi đè original_text thành null hoặc rỗng một cách vô lý nếu textBlock có sẵn
                val translatedText = if (blockIndex < translatedTexts.size) translatedTexts[blockIndex] else ""
                val originalText = if (blockIndex < originalTexts.size) {
                    val dbOrig = originalTexts[blockIndex]
                    // Nếu dbOrig là chuỗi rỗng hoặc "[]", trả về null để fallback về text
                    if (dbOrig.isBlank() || dbOrig.trim() == "[]") null else dbOrig
                } else null
                blockIndex++
                
                // Lấy tất cả thuộc tính overlay từ image_blocks
                val overlayColor = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_COLOR)
                    if (!blockCursor.isNull(idx)) blockCursor.getInt(idx) else null
                } catch (e: Exception) { null }
                
                val overlayAlpha = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_ALPHA)).toFloat() } catch (e: Exception) { 1.0f }
                val overlaySaturation = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_SATURATION)).toFloat() } catch (e: Exception) { 1.0f }
                val overlayInset = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET)).toFloat() } catch (e: Exception) { 0f }
                val overlayInsetH = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL)).toFloat() } catch (e: Exception) { overlayInset }
                val overlayInsetV = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET_VERTICAL)).toFloat() } catch (e: Exception) { overlayInset }
                val overlayRotation = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_ROTATION)
                    if (!blockCursor.isNull(idx)) blockCursor.getDouble(idx).toFloat() else null
                } catch (e: Exception) { null }
                val overlayType = try { blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_TYPE)) } catch (e: Exception) { 0 }
                
                val textColor = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_COLOR)
                    if (!blockCursor.isNull(idx)) {
                        val c = blockCursor.getInt(idx)
                        if (c != 0) c else null
                    } else null
                } catch (e: Exception) { null }
                val textBoldness = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_BOLDNESS)).toFloat() } catch (e: Exception) { 1.0f }
                val textSaturation = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_SATURATION)).toFloat() } catch (e: Exception) { 1.0f }
                
                val fontSize = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_SIZE)).toFloat() } catch (e: Exception) { 14f }
                val fontFamily = try { blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_FAMILY)) } catch (e: Exception) { null }
                val rotation = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_ROTATION)).toFloat() } catch (e: Exception) { 0f }
                val lineSpacing = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_LINE_SPACING)).toFloat() } catch (e: Exception) { 1.0f }
                
                val borderColor = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_COLOR)
                    if (!blockCursor.isNull(idx)) blockCursor.getInt(idx) else null
                } catch (e: Exception) { null }
                val borderThickness = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_THICKNESS)).toFloat() } catch (e: Exception) { 0f }
                
                val shadowColor = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_COLOR)
                    if (!blockCursor.isNull(idx)) blockCursor.getInt(idx) else null
                } catch (e: Exception) { null }
                val shadowAlpha = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_ALPHA)).toFloat() } catch (e: Exception) { 1.0f }
                val shadowRadius = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_RADIUS)).toFloat() } catch (e: Exception) { 0f }
                val textAlignStr = try { blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_ALIGN)) } catch (e: Exception) { "CENTER" }
                val textAlignEnum = try { com.example.ocrmanga.data.models.TextAlignMode.valueOf(textAlignStr) } catch (e: Exception) { com.example.ocrmanga.data.models.TextAlignMode.CENTER }
                
                val textGradientColors = try {
                    val colorsStr = blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_COLORS))
                    colorsStr?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() }
                } catch (e: Exception) { null }
                val textGradientOffsets = try {
                    val offsetsStr = blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS))
                    offsetsStr?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toFloatOrNull() }
                } catch (e: Exception) { null }
                val textGradientType = try { blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_TYPE)) } catch (e: Exception) { 0 }
                
                // Log để debug
                if (overlayInset != 0f || overlayInsetH != 0f || overlayInsetV != 0f) {
                    Log.i(TAG, "getMangaRoom ĐỌC INSET: imageId=$imageId bounds=$bounds inset=$overlayInset insetH=$overlayInsetH insetV=$overlayInsetV")
                }
                
                val finalTextColor = textColor ?: 0xFF000000.toInt()
                val finalFontFamily = if (fontFamily.isNullOrBlank()) "mto_astro_city" else fontFamily
                
                textBlocks.add(TextBlockInfo(
                    text = translatedText,
                    originalText = originalText,
                    bounds = bounds,
                    fontSize = fontSize,
                    lineSpacing = lineSpacing,
                    rotation = rotation,
                    textAlign = textAlignEnum,
                    originalImageWidth = null,
                    originalImageHeight = null,
                    shapeType = overlayType,
                    backgroundType = BackgroundType.WHITE,
                    averageBackgroundColor = overlayColor,
                    originalTextColor = null,
                    customOverlayColor = overlayColor,
                    customTextColor = finalTextColor,
                    overlayAlpha = overlayAlpha,
                    textBoldness = textBoldness,
                    overlaySaturation = overlaySaturation,
                    textSaturation = textSaturation,
                    customBorderColor = borderColor,
                    borderThickness = borderThickness,
                    customShadowColor = shadowColor,
                    shadowAlpha = shadowAlpha,
                    shadowRadius = shadowRadius,
                    fontFamily = finalFontFamily,
                    applyMerge = false,
                    overlayInsetHorizontal = overlayInsetH,
                    overlayInsetVertical = overlayInsetV,
                    overlayRotation = overlayRotation,
                    textGradientColors = textGradientColors,
                    textGradientOffsets = textGradientOffsets,
                    textGradientType = textGradientType
                ))
            }
            blockCursor.close()
            
            if (textBlocks.isNotEmpty()) {
                if (!translations.containsKey(uri)) {
                    translations[uri] = originalTextForImage to textBlocks
                }
            }
        }
        imageCursor.close()
        
        // Clean up images with missing files from DB
        if (missingImageIds.isNotEmpty()) {
            Log.w(TAG, "getMangaRoom: Removing ${missingImageIds.size} images with missing files from DB")
            for (imageId in missingImageIds) {
                try {
                    db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    Log.i(TAG, "getMangaRoom: Removed missing image from DB: imageId=$imageId")
                } catch (e: Exception) {
                    Log.e(TAG, "getMangaRoom: Failed to remove missing image imageId=$imageId", e)
                }
            }
            // Re-index display_order after removing missing images
            val reorderCursor = db.rawQuery(
                "SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? ORDER BY $COLUMN_DISPLAY_ORDER ASC",
                arrayOf(roomId.toString())
            )
            var newOrder = 0
            while (reorderCursor.moveToNext()) {
                val imgId = reorderCursor.getLong(0)
                val values = ContentValues().apply { put(COLUMN_DISPLAY_ORDER, newOrder) }
                db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imgId.toString()))
                newOrder++
            }
            reorderCursor.close()
        }

        return Triple(images, orders, translations)
    }

    /**
     * OPTIMIZED VERSION: Load room data using batch queries instead of N+2 queries.
     *
     * OLD WAY (N+2 queries for N images):
     * - 1 query: get all images
     * - N queries: 1 translation query per image
     * - N queries: 1 blocks query per image
     *
     * NEW WAY (3 queries total):
     * - 1 query: get all images
     * - 1 query: get ALL translations at once (grouped by image_id)
     * - 1 query: get ALL blocks at once (grouped by image_id)
     */
    fun getMangaRoomOptimized(roomId: Long): Triple<List<Uri>, List<Int>, Map<Uri, Pair<String, List<TextBlockInfo>>>> {
        val db = writableDatabase

        // Cleanup duplicates first (same as original)
        cleanupDuplicateImagesStrict(roomId)

        // Delete pending translations
        db.execSQL("""
            DELETE FROM translations
            WHERE image_id IN (
                SELECT image_id FROM images WHERE room_id = ?
            ) AND pending_delete = 1
        """, arrayOf(roomId.toString()))

        // Reset change flags
        db.execSQL("""
            UPDATE $TABLE_CHANGE_IMAGES
            SET $COLUMN_CHANGE_IMAGE_FLAG = 0
            WHERE $COLUMN_CHANGE_IMAGE_ROOM_ID = ?
        """, arrayOf(roomId.toString()))

        val images = mutableListOf<Uri>()
        val orders = mutableListOf<Int>()
        val translations = mutableMapOf<Uri, Pair<String, MutableList<TextBlockInfo>>>()

        // ===== QUERY 1: Get all images in ONE query =====
        val seenUris = mutableSetOf<String>()
        val seenImageIds = mutableSetOf<Long>()
        val seenFilenames = mutableSetOf<String>()
        val missingImageIds = mutableListOf<Long>()
        val imageIdToUri = mutableMapOf<Long, Uri>()
        val imageIdToOrder = mutableMapOf<Long, Int>()

        val imageCursor = db.rawQuery("""
            SELECT $COLUMN_IMAGE_URI, $COLUMN_DISPLAY_ORDER, $COLUMN_IMAGE_ID
            FROM $TABLE_IMAGES
            WHERE $COLUMN_ROOM_ID = ?
            ORDER BY $COLUMN_DISPLAY_ORDER
        """, arrayOf(roomId.toString()))

        while (imageCursor.moveToNext()) {
            val uriStr = imageCursor.getString(0)
            val imageId = imageCursor.getLong(2)
            val filename = try { Uri.parse(uriStr).lastPathSegment ?: uriStr } catch (e: Exception) { uriStr }

            if (seenImageIds.contains(imageId) || seenUris.contains(uriStr) || seenFilenames.contains(filename)) {
                Log.w(TAG, "getMangaRoomOptimized: Skip duplicate image_id=$imageId")
                continue
            }

            val uri = Uri.parse(uriStr)
            val filePath = uri.path
            if (filePath != null && !File(filePath).exists()) {
                Log.w(TAG, "getMangaRoomOptimized: File missing for imageId=$imageId")
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
            imageIdToOrder[imageId] = order
        }
        imageCursor.close()

        // ===== QUERY 2: Get ALL translations in ONE query (grouped by image_id) =====
        val translationsByImageId = mutableMapOf<Long, MutableList<TranslationData>>()

        if (seenImageIds.isNotEmpty()) {
            val placeholders = seenImageIds.joinToString(",") { "?" }
            val translationsCursor = db.rawQuery("""
                SELECT $COLUMN_IMAGE_ID, translated_text, original_text, x, y, width, height
                FROM translations
                WHERE $COLUMN_IMAGE_ID IN ($placeholders)
                AND (pending_delete IS NULL OR pending_delete = 0)
                ORDER BY $COLUMN_IMAGE_ID, text_id ASC
            """, seenImageIds.map { it.toString() }.toTypedArray())

            while (translationsCursor.moveToNext()) {
                val imgId = translationsCursor.getLong(0)
                val text = translationsCursor.getString(1) ?: ""
                val origText = translationsCursor.getString(2) ?: ""
                val x = translationsCursor.getInt(3)
                val y = translationsCursor.getInt(4)
                val w = translationsCursor.getInt(5)
                val h = translationsCursor.getInt(6)

                translationsByImageId.getOrPut(imgId) { mutableListOf() }.add(
                    TranslationData(text, origText, x, y, w, h)
                )
            }
            translationsCursor.close()
        }

        // ===== QUERY 3: Get ALL blocks in ONE query, store in memory map =====
        val blocksDataByImageId = mutableMapOf<Long, MutableList<BlockData>>()
        if (seenImageIds.isNotEmpty()) {
            val placeholders = seenImageIds.joinToString(",") { "?" }
            val blocksCursor = db.rawQuery("""
                SELECT * FROM $TABLE_IMAGE_BLOCKS
                WHERE $COLUMN_BLOCK_IMAGE_ID IN ($placeholders)
                ORDER BY $COLUMN_BLOCK_IMAGE_ID, $COLUMN_BLOCK_ID ASC
            """, seenImageIds.map { it.toString() }.toTypedArray())

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
                val blockData = BlockData(
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
                    textAlign = try { com.example.ocrmanga.data.models.TextAlignMode.valueOf(blocksCursor.getString(textAlignCol)) } catch (e: Exception) { com.example.ocrmanga.data.models.TextAlignMode.CENTER },
                    textGradientColors = try { blocksCursor.getString(textGradColorsCol)?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() } } catch (e: Exception) { null },
                    textGradientOffsets = try { blocksCursor.getString(textGradOffsetsCol)?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toFloatOrNull() } } catch (e: Exception) { null },
                    textGradientType = try { blocksCursor.getInt(textGradTypeCol) } catch (e: Exception) { 0 }
                )
                blocksDataByImageId.getOrPut(imgId) { mutableListOf() }.add(blockData)
            }
            blocksCursor.close()
        }

        // ===== Process each image using the cached data =====
        for (imageId in seenImageIds) {
            val uri = imageIdToUri[imageId] ?: continue

            // Get translated and original texts for this image
            val translationsForImage = translationsByImageId[imageId] ?: emptyList()

            // Get blocks from pre-loaded map (NO additional query)
            val blocksData = blocksDataByImageId[imageId] ?: emptyList()

            val textBlocks = mutableListOf<TextBlockInfo>()
            for ((blockIndex, blockData) in blocksData.withIndex()) {
                val transData = if (blockIndex < translationsForImage.size) translationsForImage[blockIndex] else null
                
                // Prioritize coordinates from translations table if available (width > 0 and height > 0)
                val bounds = if (transData != null && transData.width > 0 && transData.height > 0) {
                    Rect(transData.x, transData.y, transData.x + transData.width, transData.y + transData.height)
                } else {
                    Rect(blockData.x, blockData.y, blockData.x + blockData.width, blockData.y + blockData.height)
                }
                
                val translatedText = transData?.translatedText ?: ""
                val originalText = transData?.originalText ?: ""

                // LẤY originalText từ database (không filter blank)
                // Logic: nếu translatedText có giá trị, originalText có thể là null hoặc rỗng
                // Nếu translatedText rỗng, originalText có thể có giá trị (case reuse OCR)
                val finalOriginalText = if (originalText.isNotBlank()) originalText else translatedText.takeIf { it.isNotBlank() }

                val finalTextColor = blockData.textColor ?: 0xFF000000.toInt()
                val finalFontFamily = if (blockData.fontFamily.isNullOrBlank()) "mto_astro_city" else blockData.fontFamily

                textBlocks.add(TextBlockInfo(
                    text = translatedText,
                    originalText = finalOriginalText,
                    bounds = bounds,
                    fontSize = blockData.fontSize,
                    lineSpacing = blockData.lineSpacing,
                    rotation = blockData.rotation,
                    textAlign = blockData.textAlign,
                    originalImageWidth = null,
                    originalImageHeight = null,
                    shapeType = blockData.overlayType,
                    backgroundType = BackgroundType.WHITE,
                    averageBackgroundColor = blockData.overlayColor,
                    originalTextColor = null,
                    customOverlayColor = blockData.overlayColor,
                    customTextColor = finalTextColor,
                    overlayAlpha = blockData.overlayAlpha,
                    textBoldness = blockData.textBoldness,
                    overlaySaturation = blockData.overlaySaturation,
                    textSaturation = blockData.textSaturation,
                    customBorderColor = blockData.borderColor,
                    borderThickness = blockData.borderThickness,
                    customShadowColor = blockData.shadowColor,
                    shadowAlpha = blockData.shadowAlpha,
                    shadowRadius = blockData.shadowRadius,
                    fontFamily = finalFontFamily,
                    applyMerge = false,
                    overlayInsetHorizontal = blockData.overlayInsetH,
                    overlayInsetVertical = blockData.overlayInsetV,
                    overlayRotation = blockData.overlayRotation,
                    textGradientColors = blockData.textGradientColors,
                    textGradientOffsets = blockData.textGradientOffsets,
                    textGradientType = blockData.textGradientType
                ))
            }

            // KHÔNG filter textBlocks rỗng - cho phép blocks có translatedText rỗng nếu originalText có giá trị
            translations[uri] = "" to textBlocks
        }

        // Clean up missing images
        if (missingImageIds.isNotEmpty()) {
            Log.w(TAG, "getMangaRoomOptimized: Removing ${missingImageIds.size} images with missing files from DB")
            for (imageId in missingImageIds) {
                try {
                    db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                } catch (e: Exception) {
                    Log.e(TAG, "getMangaRoomOptimized: Failed to remove missing image imageId=$imageId", e)
                }
            }
            // Re-index display_order
            val reorderCursor = db.rawQuery(
                "SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? ORDER BY $COLUMN_DISPLAY_ORDER ASC",
                arrayOf(roomId.toString())
            )
            var newOrder = 0
            while (reorderCursor.moveToNext()) {
                val imgId = reorderCursor.getLong(0)
                val values = ContentValues().apply { put(COLUMN_DISPLAY_ORDER, newOrder) }
                db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imgId.toString()))
                newOrder++
            }
            reorderCursor.close()
        }

        return Triple(images, orders, translations)
    }

    /**
     * Load translations for a specific batch of image URIs.
     * Cấu trúc dữ liệu:
     * - images: thông tin ảnh (uri)
     * - translations: lấy translated_text VÀ original_text (per-block)
     * - image_blocks: TẤT CẢ dữ liệu overlay (bounds, màu sắc, font, inset, rotation, v.v.)
     */
    fun getTranslationsForImages(imageUris: List<Uri>): Map<Uri, Pair<String, List<TextBlockInfo>>> {
        val db = readableDatabase
        val translations = mutableMapOf<Uri, Pair<String, MutableList<TextBlockInfo>>>()
        
        for (uri in imageUris) {
            // 1. Lấy image_id từ bảng images
            val imageCursor = db.rawQuery("""
                SELECT $COLUMN_IMAGE_ID
                FROM $TABLE_IMAGES
                WHERE $COLUMN_IMAGE_URI = ?
            """, arrayOf(uri.toString()))
            
            if (!imageCursor.moveToFirst()) {
                imageCursor.close()
                continue
            }
            
            val imageId = imageCursor.getLong(0)
            imageCursor.close()
            
            // 2. Lấy dữ liệu translation (bao gồm bounds) từ bảng translations
            val translationsCursor = db.rawQuery("""
                SELECT translated_text, original_text, x, y, width, height FROM translations
                WHERE $COLUMN_IMAGE_ID = ?
                AND (pending_delete IS NULL OR pending_delete = 0)
                ORDER BY text_id ASC
            """, arrayOf(imageId.toString()))

            val translationDataList = mutableListOf<TranslationData>()
            while (translationsCursor.moveToNext()) {
                translationDataList.add(TranslationData(
                    translatedText = translationsCursor.getString(0) ?: "",
                    originalText = translationsCursor.getString(1) ?: "",
                    x = translationsCursor.getInt(2),
                    y = translationsCursor.getInt(3),
                    width = translationsCursor.getInt(4),
                    height = translationsCursor.getInt(5)
                ))
            }
            translationsCursor.close()

            // 3. Lấy TẤT CẢ dữ liệu từ bảng image_blocks (theo thứ tự block_id)
            val blockCursor = db.rawQuery("""
                SELECT * FROM $TABLE_IMAGE_BLOCKS 
                WHERE $COLUMN_BLOCK_IMAGE_ID = ?
                ORDER BY $COLUMN_BLOCK_ID ASC
            """, arrayOf(imageId.toString()))
            
            val textBlocks = mutableListOf<TextBlockInfo>()
            var blockIndex = 0
            while (blockCursor.moveToNext()) {
                // Match translation data theo thứ tự index
                val transData = if (blockIndex < translationDataList.size) translationDataList[blockIndex] else null
                
                // Prioritize coordinates from translations table if available
                val bounds = if (transData != null && transData.width > 0 && transData.height > 0) {
                    Log.i(TAG, "[DB-READ-COORD] imageId=$imageId block=$blockIndex text='${transData.translatedText.take(20)}' FROM translations: x=${transData.x} y=${transData.y} w=${transData.width} h=${transData.height}")
                    Rect(transData.x, transData.y, transData.x + transData.width, transData.y + transData.height)
                } else {
                    val x = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_X))
                    val y = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_Y))
                    val width = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_WIDTH))
                    val height = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_HEIGHT))
                    Rect(x, y, x + width, y + height)
                }
                
                val translatedText = transData?.translatedText ?: ""
                val originalText = if (transData != null) {
                    val dbOrig = transData.originalText
                    if (dbOrig.isBlank() || dbOrig.trim() == "[]") null else dbOrig
                } else null
                blockIndex++
                
                // Lấy tất cả thuộc tính overlay từ image_blocks
                val overlayColor = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_COLOR)
                    if (!blockCursor.isNull(idx)) blockCursor.getInt(idx) else null
                } catch (e: Exception) { null }
                
                val overlayAlpha = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_ALPHA)).toFloat() } catch (e: Exception) { 1.0f }
                val overlaySaturation = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_SATURATION)).toFloat() } catch (e: Exception) { 1.0f }
                val overlayInset = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET)).toFloat() } catch (e: Exception) { 0f }
                val overlayInsetH = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL)).toFloat() } catch (e: Exception) { overlayInset }
                val overlayInsetV = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_INSET_VERTICAL)).toFloat() } catch (e: Exception) { overlayInset }
                val overlayRotation = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_ROTATION)
                    if (!blockCursor.isNull(idx)) blockCursor.getDouble(idx).toFloat() else null
                } catch (e: Exception) { null }
                val overlayType = try { blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_TYPE)) } catch (e: Exception) { 0 }
                
                val textColor = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_COLOR)
                    if (!blockCursor.isNull(idx)) {
                        val c = blockCursor.getInt(idx)
                        if (c != 0) c else null
                    } else null
                } catch (e: Exception) { null }
                val textBoldness = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_BOLDNESS)).toFloat() } catch (e: Exception) { 1.0f }
                val textSaturation = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_SATURATION)).toFloat() } catch (e: Exception) { 1.0f }
                
                val fontSize = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_SIZE)).toFloat() } catch (e: Exception) { 14f }
                val fontFamily = try { blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_FAMILY)) } catch (e: Exception) { null }
                val rotation = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_ROTATION)).toFloat() } catch (e: Exception) { 0f }
                val lineSpacing = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_LINE_SPACING)).toFloat() } catch (e: Exception) { 1.0f }
                
                val borderColor = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_COLOR)
                    if (!blockCursor.isNull(idx)) blockCursor.getInt(idx) else null
                } catch (e: Exception) { null }
                val borderThickness = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_THICKNESS)).toFloat() } catch (e: Exception) { 0f }
                
                val shadowColor = try { 
                    val idx = blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_COLOR)
                    if (!blockCursor.isNull(idx)) blockCursor.getInt(idx) else null
                } catch (e: Exception) { null }
                val shadowAlpha = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_ALPHA)).toFloat() } catch (e: Exception) { 1.0f }
                val shadowRadius = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_RADIUS)).toFloat() } catch (e: Exception) { 0f }
                val textAlignStr = try { blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_ALIGN)) } catch (e: Exception) { "CENTER" }
                val textAlignEnum = try { com.example.ocrmanga.data.models.TextAlignMode.valueOf(textAlignStr) } catch (e: Exception) { com.example.ocrmanga.data.models.TextAlignMode.CENTER }
                
                val textGradientColors = try {
                    val colorsStr = blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_COLORS))
                    colorsStr?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() }
                } catch (e: Exception) { null }
                val textGradientOffsets = try {
                    val offsetsStr = blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS))
                    offsetsStr?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toFloatOrNull() }
                } catch (e: Exception) { null }
                val textGradientType = try { blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_GRADIENT_TYPE)) } catch (e: Exception) { 0 }
                
                // Log chi tiết dữ liệu gradient đọc được
                if (textGradientColors != null && textGradientColors.isNotEmpty()) {
                    Log.i(TAG, "[DB-READ-GRADIENT] imageId=$imageId colors=$textGradientColors offsets=$textGradientOffsets type=$textGradientType")
                }
                
                // Log để debug
                if (overlayInset != 0f || overlayInsetH != 0f || overlayInsetV != 0f) {
                    Log.i(TAG, "getTranslationsForImages ĐỌC INSET: imageId=$imageId bounds=$bounds inset=$overlayInset insetH=$overlayInsetH insetV=$overlayInsetV")
                }
                
                val finalTextColor = textColor ?: 0xFF000000.toInt()
                val finalFontFamily = if (fontFamily.isNullOrBlank()) "mto_astro_city" else fontFamily
                
                val origWidth = try { 
                    val idx = blockCursor.getColumnIndex(COLUMN_BLOCK_ORIGINAL_WIDTH)
                    if (idx >= 0 && !blockCursor.isNull(idx)) blockCursor.getInt(idx) else null
                } catch (e: Exception) { null }
                val origHeight = try { 
                    val idx = blockCursor.getColumnIndex(COLUMN_BLOCK_ORIGINAL_HEIGHT)
                    if (idx >= 0 && !blockCursor.isNull(idx)) blockCursor.getInt(idx) else null
                } catch (e: Exception) { null }

                textBlocks.add(TextBlockInfo(
                    text = translatedText,
                    originalText = originalText,
                    bounds = bounds,
                    fontSize = fontSize,
                    lineSpacing = lineSpacing,
                    rotation = rotation,
                    textAlign = textAlignEnum,
                    originalImageWidth = origWidth,
                    originalImageHeight = origHeight,
                    shapeType = overlayType,
                    backgroundType = BackgroundType.WHITE,
                    averageBackgroundColor = overlayColor,
                    originalTextColor = null,
                    customOverlayColor = overlayColor,
                    customTextColor = finalTextColor,
                    overlayAlpha = overlayAlpha,
                    textBoldness = textBoldness,
                    overlaySaturation = overlaySaturation,
                    textSaturation = textSaturation,
                    customBorderColor = borderColor,
                    borderThickness = borderThickness,
                    customShadowColor = shadowColor,
                    shadowAlpha = shadowAlpha,
                    shadowRadius = shadowRadius,
                    fontFamily = finalFontFamily,
                    applyMerge = false,
                    overlayInsetHorizontal = overlayInsetH,
                    overlayInsetVertical = overlayInsetV,
                    overlayRotation = overlayRotation,
                    textGradientColors = textGradientColors,
                    textGradientOffsets = textGradientOffsets,
                    textGradientType = textGradientType,
                ))
            }
            blockCursor.close()

            if (textBlocks.isNotEmpty()) {
                translations[uri] = "" to textBlocks
            }
        }
        
        return translations.mapValues { (_, pair) -> pair.first to pair.second.toList() }
    }

    /**
     * Lấy danh sách original_text từ bảng translations cho một image.
     * Dùng khi cần reuse OCR text đã lưu (thay vì OCR lại).
     * @return List original_text theo thứ tự text_id, rỗng nếu không có
     */
    fun getOriginalTextsForImage(imageId: Long): List<String> {
        val db = readableDatabase
        val result = mutableListOf<String>()
        try {
            val cursor = db.rawQuery("""
                SELECT original_text FROM translations
                WHERE $COLUMN_IMAGE_ID = ?
                AND (pending_delete IS NULL OR pending_delete = 0)
                AND original_text IS NOT NULL
                AND original_text != ''
                ORDER BY text_id ASC
            """, arrayOf(imageId.toString()))
            while (cursor.moveToNext()) {
                val orig = cursor.getString(0) ?: ""
                if (orig.isNotBlank() && orig.trim() != "[]") {
                    result.add(orig)
                }
            }
            cursor.close()
        } catch (e: Exception) {
            Log.w(TAG, "getOriginalTextsForImage failed for imageId=$imageId", e)
        }
        return result
    }

    fun updateRoomTitle(roomId: Long, newTitle: String) {
        val db = writableDatabase
        val values = ContentValues().apply { put(COLUMN_TITLE, newTitle) }
        db.update(TABLE_ROOMS, values, "$COLUMN_ROOM_ID=?", arrayOf(roomId.toString()))
    }

    /**
     * Update image URI for a single image row. This preserves the image_id and any
     * translations / image_blocks associated with that image. Caller is responsible
     * for also updating any in-memory maps in the ViewModel.
     */
    fun updateImageUri(imageId: Long, newUri: Uri) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply { put(COLUMN_IMAGE_URI, newUri.toString()) }
            db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "updateImageUri failed for imageId=$imageId newUri=$newUri", e)
        }
    }

    /**
     * Replace an image referenced by imageId with a newUri. This will copy the new
     * image into the app's images/<roomId>/ folder (naming it after the imageId to
     * avoid collisions), update the stored image_uri in the DB, and return the
     * app-managed Uri if successful. Returns null on failure (DB not updated).
     */
    fun replaceImageWithCopy(imageId: Long, newUri: Uri): Uri? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // find roomId for this image
            val cur = db.rawQuery("SELECT $COLUMN_ROOM_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            if (!cur.moveToFirst()) {
                cur.close()
                Log.w(TAG, "replaceImageWithCopy: imageId not found: $imageId")
                return null
            }
            val roomId = cur.getLong(0)
            val oldUriStr = cur.getString(1)
            cur.close()

            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            imagesDir.mkdirs()

            // Decide whether we can overwrite the previous stored file (preferred)
            var storedFile: File? = null
            try {
                val prevPath = try { Uri.parse(oldUriStr).path } catch (e: Exception) { null }
                if (!prevPath.isNullOrBlank()) {
                    val prev = File(prevPath)
                    if (prev.exists() && prev.parentFile != null && prev.parentFile.absolutePath.startsWith(imagesDir.absolutePath)) {
                        // Overwrite the existing file in-place to preserve the stored URI and display order
                        val copiedOver = copyImageToInternalStorage(newUri, prev.parentFile, prev.name)
                        if (copiedOver != null && copiedOver.exists()) {
                            storedFile = copiedOver
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "replaceImageWithCopy: unable to check/overwrite previous file for imageId=$imageId", e)
            }

            // If overwrite wasn't possible, create a stable filename based on imageId
            if (storedFile == null) {
                // Always store images in rooms as WebP
                val safeExt = "webp"
                val fileName = "image_${imageId}.$safeExt"
                val copied = copyImageToInternalStorage(newUri, imagesDir, fileName)
                if (copied == null) {
                    Log.e(TAG, "replaceImageWithCopy: failed to copy $newUri for imageId=$imageId")
                    return null
                }
                storedFile = copied
                // After copying into a stable new file, delete previous stored file if it was in our imagesDir
                try {
                    val prevFile = try { File(Uri.parse(oldUriStr).path ?: "") } catch (e: Exception) { null }
                    if (prevFile != null && prevFile.exists()) {
                        val parent = prevFile.parentFile
                        if (parent != null && storedFile != null && parent.absolutePath.startsWith(imagesDir.absolutePath) && prevFile.absolutePath != storedFile!!.absolutePath) {
                            prevFile.delete()
                        }
                    }
                } catch (e: Exception) { /* ignore */ }
            }

            // storedFile should not be null here, but check anyway
            if (storedFile == null) {
                Log.e(TAG, "replaceImageWithCopy: storedFile is null after processing")
                return null
            }

            val storedUri = Uri.fromFile(storedFile)
            val values = ContentValues().apply { put(COLUMN_IMAGE_URI, storedUri.toString()) }
            db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))

            // Optionally, try to delete original provided uri if it's a file the app can remove
            try { deleteOriginalImage(newUri) } catch (e: Exception) { /* ignore */ }

            db.setTransactionSuccessful()
            return storedUri
        } catch (e: Exception) {
            Log.e(TAG, "replaceImageWithCopy failed for imageId=$imageId", e)
            return null
        } finally {
            try { db.endTransaction() } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * Simple file swap: Just overwrite the old image file with new content.
     * Does NOT change anything in the database (URI, translations, blocks all preserved).
     * This is the simplest and safest way to replace an image.
     */
    fun replaceImageFileOnly(imageId: Long, newUri: Uri): Uri? {
        try {
            // Find the current stored URI for this image
            val db = readableDatabase
            val cur = db.rawQuery(
                "SELECT $COLUMN_ROOM_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_ID = ?", 
                arrayOf(imageId.toString())
            )
            if (!cur.moveToFirst()) {
                cur.close()
                Log.w(TAG, "replaceImageFileOnly: imageId not found: $imageId")
                return null
            }
            val roomId = cur.getLong(0)
            val oldUriStr = cur.getString(1)
            cur.close()

            // Parse the old URI to get the file path
            val oldUri = Uri.parse(oldUriStr)
            val oldFilePath = oldUri.path
            if (oldFilePath.isNullOrBlank()) {
                Log.e(TAG, "replaceImageFileOnly: Cannot parse file path from URI: $oldUriStr")
                return null
            }

            val oldFile = File(oldFilePath)
            if (!oldFile.exists()) {
                Log.e(TAG, "replaceImageFileOnly: Old file does not exist: $oldFilePath")
                return null
            }

            // Copy the new image content to overwrite the old file (same filename)
            val parentDir = oldFile.parentFile
            val fileName = oldFile.name
            
            if (parentDir == null) {
                Log.e(TAG, "replaceImageFileOnly: Cannot get parent directory")
                return null
            }

            // SAFETY: Copy new file to a temporary name FIRST, then delete old and rename
            // This prevents data loss if the copy fails
            val tempFileName = "temp_replace_${System.currentTimeMillis()}_$fileName"
            val tempCopiedFile = copyImageToInternalStorage(newUri, parentDir, tempFileName)
            if (tempCopiedFile == null || !tempCopiedFile.exists()) {
                Log.e(TAG, "replaceImageFileOnly: Failed to copy new image to temp file $parentDir/$tempFileName")
                return null
            }

            // Now that we have the new file successfully copied, delete the old file
            oldFile.delete()

            // Rename temp file to original filename
            val finalFile = File(parentDir, fileName)
            if (!tempCopiedFile.renameTo(finalFile)) {
                // If rename fails, try copy and delete
                tempCopiedFile.copyTo(finalFile, overwrite = true)
                tempCopiedFile.delete()
            }
            
            if (!finalFile.exists()) {
                Log.e(TAG, "replaceImageFileOnly: Failed to rename/move temp file to $parentDir/$fileName")
                return null
            }
            val copiedFile = finalFile

            // Try to delete the source file if it's a temporary file
            try { deleteOriginalImage(newUri) } catch (e: Exception) { /* ignore */ }

            Log.d(TAG, "replaceImageFileOnly: Successfully replaced $oldFilePath with content from $newUri")
            
            // Return the same URI as before (file was overwritten in place)
            return oldUri
        } catch (e: Exception) {
            Log.e(TAG, "replaceImageFileOnly failed for imageId=$imageId", e)
            return null
        }
    }

    /**
     * Get the count of images in a room
     */
    fun getImageCountForRoom(roomId: Long): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?",
            arrayOf(roomId.toString())
        )
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    fun getRoomTitle(roomId: Long): String? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_TITLE FROM $TABLE_ROOMS WHERE $COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
        return if (cursor.moveToFirst()) {
            val title = cursor.getString(0)
            cursor.close()
            title
        } else {
            cursor.close()
            null
        }
    }

    fun getAllRooms(): List<Triple<Long, String, Uri>> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_ROOM_ID, $COLUMN_TITLE, $COLUMN_COVER_URI FROM $TABLE_ROOMS ORDER BY $COLUMN_ROOM_ID DESC", null)
        val rooms = mutableListOf<Triple<Long, String, Uri>>()
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val title = cursor.getString(1)
            val coverUri = Uri.parse(cursor.getString(2))
            rooms.add(Triple(id, title, coverUri))
        }
        cursor.close()
        return rooms
    }

    fun deleteRoom(roomId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("""
                DELETE FROM translations 
                WHERE $COLUMN_IMAGE_ID IN (
                    SELECT $COLUMN_IMAGE_ID FROM $TABLE_IMAGES 
                    WHERE $COLUMN_ROOM_ID = ?
                )
            """, arrayOf(roomId.toString()))

            db.delete(TABLE_IMAGES, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
            db.delete(TABLE_ROOMS, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))

            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            if (imagesDir.exists()) {
                imagesDir.deleteRecursively()
            }

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting room $roomId", e)
        } finally {
            db.endTransaction()
        }
    }

    fun migrateRoomImageLinks() {
        val db = writableDatabase
        val externalDir = appContext.getExternalFilesDir(null)
        if (externalDir == null) return
        val cursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI, $COLUMN_ROOM_ID FROM $TABLE_IMAGES", null)
        while (cursor.moveToNext()) {
            val imageId = cursor.getLong(0)
            val oldUriStr = cursor.getString(1)
            val roomId = cursor.getLong(2)
            val oldUri = Uri.parse(oldUriStr)
            val fileName = File(oldUri.path ?: "").name
            val correctFile = File(externalDir, "images/$roomId/$fileName")
            if (correctFile.exists()) {
                val newUri = Uri.fromFile(correctFile).toString()
                if (oldUriStr != newUri) {
                    val values = ContentValues().apply { put(COLUMN_IMAGE_URI, newUri) }
                    db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID=?", arrayOf(imageId.toString()))
                }
            }
        }
        cursor.close()
    }

    // Lấy tất cả API key cùng type
    fun getAllApiKeysWithStats(): List<com.example.ocrmanga.data.models.ApiKeyInfo> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_API_KEY_ID, $COLUMN_API_KEY_VALUE, $COLUMN_API_KEY_TYPE, $COLUMN_IS_ACTIVE FROM $TABLE_API_KEYS", null)
        val apiKeys = mutableListOf<com.example.ocrmanga.data.models.ApiKeyInfo>()
        while (cursor.moveToNext()) {
            apiKeys.add(com.example.ocrmanga.data.models.ApiKeyInfo(
                id = cursor.getInt(0),
                value = cursor.getString(1),
                type = cursor.getString(2),
                isActive = cursor.getInt(3) == 1
            ))
        }
        cursor.close()
        return apiKeys
    }

    fun updateApiKeyStats(apiKey: String) {
        // No-op in simplified version
    }

    fun getAllApiKeys(): List<Pair<String, String>> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_API_KEY_VALUE, $COLUMN_API_KEY_TYPE FROM $TABLE_API_KEYS", null)
        val apiKeys = mutableListOf<Pair<String, String>>()
        while (cursor.moveToNext()) {
            apiKeys.add(cursor.getString(0) to cursor.getString(1))
        }
        cursor.close()
        return apiKeys
    }

    fun insertApiKey(apiKey: String, type: String = "default") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_API_KEY_VALUE, apiKey)
            put(COLUMN_API_KEY_TYPE, type)
            put(COLUMN_CREATED_DATE, "2025-07-15") // Default created date
            put(COLUMN_UPDATED_DATE, "2025-07-15") // Default updated date
            put(COLUMN_IS_ACTIVE, 1) // Default active status
        }
        db.insert(TABLE_API_KEYS, null, values)
    }

    fun updateApiKey(oldKey: String, newKey: String, newType: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_API_KEY_VALUE, newKey)
            if (newType != null) put(COLUMN_API_KEY_TYPE, newType)
        }
        db.update(TABLE_API_KEYS, values, "$COLUMN_API_KEY_VALUE = ?", arrayOf(oldKey))
    }

    fun deleteApiKey(apiKey: String) {
        val db = writableDatabase
        db.delete(TABLE_API_KEYS, "$COLUMN_API_KEY_VALUE = ?", arrayOf(apiKey))
    }

    fun updateApiKeyStatus(apiKey: String, isActive: Boolean): Boolean {
        val newStatus = if (isActive) 1 else 0
        val db = writableDatabase
        val values = ContentValues().apply {
            put("is_active", newStatus)
        }
        val rowsUpdated = db.update(TABLE_API_KEYS, values, "$COLUMN_API_KEY_VALUE = ?", arrayOf(apiKey))
        db.close()
        return rowsUpdated > 0
    }

    fun getApiKeyStatus(apiKey: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT is_active FROM $TABLE_API_KEYS WHERE $COLUMN_API_KEY_VALUE = ?", arrayOf(apiKey))
        val isActive = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else false
        cursor.close()
        return isActive
    }

    // --- Room Settings CRUD ---
    
    /**
     * Get auto-translate setting for a room. Returns true (enabled) by default.
     */
    fun getAutoTranslateSetting(roomId: Long): Boolean {
        val db = readableDatabase
        try {
            val cursor = db.rawQuery(
                "SELECT $COLUMN_AUTO_TRANSLATE_NEW_IMAGES FROM $TABLE_ROOM_SETTINGS WHERE $COLUMN_SETTING_ROOM_ID = ?",
                arrayOf(roomId.toString())
            )
            val result = if (cursor.moveToFirst()) {
                cursor.getInt(0) == 1
            } else {
                // Default to enabled if no setting exists
                true
            }
            cursor.close()
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Error getting auto-translate setting for roomId=$roomId", e)
            return true // Default to enabled on error
        }
    }
    
    /**
     * Set auto-translate setting for a room.
     */
    fun setAutoTranslateSetting(roomId: Long, enabled: Boolean) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply {
                put(COLUMN_SETTING_ROOM_ID, roomId)
                put(COLUMN_AUTO_TRANSLATE_NEW_IMAGES, if (enabled) 1 else 0)
            }
            val updated = db.update(
                TABLE_ROOM_SETTINGS,
                values,
                "$COLUMN_SETTING_ROOM_ID = ?",
                arrayOf(roomId.toString())
            )
            if (updated == 0) {
                // Insert if not exists
                db.insert(TABLE_ROOM_SETTINGS, null, values)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto-translate for roomId=$roomId", e)
        }
    }

    /**
     * Get ancient translation (cổ trang) setting for a room. Returns false (disabled) by default.
     */
    fun getAncientTranslationSetting(roomId: Long): Boolean {
        val db = readableDatabase
        try {
            val cursor = db.rawQuery(
                "SELECT $COLUMN_ANCIENT_TRANSLATION_ENABLED FROM $TABLE_ROOM_SETTINGS WHERE $COLUMN_SETTING_ROOM_ID = ?",
                arrayOf(roomId.toString())
            )
            val result = if (cursor.moveToFirst()) {
                cursor.getInt(0) == 1
            } else {
                // Default to disabled if no setting exists
                false
            }
            cursor.close()
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Error getting ancient translation setting for roomId=$roomId", e)
            return false // Default to disabled on error
        }
    }

    /**
     * Set ancient translation setting for a room.
     */
    fun setAncientTranslationSetting(roomId: Long, enabled: Boolean) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply {
                put(COLUMN_SETTING_ROOM_ID, roomId)
                put(COLUMN_ANCIENT_TRANSLATION_ENABLED, if (enabled) 1 else 0)
            }
            val updated = db.update(
                TABLE_ROOM_SETTINGS,
                values,
                "$COLUMN_SETTING_ROOM_ID = ?",
                arrayOf(roomId.toString())
            )
            if (updated == 0) {
                // Insert if not exists
                db.insert(TABLE_ROOM_SETTINGS, null, values)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting ancient translation for roomId=$roomId", e)
        }
    }

    init {
        migrateRoomImageLinks()
    }

    // Cursor helper extensions for safe reads
    private fun android.database.Cursor.getIntOrNull(index: Int): Int? {
        return try {
            if (index < 0 || isNull(index)) null else getInt(index)
        } catch (e: Exception) {
            null
        }
    }

    private fun android.database.Cursor.getIntOrDefault(index: Int, default: Int): Int {
        return try {
            if (index < 0 || isNull(index)) default else getInt(index)
        } catch (e: Exception) {
            default
        }
    }

    private fun android.database.Cursor.getFloatOrDefault(index: Int, default: Float): Float {
        return try {
            if (index < 0 || isNull(index)) default else getFloat(index)
        } catch (e: Exception) {
            default
        }
    }
    
    private fun android.database.Cursor.getFloatOrNull(index: Int): Float? {
        return try {
            if (index < 0 || isNull(index)) null else getFloat(index)
        } catch (e: Exception) {
            null
        }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? {
        return try {
            if (index < 0 || isNull(index)) null else getString(index)
        } catch (e: Exception) {
            null
        }
    }
} 