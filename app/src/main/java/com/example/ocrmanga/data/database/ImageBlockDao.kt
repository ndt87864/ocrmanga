package com.example.ocrmanga.data.database

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import com.example.ocrmanga.data.models.BackgroundType
import com.example.ocrmanga.data.models.ImageBlock
import com.example.ocrmanga.data.models.TextAlignMode
import com.example.ocrmanga.data.models.TextBlockInfo

/**
 * DAO xử lý các thao tác CRUD cho bảng `image_blocks`.
 *
 * Mỗi `ImageBlock` lưu trữ toàn bộ thông tin trực quan của một khối văn bản
 * (overlay, text, border, shadow, font, gradient), tương ứng 1-1 với một
 * bản ghi trong bảng `translations` (cùng thứ tự chèn).
 *
 * **Lưu ý giải phóng tài nguyên**: Mọi Cursor được tạo ra trong DAO này phải
 * được đóng ngay sau khi sử dụng xong để tránh rò rỉ bộ nhớ native.
 */
internal class ImageBlockDao(private val dbHelper: DatabaseHelper) {

    private val db: SQLiteDatabase get() = dbHelper.writableDatabase

    // ── WRITE ──────────────────────────────────────────────────────────────

    /**
     * Chèn một image block mới vào DB và trả về row ID.
     * Gradient colors/offsets được serialize thành chuỗi phân cách bằng dấu phẩy.
     */
    fun insert(
        imageId: Long,
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
        val gradientColorsStr = textGradientColors?.joinToString(",") { it.toString() }
        val gradientOffsetsStr = textGradientOffsets?.joinToString(",") { it.toString() }

        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_BLOCK_IMAGE_ID, imageId)
            put(DatabaseHelper.COLUMN_BLOCK_X, x)
            put(DatabaseHelper.COLUMN_BLOCK_Y, y)
            put(DatabaseHelper.COLUMN_BLOCK_WIDTH, width)
            put(DatabaseHelper.COLUMN_BLOCK_HEIGHT, height)
            put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_TYPE, overlayType)
            overlayColor?.let { put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_COLOR, it) }
            put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_BRIGHTNESS, overlayBrightness)
            put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_ALPHA, overlayAlpha)
            put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_SATURATION, overlaySaturation)
            put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_INSET, overlayInset)
            put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL, overlayInsetHorizontal)
            put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_INSET_VERTICAL, overlayInsetVertical)
            overlayRotation?.let { put(DatabaseHelper.COLUMN_BLOCK_OVERLAY_ROTATION, it) }
            put(DatabaseHelper.COLUMN_BLOCK_TEXT_COLOR, textColor ?: 0xFF000000.toInt())
            put(DatabaseHelper.COLUMN_BLOCK_TEXT_BRIGHTNESS, textBrightness)
            put(DatabaseHelper.COLUMN_BLOCK_TEXT_BOLDNESS, textBoldness)
            put(DatabaseHelper.COLUMN_BLOCK_TEXT_SATURATION, textSaturation)
            borderColor?.let { put(DatabaseHelper.COLUMN_BLOCK_BORDER_COLOR, it) }
            put(DatabaseHelper.COLUMN_BLOCK_BORDER_BRIGHTNESS, borderBrightness)
            put(DatabaseHelper.COLUMN_BLOCK_BORDER_BOLDNESS, borderBoldness)
            put(DatabaseHelper.COLUMN_BLOCK_BORDER_THICKNESS, borderThickness)
            shadowColor?.let { put(DatabaseHelper.COLUMN_BLOCK_SHADOW_COLOR, it) }
            put(DatabaseHelper.COLUMN_BLOCK_SHADOW_ALPHA, shadowAlpha)
            put(DatabaseHelper.COLUMN_BLOCK_SHADOW_RADIUS, shadowRadius)
            put(DatabaseHelper.COLUMN_BLOCK_ROTATION, rotation)
            put(DatabaseHelper.COLUMN_BLOCK_FONT_FAMILY, fontFamily)
            put(DatabaseHelper.COLUMN_BLOCK_FONT_SIZE, fontSize)
            put(DatabaseHelper.COLUMN_BLOCK_LINE_SPACING, lineSpacing)
            put(DatabaseHelper.COLUMN_BLOCK_TEXT_ALIGN, textAlign)
            put(DatabaseHelper.COLUMN_BLOCK_TEXT_GRADIENT_COLORS, gradientColorsStr)
            put(DatabaseHelper.COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS, gradientOffsetsStr)
            put(DatabaseHelper.COLUMN_BLOCK_TEXT_GRADIENT_TYPE, textGradientType)
            originalWidth?.let { put(DatabaseHelper.COLUMN_BLOCK_ORIGINAL_WIDTH, it) }
            originalHeight?.let { put(DatabaseHelper.COLUMN_BLOCK_ORIGINAL_HEIGHT, it) }
        }
        return db.insert(DatabaseHelper.TABLE_IMAGE_BLOCKS, null, values)
    }

    /** Xóa tất cả image_blocks của một ảnh. */
    fun deleteForImage(imageId: Long) {
        db.delete(
            DatabaseHelper.TABLE_IMAGE_BLOCKS,
            "${DatabaseHelper.COLUMN_BLOCK_IMAGE_ID} = ?",
            arrayOf(imageId.toString())
        )
    }

    // ── READ ───────────────────────────────────────────────────────────────

    /** Trả về danh sách [ImageBlock] cho một ảnh (theo thứ tự block_id). */
    fun getForImage(imageId: Long): List<ImageBlock> {
        val cursor = db.rawQuery(
            "SELECT * FROM ${DatabaseHelper.TABLE_IMAGE_BLOCKS} " +
                "WHERE ${DatabaseHelper.COLUMN_BLOCK_IMAGE_ID} = ? " +
                "ORDER BY ${DatabaseHelper.COLUMN_BLOCK_ID} ASC",
            arrayOf(imageId.toString())
        )
        val result = mutableListOf<ImageBlock>()
        while (cursor.moveToNext()) result.add(cursorToImageBlock(cursor))
        cursor.close()
        return result
    }

    // ── CURSOR MAPPERS ─────────────────────────────────────────────────────

    /**
     * Chuyển đổi một hàng Cursor thành [ImageBlock].
     * Sử dụng extension functions để đảm bảo an toàn kiểu dữ liệu.
     */
    internal fun cursorToImageBlock(cursor: Cursor): ImageBlock {
        fun idx(name: String) = try { cursor.getColumnIndexOrThrow(name) } catch (e: Exception) { -1 }
        val textAlignStr = try { cursor.getString(idx(DatabaseHelper.COLUMN_BLOCK_TEXT_ALIGN)) } catch (e: Exception) { "CENTER" }
        return ImageBlock(
            blockId = if (idx(DatabaseHelper.COLUMN_BLOCK_ID) >= 0) cursor.getLong(idx(DatabaseHelper.COLUMN_BLOCK_ID)) else -1L,
            imageId = if (idx(DatabaseHelper.COLUMN_BLOCK_IMAGE_ID) >= 0) cursor.getLong(idx(DatabaseHelper.COLUMN_BLOCK_IMAGE_ID)) else -1L,
            x = cursor.getIntOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_X), 0),
            y = cursor.getIntOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_Y), 0),
            width = cursor.getIntOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_WIDTH), 0),
            height = cursor.getIntOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_HEIGHT), 0),
            overlayType = cursor.getIntOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_TYPE), 0),
            overlayColor = cursor.getIntOrNull(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_COLOR)),
            overlayBrightness = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_BRIGHTNESS), 1f),
            overlayAlpha = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_ALPHA), 1f),
            overlaySaturation = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_SATURATION), 1f),
            overlayInset = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_INSET), 0f),
            overlayInsetHorizontal = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_INSET_HORIZONTAL), 0f),
            overlayInsetVertical = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_INSET_VERTICAL), 0f),
            overlayRotation = cursor.getFloatOrNull(idx(DatabaseHelper.COLUMN_BLOCK_OVERLAY_ROTATION)),
            textColor = cursor.getIntOrNull(idx(DatabaseHelper.COLUMN_BLOCK_TEXT_COLOR)),
            textBrightness = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_TEXT_BRIGHTNESS), 1f),
            textBoldness = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_TEXT_BOLDNESS), 1f),
            textSaturation = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_TEXT_SATURATION), 1f),
            borderColor = cursor.getIntOrNull(idx(DatabaseHelper.COLUMN_BLOCK_BORDER_COLOR)),
            borderBrightness = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_BORDER_BRIGHTNESS), 1f),
            borderBoldness = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_BORDER_BOLDNESS), 1f),
            borderThickness = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_BORDER_THICKNESS), 0f),
            shadowColor = cursor.getIntOrNull(idx(DatabaseHelper.COLUMN_BLOCK_SHADOW_COLOR)),
            shadowAlpha = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_SHADOW_ALPHA), 1f),
            shadowRadius = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_SHADOW_RADIUS), 0f),
            rotation = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_ROTATION), 0f),
            fontFamily = run {
                val ff = try { cursor.getString(idx(DatabaseHelper.COLUMN_BLOCK_FONT_FAMILY)) } catch (e: Exception) { null }
                if (ff.isNullOrBlank()) "mto_astro_city" else ff
            },
            fontSize = cursor.getFloatOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_FONT_SIZE), 12f),
            textAlign = try { TextAlignMode.valueOf(textAlignStr) } catch (e: Exception) { TextAlignMode.CENTER },
            textGradientColors = try {
                cursor.getString(idx(DatabaseHelper.COLUMN_BLOCK_TEXT_GRADIENT_COLORS))
                    ?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toIntOrNull() }
            } catch (e: Exception) { null },
            textGradientOffsets = try {
                cursor.getString(idx(DatabaseHelper.COLUMN_BLOCK_TEXT_GRADIENT_OFFSETS))
                    ?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toFloatOrNull() }
            } catch (e: Exception) { null },
            textGradientType = cursor.getIntOrDefault(idx(DatabaseHelper.COLUMN_BLOCK_TEXT_GRADIENT_TYPE), 0)
        )
    }

    /**
     * Tạo [TextBlockInfo] từ một [ImageBlock] và dữ liệu dịch thuật phối hợp.
     *
     * @param block      Dữ liệu overlay từ `image_blocks`.
     * @param translated Văn bản đã dịch (từ bảng `translations`).
     * @param original   Văn bản gốc (từ bảng `translations`), có thể null.
     * @param originalTextColor Màu gốc từ bảng `translations`.
     * @param bounds     Tọa độ (ưu tiên từ bảng `translations` nếu hợp lệ).
     */
    /**
     * Tạo [TextBlockInfo] trực tiếp từ Cursor hiện tại (chứa cả các cột của image_blocks và translations).
     */
    internal fun cursorToTextBlockInfo(cursor: Cursor): TextBlockInfo {
        val block = cursorToImageBlock(cursor)
        val bounds = Rect(block.x, block.y, block.x + block.width, block.y + block.height)
        return blockToTextBlockInfo(block, "", null, null, bounds)
    }

    /**
     * Tạo [TextBlockInfo] từ một [ImageBlock] và dữ liệu dịch thuật phối hợp.
     *
     * @param block      Dữ liệu overlay từ `image_blocks`.
     * @param translated Văn bản đã dịch (từ bảng `translations`).
     * @param original   Văn bản gốc (từ bảng `translations`), có thể null.
     * @param originalTextColor Màu gốc từ bảng `translations`.
     * @param bounds     Tọa độ (ưu tiên từ bảng `translations` nếu hợp lệ).
     */
    internal fun blockToTextBlockInfo(
        block: ImageBlock,
        translated: String,
        original: String?,
        originalTextColor: Int?,
        bounds: Rect
    ): TextBlockInfo {
        val finalTextColor = block.textColor?.takeIf { it != 0 } ?: 0xFF000000.toInt()
        val finalFontFamily = block.fontFamily.ifBlank { "mto_astro_city" }
        return TextBlockInfo(
            text = translated,
            originalText = original?.takeIf { it.isNotBlank() },
            bounds = bounds,
            fontSize = block.fontSize,
            rotation = block.rotation,
            textAlign = block.textAlign,
            shapeType = block.overlayType,
            backgroundType = BackgroundType.WHITE,
            averageBackgroundColor = block.overlayColor,
            originalTextColor = originalTextColor,
            customOverlayColor = block.overlayColor,
            customTextColor = finalTextColor,
            overlayAlpha = block.overlayAlpha,
            textBoldness = block.textBoldness,
            overlaySaturation = block.overlaySaturation,
            textSaturation = block.textSaturation,
            customBorderColor = block.borderColor,
            borderThickness = block.borderThickness,
            customShadowColor = block.shadowColor,
            shadowAlpha = block.shadowAlpha,
            shadowRadius = block.shadowRadius,
            fontFamily = finalFontFamily,
            overlayInsetHorizontal = block.overlayInsetHorizontal,
            overlayInsetVertical = block.overlayInsetVertical,
            overlayRotation = block.overlayRotation,
            textGradientColors = block.textGradientColors,
            textGradientOffsets = block.textGradientOffsets,
            textGradientType = block.textGradientType,
            applyMerge = false
        )
    }
}

