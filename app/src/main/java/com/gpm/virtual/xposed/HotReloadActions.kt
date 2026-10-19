package com.gpm.virtual.xposed

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.gpm.virtual.data.CancelCantikController
import com.gpm.virtual.data.KnownPackages
import com.gpm.virtual.data.SortirSettingsController
import com.gpm.virtual.data.preferences.GpsConfig
import com.gpm.virtual.data.preferences.GpsPreferences

object HotReloadActions {

    private const val TAG = "GPM:HotReload"
    private const val INTERNAL_PERMISSION = "com.gpm.virtual.permission.INTERNAL"

    // ── Broad Actions ────────────────────────────────────────
    const val ACTION_FORCE_SYNC = "com.gpm.virtual.FORCE_SYNC"
    const val ACTION_CONFIG_UPDATED = "com.gpm.virtual.CONFIG_UPDATED"
    const val ACTION_HOT_RELOAD = "com.gpm.virtual.HOT_RELOAD"

    // ── Cancel Cantik ───────────────────────────────────────
    const val CANCEL_UPDATE = "com.gpm.virtual.CANCEL_CANTIK_UPDATE"
    const val EXTRA_CANCEL_ENABLED = "cancel_cantik_enabled"

    // ── Sortir Order ────────────────────────────────────────
    const val SORTIR_UPDATE = "com.gpm.virtual.SORTIR_CONFIG_UPDATE"
    const val EXTRA_SHADOW_MODE = "shadow_mode"
    const val EXTRA_EXTEND_TIMER = "extend_timer"
    const val EXTRA_FILTER_ENABLED = "filter_enabled"

    // ── Config Extras Keys ──────────────────────────────────
    const val EXTRA_HOOK_ENABLED = "is_hook_enabled"
    const val EXTRA_SPOOF_ACTIVE = "is_spoof_active"
    const val EXTRA_LATITUDE = "latitude"
    const val EXTRA_LONGITUDE = "longitude"
    const val EXTRA_ACCURACY = "accuracy"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_BEARING = "bearing"
    const val EXTRA_CUSTOM_BEARING = "is_custom_bearing_enabled"
    const val EXTRA_ANTI_MOCK = "anti_mock"
    const val EXTRA_RANDOM_LOCATION = "random_location"
    const val EXTRA_RANDOM_RADIUS = "random_radius"
    const val EXTRA_RANDOM_ACCURACY = "random_accuracy"
    const val EXTRA_VIRTUAL = "use_virtual"
    const val EXTRA_VIRTUAL_RACING = "use_virtual_racing"
    const val EXTRA_USE_RANDOM = "use_random"
    const val EXTRA_CONN_STABILITY = "connection_stability_enabled"
    const val EXTRA_OKHTTP_STABILITY = "okhttp_stability_enabled"
    const val EXTRA_FAST_TCP = "fast_tcp_enabled"
    const val EXTRA_FASTEST_CAPTURE = "fastest_capture_mode_enabled"
    const val EXTRA_GCM_SUPPORT = "gcm_support_enabled"
    const val EXTRA_LOCATION_SHARING = "is_location_sharing_enabled"

    /**
     * Target packages to broadcast to.
     */
    val TARGET_PACKAGES = listOf(
        KnownPackages.GOJEK_PARTNER,
        KnownPackages.GOJEK_APP,
        KnownPackages.GRAB_TAXI_DRIVER,
        KnownPackages.GRAB_DRIVER,
        KnownPackages.GRAB_PASSENGER,
        "com.shopee.driver.id",
        "com.shopee.id",
        "com.maxim.driver",
        "com.taxsee.taxsee",
        "com.indriver.driver",
        "sinet.startup.inDriver",
        KnownPackages.GOOGLE_MAPS,
        KnownPackages.WAZE,
        "hk.gogovan.GoGoVanDriver2",
        "com.lalamove.driver",
        "pt.dostavista",
        "com.deliveree",
        "id.anteraja.satria",
        "com.sicepat.kurir",
        "com.jtexpress"
    )

    /**
     * Dispatch an immediate Hot Reload across all target apps and hooks.
     * Guaranteed fast execution with zero blocking.
     */
    fun dispatchFullHotReload(context: Context, config: GpsConfig? = null, trigger: String = "toggle_change") {
        try {
            val appCtx = context.applicationContext ?: context
            val cfg = config ?: GpsPreferences.getInstance(appCtx).getConfig()
            val now = System.currentTimeMillis()

            // 1. Send dedicated CancelCantik update
            try {
                val cancelIntent = Intent(CANCEL_UPDATE).apply {
                    setPackage(KnownPackages.GOJEK_PARTNER)
                    putExtra(EXTRA_CANCEL_ENABLED, CancelCantikController.isEnabled(appCtx))
                    putExtra("trigger", trigger)
                    putExtra("timestamp", now)
                }
                appCtx.sendBroadcast(cancelIntent)
            } catch (_: Throwable) {}

            // 2. Send dedicated Sortir update
            try {
                val sortirState = SortirSettingsController.state.value
                for (pkg in listOf(KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER)) {
                    val sortirIntent = Intent(SORTIR_UPDATE).apply {
                        setPackage(pkg)
                        putExtra(EXTRA_SHADOW_MODE, sortirState.enabled)
                        putExtra(EXTRA_EXTEND_TIMER, sortirState.extendTimer)
                        putExtra(EXTRA_FILTER_ENABLED, sortirState.filterEnabled)
                        putExtra("trigger", trigger)
                        putExtra("timestamp", now)
                    }
                    appCtx.sendBroadcast(sortirIntent)
                }
            } catch (_: Throwable) {}

            // 3. Build comprehensive config intent
            fun buildSyncIntent(action: String, targetPkg: String? = null): Intent {
                return Intent(action).apply {
                    if (targetPkg != null) setPackage(targetPkg)
                    putExtra(EXTRA_HOOK_ENABLED, cfg.isHookEnabled)
                    putExtra(EXTRA_SPOOF_ACTIVE, cfg.isSpoofActive)
                    putExtra(EXTRA_LATITUDE, cfg.latitude)
                    putExtra(EXTRA_LONGITUDE, cfg.longitude)
                    putExtra("lat", cfg.latitude)
                    putExtra("lng", cfg.longitude)
                    putExtra(EXTRA_ACCURACY, cfg.accuracy)
                    putExtra(EXTRA_SPEED, cfg.walkSpeedMps)
                    putExtra(EXTRA_BEARING, cfg.bearing)
                    putExtra(EXTRA_CUSTOM_BEARING, cfg.isCustomBearingEnabled)
                    putExtra(EXTRA_ANTI_MOCK, cfg.isAntiMockEnabled)
                    putExtra(EXTRA_RANDOM_LOCATION, cfg.isRandomLocationEnabled)
                    putExtra(EXTRA_RANDOM_RADIUS, cfg.randomRadiusMeters)
                    putExtra(EXTRA_RANDOM_ACCURACY, cfg.isRandomAccuracyEnabled)
                    putExtra(EXTRA_VIRTUAL, cfg.useVirtual)
                    putExtra(EXTRA_VIRTUAL_RACING, cfg.useVirtualRacing)
                    putExtra(EXTRA_USE_RANDOM, cfg.useRandom || cfg.isRandomLocationEnabled)
                    putExtra(EXTRA_CONN_STABILITY, cfg.isConnectionStabilityEnabled)
                    putExtra(EXTRA_OKHTTP_STABILITY, cfg.isOkHttpStabilityEnabled)
                    putExtra(EXTRA_FAST_TCP, cfg.isFastTcpEnabled)
                    putExtra(EXTRA_FASTEST_CAPTURE, cfg.isFastestCaptureModeEnabled)
                    putExtra(EXTRA_GCM_SUPPORT, cfg.isGcmSupportEnabled)
                    putExtra(EXTRA_LOCATION_SHARING, cfg.isLocationSharingEnabled)
                    putExtra("location_sharing", cfg.isLocationSharingEnabled)
                    putExtra(EXTRA_CANCEL_ENABLED, CancelCantikController.isEnabled(appCtx))
                    putExtra(EXTRA_SHADOW_MODE, SortirSettingsController.state.value.enabled)
                    putExtra(EXTRA_EXTEND_TIMER, SortirSettingsController.state.value.extendTimer)
                    putExtra(EXTRA_FILTER_ENABLED, SortirSettingsController.state.value.filterEnabled)
                    putExtra("trigger", trigger)
                    putExtra("timestamp", now)
                }
            }

            // 4. Send targeted broadcasts to all target apps
            for (pkg in TARGET_PACKAGES) {
                try {
                    appCtx.sendBroadcast(buildSyncIntent(ACTION_FORCE_SYNC, pkg))
                    appCtx.sendBroadcast(buildSyncIntent(ACTION_CONFIG_UPDATED, pkg))
                } catch (_: Throwable) {}
            }

            // 5. Send general broadcast for dynamically connected hooks
            try {
                appCtx.sendBroadcast(buildSyncIntent(ACTION_FORCE_SYNC), INTERNAL_PERMISSION)
            } catch (_: Throwable) {
                try {
                    appCtx.sendBroadcast(buildSyncIntent(ACTION_FORCE_SYNC))
                } catch (_: Throwable) {}
            }
            try {
                appCtx.sendBroadcast(buildSyncIntent(ACTION_CONFIG_UPDATED))
            } catch (_: Throwable) {}

            Log.d(TAG, "🔥 dispatchFullHotReload complete (trigger=$trigger, targets=${TARGET_PACKAGES.size})")
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchFullHotReload failed: ${t.message}")
        }
    }
}