package com.demonicmusichost.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demonicmusichost.app.data.SocketManager
import com.demonicmusichost.app.data.model.SessionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class SessionViewModel : ViewModel() {

    val sessionState: StateFlow<SessionState?> = SocketManager.sessionState
    val isHost: StateFlow<Boolean> = SocketManager.isHost
    val mySessionId: StateFlow<String?> = SocketManager.mySessionId
    val connected: StateFlow<Boolean> = SocketManager.connected

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessage: SharedFlow<String> = _toastMessage

    private val _navigateHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateHome: SharedFlow<Unit> = _navigateHome

    init {
        SocketManager.errorMessage
            .onEach { msg -> _toastMessage.emit(msg) }
            .launchIn(viewModelScope)

        SocketManager.kicked
            .onEach { by ->
                _toastMessage.emit("Du wurdest von $by aus der Session entfernt.")
                _navigateHome.emit(Unit)
            }
            .launchIn(viewModelScope)
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    fun play(positionMs: Long? = null) = SocketManager.playbackControl("play", positionMs)
    fun pause(positionMs: Long? = null) = SocketManager.playbackControl("pause", positionMs)
    fun next() = SocketManager.playbackControl("next")
    fun prev() = SocketManager.playbackControl("prev")
    fun seek(positionMs: Long) = SocketManager.playbackControl("seek", positionMs)

    // ── Queue ─────────────────────────────────────────────────────────────────

    fun removeTrack(index: Int) = SocketManager.queueRemove(index)
    fun reorderQueue(from: Int, to: Int) = SocketManager.queueReorder(from, to)

    // ── Settings ──────────────────────────────────────────────────────────────

    fun updateAllowJoin(allow: Boolean) = SocketManager.updateSettings(allowJoin = allow)
    fun updateAllowGuestAdd(allow: Boolean) = SocketManager.updateSettings(allowGuestAdd = allow)

    // ── Admin ─────────────────────────────────────────────────────────────────

    fun kickParticipant(socketId: String) = SocketManager.kickParticipant(socketId)

    fun leaveSession() {
        SocketManager.disconnect()
    }

    fun getServerUrl(): String = SocketManager.getServerUrl()
}
