package com.xrdavies.mob.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.Health
import com.xrdavies.mob.vnc.VncSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppState(context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("mob", Context.MODE_PRIVATE)
    
    private val _mobdUrl = MutableStateFlow(prefs.getString("mobdUrl", "") ?: "")
    val mobdUrl: StateFlow<String> = _mobdUrl
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    
    private val _health = MutableStateFlow<Health?>(null)
    val health: StateFlow<Health?> = _health
    
    private val _showDesktopDrawer = MutableStateFlow(false)
    val showDesktopDrawer: StateFlow<Boolean> = _showDesktopDrawer
    
    // macOS user for Screen Sharing sign-in (ARD), so the live view lands in that user's session.
    private val _vncUsername = MutableStateFlow(prefs.getString("vncUsername", "") ?: "")
    val vncUsername: StateFlow<String> = _vncUsername
    private val _vncPassword = MutableStateFlow(prefs.getString("vncPassword", "") ?: "")
    val vncPassword: StateFlow<String> = _vncPassword

    /** One live view shared by the thumbnail and the full-screen view. */
    val vnc = VncSession()
    private var desktopUsers = 0

    private var _apiClient: ApiClient = ApiClient(_mobdUrl.value)
    val apiClient: ApiClient
        get() = _apiClient
    
    init {
        checkHealth()
    }
    
    fun updateMobdUrl(url: String) {
        _mobdUrl.value = url
        prefs.edit().putString("mobdUrl", url).apply()
        _apiClient = ApiClient(url)
        checkHealth()
        if (desktopUsers > 0) connectDesktop()
    }

    fun updateVncCredentials(username: String, password: String) {
        _vncUsername.value = username
        _vncPassword.value = password
        prefs.edit().putString("vncUsername", username).putString("vncPassword", password).apply()
        if (desktopUsers > 0) connectDesktop()
    }

    /** A view that shows the desktop is on screen; the live view stays open while any is. */
    fun retainDesktop() {
        if (desktopUsers++ == 0) connectDesktop()
    }

    fun releaseDesktop() {
        if (--desktopUsers == 0) vnc.disconnect()
    }

    fun connectDesktop() {
        if (_mobdUrl.value.isBlank()) return
        vnc.connect(_apiClient.okHttpClient, _apiClient.vncUrl, _vncUsername.value, _vncPassword.value)
    }
    
    fun checkHealth() {
        if (_mobdUrl.value.isEmpty()) {
            _isConnected.value = false
            _health.value = null
            return
        }
        
        viewModelScope.launch {
            try {
                val h = _apiClient.api.getHealth()
                _health.value = h
                _isConnected.value = h.ok
            } catch (e: Exception) {
                _isConnected.value = false
                _health.value = null
            }
        }
    }
    
    fun toggleDesktopDrawer() {
        _showDesktopDrawer.value = !_showDesktopDrawer.value
    }

    private val _showLiveView = MutableStateFlow(false)
    val showLiveView: StateFlow<Boolean> = _showLiveView

    fun openLiveView() {
        _showLiveView.value = true
    }

    fun closeLiveView() {
        vnc.setControl(false)
        _showLiveView.value = false
    }
}
