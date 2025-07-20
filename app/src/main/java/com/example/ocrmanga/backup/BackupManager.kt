package com.example.ocrmanga.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as GDriveFile
import java.io.File
import java.io.IOException

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
     * @return true if backup succeeded, false otherwise
     */
    fun backupAppData(): Boolean {
        return try {
            val appDataDir: java.io.File = context.filesDir.parentFile!! // /data/data/<package>
            val backupFile = java.io.File(context.cacheDir, "ocrmanga_backup.zip")
            // Thư mục nội bộ
            val filesDir = java.io.File(appDataDir, "files")
            val imagesDir = java.io.File(filesDir, "images")
            // Thư mục external storage
            val externalFilesDir = context.getExternalFilesDir(null)
            val externalImagesDir = if (externalFilesDir != null) java.io.File(externalFilesDir, "images") else null
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

            if (!zipSafeDirectories(appDataDir, safeDirs, backupFile)) {
                Log.e("BackupManager", "Failed to zip safe app data")
                return false
            }
            val gFile = GDriveFile().apply {
                name = "ocrmanga_backup_${System.currentTimeMillis()}.zip"
                mimeType = "application/zip"
            }
            val mediaContent = FileContent("application/zip", backupFile)
            driveService.files().create(gFile, mediaContent)
                .setFields("id, name")
                .execute()
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
    private fun zipSafeDirectories(rootDir: File, safeDirs: List<File>, zipFile: File): Boolean {
        return try {
            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                for (dir in safeDirs) {
                    if (dir.exists()) {
                        // Nếu là external images, zip với entryName tương đối từ images/
                        val externalFilesDir = context.getExternalFilesDir(null)
                        if (externalFilesDir != null && dir.absolutePath == java.io.File(externalFilesDir.absolutePath, "images").absolutePath) {
                            zipFileRecursivelyCustomRoot(dir, dir, zos, "images")
                        } else {
                            zipFileRecursively(rootDir, dir, zos)
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

    // Zip thư mục, entryName luôn bắt đầu từ images/... (tương đối từ customRoot)
    private fun zipFileRecursivelyCustomRoot(customRoot: File, srcFile: File, zos: java.util.zip.ZipOutputStream, entryRoot: String) {
        // Luôn tạo entryName là images/... (không có dấu .., không absolutePath)
        val relPath = if (srcFile == customRoot) "" else srcFile.relativeTo(customRoot).invariantSeparatorsPath
        val entryName = if (relPath.isEmpty()) entryRoot else "$entryRoot/$relPath"
        if (entryName.contains("..")) return // Bỏ qua entryName không hợp lệ
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
                    zipFileRecursivelyCustomRoot(customRoot, child, zos, entryRoot)
                }
            }
        } else {
            try {
                val entry = java.util.zip.ZipEntry(entryName)
                zos.putNextEntry(entry)
                srcFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            } catch (e: Exception) {
                Log.w("BackupManager", "Skip file: ${srcFile.absolutePath} (${e.message})")
            }
        }
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

    private fun zipFileRecursively(rootDir: File, srcFile: File, zos: java.util.zip.ZipOutputStream) {
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
                    zipFileRecursively(rootDir, child, zos)
                }
            }
        } else {
            try {
                val entry = java.util.zip.ZipEntry(entryName)
                zos.putNextEntry(entry)
                srcFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            } catch (e: Exception) {
                Log.w("BackupManager", "Skip file: ${srcFile.absolutePath} (${e.message})")
            }
        }
    }
}
