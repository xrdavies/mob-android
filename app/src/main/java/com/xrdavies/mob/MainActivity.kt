package com.xrdavies.mob

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xrdavies.mob.notify.Notifier
import com.xrdavies.mob.ui.MobApp
import com.xrdavies.mob.ui.theme.MobTheme

class MainActivity : ComponentActivity() {
    private val appState get() = (application as MobApplication).appState

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appState = appState
        setContent {
            MobTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MobApp(appState)
                }
            }
        }
        if (savedInstanceState == null) openFrom(intent)
        // Ask once; Settings explains how to turn it on later.
        val prefs = getSharedPreferences("mob", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !appState.notifier.allowed && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        appState.files.foreground = true
    }

    override fun onStop() {
        appState.files.foreground = false
        appState.files.pauseAll()
        super.onStop()
    }

    /** A notification tap carries the machine and chat to open. */
    private fun openFrom(intent: Intent?) {
        val machine = intent?.getStringExtra(Notifier.EXTRA_MACHINE)?.takeIf { it.isNotEmpty() } ?: return
        appState.openChat(machine, intent.getStringExtra(Notifier.EXTRA_SESSION))
    }
}
