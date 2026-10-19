package com.gpm.virtual

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gpm.virtual.data.ConfigSyncRegistry
import com.gpm.virtual.data.KnownPackages
import org.osmdroid.config.Configuration
import java.io.File

class GpmApplication : Application() {

    lateinit var preferences: com.gpm.virtual.data.preferences.GpsPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(APP_TAG, "🚀 GpmApplication starting (${packageName})")

        // ═══ 0. OkHttp Networking Engine ═══
        try {
            com.gpm.virtual.network.OkHttpManager.init(this)
            Log.i(APP_TAG, "✅ OkHttpManager initialized")
        } catch (t: Throwable) {
            Log.w(APP_TAG, "OkHttpManager init warning: ${t.message}")
        }

        // ═══ 1. GpsPreferences ═══
        try {
            preferences = com.gpm.virtual.data.preferences.GpsPreferences.getInstance(this)
            Log.i(APP_TAG, "✅ GpsPreferences initialized")
        } catch (t: Throwable) {
            Log.e(APP_TAG, "❌ GpsPreferences init failed: ${t.message}", t)
            throw RuntimeException("GpsPreferences mandatory init failed", t)
        }

        // ═══ 2. SpoofLifecycleManager ═══
        try {
            com.gpm.virtual.manager.SpoofLifecycleManager.init(this)
            Log.i(APP_TAG, "✅ SpoofLifecycleManager initialized")
        } catch (t: Throwable) {
            Log.e(APP_TAG, "❌ SpoofLifecycleManager failed: ${t.message}", t)
        }

        // ═══ 3. Clear stale spoof flags ═══
        try {
            val config = preferences.getConfig()
            if (config.isSpoofActive) {
                preferences.updateConfig { it.copy(isSpoofActive = false) }
                Log.i(APP_TAG, "🧹 isSpoofActive cleared")
            }
        } catch (t: Throwable) {
            Log.w(APP_TAG, "Clear isSpoofActive: ${t.message}")
        }
        try {
            com.gpm.virtual.data.InjectionPrefsHelper.setSpoofActive(this, "gojek", false)
            com.gpm.virtual.data.InjectionPrefsHelper.setSpoofActive(this, "grab", false)
        } catch (_: Throwable) {}

        // ═══ 4. Sortir — PRIORITAS TINGGI ═══
        try {
            com.gpm.virtual.data.SortirSettingsController.register(this)
            Log.i(APP_TAG, "✅ SortirSettingsController registered")

            // ⭐ Force write — pastikan prefs ada & mtime berubah (trigger LSPosed reload)
            com.gpm.virtual.data.SortirSettingsController.forceWriteDefaults(this)
            Log.i(APP_TAG, "✅ Sortir forceWriteDefaults done")

            // Sync ke GPM lain + HotReload ke Grab
            com.gpm.virtual.data.SortirSettingsController.resendConfig()
            Log.i(APP_TAG, "✅ Sortir resendConfig done")
        } catch (t: Throwable) {
            Log.e(APP_TAG, "❌ Sortir pipeline FAILED: ${t.message}", t)
        }

        // ═══ 5. ConfigSyncRegistry ═══
        try {
            ConfigSyncRegistry.registerAll(this)
            Log.i(APP_TAG, "✅ Controllers: ${ConfigSyncRegistry.describeAll()}")
        } catch (t: Throwable) {
            Log.w(APP_TAG, "ConfigSyncRegistry: ${t.message}")
        }

        // ═══ 6. Startup HotReload ═══
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ConfigSyncRegistry.resendAll()
                Log.i(APP_TAG, "🔥 Startup HotReload sent")
            } catch (t: Throwable) {
                Log.w(APP_TAG, "Startup HotReload: ${t.message}")
            }
        }, 500L)

        // ═══ 7. VtracingEngine center ═══
        try {
            val config = preferences.getConfig()
            com.gpm.virtual.util.VtracingEngine.setCenter(config.latitude, config.longitude)
        } catch (t: Throwable) {
            Log.w(APP_TAG, "VtracingEngine: ${t.message}")
        }

        // ═══ 8. WiFi lock ═══
        try {
            if (preferences.getConfig().isConnectionStabilityEnabled) {
                com.gpm.virtual.util.NetworkStabilityEngine.acquireHighPerfWifiLock(this)
            }
        } catch (_: Throwable) {}

        // ═══ 9. osmdroid ═══
        try {
            Configuration.getInstance().apply {
                userAgentValue = packageName
                osmdroidBasePath = File(cacheDir, "osmdroid")
                osmdroidTileCache = File(osmdroidBasePath, "tiles")
            }
        } catch (t: Throwable) {
            Log.w(APP_TAG, "osmdroid: ${t.message}")
        }

        // ═══ 10. HARD_SHUTDOWN ═══
        try {
            val targets = listOf(
                KnownPackages.GOJEK_PARTNER,
                KnownPackages.GRAB_TAXI_DRIVER,
                KnownPackages.GRAB_DRIVER,
            )
            for (pkg in targets) {
                try {
                    sendBroadcast(Intent("com.gpm.virtual.HARD_SHUTDOWN").apply {
                        setPackage(pkg)
                        putExtra("trigger", "app_start_sync")
                    })
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // ═══ 11. Delayed Sortir sync (safety net) ═══
        for (delay in listOf(500L, 2000L)) {
            try {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        com.gpm.virtual.data.SortirSettingsController.forceWriteDefaults(this)
                        com.gpm.virtual.data.SortirSettingsController.resendConfig()
                        Log.d(APP_TAG, "🔄 Delayed Sortir sync @${delay}ms")
                    } catch (_: Throwable) {}
                }, delay)
            } catch (_: Throwable) {}
        }

        Log.i(APP_TAG, "🚀 GpmApplication ready")
    }

    companion object {
        private const val APP_TAG = "GpmApplication"
    }
}