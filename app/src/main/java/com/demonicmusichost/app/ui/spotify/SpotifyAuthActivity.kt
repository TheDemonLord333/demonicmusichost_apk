package com.demonicmusichost.app.ui.spotify

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.demonicmusichost.app.data.PrefsManager

/**
 * Opens the DMH Spotify OAuth flow in a WebView.
 * The server redirects to "/?spotify_token=...&spotify_refresh=...&spotify_expires=..."
 * We intercept that URL, extract the tokens, and return them via setResult.
 */
class SpotifyAuthActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = RelativeLayout(this)
        webView = WebView(this).also {
            it.id = android.view.View.generateViewId()
        }
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            val params = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }
            layoutParams = params
        }
        layout.addView(webView, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ))
        layout.addView(progressBar)
        setContentView(layout)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // required for Spotify login JS
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                progressBar.isVisible = false
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUrl(request.url.toString())
            }

            // API < 24 fallback
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrl(url)
            }
        }

        val serverUrl = PrefsManager.serverUrl
        webView.loadUrl("$serverUrl/auth/spotify?from=home")
    }

    private fun handleUrl(url: String): Boolean {
        val uri = url.toUri()
        val token = uri.getQueryParameter("spotify_token")
        if (!token.isNullOrBlank()) {
            val refresh = uri.getQueryParameter("spotify_refresh") ?: ""
            val expires = uri.getQueryParameter("spotify_expires")?.toLongOrNull() ?: 0L
            PrefsManager.saveSpotifyTokens(token, refresh, expires)
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_REFRESH, refresh)
                putExtra(EXTRA_EXPIRES, expires)
            })
            finish()
            return true
        }
        val error = uri.getQueryParameter("spotify_error")
        if (!error.isNullOrBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        return false
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    companion object {
        const val EXTRA_TOKEN = "extra_spotify_token"
        const val EXTRA_REFRESH = "extra_spotify_refresh"
        const val EXTRA_EXPIRES = "extra_spotify_expires"
    }
}
