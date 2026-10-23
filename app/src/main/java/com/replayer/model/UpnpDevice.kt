package com.replayer.model

import kotlinx.serialization.Serializable

@Serializable
data class UpnpDevice(
    val id: String,
    val friendlyName: String,
    val manufacturer: String,
    val location: String,
    val iconUrl: String? = null,
    val contentDirectoryControlUrl: String? = null
)
