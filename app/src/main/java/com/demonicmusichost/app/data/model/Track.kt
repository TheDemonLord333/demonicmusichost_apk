package com.demonicmusichost.app.data.model

import com.google.gson.annotations.SerializedName

data class Track(
    @SerializedName("id")           val id: String = "",
    @SerializedName("title")        val title: String = "",
    @SerializedName("artist")       val artist: String = "",
    @SerializedName("album")        val album: String = "",
    @SerializedName("thumbnail")    val thumbnail: String = "",
    @SerializedName("duration")     val duration: Long = 0L,
    @SerializedName("source")       val source: String = "",      // "spotify"|"youtube"|"local"
    @SerializedName("sourceId")     val sourceId: String = "",
    @SerializedName("url")          val url: String = "",
    @SerializedName("addedBy")      val addedBy: String = "",

    // Source-specific fields returned by the server's search endpoints
    @SerializedName("youtubeId")    val youtubeId: String? = null,   // YouTube video ID
    @SerializedName("spotifyUri")   val spotifyUri: String? = null,  // "spotify:track:…"
    @SerializedName("previewUrl")   val previewUrl: String? = null,  // Spotify 30-sec MP3
    @SerializedName("localFileId")  val localFileId: String? = null  // UUID from /upload
)
