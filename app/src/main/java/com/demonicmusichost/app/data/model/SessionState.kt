package com.demonicmusichost.app.data.model

import com.google.gson.annotations.SerializedName

data class SessionState(
    @SerializedName("id") val id: String = "",
    @SerializedName("queue") val queue: List<Track> = emptyList(),
    @SerializedName("currentTrackIndex") val currentTrackIndex: Int = -1,
    @SerializedName("isPlaying") val isPlaying: Boolean = false,
    @SerializedName("position") val position: Long = 0L,
    @SerializedName("participants") val participants: List<Participant> = emptyList(),
    @SerializedName("hostUsername") val hostUsername: String = "",
    @SerializedName("settings") val settings: SessionSettings = SessionSettings()
)

data class SessionSettings(
    @SerializedName("allowJoin") val allowJoin: Boolean = true,
    @SerializedName("allowGuestAdd") val allowGuestAdd: Boolean = true
)
