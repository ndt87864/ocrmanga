package com.example.ocrmanga.utils

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.widget.Toast
import com.example.ocrmanga.data.database.DatabaseHelper
import com.example.ocrmanga.data.models.TextBlockInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.graphics.pdf.PdfDocument
import com.example.ocrmanga.ui.screens.view.computeDefaultTextColor

object ViewerExportHelper {
    private const val TAG = "ViewerExportHelper"

    fun convertRoomToPDF(
        application: Application,
        roomId: Long?,
        imageUris: List<Uri>,
        onResult: (String?) -> Unit
    ) {
        try {
            val context = application.applicationContext
            val pdfDocument = PdfDocument()

            imageUris.forEachIndexed { index, uri ->
                val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width, bitmap.height, true)
                val pageInfo = PdfDocument.PageInfo.Builder(scaledBitmap.width, scaledBitmap.height, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val paint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                    isDither = true
                }
                page.canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)
                pdfDocument.finishPage(page)
            }
            val pdfFile = File(context.getExternalFilesDir(null), "room_${roomId}.pdf")
            pdfDocument.writeTo(FileOutputStream(pdfFile))
            pdfDocument.close()
            onResult(pdfFile.absolutePath)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error creating PDF", e)
            onResult(null)
        }
    }

    suspend fun exportRoomAsZip(
        application: Application,
        roomId: Long,
        databaseHelper: DatabaseHelper,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ): String? = withContext(Dispatchers.IO) {
        onStart()
        try {
            val (allImages, _, translations) = databaseHelper.getMangaRoomOptimized(roomId)
            if (allImages.isEmpty()) return@withContext null
            val timestamp = System.currentTimeMillis()
            val fileName = "room_${roomId}_$timestamp.zip"

            var zipFile: File? = null
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null) {
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    if (downloadsDir.exists() && downloadsDir.canWrite()) {
                        zipFile = File(downloadsDir, fileName)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Unable to prepare Downloads dir, will fallback to app exports", e)
                zipFile = null
            }

            if (zipFile == null) {
                val exportsDir = File(application.getExternalFilesDir(null), "exports")
                if (!exportsDir.exists()) exportsDir.mkdirs()
                zipFile = File(exportsDir, fileName)
            }

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val buffer = ByteArray(8 * 1024)
                var idx = 0
                for (uri in allImages) {
                    try {
                        val entryName = try { File(uri.path ?: "image_${idx}.webp").name } catch (e: Exception) { "image_${idx}.webp" }
                        val pair = translations[uri]
                        if (pair != null) {
                            try {
                                val cr = application.contentResolver
                                cr.openInputStream(uri)?.use { input ->
                                    val src = BitmapFactory.decodeStream(input) ?: return@use
                                    val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
                                    val canvas = Canvas(bmp)

                                    for (block in pair.second) {
                                        if (block.text.isBlank()) continue
                                        try {
                                            val blockOriginalW = block.originalImageWidth?.toFloat() ?: src.width.toFloat()
                                            val srcScaleX = if (blockOriginalW > 0) src.width.toFloat() / blockOriginalW else 1f

                                            val blockOriginalH = block.originalImageHeight?.toFloat() ?: src.height.toFloat()
                                            val scaledBlockH = blockOriginalH * srcScaleX
                                            val offsetY = if (src.height.toFloat() > scaledBlockH) (src.height.toFloat() - scaledBlockH) / 2f else 0f

                                            val adjBoundsLeft = block.bounds.left * srcScaleX
                                            val adjBoundsTop = block.bounds.top * srcScaleX + offsetY
                                            val adjBoundsWidth = (block.bounds.right - block.bounds.left) * srcScaleX
                                            val adjBoundsHeight = (block.bounds.bottom - block.bounds.top) * srcScaleX

                                            val boundsWidth = adjBoundsWidth
                                            val boundsHeight = adjBoundsHeight

                                            val rawOverlayColor = block.customOverlayColor ?: block.averageBackgroundColor ?: 0xFFFFFFFF.toInt()
                                            val overlayColor = if (block.overlaySaturation != 1.0f) {
                                                val hsv = FloatArray(3)
                                                androidx.core.graphics.ColorUtils.colorToHSL(rawOverlayColor, hsv)
                                                hsv[1] = (hsv[1] * block.overlaySaturation).coerceIn(0f, 1f)
                                                androidx.core.graphics.ColorUtils.HSLToColor(hsv)
                                            } else {
                                                rawOverlayColor
                                            }

                                            val overlayPaint = Paint().apply {
                                                isAntiAlias = true
                                                style = Paint.Style.FILL
                                                color = overlayColor
                                                alpha = (block.overlayAlpha * 255).toInt().coerceIn(0, 255)
                                            }

                                            val bitmapRect = androidx.compose.ui.geometry.Rect(
                                                adjBoundsLeft,
                                                adjBoundsTop,
                                                adjBoundsLeft + adjBoundsWidth,
                                                adjBoundsTop + adjBoundsHeight
                                            )

                                            val displayMetrics = application.resources.displayMetrics
                                            val refScreenWidthPx = 360f * displayMetrics.density
                                            val exportFontScale = src.width.toFloat() / refScreenWidthPx

                                            val insetH = (if (block.overlayInsetHorizontal != 0f) block.overlayInsetHorizontal else block.overlayInset) * exportFontScale
                                            val insetV = (if (block.overlayInsetVertical != 0f) block.overlayInsetVertical else block.overlayInset) * exportFontScale

                                            val windowedResult = com.example.ocrmanga.ui.screens.view.calculateWindowedOverlayBounds(
                                                originalBounds = bitmapRect,
                                                text = block.text,
                                                baseFontSize = block.fontSize * exportFontScale,
                                                isVertical = block.isVertical,
                                                context = application,
                                                fontFamilyName = block.fontFamily,
                                                lineSpacing = block.lineSpacing,
                                                shapeType = block.shapeType,
                                                overlayInsetHorizontal = insetH,
                                                overlayInsetVertical = insetV,
                                                horizontalPadding = 4f * exportFontScale,
                                                verticalPadding = 4f * exportFontScale
                                            )
                                            val outerBounds = windowedResult.outerBounds
                                            val innerBounds = windowedResult.innerBounds
                                            val finalFontSizeForBitmap = windowedResult.optimalFontSize

                                            val overlayRectF = RectF(
                                                innerBounds.left,
                                                innerBounds.top,
                                                innerBounds.right,
                                                innerBounds.bottom
                                            )

                                            val overlayRotationAngle = block.overlayRotation ?: 0f
                                            val cx = outerBounds.center.x
                                            val cy = outerBounds.center.y

                                            if (overlayRotationAngle != 0f) {
                                                canvas.save()
                                                canvas.rotate(overlayRotationAngle, cx, cy)
                                            }

                                            if (block.shapeType == 1) {
                                                canvas.drawOval(overlayRectF, overlayPaint)
                                            } else {
                                                canvas.drawRect(overlayRectF, overlayPaint)
                                            }

                                            if (overlayRotationAngle != 0f) {
                                                canvas.restore()
                                            }

                                            val textRenderLeft = innerBounds.left + innerBounds.width * (if (block.shapeType == 1) 0.15f else 0f)
                                            val textRenderTop = innerBounds.top + innerBounds.height * (if (block.shapeType == 1) 0.15f else 0f)
                                            val textRenderWidth = innerBounds.width * (if (block.shapeType == 1) 0.7f else 1f)
                                            val textRenderHeight = innerBounds.height * (if (block.shapeType == 1) 0.7f else 1f)

                                            val safeTextRenderWidth = (textRenderWidth - 8f * exportFontScale).coerceAtLeast(1f)
                                            val wrappedTextLines = com.example.ocrmanga.ui.screens.view.wrapText(
                                                text = block.text,
                                                width = safeTextRenderWidth,
                                                fontSize = finalFontSizeForBitmap,
                                                context = application,
                                                fontFamilyName = block.fontFamily
                                            )
                                            val wrappedText = wrappedTextLines.joinToString("\n")

                                            val rawTextColor = block.customTextColor ?: block.originalTextColor ?: computeDefaultTextColor(overlayColor or 0xFF000000.toInt(), block.averageBackgroundColor)
                                            var textColor = if (block.textSaturation != 1.0f) {
                                                val hsv = FloatArray(3)
                                                androidx.core.graphics.ColorUtils.colorToHSL(rawTextColor, hsv)
                                                hsv[1] = (hsv[1] * block.textSaturation).coerceIn(0f, 1f)
                                                androidx.core.graphics.ColorUtils.HSLToColor(hsv)
                                            } else {
                                                rawTextColor
                                            }

                                            val typeface = try {
                                                com.example.ocrmanga.ui.screens.view.getCachedTypefaceForExport(application, block.fontFamily)
                                            } catch (e: Exception) {
                                                null
                                            }

                                            fun mapAlign(a: com.example.ocrmanga.data.models.TextAlignMode): Paint.Align = when(a) {
                                                com.example.ocrmanga.data.models.TextAlignMode.LEFT -> Paint.Align.LEFT
                                                com.example.ocrmanga.data.models.TextAlignMode.CENTER -> Paint.Align.CENTER
                                            }
                                            val tp = TextPaint().apply {
                                                isAntiAlias = true
                                                color = textColor
                                                textSize = finalFontSizeForBitmap
                                                textAlign = mapAlign(block.textAlign)
                                                this.typeface = typeface ?: Typeface.DEFAULT

                                                if (block.textBoldness > 1.0f) {
                                                    style = Paint.Style.FILL_AND_STROKE
                                                    strokeWidth = ((block.textBoldness - 1.0f) * 2.0f) * exportFontScale
                                                } else if (block.textBoldness < 1.0f) {
                                                    alpha = (255 * block.textBoldness).toInt().coerceIn(50, 255)
                                                }
                                            }

                                            val gradientColorsArr = block.textGradientColors?.toIntArray()
                                            val gradientPositionsArr = block.textGradientOffsets?.toFloatArray()
                                            val gradientType = block.textGradientType

                                            var borderPaint = if (block.customBorderColor != null && block.borderThickness > 0f) {
                                                TextPaint().apply {
                                                    isAntiAlias = true
                                                    color = block.customBorderColor
                                                    alpha = (block.borderAlpha * 255).toInt().coerceIn(0, 255)
                                                    textSize = finalFontSizeForBitmap
                                                    textAlign = mapAlign(block.textAlign)
                                                    style = Paint.Style.STROKE
                                                    strokeWidth = block.borderThickness * exportFontScale
                                                    this.typeface = typeface ?: Typeface.DEFAULT
                                                }
                                            } else null

                                            var shadowPaint = if (block.customShadowColor != null) {
                                                TextPaint().apply {
                                                    isAntiAlias = true
                                                    color = block.customShadowColor
                                                    alpha = (block.shadowAlpha * 255).toInt().coerceIn(0, 255)
                                                    textSize = finalFontSizeForBitmap
                                                    textAlign = mapAlign(block.textAlign)
                                                    style = Paint.Style.FILL
                                                    this.typeface = typeface ?: Typeface.DEFAULT
                                                    val radius = if (block.shadowRadius > 0f) {
                                                        block.shadowRadius * exportFontScale
                                                    } else {
                                                        (finalFontSizeForBitmap * 0.14f).coerceAtLeast(1f)
                                                    }
                                                    val dx = finalFontSizeForBitmap * 0.04f
                                                    val dy = finalFontSizeForBitmap * 0.04f
                                                    setShadowLayer(radius, dx, dy, block.customShadowColor)
                                                }
                                            } else null

                                            canvas.save()
                                            val rotation = block.rotation ?: 0f
                                            if (rotation != 0f) canvas.rotate(rotation, cx, cy)

                                            val lines = wrappedText.split("\n")
                                            val fontMetrics = tp.fontMetrics
                                            val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * block.lineSpacing

                                            val textLeft = textRenderLeft
                                            val textTop = textRenderTop
                                            val textDrawWidth = textRenderWidth
                                            val textDrawHeight = textRenderHeight

                                            if (block.isVertical) {
                                                var currentX = textLeft + textDrawWidth - lineHeight
                                                for (line in lines) {
                                                    if (line.isNotBlank() && currentX >= textLeft) {
                                                        canvas.save()
                                                        canvas.translate(currentX, textTop)
                                                        canvas.rotate(90f)
                                                        val lineWidth = tp.measureText(line)
                                                        val centeredY = (textDrawHeight - lineWidth) / 2
                                                        shadowPaint?.let { canvas.drawText(line, centeredY, -fontMetrics.ascent, it) }
                                                        borderPaint?.let { canvas.drawText(line, centeredY, -fontMetrics.ascent, it) }
                                                        if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                                                            com.example.ocrmanga.ui.screens.view.drawTextPerCharacter(
                                                                canvas, line, centeredY, -fontMetrics.ascent, tp,
                                                                gradientColorsArr, gradientPositionsArr, gradientType, fontMetrics
                                                            )
                                                        } else {
                                                            canvas.drawText(line, centeredY, -fontMetrics.ascent, tp)
                                                        }
                                                        canvas.restore()
                                                        currentX -= lineHeight
                                                    }
                                                }
                                            } else {
                                                val totalTextHeight = lines.size * lineHeight
                                                val verticalMargin = (textDrawHeight - totalTextHeight) / 2f
                                                val startY = textTop + verticalMargin - fontMetrics.ascent
                                                var currentY = startY

                                                for (line in lines) {
                                                    if (line.isNotBlank()) {
                                                        val centerX = textLeft + textDrawWidth / 2f
                                                        when (block.textAlign) {
                                                            com.example.ocrmanga.data.models.TextAlignMode.LEFT -> {
                                                                val paddingLeft = 4f * exportFontScale
                                                                val drawX = textLeft + paddingLeft

                                                                tp.textAlign = Paint.Align.LEFT
                                                                shadowPaint?.textAlign = Paint.Align.LEFT
                                                                borderPaint?.textAlign = Paint.Align.LEFT

                                                                shadowPaint?.let { canvas.drawText(line, drawX, currentY, it) }
                                                                borderPaint?.let { canvas.drawText(line, drawX, currentY, it) }
                                                                if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                                                                    com.example.ocrmanga.ui.screens.view.drawTextPerCharacter(
                                                                        canvas, line, drawX, currentY, tp,
                                                                        gradientColorsArr, gradientPositionsArr, gradientType, fontMetrics
                                                                    )
                                                                } else {
                                                                    canvas.drawText(line, drawX, currentY, tp)
                                                                }
                                                            }
                                                            else -> {
                                                                tp.textAlign = Paint.Align.CENTER
                                                                shadowPaint?.textAlign = Paint.Align.CENTER
                                                                borderPaint?.textAlign = Paint.Align.CENTER
                                                                
                                                                shadowPaint?.let { canvas.drawText(line, centerX, currentY, it) }
                                                                borderPaint?.let { canvas.drawText(line, centerX, currentY, it) }
                                                                if (gradientColorsArr != null && gradientColorsArr.size >= 2) {
                                                                    com.example.ocrmanga.ui.screens.view.drawTextPerCharacter(
                                                                        canvas, line, centerX, currentY, tp,
                                                                        gradientColorsArr, gradientPositionsArr, gradientType, fontMetrics
                                                                    )
                                                                } else {
                                                                    canvas.drawText(line, centerX, currentY, tp)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    currentY += lineHeight
                                                }
                                            }

                                            canvas.restore()
                                        } catch (e: Exception) {
                                            AppLogger.w(TAG, "Failed to render block for uri=$uri", e)
                                        }
                                    }

                                    val baos = ByteArrayOutputStream()
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                                    val bytes = baos.toByteArray()
                                    zos.putNextEntry(ZipEntry(entryName))
                                    zos.write(bytes)
                                    zos.closeEntry()
                                }
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Failed to render image with translations for $uri", e)
                            }
                        } else {
                            try {
                                val path = uri.path
                                if (!path.isNullOrBlank()) {
                                    val f = File(path)
                                    if (f.exists()) {
                                        zos.putNextEntry(ZipEntry(entryName))
                                        BufferedInputStream(FileInputStream(f)).use { bis ->
                                            var len = bis.read(buffer)
                                            while (len > 0) {
                                                zos.write(buffer, 0, len)
                                                len = bis.read(buffer)
                                            }
                                        }
                                        zos.closeEntry()
                                    } else {
                                        application.contentResolver.openInputStream(uri)?.use { input ->
                                            zos.putNextEntry(ZipEntry(entryName))
                                            BufferedInputStream(input).use { bis ->
                                                var len = bis.read(buffer)
                                                while (len > 0) {
                                                    zos.write(buffer, 0, len)
                                                    len = bis.read(buffer)
                                                }
                                            }
                                            zos.closeEntry()
                                        }
                                    }
                                } else {
                                    application.contentResolver.openInputStream(uri)?.use { input ->
                                        zos.putNextEntry(ZipEntry(entryName))
                                        BufferedInputStream(input).use { bis ->
                                            var len = bis.read(buffer)
                                            while (len > 0) {
                                                zos.write(buffer, 0, len)
                                                len = bis.read(buffer)
                                            }
                                        }
                                        zos.closeEntry()
                                    }
                                }
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Failed to add original image to zip: $uri", e)
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to add image to zip: $uri", e)
                    }
                    idx++
                }
            }
            onEnd()
            zipFile.absolutePath
        } catch (e: Exception) {
            AppLogger.e(TAG, "exportRoomAsZip failed for room $roomId", e)
            onEnd()
            null
        }
    }
}
