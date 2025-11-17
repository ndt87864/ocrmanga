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
import android.util.Log
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.BackgroundType
import java.io.File
import java.io.FileOutputStream

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
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

    private val appContext = context

    init {
        migrateRoomImageLinks()
        // Tự động thêm cột rotation vào bảng translations nếu chưa có
        try {
            val db = writableDatabase
            val cursor = db.rawQuery("PRAGMA table_info(translations)", null)
            var hasRotation = false
            var hasShapeType = false
            while (cursor.moveToNext()) {
                val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (columnName == "rotation") {
                    hasRotation = true
                }
                if (columnName == "shape_type") {
                    hasShapeType = true
                }
            }
            cursor.close()
            if (!hasRotation) {
                db.execSQL("ALTER TABLE translations ADD COLUMN rotation REAL DEFAULT 0")
            }
            if (!hasShapeType) {
                db.execSQL("ALTER TABLE translations ADD COLUMN shape_type INTEGER DEFAULT 0")
                // Cập nhật tất cả dữ liệu cũ có shape_type NULL hoặc chưa có giá trị về 0 (hình chữ nhật)
                try {
                    db.execSQL("UPDATE translations SET shape_type = 0 WHERE shape_type IS NULL")
                    Log.i(TAG, "Đã cập nhật shape_type = 0 cho tất cả dữ liệu cũ")
                } catch (e: Exception) {
                    Log.w(TAG, "Không thể cập nhật shape_type cho dữ liệu cũ", e)
                }
            }
            
            // Kiểm tra và thêm cột apply_merge nếu chưa có
            var hasApplyMerge = false
            val cursor2 = db.rawQuery("PRAGMA table_info(translations)", null)
            while (cursor2.moveToNext()) {
                val columnName = cursor2.getString(cursor2.getColumnIndexOrThrow("name"))
                if (columnName == "apply_merge") {
                    hasApplyMerge = true
                }
            }
            cursor2.close()
            if (!hasApplyMerge) {
                db.execSQL("ALTER TABLE translations ADD COLUMN apply_merge INTEGER DEFAULT 1")
                Log.i(TAG, "Đã thêm cột apply_merge vào bảng translations")
            }
            
            // Kiểm tra và thêm cột pending_delete nếu chưa có
            var hasPendingDelete = false
            val cursor3 = db.rawQuery("PRAGMA table_info(translations)", null)
            while (cursor3.moveToNext()) {
                val colName = cursor3.getString(cursor3.getColumnIndexOrThrow("name"))
                if (colName == "pending_delete") {
                    hasPendingDelete = true
                    break
                }
            }
            cursor3.close()
            if (!hasPendingDelete) {
                db.execSQL("ALTER TABLE translations ADD COLUMN pending_delete INTEGER DEFAULT 0")
                Log.i(TAG, "Đã thêm cột pending_delete vào bảng translations")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể tự động thêm cột vào bảng translations", e)
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
            while (c.moveToNext()) {
                val columnName = c.getString(c.getColumnIndexOrThrow("name"))
                when (columnName) {
                    COLUMN_BLOCK_SHADOW_COLOR -> hasShadowColor = true
                    COLUMN_BLOCK_SHADOW_ALPHA -> hasShadowAlpha = true
                    COLUMN_BLOCK_SHADOW_RADIUS -> hasShadowRadius = true
                    COLUMN_BLOCK_FONT_FAMILY -> hasFontFamily = true
                    COLUMN_BLOCK_FONT_SIZE -> hasFontSize = true
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
        } catch (e: Exception) {
            Log.w(TAG, "Không thể tự động thêm cột vào bảng image_blocks", e)
        }
    }

    companion object {
        private const val DATABASE_NAME = "MangaDownloader.db"
    private const val DATABASE_VERSION = 14
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

        // API Keys table
        private const val TABLE_API_KEYS = "api_keys"
        private const val COLUMN_API_KEY_ID = "api_key_id"
        private const val COLUMN_API_KEY_VALUE = "api_key_value"
        private const val COLUMN_CREATED_DATE = "created_date"
        private const val COLUMN_UPDATED_DATE = "updated_date"
        private const val COLUMN_IS_ACTIVE = "is_active"
        private const val COLUMN_API_KEY_TYPE = "type" // Thêm trường type

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

        db.execSQL("""
            CREATE TABLE translations (
                text_id INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_IMAGE_ID INTEGER,
                original_text TEXT,
                translated_text TEXT,
                bounds_left INTEGER,
                bounds_top INTEGER,
                bounds_right INTEGER,
                bounds_bottom INTEGER,
                font_size REAL,
                rotation REAL DEFAULT 0,
                original_image_width INTEGER, -- New column
                original_image_height INTEGER, -- New column
                shape_type INTEGER DEFAULT 0, -- New column: 0 = rectangle, 1 = oval
                background_type INTEGER DEFAULT 0, -- New column: 0 = WHITE, 1 = COLORED, 2 = TRANSPARENT
                average_background_color INTEGER, -- New column: màu nền trung bình (nullable)
                original_text_color INTEGER, -- New column: màu chữ gốc (nullable)
                custom_overlay_color INTEGER, -- New column: màu overlay tùy chỉnh (nullable)
                custom_text_color INTEGER, -- New column: màu text tùy chỉnh (nullable)
                overlay_alpha REAL DEFAULT 1.0, -- New column: độ trong suốt overlay
                text_boldness REAL DEFAULT 1.0, -- New column: độ đậm text
                overlay_saturation REAL DEFAULT 1.0, -- New column: độ bão hòa overlay
                text_saturation REAL DEFAULT 1.0, -- New column: độ bão hòa text
                pending_delete INTEGER DEFAULT 0, -- New column: đánh dấu pending delete khi retranslate
                FOREIGN KEY ($COLUMN_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
            )
        """)

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
                FOREIGN KEY ($COLUMN_BLOCK_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
            )
            """
        )
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
                Log.i(TAG, "Đã thêm cột shape_type và cập nhật dữ liệu cũ = 0")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột shape_type vào bảng translations", e)
            }
        }
        
        // Thêm cột background_type và average_background_color cho bảng translations (version 6)
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN background_type INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE translations ADD COLUMN average_background_color INTEGER")
                Log.i(TAG, "Đã thêm các cột background_type và average_background_color vào bảng translations")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm các cột màu nền vào bảng translations", e)
            }
        }
        
        // Thêm cột original_text_color cho bảng translations (version 7)
        if (oldVersion < 7) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN original_text_color INTEGER")
                Log.i(TAG, "Đã thêm cột original_text_color vào bảng translations")
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
                Log.i(TAG, "Đã thêm các cột màu sắc tùy chỉnh vào bảng translations")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm các cột màu sắc tùy chỉnh vào bảng translations", e)
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
                Log.i(TAG, "Đã tạo bảng $TABLE_IMAGE_BLOCKS")
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
                    Log.i(TAG, "Migrated translations -> $TABLE_IMAGE_BLOCKS")
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
                Log.i(TAG, "Đã tạo bảng $TABLE_CHANGE_IMAGES")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể tạo bảng $TABLE_CHANGE_IMAGES", e)
            }
        }
        // Add lineSpacing column in version 12
        if (oldVersion < 12) {
            try {
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN line_spacing REAL DEFAULT 1.0")
                Log.i(TAG, "Đã thêm cột line_spacing vào $TABLE_IMAGE_BLOCKS")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột line_spacing (có thể đã tồn tại)", e)
            }
        }
        // Add pending_delete column to translations in version 14
        if (oldVersion < 14) {
            try {
                db.execSQL("ALTER TABLE translations ADD COLUMN pending_delete INTEGER DEFAULT 0")
                Log.i(TAG, "Đã thêm cột pending_delete vào bảng translations")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột pending_delete (có thể đã tồn tại)", e)
            }
        }
    }

    // --- Helper methods for image blocks CRUD ---
    fun insertImageBlock(imageId: Long,
                         x: Int, y: Int, width: Int, height: Int,
                         overlayType: Int = 0,
                         overlayColor: Int? = null,
                         overlayBrightness: Float = 1.0f,
                         overlayAlpha: Float = 1.0f,
                         overlaySaturation: Float = 1.0f,
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
                         lineSpacing: Float = 1.0f
    ): Long {
        val db = writableDatabase
        if (shadowColor != null || shadowAlpha != 1.0f || shadowRadius != 0f) {
            Log.i("DatabaseHelper", "LƯU SHADOW: imageId=$imageId shadowColor=$shadowColor shadowAlpha=$shadowAlpha shadowRadius=$shadowRadius")
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
                        }
                        val id = db.insert(TABLE_IMAGE_BLOCKS, null, values)
        Log.i(TAG, "Inserted image_block id=$id imageId=$imageId borderColor=${borderColor?.toString() ?: "null"} borderThickness=$borderThickness fontFamily='$fontFamily' shadowColor=${shadowColor?.toString() ?: "null"} shadowAlpha=$shadowAlpha shadowRadius=$shadowRadius")
        // Explicit log when shadow properties are present to make it easy to spot
        if (shadowColor != null || (shadowRadius > 0f) || shadowAlpha != 1.0f) {
            Log.i(TAG, "Saved SHADOW for image_block id=$id imageId=$imageId shadowColor=${shadowColor?.toString() ?: "null"} shadowAlpha=$shadowAlpha shadowRadius=$shadowRadius")
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
        val textColor = cursor.getIntOrNull(idx(COLUMN_BLOCK_TEXT_COLOR))
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
        Log.d(TAG, "cursorToImageBlock: id=$id imageId=$imageId shadowColor=${shadowColor?.toString() ?: "null"} shadowAlpha=$shadowAlpha shadowRadius=$shadowRadius")
        if (shadowColor != null || (shadowRadius > 0f) || shadowAlpha != 1.0f) {
            Log.i(TAG, "Loaded SHADOW from DB for image_block id=$id imageId=$imageId shadowColor=${shadowColor?.toString() ?: "null"} shadowAlpha=$shadowAlpha shadowRadius=$shadowRadius")
        }
        val rotation = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_ROTATION), 0f)
        val fontFamily = cursor.getString(idx(COLUMN_BLOCK_FONT_FAMILY)) ?: "mto_astro_city"
        val fontSize = cursor.getFloatOrDefault(idx(COLUMN_BLOCK_FONT_SIZE), 12f)
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
            fontSize = fontSize
        )
    }

    fun deleteBlocksForImage(imageId: Long) {
        val db = writableDatabase
        db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
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

    /**
     * Đánh dấu tất cả bản dịch cũ của một imageId là pending_delete = 1
     * Được gọi khi bắt đầu retranslate một ảnh
     */
    fun markTranslationsAsPendingDelete(imageId: Long) {
        val db = writableDatabase
        try {
            val values = ContentValues().apply { put("pending_delete", 1) }
            val rowsUpdated = db.update("translations", values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
            Log.i(TAG, "Marked $rowsUpdated translations as pending_delete for imageId=$imageId")
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
            Log.i(TAG, "Deleted $rowsDeleted pending translations for imageId=$imageId")
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
            Log.i(TAG, "Cleared pending_delete status for $rowsUpdated translations of imageId=$imageId")
        } catch (e: Exception) {
            Log.w(TAG, "clearPendingDeleteStatus failed for imageId=$imageId", e)
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
     * or leave as-is if no translated data is provided. After applying, set is_changed = 0 for those images.
     *
     * This method assumes the caller prepares the translatedTexts mapping from imageId to its new translations/data.
     */
    fun applyPendingChangesForRoom(roomId: Long, translatedByImageId: Map<Long, Pair<String, List<TextBlockInfo>>>): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val changedIds = getChangedImageIdsForRoom(roomId)
            for (imageId in changedIds) {
                // delete old translations and blocks (including pending_delete ones)
                db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                try { deleteBlocksForImage(imageId) } catch (e: Exception) { /* ignore */ }

                // insert new translations if available
                translatedByImageId[imageId]?.let { (originalText, textBlocks) ->
                    textBlocks.forEach { textBlock ->
                        val bounds = textBlock.bounds
                        val textValues = ContentValues().apply {
                            put(COLUMN_IMAGE_ID, imageId)
                            put("original_text", textBlock.originalText ?: originalText)
                            put("translated_text", textBlock.text)
                            put("bounds_left", bounds.left)
                            put("bounds_top", bounds.top)
                            put("bounds_right", bounds.right)
                            put("bounds_bottom", bounds.bottom)
                            put("font_size", textBlock.fontSize)
                            put("rotation", textBlock.rotation ?: 0f)
                            put("original_image_width", textBlock.originalImageWidth)
                            put("original_image_height", textBlock.originalImageHeight)
                            put("shape_type", textBlock.shapeType)
                            put("background_type", textBlock.backgroundType.ordinal)
                            put("average_background_color", textBlock.averageBackgroundColor)
                            put("original_text_color", textBlock.originalTextColor ?: 0xFF000000.toInt())
                            put("custom_overlay_color", textBlock.customOverlayColor)
                            put("custom_text_color", textBlock.customTextColor)
                            put("overlay_alpha", textBlock.overlayAlpha)
                            put("text_boldness", textBlock.textBoldness)
                            put("overlay_saturation", textBlock.overlaySaturation)
                            put("text_saturation", textBlock.textSaturation)
                            put("apply_merge", if (textBlock.applyMerge) 1 else 0)
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
                                    lineSpacing = textBlock.lineSpacing
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Không thể lưu image_block cho image $imageId khi applyPendingChanges", e)
                            }
                        }
                    }
                }

                // clear change flag
                clearImageChange(imageId)
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

    fun saveMangaRoom(imageUris: List<Uri>, translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>, title: String? = null): Long {
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
            Log.i(TAG, "Saved new room with ID: $roomId, title: $roomTitle")

            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            imagesDir.mkdirs()
            Log.i(TAG, "Created directory: ${imagesDir.absolutePath}")

            val coverFile = copyImageToInternalStorage(imageUris.first(), imagesDir, "cover.jpg")
            if (coverFile != null) {
                val coverUri = Uri.fromFile(coverFile)
                val roomValues = ContentValues().apply {
                    put(COLUMN_COVER_URI, coverUri.toString())
                }
                db.update(TABLE_ROOMS, roomValues, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
                Log.i(TAG, "Saved new room with ID: $roomId, cover URI: $coverUri")
                deleteOriginalImage(imageUris.first()) // Xóa ảnh gốc của cover
            } else {
                Log.e(TAG, "Failed to copy cover image for room $roomId")
            }

            imageUris.forEachIndexed { index, originalUri ->
                val fileName = "image_$index.jpg"
                val newFile = copyImageToInternalStorage(originalUri, imagesDir, fileName)
                if (newFile != null) {
                    val newUri = Uri.fromFile(newFile)
                    val imageValues = ContentValues().apply {
                        put(COLUMN_ROOM_ID, roomId)
                        put(COLUMN_IMAGE_URI, newUri.toString())
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, if (translatedTexts.containsKey(originalUri)) 1 else 0)
                    }
                    val imageId = db.insert(TABLE_IMAGES, null, imageValues)
                    if (imageId == -1L) {
                        Log.e(TAG, "Failed to insert image $newUri at index $index for room $roomId")
                    } else {
                        Log.i(TAG, "Saved image for room $roomId: ID=$imageId, URI=$newUri, Order=$index")
                        deleteOriginalImage(originalUri) // Xóa ảnh gốc sau khi lưu
                        // Ensure change record exists for this image (default is_changed = 0)
                        try { ensureChangeRecord(imageId, roomId) } catch (e: Exception) { /* ignore */ }
                    }

                    // Tự động scale lại bounds nếu ảnh đã bị resize
                    translatedTexts[originalUri]?.let { (originalText, textBlocks) ->
                        // Lấy kích thước gốc từ textBlock đầu tiên (nếu có)
                        val originalWidth = textBlocks.firstOrNull()?.originalImageWidth
                        val originalHeight = textBlocks.firstOrNull()?.originalImageHeight
                        // Lấy kích thước ảnh đã lưu
                        val savedBitmap = android.graphics.BitmapFactory.decodeFile(newFile.absolutePath)
                        val savedWidth = savedBitmap?.width
                        val savedHeight = savedBitmap?.height
                        val scaleX = if (originalWidth != null && savedWidth != null && originalWidth > 0) savedWidth.toFloat() / originalWidth else 1f
                        val scaleY = if (originalHeight != null && savedHeight != null && originalHeight > 0) savedHeight.toFloat() / originalHeight else 1f
                        textBlocks.forEach { textBlock ->
                            val origRect = textBlock.bounds
                            val scaledRect = if (scaleX != 1f || scaleY != 1f) {
                                android.graphics.Rect(
                                    (origRect.left * scaleX).toInt(),
                                    (origRect.top * scaleY).toInt(),
                                    (origRect.right * scaleX).toInt(),
                                    (origRect.bottom * scaleY).toInt()
                                )
                            } else origRect
                            val textValues = ContentValues().apply {
                                put(COLUMN_IMAGE_ID, imageId)
                                put("original_text", textBlock.originalText ?: originalText)
                                put("translated_text", textBlock.text)
                                put("bounds_left", scaledRect.left)
                                put("bounds_top", scaledRect.top)
                                put("bounds_right", scaledRect.right)
                                put("bounds_bottom", scaledRect.bottom)
                                put("font_size", textBlock.fontSize)
                                put("rotation", textBlock.rotation ?: 0f)
                                put("original_image_width", savedWidth)
                                put("original_image_height", savedHeight)
                                put("shape_type", textBlock.shapeType)
                                put("background_type", textBlock.backgroundType.ordinal)
                                put("average_background_color", textBlock.averageBackgroundColor)
                                put("original_text_color", textBlock.originalTextColor ?: 0xFF000000.toInt()) // Mặc định màu đen nếu null
                                put("custom_overlay_color", textBlock.customOverlayColor)
                                put("custom_text_color", textBlock.customTextColor)
                                put("overlay_alpha", textBlock.overlayAlpha)
                                put("text_boldness", textBlock.textBoldness)
                                put("overlay_saturation", textBlock.overlaySaturation)
                                put("text_saturation", textBlock.textSaturation)
                                put("apply_merge", if (textBlock.applyMerge) 1 else 0)
                            }
                            val textId = db.insert("translations", null, textValues)
                            if (textId == -1L) {
                                Log.e(TAG, "Failed to insert translation for image $imageId")
                            } else {
                                Log.i(TAG, "Saved translation for image $imageId: ID=$textId")
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
                                    // Log để debug màu text khi lưu
                                    Log.d(TAG, "Lưu vào image_blocks - textColor: $textColor (hex: ${String.format("#%08X", textColor ?: 0)})")
                                    Log.d(TAG, "  customTextColor: ${textBlock.customTextColor} (hex: ${String.format("#%08X", textBlock.customTextColor ?: 0)})")
                                    Log.d(TAG, "  originalTextColor: ${textBlock.originalTextColor} (hex: ${String.format("#%08X", textBlock.originalTextColor ?: 0)})")
                                    
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
                                            lineSpacing = textBlock.lineSpacing
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
            Log.i(TAG, "Successfully saved room $roomId with ${imageUris.size} images")
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
    fun updateMangaRoom(roomId: Long, imageUris: List<Uri>, translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>): Boolean {
        if (imageUris.isEmpty()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Lấy danh sách ảnh cũ trong DB
            val oldImages = mutableListOf<Pair<Long, Uri>>() // Pair<imageId, uri>
            val imageIdMap = mutableMapOf<String, Long>() // uri.toString() -> imageId
            val cursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val uri = Uri.parse(cursor.getString(1))
                oldImages.add(imageId to uri)
                imageIdMap[uri.toString()] = imageId
            }
            cursor.close()
            val oldUris = oldImages.map { it.second }

            // Xóa ảnh đã bị loại khỏi danh sách mới
            // Use filename/lastPathSegment matching to avoid deleting images when URI forms differ
            fun lastNameOf(uri: Uri?): String? {
                return try {
                    uri?.lastPathSegment ?: java.io.File(uri.toString()).name
                } catch (e: Exception) { null }
            }

            oldImages.forEach { (imageId, uri) ->
                val exactPresent = imageUris.any { it.toString() == uri.toString() }
                val name = lastNameOf(uri)
                val namePresent = if (name != null) imageUris.any { newUri ->
                    val newName = lastNameOf(newUri)
                    newName != null && (newName == name || newUri.toString().endsWith(name))
                } else false
                if (!exactPresent && !namePresent) {
                    db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    // Xóa file vật lý nếu ảnh không còn được dùng
                    val file = File(Uri.parse(uri.toString()).path ?: "")
                    if (file.exists()) file.delete()
                }
            }

            // Thêm ảnh mới và cập nhật thứ tự, trạng thái dịch
            val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
            imagesDir.mkdirs()
            imageUris.forEachIndexed { index, uri ->
                val uriStr = uri.toString()
                val isTranslated = if (translatedTexts.containsKey(uri)) 1 else 0
                if (uri !in oldUris) {
                    // Ảnh mới: chỉ copy nếu file chưa tồn tại trong thư mục phòng
                    val fileName = "image_$index.jpg"
                    val newFile = File(imagesDir, fileName)
                    if (!newFile.exists()) {
                        val copied = copyImageToInternalStorage(uri, imagesDir, fileName)
                        if (copied == null) {
                            Log.e(TAG, "Không thể copy ảnh mới $uri vào phòng $roomId")
                        }
                    }
                    val newUri = if (newFile.exists()) Uri.fromFile(newFile) else uri
                    val imageValues = ContentValues().apply {
                        put(COLUMN_ROOM_ID, roomId)
                        put(COLUMN_IMAGE_URI, newUri.toString())
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, isTranslated)
                    }
                    val imageId = db.insert(TABLE_IMAGES, null, imageValues)
                    if (imageId != -1L) {
                        // Tự động scale lại bounds nếu ảnh đã bị resize
                        // Ensure change record exists for this image
                        try { ensureChangeRecord(imageId, roomId) } catch (e: Exception) { /* ignore */ }
                        translatedTexts[uri]?.let { (originalText, textBlocks) ->
                            val originalWidth = textBlocks.firstOrNull()?.originalImageWidth
                            val originalHeight = textBlocks.firstOrNull()?.originalImageHeight
                            val savedBitmap = android.graphics.BitmapFactory.decodeFile(newFile.absolutePath)
                            val savedWidth = savedBitmap?.width
                            val savedHeight = savedBitmap?.height
                            val scaleX = if (originalWidth != null && savedWidth != null && originalWidth > 0) savedWidth.toFloat() / originalWidth else 1f
                            val scaleY = if (originalHeight != null && savedHeight != null && originalHeight > 0) savedHeight.toFloat() / originalHeight else 1f
                            // Remove any existing blocks for this image so we replace with fresh ones
                            try { deleteBlocksForImage(imageId) } catch (e: Exception) { /* ignore */ }
                            textBlocks.forEach { textBlock ->
                                val origRect = textBlock.bounds
                                val scaledRect = if (scaleX != 1f || scaleY != 1f) {
                                    android.graphics.Rect(
                                        (origRect.left * scaleX).toInt(),
                                        (origRect.top * scaleY).toInt(),
                                        (origRect.right * scaleX).toInt(),
                                        (origRect.bottom * scaleY).toInt()
                                    )
                                } else origRect
                                val textValues = ContentValues().apply {
                                    put(COLUMN_IMAGE_ID, imageId)
                                    put("original_text", textBlock.originalText ?: originalText)
                                    put("translated_text", textBlock.text)
                                    put("bounds_left", scaledRect.left)
                                    put("bounds_top", scaledRect.top)
                                    put("bounds_right", scaledRect.right)
                                    put("bounds_bottom", scaledRect.bottom)
                                    put("font_size", textBlock.fontSize)
                                    put("rotation", textBlock.rotation ?: 0f)
                                    put("original_image_width", savedWidth)
                                    put("original_image_height", savedHeight)
                                    put("shape_type", textBlock.shapeType)
                                    put("background_type", textBlock.backgroundType.ordinal)
                                    put("average_background_color", textBlock.averageBackgroundColor)
                                    put("original_text_color", textBlock.originalTextColor ?: 0xFF000000.toInt()) // Mặc định màu đen nếu null
                                    put("custom_overlay_color", textBlock.customOverlayColor)
                                    put("custom_text_color", textBlock.customTextColor)
                                    put("overlay_alpha", textBlock.overlayAlpha)
                                    put("text_boldness", textBlock.textBoldness)
                                    put("overlay_saturation", textBlock.overlaySaturation)
                                    put("text_saturation", textBlock.textSaturation)
                                    put("apply_merge", if (textBlock.applyMerge) 1 else 0)
                                }
                                val inserted = db.insert("translations", null, textValues)
                                if (inserted != -1L) {
                                    try {
                                        val blockWidth = scaledRect.right - scaledRect.left
                                        val blockHeight = scaledRect.bottom - scaledRect.top
                                        val overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor
                                        val textColor = textBlock.customTextColor ?: textBlock.originalTextColor
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
                                            lineSpacing = textBlock.lineSpacing
                                        )
                                        // After inserting translations for this image, clear change flag
                                        try { clearImageChange(imageId) } catch (e: Exception) { /* ignore */ }
                                        // Delete pending translations after successful save
                                        try { deletePendingTranslations(imageId) } catch (e: Exception) { /* ignore */ }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Không thể lưu image_block cho image $imageId", e)
                                    }
                                }
                            }
                            savedBitmap?.recycle()
                        }
                    }
                } else {
                    // Ảnh cũ: cập nhật thứ tự, trạng thái dịch
                    // Try to resolve imageId by exact URI, then by filename fallback
                    var imageId = imageIdMap[uriStr]
                    if (imageId == null) {
                        // attempt filename-based match
                        try {
                            val fileName = Uri.parse(uriStr).lastPathSegment ?: java.io.File(uriStr).name
                            val entry = imageIdMap.entries.find { (k, _) ->
                                val kName = try { Uri.parse(k).lastPathSegment ?: java.io.File(k).name } catch (e: Exception) { java.io.File(k).name }
                                k == uriStr || kName == fileName || k.endsWith(fileName)
                            }
                            if (entry != null) imageId = entry.value
                        } catch (e: Exception) { /* ignore */ }
                    }
                    if (imageId == null) {
                        // Not found; treat as new image (insert)
                        val fileName = "image_$index.jpg"
                        val newFile = File(imagesDir, fileName)
                        if (!newFile.exists()) {
                            val copied = copyImageToInternalStorage(uri, imagesDir, fileName)
                            if (copied == null) {
                                Log.e(TAG, "Không thể copy ảnh mới $uri vào phòng $roomId (fallback insert)")
                            }
                        }
                        val newUri = if (newFile.exists()) Uri.fromFile(newFile) else uri
                        val imageValues = ContentValues().apply {
                            put(COLUMN_ROOM_ID, roomId)
                            put(COLUMN_IMAGE_URI, newUri.toString())
                            put(COLUMN_DISPLAY_ORDER, index)
                            put(COLUMN_IS_TRANSLATED, isTranslated)
                        }
                        val insertedId = db.insert(TABLE_IMAGES, null, imageValues)
                        if (insertedId != -1L) {
                            try { ensureChangeRecord(insertedId, roomId) } catch (e: Exception) { /* ignore */ }
                        }
                    } else {
                        val imageValues = ContentValues().apply {
                            put(COLUMN_DISPLAY_ORDER, index)
                            put(COLUMN_IS_TRANSLATED, isTranslated)
                        }
                        db.update(TABLE_IMAGES, imageValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        // If stored URI differs from provided, update stored URI to the new one (normalized)
                        try {
                            val storedUriCursor = db.rawQuery("SELECT $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                            if (storedUriCursor.moveToFirst()) {
                                val storedUriStr = storedUriCursor.getString(0)
                                if (storedUriStr != uriStr) {
                                    val updateV = ContentValues().apply { put(COLUMN_IMAGE_URI, uriStr) }
                                    db.update(TABLE_IMAGES, updateV, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                                }
                            }
                            storedUriCursor.close()
                        } catch (e: Exception) { /* ignore */ }
                    }
                    // Nếu có bản dịch mới, xóa bản dịch cũ và thêm lại
                    if (translatedTexts.containsKey(uri)) {
                        val resolvedId = imageId
                        if (resolvedId == null) {
                            Log.w(TAG, "Skipping translation insert for uri=$uri because imageId could not be resolved")
                            return@forEachIndexed
                        }
                        db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(resolvedId.toString()))
                        translatedTexts[uri]?.let { (originalText, textBlocks) ->
                            try { deleteBlocksForImage(resolvedId) } catch (e: Exception) { /* ignore */ }
                            // Lấy lại kích thước ảnh đã lưu
                            val imageFile = File(Uri.parse(uriStr).path ?: "")
                            val savedBitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                            val savedWidth = savedBitmap?.width
                            val savedHeight = savedBitmap?.height
                            val originalWidth = textBlocks.firstOrNull()?.originalImageWidth
                            val originalHeight = textBlocks.firstOrNull()?.originalImageHeight
                            val scaleX = if (originalWidth != null && savedWidth != null && originalWidth > 0) savedWidth.toFloat() / originalWidth else 1f
                            val scaleY = if (originalHeight != null && savedHeight != null && originalHeight > 0) savedHeight.toFloat() / originalHeight else 1f
                            textBlocks.forEach { textBlock ->
                                val origRect = textBlock.bounds
                                val scaledRect = if (scaleX != 1f || scaleY != 1f) {
                                    android.graphics.Rect(
                                        (origRect.left * scaleX).toInt(),
                                        (origRect.top * scaleY).toInt(),
                                        (origRect.right * scaleX).toInt(),
                                        (origRect.bottom * scaleY).toInt()
                                    )
                                } else origRect
                                val textValues = ContentValues().apply {
                                    put(COLUMN_IMAGE_ID, resolvedId)
                                    put("original_text", textBlock.originalText ?: originalText)
                                    put("translated_text", textBlock.text)
                                    put("bounds_left", scaledRect.left)
                                    put("bounds_top", scaledRect.top)
                                    put("bounds_right", scaledRect.right)
                                    put("bounds_bottom", scaledRect.bottom)
                                    put("font_size", textBlock.fontSize)
                                    put("rotation", textBlock.rotation ?: 0f)
                                    put("original_image_width", savedWidth)
                                    put("original_image_height", savedHeight)
                                    put("shape_type", textBlock.shapeType)
                                    put("background_type", textBlock.backgroundType.ordinal)
                                    put("average_background_color", textBlock.averageBackgroundColor)
                                    put("original_text_color", textBlock.originalTextColor ?: 0xFF000000.toInt()) // Mặc định màu đen nếu null
                                    put("custom_overlay_color", textBlock.customOverlayColor)
                                    put("custom_text_color", textBlock.customTextColor)
                                    put("overlay_alpha", textBlock.overlayAlpha)
                                    put("text_boldness", textBlock.textBoldness)
                                    put("overlay_saturation", textBlock.overlaySaturation)
                                    put("text_saturation", textBlock.textSaturation)
                                    put("apply_merge", if (textBlock.applyMerge) 1 else 0)
                                }
                                val inserted = db.insert("translations", null, textValues)
                                if (inserted != -1L) {
                                    try {
                                        val blockWidth = scaledRect.right - scaledRect.left
                                        val blockHeight = scaledRect.bottom - scaledRect.top
                                        val overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor
                                        val textColor = textBlock.customTextColor ?: (textBlock.originalTextColor ?: 0xFF000000.toInt())
                                        insertImageBlock(
                                            imageId = resolvedId,
                                            x = scaledRect.left,
                                            y = scaledRect.top,
                                            width = blockWidth,
                                            height = blockHeight,
                                            overlayType = textBlock.shapeType,
                                            overlayColor = overlayColor,
                                            overlayBrightness = 1.0f,
                                            overlayAlpha = textBlock.overlayAlpha,
                                            overlaySaturation = textBlock.overlaySaturation,
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
                                            lineSpacing = textBlock.lineSpacing
                                        )
                                            // translations for existing image updated => clear change flag
                                            try { clearImageChange(resolvedId) } catch (e: Exception) { /* ignore */ }
                                            // Delete pending translations after successful save
                                            try { deletePendingTranslations(resolvedId) } catch (e: Exception) { /* ignore */ }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Không thể lưu image_block cho image $imageId", e)
                                    }
                                }
                            }
                            savedBitmap?.recycle()
                        }
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
            Log.i(TAG, "Đã cập nhật phòng $roomId (tối ưu lưu trữ, chỉ copy ảnh mới)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi cập nhật phòng $roomId", e)
            return false
        } finally {
            db.endTransaction()
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
        callerUriToImageId: Map<Uri, Long>
    ): Boolean {
        if (imageUris.isEmpty()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            // build map uri->imageId for images belonging to this room
            val imageIdMap = mutableMapOf<String, Long>()
            // start with caller-provided mapping (if available) to improve matching
            callerUriToImageId.forEach { (k, v) -> imageIdMap[k.toString()] = v }
            val cursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(0)
                val uri = cursor.getString(1)
                imageIdMap[uri] = imageId
            }
            cursor.close()

            // Update display order and is_translated flags for all images
            imageUris.forEachIndexed { index, uri ->
                val uriStr = uri.toString()
                val isTranslated = if (translatedTexts.containsKey(uri)) 1 else 0
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
                Log.d(TAG, "Selective save: processing dirtyUri=$uriStr initialImageId=$imageId")
                // Fallback: sometimes UI URI and stored image URI differ (content:// vs file://)
                // Try to match by lastPathSegment / filename
                if (imageId == null) {
                    try {
                        val fileName = Uri.parse(uriStr).lastPathSegment ?: java.io.File(uriStr).name
                        val entry = imageIdMap.entries.find { (k, _) ->
                            val kName = try { Uri.parse(k).lastPathSegment ?: java.io.File(k).name } catch (e: Exception) { java.io.File(k).name }
                            k == uriStr || kName == fileName || k.endsWith(fileName)
                        }
                        if (entry != null) imageId = entry.value
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                // If imageId is null, treat this as a new image: copy into room folder and insert into images
                if (imageId == null) {
                    try {
                        val index = imageUris.indexOf(dirtyUri).coerceAtLeast(0)
                        val fileName = "image_${index}.jpg"
                        val newFile = copyImageToInternalStorage(dirtyUri, imagesDir, fileName)
                        val newUri = if (newFile != null && newFile.exists()) Uri.fromFile(newFile) else dirtyUri
                        val imageValues = ContentValues().apply {
                            put(COLUMN_ROOM_ID, roomId)
                            put(COLUMN_IMAGE_URI, newUri.toString())
                            put(COLUMN_DISPLAY_ORDER, index)
                            put(COLUMN_IS_TRANSLATED, if (translatedTexts.containsKey(dirtyUri)) 1 else 0)
                        }
                        val insertedImageId = db.insert(TABLE_IMAGES, null, imageValues)
                        if (insertedImageId != -1L) {
                            imageId = insertedImageId
                            imageIdMap[newUri.toString()] = imageId
                            Log.i(TAG, "Inserted new image for dirtyUri=$uriStr as imageId=$imageId newUri=$newUri")
                            // remove original file if necessary
                            try { deleteOriginalImage(dirtyUri) } catch (e: Exception) { /* ignore */ }
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
                Log.d(TAG, "Selective save: resolved imageId=$imageId for dirtyUri=$uriStr")

                // delete existing translations and image_blocks for this imageId
                db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                try { deleteBlocksForImage(imageId) } catch (e: Exception) { /* ignore */ }

                // insert new translations if present
                translatedTexts[dirtyUri]?.let { (originalText, textBlocks) ->
                    // find saved file size info if needed
                    val imageFile = File(Uri.parse((imageIdMap.entries.find { it.value == imageId }?.key) ?: uriStr).path ?: "")
                    val savedBitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                    val savedWidth = savedBitmap?.width
                    val savedHeight = savedBitmap?.height

                    textBlocks.forEach { textBlock ->
                        val origRect = textBlock.bounds
                        // Attempt to scale if original sizes provided
                        val originalWidth = textBlock.originalImageWidth
                        val originalHeight = textBlock.originalImageHeight
                        val scaleX = if (originalWidth != null && savedWidth != null && originalWidth > 0) savedWidth.toFloat() / originalWidth else 1f
                        val scaleY = if (originalHeight != null && savedHeight != null && originalHeight > 0) savedHeight.toFloat() / originalHeight else 1f
                        val scaledRect = if (scaleX != 1f || scaleY != 1f) {
                            android.graphics.Rect(
                                (origRect.left * scaleX).toInt(),
                                (origRect.top * scaleY).toInt(),
                                (origRect.right * scaleX).toInt(),
                                (origRect.bottom * scaleY).toInt()
                            )
                        } else origRect

                        val textValues = ContentValues().apply {
                            put(COLUMN_IMAGE_ID, imageId)
                            put("original_text", textBlock.originalText ?: originalText)
                            put("translated_text", textBlock.text)
                            put("bounds_left", scaledRect.left)
                            put("bounds_top", scaledRect.top)
                            put("bounds_right", scaledRect.right)
                            put("bounds_bottom", scaledRect.bottom)
                            put("font_size", textBlock.fontSize)
                            put("rotation", textBlock.rotation ?: 0f)
                            put("original_image_width", savedWidth)
                            put("original_image_height", savedHeight)
                            put("shape_type", textBlock.shapeType)
                            put("background_type", textBlock.backgroundType.ordinal)
                            put("average_background_color", textBlock.averageBackgroundColor)
                            put("original_text_color", textBlock.originalTextColor ?: 0xFF000000.toInt())
                            put("custom_overlay_color", textBlock.customOverlayColor)
                            put("custom_text_color", textBlock.customTextColor)
                            put("overlay_alpha", textBlock.overlayAlpha)
                            put("text_boldness", textBlock.textBoldness)
                            put("overlay_saturation", textBlock.overlaySaturation)
                            put("text_saturation", textBlock.textSaturation)
                            put("apply_merge", if (textBlock.applyMerge) 1 else 0)
                        }
                                val inserted = db.insert("translations", null, textValues)
                                if (inserted != -1L) {
                                Log.d(TAG, "Inserted translation for imageId=$imageId bounds=${scaledRect.left},${scaledRect.top},${scaledRect.right},${scaledRect.bottom}")
                            try {
                                val blockWidth = scaledRect.right - scaledRect.left
                                val blockHeight = scaledRect.bottom - scaledRect.top
                                val overlayColor = textBlock.customOverlayColor ?: textBlock.averageBackgroundColor
                                val textColor = textBlock.customTextColor ?: textBlock.originalTextColor
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
                                    lineSpacing = textBlock.lineSpacing
                                )
                                // Clear change flag after successful save
                                try { clearImageChange(imageId) } catch (e: Exception) { /* ignore */ }
                                // Delete pending translations after successful save
                                try { deletePendingTranslations(imageId) } catch (e: Exception) { /* ignore */ }
                            } catch (e: Exception) {
                                Log.w(TAG, "Không thể lưu image_block cho image $imageId", e)
                            }
                        }
                    }
                    savedBitmap?.recycle()
                }
            }

            db.setTransactionSuccessful()
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
                    val newFile = File(directory, fileName)
                    val inputStream = appContext.contentResolver.openInputStream(originalUri)
                        ?: return null
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    val format = when {
                        fileName.endsWith(".webp", true) -> Bitmap.CompressFormat.WEBP
                        fileName.endsWith(".png", true) -> Bitmap.CompressFormat.PNG
                        else -> Bitmap.CompressFormat.JPEG
                    }
                    val outStream = FileOutputStream(newFile)
                    bitmap.compress(format, 100, outStream)
                    outStream.close()
                    return newFile
                } catch (se: SecurityException) {
                    Log.e(TAG, "Permission denied when trying to read Downloads provider URI $originalUri - app must take persistable permissions when the URI is obtained (use ACTION_OPEN_DOCUMENT / takePersistableUriPermission).", se)
                    return null
                }
            }

            // Default path for other URIs
            val newFile = File(directory, fileName)
            val inputStream = appContext.contentResolver.openInputStream(originalUri)
            if (inputStream != null) {
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                // Determine format from file extension
                val format = when {
                    fileName.endsWith(".webp", true) -> Bitmap.CompressFormat.WEBP
                    fileName.endsWith(".png", true) -> Bitmap.CompressFormat.PNG
                    else -> Bitmap.CompressFormat.JPEG
                }
                val outStream = FileOutputStream(newFile)
                bitmap.compress(format, 100, outStream)
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
                Log.i(TAG, "Skipping deletion of original image on Android 9 or below: $uri")
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
        val db = readableDatabase
        val images = mutableListOf<Uri>()
        val orders = mutableListOf<Int>()
        val translations = mutableMapOf<Uri, Pair<String, MutableList<TextBlockInfo>>>()

        val seenUris = mutableSetOf<String>()
        val imageCursor = db.rawQuery("""
            SELECT $COLUMN_IMAGE_URI, $COLUMN_DISPLAY_ORDER, $COLUMN_IMAGE_ID 
            FROM $TABLE_IMAGES 
            WHERE $COLUMN_ROOM_ID = ? 
            ORDER BY $COLUMN_DISPLAY_ORDER
        """, arrayOf(roomId.toString()))

        while (imageCursor.moveToNext()) {
            val uriStr = imageCursor.getString(0)
            if (seenUris.contains(uriStr)) continue // Bỏ qua uri đã xuất hiện
            seenUris.add(uriStr)
            val uri = Uri.parse(uriStr)
            val order = imageCursor.getInt(1)
            val imageId = imageCursor.getLong(2)

            images.add(uri)
            orders.add(order)

            val textCursor = db.rawQuery("""
                SELECT translated_text, bounds_left, bounds_top, bounds_right, bounds_bottom, font_size, rotation, original_image_width, original_image_height, shape_type, background_type, average_background_color, original_text_color, custom_overlay_color, custom_text_color, overlay_alpha, text_boldness, overlay_saturation, text_saturation, apply_merge
                FROM translations 
                WHERE $COLUMN_IMAGE_ID = ? AND (pending_delete IS NULL OR pending_delete = 0)
            """, arrayOf(imageId.toString()))

            val textBlocks = mutableListOf<TextBlockInfo>()
            var originalText = ""
            while (textCursor.moveToNext()) {
                val translatedText = textCursor.getString(0)
                val bounds = Rect(
                    textCursor.getInt(1),
                    textCursor.getInt(2),
                    textCursor.getInt(3),
                    textCursor.getInt(4)
                )
                val fontSize = textCursor.getFloat(5)
                val rotation = if (textCursor.columnCount > 6) textCursor.getFloat(6) else 0f
                val originalImageWidth = if (textCursor.columnCount > 7) textCursor.getInt(7) else null
                val originalImageHeight = if (textCursor.columnCount > 8) textCursor.getInt(8) else null
                val shapeType = if (textCursor.columnCount > 9) textCursor.getInt(9) else 0
                val backgroundTypeOrdinal = if (textCursor.columnCount > 10) textCursor.getInt(10) else 0
                val averageBackgroundColor = if (textCursor.columnCount > 11) {
                    val value = textCursor.getInt(11)
                    if (textCursor.isNull(11)) null else value
                } else null
                val originalTextColor = if (textCursor.columnCount > 12) {
                    val value = textCursor.getInt(12)
                    if (textCursor.isNull(12)) null else value
                } else null
                val customOverlayColor = if (textCursor.columnCount > 13) {
                    val value = textCursor.getInt(13)
                    if (textCursor.isNull(13)) null else value
                } else null
                val customTextColor = if (textCursor.columnCount > 14) {
                    val value = textCursor.getInt(14)
                    if (textCursor.isNull(14)) null else value
                } else null
                
                // Log để debug màu text
                if (customTextColor != null) {
                    Log.d(TAG, "Load từ translations - customTextColor: $customTextColor (hex: ${String.format("#%08X", customTextColor)})")
                }
                if (originalTextColor != null) {
                    Log.d(TAG, "Load từ translations - originalTextColor: $originalTextColor (hex: ${String.format("#%08X", originalTextColor)})")
                }
                
                val overlayAlpha = if (textCursor.columnCount > 15) textCursor.getFloat(15) else 1.0f
                val textBoldness = if (textCursor.columnCount > 16) textCursor.getFloat(16) else 1.0f
                val overlaySaturation = if (textCursor.columnCount > 17) textCursor.getFloat(17) else 1.0f
                val textSaturation = if (textCursor.columnCount > 18) textCursor.getFloat(18) else 1.0f
                val applyMerge = if (textCursor.columnCount > 19) textCursor.getInt(19) == 1 else true
                val backgroundType = BackgroundType.values().getOrNull(backgroundTypeOrdinal) ?: BackgroundType.WHITE
                // Try to find a matching image_blocks row for more persistent styling
                try {
                    val bw = bounds.right - bounds.left
                    val bh = bounds.bottom - bounds.top
                    val blockCursor = db.rawQuery(
                        "SELECT * FROM $TABLE_IMAGE_BLOCKS WHERE $COLUMN_BLOCK_IMAGE_ID = ? AND $COLUMN_BLOCK_X = ? AND $COLUMN_BLOCK_Y = ? AND $COLUMN_BLOCK_WIDTH = ? AND $COLUMN_BLOCK_HEIGHT = ?",
                        arrayOf(imageId.toString(), bounds.left.toString(), bounds.top.toString(), bw.toString(), bh.toString())
                    )
                    if (blockCursor.moveToFirst()) {
                        // read overrides from image_blocks
                        val overlayColorBlock = if (!blockCursor.isNull(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_COLOR))) blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_COLOR)) else customOverlayColor ?: averageBackgroundColor
                        val overlayAlphaBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_ALPHA)).toFloat() } catch (e: Exception) { overlayAlpha }
                        val overlaySatBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_OVERLAY_SATURATION)).toFloat() } catch (e: Exception) { overlaySaturation }
                        
                        // Ưu tiên customTextColor từ translations, chỉ dùng textColorBlock từ image_blocks nếu khác null
                        // QUAN TRỌNG: Nếu cả customTextColor và textColorBlock đều null, 
                        // hãy giữ null để ImageViewer tính toán màu dựa trên brightness của overlay
                        // KHÔNG nên dùng originalTextColor làm fallback vì nó là màu từ OCR (ảnh gốc), 
                        // không phải màu dựa trên overlay hiện tại
                        val textColorFromImageBlock = if (!blockCursor.isNull(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_COLOR))) {
                            val color = blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_COLOR))
                            if (color != 0) color else null // Nếu là 0, coi là null (không được lưu)
                        } else null
                        val finalTextColor = customTextColor ?: textColorFromImageBlock ?: originalTextColor ?: 0xFF000000.toInt() // Mặc định màu đen
                        
                        // Log để debug màu text từ image_blocks
                        Log.d(TAG, "Load từ image_blocks - finalTextColor: $finalTextColor (hex: ${String.format("#%08X", finalTextColor ?: 0)})")
                        Log.d(TAG, "  customTextColor: $customTextColor (hex: ${String.format("#%08X", customTextColor ?: 0)})")
                        Log.d(TAG, "  textColorFromImageBlock: $textColorFromImageBlock (hex: ${String.format("#%08X", textColorFromImageBlock ?: 0)})")
                        Log.d(TAG, "  originalTextColor: $originalTextColor (hex: ${String.format("#%08X", originalTextColor ?: 0)})")
                        
                        val textBoldBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_BOLDNESS)).toFloat() } catch (e: Exception) { textBoldness }
                        val textSatBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_SATURATION)).toFloat() } catch (e: Exception) { textSaturation }
                        val rotationBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_ROTATION)).toFloat() } catch (e: Exception) { rotation }
                        val fontFamilyBlock = try { blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_FAMILY)) } catch (e: Exception) { null }
                        val finalFontFamily = if (fontFamilyBlock.isNullOrBlank()) "mto_astro_city" else fontFamilyBlock
                        val fontSizeBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_SIZE)).toFloat() } catch (e: Exception) { fontSize }
                        val borderColorBlock = if (!blockCursor.isNull(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_COLOR))) blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_COLOR)) else null
                            val borderThicknessBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_THICKNESS)).toFloat() } catch (e: Exception) { 0f }
                            val shadowColorBlock = if (!blockCursor.isNull(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_COLOR))) blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_COLOR)) else null
                            val shadowAlphaBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_ALPHA)).toFloat() } catch (e: Exception) { 1.0f }
                            val shadowRadiusBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_SHADOW_RADIUS)).toFloat() } catch (e: Exception) { 0f }
                            val lineSpacingBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_LINE_SPACING)).toFloat() } catch (e: Exception) { 1.0f }
                            // Log shadow values loaded from image_blocks for debugging

                        textBlocks.add(TextBlockInfo(
                            text = translatedText,
                            bounds = bounds,
                            fontSize = fontSizeBlock,
                            lineSpacing = lineSpacingBlock,
                            rotation = rotationBlock,
                            originalImageWidth = originalImageWidth,
                            originalImageHeight = originalImageHeight,
                            shapeType = shapeType,
                            backgroundType = backgroundType,
                            averageBackgroundColor = averageBackgroundColor,
                            originalTextColor = originalTextColor,
                            customOverlayColor = overlayColorBlock,
                                customTextColor = finalTextColor,
                            overlayAlpha = overlayAlphaBlock,
                            textBoldness = textBoldBlock,
                            overlaySaturation = overlaySatBlock,
                            textSaturation = textSatBlock,
                            customBorderColor = borderColorBlock,
                            borderThickness = borderThicknessBlock,
                                customShadowColor = shadowColorBlock,
                                shadowAlpha = shadowAlphaBlock,
                                shadowRadius = shadowRadiusBlock,
                            fontFamily = finalFontFamily,
                            applyMerge = applyMerge
                            // keep other fields default/null
                        ))
                        blockCursor.close()
                    } else {
                        blockCursor.close()
                        // No block override found; use values from translations
                        textBlocks.add(TextBlockInfo(
                            text = translatedText,
                            bounds = bounds,
                            fontSize = fontSize,
                            lineSpacing = 1.0f,
                            rotation = rotation,
                            originalImageWidth = originalImageWidth,
                            originalImageHeight = originalImageHeight,
                            shapeType = shapeType,
                            backgroundType = backgroundType,
                            averageBackgroundColor = averageBackgroundColor,
                            originalTextColor = originalTextColor,
                            customOverlayColor = customOverlayColor,
                            customTextColor = customTextColor,
                            overlayAlpha = overlayAlpha,
                            textBoldness = textBoldness,
                            overlaySaturation = overlaySaturation,
                            textSaturation = textSaturation,
                            applyMerge = applyMerge
                        ))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error while querying image_blocks for image $imageId", e)
                    // fallback to original values
                    textBlocks.add(TextBlockInfo(
                        text = translatedText,
                        bounds = bounds,
                        fontSize = fontSize,
                        lineSpacing = 1.0f,
                        rotation = rotation,
                        originalImageWidth = originalImageWidth,
                        originalImageHeight = originalImageHeight,
                        shapeType = shapeType,
                        backgroundType = backgroundType,
                        averageBackgroundColor = averageBackgroundColor,
                        originalTextColor = originalTextColor,
                        customOverlayColor = customOverlayColor,
                        customTextColor = customTextColor,
                        overlayAlpha = overlayAlpha,
                        textBoldness = textBoldness,
                        overlaySaturation = overlaySaturation,
                        textSaturation = textSaturation,
                        applyMerge = applyMerge
                    ))
                }
            }
            textCursor.close()
            if (textBlocks.isNotEmpty()) {
                translations[uri] = originalText to textBlocks
            }
        }
        imageCursor.close()

        return Triple(images, orders, translations)
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
            Log.i(TAG, "updateImageUri: imageId=$imageId -> $newUri")
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
                val ext = try { java.io.File(newUri.path ?: "").extension } catch (e: Exception) { "jpg" }
                val safeExt = if (ext.isNullOrBlank()) "jpg" else ext
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
                        if (parent != null && parent.absolutePath.startsWith(imagesDir.absolutePath) && prevFile.absolutePath != storedFile.absolutePath) {
                            prevFile.delete()
                        }
                    }
                } catch (e: Exception) { /* ignore */ }
            }

            val storedUri = Uri.fromFile(storedFile)
            val values = ContentValues().apply { put(COLUMN_IMAGE_URI, storedUri.toString()) }
            db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))

            // Optionally, try to delete original provided uri if it's a file the app can remove
            try { deleteOriginalImage(newUri) } catch (e: Exception) { /* ignore */ }

            db.setTransactionSuccessful()
            Log.i(TAG, "Replaced imageId=$imageId with $storedUri (overwrote=${storedFile != null})")
            return storedUri
        } catch (e: Exception) {
            Log.e(TAG, "replaceImageWithCopy failed for imageId=$imageId", e)
            return null
        } finally {
            try { db.endTransaction() } catch (e: Exception) { /* ignore */ }
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
                    Log.i(TAG, "Migrated imageUri for imageId=$imageId to $newUri")
                }
            }
        }
        cursor.close()
    }

    // Lấy tất cả API key cùng type
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
}