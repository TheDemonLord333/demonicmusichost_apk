package com.demonicmusichost.app.ui.session

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.demonicmusichost.app.data.PrefsManager

/**
 * Hosts the Spotify Web Playback SDK in a hidden WebView, acting as a
 * virtual Spotify Connect device.  Requires Spotify Premium.
 *
 * Call [init] once (on the main thread) to create the WebView and start
 * loading the SDK.  The SDK fires [onReady] when it has obtained a
 * device_id and is ready to accept [playUri] calls.
 *
 * Thread-safety: all public methods must be called from the main thread.
 */
class SpotifyWebPlayer(
    private val context: Context,
    /** Called (on main thread) once the SDK is ready to accept playback commands. */
    private val onReady: () -> Unit,
    /** Called (on main thread) when a non-recoverable error occurs. */
    private val onError: (message: String) -> Unit,
    /** Called (on main thread) every ~2 s while a track is playing with the live position. */
    private val onProgressUpdate: (positionMs: Long) -> Unit,
    /** Called (on main thread) when the current track finishes playing. */
    private val onTrackEnded: () -> Unit
) {

    private var webView: WebView? = null
    private var deviceId: String? = null

    // URI to play as soon as the device becomes ready
    private var pendingUri: String? = null

    val isReady: Boolean get() = deviceId != null

    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        if (webView != null) return
        webView = WebView(context).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Pretend to be Chrome so the Spotify SDK accepts us
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            wv.addJavascriptInterface(JsBridge(), "DMHAndroid")
            wv.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                ) {
                    Log.e(TAG, "WebView error [$errorCode] $description @ $failingUrl")
                }
            }
            // Load from open.spotify.com origin so the SDK's DRM checks pass
            wv.loadDataWithBaseURL(
                "https://open.spotify.com",
                PLAYER_HTML,
                "text/html",
                "utf-8",
                null
            )
        }
        Log.i(TAG, "SpotifyWebPlayer initializing…")
    }

    /** Start playback of [spotifyUri] (format "spotify:track:XXXX"). */
    fun playUri(spotifyUri: String) {
        val id = deviceId
        if (id == null) {
            Log.w(TAG, "playUri queued – not ready yet")
            pendingUri = spotifyUri
            return
        }
        val token = PrefsManager.spotifyToken ?: run {
            onError("Kein Spotify-Token. Bitte erneut anmelden.")
            return
        }
        val safeUri   = spotifyUri.replace("\\", "\\\\").replace("'", "\\'")
        val safeId    = id.replace("\\", "\\\\").replace("'", "\\'")
        val safeToken = token.replace("\\", "\\\\").replace("'", "\\'")
        webView?.evaluateJavascript("playUri('$safeUri','$safeId','$safeToken');", null)
        Log.i(TAG, "playUri → $spotifyUri")
    }

    fun pause() {
        webView?.evaluateJavascript("if(typeof player!=='undefined')player.pause();", null)
    }

    fun resume() {
        webView?.evaluateJavascript("if(typeof player!=='undefined')player.resume();", null)
    }

    fun release() {
        webView?.evaluateJavascript("if(typeof player!=='undefined')player.disconnect();", null)
        webView?.stopLoading()
        webView?.destroy()
        webView   = null
        deviceId  = null
        pendingUri = null
        Log.i(TAG, "released")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JavaScript → Android bridge (called on a background thread by the WebView)
    // ─────────────────────────────────────────────────────────────────────────

    private inner class JsBridge {

        @JavascriptInterface
        fun getToken(): String = PrefsManager.spotifyToken ?: ""

        @JavascriptInterface
        fun onReady(id: String) {
            Log.i(TAG, "SDK ready, deviceId=$id")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                deviceId = id
                this@SpotifyWebPlayer.onReady()
                // Play any track that was requested before the SDK was ready
                pendingUri?.let { uri ->
                    pendingUri = null
                    playUri(uri)
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.e(TAG, "SDK error: $message")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                this@SpotifyWebPlayer.onError(message)
            }
        }

        @JavascriptInterface
        fun onProgressUpdate(positionMs: Long) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                this@SpotifyWebPlayer.onProgressUpdate(positionMs)
            }
        }

        @JavascriptInterface
        fun onTrackEnded() {
            Log.i(TAG, "Track ended")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                this@SpotifyWebPlayer.onTrackEnded()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Embedded HTML — loads the Spotify Web Playback SDK and bridges events
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SpotifyWebPlayer"

        @Suppress("ktlint:standard:max-line-length")
        private val PLAYER_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"/></head><body>
<script src="https://sdk.scdn.co/spotify-player.js"></script>
<script>
var player;
var positionMs = 0;
var trackDuration = 0;
var pollTimer = null;

window.onSpotifyWebPlaybackSDKReady = function() {
    player = new Spotify.Player({
        name: 'DemonicMusicHost',
        getOAuthToken: function(cb) { cb(DMHAndroid.getToken()); },
        volume: 1.0
    });

    player.addListener('ready', function(data) {
        DMHAndroid.onReady(data.device_id);
    });

    player.addListener('not_ready', function(data) {
        DMHAndroid.onError('Spotify Player not ready');
    });

    player.addListener('player_state_changed', function(state) {
        if (!state) { stopPoll(); return; }
        positionMs    = state.position;
        trackDuration = state.duration;
        if (!state.paused) {
            startPoll();
        } else {
            stopPoll();
        }
    });

    player.addListener('initialization_error', function(data) {
        DMHAndroid.onError('Init error: ' + data.message);
    });
    player.addListener('authentication_error', function(data) {
        DMHAndroid.onError('Auth error: ' + data.message);
    });
    player.addListener('account_error', function(data) {
        DMHAndroid.onError('Spotify Premium erforderlich: ' + data.message);
    });
    player.addListener('playback_error', function(data) {
        DMHAndroid.onError('Playback error: ' + data.message);
    });

    player.connect();
};

function startPoll() {
    if (pollTimer) return;
    pollTimer = setInterval(function() {
        positionMs += 2000;
        if (trackDuration > 0 && positionMs >= trackDuration) {
            stopPoll();
            DMHAndroid.onTrackEnded();
        } else {
            DMHAndroid.onProgressUpdate(positionMs);
        }
    }, 2000);
}

function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

function playUri(uri, deviceId, token) {
    fetch('https://api.spotify.com/v1/me/player/play?device_id=' + deviceId, {
        method: 'PUT',
        headers: {
            'Authorization': 'Bearer ' + token,
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ uris: [uri] })
    }).then(function(r) {
        if (!r.ok) {
            r.text().then(function(t) {
                DMHAndroid.onError('Playback API ' + r.status + ': ' + t);
            });
        }
    }).catch(function(e) {
        DMHAndroid.onError('Network: ' + e.message);
    });
}
</script>
</body></html>"""
    }
}
