package com.example.ocrmanga.utils

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import com.example.ocrmanga.data.models.TextBlockInfo
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

object PdfUtils {
    private const val TAG = "PdfUtils"

    /**
     * Xuất phòng thành file PDF với ảnh và bản dịch được ghi đè
     */
    suspend fun exportRoomToPdf(
        context: Context,
        roomTitle: String,
        imageUris: List<Uri>,
        translations: Map<Uri, Pair<String, List<TextBlockInfo>>>,
        outputDir: File
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (imageUris.isEmpty()) {
                Log.w(TAG, "No images to export to PDF")
                return@withContext null
            }

            // Ensure output directory exists
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val sanitizedTitle = roomTitle.replace(Regex("[^a-zA-Z0-9_\\- ]"), "_")
            val pdfFile = File(outputDir, "${sanitizedTitle}_$timestamp.pdf")
            
            val pdfDocument = PdfDocument()
            var pageCount = 0
            
            imageUris.forEachIndexed { pageIndex, imageUri ->
                try {
                    // Tải và decode bitmap
                    val bitmap = loadBitmapFromUri(context, imageUri)
                    if (bitmap == null) {
                        Log.w(TAG, "Failed to load bitmap for page $pageIndex, skipping")
                        return@forEachIndexed
                    }
                    
                    // Tạo trang PDF
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageIndex + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas
                    
                    // Vẽ ảnh gốc
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    
                    // Vẽ các bản dịch lên ảnh
                    translations[imageUri]?.let { (_, textBlocks) ->
                        textBlocks.forEach { textBlock ->
                            try {
                                drawTextBlock(canvas, textBlock)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error drawing text block on page $pageIndex", e)
                            }
                        }
                    }
                    
                    pdfDocument.finishPage(page)
                    bitmap.recycle()
                    pageCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing page $pageIndex", e)
                }
            }
            
            if (pageCount == 0) {
                Log.e(TAG, "No pages were successfully processed")
                pdfDocument.close()
                return@withContext null
            }
            
            // Lưu PDF
            val fileOutputStream = FileOutputStream(pdfFile)
            pdfDocument.writeTo(fileOutputStream)
            fileOutputStream.close()
            pdfDocument.close()
            
            Log.i(TAG, "PDF exported successfully: ${pdfFile.absolutePath} ($pageCount pages)")
            pdfFile
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting PDF", e)
            null
        }
    }

    /**
     * Trích xuất các trang từ PDF thành bitmap
     */
    suspend fun extractPagesFromPdf(context: Context, pdfUri: Uri): List<Bitmap>? = withContext(Dispatchers.IO) {
        try {
            PDFBoxResourceLoader.init(context)
            
            val inputStream = context.contentResolver.openInputStream(pdfUri)
            if (inputStream == null) {
                Log.e(TAG, "Cannot open input stream for PDF URI: $pdfUri")
                return@withContext null
            }
            
            val document = PDDocument.load(inputStream)
            if (document.numberOfPages == 0) {
                Log.w(TAG, "PDF has no pages")
                document.close()
                inputStream.close()
                return@withContext null
            }
            
            val renderer = PDFRenderer(document)
            val bitmaps = mutableListOf<Bitmap>()
            
            for (i in 0 until document.numberOfPages) {
                try {
                    // Render page at 150 DPI (good quality vs file size balance)
                    val bitmap = renderer.renderPageBitmap(i, 1.5f)
                    if (bitmap != null) {
                        bitmaps.add(bitmap)
                        Log.d(TAG, "Rendered page $i successfully")
                    } else {
                        Log.w(TAG, "Failed to render page $i")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error rendering page $i", e)
                }
            }
            
            document.close()
            inputStream.close()
            
            if (bitmaps.isEmpty()) {
                Log.e(TAG, "No pages could be extracted from PDF")
                return@withContext null
            }
            
            Log.i(TAG, "Extracted ${bitmaps.size} pages from PDF")
            bitmaps
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting PDF pages", e)
            null
        }
    }

    /**
     * Lưu các bitmap thành URI để sử dụng trong app
     */
    suspend fun saveBitmapsAsImages(context: Context, bitmaps: List<Bitmap>, roomDir: File): List<Uri> = withContext(Dispatchers.IO) {
        val uris = mutableListOf<Uri>()
        
        bitmaps.forEachIndexed { index, bitmap ->
            try {
                val imageFile = File(roomDir, "pdf_page_$index.jpg")
                val outputStream = FileOutputStream(imageFile)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                outputStream.close()
                
                uris.add(Uri.fromFile(imageFile))
                Log.i(TAG, "Saved PDF page $index as ${imageFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving bitmap as image for page $index", e)
            }
        }
        
        uris
    }

    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: $uri", e)
            null
        }
    }

    private fun drawTextBlock(canvas: Canvas, textBlock: TextBlockInfo) {
        try {
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = textBlock.fontSize.coerceIn(8f, 72f) // Clamp text size
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            // Tạo paint cho nền
            val backgroundPaint = Paint().apply {
                color = Color.WHITE
                alpha = 200 // Semi-transparent background
                style = Paint.Style.FILL
            }

            val bounds = textBlock.bounds
            val text = textBlock.text

            // Validate bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                Log.w(TAG, "Invalid bounds for text block: $bounds")
                return
            }

            // Tính toán kích thước text
            val textWidth = paint.measureText(text)
            val textHeight = paint.fontMetrics.bottom - paint.fontMetrics.top

            // Vẽ nền cho text nếu cần
            try {
                when (textBlock.shapeType) {
                    0 -> { // Rectangle
                        canvas.drawRect(bounds, backgroundPaint)
                    }
                    1 -> { // Oval
                        canvas.drawOval(RectF(bounds), backgroundPaint)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error drawing background shape", e)
            }

            // Tính toán vị trí để căn giữa text trong bounds
            val x = bounds.left + (bounds.width() - textWidth) / 2
            val y = bounds.top + (bounds.height() + textHeight) / 2 - paint.fontMetrics.bottom

            // Xoay canvas nếu cần
            val rotation = textBlock.rotation ?: 0f
            if (rotation != 0f && rotation.isFinite()) {
                canvas.save()
                canvas.rotate(rotation, bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }

            // Vẽ text - chia nhỏ nếu text quá dài
            if (textWidth > bounds.width() && text.length > 1) {
                // Split text into multiple lines if too wide
                val words = text.split(" ")
                var currentLine = ""
                var currentY = y

                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    val testWidth = paint.measureText(testLine)
                    
                    if (testWidth <= bounds.width() || currentLine.isEmpty()) {
                        currentLine = testLine
                    } else {
                        // Draw current line and start new one
                        val lineX = bounds.left + (bounds.width() - paint.measureText(currentLine)) / 2
                        canvas.drawText(currentLine, lineX, currentY, paint)
                        currentLine = word
                        currentY += textHeight * 0.8f
                        
                        // Stop if we're going outside bounds
                        if (currentY > bounds.bottom) break
                    }
                }
                
                // Draw the last line
                if (currentLine.isNotEmpty() && currentY <= bounds.bottom) {
                    val lineX = bounds.left + (bounds.width() - paint.measureText(currentLine)) / 2
                    canvas.drawText(currentLine, lineX, currentY, paint)
                }
            } else {
                // Draw single line
                canvas.drawText(text, x, y, paint)
            }

            // Khôi phục canvas nếu đã xoay
            if (rotation != 0f && rotation.isFinite()) {
                canvas.restore()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing text block: ${textBlock.text}", e)
        }
    }
}