package com.example.ocrmanga.backup

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.FileList
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class RestoreManager(private val context: Context, private val googleAccount: GoogleSignInAccount) {
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
     * Download the latest backup zip from Google Drive and restore app data.
     * @return true if restore succeeded, false otherwise
     */
    fun restoreAppData(): Boolean {
        return try {
            // 1. Tìm file backup mới nhất trên Drive
            val backupFileId = findLatestBackupFileId()
            if (backupFileId == null) {
                // Thông báo rõ ràng chưa có dữ liệu sao lưu
                Toast.makeText(context, "Chưa có dữ liệu sao lưu trên Google Drive!", Toast.LENGTH_LONG).show()
                return false
            }
            val localZip = java.io.File(context.cacheDir, "ocrmanga_restore.zip")
            // 2. Tải file zip về máy
            java.io.FileOutputStream(localZip.absolutePath).use { outputStream: java.io.OutputStream ->
                driveService.files().get(backupFileId).executeMediaAndDownloadTo(outputStream)
            }
            // 3. Giải nén vào app data
            unzipToAppData(localZip)
            localZip.delete()
            true
        } catch (e: Exception) {
            Log.e("RestoreManager", "Restore failed", e)
            Toast.makeText(context, "Đồng bộ hóa thất bại!", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Giải nén file zip vào các thư mục dữ liệu app (files, databases, shared_prefs) và external images nếu có
     */
    private fun unzipToAppData(zipFile: File) {
        val appDataDir = context.filesDir.parentFile!!
        val externalFilesDir = context.getExternalFilesDir(null)
        java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                val isDir = entry.isDirectory
                val outFile: java.io.File? = when {
                    entryName.startsWith("images/") && externalFilesDir != null -> {
                        java.io.File(externalFilesDir.absolutePath, entryName)
                    }
                    else -> {
                        java.io.File(appDataDir.absolutePath, entryName)
                    }
                }
                if (outFile != null) {
                    if (isDir) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        java.io.FileOutputStream(outFile.absolutePath).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Tìm file backup zip mới nhất trên Google Drive
     */
    private fun findLatestBackupFileId(): String? {
        val result = driveService.files().list()
            .setQ("mimeType='application/zip' and name contains 'ocrmanga_backup_'")
            .setFields("files(id, name, createdTime)")
            .execute()
        val files = result.files
        val latest = files.maxByOrNull { it.createdTime.value }
        return latest?.id
    }

}
