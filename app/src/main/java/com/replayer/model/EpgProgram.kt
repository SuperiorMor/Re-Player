package com.replayer.model

import kotlinx.serialization.Serializable

@Serializable
data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long
) {
    fun isCurrent(now: Long = System.currentTimeMillis()): Boolean {
        return now in startTime..endTime
    }

    fun progress(now: Long = System.currentTimeMillis()): Float {
        if (endTime <= startTime) return 0f
        return ((now - startTime).toFloat() / (endTime - startTime).toFloat()).coerceIn(0f, 1f)
    }

    fun formattedTimeRange(): String {
        val s = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return "${s.format(java.util.Date(startTime))} - ${s.format(java.util.Date(endTime))}"
    }

    fun formattedStartTime(): String {
        val s = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return s.format(java.util.Date(startTime))
    }

    fun formattedEndTime(): String {
        val s = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return s.format(java.util.Date(endTime))
    }
}
