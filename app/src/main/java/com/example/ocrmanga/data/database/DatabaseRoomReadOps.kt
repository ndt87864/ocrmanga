package com.example.ocrmanga.data.database

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import com.example.ocrmanga.data.models.ImageBlock
import com.example.ocrmanga.data.models.TextAlignMode
import com.example.ocrmanga.data.models.TextBlockInfo
import java.io.File
import java.io.FileOutputStream

internal data class DatabaseBlockData(
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

internal data class DatabaseTranslationData(
    val translatedText: String, val originalText: String,
    val x: Int, val y: Int, val width: Int, val height: Int,
    val originalTextColor: Int?
)

internal fun DatabaseHelper.copyImageToInternalStorage(originalUri: Uri, directory: File, fileName: String): File? {
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

internal fun DatabaseHelper.deleteOriginalImage(uri: Uri) {
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

internal fun DatabaseHelper.getMangaRoomImpl(roomId: Long): Triple<List<Uri>, List<Int>, Map<Uri, Pair<String, List<TextBlockInfo>>>> {
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

internal fun DatabaseHelper.getMangaRoomOptimizedImpl(roomId: Long): Triple<List<Uri>, List<Int>, Map<Uri, Pair<String, List<TextBlockInfo>>>> {
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

    val translationsByImageId = mutableMapOf<Long, MutableList<DatabaseTranslationData>>()
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
                DatabaseTranslationData(
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

    val blocksDataByImageId = mutableMapOf<Long, MutableList<DatabaseBlockData>>()
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
                DatabaseBlockData(
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

internal fun DatabaseHelper.getTranslationsForImagesImpl(imageUris: List<Uri>): Map<Uri, Pair<String, List<TextBlockInfo>>> {
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

        val transList = mutableListOf<DatabaseTranslationData>()
        while (transCursor.moveToNext()) {
            transList.add(
                DatabaseTranslationData(
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

