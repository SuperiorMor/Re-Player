package com.replayer

import android.app.Application
import com.replayer.updater.UpdateManager
import java.io.File

class RePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            UpdateManager(this).cleanOldUpdates()
        } catch (_: Throwable) {}

        // Emergency cleanup: if an oversized/corrupt EPG cache file exists from previous version, remove it immediately
        try {
            val epgFile = File(filesDir, "iptv_epg.json")
            if (epgFile.exists() && epgFile.length() > 1_500_000L) {
                epgFile.delete()
            }
            val tempEpg = File(cacheDir, "temp_epg.xml")
            if (tempEpg.exists()) {
                tempEpg.delete()
            }
        } catch (_: Throwable) {}
    }
}
