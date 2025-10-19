package com.example.ocrmanga.data.database

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
        } catch (e: Exception) {
            Log.w(TAG, "Không thể tự động thêm cột vào bảng translations", e)
        }
    }

    companion object {
        private const val DATABASE_NAME = "MangaDownloader.db"
    private const val DATABASE_VERSION = 10
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
    const val COLUMN_BLOCK_ROTATION = "rotation"
    const val COLUMN_BLOCK_FONT_FAMILY = "font_family"
    const val COLUMN_BLOCK_FONT_SIZE = "font_size"
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
                $COLUMN_BLOCK_ROTATION REAL DEFAULT 0.0,
                $COLUMN_BLOCK_FONT_FAMILY TEXT DEFAULT '',
                $COLUMN_BLOCK_FONT_SIZE REAL DEFAULT 12.0,
                FOREIGN KEY ($COLUMN_BLOCK_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
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
                         rotation: Float = 0f,
                         fontFamily: String = "",
                         fontSize: Float = 12f
    ): Long {
        val db = writableDatabase
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
            textColor?.let { put(COLUMN_BLOCK_TEXT_COLOR, it) }
            put(COLUMN_BLOCK_TEXT_BRIGHTNESS, textBrightness)
            put(COLUMN_BLOCK_TEXT_BOLDNESS, textBoldness)
            put(COLUMN_BLOCK_TEXT_SATURATION, textSaturation)
            borderColor?.let { put(COLUMN_BLOCK_BORDER_COLOR, it) }
            put(COLUMN_BLOCK_BORDER_BRIGHTNESS, borderBrightness)
            put(COLUMN_BLOCK_BORDER_BOLDNESS, borderBoldness)
            put(COLUMN_BLOCK_BORDER_THICKNESS, borderThickness)
            put(COLUMN_BLOCK_ROTATION, rotation)
            put(COLUMN_BLOCK_FONT_FAMILY, fontFamily)
            put(COLUMN_BLOCK_FONT_SIZE, fontSize)
        }
        val id = db.insert(TABLE_IMAGE_BLOCKS, null, values)
        Log.i(TAG, "Inserted image_block id=$id imageId=$imageId borderColor=${borderColor?.toString() ?: "null"} borderThickness=$borderThickness fontFamily='$fontFamily'")
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
            rotation = rotation,
            fontFamily = if (fontFamily.isNullOrBlank()) "mto_astro_city" else fontFamily,
            fontSize = fontSize
        )
    }

    fun deleteBlocksForImage(imageId: Long) {
        val db = writableDatabase
        db.delete(TABLE_IMAGE_BLOCKS, "$COLUMN_BLOCK_IMAGE_ID = ?", arrayOf(imageId.toString()))
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
                                put("original_text", originalText)
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
                                put("original_text_color", textBlock.originalTextColor)
                                put("custom_overlay_color", textBlock.customOverlayColor)
                                put("custom_text_color", textBlock.customTextColor)
                                put("overlay_alpha", textBlock.overlayAlpha)
                                put("text_boldness", textBlock.textBoldness)
                                put("overlay_saturation", textBlock.overlaySaturation)
                                put("text_saturation", textBlock.textSaturation)
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
                                            rotation = textBlock.rotation ?: 0f,
                                            fontFamily = textBlock.fontFamily,
                                            fontSize = textBlock.fontSize
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
            oldImages.filter { it.second !in imageUris }.forEach { (imageId, uri) ->
                db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                // Xóa file vật lý nếu ảnh không còn được dùng
                val file = File(Uri.parse(uri.toString()).path ?: "")
                if (file.exists()) file.delete()
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
                                    put("original_text", originalText)
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
                                    put("original_text_color", textBlock.originalTextColor)
                                    put("custom_overlay_color", textBlock.customOverlayColor)
                                    put("custom_text_color", textBlock.customTextColor)
                                    put("overlay_alpha", textBlock.overlayAlpha)
                                    put("text_boldness", textBlock.textBoldness)
                                    put("overlay_saturation", textBlock.overlaySaturation)
                                    put("text_saturation", textBlock.textSaturation)
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
                                            rotation = textBlock.rotation ?: 0f,
                                            fontFamily = textBlock.fontFamily,
                                            fontSize = textBlock.fontSize
                                        )
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
                    val imageId = imageIdMap[uriStr] ?: return@forEachIndexed
                    val imageValues = ContentValues().apply {
                        put(COLUMN_DISPLAY_ORDER, index)
                        put(COLUMN_IS_TRANSLATED, isTranslated)
                    }
                    db.update(TABLE_IMAGES, imageValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                    // Nếu có bản dịch mới, xóa bản dịch cũ và thêm lại
                    if (translatedTexts.containsKey(uri)) {
                        db.delete("translations", "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        translatedTexts[uri]?.let { (originalText, textBlocks) ->
                            try { deleteBlocksForImage(imageId) } catch (e: Exception) { /* ignore */ }
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
                                    put(COLUMN_IMAGE_ID, imageId)
                                    put("original_text", originalText)
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
                                    put("original_text_color", textBlock.originalTextColor)
                                    put("custom_overlay_color", textBlock.customOverlayColor)
                                    put("custom_text_color", textBlock.customTextColor)
                                    put("overlay_alpha", textBlock.overlayAlpha)
                                    put("text_boldness", textBlock.textBoldness)
                                    put("overlay_saturation", textBlock.overlaySaturation)
                                    put("text_saturation", textBlock.textSaturation)
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
                                            rotation = textBlock.rotation ?: 0f,
                                            fontFamily = textBlock.fontFamily,
                                            fontSize = textBlock.fontSize
                                        )
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

    private fun copyImageToInternalStorage(originalUri: Uri, directory: File, fileName: String): File? {
        return try {
            val newFile = File(directory, fileName)
            val inputStream = appContext.contentResolver.openInputStream(originalUri)
            if (inputStream != null) {
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                // Xác định định dạng từ đuôi file
                val format = when {
                    fileName.endsWith(".webp", true) -> Bitmap.CompressFormat.WEBP
                    fileName.endsWith(".png", true) -> Bitmap.CompressFormat.PNG
                    else -> Bitmap.CompressFormat.JPEG
                }
                val outStream = FileOutputStream(newFile)
                bitmap.compress(format, 100, outStream)
                outStream.close()
                newFile
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy & compress image $originalUri", e)
            null
        }
    }

    private fun deleteOriginalImage(uri: Uri) {
        try {
            val contentResolver = appContext.contentResolver
            val scheme = uri.scheme
            when {
                // Nếu là MediaStore uri (content://media/external...)
                scheme == "content" && uri.authority?.contains("media") == true -> {
                    contentResolver.delete(uri, null, null)
                }
                // Nếu là Document uri (SAF)
                scheme == "content" && uri.authority?.contains("documents") == true -> {
                    try {
                        DocumentsContract.deleteDocument(contentResolver, uri)
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
                SELECT original_text, translated_text, bounds_left, bounds_top, bounds_right, bounds_bottom, font_size, rotation, original_image_width, original_image_height, shape_type, background_type, average_background_color, original_text_color, custom_overlay_color, custom_text_color, overlay_alpha, text_boldness, overlay_saturation, text_saturation
                FROM translations 
                WHERE $COLUMN_IMAGE_ID = ?
            """, arrayOf(imageId.toString()))

            val textBlocks = mutableListOf<TextBlockInfo>()
            var originalText = ""
            while (textCursor.moveToNext()) {
                originalText = textCursor.getString(0) ?: ""
                val translatedText = textCursor.getString(1)
                val bounds = Rect(
                    textCursor.getInt(2),
                    textCursor.getInt(3),
                    textCursor.getInt(4),
                    textCursor.getInt(5)
                )
                val fontSize = textCursor.getFloat(6)
                val rotation = if (textCursor.columnCount > 7) textCursor.getFloat(7) else 0f
                val originalImageWidth = if (textCursor.columnCount > 8) textCursor.getInt(8) else null
                val originalImageHeight = if (textCursor.columnCount > 9) textCursor.getInt(9) else null
                val shapeType = if (textCursor.columnCount > 10) textCursor.getInt(10) else 0
                val backgroundTypeOrdinal = if (textCursor.columnCount > 11) textCursor.getInt(11) else 0
                val averageBackgroundColor = if (textCursor.columnCount > 12) {
                    val value = textCursor.getInt(12)
                    if (textCursor.isNull(12)) null else value
                } else null
                val originalTextColor = if (textCursor.columnCount > 13) {
                    val value = textCursor.getInt(13)
                    if (textCursor.isNull(13)) null else value
                } else null
                val customOverlayColor = if (textCursor.columnCount > 14) {
                    val value = textCursor.getInt(14)
                    if (textCursor.isNull(14)) null else value
                } else null
                val customTextColor = if (textCursor.columnCount > 15) {
                    val value = textCursor.getInt(15)
                    if (textCursor.isNull(15)) null else value
                } else null
                val overlayAlpha = if (textCursor.columnCount > 16) textCursor.getFloat(16) else 1.0f
                val textBoldness = if (textCursor.columnCount > 17) textCursor.getFloat(17) else 1.0f
                val overlaySaturation = if (textCursor.columnCount > 18) textCursor.getFloat(18) else 1.0f
                val textSaturation = if (textCursor.columnCount > 19) textCursor.getFloat(19) else 1.0f
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
                        val textColorBlock = if (!blockCursor.isNull(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_COLOR))) blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_COLOR)) else customTextColor ?: originalTextColor
                        val textBoldBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_BOLDNESS)).toFloat() } catch (e: Exception) { textBoldness }
                        val textSatBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_TEXT_SATURATION)).toFloat() } catch (e: Exception) { textSaturation }
                        val rotationBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_ROTATION)).toFloat() } catch (e: Exception) { rotation }
                        val fontFamilyBlock = try { blockCursor.getString(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_FAMILY)) } catch (e: Exception) { null }
                        val finalFontFamily = if (fontFamilyBlock.isNullOrBlank()) "mto_astro_city" else fontFamilyBlock
                        val fontSizeBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_FONT_SIZE)).toFloat() } catch (e: Exception) { fontSize }
                        val borderColorBlock = if (!blockCursor.isNull(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_COLOR))) blockCursor.getInt(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_COLOR)) else null
                        val borderThicknessBlock = try { blockCursor.getDouble(blockCursor.getColumnIndexOrThrow(COLUMN_BLOCK_BORDER_THICKNESS)).toFloat() } catch (e: Exception) { 0f }
                        textBlocks.add(TextBlockInfo(
                            text = translatedText,
                            bounds = bounds,
                            fontSize = fontSizeBlock,
                            rotation = rotationBlock,
                            originalImageWidth = originalImageWidth,
                            originalImageHeight = originalImageHeight,
                            shapeType = shapeType,
                            backgroundType = backgroundType,
                            averageBackgroundColor = averageBackgroundColor,
                            originalTextColor = originalTextColor,
                            customOverlayColor = overlayColorBlock,
                            customTextColor = textColorBlock,
                            overlayAlpha = overlayAlphaBlock,
                            textBoldness = textBoldBlock,
                            overlaySaturation = overlaySatBlock,
                            textSaturation = textSatBlock,
                            customBorderColor = borderColorBlock,
                            borderThickness = borderThicknessBlock,
                            fontFamily = finalFontFamily,
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
                            textSaturation = textSaturation
                        ))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error while querying image_blocks for image $imageId", e)
                    // fallback to original values
                    textBlocks.add(TextBlockInfo(
                        text = translatedText,
                        bounds = bounds,
                        fontSize = fontSize,
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
                        textSaturation = textSaturation
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