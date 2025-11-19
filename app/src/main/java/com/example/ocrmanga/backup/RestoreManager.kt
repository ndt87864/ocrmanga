package com.example.ocrmanga.backup

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        mainHandler.post {
            Toast.makeText(context, message, duration).show()
        }
    }


    /**
     * Download the latest backup zip from Google Drive and restore app data.
     * @param onProgress callback to report download progress (0.0 to 1.0)
     * @return true if restore succeeded, false otherwise
     */
    fun restoreAppData(onProgress: ((Float) -> Unit)? = null): Boolean {
        return try {
            // 1. Tìm file backup mới nhất trên Drive
            val backupFileId = findLatestBackupFileId()
            if (backupFileId == null) {
                // Thông báo rõ ràng chưa có dữ liệu sao lưu
                showToast("Chưa có dữ liệu sao lưu trên Google Drive!")
                return false
            }

            // Get file size for progress tracking
            val fileMetadata = driveService.files().get(backupFileId).setFields("size").execute()
            val fileSize = fileMetadata.size?.toLong() ?: 0L
            
            val localZip = java.io.File(context.cacheDir, "ocrmanga_restore.zip")
            
            // 2. Tải file zip về máy với progress tracking dựa trên dung lượng thực tế
            if (fileSize > 0) {
                var downloadedBytes = 0L
                val buffer = ByteArray(8192)
                
                driveService.files().get(backupFileId).executeMediaAsInputStream().use { inputStream: java.io.InputStream ->
                    java.io.FileOutputStream(localZip.absolutePath).use { outputStream: java.io.OutputStream ->
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytes -> bytesRead = bytes } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            // Progress = (bytes tải xuống / tổng dung lượng file) * 100
                            // Download chiếm 90% của toàn bộ quá trình restore
                            val downloadProgress = (downloadedBytes.toFloat() / fileSize.toFloat()) * 0.9f
                            onProgress?.invoke(downloadProgress)
                        }
                    }
                }
            } else {
                // Fallback if file size is unknown
                java.io.FileOutputStream(localZip.absolutePath).use { outputStream: java.io.OutputStream ->
                    driveService.files().get(backupFileId).executeMediaAndDownloadTo(outputStream)
                }
                onProgress?.invoke(0.9f)
            }
            
            // 3. Giải nén vào app data (remaining 10% of progress)
            onProgress?.invoke(0.95f)
            unzipToAppData(localZip)
            
            // 4. Xử lý đặc biệt cho Android 9: kiểm tra và sửa quyền file
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                fixFilePermissionsForAndroid9()
            }
            
            // 5. Thực hiện các bước sau restore để đảm bảo app nhận dữ liệu
            postRestoreActions()
            
            // 5.5. Delay dài để đảm bảo tất cả database operations đã hoàn thành
            Thread.sleep(3000)
            
            // 6. Debug: kiểm tra database sau restore với retry logic
            debugDatabaseAfterRestoreWithRetry()
            
            onProgress?.invoke(1.0f)
            localZip.delete()
            
            // 7. Thông báo user restart app và tự động restart nếu có thể
            val externalFilesAvailable = context.getExternalFilesDir(null) != null
            if (externalFilesAvailable) {
                showToast("Khôi phục thành công! QUAN TRỌNG: Hãy đóng và mở lại ứng dụng để xem dữ liệu đã khôi phục.")
                // Tự động restart app sau 3 giây
                restartAppAfterDelay()
            } else {
                showToast("Khôi phục hoàn tất nhưng external storage không khả dụng. Hãy restart app và kiểm tra lại.")
            }
            
            true
        } catch (e: Exception) {
            Log.e("RestoreManager", "Restore failed", e)
            showToast("Đồng bộ hóa thất bại: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Giải nén file zip vào các thư mục dữ liệu app (files, databases, shared_prefs) và external images nếu có
     * Sửa để tương thích với Android 9
     */
    private fun unzipToAppData(zipFile: File) {
        val appDataDir = context.filesDir.parentFile!!
        val externalFilesDir = context.getExternalFilesDir(null)
        
        // Kiểm tra và tạo external images directory trước
        if (externalFilesDir != null) {
            val externalImagesDir = File(externalFilesDir, "images")
            if (!externalImagesDir.exists()) {
                val created = externalImagesDir.mkdirs()
                Log.d("RestoreManager", "Created external images directory: $created, path: ${externalImagesDir.absolutePath}")
            }
            
            // Kiểm tra quyền ghi vào external storage
            val testFile = File(externalImagesDir, "test_write.tmp")
            try {
                testFile.createNewFile()
                val canWrite = testFile.exists() && testFile.canWrite()
                testFile.delete()
                Log.d("RestoreManager", "External storage write test: $canWrite")
                if (!canWrite) {
                    Log.w("RestoreManager", "Cannot write to external storage!")
                }
            } catch (e: Exception) {
                Log.e("RestoreManager", "Error testing external storage write", e)
            }
        } else {
            Log.w("RestoreManager", "External files directory is not available! This will cause issues with image loading.")
        }
        
        java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                val isDir = entry.isDirectory
                
                val outFile: java.io.File? = when {
                    entryName.startsWith("images/") -> {
                        // Images MUST be restored to external storage vì DatabaseHelper expect chúng ở đó
                        val targetFile = if (externalFilesDir != null) {
                            java.io.File(externalFilesDir.absolutePath, entryName)
                        } else {
                            Log.e("RestoreManager", "Cannot restore images: External storage not available")
                            null // Skip image files if external storage not available
                        }
                        if (targetFile != null) {
                            Log.d("RestoreManager", "Image target path for $entryName: ${targetFile.absolutePath}")
                        }
                        targetFile
                    }
                    else -> {
                        java.io.File(appDataDir.absolutePath, entryName)
                    }
                }
                
                if (outFile != null) {
                    try {
                        if (isDir) {
                            val created = outFile.mkdirs()
                            Log.d("RestoreManager", "Created directory $outFile: $created")
                        } else {
                            // Đảm bảo parent directory tồn tại
                            outFile.parentFile?.let { parent ->
                                if (!parent.exists()) {
                                    val created = parent.mkdirs()
                                    Log.d("RestoreManager", "Created parent directory $parent: $created")
                                }
                            }
                            
                            // Kiểm tra quyền ghi
                            if (outFile.parentFile?.canWrite() == true || outFile.parentFile == null) {
                                java.io.FileOutputStream(outFile.absolutePath).use { fos ->
                                    zis.copyTo(fos)
                                    fos.flush()
                                }
                                Log.d("RestoreManager", "Restored file: ${outFile.absolutePath}")
                            } else {
                                Log.w("RestoreManager", "Cannot write to: ${outFile.parentFile?.absolutePath}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("RestoreManager", "Error processing $entryName", e)
                        // Tiếp tục với entry tiếp theo thay vì dừng toàn bộ quá trình
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        
        // Đồng bộ filesystem để đảm bảo dữ liệu được ghi
        try {
            Runtime.getRuntime().exec("sync")
        } catch (e: Exception) {
            Log.w("RestoreManager", "Could not sync filesystem", e)
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

    /**
     * Sửa quyền file cho Android 9 sau khi restore
     */
    private fun fixFilePermissionsForAndroid9() {
        try {
            val appDataDir = context.filesDir.parentFile!!
            
            // Đệ quy sửa quyền cho tất cả file trong app data
            fun fixPermissions(file: File) {
                if (file.exists()) {
                    try {
                        if (file.isDirectory) {
                            file.setReadable(true, false)
                            file.setWritable(true, false)
                            file.setExecutable(true, false)
                            file.listFiles()?.forEach { child ->
                                fixPermissions(child)
                            }
                        } else {
                            file.setReadable(true, false)
                            file.setWritable(true, false)
                        }
                    } catch (e: Exception) {
                        Log.w("RestoreManager", "Could not fix permissions for: ${file.absolutePath}", e)
                    }
                }
            }
            
            // Sửa quyền cho databases
            val databasesDir = File(appDataDir, "databases")
            if (databasesDir.exists()) {
                fixPermissions(databasesDir)
            }
            
            // Sửa quyền cho shared_prefs
            val sharedPrefsDir = File(appDataDir, "shared_prefs")
            if (sharedPrefsDir.exists()) {
                fixPermissions(sharedPrefsDir)
            }
            
            // Sửa quyền cho files directory
            fixPermissions(context.filesDir)
            
        } catch (e: Exception) {
            Log.e("RestoreManager", "Error fixing file permissions", e)
        }
    }

    /**
     * Các hành động sau khi restore để đảm bảo app nhận dữ liệu
     */
    private fun postRestoreActions() {
        try {
            // 1. Xóa cache của database để force reload
            val databasesDir = File(context.filesDir.parentFile!!, "databases")
            if (databasesDir.exists()) {
                // Tìm các file database và xóa file -wal, -shm (cache files)
                databasesDir.listFiles()?.forEach { file ->
                    when {
                        file.name.endsWith("-wal") -> {
                            val deleted = file.delete()
                            Log.d("RestoreManager", "Deleted WAL file ${file.name}: $deleted")
                        }
                        file.name.endsWith("-shm") -> {
                            val deleted = file.delete()
                            Log.d("RestoreManager", "Deleted SHM file ${file.name}: $deleted")
                        }
                        file.name.endsWith("-journal") -> {
                            val deleted = file.delete()
                            Log.d("RestoreManager", "Deleted journal file ${file.name}: $deleted")
                        }
                    }
                }
            }
            
            // 2. Clear app preferences cache
            val sharedPrefsDir = File(context.filesDir.parentFile!!, "shared_prefs")
            Log.d("RestoreManager", "SharedPrefs dir exists: ${sharedPrefsDir.exists()}")
            sharedPrefsDir.listFiles()?.forEach { file ->
                Log.d("RestoreManager", "SharedPref file found: ${file.name}")
            }
            
            // 3. Verify image files exist and are accessible
            val imageFiles = mutableListOf<String>()
            fun scanImageFiles(dir: File, prefix: String = "") {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isDirectory) {
                            scanImageFiles(file, "$prefix${file.name}/")
                        } else if (file.name.endsWith(".jpg") || file.name.endsWith(".png")) {
                            imageFiles.add("$prefix${file.name}")
                        }
                    }
                }
            }
            
            // Kiểm tra images trong internal storage
            val internalImagesDir = File(context.filesDir, "images")
            scanImageFiles(internalImagesDir)
            Log.d("RestoreManager", "Found ${imageFiles.size} image files in internal storage")
            
            // Kiểm tra images trong external storage  
            val externalImagesDir = context.getExternalFilesDir(null)?.let { File(it, "images") }
            if (externalImagesDir != null) {
                val externalImageFiles = mutableListOf<String>()
                fun scanExternalImageFiles(dir: File, prefix: String = "") {
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isDirectory) {
                                scanExternalImageFiles(file, "$prefix${file.name}/")
                            } else if (file.name.endsWith(".jpg") || file.name.endsWith(".png")) {
                                externalImageFiles.add("$prefix${file.name}")
                            }
                        }
                    }
                }
                scanExternalImageFiles(externalImagesDir)
                Log.d("RestoreManager", "Found ${externalImageFiles.size} image files in external storage")
                Log.d("RestoreManager", "External images dir: ${externalImagesDir.absolutePath}, exists: ${externalImagesDir.exists()}")
                
                // List some sample files
                externalImageFiles.take(5).forEach { file ->
                    Log.d("RestoreManager", "External image sample: $file")
                }
            } else {
                Log.d("RestoreManager", "External files directory not available")
            }
            
            // 4. Force flush system buffers
            try {
                Runtime.getRuntime().exec("sync").waitFor()
            } catch (e: Exception) {
                Log.w("RestoreManager", "Could not sync filesystem", e)
            }
            
            Log.d("RestoreManager", "Post-restore actions completed")
            
        } catch (e: Exception) {
            Log.e("RestoreManager", "Error in post-restore actions", e)
        }
    }

    /**
     * Debug: kiểm tra database sau khi restore
     */
    private fun debugDatabaseAfterRestore() {
        try {
            val databasePath = File(context.filesDir.parentFile!!, "databases")
            Log.d("RestoreManager", "Database directory exists: ${databasePath.exists()}")
            
            if (databasePath.exists()) {
                databasePath.listFiles()?.forEach { file ->
                    Log.d("RestoreManager", "Database file: ${file.name}, size: ${file.length()} bytes")
                }
                
                // Thử truy cập database để kiểm tra dữ liệu
                try {
                    // Sử dụng database name đúng 
                    val dbNames = listOf("MangaDownloader.db", "manga_database.db")
                    var successfulDb: String? = null
                    
                    for (dbName in dbNames) {
                        try {
                            val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
                            
                            // Kiểm tra các table tồn tại
                            val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                            val tables = mutableListOf<String>()
                            while (cursor.moveToNext()) {
                                tables.add(cursor.getString(0))
                            }
                            cursor.close()
                            Log.d("RestoreManager", "Database $dbName - Tables found: $tables")
                            
                            // Nếu có tables hữu ích, sử dụng database này
                            if (tables.any { it.contains("room") || it.contains("image") || it.contains("manga") || it.contains("Room") || it.contains("Image") }) {
                                successfulDb = dbName
                                
                                // Kiểm tra số lượng rooms
                                val roomTables = tables.filter { it.contains("room", ignoreCase = true) }
                                roomTables.forEach { tableName ->
                                    try {
                                        val roomCursor = db.rawQuery("SELECT COUNT(*) FROM `$tableName`", null)
                                        if (roomCursor.moveToFirst()) {
                                            val roomCount = roomCursor.getInt(0)
                                            Log.d("RestoreManager", "Table $tableName: $roomCount records")
                                        }
                                        roomCursor.close()
                                    } catch (e: Exception) {
                                        Log.w("RestoreManager", "Could not query table $tableName", e)
                                    }
                                }
                                
                                // Kiểm tra số lượng images
                                val imageTables = tables.filter { it.contains("image", ignoreCase = true) }
                                imageTables.forEach { tableName ->
                                    try {
                                        val imageCursor = db.rawQuery("SELECT COUNT(*) FROM `$tableName`", null)
                                        if (imageCursor.moveToFirst()) {
                                            val imageCount = imageCursor.getInt(0)
                                            Log.d("RestoreManager", "Table $tableName: $imageCount records")
                                        }
                                        imageCursor.close()
                                    } catch (e: Exception) {
                                        Log.w("RestoreManager", "Could not query table $tableName", e)
                                    }
                                }
                            }
                            
                            db.close()
                            if (successfulDb != null) break
                        } catch (e: Exception) {
                            Log.e("RestoreManager", "Error accessing database $dbName", e)
                        }
                    }
                    
                    if (successfulDb == null) {
                        Log.w("RestoreManager", "No usable database found! This may cause data display issues.")
                        showToast("Cảnh báo: Database có thể chưa được restore đúng cách.")
                    } else {
                        Log.d("RestoreManager", "Successfully found usable database: $successfulDb")
                    }
                    
                } catch (e: Exception) {
                    Log.e("RestoreManager", "Error accessing database", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e("RestoreManager", "Error in database debug", e)
        }
    }

    /**
     * Restart app sau khi restore để tránh database conflicts
     */
    private fun restartAppAfterDelay() {
        try {
            // Sử dụng Handler để delay restart
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Tạo intent để restart app
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    
                    if (intent != null) {
                        context.startActivity(intent)
                        // Kill current process
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                } catch (e: Exception) {
                    Log.e("RestoreManager", "Error restarting app", e)
                }
            }, 3000) // 3 giây delay
        } catch (e: Exception) {
            Log.e("RestoreManager", "Error scheduling app restart", e)
        }
    }

    /**
     * Debug database sau restore với retry logic để tránh lock conflicts
     */
    private fun debugDatabaseAfterRestoreWithRetry() {
        try {
            Log.d("RestoreManager", "=== DEBUG DATABASE AFTER RESTORE WITH RETRY ===")
            
            // Retry logic với delays
            for (attempt in 1..3) {
                try {
                    Log.d("RestoreManager", "Database debug attempt $attempt")
                    
                    // Kiểm tra database directory và files
                    val databasesDir = File(context.getDatabasePath("dummy").parent)
                    Log.d("RestoreManager", "Database directory exists: ${databasesDir.exists()}")
                    
                    if (databasesDir.exists()) {
                        databasesDir.listFiles()?.forEach { file ->
                            if (file.isFile()) {
                                Log.d("RestoreManager", "Database file: ${file.name}, size: ${file.length()} bytes")
                            }
                        }
                    }
                    
                    // Thử mở database với timeout ngắn
                    val mangaDbPath = context.getDatabasePath("MangaDownloader.db")
                    if (mangaDbPath.exists()) {
                        Log.d("RestoreManager", "MangaDownloader.db exists, checking tables...")
                        
                        // Đợi một chút trước khi thử truy cập database
                        Thread.sleep((1000 * attempt).toLong())
                        
                        // Tạo connection đơn giản để check tables
                        checkDatabaseTablesQuietly()
                        
                        break // Thành công, thoát khỏi retry loop
                    } else {
                        Log.d("RestoreManager", "MangaDownloader.db not found")
                    }
                    
                } catch (e: Exception) {
                    Log.w("RestoreManager", "Database debug attempt $attempt failed: ${e.message}")
                    if (attempt == 3) {
                        Log.e("RestoreManager", "All database debug attempts failed", e)
                    } else {
                        Thread.sleep(2000) // Đợi lâu hơn trước khi retry
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("RestoreManager", "Error in database debug with retry", e)
        }
    }
    
    /**
     * Kiểm tra database tables một cách an toàn
     */
    private fun checkDatabaseTablesQuietly() {
        try {
            // Sử dụng SQLiteDatabase.openDatabase để tránh SQLiteOpenHelper conflicts
            val dbPath = context.getDatabasePath("MangaDownloader.db").absolutePath
            val database = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbPath, 
                null, 
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            
            val cursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", 
                null
            )
            val tables = mutableListOf<String>()
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0))
            }
            cursor.close()
            database.close()
            
            Log.d("RestoreManager", "Database tables found: $tables")
            
            if (tables.contains("rooms") && tables.contains("room_images")) {
                Log.d("RestoreManager", "Required tables found: rooms, room_images")
            } else {
                Log.w("RestoreManager", "Missing required tables. Found: $tables")
            }
            
        } catch (e: Exception) {
            Log.w("RestoreManager", "Could not check database tables quietly: ${e.message}")
        }
    }

    /**
     * Force restart app để reload dữ liệu
     */
    fun restartApp() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(it)
                
                // Force exit current process
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        } catch (e: Exception) {
            Log.e("RestoreManager", "Error restarting app", e)
        }
    }

}
