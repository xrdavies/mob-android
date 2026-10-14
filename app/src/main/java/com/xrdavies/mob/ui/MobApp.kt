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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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

    val destinations = listOf(
        Triple("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
        Triple("tasks", "Tasks", Icons.AutoMirrored.Filled.List),
        Triple("settings", "Settings", Icons.Filled.Settings),
    )
    val navIcon: @Composable (String, String, ImageVector) -> Unit = { r, label, icon ->
        if (r == "chat") {
            BadgedBox(badge = { if (waiting > 0) Badge { Text("$waiting") } }) {
                Icon(icon, if (waiting > 0) "$label, $waiting waiting for you" else label)
            }
        } else Icon(icon, label)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tablets: a navigation rail and the chat list beside the chat instead of the bottom bar and drawer.
        val wide = maxWidth >= 600.dp
        val content: @Composable (Modifier) -> Unit = { modifier ->
            NavHost(navController, startDestination = "chat", modifier = modifier) {
                composable("chat") {
                    ChatScreen(
                        appState,
                        onOpenDrawer = if (wide) null else ({ scope.launch { drawer.open() } }),
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

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Spacer(Modifier.height(8.dp))
                    destinations.forEach { (r, label, icon) ->
                        NavigationRailItem(
                            icon = { navIcon(r, label, icon) },
                            label = { Text(label) },
                            selected = route == r,
                            onClick = { navController.go(r) },
                        )
                    }
                }
                if (route == "chat") {
                    SessionDrawer(appState, onClose = {}, onManageMachines = { navController.go("settings") }, permanent = true)
                    VerticalDivider()
                }
                content(Modifier.weight(1f).fillMaxHeight())
            }
            return@BoxWithConstraints
        }

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
                        destinations.forEach { (r, label, icon) ->
                            NavigationBarItem(
                                icon = { navIcon(r, label, icon) },
                                label = { Text(label) },
                                selected = route == r,
                                onClick = { navController.go(r) },
                            )
                        }
                    }
                }
            ) { padding -> content(Modifier.padding(padding)) }
        }
    }
}
