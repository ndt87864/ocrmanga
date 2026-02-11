package com.example.ocrmanga.backup

import android.content.Context
import android.net.Uri
import com.example.ocrmanga.utils.AppLogger as Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.AbstractInputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as GDriveFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.FileInputStream
import java.io.OutputStream

// Custom FileContent class để theo dõi progress upload
class ProgressFileContent(
    type: String,
    private val file: File,
    private val onProgress: (Long) -> Unit
) : AbstractInputStreamContent(type) {
    
    override fun getLength(): Long = file.length()
    
    override fun retrySupported(): Boolean = true
    
    override fun getInputStream(): InputStream {
        return ProgressInputStream(FileInputStream(file), onProgress)
    }
}

// Custom InputStream để theo dõi bytes đã đọc
class ProgressInputStream(
    private val inputStream: InputStream,
    private val onProgress: (Long) -> Unit
) : InputStream() {
    
    private var totalBytesRead = 0L
    
    override fun read(): Int {
        val result = inputStream.read()
        if (result != -1) {
            totalBytesRead++
            onProgress(totalBytesRead)
        }
        return result
    }
    
    override fun read(b: ByteArray): Int {
        val result = inputStream.read(b)
        if (result > 0) {
            totalBytesRead += result
            onProgress(totalBytesRead)
        }
        return result
    }
    
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val result = inputStream.read(b, off, len)
        if (result > 0) {
            totalBytesRead += result
            onProgress(totalBytesRead)
        }
        return result
    }
    
    override fun close() {
        inputStream.close()
    }
}

class BackupManager(private val context: Context, private val googleAccount: GoogleSignInAccount) {
    private val credential: GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
        context, listOf(DriveScopes.DRIVE_FILE)
    ).apply {
        selectedAccount = googleAccount.account
    }
    private val driveService: Drive = Drive.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        credential
    ).setApplicationName("OCR Manga").build()

    /**
     * Backup all app data to Google Drive. This will zip the app's data directory and upload it.
     * @param onProgress callback to report backup progress (0.0 to 1.0)
     * @return true if backup succeeded, false otherwise
     */
    fun backupAppData(onProgress: ((Float) -> Unit)? = null): Boolean {
        return try {
            val appDataDir: java.io.File = context.filesDir.parentFile!! // /data/data/<package>
            val backupFile = java.io.File(context.cacheDir, "ocrmanga_backup.zip")
            // Thư mục nội bộ
            val filesDir = java.io.File(appDataDir, "files")
            val imagesDir = java.io.File(filesDir, "images")
            // Thư mục external storage
            val externalFilesDir = context.getExternalFilesDir(null)
            val externalImagesDir =
                if (externalFilesDir != null) java.io.File(externalFilesDir, "images") else null
            val safeDirs = mutableListOf<java.io.File>(
                filesDir,
                java.io.File(appDataDir, "databases"),
                java.io.File(appDataDir, "shared_prefs")
            )
            // Nếu thư mục images nội bộ tồn tại, thêm vào backup
            if (imagesDir.exists() && imagesDir.isDirectory) {
                safeDirs.add(imagesDir)
            }
            // Nếu thư mục images ngoài (external) tồn tại, thêm vào backup
            if (externalImagesDir != null && externalImagesDir.exists() && externalImagesDir.isDirectory) {
                safeDirs.add(externalImagesDir)
            }

            // Xóa các file backup cũ trên Google Drive trước khi upload mới
            onProgress?.invoke(0.1f)
            try {
                val oldBackups = driveService.files().list()
                    .setQ("mimeType='application/zip' and name contains 'ocrmanga_backup_'")
                    .setFields("files(id, name)")
                    .execute().files
                oldBackups?.forEach { file ->
                    try {
                        driveService.files().delete(file.id).execute()
                    } catch (e: Exception) {
                        Log.w("BackupManager", "Failed to delete old backup: ${file.name}", e)
                    }
                }
            } catch (e: Exception) {
                Log.w("BackupManager", "Failed to list/delete old backups", e)
            }

            onProgress?.invoke(0.2f)
            if (!zipSafeDirectories(appDataDir, safeDirs, backupFile, onProgress)) {
                Log.e("BackupManager", "Failed to zip safe app data")
                return false
            }

            // Lấy kích thước file zip để tính progress upload
            val zipFileSize = backupFile.length()
            onProgress?.invoke(0.8f)

            val gFile = GDriveFile().apply {
                name = "ocrmanga_backup_${System.currentTimeMillis()}.zip"
                mimeType = "application/zip"
            }

            // Upload với progress tracking dựa trên dung lượng
            if (zipFileSize > 0) {
                val mediaContent =
                    ProgressFileContent("application/zip", backupFile) { uploadedBytes ->
                        // Upload chiếm 20% cuối của toàn bộ quá trình (từ 80% đến 100%)
                        val uploadProgress =
                            0.8f + (uploadedBytes.toFloat() / zipFileSize.toFloat()) * 0.2f
                        onProgress?.invoke(uploadProgress)
                    }
                driveService.files().create(gFile, mediaContent)
                    .setFields("id, name")
                    .execute()
            } else {
                val mediaContent = FileContent("application/zip", backupFile)
                driveService.files().create(gFile, mediaContent)
                    .setFields("id, name")
                    .execute()
            }

            onProgress?.invoke(1.0f)
            backupFile.delete()
            true
        } catch (e: Exception) {
            Log.e("BackupManager", "Backup failed", e)
            false
        }
    }

    /**
     * Zip only safe directories (files, databases, shared_prefs)
     */
    private fun zipSafeDirectories(
        rootDir: File,
        safeDirs: List<File>,
        zipFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        return try {
            // Đếm tổng số files để có progress chính xác hơn
            val totalFiles = safeDirs.sumOf { dir ->
                if (dir.exists()) countFilesRecursively(dir) else 0
            }
            var processedFiles = 0

            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                safeDirs.forEach { dir ->
                    if (dir.exists()) {
                        // Nếu là external images, zip với entryName tương đối từ images/
                        val externalFilesDir = context.getExternalFilesDir(null)
                        if (externalFilesDir != null && dir.absolutePath == java.io.File(
                                externalFilesDir.absolutePath,
                                "images"
                            ).absolutePath
                        ) {
                            processedFiles += zipFileRecursivelyCustomRoot(
                                dir,
                                dir,
                                zos,
                                "images",
                                totalFiles,
                                processedFiles,
                                onProgress
                            )
                        } else {
                            processedFiles += zipFileRecursively(
                                rootDir,
                                dir,
                                zos,
                                totalFiles,
                                processedFiles,
                                onProgress
                            )
                        }
                    }
                }
            }
            true
        } catch (e: IOException) {
            Log.e("BackupManager", "Zip error", e)
            false
        }
    }

    // Đếm tổng số files trong thư mục
    private fun countFilesRecursively(dir: File): Int {
        var count = 0
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    count += countFilesRecursively(child)
                } else {
                    count++
                }
            }
        } else {
            count = 1
        }
        return count
    }

    // Zip thư mục, entryName luôn bắt đầu từ images/... (tương đối từ customRoot)
    private fun zipFileRecursivelyCustomRoot(
        customRoot: File,
        srcFile: File,
        zos: java.util.zip.ZipOutputStream,
        entryRoot: String,
        totalFiles: Int = 0,
        processedFiles: Int = 0,
        onProgress: ((Float) -> Unit)? = null
    ): Int {
        var filesProcessed = 0
        // Luôn tạo entryName là images/... (không có dấu .., không absolutePath)
        val relPath =
            if (srcFile == customRoot) "" else srcFile.relativeTo(customRoot).invariantSeparatorsPath
        val entryName = if (relPath.isEmpty()) entryRoot else "$entryRoot/$relPath"
        if (entryName.contains("..")) return 0 // Bỏ qua entryName không hợp lệ

        if (srcFile.isDirectory) {
            val files = srcFile.listFiles()
            if (files == null || files.isEmpty()) {
                try {
                    val dirEntryName = if (entryName.endsWith("/")) entryName else "$entryName/"
                    val entry = java.util.zip.ZipEntry(dirEntryName)
                    zos.putNextEntry(entry)
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w("BackupManager", "Skip empty dir: ${srcFile.absolutePath} (${e.message})")
                }
            } else {
                files.forEach { child ->
                    filesProcessed += zipFileRecursivelyCustomRoot(
                        customRoot,
                        child,
                        zos,
                        entryRoot,
                        totalFiles,
                        processedFiles + filesProcessed,
                        onProgress
                    )
                }
            }
        } else {
            try {
                val entry = java.util.zip.ZipEntry(entryName)
                zos.putNextEntry(entry)
                srcFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                filesProcessed = 1

                // Update progress (zip is from 20% to 80%)
                if (totalFiles > 0) {
                    val zipProgress =
                        0.2f + ((processedFiles + filesProcessed).toFloat() / totalFiles.toFloat()) * 0.6f
                    onProgress?.invoke(zipProgress.coerceAtMost(0.8f))
                }
            } catch (e: Exception) {
                Log.w("BackupManager", "Skip file: ${srcFile.absolutePath} (${e.message})")
            }
        }
        return filesProcessed
    }

    /**
     * Zip a directory recursively.
     */
    private fun zipDirectory(srcDir: File?, zipFile: File): Boolean {
        if (srcDir == null || !srcDir.exists()) return false
        return try {
            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                zipFileRecursively(srcDir, srcDir, zos)
            }
            true
        } catch (e: IOException) {
            Log.e("BackupManager", "Zip error", e)
            false
        }
    }

    private fun zipFileRecursively(
        rootDir: File,
        srcFile: File,
        zos: java.util.zip.ZipOutputStream,
        totalFiles: Int = 0,
        processedFiles: Int = 0,
        onProgress: ((Float) -> Unit)? = null
    ): Int {
        var filesProcessed = 0
        val entryName = srcFile.relativeTo(rootDir).path.replace("\\", "/")

        if (srcFile.isDirectory) {
            // Đảm bảo thêm entry cho thư mục rỗng
            val files = srcFile.listFiles()
            if (files == null || files.isEmpty()) {
                try {
                    val dirEntryName = if (entryName.endsWith("/")) entryName else "$entryName/"
                    val entry = java.util.zip.ZipEntry(dirEntryName)
                    zos.putNextEntry(entry)
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w("BackupManager", "Skip empty dir: ${srcFile.absolutePath} (${e.message})")
                }
            } else {
                files.forEach { child ->
                    filesProcessed += zipFileRecursively(
                        rootDir,
                        child,
                        zos,
                        totalFiles,
                        processedFiles + filesProcessed,
                        onProgress
                    )
                }
            }
        } else {
            try {
                val entry = java.util.zip.ZipEntry(entryName)
                zos.putNextEntry(entry)
                srcFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                filesProcessed = 1

                // Update progress (zip is from 20% to 80%)
                if (totalFiles > 0) {
                    val zipProgress =
                        0.2f + ((processedFiles + filesProcessed).toFloat() / totalFiles.toFloat()) * 0.6f
                    onProgress?.invoke(zipProgress.coerceAtMost(0.8f))
                }
            } catch (e: Exception) {
                Log.w("BackupManager", "Skip file: ${srcFile.absolutePath} (${e.message})")
            }
        }
        return filesProcessed
    }
}
