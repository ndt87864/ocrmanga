package com.example.ocrmanga.viewmodels

import android.app.Application
import android.net.Uri
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
            val rooms = databaseHelper.getAllRooms()
            _uiState.update { it.copy(savedRooms = rooms) }
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
}

data class GalleryUiState(
    val selectedImages: List<Uri> = emptyList(),
    val savedRooms: List<Triple<Long, String, Uri>> = emptyList() // roomId, title, coverUri
)