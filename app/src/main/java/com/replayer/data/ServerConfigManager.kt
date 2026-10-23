package com.replayer.data

import android.content.Context
import android.content.SharedPreferences
import com.replayer.model.UpnpDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServerConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("replayer_server_config", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val _connectedUpnpDevice = MutableStateFlow<UpnpDevice?>(loadConnectedDevice())
    val connectedUpnpDevice: StateFlow<UpnpDevice?> = _connectedUpnpDevice.asStateFlow()

    private fun loadConnectedDevice(): UpnpDevice? {
        val raw = prefs.getString("connected_upnp_device", null) ?: return null
        return try {
            json.decodeFromString<UpnpDevice>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun connectUpnpDevice(device: UpnpDevice) {
        _connectedUpnpDevice.value = device
        try {
            val encoded = json.encodeToString(device)
            prefs.edit().putString("connected_upnp_device", encoded).apply()
        } catch (_: Exception) {}
    }

    fun disconnectUpnpDevice() {
        _connectedUpnpDevice.value = null
        prefs.edit().remove("connected_upnp_device").apply()
    }
}
