package com.example.ocrmanga

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ocrmanga.ui.screens.api.ApiKeyManagementScreen
import com.example.ocrmanga.ui.screens.GalleryScreen
import com.example.ocrmanga.ui.screens.view.ViewerScreen
import com.example.ocrmanga.ui.theme.OCRMangaTheme
import com.example.ocrmanga.ui.screens.settings.ThemeSettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OCRMangaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "gallery") {
                        composable("gallery") {
                            GalleryScreen(
                                onNavigateBack = { finish() },
                                onNavigateToViewer = { imageUris: List<String> ->
                                    navController.currentBackStackEntry?.savedStateHandle?.set("imageUris", imageUris)
                                    navController.navigate("viewer")
                                },
                                onNavigateToRoom = { roomId ->
                                    navController.currentBackStackEntry?.savedStateHandle?.set("roomId", roomId)
                                    navController.navigate("viewer")
                                },
                                onNavigateToApiKeyManagement = {
                                    navController.navigate("apiKeyManagement")
                                },
                                onNavigateToThemeSettings = {
                                    navController.navigate("themeSettings")
                                }
                            )
                        }
                        composable("viewer") {
                            val imageUris = navController.previousBackStackEntry?.savedStateHandle?.get<List<String>>("imageUris") ?: emptyList()
                            val roomId = navController.previousBackStackEntry?.savedStateHandle?.get<Long>("roomId")
                            ViewerScreen(
                                imageUris = imageUris,
                                roomId = roomId,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("apiKeyManagement") {
                            ApiKeyManagementScreen()
                        }
                        composable("themeSettings") {
                            ThemeSettingsScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}