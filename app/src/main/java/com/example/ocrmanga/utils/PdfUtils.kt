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
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val pdfFile = File(outputDir, "${roomTitle}_$timestamp.pdf")
            
            val pdfDocument = PdfDocument()
            
            imageUris.forEachIndexed { pageIndex, imageUri ->
                // Tải và decode bitmap
                val bitmap = loadBitmapFromUri(context, imageUri) ?: return@forEachIndexed
                
                // Tạo trang PDF
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                
                // Vẽ ảnh gốc
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                
                // Vẽ các bản dịch lên ảnh
                translations[imageUri]?.let { (_, textBlocks) ->
                    textBlocks.forEach { textBlock ->
                        drawTextBlock(canvas, textBlock)
                    }
                }
                
                pdfDocument.finishPage(page)
                bitmap.recycle()
            }
            
            // Lưu PDF
            val fileOutputStream = FileOutputStream(pdfFile)
            pdfDocument.writeTo(fileOutputStream)
            fileOutputStream.close()
            pdfDocument.close()
            
            Log.i(TAG, "PDF exported successfully: ${pdfFile.absolutePath}")
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
            val document = PDDocument.load(inputStream)
            val renderer = PDFRenderer(document)
            val bitmaps = mutableListOf<Bitmap>()
            
            for (i in 0 until document.numberOfPages) {
                // Render page at 150 DPI (good quality vs file size balance)
                val bitmap = renderer.renderPageBitmap(i, 1.5f)
                bitmaps.add(bitmap)
            }
            
            document.close()
            inputStream?.close()
            
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
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = textBlock.fontSize
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

        // Tính toán kích thước text
        val textWidth = paint.measureText(text)
        val textHeight = paint.fontMetrics.bottom - paint.fontMetrics.top

        // Vẽ nền cho text nếu cần
        when (textBlock.shapeType) {
            0 -> { // Rectangle
                canvas.drawRect(bounds, backgroundPaint)
            }
            1 -> { // Oval
                canvas.drawOval(RectF(bounds), backgroundPaint)
            }
        }

        // Tính toán vị trí để căn giữa text trong bounds
        val x = bounds.left + (bounds.width() - textWidth) / 2
        val y = bounds.top + (bounds.height() + textHeight) / 2 - paint.fontMetrics.bottom

        // Xoay canvas nếu cần
        if (textBlock.rotation != null && textBlock.rotation != 0f) {
            canvas.save()
            canvas.rotate(textBlock.rotation, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }

        // Vẽ text
        canvas.drawText(text, x, y, paint)

        // Khôi phục canvas nếu đã xoay
        if (textBlock.rotation != null && textBlock.rotation != 0f) {
            canvas.restore()
        }
    }
}