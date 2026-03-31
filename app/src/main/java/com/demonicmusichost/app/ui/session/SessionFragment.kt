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

    // ExoPlayer for local-source tracks
    private var player: ExoPlayer? = null
    private var lastLocalTrackId: String? = null   // tracks which file is loaded

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

    // ─── ExoPlayer ────────────────────────────────────────────────────────────────

    private fun setupExoPlayer() {
        player = ExoPlayer.Builder(requireContext()).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        // Notify server so it can advance the queue
                        SocketManager.reportTrackEnded()
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // Periodically report position to server for web-client sync
                    if (isPlaying) startProgressReporting()
                }
            })
        }
    }

    private var progressJob: kotlinx.coroutines.Job? = null

    private fun startProgressReporting() {
        progressJob?.cancel()
        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                val p = player ?: break
                if (!p.isPlaying) break
                SocketManager.reportProgress(p.currentPosition)
            }
        }
    }

    private fun handleLocalPlayback(state: SessionState) {
        val currentTrack = state.queue.getOrNull(state.currentTrackIndex)
        val p = player ?: return

        if (currentTrack == null || currentTrack.source != "local") {
            // Not a local track — stop local playback
            if (p.isPlaying) p.stop()
            lastLocalTrackId = null
            return
        }

        // Load new track if changed
        if (lastLocalTrackId != currentTrack.sourceId) {
            lastLocalTrackId = currentTrack.sourceId
            val streamUrl = "${SocketManager.getServerUrl()}${currentTrack.url}"
            p.setMediaItem(MediaItem.fromUri(streamUrl))
            p.prepare()
        }

        if (state.isPlaying && !p.isPlaying) {
            p.play()
        } else if (!state.isPlaying && p.isPlaying) {
            p.pause()
        }
    }

    // ─── Adapters ─────────────────────────────────────────────────────────────────

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
                    .setPositiveButton(R.string.kick) { _, _ -> viewModel.kickParticipant(participant.socketId) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        binding.rvParticipants.apply {
            adapter = participantAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ─── Listeners ────────────────────────────────────────────────────────────────

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
                    player?.release()
                    player = null
                    viewModel.leaveSession()
                    findNavController().navigate(R.id.action_session_to_home)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnCopyCode.setOnClickListener {
            val code = viewModel.mySessionId.value ?: return@setOnClickListener
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Session Code", code))
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
            val url = "${viewModel.getServerUrl()}/?code=$code"
            openInBrowser(url)
        }
    }

    // ─── State observation ────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionState.collect { state ->
                    if (state != null) {
                        handleLocalPlayback(state)
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
                viewModel.mySessionId.collect { id ->
                    binding.tvSessionCode.text = id ?: "—"
                }
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

    // ─── Render ───────────────────────────────────────────────────────────────────

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

        // For local tracks, use ExoPlayer's position for smoother progress display
        val displayPosition = if (currentTrack?.source == "local")
            player?.currentPosition ?: state.position
        else state.position

        if (currentTrack != null && currentTrack.duration > 0) {
            val progress = ((displayPosition.toFloat() / currentTrack.duration) * 100).toInt()
            binding.progressPlayback.progress = progress.coerceIn(0, 100)
            binding.tvProgressCurrent.text = formatMs(displayPosition)
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
        binding.switchAllowJoin.setOnCheckedChangeListener { _, checked ->
            if (viewModel.isHost.value) viewModel.updateAllowJoin(checked)
        }
        binding.switchAllowAdd.setOnCheckedChangeListener { _, checked ->
            if (viewModel.isHost.value) viewModel.updateAllowGuestAdd(checked)
        }
    }

    // ─── QR Code Dialog ───────────────────────────────────────────────────────────

    private fun showQrCodeDialog(sessionCode: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_qr_code, null)
        val ivQr     = dialogView.findViewById<ImageView>(R.id.ivQrCode)
        val tvUrl    = dialogView.findViewById<TextView>(R.id.tvQrUrl)
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
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string() ?: return@use null
                        Gson().fromJson(body, JsonObject::class.java)
                    }
                }
                if (!dialog.isShowing) return@launch
                if (result == null) {
                    tvLoading.text = getString(R.string.qr_load_error)
                    return@launch
                }
                val dataUrl  = result.get("dataUrl")?.asString ?: ""
                val joinUrl  = result.get("joinUrl")?.asString ?: ""
                val base64   = dataUrl.substringAfter("base64,")
                val bytes    = Base64.decode(base64, Base64.DEFAULT)
                val bitmap   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    tvLoading.text = getString(R.string.qr_load_error)
                    return@launch
                }
                tvLoading.isVisible = false
                ivQr.setImageBitmap(bitmap)
                ivQr.isVisible = true
                tvUrl.text = joinUrl
                tvUrl.isVisible = joinUrl.isNotBlank()
            } catch (e: Exception) {          // catch ALL exceptions (JSON, Base64, IO…)
                if (dialog.isShowing) tvLoading.text = getString(R.string.qr_load_error)
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

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

    override fun onDestroyView() {
        progressJob?.cancel()
        player?.release()
        player = null
        super.onDestroyView()
        _binding = null
    }
}
