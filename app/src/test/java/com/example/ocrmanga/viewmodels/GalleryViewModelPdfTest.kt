package com.example.ocrmanga.viewmodels

import android.app.Application
import android.net.Uri
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.Mockito.*
import com.example.ocrmanga.viewmodels.GalleryViewModel

/**
 * Test for GalleryViewModel PDF functionality
 */
@RunWith(MockitoJUnitRunner::class)
class GalleryViewModelPdfTest {

    @Mock
    private lateinit var application: Application

    private lateinit var viewModel: GalleryViewModel

    @Before
    fun setup() {
        viewModel = GalleryViewModel(application)
    }

    @Test
    fun testGalleryViewModelInitialization() {
        // Test that ViewModel can be initialized
        assertNotNull(viewModel)
    }

    @Test
    fun testExportRoomToPdfMethodExists() {
        // Test that the exportRoomToPdf method exists with correct signature
        val method = GalleryViewModel::class.java.getDeclaredMethod(
            "exportRoomToPdf",
            Long::class.javaPrimitiveType,
            Function1::class.java
        )
        assertNotNull(method)
        assertEquals("exportRoomToPdf", method.name)
    }

    @Test
    fun testProcessPdfFileMethodExists() {
        // Test that the processPdfFile method exists with correct signature  
        val method = GalleryViewModel::class.java.getDeclaredMethod(
            "processPdfFile",
            Uri::class.java,
            Function1::class.java
        )
        assertNotNull(method)
        assertEquals("processPdfFile", method.name)
    }

    @Test
    fun testUiStateInitialization() {
        // Test that the UI state is properly initialized
        val uiState = viewModel.uiState.value
        assertNotNull(uiState)
        assertEquals(emptyList<Uri>(), uiState.selectedImages)
        assertEquals(emptyList<Triple<Long, String, Uri>>(), uiState.savedRooms)
    }
}