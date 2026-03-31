package com.demonicmusichost.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demonicmusichost.app.data.SocketManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState

    private val _serverUrl = MutableStateFlow(DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl

    init {
        // Watch for session creation/join → navigate to session screen
        SocketManager.mySessionId
            .onEach { sessionId ->
                if (sessionId != null) {
                    _uiState.value = HomeUiState.SessionReady(sessionId)
                }
            }
            .launchIn(viewModelScope)

        SocketManager.errorMessage
            .onEach { msg ->
                _uiState.value = HomeUiState.Error(msg)
            }
            .launchIn(viewModelScope)
    }

    fun updateServerUrl(url: String) {
        _serverUrl.value = url
        SocketManager.init(url)
    }

    fun createSession(username: String) {
        if (username.isBlank()) {
            _uiState.value = HomeUiState.Error("Bitte gib einen Namen ein.")
            return
        }
        _uiState.value = HomeUiState.Loading
        SocketManager.init(_serverUrl.value)
        SocketManager.createSession(username.trim())
    }

    fun joinSession(username: String, code: String) {
        if (username.isBlank()) {
            _uiState.value = HomeUiState.Error("Bitte gib einen Namen ein.")
            return
        }
        if (code.isBlank()) {
            _uiState.value = HomeUiState.Error("Bitte gib einen Session-Code ein.")
            return
        }
        _uiState.value = HomeUiState.Loading
        SocketManager.init(_serverUrl.value)
        SocketManager.joinSession(code.trim(), username.trim())
    }

    fun clearError() {
        if (_uiState.value is HomeUiState.Error) {
            _uiState.value = HomeUiState.Idle
        }
    }

    companion object {
        // Default server — user can change this in the settings dialog
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:3001"
    }
}

sealed class HomeUiState {
    object Idle : HomeUiState()
    object Loading : HomeUiState()
    data class SessionReady(val sessionId: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
