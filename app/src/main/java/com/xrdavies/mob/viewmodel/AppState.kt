package com.xrdavies.mob.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.Health
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
}
