package com.xrdavies.mob

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xrdavies.mob.ui.MobApp
import com.xrdavies.mob.ui.theme.MobTheme
import com.xrdavies.mob.viewmodel.AppState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appState = AppState(applicationContext)
        
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
    }
}
