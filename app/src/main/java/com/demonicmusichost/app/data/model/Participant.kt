package com.demonicmusichost.app.data.model

import com.google.gson.annotations.SerializedName

data class Participant(
    @SerializedName("username") val username: String = "",
    @SerializedName("isHost") val isHost: Boolean = false,
    @SerializedName("socketId") val socketId: String = ""
)
