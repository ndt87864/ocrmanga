package com.example.ocrmanga

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ocrmanga.ui.screens.api.ApiKeyManagementScreen
import com.example.ocrmanga.ui.screens.GalleryScreen
import com.example.ocrmanga.ui.screens.view.ViewerScreen
import com.example.ocrmanga.ui.theme.OCRMangaTheme
import com.example.ocrmanga.ui.screens.settings.ThemeSettingsScreen
import com.example.ocrmanga.ui.screens.DonateScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Gallery : Screen("gallery", "Trang chủ", Icons.Default.Home)
    object Donate : Screen("donate", "Ủng hộ", Icons.Default.Favorite)
    object ApiKey : Screen("apiKeyManagement", "API AI", Icons.Default.AutoAwesome)
    object Settings : Screen("themeSettings", "Cài đặt", Icons.Default.Settings)
}

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
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    val items = listOf(
                        Screen.Gallery,
                        Screen.Donate,
                        Screen.ApiKey,
                        Screen.Settings
                    )

                    Scaffold(
                        bottomBar = {
                            if (currentDestination?.route in items.map { it.route }) {
                                NavigationBar(modifier = Modifier.height(56.dp)) {
                                    items.forEach { screen ->
                                        NavigationBarItem(
                                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                                            label = { Text(screen.title) },
                                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                            onClick = {
                                                navController.navigate(screen.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = "gallery",
                            modifier = Modifier.padding(innerPadding)
                        ) {
                            composable("gallery") {
                                GalleryScreen(
                                    onNavigateToViewer = { imageUris: List<String> ->
                                        navController.currentBackStackEntry?.savedStateHandle?.set("imageUris", imageUris)
                                        navController.currentBackStackEntry?.savedStateHandle?.remove<Long>("roomId")
                                        navController.navigate("viewer")
                                    },
                                    onNavigateToRoom = { roomId ->
                                        navController.currentBackStackEntry?.savedStateHandle?.set("roomId", roomId)
                                        navController.currentBackStackEntry?.savedStateHandle?.remove<List<String>>("imageUris")
                                        navController.navigate("viewer")
                                    }
                                )
                            }
                            composable("donate") {
                                DonateScreen()
                            }
                            composable("viewer") {
                                val prev = navController.previousBackStackEntry
                                val imageUris = prev?.savedStateHandle?.get<List<String>>("imageUris") ?: emptyList()
                                val roomId = prev?.savedStateHandle?.get<Long>("roomId")

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
                                ThemeSettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
