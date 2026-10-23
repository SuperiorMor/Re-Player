package com.replayer.model

data class UpnpDevice(
    val id: String,
    val friendlyName: String,
    val manufacturer: String,
    val location: String,
    val iconUrl: String? = null,
    val contentDirectoryControlUrl: String? = null
)
