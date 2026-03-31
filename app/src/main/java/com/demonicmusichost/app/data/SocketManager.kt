package com.demonicmusichost.app.data

import android.util.Log
import com.demonicmusichost.app.data.model.SessionState
import com.demonicmusichost.app.data.model.Track
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Singleton managing the Socket.IO connection to the DMH server.
 *
 * Call [init] with your server URL (e.g. "https://yourserver.com") before
 * any other method.
 */
object SocketManager {

    private const val TAG = "DMH_Socket"

    private val gson = Gson()

    private var socket: Socket? = null

    // ── Connection state ──────────────────────────────────────────────────────
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    // ── Session state ─────────────────────────────────────────────────────────
    private val _sessionState = MutableStateFlow<SessionState?>(null)
    val sessionState: StateFlow<SessionState?> = _sessionState

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost

    private val _mySessionId = MutableStateFlow<String?>(null)
    val mySessionId: StateFlow<String?> = _mySessionId

    // ── One-shot events ───────────────────────────────────────────────────────
    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorMessage: SharedFlow<String> = _errorMessage

    private val _kicked = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val kicked: SharedFlow<String> = _kicked

    // ── Server URL ────────────────────────────────────────────────────────────
    private var serverUrl: String = ""

    fun getServerUrl(): String = serverUrl

    // ─────────────────────────────────────────────────────────────────────────
    // Init / connect
    // ─────────────────────────────────────────────────────────────────────────

    fun init(url: String) {
        if (socket?.connected() == true && serverUrl == url) return
        serverUrl = url.trimEnd('/')
        disconnect()
        connect()
    }

    private fun connect() {
        // Trust all certs for self-signed HTTPS during development.
        // Replace with proper cert pinning before going to production.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })
        val sslContext = try {
            SSLContext.getInstance("TLS").also { it.init(null, trustAll, SecureRandom()) }
        } catch (e: Exception) {
            Log.w(TAG, "SSL setup warning: ${e.message}")
            null
        }

        val okHttpClient = okhttp3.OkHttpClient.Builder().apply {
            if (sslContext != null) {
                sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
            }
        }.build()

        val opts = IO.Options.builder()
            .setTransports(arrayOf("websocket"))
            .setReconnection(true)
            .setReconnectionAttempts(5)
            .setReconnectionDelay(2000)
            .setCallFactory(okHttpClient)
            .setWebSocketFactory(okHttpClient)
            .build()

        socket = IO.socket(URI.create(serverUrl), opts).also { s ->
            s.on(Socket.EVENT_CONNECT) {
                Log.i(TAG, "Connected")
                _connected.tryEmit(true)
            }
            s.on(Socket.EVENT_DISCONNECT) {
                Log.i(TAG, "Disconnected")
                _connected.tryEmit(false)
            }
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Connection error: ${args.firstOrNull()}")
                _connected.tryEmit(false)
            }
            registerSessionEvents(s)
            s.connect()
        }
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
        _connected.tryEmit(false)
        _sessionState.tryEmit(null)
        _mySessionId.tryEmit(null)
        _isHost.tryEmit(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Register socket events
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerSessionEvents(s: Socket) {

        // ── Server → Client ───────────────────────────────────────────────────

        s.on("session_created") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            val sessionId = data.optString("sessionId")
            _mySessionId.tryEmit(sessionId)
            _isHost.tryEmit(true)
            parseState(data.optJSONObject("state"))
            Log.i(TAG, "Session created: $sessionId")
        }

        s.on("session_joined") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            val sessionId = data.optString("sessionId")
            _mySessionId.tryEmit(sessionId)
            _isHost.tryEmit(false)
            parseState(data.optJSONObject("state"))
            Log.i(TAG, "Session joined: $sessionId")
        }

        s.on("queue_updated") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            _sessionState.value?.let { current ->
                val updated = current.copy(
                    queue = parseTrackList(data),
                    currentTrackIndex = data.optInt("currentTrackIndex", current.currentTrackIndex)
                )
                _sessionState.tryEmit(updated)
            }
        }

        s.on("playback_updated") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            _sessionState.value?.let { current ->
                val updated = current.copy(
                    currentTrackIndex = data.optInt("currentTrackIndex", current.currentTrackIndex),
                    isPlaying = data.optBoolean("isPlaying", current.isPlaying),
                    position = data.optLong("position", current.position)
                )
                _sessionState.tryEmit(updated)
            }
        }

        s.on("participant_joined") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            _sessionState.value?.let { current ->
                val participants = parseParticipantList(data)
                _sessionState.tryEmit(current.copy(participants = participants))
            }
        }

        s.on("participant_left") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            _sessionState.value?.let { current ->
                val participants = parseParticipantList(data)
                _sessionState.tryEmit(current.copy(participants = participants))
            }
        }

        s.on("settings_updated") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            _sessionState.value?.let { current ->
                val settingsJson = data.optJSONObject("settings")
                if (settingsJson != null) {
                    val settings = com.demonicmusichost.app.data.model.SessionSettings(
                        allowJoin = settingsJson.optBoolean("allowJoin", true),
                        allowGuestAdd = settingsJson.optBoolean("allowGuestAdd", true)
                    )
                    _sessionState.tryEmit(current.copy(settings = settings))
                }
            }
        }

        s.on("host_transferred") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            _sessionState.value?.let { current ->
                val participants = parseParticipantList(data)
                _sessionState.tryEmit(current.copy(participants = participants))
            }
        }

        s.on("kicked") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            val by = data.optString("by", "Host")
            _kicked.tryEmit(by)
        }

        s.on("error") { args ->
            val data = args.firstOrNull() as? JSONObject ?: return@on
            val msg = data.optString("message", "Unknown error")
            _errorMessage.tryEmit(msg)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Emit helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun createSession(username: String) {
        emit("create_session", JSONObject().put("username", username))
    }

    fun joinSession(sessionId: String, username: String) {
        emit("join_session", JSONObject()
            .put("sessionId", sessionId.uppercase())
            .put("username", username))
    }

    fun playbackControl(action: String, position: Long? = null) {
        val data = JSONObject().put("action", action)
        if (position != null) data.put("position", position)
        emit("playback_control", data)
    }

    fun queueAdd(track: Track) {
        val trackJson = JSONObject(gson.toJson(track))
        emit("queue_add", JSONObject().put("track", trackJson))
    }

    fun queueRemove(trackIndex: Int) {
        emit("queue_remove", JSONObject().put("trackIndex", trackIndex))
    }

    fun queueReorder(fromIndex: Int, toIndex: Int) {
        emit("queue_reorder", JSONObject()
            .put("fromIndex", fromIndex)
            .put("toIndex", toIndex))
    }

    fun updateSettings(allowJoin: Boolean? = null, allowGuestAdd: Boolean? = null) {
        val data = JSONObject()
        if (allowJoin != null) data.put("allowJoin", allowJoin)
        if (allowGuestAdd != null) data.put("allowGuestAdd", allowGuestAdd)
        emit("settings_update", data)
    }

    fun kickParticipant(targetSocketId: String) {
        emit("kick_participant", JSONObject().put("targetSocketId", targetSocketId))
    }

    fun reportProgress(positionMs: Long) {
        emit("playback_progress", JSONObject().put("position", positionMs))
    }

    fun reportTrackEnded() {
        emit("track_ended", JSONObject())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun emit(event: String, data: JSONObject) {
        if (socket?.connected() == true) {
            socket?.emit(event, data)
        } else {
            Log.w(TAG, "emit('$event') skipped – not connected")
        }
    }

    private fun parseState(json: JSONObject?) {
        json ?: return
        val state = gson.fromJson(json.toString(), SessionState::class.java)
        _sessionState.tryEmit(state)
    }

    private fun parseTrackList(data: JSONObject): List<Track> {
        val arr = data.optJSONArray("queue") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            gson.fromJson(arr.getJSONObject(i).toString(), Track::class.java)
        }
    }

    private fun parseParticipantList(data: JSONObject): List<com.demonicmusichost.app.data.model.Participant> {
        val arr = data.optJSONArray("participants") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            gson.fromJson(arr.getJSONObject(i).toString(), com.demonicmusichost.app.data.model.Participant::class.java)
        }
    }
}
