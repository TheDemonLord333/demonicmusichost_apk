package com.demonicmusichost.app.ui.session

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.model.SessionState
import com.demonicmusichost.app.data.model.Track
import com.demonicmusichost.app.databinding.FragmentSessionBinding
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SessionFragment : Fragment() {

    private var _binding: FragmentSessionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SessionViewModel by viewModels()
    private lateinit var queueAdapter: QueueAdapter
    private lateinit var participantAdapter: ParticipantAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapters()
        setupListeners()
        observeState()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun setupAdapters() {
        queueAdapter = QueueAdapter { index ->
            viewModel.removeTrack(index)
        }
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

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionState.collect { state ->
                    if (state != null) renderState(state)
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

    // ─────────────────────────────────────────────────────────────────────────

    private fun renderState(state: SessionState) {
        // Queue
        queueAdapter.currentTrackIndex = state.currentTrackIndex
        queueAdapter.submitList(state.queue)
        binding.tvQueueEmpty.isVisible = state.queue.isEmpty()

        // Participant list
        participantAdapter.submitList(state.participants)
        binding.tvParticipantCount.text =
            getString(R.string.participant_count, state.participants.size)

        // Now playing
        val currentTrack: Track? = state.queue.getOrNull(state.currentTrackIndex)
        if (currentTrack != null) {
            binding.tvNpTitle.text = currentTrack.title.ifBlank { "—" }
            binding.tvNpArtist.text = currentTrack.artist.ifBlank { currentTrack.addedBy }
            binding.tvNpAddedBy.text = getString(R.string.added_by, currentTrack.addedBy)

            if (currentTrack.thumbnail.isNotBlank()) {
                Glide.with(binding.ivNpThumbnail)
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

        // Play/Pause icon
        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // Progress
        if (currentTrack != null && currentTrack.duration > 0) {
            val progress = ((state.position.toFloat() / currentTrack.duration) * 100).toInt()
            binding.progressPlayback.progress = progress.coerceIn(0, 100)
            binding.tvProgressCurrent.text = formatMs(state.position)
            binding.tvProgressTotal.text = formatMs(currentTrack.duration)
        } else {
            binding.progressPlayback.progress = 0
            binding.tvProgressCurrent.text = "0:00"
            binding.tvProgressTotal.text = "0:00"
        }

        // Host settings switches (without triggering listener loop)
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

    private fun formatMs(ms: Long): String {
        val min = TimeUnit.MILLISECONDS.toMinutes(ms)
        val sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%d:%02d".format(min, sec)
    }

    private fun openInBrowser(url: String) {
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Browser nicht gefunden.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
