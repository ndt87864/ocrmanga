package com.example.ocrmanga.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * Lớp định nghĩa Schema của cơ sở dữ liệu MangaDownloader.
 * Quản lý các lệnh khởi tạo bảng, migrations và cập nhật schema.
 */
internal object DatabaseSchema {

    private const val TAG = "DatabaseSchema"
    const val DATABASE_NAME = "MangaDownloader.db"
    const val DATABASE_VERSION = 32

    // Manga Rooms table
    const val TABLE_ROOMS = "manga_rooms"
    const val COLUMN_ROOM_ID = "room_id"
    const val COLUMN_TITLE = "title"
    const val COLUMN_COVER_URI = "cover_uri"

    // Images table
    const val TABLE_IMAGES = "images"
    const val COLUMN_IMAGE_ID = "image_id"
    // Sử dụng hằng số COLUMN_ROOM_ID của table rooms bên trên
    const val COLUMN_IMAGE_URI = "image_uri"
    const val COLUMN_DISPLAY_ORDER = "display_order"
    const val COLUMN_IS_TRANSLATED = "is_translated"

    // Translations table
    const val TABLE_TRANSLATIONS = "translations"
    const val COLUMN_TRANSLATION_ID = "text_id"
    const val COLUMN_TRANSLATED_TEXT = "translated_text"
    const val COLUMN_ORIGINAL_TEXT = "original_text"
    const val COLUMN_PENDING_DELETE = "pending_delete"
    const val COLUMN_APPLY_MERGE = "apply_merge"
    const val COLUMN_X = "x"
    const val COLUMN_Y = "y"
    const val COLUMN_WIDTH = "width"
    const val COLUMN_HEIGHT = "height"
    const val COLUMN_ORIGINAL_TEXT_COLOR = "original_text_color"

    // API Keys table
    const val TABLE_API_KEYS = "api_keys"
    const val COLUMN_API_KEY_ID = "api_key_id"
    const val COLUMN_API_KEY_VALUE = "api_key_value"
    const val COLUMN_API_KEY_TYPE = "type"
    const val COLUMN_CREATED_DATE = "created_date"
    const val COLUMN_UPDATED_DATE = "updated_date"
    const val COLUMN_IS_ACTIVE = "is_active"
    const val COLUMN_ALLOWED_MODELS = "allowed_models"

    // Room settings table
    const val TABLE_ROOM_SETTINGS = "room_settings"
    const val COLUMN_SETTING_ROOM_ID = "room_id"
    const val COLUMN_AUTO_TRANSLATE_NEW_IMAGES = "auto_translate_new_images"
    const val COLUMN_ANCIENT_TRANSLATION_ENABLED = "ancient_translation_enabled"

    // Image blocks table
    const val TABLE_IMAGE_BLOCKS = "image_blocks"
    const val COLUMN_BLOCK_ID = "block_id"
    const val COLUMN_BLOCK_IMAGE_ID = "image_id"
    const val COLUMN_BLOCK_X = "x"
    const val COLUMN_BLOCK_Y = "y"
    const val COLUMN_BLOCK_WIDTH = "width"
    const val COLUMN_BLOCK_HEIGHT = "height"
    const val COLUMN_BLOCK_OVERLAY_TYPE = "overlay_type"
    const val COLUMN_BLOCK_OVERLAY_COLOR = "overlay_color"
    const val COLUMN_BLOCK_OVERLAY_BRIGHTNESS = "overlay_brightness"
    const val COLUMN_BLOCK_OVERLAY_ALPHA = "overlay_alpha"
    const val COLUMN_BLOCK_OVERLAY_SATURATION = "overlay_saturation"
    const val COLUMN_BLOCK_OVERLAY_INSET = "overlay_inset"
    const val COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL = "overlay_inset_horizontal"
    const val COLUMN_BLOCK_OVERLAY_INSET_VERTICAL = "overlay_inset_vertical"
    const val COLUMN_BLOCK_OVERLAY_ROTATION = "overlay_rotation"
    const val COLUMN_BLOCK_TEXT_COLOR = "text_color"
    const val COLUMN_BLOCK_TEXT_BRIGHTNESS = "text_brightness"
    const val COLUMN_BLOCK_TEXT_BOLDNESS = "text_boldness"
    const val COLUMN_BLOCK_TEXT_SATURATION = "text_saturation"
    const val COLUMN_BLOCK_BORDER_COLOR = "border_color"
    const val COLUMN_BLOCK_BORDER_BRIGHTNESS = "border_brightness"
    const val COLUMN_BLOCK_BORDER_BOLDNESS = "border_boldness"
    const val COLUMN_BLOCK_BORDER_THICKNESS = "border_thickness"
    const val COLUMN_BLOCK_SHADOW_COLOR = "shadow_color"
    const val COLUMN_BLOCK_SHADOW_ALPHA = "shadow_alpha"
    const val COLUMN_BLOCK_SHADOW_RADIUS = "shadow_radius"
    const val COLUMN_BLOCK_ROTATION = "rotation"
    const val COLUMN_BLOCK_FONT_FAMILY = "font_family"
    const val COLUMN_BLOCK_FONT_SIZE = "font_size"
    const val COLUMN_BLOCK_LINE_SPACING = "line_spacing"
    const val COLUMN_BLOCK_TEXT_ALIGN = "text_align"
    const val COLUMN_BLOCK_TEXT_GRADIENT_COLORS = "text_gradient_colors"
    const val COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS = "text_gradient_offsets"
    const val COLUMN_BLOCK_TEXT_GRADIENT_TYPE = "text_gradient_type"
    const val COLUMN_BLOCK_ORIGINAL_WIDTH = "original_width"
    const val COLUMN_BLOCK_ORIGINAL_HEIGHT = "original_height"

    // change_images table
    const val TABLE_CHANGE_IMAGES = "change_images"
    const val COLUMN_CHANGE_IMAGE_ID = "change_image_id"
    const val COLUMN_CHANGE_IMAGE_IMAGE_ID = "image_id"
    const val COLUMN_CHANGE_IMAGE_ROOM_ID = "room_id"
    const val COLUMN_CHANGE_IMAGE_FLAG = "is_changed"

    /** Tạo các bảng cơ sở dữ liệu ban đầu. */
    fun createTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_ROOMS (
                $COLUMN_ROOM_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT,
                $COLUMN_COVER_URI TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_IMAGES (
                $COLUMN_IMAGE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_ROOM_ID INTEGER,
                $COLUMN_IMAGE_URI TEXT,
                $COLUMN_DISPLAY_ORDER INTEGER,
                $COLUMN_IS_TRANSLATED INTEGER DEFAULT 0,
                FOREIGN KEY ($COLUMN_ROOM_ID) REFERENCES $TABLE_ROOMS($COLUMN_ROOM_ID)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_TRANSLATIONS (
                $COLUMN_TRANSLATION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_IMAGE_ID INTEGER,
                $COLUMN_TRANSLATED_TEXT TEXT,
                $COLUMN_ORIGINAL_TEXT TEXT,
                $COLUMN_PENDING_DELETE INTEGER DEFAULT 0,
                $COLUMN_APPLY_MERGE INTEGER DEFAULT 1,
                $COLUMN_X INTEGER DEFAULT 0,
                $COLUMN_Y INTEGER DEFAULT 0,
                $COLUMN_WIDTH INTEGER DEFAULT 0,
                $COLUMN_HEIGHT INTEGER DEFAULT 0,
                $COLUMN_ORIGINAL_TEXT_COLOR INTEGER,
                FOREIGN KEY ($COLUMN_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_API_KEYS (
                $COLUMN_API_KEY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_API_KEY_VALUE TEXT UNIQUE,
                $COLUMN_CREATED_DATE TEXT,
                $COLUMN_UPDATED_DATE TEXT,
                $COLUMN_IS_ACTIVE INTEGER DEFAULT 1,
                $COLUMN_API_KEY_TYPE TEXT DEFAULT 'default',
                $COLUMN_ALLOWED_MODELS TEXT DEFAULT ''
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_ROOM_SETTINGS (
                $COLUMN_SETTING_ROOM_ID INTEGER PRIMARY KEY,
                $COLUMN_AUTO_TRANSLATE_NEW_IMAGES INTEGER DEFAULT 1,
                $COLUMN_ANCIENT_TRANSLATION_ENABLED INTEGER DEFAULT 0,
                FOREIGN KEY ($COLUMN_SETTING_ROOM_ID) REFERENCES $TABLE_ROOMS($COLUMN_ROOM_ID)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_IMAGE_BLOCKS (
                $COLUMN_BLOCK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_BLOCK_IMAGE_ID INTEGER,
                $COLUMN_BLOCK_X INTEGER,
                $COLUMN_BLOCK_Y INTEGER,
                $COLUMN_BLOCK_WIDTH INTEGER,
                $COLUMN_BLOCK_HEIGHT INTEGER,
                $COLUMN_BLOCK_OVERLAY_TYPE INTEGER DEFAULT 0,
                $COLUMN_BLOCK_OVERLAY_COLOR INTEGER,
                $COLUMN_BLOCK_OVERLAY_BRIGHTNESS REAL DEFAULT 1.0,
                $COLUMN_BLOCK_OVERLAY_ALPHA REAL DEFAULT 1.0,
                $COLUMN_BLOCK_OVERLAY_SATURATION REAL DEFAULT 1.0,
                $COLUMN_BLOCK_OVERLAY_INSET REAL DEFAULT 0.0,
                $COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL REAL DEFAULT 0.0,
                $COLUMN_BLOCK_OVERLAY_INSET_VERTICAL REAL DEFAULT 0.0,
                $COLUMN_BLOCK_OVERLAY_ROTATION REAL,
                $COLUMN_BLOCK_TEXT_COLOR INTEGER DEFAULT -16777216,
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
                $COLUMN_BLOCK_LINE_SPACING REAL DEFAULT 1.0,
                $COLUMN_BLOCK_TEXT_ALIGN TEXT DEFAULT 'CENTER',
                $COLUMN_BLOCK_TEXT_GRADIENT_COLORS TEXT,
                $COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS TEXT,
                $COLUMN_BLOCK_TEXT_GRADIENT_TYPE INTEGER DEFAULT 0,
                $COLUMN_BLOCK_ORIGINAL_WIDTH INTEGER,
                $COLUMN_BLOCK_ORIGINAL_HEIGHT INTEGER,
                FOREIGN KEY ($COLUMN_BLOCK_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_CHANGE_IMAGES (
                $COLUMN_CHANGE_IMAGE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CHANGE_IMAGE_IMAGE_ID INTEGER,
                $COLUMN_CHANGE_IMAGE_ROOM_ID INTEGER,
                $COLUMN_CHANGE_IMAGE_FLAG INTEGER DEFAULT 0,
                FOREIGN KEY ($COLUMN_CHANGE_IMAGE_IMAGE_ID) REFERENCES $TABLE_IMAGES($COLUMN_IMAGE_ID)
            )
        """.trimIndent())

        createIndices(db)
    }

    /** Tạo các index phục vụ tìm kiếm nhanh. */
    fun createIndices(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_id ON $TABLE_IMAGES($COLUMN_ROOM_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_image_id ON $TABLE_TRANSLATIONS($COLUMN_IMAGE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_pending_delete ON $TABLE_TRANSLATIONS($COLUMN_IMAGE_ID, $COLUMN_PENDING_DELETE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_image_blocks_image_id ON $TABLE_IMAGE_BLOCKS($COLUMN_BLOCK_IMAGE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_change_images_image_id ON $TABLE_CHANGE_IMAGES($COLUMN_CHANGE_IMAGE_IMAGE_ID)")
    }

    /** Thực hiện nâng cấp database giữa các phiên bản. */
    fun upgradeDatabase(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 32) {
            try {
                db.execSQL("ALTER TABLE $TABLE_API_KEYS ADD COLUMN $COLUMN_ALLOWED_MODELS TEXT NOT NULL DEFAULT ''")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột allowed_models vào api_keys", e)
            }
        }
        if (oldVersion < 31) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_uri ON $TABLE_IMAGES($COLUMN_IMAGE_URI)")
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_IMAGES ADD COLUMN $COLUMN_IS_TRANSLATED INTEGER DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_ROOMS ADD COLUMN $COLUMN_TITLE TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN original_image_width INTEGER")
            db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN original_image_height INTEGER")
            try {
                val cursor = db.rawQuery("PRAGMA table_info($TABLE_TRANSLATIONS)", null)
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
                    db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN rotation REAL DEFAULT 0")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể kiểm tra/thêm cột rotation cho translations", e)
            }
            try {
                db.execSQL("UPDATE $TABLE_TRANSLATIONS SET rotation = 0 WHERE rotation IS NULL")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể update rotation cho các bản ghi cũ", e)
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_API_KEYS ( $COLUMN_API_KEY_VALUE TEXT PRIMARY KEY )")
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE $TABLE_API_KEYS ADD COLUMN $COLUMN_API_KEY_TYPE TEXT NOT NULL DEFAULT 'default'")
            } catch (e: Exception) { /* ignore */ }
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN shape_type INTEGER DEFAULT 0")
                db.execSQL("UPDATE $TABLE_TRANSLATIONS SET shape_type = 0 WHERE shape_type IS NULL")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột shape_type vào translations", e)
            }
        }
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN background_type INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN average_background_color INTEGER")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm các cột màu nền vào translations", e)
            }
        }
        if (oldVersion < 7) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN original_text_color INTEGER")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột original_text_color vào translations", e)
            }
        }
        if (oldVersion < 8) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN custom_overlay_color INTEGER")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN custom_text_color INTEGER")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN overlay_alpha REAL DEFAULT 1.0")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN text_boldness REAL DEFAULT 1.0")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN overlay_saturation REAL DEFAULT 1.0")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN text_saturation REAL DEFAULT 1.0")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm các cột màu sắc tùy chỉnh vào translations", e)
            }
        }
        if (oldVersion < 15) {
            try {
                // image table temp original_text column (deprecated in v27)
                db.execSQL("ALTER TABLE $TABLE_IMAGES ADD COLUMN original_text TEXT")
                try {
                    db.execSQL("""
                        UPDATE $TABLE_IMAGES
                        SET original_text = (
                            SELECT original_text FROM $TABLE_TRANSLATIONS 
                            WHERE $TABLE_TRANSLATIONS.$COLUMN_IMAGE_ID = $TABLE_IMAGES.$COLUMN_IMAGE_ID 
                            LIMIT 1
                        )
                        WHERE EXISTS (
                            SELECT 1 FROM $TABLE_TRANSLATIONS 
                            WHERE $TABLE_TRANSLATIONS.$COLUMN_IMAGE_ID = $TABLE_IMAGES.$COLUMN_IMAGE_ID
                        )
                    """)
                } catch (e: Exception) { /* ignore */ }
            } catch (e: Exception) { /* ignore */ }
        }
        if (oldVersion < 10) {
            try {
                db.execSQL("""
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
                """)
                try {
                    val cursor = db.rawQuery("SELECT * FROM $TABLE_TRANSLATIONS", null)
                    while (cursor.moveToNext()) {
                        fun idx(n: String) = try { cursor.getColumnIndex(n) } catch (e: Exception) { -1 }
                        val imgIdIdx = idx(COLUMN_IMAGE_ID)
                        if (imgIdIdx < 0) continue
                        val imageId = cursor.getLong(imgIdIdx)
                        val left = try { if (cursor.isNull(idx("bounds_left"))) null else cursor.getInt(idx("bounds_left")) } catch (e: Exception) { null } ?: continue
                        val top = try { if (cursor.isNull(idx("bounds_top"))) null else cursor.getInt(idx("bounds_top")) } catch (e: Exception) { null } ?: continue
                        val right = try { if (cursor.isNull(idx("bounds_right"))) null else cursor.getInt(idx("bounds_right")) } catch (e: Exception) { null } ?: continue
                        val bottom = try { if (cursor.isNull(idx("bounds_bottom"))) null else cursor.getInt(idx("bounds_bottom")) } catch (e: Exception) { null } ?: continue
                        val bw = right - left
                        val bh = bottom - top
                        val customOverlayColor = try { if (cursor.isNull(idx("custom_overlay_color"))) null else cursor.getInt(idx("custom_overlay_color")) } catch (e: Exception) { null }
                        val avgBgColor = try { if (cursor.isNull(idx("average_background_color"))) null else cursor.getInt(idx("average_background_color")) } catch (e: Exception) { null }
                        val overlayColor = customOverlayColor ?: avgBgColor
                        val customTextColor = try { if (cursor.isNull(idx("custom_text_color"))) null else cursor.getInt(idx("custom_text_color")) } catch (e: Exception) { null }
                        val origTextColor = try { if (cursor.isNull(idx("original_text_color"))) null else cursor.getInt(idx("original_text_color")) } catch (e: Exception) { null }
                        val textColor = customTextColor ?: origTextColor
                        val overlayAlpha = try { cursor.getFloat(idx("overlay_alpha")) } catch (e: Exception) { 1.0f }
                        val overlaySat = try { cursor.getFloat(idx("overlay_saturation")) } catch (e: Exception) { 1.0f }
                        val textBold = try { cursor.getFloat(idx("text_boldness")) } catch (e: Exception) { 1.0f }
                        val textSat = try { cursor.getFloat(idx("text_saturation")) } catch (e: Exception) { 1.0f }
                        val rotation = try { cursor.getFloat(idx("rotation")) } catch (e: Exception) { 0f }
                        val fontSize = try { cursor.getFloat(idx("font_size")) } catch (e: Exception) { 12f }
                        val shapeType = try { cursor.getInt(idx("shape_type")) } catch (e: Exception) { 0 }
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
        if (oldVersion < 11) {
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_CHANGE_IMAGES (
                        $COLUMN_CHANGE_IMAGE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COLUMN_CHANGE_IMAGE_IMAGE_ID INTEGER NOT NULL,
                        $COLUMN_CHANGE_IMAGE_ROOM_ID INTEGER NOT NULL,
                        $COLUMN_CHANGE_IMAGE_FLAG INTEGER NOT NULL DEFAULT 0,
                        UNIQUE($COLUMN_CHANGE_IMAGE_IMAGE_ID)
                    )
                """)
            } catch (e: Exception) {
                Log.w(TAG, "Không thể tạo bảng $TABLE_CHANGE_IMAGES", e)
            }
        }
        if (oldVersion < 12) {
            try {
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_LINE_SPACING REAL DEFAULT 1.0")
            } catch (e: Exception) { /* ignore */ }
        }
        if (oldVersion < 21) {
            try {
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_TEXT_ALIGN TEXT DEFAULT 'CENTER'")
            } catch (e: Exception) { /* ignore */ }
        }
        if (oldVersion < 14) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN $COLUMN_PENDING_DELETE INTEGER DEFAULT 0")
            } catch (e: Exception) { /* ignore */ }
        }
        if (oldVersion < 15) {
            try {
                createIndices(db)
            } catch (e: Exception) {
                Log.w(TAG, "Không thể tạo indexes", e)
            }
        }
        if (oldVersion < 16) {
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_ROOM_SETTINGS (
                        $COLUMN_SETTING_ROOM_ID INTEGER PRIMARY KEY,
                        $COLUMN_AUTO_TRANSLATE_NEW_IMAGES INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY ($COLUMN_SETTING_ROOM_ID) REFERENCES $TABLE_ROOMS($COLUMN_ROOM_ID) ON DELETE CASCADE
                    )
                """)
                db.execSQL("""
                    INSERT OR IGNORE INTO $TABLE_ROOM_SETTINGS ($COLUMN_SETTING_ROOM_ID, $COLUMN_AUTO_TRANSLATE_NEW_IMAGES)
                    SELECT $COLUMN_ROOM_ID, 1 FROM $TABLE_ROOMS
                """)
            } catch (e: Exception) {
                Log.w(TAG, "Không thể tạo bảng $TABLE_ROOM_SETTINGS", e)
            }
        }
        if (oldVersion < 17) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN overlay_rotation REAL")
            } catch (e: Exception) { /* ignore */ }
        }
        if (oldVersion < 18) {
            try {
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
                db.execSQL("""
                    INSERT INTO translations_new (text_id, $COLUMN_IMAGE_ID, translated_text, pending_delete, apply_merge)
                    SELECT text_id, $COLUMN_IMAGE_ID, translated_text, COALESCE(pending_delete, 0), COALESCE(apply_merge, 1)
                    FROM $TABLE_TRANSLATIONS
                """)
                db.execSQL("DROP TABLE $TABLE_TRANSLATIONS")
                db.execSQL("ALTER TABLE translations_new RENAME TO $TABLE_TRANSLATIONS")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_image_id ON $TABLE_TRANSLATIONS($COLUMN_IMAGE_ID)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_translations_pending_delete ON $TABLE_TRANSLATIONS($COLUMN_IMAGE_ID, $COLUMN_PENDING_DELETE)")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi migrate bảng translations", e)
            }
        }
        if (oldVersion < 19) {
            try {
                db.execSQL("ALTER TABLE $TABLE_ROOM_SETTINGS ADD COLUMN $COLUMN_ANCIENT_TRANSLATION_ENABLED INTEGER NOT NULL DEFAULT 0")
                try {
                    db.execSQL("UPDATE $TABLE_ROOM_SETTINGS SET $COLUMN_ANCIENT_TRANSLATION_ENABLED = 0 WHERE $COLUMN_ANCIENT_TRANSLATION_ENABLED IS NULL")
                } catch (e: Exception) { /* ignore */ }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm ancient_translation_enabled", e)
            }
        }
        if (oldVersion < 22) {
            try {
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_TEXT_GRADIENT_COLORS TEXT")
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS TEXT")
                db.execSQL("ALTER TABLE $TABLE_IMAGE_BLOCKS ADD COLUMN $COLUMN_BLOCK_TEXT_GRADIENT_TYPE INTEGER DEFAULT 0")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm cột gradient", e)
            }
        }
        if (oldVersion < 25) {
            try {
                val cursor = db.rawQuery("PRAGMA table_info($TABLE_API_KEYS)", null)
                var hasQuota = false
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (name == "remaining_quota" || name == "consecutive_failures") {
                        hasQuota = true
                        break
                    }
                }
                cursor.close()
                if (hasQuota) {
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
                    db.execSQL("""
                        INSERT INTO api_keys_new ($COLUMN_API_KEY_VALUE, $COLUMN_API_KEY_TYPE, created_date, updated_date, is_active)
                        SELECT $COLUMN_API_KEY_VALUE, $COLUMN_API_KEY_TYPE,
                               COALESCE(created_date, '2025-07-15'),
                               COALESCE(updated_date, '2025-07-15'),
                               COALESCE(is_active, 1)
                        FROM $TABLE_API_KEYS
                    """)
                    db.execSQL("DROP TABLE $TABLE_API_KEYS")
                    db.execSQL("ALTER TABLE api_keys_new RENAME TO $TABLE_API_KEYS")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi dọn dẹp api_keys", e)
            }
        }
        if (oldVersion < 26) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN $COLUMN_ORIGINAL_TEXT TEXT")
            } catch (e: Exception) {
                Log.w(TAG, "Không thể thêm original_text vào translations", e)
            }
        }
        if (oldVersion < 27 || oldVersion < 28) {
            try {
                db.execSQL("PRAGMA defer_foreign_keys = ON;")
                db.execSQL("DROP TABLE IF EXISTS images_new")
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
                db.execSQL("""
                    INSERT INTO images_new (image_id, room_id, image_uri, display_order, is_translated)
                    SELECT image_id, room_id, image_uri, display_order, is_translated FROM $TABLE_IMAGES
                """)
                db.execSQL("DROP TABLE $TABLE_IMAGES")
                db.execSQL("ALTER TABLE images_new RENAME TO $TABLE_IMAGES")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_id ON $TABLE_IMAGES($COLUMN_ROOM_ID)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_room_order ON $TABLE_IMAGES($COLUMN_ROOM_ID, $COLUMN_DISPLAY_ORDER)")
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi migration version 27/28", e)
            }
        }
        if (oldVersion < 29) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN $COLUMN_X INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN $COLUMN_Y INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN $COLUMN_WIDTH INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN $COLUMN_HEIGHT INTEGER DEFAULT 0")
                db.execSQL("""
                    UPDATE $TABLE_TRANSLATIONS 
                    SET 
                        x = (SELECT x FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = $TABLE_TRANSLATIONS.$COLUMN_TRANSLATION_ID),
                        y = (SELECT y FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = $TABLE_TRANSLATIONS.$COLUMN_TRANSLATION_ID),
                        width = (SELECT width FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = $TABLE_TRANSLATIONS.$COLUMN_TRANSLATION_ID),
                        height = (SELECT height FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = $TABLE_TRANSLATIONS.$COLUMN_TRANSLATION_ID)
                    WHERE EXISTS (SELECT 1 FROM $TABLE_IMAGE_BLOCKS WHERE $TABLE_IMAGE_BLOCKS.$COLUMN_BLOCK_ID = $TABLE_TRANSLATIONS.$COLUMN_TRANSLATION_ID)
                """)
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi migration version 29", e)
            }
        }
        if (oldVersion < 30) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSLATIONS ADD COLUMN original_font_size REAL DEFAULT -1")
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi migration version 30", e)
            }
        }
    }
}
