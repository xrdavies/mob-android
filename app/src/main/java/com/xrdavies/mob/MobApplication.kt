package com.xrdavies.mob

import android.app.Application
import com.xrdavies.mob.viewmodel.AppState

/** Owns the one [AppState] so it survives activity recreation (rotation, theme changes). */
class MobApplication : Application() {
    val appState: AppState by lazy { AppState(this) }
}
