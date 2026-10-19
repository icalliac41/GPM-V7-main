package com.gpm.virtual.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.gpm.virtual.data.preferences.GpsConfig
import com.gpm.virtual.data.repository.LocationRepository
import com.gpm.virtual.service.MockLocationService
import com.gpm.virtual.xposed.GojekHookEntry
import com.gpm.virtual.xposed.GrabHookEntry

class SpoofManager(
    private val context: Context,
    private val injectionPrefs: SharedPreferences,
    private val repository: LocationRepository,
    private val onStatusMessage: (String) -> Unit
) {

    fun isGojekSpoofActive(): Boolean {
        return try {
            val st = SpoofLifecycleManager.getState(context)
            st.active && (st.scope == "GOJEK" || st.scope == "ALL")
        } catch (_: Throwable) {
            injectionPrefs.getBoolean("gojek_spoof_active", false)
        }
    }

    fun isGrabSpoofActive(): Boolean {
        return try {
            val st = SpoofLifecycleManager.getState(context)
            st.active && (st.scope == "GRAB" || st.scope == "ALL")
        } catch (_: Throwable) {
            injectionPrefs.getBoolean("grab_spoof_active", false)
        }
    }

    fun getInjectionScope(): String =
        injectionPrefs.getString("target_scope", "ALL") ?: "ALL"

    fun getGojekTarget(): Pair<Double, Double>? {
        if (!injectionPrefs.getBoolean("gojek_has_target", false)) return null
        val lat = Double.fromBits(injectionPrefs.getLong("gojek_lat", 0L))
        val lng = Double.fromBits(injectionPrefs.getLong("gojek_lng", 0L))
        return if (lat.isNaN() || lng.isNaN()) null else lat to lng
    }

    fun getGrabTarget(): Pair<Double, Double>? {
        if (!injectionPrefs.getBoolean("grab_has_target", false)) return null
        val lat = Double.fromBits(injectionPrefs.getLong("grab_lat", 0L))
        val lng = Double.fromBits(injectionPrefs.getLong("grab_lng", 0L))
        return if (lat.isNaN() || lng.isNaN()) null else lat to lng
    }

    fun setInjectionScope(scope: String) {
        val normalized = scope.uppercase().let {
            if (it in listOf("GOJEK", "GRAB", "ALL")) it else "ALL"
        }
        injectionPrefs.edit().putString("target_scope", normalized).commit()
        notifyTargetsConfigChanged()
        onStatusMessage("Mode: $normalized")
    }

    fun setGojekTarget(lat: Double?, lng: Double?) {
        if (lat == null || lng == null) {
            injectionPrefs.edit()
                .putBoolean("gojek_has_target", false)
                .remove("gojek_lat").remove("gojek_lng")
                .commit()
        } else {
            injectionPrefs.edit()
                .putBoolean("gojek_has_target", true)
                .putLong("gojek_lat", lat.toRawBits())
                .putLong("gojek_lng", lng.toRawBits())
                .commit()
        }
    }

    fun setGrabTarget(lat: Double?, lng: Double?) {
        if (lat == null || lng == null) {
            injectionPrefs.edit()
                .putBoolean("grab_has_target", false)
                .remove("grab_lat").remove("grab_lng")
                .commit()
        } else {
            injectionPrefs.edit()
                .putBoolean("grab_has_target", true)
                .putLong("grab_lat", lat.toRawBits())
                .putLong("grab_lng", lng.toRawBits())
                .commit()
        }
    }

    // ⭐ Toggle Gojek — pakai currentConfig coords saat Play ON
    fun toggleGojekSpoof(currentConfig: GpsConfig): Boolean {
        val next = !isGojekSpoofActive()

        if (next) {
            val coord = currentConfig.latitude to currentConfig.longitude
            setGojekTarget(coord.first, coord.second)
            val grabCoord = getGrabTarget() ?: coord
            SpoofLifecycleManager.activate(
                context = context,
                scope = if (isGrabSpoofActive()) "ALL" else "GOJEK",
                gojekLat = coord.first, gojekLng = coord.second,
                grabLat = grabCoord.first, grabLng = grabCoord.second
            )
            injectGojekHooks(coord)
            syncGlobalSpoofState(true)
            onStatusMessage("Spoof Gojek ON di %.5f, %.5f".format(coord.first, coord.second))
        } else {
            val grabStillOn = isGrabSpoofActive()
            if (grabStillOn) {
                injectionPrefs.edit()
                    .putBoolean("gojek_spoof_active", false)
                    .putBoolean("gojek_has_target", false)
                    .remove("gojek_lat").remove("gojek_lng")
                    .commit()

                val grabCoord = getGrabTarget() ?: (currentConfig.latitude to currentConfig.longitude)
                SpoofLifecycleManager.activate(
                    context, "GRAB",
                    gojekLat = 0.0, gojekLng = 0.0,
                    grabLat = grabCoord.first, grabLng = grabCoord.second
                )
                syncGlobalSpoofState(true)
                onStatusMessage("Spoof Gojek OFF (Grab masih aktif)")
            } else {
                SpoofLifecycleManager.deactivate(context)
                syncGlobalSpoofState(false)
                onStatusMessage("Spoof Gojek dinonaktifkan")
            }
        }
        return next
    }

    fun toggleGrabSpoof(currentConfig: GpsConfig): Boolean {
        val next = !isGrabSpoofActive()

        if (next) {
            val coord = currentConfig.latitude to currentConfig.longitude
            setGrabTarget(coord.first, coord.second)
            val gojekCoord = getGojekTarget() ?: coord
            SpoofLifecycleManager.activate(
                context, if (isGojekSpoofActive()) "ALL" else "GRAB",
                gojekLat = gojekCoord.first, gojekLng = gojekCoord.second,
                grabLat = coord.first, grabLng = coord.second
            )
            injectGrabHooks(coord)
            syncGlobalSpoofState(true)
            onStatusMessage("Spoof Grab ON di %.5f, %.5f".format(coord.first, coord.second))
        } else {
            val gojekStillOn = isGojekSpoofActive()
            if (gojekStillOn) {
                injectionPrefs.edit()
                    .putBoolean("grab_spoof_active", false)
                    .putBoolean("grab_has_target", false)
                    .remove("grab_lat").remove("grab_lng")
                    .commit()

                val gojekCoord = getGojekTarget() ?: (currentConfig.latitude to currentConfig.longitude)
                SpoofLifecycleManager.activate(
                    context, "GOJEK",
                    gojekLat = gojekCoord.first, gojekLng = gojekCoord.second,
                    grabLat = 0.0, grabLng = 0.0
                )
                syncGlobalSpoofState(true)
                onStatusMessage("Spoof Grab OFF (Gojek masih aktif)")
            } else {
                SpoofLifecycleManager.deactivate(context)
                syncGlobalSpoofState(false)
                onStatusMessage("Spoof Grab dinonaktifkan")
            }
        }
        return next
    }

    fun toggleAllSpoof(currentConfig: GpsConfig): Boolean {
        val scope = getInjectionScope()

        return when (scope) {
            "GOJEK" -> toggleGojekSpoof(currentConfig)
            "GRAB" -> toggleGrabSpoof(currentConfig)
            else -> {
                val next = !(isGojekSpoofActive() && isGrabSpoofActive())

                if (next) {
                    val coord = currentConfig.latitude to currentConfig.longitude
                    setGojekTarget(coord.first, coord.second)
                    setGrabTarget(coord.first, coord.second)
                    SpoofLifecycleManager.activate(
                        context, "ALL",
                        gojekLat = coord.first, gojekLng = coord.second,
                        grabLat = coord.first, grabLng = coord.second
                    )
                    injectGojekHooks(coord)
                    injectGrabHooks(coord)
                    syncGlobalSpoofState(true)
                    onStatusMessage("Spoof Gojek + Grab aktif")
                } else {
                    SpoofLifecycleManager.deactivate(context)
                    syncGlobalSpoofState(false)
                    onStatusMessage("Spoof keduanya dinonaktifkan")
                }

                next
            }
        }
    }

    fun sendSpoofBroadcast(
        pkg: String, active: Boolean, action: String,
        lat: Double? = null, lng: Double? = null
    ) {
        try {
            val anyActive = isGojekSpoofActive() || isGrabSpoofActive()
            context.getSharedPreferences("gpm_virtual_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_spoof_active", anyActive)
                .putBoolean("is_hook_enabled", true)
                .commit()

            val intent = Intent(action).apply {
                setPackage(pkg)
                putExtra("active", active)
                putExtra("trigger", "gpm_spoof_manager")

                if (active && lat != null && lng != null) {
                    putExtra("override", true)
                    putExtra("latitude", lat)
                    putExtra("longitude", lng)
                } else if (!active) {
                    putExtra("override", false)
                    putExtra("force_off", true)
                }
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            android.util.Log.w("SpoofManager", "sendSpoofBroadcast failed: ${t.message}")
        }
    }

    fun injectGojekHooks(coord: Pair<Double, Double>) {
        try {
            val intent = Intent("com.gpm.virtual.FORCE_REHOOK_GOJEK").apply {
                setPackage(GojekHookEntry.TARGET_PACKAGE)
                putExtra("trigger", "gpm_fab")
                putExtra("override", true)
                putExtra("latitude", coord.first)
                putExtra("longitude", coord.second)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
            onStatusMessage("Injeksi lokasi Gojek dikirim (%.5f, %.5f)".format(coord.first, coord.second))
        } catch (t: Throwable) {
            onStatusMessage("Gagal kirim sinyal Gojek: ${t.message}")
        }
    }

    fun injectGrabHooks(coord: Pair<Double, Double>) {
        val targets = listOf("com.grabtaxi.driver2", "com.grab.driver")
        var sent = 0
        for (pkg in targets) {
            try {
                val intent = Intent("com.gpm.virtual.FORCE_REHOOK_GRAB").apply {
                    setPackage(pkg)
                    putExtra("trigger", "gpm_fab")
                    putExtra("override", true)
                    putExtra("latitude", coord.first)
                    putExtra("longitude", coord.second)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.sendBroadcast(intent)
                sent++
            } catch (_: Throwable) {}
        }
        onStatusMessage("Injeksi lokasi Grab dikirim ke $sent target")
    }

    fun notifyTargetsConfigChanged() {
        val targets = listOf(
            GojekHookEntry.TARGET_PACKAGE,
            "com.grabtaxi.driver2",
            "com.grab.driver",
            "com.google.android.apps.maps"
        )
        for (pkg in targets) {
            try {
                context.sendBroadcast(Intent("com.gpm.virtual.FORCE_SYNC").apply {
                    setPackage(pkg)
                    putExtra("trigger", "gpm_config_changed")
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
            } catch (_: Throwable) {}
        }
    }

    fun clearPerAppOverrides() {
        setGojekTarget(null, null)
        setGrabTarget(null, null)

        try {
            context.sendBroadcast(Intent("com.gpm.virtual.CLEAR_OVERRIDE").apply {
                setPackage(GojekHookEntry.TARGET_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        } catch (_: Throwable) {}

        for (pkg in listOf("com.grabtaxi.driver2", "com.grab.driver")) {
            try {
                context.sendBroadcast(Intent("com.gpm.virtual.CLEAR_OVERRIDE").apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
            } catch (_: Throwable) {}
        }

        onStatusMessage("Override per-app dibersihkan")
    }

    fun restoreSpoofStateToTargets(currentConfig: GpsConfig) {
        try {
            val st = SpoofLifecycleManager.getState(context)
            if (st.active) {
                SpoofLifecycleManager.activate(
                    context, st.scope,
                    st.gojekLat, st.gojekLng,
                    st.grabLat, st.grabLng
                )
            } else {
                SpoofLifecycleManager.deactivate(context)
            }
        } catch (_: Throwable) {}
    }

    /**
     * ⭐ [FIX-FINAL] Service start/stop HANYA berdasarkan Play state (anyActive).
     * VT toggles tidak pernah trigger service.
     */
    private fun syncGlobalSpoofState(anyActive: Boolean) {
        try {
            context.getSharedPreferences("gpm_virtual_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_spoof_active", anyActive)
                .putBoolean("is_hook_enabled", true)
                .commit()
        } catch (_: Throwable) {}

        try {
            repository.updateConfig { it.copy(isSpoofActive = anyActive) }
        } catch (_: Throwable) {}

        if (anyActive) {
            MockLocationService.start(context)
            MockLocationService.pushImmediate(context)
        } else {
            MockLocationService.stop(context)
        }
    }
}