package com.example.ocrmanga.data.database

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import com.example.ocrmanga.data.models.TextAlignMode
import com.example.ocrmanga.data.models.TextBlockInfo
import java.io.File
import java.io.FileOutputStream

internal fun DatabaseHelper.updateMangaRoomSelectiveImpl(
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

internal fun DatabaseHelper.applyPendingChangesForRoomImpl(
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

internal fun DatabaseHelper.cleanupDuplicateImages(roomId: Long) {
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

internal fun DatabaseHelper.cleanupDuplicateImagesStrict(roomId: Long) {
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

internal fun DatabaseHelper.cleanupAndSyncRoomImagesImpl(roomId: Long, expectedCount: Int) {
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

