package com.demonicmusichost.app.ui.session

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.PrefsManager
import com.demonicmusichost.app.data.SocketManager
import com.demonicmusichost.app.data.model.SessionState
import com.demonicmusichost.app.data.model.Track
import com.demonicmusichost.app.databinding.FragmentSessionBinding
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SessionFragment : Fragment() {

    private var _binding: FragmentSessionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SessionViewModel by viewModels()
    private lateinit var queueAdapter: QueueAdapter
    private lateinit var participantAdapter: ParticipantAdapter

    // ── ExoPlayer (local + YouTube host audio playback) ───────────────────────
    private var player: ExoPlayer? = null
    private var lastPlayingTrackId: String? = null   // "source:id" of the loaded track
    private var progressJob: Job? = null
    private var youtubeLoadJob: Job? = null

    // ── Spotify Web Playback SDK (full-track, Spotify Premium required) ────────
    private var spotifyPlayer: SpotifyWebPlayer? = null

    // Piped API instances (free YouTube audio extraction, no API key)
    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://piped-api.garudalinux.org",
        "https://api.piped.yt"
    )

    private val httpClient: OkHttpClient by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        })
        val sc = try {
            SSLContext.getInstance("TLS").also { it.init(null, trustAll, SecureRandom()) }
        } catch (e: Exception) { null }
        OkHttpClient.Builder().apply {
            if (sc != null) {
                sslSocketFactory(sc.socketFactory, trustAll[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
            }
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(10, TimeUnit.SECONDS)
        }.build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try { setupExoPlayer() } catch (e: Exception) {
            android.util.Log.e("SessionFragment", "ExoPlayer init failed: ${e.message}")
        }
        setupAdapters()
        setupListeners()
        observeState()
    }

    override fun onDestroyView() {
        progressJob?.cancel()
        youtubeLoadJob?.cancel()
        player?.release()
        player = null
        spotifyPlayer?.release()
        spotifyPlayer = null
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ExoPlayer
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupExoPlayer() {
        player = ExoPlayer.Builder(requireContext()).build().also { p ->
            p.addListener(object : Player.Listener {

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        SocketManager.reportTrackEnded()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) startProgressReporting() else progressJob?.cancel()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val msg = when {
                        lastPlayingTrackId?.startsWith("youtube") == true ->
                            "YouTube-Stream konnte nicht geladen werden."
                        else -> "Wiedergabefehler: ${error.message}"
                    }
                    view?.post {
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    /**
     * Returns the SpotifyWebPlayer, creating and initialising it on first call.
     * Only called when we're the host and a Spotify track actually needs to play.
     */
    private fun getOrCreateSpotifyPlayer(): SpotifyWebPlayer {
        return spotifyPlayer ?: SpotifyWebPlayer(
            context = requireContext(),
            onReady = {
                android.util.Log.i("SessionFragment", "Spotify SDK ready")
            },
            onError = { msg ->
                // "initialization_error" fires before any track is queued and is expected
                // on devices/accounts that can't use the SDK — log silently, don't spam the user.
                val isInitError = msg.startsWith("Init error") || msg.startsWith("Auth error")
                if (isInitError) {
                    android.util.Log.w("SessionFragment", "Spotify SDK: $msg")
                } else {
                    Toast.makeText(requireContext(), "Spotify: $msg", Toast.LENGTH_LONG).show()
                }
            },
            onProgressUpdate = { positionMs ->
                SocketManager.reportProgress(positionMs)
            },
            onTrackEnded = {
                SocketManager.reportTrackEnded()
            }
        ).also { sp -> spotifyPlayer = sp; sp.init() }
    }

    private fun startProgressReporting() {
        progressJob?.cancel()
        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(2000)
                val p = player ?: break
                if (!p.isPlaying) break
                SocketManager.reportProgress(p.currentPosition)
            }
        }
    }

    /**
     * Called whenever sessionState changes.
     * Only active when this client is the HOST — guests receive audio via the
     * server's playback_updated events and are not responsible for sound output.
     */
    private fun handleHostPlayback(state: SessionState) {
        if (!viewModel.isHost.value) {
            // Not the host — stop any local playback silently
            player?.let { if (it.isPlaying) it.pause() }
            return
        }

        val currentTrack = state.queue.getOrNull(state.currentTrackIndex)
        val p = player ?: return

        if (currentTrack == null) {
            if (p.isPlaying) p.stop()
            lastPlayingTrackId = null
            return
        }

        val trackKey = "${currentTrack.source}:${currentTrack.id}"

        if (lastPlayingTrackId != trackKey) {
            // New track — determine stream URL and load it
            lastPlayingTrackId = trackKey
            youtubeLoadJob?.cancel()

            when (currentTrack.source) {
                "local" -> {
                    // Stream from the DMH server's upload endpoint
                    val fileId = currentTrack.localFileId
                        ?: currentTrack.url.substringAfterLast('/')
                    val url = "${SocketManager.getServerUrl()}/upload/stream/$fileId"
                    loadMedia(p, url, state.isPlaying)
                }

                "spotify" -> {
                    // Full-track playback via Spotify Web Playback SDK (Spotify Premium required).
                    // Only attempt if the user has a valid Spotify token; otherwise fall back
                    // to the 30-second preview so guests without Premium still hear something.
                    val uri = currentTrack.spotifyUri
                    if (!uri.isNullOrBlank() && PrefsManager.isSpotifyValid()) {
                        // Stop ExoPlayer if it was playing something before
                        if (p.isPlaying) p.stop()
                        getOrCreateSpotifyPlayer().playUri(uri)
                    } else {
                        // Fallback: 30-second preview (no token or no URI)
                        val preview = currentTrack.previewUrl
                        if (!preview.isNullOrBlank()) {
                            loadMedia(p, preview, state.isPlaying)
                        } else {
                            Toast.makeText(
                                requireContext(),
                                "Spotify: Kein Token. Bitte in der App anmelden.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                "youtube" -> {
                    // Fetch a direct audio stream URL from the Piped API
                    // (free, open-source YouTube front-end, no API key needed)
                    val videoId = currentTrack.youtubeId ?: currentTrack.sourceId
                    if (videoId.isNotBlank()) {
                        youtubeLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                            val streamUrl = fetchYouTubeStream(videoId)
                            if (streamUrl != null) {
                                loadMedia(p, streamUrl, state.isPlaying)
                            } else {
                                lastPlayingTrackId = null   // allow retry on next state change
                                Toast.makeText(
                                    requireContext(),
                                    "YouTube-Stream konnte nicht geladen werden.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    return  // play/pause handled after stream URL is loaded
                }
            }
            return
        }

        // Same track — sync play/pause state
        if (currentTrack.source == "spotify") {
            // Only touch the SDK player if it already exists; don't create it just to pause
            val sp = spotifyPlayer ?: return
            if (state.isPlaying) sp.resume() else sp.pause()
        } else {
            if (state.isPlaying && !p.isPlaying) p.play()
            else if (!state.isPlaying && p.isPlaying) p.pause()
        }
    }

    private fun loadMedia(p: ExoPlayer, url: String, shouldPlay: Boolean) {
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        if (shouldPlay) p.play()
    }

    /** Tries each Piped instance in order, returns the best audio stream URL or null. */
    private suspend fun fetchYouTubeStream(videoId: String): String? =
        withContext(Dispatchers.IO) {
            for (instance in PIPED_INSTANCES) {
                try {
                    val request = Request.Builder()
                        .url("$instance/streams/$videoId")
                        .build()
                    val body = httpClient.newCall(request).execute().use { r ->
                        if (!r.isSuccessful) return@use null
                        r.body?.string()
                    } ?: continue

                    val json = Gson().fromJson(body, JsonObject::class.java)
                    val streams = json.getAsJsonArray("audioStreams") ?: continue

                    // Pick the highest-bitrate stream
                    var bestUrl: String? = null
                    var bestBitrate = 0
                    for (i in 0 until streams.size()) {
                        val s = streams[i].asJsonObject
                        val bitrate = s.get("bitrate")?.asInt ?: 0
                        val url = s.get("url")?.asString ?: continue
                        if (bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = url }
                    }
                    if (bestUrl != null) return@withContext bestUrl
                } catch (_: Exception) { /* try next instance */ }
            }
            null
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Adapters
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupAdapters() {
        queueAdapter = QueueAdapter { index -> viewModel.removeTrack(index) }
        binding.rvQueue.apply {
            adapter = queueAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
        participantAdapter = ParticipantAdapter { participant ->
            if (viewModel.isHost.value) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.kick_confirm_title)
                    .setMessage(getString(R.string.kick_confirm_message, participant.username))
                    .setPositiveButton(R.string.kick) { _, _ ->
                        viewModel.kickParticipant(participant.socketId)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        binding.rvParticipants.apply {
            adapter = participantAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnPlayPause.setOnClickListener {
            val state = viewModel.sessionState.value ?: return@setOnClickListener
            if (state.isPlaying) viewModel.pause() else viewModel.play()
        }
        binding.btnNext.setOnClickListener { viewModel.next() }
        binding.btnPrev.setOnClickListener { viewModel.prev() }

        binding.btnLeave.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.leave_session_title)
                .setMessage(R.string.leave_session_message)
                .setPositiveButton(R.string.leave) { _, _ ->
                    player?.release(); player = null
                    viewModel.leaveSession()
                    findNavController().navigate(R.id.action_session_to_home)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnCopyCode.setOnClickListener {
            val code = viewModel.mySessionId.value ?: return@setOnClickListener
            val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("Session Code", code))
            Toast.makeText(requireContext(), getString(R.string.code_copied, code), Toast.LENGTH_SHORT).show()
        }

        binding.btnShowQr.setOnClickListener {
            val code = viewModel.mySessionId.value ?: return@setOnClickListener
            showQrCodeDialog(code)
        }

        binding.btnAddSong.setOnClickListener {
            AddSongBottomSheet().show(childFragmentManager, AddSongBottomSheet.TAG)
        }

        binding.switchAllowJoin.setOnCheckedChangeListener { _, checked ->
            if (viewModel.isHost.value) viewModel.updateAllowJoin(checked)
        }
        binding.switchAllowAdd.setOnCheckedChangeListener { _, checked ->
            if (viewModel.isHost.value) viewModel.updateAllowGuestAdd(checked)
        }

        binding.btnOpenWeb.setOnClickListener {
            val code = viewModel.mySessionId.value ?: return@setOnClickListener
            openInBrowser("${viewModel.getServerUrl()}/?code=$code")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State observation
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionState.collect { state ->
                    if (state != null) {
                        handleHostPlayback(state)
                        renderState(state)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isHost.collect { host ->
                    queueAdapter.isHostMode = host
                    participantAdapter.isHostMode = host
                    binding.layoutHostControls.isVisible = host
                    binding.layoutPlayback.isVisible = host
                    binding.layoutGuestInfo.isVisible = !host
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.mySessionId.collect { id -> binding.tvSessionCode.text = id ?: "—" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toastMessage.collect { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigateHome.collect {
                    findNavController().navigate(R.id.action_session_to_home)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connected.collect { connected ->
                    binding.tvConnectionStatus.isVisible = !connected
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderState(state: SessionState) {
        queueAdapter.currentTrackIndex = state.currentTrackIndex
        queueAdapter.submitList(state.queue)
        binding.tvQueueEmpty.isVisible = state.queue.isEmpty()

        participantAdapter.submitList(state.participants)
        binding.tvParticipantCount.text =
            getString(R.string.participant_count, state.participants.size)

        val canAdd = viewModel.isHost.value || state.settings.allowGuestAdd
        binding.btnAddSong.isVisible = canAdd

        val currentTrack: Track? = state.queue.getOrNull(state.currentTrackIndex)
        if (currentTrack != null) {
            binding.tvNpTitle.text = currentTrack.title.ifBlank { "—" }
            binding.tvNpArtist.text = currentTrack.artist.ifBlank { currentTrack.addedBy }
            binding.tvNpAddedBy.text = getString(R.string.added_by, currentTrack.addedBy)
            if (currentTrack.thumbnail.isNotBlank()) {
                com.bumptech.glide.Glide.with(binding.ivNpThumbnail)
                    .load(currentTrack.thumbnail)
                    .placeholder(R.drawable.ic_music_note)
                    .into(binding.ivNpThumbnail)
            } else {
                binding.ivNpThumbnail.setImageResource(R.drawable.ic_music_note)
            }
        } else {
            binding.tvNpTitle.text = getString(R.string.no_track)
            binding.tvNpArtist.text = "—"
            binding.tvNpAddedBy.text = ""
            binding.ivNpThumbnail.setImageResource(R.drawable.ic_music_note)
        }

        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // Show ExoPlayer's live position for local tracks; server-reported position otherwise
        val displayPos = if (currentTrack?.source == "local")
            player?.currentPosition ?: state.position
        else state.position

        if (currentTrack != null && currentTrack.duration > 0) {
            val pct = ((displayPos.toFloat() / currentTrack.duration) * 100).toInt()
            binding.progressPlayback.progress = pct.coerceIn(0, 100)
            binding.tvProgressCurrent.text = formatMs(displayPos)
            binding.tvProgressTotal.text = formatMs(currentTrack.duration)
        } else {
            binding.progressPlayback.progress = 0
            binding.tvProgressCurrent.text = "0:00"
            binding.tvProgressTotal.text = "0:00"
        }

        binding.switchAllowJoin.setOnCheckedChangeListener(null)
        binding.switchAllowAdd.setOnCheckedChangeListener(null)
        binding.switchAllowJoin.isChecked = state.settings.allowJoin
        binding.switchAllowAdd.isChecked = state.settings.allowGuestAdd
        binding.switchAllowJoin.setOnCheckedChangeListener { _, c ->
            if (viewModel.isHost.value) viewModel.updateAllowJoin(c)
        }
        binding.switchAllowAdd.setOnCheckedChangeListener { _, c ->
            if (viewModel.isHost.value) viewModel.updateAllowGuestAdd(c)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QR Code Dialog
    // ─────────────────────────────────────────────────────────────────────────

    private fun showQrCodeDialog(sessionCode: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_qr_code, null)
        val ivQr      = dialogView.findViewById<ImageView>(R.id.ivQrCode)
        val tvUrl     = dialogView.findViewById<TextView>(R.id.tvQrUrl)
        val tvLoading = dialogView.findViewById<TextView>(R.id.tvQrLoading)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qr_dialog_title, sessionCode))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = "${PrefsManager.serverUrl}/api/session/$sessionCode/qr"
                    httpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) return@use null
                        val body = r.body?.string() ?: return@use null
                        Gson().fromJson(body, JsonObject::class.java)
                    }
                }
                if (!dialog.isShowing) return@launch
                if (result == null) { tvLoading.text = getString(R.string.qr_load_error); return@launch }
                val dataUrl = result.get("dataUrl")?.asString ?: ""
                val joinUrl = result.get("joinUrl")?.asString ?: ""
                val bytes   = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
                val bitmap  = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: run { tvLoading.text = getString(R.string.qr_load_error); return@launch }
                tvLoading.isVisible = false
                ivQr.setImageBitmap(bitmap); ivQr.isVisible = true
                tvUrl.text = joinUrl; tvUrl.isVisible = joinUrl.isNotBlank()
            } catch (e: Exception) {
                if (dialog.isShowing) tvLoading.text = getString(R.string.qr_load_error)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatMs(ms: Long): String {
        val min = TimeUnit.MILLISECONDS.toMinutes(ms)
        val sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%d:%02d".format(min, sec)
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)
            ))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Browser nicht gefunden.", Toast.LENGTH_SHORT).show()
        }
    }
}
