package com.xrdavies.mob.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xrdavies.mob.ui.chat.ChatScreen
import com.xrdavies.mob.ui.settings.SettingsScreen
import com.xrdavies.mob.ui.tasks.TasksScreen
import com.xrdavies.mob.viewmodel.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobApp(appState: AppState) {
    val navController = rememberNavController()
    var currentRoute by remember { mutableStateOf("chat") }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Chat, "Chat") },
                    label = { Text("Chat") },
                    selected = currentRoute == "chat",
                    onClick = {
                        currentRoute = "chat"
                        navController.navigate("chat") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, "Tasks") },
                    label = { Text("Tasks") },
                    selected = currentRoute == "tasks",
                    onClick = {
                        currentRoute = "tasks"
                        navController.navigate("tasks") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, "Settings") },
                    label = { Text("Settings") },
                    selected = currentRoute == "settings",
                    onClick = {
                        currentRoute = "settings"
                        navController.navigate("settings") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            NavHost(navController, startDestination = "chat") {
                composable("chat") { ChatScreen(appState) }
                composable("tasks") { TasksScreen(appState) }
                composable("settings") { SettingsScreen(appState) }
            }
        }
    }
}
