package com.gpm.virtual.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gpm.virtual.data.ConfigSyncRegistry
import com.gpm.virtual.data.KnownPackages
import com.gpm.virtual.data.preferences.GpsPreferences
import com.gpm.virtual.manager.SpoofLifecycleManager
import com.gpm.virtual.service.MockLocationService
import com.gpm.virtual.util.NetworkStabilityEngine
import com.gpm.virtual.util.XposedStatus
import com.gpm.virtual.xposed.HotReloadActions
import java.io.File

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.REBOOT"
        ) return

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "📱 Device boot completed — restoring all GPM settings ($action)")
        Log.i(TAG, "   Controllers: ${ConfigSyncRegistry.describeAll()}")
        Log.i(TAG, "   Total: ${ConfigSyncRegistry.count()} features")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        try {
            // 1. Ensure file & directory permissions for LSPosed / Xposed cross-app access
            try {
                val dataDir = context.applicationInfo.dataDir
                val prefsDir = File(dataDir, "shared_prefs")
                if (prefsDir.exists()) {
                    prefsDir.setReadable(true, false)
                    prefsDir.setExecutable(true, false)
                    prefsDir.listFiles()?.forEach { it.setReadable(true, false) }
                }
                val filesDir = context.filesDir
                filesDir?.setReadable(true, false)
                filesDir?.setExecutable(true, false)
                filesDir?.listFiles()?.forEach { it.setReadable(true, false) }
            } catch (_: Throwable) {}

            try {
                val active = XposedStatus.isModuleActive(context)
                Log.i(TAG, "Xposed module active = $active")
            } catch (t: Throwable) {
                Log.w(TAG, "isModuleActive check failed: ${t.message}")
            }

            try {
                ConfigSyncRegistry.registerAll(context)
            } catch (t: Throwable) {
                Log.w(TAG, "registerAll failed: ${t.message}")
            }

            // 2. Load stored configuration
            val prefs = GpsPreferences.getInstance(context)
            val config = prefs.getConfig()

            // 3. Restore Network Stability if enabled
            if (config.isConnectionStabilityEnabled) {
                try {
                    NetworkStabilityEngine.acquireHighPerfWifiLock(context)
                    if (config.isGcmSupportEnabled) {
                        NetworkStabilityEngine.startGcmHeartbeatKeeper(context)
                    }
                    Log.i(TAG, "Connection stability restored after boot")
                } catch (t: Throwable) {
                    Log.w(TAG, "NetworkStability restore error: ${t.message}")
                }
            }

            // 4. Init lifecycle manager and restore state
            SpoofLifecycleManager.init(context)
            val st = SpoofLifecycleManager.getState(context)
            Log.i(TAG, "Lifecycle: active=${st.active} scope=${st.scope} gen=${st.generation} configSpoof=${config.isSpoofActive}")

            // 5. Restore spoof state
            restoreLocationSpoofFromBoot(context)

            // 6. Resend full config & hot reload across multi-wave schedule
            scheduleMultiWaveConfigResend(context)

            // 7. Start service if spoof was active
            if (st.active || config.isSpoofActive) {
                Log.i(TAG, "Auto-starting MockLocationService (spoof active on boot)")
                safeStartService(context)
                safePing(context)
            } else {
                Log.i(TAG, "Spoof OFF on boot — service not started")
            }

            Log.i(TAG, "✅ Boot restore scheduled across 5 waves successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "BootReceiver error: ${t.message}", t)
        }
    }

    private fun scheduleMultiWaveConfigResend(context: Context) {
        val appCtx = context.applicationContext ?: context
        val handler = Handler(Looper.getMainLooper())
        val waves = listOf(WAVE0_MS, WAVE1_MS, WAVE2_MS, WAVE3_MS, WAVE4_MS)

        for (delay in waves) {
            handler.postDelayed({
                try {
                    ConfigSyncRegistry.resendAll()
                    HotReloadActions.dispatchFullHotReload(appCtx, trigger = "boot_wave_${delay}ms")
                    Log.d(TAG, "📡 Boot wave ${delay}ms sent")
                } catch (_: Throwable) {}
            }, delay)
        }
    }

    private fun restoreLocationSpoofFromBoot(ctx: Context) {
        try {
            val st = SpoofLifecycleManager.getState(ctx)
            val prefs = GpsPreferences.getInstance(ctx)
            val cfg = prefs.getConfig()

            if (!st.active && !cfg.isSpoofActive) {
                // OFF — broadcast HARD_SHUTDOWN to targets
                safeBroadcast(ctx, KnownPackages.GOJEK_PARTNER, "com.gpm.virtual.HARD_SHUTDOWN") {
                    putExtra("trigger", "gpm_boot_off")
                }
                for (pkg in listOf(KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER)) {
                    safeBroadcast(ctx, pkg, "com.gpm.virtual.HARD_SHUTDOWN") {
                        putExtra("trigger", "gpm_boot_off")
                    }
                }
                Log.i(TAG, "Broadcast HARD_SHUTDOWN to all targets")
                return
            }

            // ON — broadcast coordinates to targets
            val gojekLat = if (st.gojekLat != 0.0) st.gojekLat else cfg.latitude
            val gojekLng = if (st.gojekLng != 0.0) st.gojekLng else cfg.longitude
            val grabLat = if (st.grabLat != 0.0) st.grabLat else cfg.latitude
            val grabLng = if (st.grabLng != 0.0) st.grabLng else cfg.longitude

            if (st.scope == "GOJEK" || st.scope == "ALL") {
                if (gojekLat != 0.0 && gojekLng != 0.0) {
                    safeBroadcast(ctx, KnownPackages.GOJEK_PARTNER, "com.gpm.virtual.ACTION_SPOOF_GOJEK") {
                        putExtra("active", true)
                        putExtra("override", true)
                        putExtra("latitude", gojekLat)
                        putExtra("longitude", gojekLng)
                        putExtra("trigger", "gpm_boot")
                    }
                    Log.i(TAG, "[Spoof] Gojek restored: $gojekLat, $gojekLng")
                }
            }
            if (st.scope == "GRAB" || st.scope == "ALL") {
                if (grabLat != 0.0 && grabLng != 0.0) {
                    for (pkg in listOf(KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER)) {
                        safeBroadcast(ctx, pkg, "com.gpm.virtual.ACTION_SPOOF_GRAB") {
                            putExtra("active", true)
                            putExtra("override", true)
                            putExtra("latitude", grabLat)
                            putExtra("longitude", grabLng)
                            putExtra("trigger", "gpm_boot")
                        }
                    }
                    Log.i(TAG, "[Spoof] Grab restored: $grabLat, $grabLng")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restoreLocationSpoofFromBoot: ${t.message}")
        }
    }

    private fun safeStartService(ctx: Context) {
        try { MockLocationService.start(ctx) }
        catch (t: Throwable) { Log.w(TAG, "startService failed: ${t.message}") }
    }

    private fun safePing(ctx: Context) {
        try { MockLocationService.pingLocationToScope(ctx) }
        catch (t: Throwable) { Log.w(TAG, "ping failed: ${t.message}") }
    }

    private inline fun safeBroadcast(
        ctx: Context,
        targetPackage: String,
        action: String,
        fill: Intent.() -> Unit = {}
    ) {
        try {
            val intent = Intent(action).apply {
                setPackage(targetPackage)
                fill()
            }
            ctx.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "safeBroadcast to $targetPackage failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "GPM-BootReceiver"
        private const val WAVE0_MS = 500L
        private const val WAVE1_MS = 5_000L
        private const val WAVE2_MS = 15_000L
        private const val WAVE3_MS = 45_000L
        private const val WAVE4_MS = 90_000L
    }
}