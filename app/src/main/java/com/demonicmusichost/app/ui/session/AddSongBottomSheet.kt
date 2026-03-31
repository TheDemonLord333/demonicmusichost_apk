package com.demonicmusichost.app.ui.session

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.PrefsManager
import com.demonicmusichost.app.data.SocketManager
import com.demonicmusichost.app.data.model.Track
import com.demonicmusichost.app.databinding.BottomSheetAddSongBinding
import com.demonicmusichost.app.databinding.ItemSearchResultBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AddSongBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddSongBinding? = null
    private val binding get() = _binding!!
    private lateinit var resultsAdapter: SearchResultsAdapter
    private var currentSource = "spotify"

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
        }.build()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = BottomSheetAddSongBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        resultsAdapter = SearchResultsAdapter { track ->
            SocketManager.queueAdd(track)
            Toast.makeText(requireContext(),
                getString(R.string.track_added, track.title), Toast.LENGTH_SHORT).show()
        }
        binding.rvSearchResults.apply {
            adapter = resultsAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        // Default tab
        updateSourceTab("spotify")

        binding.tabSource.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentSource = if (tab.position == 0) "spotify" else "youtube"
                updateSourceTab(currentSource)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                doSearch(); true
            } else false
        }
    }

    private fun updateSourceTab(source: String) {
        val needsToken = source == "spotify"
        val hasToken = PrefsManager.isSpotifyValid()
        binding.tvNoSpotify.isVisible = needsToken && !hasToken
        binding.btnSearch.isEnabled = !(needsToken && !hasToken)
    }

    private fun doSearch() {
        val query = binding.etSearch.text?.toString()?.trim() ?: return
        if (query.isEmpty()) return

        val serverUrl = PrefsManager.serverUrl
        val url = if (currentSource == "spotify") {
            val token = PrefsManager.spotifyToken ?: return
            "$serverUrl/search/spotify?q=${encode(query)}&token=${encode(token)}"
        } else {
            "$serverUrl/search/youtube?q=${encode(query)}"
        }

        binding.progressSearch.isVisible = true
        binding.tvSearchError.isVisible = false
        resultsAdapter.submitList(emptyList())

        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    binding.progressSearch.isVisible = false
                    binding.tvSearchError.isVisible = true
                    binding.tvSearchError.text = getString(R.string.search_error, e.message)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                activity?.runOnUiThread {
                    binding.progressSearch.isVisible = false
                    if (!response.isSuccessful) {
                        binding.tvSearchError.isVisible = true
                        binding.tvSearchError.text = getString(R.string.search_error, response.code.toString())
                        return@runOnUiThread
                    }
                    try {
                        val json = Gson().fromJson(body, JsonObject::class.java)
                        val tracksArr = json.getAsJsonArray("tracks")
                        val tracks = (0 until tracksArr.size()).map { i ->
                            Gson().fromJson(tracksArr[i], Track::class.java)
                        }
                        resultsAdapter.submitList(tracks)
                        if (tracks.isEmpty()) {
                            binding.tvSearchError.isVisible = true
                            binding.tvSearchError.text = getString(R.string.no_results)
                        }
                    } catch (e: Exception) {
                        binding.tvSearchError.isVisible = true
                        binding.tvSearchError.text = getString(R.string.search_error, e.message)
                    }
                }
            }
        })
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─── Adapter ─────────────────────────────────────────────────────────────────

    inner class SearchResultsAdapter(
        private val onAdd: (Track) -> Unit
    ) : ListAdapter<Track, SearchResultsAdapter.VH>(DIFF) {

        inner class VH(private val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(track: Track) {
                b.tvTitle.text = track.title.ifBlank { "—" }
                b.tvArtist.text = track.artist.ifBlank { track.addedBy }
                if (track.thumbnail.isNotBlank()) {
                    Glide.with(b.ivThumb).load(track.thumbnail)
                        .placeholder(R.drawable.ic_music_note).into(b.ivThumb)
                } else {
                    b.ivThumb.setImageResource(R.drawable.ic_music_note)
                }
                b.btnAdd.setOnClickListener { onAdd(track) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        private val DIFF = object : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(a: Track, b: Track) = a.sourceId == b.sourceId && a.source == b.source
            override fun areContentsTheSame(a: Track, b: Track) = a == b
        }
    }

    companion object {
        const val TAG = "AddSongBottomSheet"
        private val DIFF = object : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(a: Track, b: Track) = a.id == b.id
            override fun areContentsTheSame(a: Track, b: Track) = a == b
        }
    }
}
