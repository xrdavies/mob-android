package com.xrdavies.mob

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.xrdavies.mob.viewmodel.AppState

/** Owns the one [AppState] so it survives activity recreation (rotation, theme changes). */
class MobApplication : Application() {
    val appState: AppState by lazy { AppState(this) }

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        // Notifications for the chat on screen are skipped while the app is in front.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    appState.foreground = true
                    appState.updateBackgroundService()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) appState.foreground = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
