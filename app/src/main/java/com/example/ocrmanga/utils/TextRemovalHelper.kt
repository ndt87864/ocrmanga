package com.example.ocrmanga.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
    fun initializePython(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
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
            
            // Gọi Python script
            val python = Python.getInstance()
            val module = python.getModule("text_remover")
            val result: PyObject = module.callAttr("remove_text", imagePath, blocksJson, outputPath)
            val resultString = result.toString()
            
            Log.d(TAG, "Python result: $resultString")
            
            // Kiểm tra kết quả
            if (resultString.startsWith("Error:")) {
                Log.e(TAG, "Python error: $resultString")
                return@withContext null
            }
            
            // Kiểm tra file output có tồn tại không
            if (!outputFile.exists()) {
                Log.e(TAG, "Output file does not exist: $outputPath")
                return@withContext null
            }
            
            Log.d(TAG, "Text removal successful: $outputPath")
            
            // Trả về Uri của file đã xóa text
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
            // Nếu là file:// uri, lấy path trực tiếp
            if (uri.scheme == "file") {
                return uri.path
            }
            
            // Nếu là content:// uri, copy sang cache rồi lấy path
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(context.cacheDir, "temp_input_${System.currentTimeMillis()}.jpg")
            
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
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
            val maskPath = maskFile.absolutePath

            // Tạo file output
            val outputFile = File(context.cacheDir, "inpainted_mask_${System.currentTimeMillis()}.jpg")
            val outputPath = outputFile.absolutePath

            Log.d(TAG, "Starting mask removal: image=$imagePath, mask=$maskPath")

            val python = Python.getInstance()
            val module = python.getModule("text_remover")
            
            // Gọi hàm Python
            val result = module.callAttr("remove_text_with_mask", imagePath, maskPath, outputPath)
            val resultString = result.toString()

            Log.d(TAG, "Python result: $resultString")

            if (resultString.startsWith("Error:")) {
                Log.e(TAG, "Python error: $resultString")
                return@withContext null
            }

            if (!outputFile.exists()) {
                return@withContext null
            }

            return@withContext Uri.fromFile(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "Error removing text with mask", e)
            return@withContext null
        }
    }
}
