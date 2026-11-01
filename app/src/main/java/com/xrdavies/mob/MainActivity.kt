package com.xrdavies.mob

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appState = appState
        // Debug builds: `am start ... --ez liveRelayOnly true` allows only relayed (TURN) candidates.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent?.hasExtra("liveRelayOnly") == true) {
            appState.stream.relayOnly = intent.getBooleanExtra("liveRelayOnly", false)
        }
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
        // Android 17 blocks connections to local addresses (the office network, a VPN) without this;
        // Android stops showing the prompt itself once the user declines it.
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            askLocalNetwork.launch(LOCAL_NETWORK)
        }
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
        appState.chat.resync()
    }

    override fun onStop() {
        // No sound from the background: the live view is a foreground thing.
        if (appState.stream.sound.value.isOn) appState.stream.setSound(false)
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

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
