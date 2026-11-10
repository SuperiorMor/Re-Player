package com.replayer

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.replayer.diagnostics.CrashReporter
import com.replayer.updater.UpdateManager
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class RePlayerApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
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

    override fun newImageLoader(): ImageLoader {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                var req = chain.request()
                val host = req.url.host
                if (host.equals("epg.it999.ru", ignoreCase = true) || host.equals("epg.one", ignoreCase = true)) {
                    val fixed = req.url.newBuilder().host("cdn.epg.one").build()
                    req = req.newBuilder().url(fixed).build()
                }
                req = req.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                chain.proceed(req)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(32L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            CrashReporter.breadcrumb("Memory", "onTrimMemory level=$level")
        }
    }
}
