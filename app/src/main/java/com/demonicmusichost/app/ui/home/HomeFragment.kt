package com.demonicmusichost.app.ui.home

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.PrefsManager
import com.demonicmusichost.app.databinding.FragmentHomeBinding
import com.demonicmusichost.app.ui.scanner.QRScannerActivity
import com.demonicmusichost.app.ui.spotify.SpotifyAuthActivity
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    // QR scanner launcher — receives the scanned session code back
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra(QRScannerActivity.EXTRA_RESULT_CODE)
        if (!code.isNullOrBlank()) {
            binding.etSessionCode.setText(code)
            Toast.makeText(requireContext(),
                getString(R.string.qr_code_scanned, code),
                Toast.LENGTH_SHORT).show()
        }
    }

    // Spotify OAuth launcher
    private val spotifyAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(),
                getString(R.string.spotify_connected), Toast.LENGTH_SHORT).show()
        }
        updateSpotifyUi()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeState()
        updateSpotifyUi()
    }

    private fun setupListeners() {
        binding.btnCreateSession.setOnClickListener {
            val username = binding.etCreateUsername.text.toString()
            viewModel.createSession(username)
        }

        binding.btnJoinSession.setOnClickListener {
            val username = binding.etJoinUsername.text.toString()
            val code = binding.etSessionCode.text.toString()
            viewModel.joinSession(username, code)
        }

        binding.btnScanQr.setOnClickListener {
            val intent = Intent(requireContext(), QRScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        }

        binding.btnServerSettings.setOnClickListener {
            showServerSettingsDialog()
        }

        binding.btnSpotifyLogin.setOnClickListener {
            val intent = Intent(requireContext(), SpotifyAuthActivity::class.java)
            spotifyAuthLauncher.launch(intent)
        }

        binding.btnSpotifyLogout.setOnClickListener {
            PrefsManager.clearSpotify()
            updateSpotifyUi()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.isVisible = state is HomeUiState.Loading
                    binding.btnCreateSession.isEnabled = state !is HomeUiState.Loading
                    binding.btnJoinSession.isEnabled = state !is HomeUiState.Loading

                    when (state) {
                        is HomeUiState.SessionReady -> {
                            findNavController().navigate(R.id.action_home_to_session)
                        }
                        is HomeUiState.Error -> {
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
                        else -> {}
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.serverUrl.collect { url ->
                    binding.tvServerUrl.text = getString(R.string.server_label, url)
                }
            }
        }
    }

    private fun updateSpotifyUi() {
        val connected = PrefsManager.isSpotifyValid()
        binding.btnSpotifyLogin.isVisible = !connected
        binding.btnSpotifyLogout.isVisible = connected
        binding.tvSpotifyStatus.isVisible = connected
        if (connected) {
            binding.tvSpotifyStatus.text = getString(R.string.spotify_connected)
        }
    }

    private fun showServerSettingsDialog() {
        val input = EditText(requireContext()).apply {
            setText(viewModel.serverUrl.value)
            hint = "https://yourserver.com"
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.server_settings_title)
            .setMessage(R.string.server_settings_message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) viewModel.updateServerUrl(url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
