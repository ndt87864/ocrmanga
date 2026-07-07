package com.example.ocrmanga.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.utils.AppLogger

/**
 * DAO xử lý các thao tác CRUD cho bảng `translations` và `change_images`.
 *
 * Bảng `translations` lưu trữ nội dung văn bản đã dịch (translated_text),
 * văn bản gốc (original_text), tọa độ khối văn bản, và trạng thái pending_delete.
 * Mỗi bản ghi được liên kết 1-1 với một `image_block` theo thứ tự chèn.
 *
 * Bảng `change_images` lưu trạng thái "đã thay đổi" của từng ảnh để tối ưu
 * việc lưu có chọn lọc (selective save).
 */
internal class TranslationDao(private val dbHelper: DatabaseHelper) {

    private val db: SQLiteDatabase get() = dbHelper.writableDatabase

    companion object {
        private const val TABLE_TRANSLATIONS = "translations"
    }

    // ── WRITE: TRANSLATION ─────────────────────────────────────────────────

    /**
     * Chèn một bản ghi translation mới.
     * @return row ID nếu thành công, -1 nếu thất bại.
     */
    fun insert(
        imageId: Long,
        translatedText: String,
        originalText: String,
        x: Int, y: Int, width: Int, height: Int,
        originalTextColor: Int? = null
    ): Long {
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_IMAGE_ID, imageId)
            put("translated_text", translatedText)
            val safeOrig = originalText.trim().takeIf { it != "[]" } ?: ""
            put("original_text", safeOrig)
            put("x", x)
            put("y", y)
            put("width", width)
            put("height", height)
            put("original_text_color", originalTextColor)
            put("pending_delete", 0)
        }
        return db.insert(TABLE_TRANSLATIONS, null, values)
    }

    /**
     * Đánh dấu tất cả bản dịch của một image là `pending_delete = 1`.
     * Gọi khi bắt đầu retranslate để giữ bản dịch cũ hiển thị tạm thời.
     */
    fun markAsPendingDelete(imageId: Long) {
        try {
            val values = ContentValues().apply { put("pending_delete", 1) }
            db.update(TABLE_TRANSLATIONS, values, "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "markAsPendingDelete failed for imageId=$imageId", e)
        }
    }

    /**
     * Xóa vĩnh viễn tất cả bản dịch `pending_delete = 1` của một image.
     * Gọi sau khi lưu bản dịch mới thành công.
     * @return số bản ghi đã xóa.
     */
    fun deletePending(imageId: Long): Int = try {
        db.delete(TABLE_TRANSLATIONS, "${DatabaseHelper.COLUMN_IMAGE_ID} = ? AND pending_delete = 1", arrayOf(imageId.toString()))
    } catch (e: Exception) {
        AppLogger.w("TranslationDao", "deletePending failed for imageId=$imageId", e)
        0
    }

    /**
     * Hủy trạng thái pending_delete (đặt về 0) cho một image.
     * Gọi khi retranslate thất bại hoặc bị hủy.
     */
    fun clearPendingDelete(imageId: Long) {
        try {
            val values = ContentValues().apply { put("pending_delete", 0) }
            db.update(TABLE_TRANSLATIONS, values, "${DatabaseHelper.COLUMN_IMAGE_ID} = ? AND pending_delete = 1", arrayOf(imageId.toString()))
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "clearPendingDelete failed for imageId=$imageId", e)
        }
    }

    /**
     * Hủy pending_delete cho tất cả bản dịch trong một room.
     */
    fun clearPendingDeleteForRoom(roomId: Long) {
        try {
            val values = ContentValues().apply { put("pending_delete", 0) }
            db.update(
                TABLE_TRANSLATIONS, values,
                "${DatabaseHelper.COLUMN_IMAGE_ID} IN " +
                    "(SELECT ${DatabaseHelper.COLUMN_IMAGE_ID} FROM ${DatabaseHelper.TABLE_IMAGES} " +
                    "WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ?) AND pending_delete = 1",
                arrayOf(roomId.toString())
            )
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "clearPendingDeleteForRoom failed for roomId=$roomId", e)
        }
    }

    /**
     * Xóa toàn bộ bản ghi translations của một image (không phân biệt pending).
     * @return số bản ghi đã xóa.
     */
    fun deleteAllForImage(imageId: Long): Int = try {
        db.delete(TABLE_TRANSLATIONS, "${DatabaseHelper.COLUMN_IMAGE_ID} = ?", arrayOf(imageId.toString()))
    } catch (e: Exception) {
        AppLogger.w("TranslationDao", "deleteAllForImage failed for imageId=$imageId", e)
        0
    }

    // ── READ: ORIGINAL TEXT ────────────────────────────────────────────────

    /**
     * Trả về danh sách original_text hợp lệ cho một image.
     * Lọc bỏ các chuỗi rỗng và chuỗi "[]".
     */
    fun getOriginalTexts(imageId: Long): List<String> {
        val result = mutableListOf<String>()
        try {
            val cursor = dbHelper.readableDatabase.rawQuery(
                """
                SELECT original_text FROM $TABLE_TRANSLATIONS
                WHERE ${DatabaseHelper.COLUMN_IMAGE_ID} = ?
                AND (pending_delete IS NULL OR pending_delete = 0)
                AND original_text IS NOT NULL AND original_text != ''
                ORDER BY text_id ASC
                """.trimIndent(),
                arrayOf(imageId.toString())
            )
            while (cursor.moveToNext()) {
                val orig = cursor.getString(0) ?: ""
                if (orig.isNotBlank() && orig.trim() != "[]") result.add(orig)
            }
            cursor.close()
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "getOriginalTexts failed for imageId=$imageId", e)
        }
        return result
    }

    // ── CHANGE FLAGS ───────────────────────────────────────────────────────

    /**
     * Đảm bảo một bản ghi `change_images` tồn tại cho image với flag = 0.
     */
    fun ensureChangeRecord(imageId: Long, roomId: Long) {
        try {
            val cursor = db.rawQuery(
                "SELECT ${DatabaseHelper.COLUMN_CHANGE_IMAGE_ID} FROM ${DatabaseHelper.TABLE_CHANGE_IMAGES} " +
                    "WHERE ${DatabaseHelper.COLUMN_CHANGE_IMAGE_IMAGE_ID} = ?",
                arrayOf(imageId.toString())
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            if (!exists) {
                val values = ContentValues().apply {
                    put(DatabaseHelper.COLUMN_CHANGE_IMAGE_IMAGE_ID, imageId)
                    put(DatabaseHelper.COLUMN_CHANGE_IMAGE_ROOM_ID, roomId)
                    put(DatabaseHelper.COLUMN_CHANGE_IMAGE_FLAG, 0)
                }
                db.insert(DatabaseHelper.TABLE_CHANGE_IMAGES, null, values)
            }
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "ensureChangeRecord failed for imageId=$imageId", e)
        }
    }

    /**
     * Đánh dấu một image là đã thay đổi (is_changed = 1).
     * @return số lượng image có is_changed = 1 trong room sau khi đánh dấu.
     */
    fun markImageChanged(imageId: Long, roomId: Long): Int {
        try {
            val values = ContentValues().apply { put(DatabaseHelper.COLUMN_CHANGE_IMAGE_FLAG, 1) }
            val updated = db.update(
                DatabaseHelper.TABLE_CHANGE_IMAGES, values,
                "${DatabaseHelper.COLUMN_CHANGE_IMAGE_IMAGE_ID} = ?", arrayOf(imageId.toString())
            )
            if (updated <= 0) {
                val ins = ContentValues().apply {
                    put(DatabaseHelper.COLUMN_CHANGE_IMAGE_IMAGE_ID, imageId)
                    put(DatabaseHelper.COLUMN_CHANGE_IMAGE_ROOM_ID, roomId)
                    put(DatabaseHelper.COLUMN_CHANGE_IMAGE_FLAG, 1)
                }
                db.insert(DatabaseHelper.TABLE_CHANGE_IMAGES, null, ins)
            }
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_CHANGE_IMAGES} " +
                    "WHERE ${DatabaseHelper.COLUMN_CHANGE_IMAGE_ROOM_ID} = ? AND ${DatabaseHelper.COLUMN_CHANGE_IMAGE_FLAG} = 1",
                arrayOf(roomId.toString())
            )
            var count = 0
            if (cursor.moveToFirst()) count = try { cursor.getInt(0) } catch (e: Exception) { 0 }
            cursor.close()
            return count
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "markImageChanged failed for imageId=$imageId", e)
        }
        return 0
    }

    /** Xóa flag is_changed về 0 cho một image. */
    fun clearImageChange(imageId: Long) {
        try {
            val values = ContentValues().apply { put(DatabaseHelper.COLUMN_CHANGE_IMAGE_FLAG, 0) }
            db.update(
                DatabaseHelper.TABLE_CHANGE_IMAGES, values,
                "${DatabaseHelper.COLUMN_CHANGE_IMAGE_IMAGE_ID} = ?", arrayOf(imageId.toString())
            )
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "clearImageChange failed for imageId=$imageId", e)
        }
    }

    /** Alias của [clearImageChange]. */
    fun clearChangedFlagForImage(imageId: Long) = clearImageChange(imageId)

    /**
     * Xóa toàn bộ flag is_changed về 0 cho tất cả images trong room.
     */
    fun clearAllChangedFlagsForRoom(roomId: Long) {
        try {
            db.execSQL(
                """
                UPDATE ${DatabaseHelper.TABLE_CHANGE_IMAGES}
                SET ${DatabaseHelper.COLUMN_CHANGE_IMAGE_FLAG} = 0
                WHERE ${DatabaseHelper.COLUMN_CHANGE_IMAGE_IMAGE_ID} IN (
                    SELECT ${DatabaseHelper.COLUMN_IMAGE_ID}
                    FROM ${DatabaseHelper.TABLE_IMAGES}
                    WHERE ${DatabaseHelper.COLUMN_ROOM_ID} = ?
                )
                """.trimIndent(),
                arrayOf(roomId.toString())
            )
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "clearAllChangedFlagsForRoom failed for roomId=$roomId", e)
        }
    }

    /**
     * Trả về danh sách image_id có is_changed = 1 trong một room.
     */
    fun getChangedImageIds(roomId: Long): List<Long> {
        val result = mutableListOf<Long>()
        try {
            val cursor = db.rawQuery(
                "SELECT ${DatabaseHelper.COLUMN_CHANGE_IMAGE_IMAGE_ID} FROM ${DatabaseHelper.TABLE_CHANGE_IMAGES} " +
                    "WHERE ${DatabaseHelper.COLUMN_CHANGE_IMAGE_ROOM_ID} = ? AND ${DatabaseHelper.COLUMN_CHANGE_IMAGE_FLAG} = 1",
                arrayOf(roomId.toString())
            )
            while (cursor.moveToNext()) result.add(cursor.getLong(0))
            cursor.close()
        } catch (e: Exception) {
            AppLogger.w("TranslationDao", "getChangedImageIds failed for roomId=$roomId", e)
        }
        return result
    }
}
