package com.replayer

import android.app.Application
import com.replayer.updater.UpdateManager

class RePlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            UpdateManager(this).cleanOldUpdates()
        } catch (_: Exception) {}
    }
}
