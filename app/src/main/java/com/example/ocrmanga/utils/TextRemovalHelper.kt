package com.example.ocrmanga.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import com.example.ocrmanga.data.models.TextBlockInfo
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Utility class để xóa text gốc trên ảnh sử dụng Python inpainting
 */
object TextRemovalHelper {
    
    private const val TAG = "TextRemovalHelper"
    
    /**
     * Khởi tạo Python (gọi một lần khi app khởi động)
     */
    private var inpainter: com.example.ocrmanga.ml.PythonTextRemover? = null

    fun initializePython(context: Context) {
        if (inpainter == null) {
            inpainter = com.example.ocrmanga.ml.PythonTextRemover(context.applicationContext)
            Log.d(TAG, "PythonTextRemover initialized")
        }
    }
    
    /**
     * Xóa text gốc từ ảnh sử dụng inpainting
     * 
     * @param context Context của app
     * @param imageUri Uri của ảnh gốc
     * @param blocks Danh sách các TextBlockInfo chứa tọa độ vùng text cần xóa
     * @return Uri của ảnh đã xóa text, hoặc null nếu thất bại
     */
    suspend fun removeTextFromImage(
        context: Context,
        imageUri: Uri,
        blocks: List<TextBlockInfo>
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Khởi tạo Python nếu chưa
            initializePython(context)
            
            // Lấy đường dẫn thực của ảnh
            val imagePath = getRealPathFromUri(context, imageUri) ?: run {
                Log.e(TAG, "Cannot get real path from Uri: $imageUri")
                return@withContext null
            }
            
            // Chuyển blocks thành JSON
            val blocksData = blocks.map { block ->
                mapOf(
                    "x" to block.bounds.left,
                    "y" to block.bounds.top,
                    "width" to block.bounds.width(),
                    "height" to block.bounds.height()
                )
            }
            val blocksJson = Gson().toJson(blocksData)
            
            // Tạo file output tạm
            val outputFile = File(context.cacheDir, "inpainted_${System.currentTimeMillis()}.jpg")
            val outputPath = outputFile.absolutePath
            
            Log.d(TAG, "Starting text removal: image=$imagePath, blocks=${blocksData.size}, output=$outputPath")

            // Use PythonTextRemover (subprocess) instead of Chaquopy to call text_remover
            val remover = inpainter ?: run {
                Log.e(TAG, "PythonTextRemover not initialized")
                return@withContext null
            }

            // Convert blocks to android.graphics.Rect list
            val rects = blocks.map { b -> b.bounds }

            val resultBitmap = remover.inpaintFromBlocks(BitmapFactory.decodeFile(imagePath), rects)

            if (resultBitmap == null) {
                Log.e(TAG, "Python inpainting returned null or failed")
                return@withContext null
            }

            // Save result bitmap to output file (JPEG)
            java.io.FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            return@withContext Uri.fromFile(outputFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error removing text from image", e)
            return@withContext null
        }
    }
    
    /**
     * Lấy đường dẫn thực từ Uri
     */
    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        return try {
            // Decode bitmap từ Uri để đảm bảo tương thích với mọi định dạng (bao gồm WebP)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (bitmap == null) {
                Log.e(TAG, "Cannot decode bitmap from Uri: $uri")
                return null
            }
            
            // Lưu bitmap dưới dạng JPEG (format mà PIL luôn hỗ trợ)
            val tempFile = File(context.cacheDir, "temp_input_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            
            // Recycle bitmap để giải phóng bộ nhớ
            bitmap.recycle()
            
            Log.d(TAG, "Converted image to JPEG: ${tempFile.absolutePath}")
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error getting real path from Uri", e)
            null
        }
    }

    /**
     * Xóa text từ ảnh sử dụng mask bitmap (vùng brusing)
     *
     * @param context Context
     * @param imageUri Uri ảnh gốc
     * @param maskBitmap Bitmap chứa mask (vùng cần xóa vẽ màu trắng/đỏ trên nền trong suốt hoặc đen)
     * @return Uri của ảnh kết quả
     */
    suspend fun removeTextWithMask(
        context: Context,
        imageUri: Uri,
        maskBitmap: Bitmap
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            initializePython(context)

            // Lấy path ảnh gốc
            val imagePath = getRealPathFromUri(context, imageUri) ?: return@withContext null

            // Lưu mask bitmap ra file
            val maskFile = File(context.cacheDir, "mask_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(maskFile).use { out ->
                maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val remover = inpainter ?: run {
                Log.e(TAG, "PythonTextRemover not initialized for mask inpaint")
                return@withContext null
            }

            // Convert image to Bitmap and call inpaintWithMask
            val srcBmp = BitmapFactory.decodeFile(imagePath)
            val resultBitmap = remover.inpaintWithMask(srcBmp, maskBitmap)

            if (resultBitmap == null) {
                Log.e(TAG, "Mask-based inpainting failed")
                return@withContext null
            }

            val outputFile = File(context.cacheDir, "inpainted_mask_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            return@withContext Uri.fromFile(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "Error removing text with mask", e)
            return@withContext null
        }
    }
}
