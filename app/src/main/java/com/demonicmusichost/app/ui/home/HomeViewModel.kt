package com.demonicmusichost.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demonicmusichost.app.data.PrefsManager
import com.demonicmusichost.app.data.SocketManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState

    private val _serverUrl = MutableStateFlow(PrefsManager.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl

    init {
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
        val trimmed = url.trim().trimEnd('/')
        PrefsManager.serverUrl = trimmed
        _serverUrl.value = trimmed
        SocketManager.init(trimmed)
    }

    fun createSession(username: String) {
        if (username.isBlank()) {
            _uiState.value = HomeUiState.Error("Bitte gib einen Namen ein.")
            return
        }
        _uiState.value = HomeUiState.Loading
        SocketManager.init(PrefsManager.serverUrl)
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
        SocketManager.init(PrefsManager.serverUrl)
        SocketManager.joinSession(code.trim(), username.trim())
    }

    fun clearError() {
        if (_uiState.value is HomeUiState.Error) {
            _uiState.value = HomeUiState.Idle
        }
    }
}

sealed class HomeUiState {
    object Idle : HomeUiState()
    object Loading : HomeUiState()
    data class SessionReady(val sessionId: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
