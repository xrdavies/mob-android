package com.xrdavies.mob.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android 17's local network permission ("Nearby devices"). Without it every connection to a local
 * address (the office network, a VPN) times out, which looks like mobd not answering; errors and
 * Settings say so instead. Rechecked whenever Mob comes to the front, since it is changed in
 * Android's settings.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    internal val state = MutableStateFlow(false)
    val blocked: StateFlow<Boolean> = state

    fun refresh(context: Context) {
        state.value = Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED
    }

    const val MESSAGE = "Mob is not allowed to reach your local network. Allow it in Android's settings: Mob → Permissions → Nearby devices."
}
