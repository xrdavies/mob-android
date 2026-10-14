package com.xrdavies.mob.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xrdavies.mob.ui.chat.ChatScreen
import com.xrdavies.mob.ui.chat.SessionDrawer
import com.xrdavies.mob.ui.settings.SettingsScreen
import com.xrdavies.mob.ui.tasks.TasksScreen
import com.xrdavies.mob.viewmodel.AppState
import kotlinx.coroutines.launch

private fun NavHostController.go(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun MobApp(appState: AppState) {
    val navController = rememberNavController()
    val route = navController.currentBackStackEntryAsState().value?.destination?.route ?: "chat"
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val showChat by appState.showChat.collectAsState()
    val waiting by appState.waitingCount.collectAsState()
    LaunchedEffect(showChat) {
        if (showChat > 0) {
            drawer.close()
            navController.go("chat")
        }
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = route == "chat",
        drawerContent = {
            SessionDrawer(
                appState,
                onClose = { scope.launch { drawer.close() } },
                onManageMachines = {
                    scope.launch { drawer.close() }
                    navController.go("settings")
                },
            )
        },
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = {
                            BadgedBox(badge = { if (waiting > 0) Badge { Text("$waiting") } }) {
                                Icon(Icons.AutoMirrored.Filled.Chat, if (waiting > 0) "Chat, $waiting waiting for you" else "Chat")
                            }
                        },
                        label = { Text("Chat") },
                        selected = route == "chat",
                        onClick = { navController.go("chat") },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.List, "Tasks") },
                        label = { Text("Tasks") },
                        selected = route == "tasks",
                        onClick = { navController.go("tasks") },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Settings, "Settings") },
                        label = { Text("Settings") },
                        selected = route == "settings",
                        onClick = { navController.go("settings") },
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                NavHost(navController, startDestination = "chat") {
                    composable("chat") {
                        ChatScreen(
                            appState,
                            onOpenDrawer = { scope.launch { drawer.open() } },
                            onOpenSettings = { navController.go("settings") },
                        )
                    }
                    composable("tasks") {
                        TasksScreen(appState, onOpenChat = { id ->
                            appState.chat.open(id)
                            navController.go("chat")
                        })
                    }
                    composable("settings") { SettingsScreen(appState) }
                }
            }
        }
    }
}
