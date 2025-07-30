package com.example.ocrmanga.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ocrmanga.data.database.DatabaseHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val databaseHelper = DatabaseHelper(application)
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    init {
        loadSavedRooms()
    }

    fun updateSelectedImages(uris: List<Uri>) {
        _uiState.update { it.copy(selectedImages = uris) }
    }

    fun clearSelectedImages() {
        _uiState.update { it.copy(selectedImages = emptyList()) }
    }

    fun loadSavedRooms() {
        viewModelScope.launch {
            try {
                Log.d("GalleryViewModel", "Loading saved rooms...")
                val rooms = databaseHelper.getAllRooms()
                Log.d("GalleryViewModel", "Found ${rooms.size} rooms")
                rooms.forEachIndexed { index, room ->
                    Log.d("GalleryViewModel", "Room $index: id=${room.first}, title=${room.second}, coverUri=${room.third}")
                }
                _uiState.update { it.copy(savedRooms = rooms) }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Error loading rooms", e)
                _uiState.update { it.copy(savedRooms = emptyList()) }
            }
        }
    }

    // Hàm để thông báo reload khi có dữ liệu mới
    fun notifyDataSaved() {
        loadSavedRooms()
    }

    // Thêm hàm xóa phòng
    fun deleteRoom(roomId: Long) {
        viewModelScope.launch {
            databaseHelper.deleteRoom(roomId)
            loadSavedRooms() // Cập nhật lại danh sách sau khi xóa
        }
    }

    // Thêm hàm đổi tên phòng
    fun updateRoomTitle(roomId: Long, newTitle: String) {
        viewModelScope.launch {
            databaseHelper.updateRoomTitle(roomId, newTitle)
            loadSavedRooms()
        }
    }

    // Thêm hàm điều hướng đến ApiKeyManagementScreen
    fun navigateToApiKeyManagement() {
        // Logic for navigation to ApiKeyManagementScreen
    }

    // Thêm hàm debug để kiểm tra database
    fun debugDatabase() {
        viewModelScope.launch {
            try {
                Log.d("GalleryViewModel", "=== DATABASE DEBUG ===")
                val rooms = databaseHelper.getAllRooms()
                Log.d("GalleryViewModel", "Total rooms in database: ${rooms.size}")
                
                // Kiểm tra database path
                val context = getApplication<Application>()
                val dbPath = context.getDatabasePath("manga_database.db")
                Log.d("GalleryViewModel", "Database path: ${dbPath.absolutePath}")
                Log.d("GalleryViewModel", "Database exists: ${dbPath.exists()}")
                Log.d("GalleryViewModel", "Database size: ${if (dbPath.exists()) dbPath.length() else 0} bytes")
                
                // Kiểm tra files directory
                val filesDir = context.filesDir
                Log.d("GalleryViewModel", "Files dir: ${filesDir.absolutePath}")
                val imagesDir = java.io.File(filesDir, "images")
                Log.d("GalleryViewModel", "Internal images dir exists: ${imagesDir.exists()}")
                
                // Kiểm tra external files directory
                val externalFilesDir = context.getExternalFilesDir(null)
                Log.d("GalleryViewModel", "External files dir: ${externalFilesDir?.absolutePath}")
                val externalImagesDir = externalFilesDir?.let { java.io.File(it, "images") }
                Log.d("GalleryViewModel", "External images dir exists: ${externalImagesDir?.exists()}")
                
                if (imagesDir.exists()) {
                    val imageFolders = imagesDir.listFiles()?.filter { it.isDirectory }
                    Log.d("GalleryViewModel", "Internal image folders count: ${imageFolders?.size ?: 0}")
                    imageFolders?.forEach { folder ->
                        val imageCount = folder.listFiles()?.count { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".png")) } ?: 0
                        Log.d("GalleryViewModel", "Internal folder ${folder.name}: $imageCount images")
                    }
                }
                
                if (externalImagesDir?.exists() == true) {
                    val externalImageFolders = externalImagesDir.listFiles()?.filter { it.isDirectory }
                    Log.d("GalleryViewModel", "External image folders count: ${externalImageFolders?.size ?: 0}")
                    externalImageFolders?.forEach { folder ->
                        val imageCount = folder.listFiles()?.count { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".png")) } ?: 0
                        Log.d("GalleryViewModel", "External folder ${folder.name}: $imageCount images")
                    }
                } else {
                    Log.d("GalleryViewModel", "External images directory does not exist")
                }
                
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Error in database debug", e)
            }
        }
    }
}

data class GalleryUiState(
    val selectedImages: List<Uri> = emptyList(),
    val savedRooms: List<Triple<Long, String, Uri>> = emptyList() // roomId, title, coverUri
)