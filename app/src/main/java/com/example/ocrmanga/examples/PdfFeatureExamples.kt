package com.example.ocrmanga.examples

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.utils.PdfUtils
import java.io.File

/**
 * Example usage of PDF functionality in OCR Manga app
 * This class demonstrates how to use the new PDF features
 */
class PdfFeatureExamples {

    /**
     * Example: Export a room to PDF
     */
    suspend fun exportRoomToPdfExample(context: Context) {
        // Sample data that would come from a room
        val roomTitle = "My Manga Collection"
        val imageUris = listOf(
            Uri.parse("file:///storage/emulated/0/Pictures/page1.jpg"),
            Uri.parse("file:///storage/emulated/0/Pictures/page2.jpg")
        )
        
        // Sample translation data with text overlays
        val translations = mapOf(
            imageUris[0] to Pair("Original text", listOf(
                TextBlockInfo(
                    text = "Hello World",
                    bounds = Rect(100, 200, 300, 250),
                    fontSize = 24f,
                    shapeType = 0 // Rectangle
                ),
                TextBlockInfo(
                    text = "Translated text",
                    bounds = Rect(100, 300, 400, 350),
                    fontSize = 20f,
                    rotation = 0f,
                    shapeType = 1 // Oval
                )
            )),
            imageUris[1] to Pair("Another text", listOf(
                TextBlockInfo(
                    text = "Page 2 content",
                    bounds = Rect(50, 100, 250, 150),
                    fontSize = 18f
                )
            ))
        )
        
        // Output directory for PDF
        val outputDir = File(context.getExternalFilesDir(null), "PDFs")
        
        // Export to PDF
        val pdfFile = PdfUtils.exportRoomToPdf(
            context = context,
            roomTitle = roomTitle,
            imageUris = imageUris,
            translations = translations,
            outputDir = outputDir
        )
        
        if (pdfFile != null) {
            println("PDF exported successfully: ${pdfFile.absolutePath}")
        } else {
            println("Failed to export PDF")
        }
    }

    /**
     * Example: Import PDF and extract pages
     */
    suspend fun importPdfExample(context: Context) {
        // PDF file URI (would come from file picker)
        val pdfUri = Uri.parse("content://com.android.providers.downloads.documents/document/123")
        
        // Extract pages from PDF
        val bitmaps = PdfUtils.extractPagesFromPdf(context, pdfUri)
        
        if (bitmaps != null && bitmaps.isNotEmpty()) {
            println("Successfully extracted ${bitmaps.size} pages from PDF")
            
            // Save pages as images
            val tempDir = File(context.getExternalFilesDir(null), "temp_pdf_${System.currentTimeMillis()}")
            val imageUris = PdfUtils.saveBitmapsAsImages(context, bitmaps, tempDir)
            
            println("Saved ${imageUris.size} images from PDF pages")
            
            // Clean up bitmaps
            bitmaps.forEach { it.recycle() }
            
            // Now these image URIs can be used like regular images in the app
            // for OCR, translation, etc.
            
        } else {
            println("Failed to extract pages from PDF")
        }
    }

    /**
     * Example: Complete workflow - Import PDF, process, export back to PDF
     */
    suspend fun completeWorkflowExample(context: Context) {
        val pdfUri = Uri.parse("content://com.android.providers.downloads.documents/document/manga.pdf")
        
        // Step 1: Import PDF
        val bitmaps = PdfUtils.extractPagesFromPdf(context, pdfUri) ?: return
        val tempDir = File(context.getExternalFilesDir(null), "temp_workflow")
        val imageUris = PdfUtils.saveBitmapsAsImages(context, bitmaps, tempDir)
        
        // Step 2: Simulate OCR and translation process
        // (In real app, this would be done through ViewerScreen)
        val translations = mutableMapOf<Uri, Pair<String, List<TextBlockInfo>>>()
        
        imageUris.forEachIndexed { index, uri ->
            // Simulate some translation results
            translations[uri] = Pair("Original text $index", listOf(
                TextBlockInfo(
                    text = "Translated text for page ${index + 1}",
                    bounds = Rect(100, 100, 400, 150),
                    fontSize = 20f,
                    shapeType = 0
                )
            ))
        }
        
        // Step 3: Export back to PDF with translations
        val outputDir = File(context.getExternalFilesDir(null), "PDFs")
        val exportedPdf = PdfUtils.exportRoomToPdf(
            context = context,
            roomTitle = "Processed Manga",
            imageUris = imageUris,
            translations = translations,
            outputDir = outputDir
        )
        
        // Step 4: Cleanup
        bitmaps.forEach { it.recycle() }
        tempDir.deleteRecursively()
        
        if (exportedPdf != null) {
            println("Complete workflow successful: ${exportedPdf.absolutePath}")
        } else {
            println("Workflow failed")
        }
    }
}