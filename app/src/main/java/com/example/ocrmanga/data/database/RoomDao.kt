package com.example.ocrmanga.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.ocrmanga.data.models.ApiKeyInfo
import com.example.ocrmanga.utils.AppLogger
import java.io.File

/**
 * DAO xử lý CRUD cho các bảng `manga_rooms`, `images`, `api_keys`, và `room_settings`.
 *
 * **Trách nhiệm:**
 * - Quản lý vòng đời của room (tạo, lấy, xóa, đổi tên).
 * - Quản lý hình ảnh trong room (lấy URI, đếm, cập nhật URI).
 * - CRUD API keys.
 * - Đọc/ghi cài đặt của room.
 */
internal class RoomDao(private val dbHelper: DatabaseHelper) {

    private val db: SQLiteDatabase get() = dbHelper.writableDatabase

    // ── ROOMS ──────────────────────────────────────────────────────────────

    /** Trả về danh sách tất cả rooms: (roomId, title, coverUri). */
    fun getAllRooms(): List<Triple<Long, String, Uri>> {
        val cursor = dbHelper.readableDatabase.rawQuery(
            "SELECT ${DatabaseHelper.COLUMN_ROOM_ID}, ${DatabaseHelper.COLUMN_TITLE}, " +
                "${DatabaseHelper.COLUMN_COVER_URI} FROM ${DatabaseHelper.TABLE_ROOMS} " +
                "ORDER BY ${DatabaseHelper.COLUMN_ROOM_ID} DESC",
            null
        )
        val rooms = mutableListOf<Triple<Long, String, Uri>>()
        while (cursor.moveToNext()) {
            rooms.add(Triple(cursor.getLong(0), cursor.getString(1), Uri.parse(cursor.getString(2))))
        }
        cursor.close()
        return rooms
    }

    /** Trả về tiêu đề room theo roomId. */
    fun getRoomTitle(roomId: Long): String? {
        val cursor = dbHelper.readableDatabase.rawQuery(
            "SELECT ${DatabaseHelper.COLUMN_TITLE} FROM ${DatabaseHelper.TABLE_ROOMS} WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ?",
            arrayOf(roomId.toString())
        )
        return if (cursor.moveToFirst()) cursor.getString(0).also { cursor.close() }
        else { cursor.close(); null }
    }

    /** Cập nhật tiêu đề room. */
    fun updateRoomTitle(roomId: Long, newTitle: String) {
        val values = ContentValues().apply { put(DatabaseHelper.COLUMN_TITLE, newTitle) }
        db.update(DatabaseHelper.TABLE_ROOMS, values, "${DatabaseHelper.COLUMN_ROOM_ID}=?", arrayOf(roomId.toString()))
    }

    /**
     * Xóa toàn bộ room: translations → image_blocks → change_images → images → room record → file vật lý.
     */
    fun deleteRoom(roomId: Long) {
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM translations WHERE ${DatabaseHelper.COLUMN_IMAGE_ID} IN " +
                    "(SELECT ${DatabaseHelper.COLUMN_IMAGE_ID} FROM ${DatabaseHelper.TABLE_IMAGES} " +
                    "WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ?)",
                arrayOf(roomId.toString())
            )
            db.execSQL(
                "DELETE FROM ${DatabaseHelper.TABLE_IMAGE_BLOCKS} WHERE ${DatabaseHelper.COLUMN_BLOCK_IMAGE_ID} IN " +
                    "(SELECT ${DatabaseHelper.COLUMN_IMAGE_ID} FROM ${DatabaseHelper.TABLE_IMAGES} " +
                    "WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ?)",
                arrayOf(roomId.toString())
            )
            db.delete(DatabaseHelper.TABLE_CHANGE_IMAGES,
                "${DatabaseHelper.COLUMN_CHANGE_IMAGE_ROOM_ID} = ?", arrayOf(roomId.toString()))
            db.delete(DatabaseHelper.TABLE_IMAGES, "${DatabaseHelper.COLUMN_ROOM_ID} = ?", arrayOf(roomId.toString()))
            db.delete(DatabaseHelper.TABLE_ROOMS, "${DatabaseHelper.COLUMN_ROOM_ID} = ?", arrayOf(roomId.toString()))

            val imagesDir = File(dbHelper.appContext.getExternalFilesDir(null), "images/$roomId")
            if (imagesDir.exists()) imagesDir.deleteRecursively()

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            AppLogger.e("RoomDao", "Error deleting room $roomId", e)
        } finally {
            db.endTransaction()
        }
    }

    // ── IMAGES ─────────────────────────────────────────────────────────────

    /**
     * Xóa một image khỏi room: xóa translations, blocks, change record, DB record, file vật lý.
     * Ném exception nếu lỗi (để caller rollback nếu cần).
     */
    fun deleteImageFromRoom(imageId: Long) {
        db.beginTransaction()
        try {
            var imageUri: String? = null
            val cursor = db.rawQuery(
                "SELECT ${DatabaseHelper.COLUMN_IMAGE_URI} FROM ${DatabaseHelper.TABLE_IMAGES} WHERE ${DatabaseHelper.COLUMN_IMAGE_ID} = ?",
                arrayOf(imageId.toString())
            )
            if (cursor.moveToFirst()) imageUri = cursor.getString(0)
            cursor.close()

            db.delete("translations", "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
            db.delete(DatabaseHelper.TABLE_IMAGE_BLOCKS, "${DatabaseHelper.COLUMN_BLOCK_IMAGE_ID} = ?", arrayOf(imageId.toString()))
            try { db.delete(DatabaseHelper.TABLE_CHANGE_IMAGES, "${DatabaseHelper.COLUMN_CHANGE_IMAGE_ID} = ?", arrayOf(imageId.toString())) } catch (e: Exception) { /* ignore */ }
            db.delete(DatabaseHelper.TABLE_IMAGES, "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))

            db.setTransactionSuccessful()

            imageUri?.let {
                try {
                    val file = File(Uri.parse(it).path ?: "")
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    AppLogger.w("RoomDao", "deleteImageFromRoom: Failed to delete physical file $it", e)
                }
            }
        } catch (e: Exception) {
            AppLogger.e("RoomDao", "Error deleting image from room: imageId=$imageId", e)
            throw e
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Xóa TẤT CẢ translations + image_blocks của một image (dùng khi OFF dịch).
     * Đặt `is_translated = 0`.
     */
    fun deleteAllTranslationsForImage(imageId: Long) {
        db.beginTransaction()
        try {
            db.delete("translations", "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
            db.delete(DatabaseHelper.TABLE_IMAGE_BLOCKS, "${DatabaseHelper.COLUMN_BLOCK_IMAGE_ID} = ?", arrayOf(imageId.toString()))
            val values = ContentValues().apply { put(DatabaseHelper.COLUMN_IS_TRANSLATED, 0) }
            db.update(DatabaseHelper.TABLE_IMAGES, values, "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            AppLogger.e("RoomDao", "Error deleting all translations for imageId=$imageId", e)
        } finally {
            db.endTransaction()
        }
    }

    /** Lấy image_id từ URI. */
    fun getImageIdByUri(uri: Uri): Long? {
        val cursor = dbHelper.readableDatabase.rawQuery(
            "SELECT ${DatabaseHelper.COLUMN_IMAGE_ID} FROM ${DatabaseHelper.TABLE_IMAGES} WHERE ${DatabaseHelper.COLUMN_IMAGE_URI} = ?",
            arrayOf(uri.toString())
        )
        return if (cursor.moveToFirst()) cursor.getLong(0).also { cursor.close() }
        else { cursor.close(); null }
    }

    /** Đếm số ảnh trong room. */
    fun getImageCount(roomId: Long): Int {
        val cursor = dbHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_IMAGES} WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ?",
            arrayOf(roomId.toString())
        )
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    /** Cập nhật URI của một image (không thay đổi translations/blocks). */
    fun updateImageUri(imageId: Long, newUri: Uri) {
        try {
            val values = ContentValues().apply { put(DatabaseHelper.COLUMN_IMAGE_URI, newUri.toString()) }
            db.update(DatabaseHelper.TABLE_IMAGES, values, "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
        } catch (e: Exception) {
            AppLogger.w("RoomDao", "updateImageUri failed for imageId=$imageId newUri=$newUri", e)
        }
    }

    /**
     * Migrate image URI links để đảm bảo tất cả trỏ về đúng thư mục `images/roomId/`.
     * Gọi một lần khi app khởi động.
     */
    fun migrateRoomImageLinks() {
        val externalDir = dbHelper.appContext.getExternalFilesDir(null) ?: return
        val cursor = db.rawQuery(
            "SELECT ${DatabaseHelper.COLUMN_IMAGE_ID}, ${DatabaseHelper.COLUMN_IMAGE_URI}, ${DatabaseHelper.COLUMN_ROOM_ID} FROM ${DatabaseHelper.TABLE_IMAGES}",
            null
        )
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
                    val values = ContentValues().apply { put(DatabaseHelper.COLUMN_IMAGE_URI, newUri) }
                    db.update(DatabaseHelper.TABLE_IMAGES, values, "${DatabaseHelper.COLUMN_IMAGE_ID}=?", arrayOf(imageId.toString()))
                }
            }
        }
        cursor.close()
    }

    // ── API KEYS ───────────────────────────────────────────────────────────

    /** Lấy tất cả API keys kèm thông tin đầy đủ. */
    fun getAllApiKeysWithStats(): List<ApiKeyInfo> {
        val cursor = dbHelper.readableDatabase.rawQuery(
            "SELECT ${DatabaseHelper.COLUMN_API_KEY_ID}, ${DatabaseHelper.COLUMN_API_KEY_VALUE}, " +
                "${DatabaseHelper.COLUMN_API_KEY_TYPE}, ${DatabaseHelper.COLUMN_IS_ACTIVE}, allowed_models " +
                "FROM ${DatabaseHelper.TABLE_API_KEYS}",
            null
        )
        val apiKeys = mutableListOf<ApiKeyInfo>()
        while (cursor.moveToNext()) {
            apiKeys.add(ApiKeyInfo(
                id = cursor.getInt(0),
                value = cursor.getString(1),
                type = cursor.getString(2),
                isActive = cursor.getInt(3) == 1,
                allowedModels = try { cursor.getString(4) } catch (e: Exception) { "" } ?: ""
            ))
        }
        cursor.close()
        return apiKeys
    }

    /** Lấy tất cả API keys dạng Pair(value, type). */
    fun getAllApiKeys(): List<Pair<String, String>> {
        val cursor = dbHelper.readableDatabase.rawQuery(
            "SELECT ${DatabaseHelper.COLUMN_API_KEY_VALUE}, ${DatabaseHelper.COLUMN_API_KEY_TYPE} FROM ${DatabaseHelper.TABLE_API_KEYS}",
            null
        )
        val apiKeys = mutableListOf<Pair<String, String>>()
        while (cursor.moveToNext()) apiKeys.add(cursor.getString(0) to cursor.getString(1))
        cursor.close()
        return apiKeys
    }

    /** Chèn API key mới. */
    fun insertApiKey(apiKey: String, type: String = "default", allowedModels: String = "") {
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_API_KEY_VALUE, apiKey)
            put(DatabaseHelper.COLUMN_API_KEY_TYPE, type)
            put(DatabaseHelper.COLUMN_CREATED_DATE, "2025-07-15")
            put(DatabaseHelper.COLUMN_UPDATED_DATE, "2025-07-15")
            put(DatabaseHelper.COLUMN_IS_ACTIVE, 1)
            put("allowed_models", allowedModels)
        }
        db.insert(DatabaseHelper.TABLE_API_KEYS, null, values)
    }

    /** Cập nhật giá trị API key. */
    fun updateApiKey(oldKey: String, newKey: String, newType: String? = null, allowedModels: String? = null) {
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_API_KEY_VALUE, newKey)
            newType?.let { put(DatabaseHelper.COLUMN_API_KEY_TYPE, it) }
            allowedModels?.let { put("allowed_models", it) }
        }
        db.update(DatabaseHelper.TABLE_API_KEYS, values, "${DatabaseHelper.COLUMN_API_KEY_VALUE} = ?", arrayOf(oldKey))
    }

    /** Cập nhật allowed_models của API key. */
    fun updateApiKeyAllowedModels(apiKey: String, allowedModels: String) {
        val values = ContentValues().apply { put("allowed_models", allowedModels) }
        db.update(DatabaseHelper.TABLE_API_KEYS, values, "${DatabaseHelper.COLUMN_API_KEY_VALUE} = ?", arrayOf(apiKey))
    }

    /** Xóa API key. */
    fun deleteApiKey(apiKey: String) {
        db.delete(DatabaseHelper.TABLE_API_KEYS, "${DatabaseHelper.COLUMN_API_KEY_VALUE} = ?", arrayOf(apiKey))
    }

    /** Cập nhật trạng thái active của API key. */
    fun updateApiKeyStatus(apiKey: String, isActive: Boolean): Boolean {
        val values = ContentValues().apply { put("is_active", if (isActive) 1 else 0) }
        return db.update(DatabaseHelper.TABLE_API_KEYS, values, "${DatabaseHelper.COLUMN_API_KEY_VALUE} = ?", arrayOf(apiKey)) > 0
    }

    /** Kiểm tra trạng thái active của API key. */
    fun getApiKeyStatus(apiKey: String): Boolean {
        val cursor = dbHelper.readableDatabase.rawQuery(
            "SELECT is_active FROM ${DatabaseHelper.TABLE_API_KEYS} WHERE ${DatabaseHelper.COLUMN_API_KEY_VALUE} = ?",
            arrayOf(apiKey)
        )
        val isActive = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else false
        cursor.close()
        return isActive
    }

    // ── ROOM SETTINGS ──────────────────────────────────────────────────────

    /**
     * Lấy cài đặt tự động dịch cho room.
     * Mặc định: `true` (bật) nếu chưa có bản ghi.
     */
    fun getAutoTranslateSetting(roomId: Long): Boolean {
        return try {
            val cursor = dbHelper.readableDatabase.rawQuery(
                "SELECT ${DatabaseHelper.COLUMN_AUTO_TRANSLATE_NEW_IMAGES} FROM ${DatabaseHelper.TABLE_ROOM_SETTINGS} WHERE ${DatabaseHelper.COLUMN_SETTING_ROOM_ID} = ?",
                arrayOf(roomId.toString())
            )
            val result = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else true
            cursor.close()
            result
        } catch (e: Exception) {
            AppLogger.w("RoomDao", "Error getting auto-translate for roomId=$roomId", e)
            true
        }
    }

    /**
     * Ghi cài đặt tự động dịch cho room (upsert).
     */
    fun setAutoTranslateSetting(roomId: Long, enabled: Boolean) {
        try {
            val values = ContentValues().apply {
                put(DatabaseHelper.COLUMN_SETTING_ROOM_ID, roomId)
                put(DatabaseHelper.COLUMN_AUTO_TRANSLATE_NEW_IMAGES, if (enabled) 1 else 0)
            }
            val updated = db.update(DatabaseHelper.TABLE_ROOM_SETTINGS, values, "${DatabaseHelper.COLUMN_SETTING_ROOM_ID} = ?", arrayOf(roomId.toString()))
            if (updated == 0) db.insert(DatabaseHelper.TABLE_ROOM_SETTINGS, null, values)
        } catch (e: Exception) {
            AppLogger.e("RoomDao", "Error setting auto-translate for roomId=$roomId", e)
        }
    }

    /**
     * Lấy cài đặt dịch cổ trang cho room.
     * Mặc định: `false` (tắt).
     */
    fun getAncientTranslationSetting(roomId: Long): Boolean {
        return try {
            val cursor = dbHelper.readableDatabase.rawQuery(
                "SELECT ${DatabaseHelper.COLUMN_ANCIENT_TRANSLATION_ENABLED} FROM ${DatabaseHelper.TABLE_ROOM_SETTINGS} WHERE ${DatabaseHelper.COLUMN_SETTING_ROOM_ID} = ?",
                arrayOf(roomId.toString())
            )
            val result = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else false
            cursor.close()
            result
        } catch (e: Exception) {
            AppLogger.w("RoomDao", "Error getting ancient translation for roomId=$roomId", e)
            false
        }
    }

    /**
     * Ghi cài đặt dịch cổ trang cho room (upsert).
     */
    fun setAncientTranslationSetting(roomId: Long, enabled: Boolean) {
        try {
            val values = ContentValues().apply {
                put(DatabaseHelper.COLUMN_SETTING_ROOM_ID, roomId)
                put(DatabaseHelper.COLUMN_ANCIENT_TRANSLATION_ENABLED, if (enabled) 1 else 0)
            }
            val updated = db.update(DatabaseHelper.TABLE_ROOM_SETTINGS, values, "${DatabaseHelper.COLUMN_SETTING_ROOM_ID} = ?", arrayOf(roomId.toString()))
            if (updated == 0) db.insert(DatabaseHelper.TABLE_ROOM_SETTINGS, null, values)
        } catch (e: Exception) {
            AppLogger.e("RoomDao", "Error setting ancient translation for roomId=$roomId", e)
        }
    }
}
