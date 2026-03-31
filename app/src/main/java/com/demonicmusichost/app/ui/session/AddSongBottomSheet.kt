package com.demonicmusichost.app.ui.session

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AddSongBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddSongBinding? = null
    private val binding get() = _binding!!
    private lateinit var resultsAdapter: SearchResultsAdapter
    private var currentSource = "spotify"

    // Local audio file picker — must be registered before onStart()
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) uploadAndQueueLocalFile(uri)
    }

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
            connectTimeout(30, TimeUnit.SECONDS)
            writeTimeout(120, TimeUnit.SECONDS)   // uploads may be large
            readTimeout(30, TimeUnit.SECONDS)
        }.build()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = BottomSheetAddSongBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        resultsAdapter = SearchResultsAdapter { track ->
            if (!SocketManager.connected.value) {
                Toast.makeText(requireContext(),
                    "Keine Verbindung zum Server.", Toast.LENGTH_SHORT).show()
                return@SearchResultsAdapter
            }
            SocketManager.queueAdd(track)
            Toast.makeText(requireContext(),
                getString(R.string.track_added, track.title), Toast.LENGTH_SHORT).show()
        }
        binding.rvSearchResults.apply {
            adapter = resultsAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        updateSourceUi("spotify")

        binding.tabSource.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentSource = when (tab.position) {
                    0 -> "spotify"
                    1 -> "youtube"
                    else -> "local"
                }
                updateSourceUi(currentSource)
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

        binding.btnPickLocalFile.setOnClickListener {
            filePicker.launch("audio/*")
        }
    }

    private fun updateSourceUi(source: String) {
        val isLocal = source == "local"
        val isSpotify = source == "spotify"
        val hasToken = PrefsManager.isSpotifyValid()

        binding.llSearchBar.isVisible = !isLocal
        binding.btnSearch.isVisible = !isLocal
        binding.btnPickLocalFile.isVisible = isLocal
        binding.rvSearchResults.isVisible = !isLocal
        binding.tvSearchError.isVisible = false
        binding.progressSearch.isVisible = false
        resultsAdapter.submitList(emptyList())

        binding.tvNoSpotify.isVisible = isSpotify && !hasToken
        if (!isLocal) binding.btnSearch.isEnabled = !(isSpotify && !hasToken)
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
                        binding.tvSearchError.text = getString(R.string.search_error, "${response.code}")
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

    // ─── Local file: upload → queue ───────────────────────────────────────────────

    private fun uploadAndQueueLocalFile(uri: Uri) {
        val ctx = requireContext()
        binding.progressSearch.isVisible = true
        binding.btnPickLocalFile.isEnabled = false
        binding.tvSearchError.isVisible = false

        // All heavy I/O runs on Dispatchers.IO — never blocks the main thread
        lifecycleScope.launch {
            try {
                val track = withContext(Dispatchers.IO) {
                    // 1. Extract audio metadata
                    val retriever = MediaMetadataRetriever()
                    val title: String; val artist: String
                    val album: String; val duration: Long
                    val mimeType: String; val fileName: String
                    try {
                        retriever.setDataSource(ctx, uri)
                        title    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                   ?: (uri.lastPathSegment?.substringBeforeLast('.') ?: "Unknown")
                        artist   = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                        album    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                   ?.toLongOrNull() ?: 0L
                        mimeType = ctx.contentResolver.getType(uri) ?: "audio/mpeg"
                        val ext  = mimeType.substringAfter('/').replace("mpeg", "mp3").take(5)
                        fileName = "${title.take(40)}.$ext"
                    } finally {
                        retriever.release()
                    }

                    // 2. Get file size (for Content-Length header, avoids chunked encoding)
                    val fileSize = ctx.contentResolver
                        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L

                    // 3. Streaming multipart body — reads in 8 KB chunks, never loads
                    //    the full file into memory
                    val resolver = ctx.contentResolver
                    val streamBody = object : RequestBody() {
                        override fun contentType() = mimeType.toMediaTypeOrNull()
                        override fun contentLength() = fileSize
                        override fun writeTo(sink: BufferedSink) {
                            resolver.openInputStream(uri)?.use { input ->
                                val buf = ByteArray(8192)
                                var n: Int
                                while (input.read(buf).also { n = it } != -1)
                                    sink.write(buf, 0, n)
                            }
                        }
                    }

                    val multipart = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("audio", fileName, streamBody)
                        .build()

                    val request = Request.Builder()
                        .url("${PrefsManager.serverUrl}/upload")
                        .post(multipart)
                        .build()

                    // 4. Synchronous HTTP call (we're on IO thread inside withContext)
                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string() ?: throw IOException("Leere Serverantwort")
                    if (!response.isSuccessful)
                        throw IOException("Upload fehlgeschlagen (${response.code})")

                    val json   = Gson().fromJson(body, JsonObject::class.java)
                    val fileId = json.get("fileId")?.asString
                               ?: throw IOException("Keine fileId in Serverantwort")

                    Track(
                        id          = fileId,
                        title       = title,
                        artist      = artist,
                        album       = album,
                        duration    = duration,
                        source      = "local",
                        sourceId    = fileId,
                        localFileId = fileId,
                        url         = "/upload/stream/$fileId"
                    )
                }

                // Back on main thread
                SocketManager.queueAdd(track)
                Toast.makeText(requireContext(),
                    getString(R.string.track_added, track.title), Toast.LENGTH_SHORT).show()
                dismiss()

            } catch (e: Exception) {
                binding.tvSearchError.text = getString(R.string.search_error, e.message)
                binding.tvSearchError.isVisible = true
            } finally {
                binding.progressSearch.isVisible = false
                binding.btnPickLocalFile.isEnabled = true
            }
        }
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
