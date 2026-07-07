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

internal fun DatabaseHelper.replaceImageWithCopyImpl(imageId: Long, newUri: Uri): Uri? {
    val db = writableDatabase
    db.beginTransaction()
    try {
        val cur = db.rawQuery("SELECT $COLUMN_ROOM_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
        if (!cur.moveToFirst()) {
            cur.close()
            return null
        }
        val roomId = cur.getLong(0)
        val oldUriStr = cur.getString(1)
        cur.close()

        val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
        imagesDir.mkdirs()

        var storedFile: File? = null
        try {
            val prevPath = try { Uri.parse(oldUriStr).path } catch (e: Exception) { null }
            if (!prevPath.isNullOrBlank()) {
                val prev = File(prevPath)
                if (prev.exists() && prev.parentFile?.absolutePath?.startsWith(imagesDir.absolutePath) == true) {
                    val copiedOver = copyImageToInternalStorage(newUri, prev.parentFile!!, prev.name)
                    if (copiedOver?.exists() == true) storedFile = copiedOver
                }
            }
        } catch (e: Exception) { /* ignore */ }

        if (storedFile == null) {
            val fileName = "image_${imageId}.webp"
            val copied = copyImageToInternalStorage(newUri, imagesDir, fileName) ?: return null
            storedFile = copied
            try {
                val prevFile = File(Uri.parse(oldUriStr).path ?: "")
                if (prevFile.exists() && prevFile.parentFile?.absolutePath?.startsWith(imagesDir.absolutePath) == true && prevFile.absolutePath != storedFile.absolutePath) {
                    prevFile.delete()
                }
            } catch (e: Exception) { /* ignore */ }
        }

        val storedUri = Uri.fromFile(storedFile)
        val values = ContentValues().apply { put(COLUMN_IMAGE_URI, storedUri.toString()) }
        db.update(TABLE_IMAGES, values, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
        try { deleteOriginalImage(newUri) } catch (e: Exception) { /* ignore */ }

        db.setTransactionSuccessful()
        return storedUri
    } catch (e: Exception) {
        Log.e(TAG, "replaceImageWithCopy failed", e)
        return null
    } finally {
        db.endTransaction()
    }
}

internal fun DatabaseHelper.replaceImageFileOnlyImpl(imageId: Long, newUri: Uri): Uri? {
    try {
        val db = readableDatabase
        val cur = db.rawQuery("SELECT $COLUMN_ROOM_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
        if (!cur.moveToFirst()) {
            cur.close()
            return null
        }
        val roomId = cur.getLong(0)
        val oldUriStr = cur.getString(1)
        cur.close()

        val oldUri = Uri.parse(oldUriStr)
        val oldFilePath = oldUri.path ?: return null
        val oldFile = File(oldFilePath)
        if (!oldFile.exists()) return null

        val parentDir = oldFile.parentFile ?: return null
        val fileName = oldFile.name
        val tempFileName = "temp_replace_${System.currentTimeMillis()}_$fileName"
        val tempFile = copyImageToInternalStorage(newUri, parentDir, tempFileName) ?: return null

        oldFile.delete()
        val finalFile = File(parentDir, fileName)
        if (!tempFile.renameTo(finalFile)) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
        }
        try { deleteOriginalImage(newUri) } catch (e: Exception) { /* ignore */ }
        return oldUri
    } catch (e: Exception) {
        Log.e(TAG, "replaceImageFileOnly failed", e)
        return null
    }
}

internal fun DatabaseHelper.saveMangaRoomImpl(
    imageUris: List<Uri>,
    translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
    title: String? = null,
    translatedStatus: Map<Uri, Boolean>? = null
): Long {
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
            val roomValues = ContentValues().apply { put(COLUMN_COVER_URI, coverUri.toString()) }
            db.update(TABLE_ROOMS, roomValues, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
            deleteOriginalImage(imageUris.first())
        }

        imageUris.forEachIndexed { index, originalUri ->
            val fileName = "image_$index.webp"
            val newFile = copyImageToInternalStorage(originalUri, imagesDir, fileName)
            if (newFile != null) {
                val newUri = Uri.fromFile(newFile)
                val imageValues = ContentValues().apply {
                    put(COLUMN_ROOM_ID, roomId)
                    put(COLUMN_IMAGE_URI, newUri.toString())
                    put(COLUMN_DISPLAY_ORDER, index)
                    put(COLUMN_IS_TRANSLATED, if (translatedStatus?.get(originalUri) == true || translatedTexts.containsKey(originalUri)) 1 else 0)
                }
                val imageId = db.insert(TABLE_IMAGES, null, imageValues)
                if (imageId != -1L) {
                    deleteOriginalImage(originalUri)
                    ensureChangeRecord(imageId, roomId)
                    
                    translatedTexts[originalUri]?.let { (_, textBlocks) ->
                        db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                        imageBlockDao.deleteForImage(imageId)

                        val blocksToSave = textBlocks.filter { !it.pendingDelete }
                        val originalWidth = blocksToSave.firstOrNull()?.originalImageWidth
                        val originalHeight = blocksToSave.firstOrNull()?.originalImageHeight
                        val savedBitmap = android.graphics.BitmapFactory.decodeFile(newFile.absolutePath)
                        val scaleX = if (originalWidth != null && savedBitmap != null && originalWidth > 0) savedBitmap.width.toFloat() / originalWidth else 1f
                        val scaleY = if (originalHeight != null && savedBitmap != null && originalHeight > 0) savedBitmap.height.toFloat() / originalHeight else 1f
                        savedBitmap?.recycle()

                        blocksToSave.forEach { textBlock ->
                            val origRect = textBlock.bounds
                            val scaledRect = if (scaleX != 1f || scaleY != 1f) {
                                Rect(
                                    (origRect.left * scaleX).toInt(),
                                    (origRect.top * scaleY).toInt(),
                                    (origRect.right * scaleX).toInt(),
                                    (origRect.bottom * scaleY).toInt()
                                )
                            } else origRect

                            val transId = translationDao.insert(
                                imageId, textBlock.text, textBlock.originalText ?: "",
                                scaledRect.left, scaledRect.top, scaledRect.width(), scaledRect.height(),
                                textBlock.originalTextColor
                            )
                            if (transId != -1L) {
                                val blockWidth = scaledRect.right - scaledRect.left
                                val blockHeight = scaledRect.bottom - scaledRect.top
                                insertImageBlock(
                                    imageId = imageId, x = scaledRect.left, y = scaledRect.top,
                                    width = blockWidth, height = blockHeight,
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
        }

        imageUris.forEach { uri -> try { deleteOriginalImage(uri) } catch (e: Exception) { /* ignore */ } }
        db.setTransactionSuccessful()
        setAutoTranslateSetting(roomId, true)
        setAutoTranslateSetting(roomId, false) // ancient setting default
        cleanupDuplicateImages(roomId)
    } catch (e: Exception) {
        Log.e(TAG, "Error saving manga room", e)
        return -1L
    } finally {
        db.endTransaction()
    }
    return roomId
}

internal fun DatabaseHelper.updateMangaRoomImpl(
    roomId: Long,
    imageUris: List<Uri>,
    translatedTexts: Map<Uri, Pair<String, List<TextBlockInfo>>>,
    translatedStatus: Map<Uri, Boolean>? = null
): Boolean {
    if (imageUris.isEmpty()) return false
    val db = writableDatabase
    db.beginTransaction()
    try {
        val oldImages = mutableListOf<Pair<Long, Uri>>()
        val imageIdMap = mutableMapOf<String, Long>()
        val cursor = db.rawQuery("SELECT $COLUMN_IMAGE_ID, $COLUMN_IMAGE_URI FROM $TABLE_IMAGES WHERE $COLUMN_ROOM_ID = ? ORDER BY $COLUMN_DISPLAY_ORDER", arrayOf(roomId.toString()))
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val uri = Uri.parse(cursor.getString(1))
            oldImages.add(id to uri)
            imageIdMap[uri.toString()] = id
        }
        cursor.close()

        fun lastNameOf(uri: Uri?): String? = try { uri?.lastPathSegment ?: File(uri.toString()).name } catch (e: Exception) { null }

        oldImages.forEach { (imageId, uri) ->
            val exact = imageUris.any { it.toString() == uri.toString() }
            val name = lastNameOf(uri)
            val namePresent = if (name != null) imageUris.any { newUri -> lastNameOf(newUri) == name } else false
            if (!exact && !namePresent) {
                db.delete(TABLE_TRANSLATIONS, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                imageBlockDao.deleteForImage(imageId)
                db.delete(TABLE_IMAGES, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))
                val file = File(Uri.parse(uri.toString()).path ?: "")
                if (file.exists()) file.delete()
            }
        }

        val imagesDir = File(appContext.getExternalFilesDir(null), "images/$roomId")
        imagesDir.mkdirs()

        val oldFilenameToImageId = mutableMapOf<String, Long>()
        val oldImageIdToFilename = mutableMapOf<Long, String>()
        oldImages.forEach { (imageId, oldUri) ->
            val filename = lastNameOf(oldUri)
            if (filename != null) {
                oldFilenameToImageId[filename] = imageId
                oldImageIdToFilename[imageId] = filename
            }
        }

        data class ImageInfo(val index: Int, val uri: Uri, val isNew: Boolean, val existingImageId: Long?)
        val imageInfos = imageUris.mapIndexed { index, uri ->
            val uriStr = uri.toString()
            val filename = lastNameOf(uri)
            val existingImageId = imageIdMap[uriStr] ?: if (filename != null) oldFilenameToImageId[filename] else null
            ImageInfo(index, uri, existingImageId == null, existingImageId)
        }

        val tempRenames = mutableMapOf<Long, File>()
        imageInfos.filter { !it.isNew && it.existingImageId != null }.forEach { info ->
            val imageId = info.existingImageId!!
            val oldFilename = oldImageIdToFilename[imageId]
            if (oldFilename != null) {
                val oldFile = File(imagesDir, oldFilename)
                if (oldFile.exists()) {
                    val tempFile = File(imagesDir, "temp_${imageId}_$oldFilename")
                    if (oldFile.renameTo(tempFile)) tempRenames[imageId] = tempFile
                }
            }
        }

        imageInfos.forEach { info ->
            val targetFilename = "image_${info.index}.webp"
            val targetFile = File(imagesDir, targetFilename)
            if (info.isNew) {
                copyImageToInternalStorage(info.uri, imagesDir, targetFilename)
            } else {
                val imageId = info.existingImageId!!
                val tempFile = tempRenames[imageId]
                if (tempFile?.exists() == true) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                }
            }
        }

        imageInfos.forEach { info ->
            val index = info.index
            val uri = info.uri
            val isTranslated = if (translatedStatus?.get(uri) == true || translatedTexts.containsKey(uri)) 1 else 0
            val targetFilename = "image_${index}.webp"
            val targetFile = File(imagesDir, targetFilename)
            val newUri = if (targetFile.exists()) Uri.fromFile(targetFile) else uri

            if (info.isNew) {
                val imageValues = ContentValues().apply {
                    put(COLUMN_ROOM_ID, roomId)
                    put(COLUMN_IMAGE_URI, newUri.toString())
                    put(COLUMN_DISPLAY_ORDER, index)
                    put(COLUMN_IS_TRANSLATED, isTranslated)
                }
                val imageId = db.insert(TABLE_IMAGES, null, imageValues)
                if (imageId != -1L) {
                    ensureChangeRecord(imageId, roomId)
                    translatedTexts[uri]?.let { (_, textBlocks) ->
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
            } else {
                val imageId = info.existingImageId!!
                val imageValues = ContentValues().apply {
                    put(COLUMN_DISPLAY_ORDER, index)
                    put(COLUMN_IS_TRANSLATED, isTranslated)
                    put(COLUMN_IMAGE_URI, newUri.toString())
                }
                db.update(TABLE_IMAGES, imageValues, "$COLUMN_IMAGE_ID = ?", arrayOf(imageId.toString()))

                val uriFilename = lastNameOf(uri)
                val translationEntry = translatedTexts[uri] ?: translatedTexts.entries.find { (k, _) ->
                    lastNameOf(k) == uriFilename
                }?.value

                if (translationEntry != null) {
                    val (_, textBlocks) = translationEntry
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
                    clearImageChange(imageId)
                    translationDao.deletePending(imageId)
                }
            }
        }

        if (imageUris.isNotEmpty()) {
            val coverUri = imageUris.first().toString()
            val roomValues = ContentValues().apply { put(COLUMN_COVER_URI, coverUri) }
            db.update(TABLE_ROOMS, roomValues, "$COLUMN_ROOM_ID = ?", arrayOf(roomId.toString()))
        }
        db.setTransactionSuccessful()
        cleanupDuplicateImages(roomId)
        return true
    } catch (e: Exception) {
        Log.e(TAG, "Lỗi khi cập nhật truyện $roomId", e)
        return false
    } finally {
        db.endTransaction()
    }
}

