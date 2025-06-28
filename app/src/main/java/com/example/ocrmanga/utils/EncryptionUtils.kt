package com.example.ocrmanga.utils

import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object EncryptionUtils {
    // Mã hóa chuỗi bằng Base64
    fun encodeBase64(input: String): String {
        return Base64.encodeToString(input.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
    }

    // Giải mã chuỗi Base64
    fun decodeBase64(encoded: String): String {
        return String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
    }

    // Nén mảng byte bằng GZIP
    fun compressBytes(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    // Giải nén mảng byte bằng GZIP
    fun decompressBytes(compressed: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(compressed)
        GZIPInputStream(bis).use { gzip ->
            return gzip.readBytes()
        }
    }

    // Chuyển Bitmap thành chuỗi nén mã hóa (Base64)
    fun bitmapToCompressedBase64(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, quality: Int = 100): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(format, quality, baos)
        val compressed = compressBytes(baos.toByteArray())
        return Base64.encodeToString(compressed, Base64.DEFAULT)
    }

    // Chuyển chuỗi nén mã hóa (Base64) thành Bitmap
    fun compressedBase64ToBitmap(base64: String): Bitmap? {
        return try {
            val compressed = Base64.decode(base64, Base64.DEFAULT)
            val bytes = decompressBytes(compressed)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
