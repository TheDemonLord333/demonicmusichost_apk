package com.demonicmusichost.app.data.model

import com.google.gson.annotations.SerializedName

data class Track(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("artist") val artist: String = "",
    @SerializedName("album") val album: String = "",
    @SerializedName("thumbnail") val thumbnail: String = "",
    @SerializedName("duration") val duration: Long = 0L,       // milliseconds
    @SerializedName("source") val source: String = "",          // "spotify" | "youtube" | "local"
    @SerializedName("sourceId") val sourceId: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("addedBy") val addedBy: String = ""
)
