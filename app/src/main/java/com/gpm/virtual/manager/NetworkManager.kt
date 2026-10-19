package com.gpm.virtual.manager

import android.content.Context
import com.gpm.virtual.data.repository.LocationRepository
import com.gpm.virtual.service.MockLocationService
import com.gpm.virtual.util.NetworkStabilityEngine

/**
 * NetworkManager — Mengelola connection stability, GCM support,
 * OkHttp stability, Fast TCP, dan Fastest Capture Mode.
 */
class NetworkManager(
    private val context: Context,
    private val repository: LocationRepository,
    private val onStatusMessage: (String) -> Unit
) {

    fun setConnectionStability(enabled: Boolean) {
        repository.updateConfig { it.copy(isConnectionStabilityEnabled = enabled) }
        val cfg = repository.getConfig()

        if (enabled) {
            NetworkStabilityEngine.acquireHighPerfWifiLock(context)
            if (cfg.isGcmSupportEnabled && cfg.isSpoofActive) {
                NetworkStabilityEngine.startGcmHeartbeatKeeper(context)
            }
        } else {
            NetworkStabilityEngine.stopGcmHeartbeatKeeper()
            NetworkStabilityEngine.releaseWifiLock()
        }

        MockLocationService.pingLocationToScope(context)
        notifyTargetsConfigChanged()
    }

    fun setGcmSupport(enabled: Boolean) {
        repository.updateConfig {
            it.copy(
                isGcmSupportEnabled = enabled,
                isConnectionStabilityEnabled = if (enabled) true else it.isConnectionStabilityEnabled
            )
        }
        val cfg = repository.getConfig()

        if (enabled && cfg.isConnectionStabilityEnabled) {
            NetworkStabilityEngine.acquireHighPerfWifiLock(context)
            NetworkStabilityEngine.startGcmHeartbeatKeeper(context)
        } else if (!enabled) {
            NetworkStabilityEngine.stopGcmHeartbeatKeeper()
        }

        MockLocationService.pingLocationToScope(context)
        notifyTargetsConfigChanged()
    }

    fun setOkHttpStability(enabled: Boolean) {
        repository.updateConfig {
            it.copy(
                isOkHttpStabilityEnabled = enabled,
                isConnectionStabilityEnabled = if (enabled) true else it.isConnectionStabilityEnabled
            )
        }
        MockLocationService.pingLocationToScope(context)
        notifyTargetsConfigChanged()
    }

    fun setFastTcp(enabled: Boolean) {
        repository.updateConfig {
            it.copy(
                isFastTcpEnabled = enabled,
                isConnectionStabilityEnabled = if (enabled) true else it.isConnectionStabilityEnabled
            )
        }
        MockLocationService.pingLocationToScope(context)
        notifyTargetsConfigChanged()
    }

    fun setFastestCaptureMode(enabled: Boolean) {
        repository.updateConfig {
            it.copy(
                isFastestCaptureModeEnabled = enabled,
                isConnectionStabilityEnabled = if (enabled) true else it.isConnectionStabilityEnabled
            )
        }
        if (enabled) {
            NetworkStabilityEngine.acquireHighPerfWifiLock(context)
        }
        MockLocationService.pingLocationToScope(context)
        notifyTargetsConfigChanged()
    }

    private fun notifyTargetsConfigChanged() {
        val targets = listOf(
            "com.gojek.partner",
            "com.grabtaxi.driver2",
            "com.grab.driver",
            "com.google.android.apps.maps"
        )
        for (pkg in targets) {
            try {
                context.sendBroadcast(android.content.Intent("com.gpm.virtual.FORCE_SYNC").apply {
                    setPackage(pkg)
                    putExtra("trigger", "gpm_config_changed")
                })
            } catch (_: Throwable) {}
        }
    }
}