package com.replayer.network.upnp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.replayer.model.UpnpDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class SsdpDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val SSDP_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:ContentDirectory:1"
    }

    fun discoverDevices(timeoutMs: Int = 4000): Flow<List<UpnpDevice>> = flow {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("RePlayerSsdpLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        val discoveredMap = mutableMapOf<String, UpnpDevice>()

        try {
            val searchMessage = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_IP:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 3\r\n")
                append("ST: $SEARCH_TARGET\r\n")
                append("\r\n")
            }

            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            val sendData = searchMessage.toByteArray()
            val group = InetAddress.getByName(SSDP_IP)
            val packet = DatagramPacket(sendData, sendData.size, group, SSDP_PORT)
            socket.send(packet)

            val receiveBuffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)

                    val location = extractHeader(response, "LOCATION")
                    val usn = extractHeader(response, "USN") ?: location

                    if (location != null && !discoveredMap.containsKey(location)) {
                        val device = UpnpDeviceParser.fetchAndParse(location)
                        if (device != null) {
                            discoveredMap[location] = device
                            emit(discoveredMap.values.toList())
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error receiving SSDP packet", e)
                }
            }
            socket.close()
        } finally {
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }
        emit(discoveredMap.values.toList())
    }.flowOn(Dispatchers.IO)

    private fun extractHeader(response: String, headerName: String): String? {
        val lines = response.split("\r?\n".toRegex())
        for (line in lines) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].trim().equals(headerName, ignoreCase = true)) {
                return parts[1].trim()
            }
        }
        return null
    }
}
