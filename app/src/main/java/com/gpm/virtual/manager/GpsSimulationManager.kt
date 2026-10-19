package com.gpm.virtual.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.gpm.virtual.data.KnownPackages
import com.gpm.virtual.data.preferences.GpsConfig
import com.gpm.virtual.data.repository.LocationRepository
import com.gpm.virtual.service.MockLocationService
import com.gpm.virtual.util.VtracingEngine
import com.gpm.virtual.xposed.RealisticGpsEngine

class GpsSimulationManager(
    private val context: Context,
    private val injectionPrefs: SharedPreferences,
    private val repository: LocationRepository,
    private val onActiveCoordinatesChanged: (Pair<Double, Double>) -> Unit,
    private val onStatusMessage: (String) -> Unit
) {

    /**
     * ⭐ [FIX-KOMBINASI] Update VT engine — support warm-up.
     *
     * Play ON  → center = spoof config, return VT jittered coords
     * Play OFF → center = real GPS (warm up baseline), return null
     * useRandom standalone tanpa VT juga didukung
     */
    fun updateVtRacing(
        config: GpsConfig,
        realGps: Pair<Double, Double>? = null
    ): Pair<Double, Double>? {
        // Kalau semua VT toggle OFF, tidak perlu warm up
        if (!config.useVirtual && !config.useVirtualRacing && !config.useRandom) {
            return null
        }

        val scope = injectionPrefs.getString("target_scope", "ALL") ?: "ALL"
        val gojekTarget = getGojekTarget()
        val grabTarget = getGrabTarget()

        // ⭐ Center: Play ON = spoof config; Play OFF = real GPS (baseline warm)
        val center: Pair<Double, Double> = if (config.isSpoofActive) {
            when {
                scope == "GOJEK" && gojekTarget != null -> gojekTarget
                scope == "GRAB" && grabTarget != null -> grabTarget
                else -> config.latitude to config.longitude
            }
        } else {
            // Play OFF → warm up engine di real GPS sebagai baseline
            realGps ?: (config.latitude to config.longitude)
        }

        VtracingEngine.setCenter(center.first, center.second)
        VtracingEngine.update(movement = config.useRandom || config.isRandomLocationEnabled)

        // Output hanya dikembalikan saat Play ON (untuk dipakai service)
        return if (config.isSpoofActive) VtracingEngine.getSpoofedPosition() else null
    }

    fun updateRandomMovement(config: GpsConfig): Pair<Double, Double>? {
        if (!config.isSpoofActive) return null
        if (!config.isRandomLocationEnabled && !config.useRandom) return null

        return RealisticGpsEngine.computeRealisticCoordinates(
            baseLat = config.latitude,
            baseLng = config.longitude,
            isRandomEnabled = true
        )
    }

    fun moveLocation(
        deltaLatMeters: Double,
        deltaLngMeters: Double,
        currentConfig: GpsConfig
    ): Pair<Double, Double> {
        val latRad = Math.toRadians(currentConfig.latitude)
        val metersPerDegreeLat = 111132.954 - 559.822 * kotlin.math.cos(2 * latRad)
        val metersPerDegreeLng = 111412.84 * kotlin.math.cos(latRad)

        val newLat = RealisticGpsEngine.normalizeLat(
            currentConfig.latitude + (deltaLatMeters / metersPerDegreeLat)
        )
        val newLng = RealisticGpsEngine.normalizeLng(
            currentConfig.longitude + (deltaLngMeters / metersPerDegreeLng)
        )

        repository.updateConfig { it.copy(latitude = newLat, longitude = newLng) }
        VtracingEngine.setCenter(newLat, newLng)

        // Sync ke per-app target + lifecycle + broadcast
        syncAllTargets(newLat, newLng, currentConfig)

        return newLat to newLng
    }

    /**
     * ⭐ Update lokasi target dari marker.
     * WAJIB dipanggil dari MainViewModel.updateTargetLocation()
     */
    fun updateTargetLocation(
        latitude: Double,
        longitude: Double,
        name: String? = null,
        currentConfig: GpsConfig
    ): Pair<Double, Double> {
        val safeLat = RealisticGpsEngine.normalizeLat(latitude)
        val safeLng = RealisticGpsEngine.normalizeLng(longitude)

        // 1. Update config global
        repository.updateConfig {
            it.copy(
                latitude = safeLat,
                longitude = safeLng,
                locationName = name ?: it.locationName
            )
        }
        VtracingEngine.setCenter(safeLat, safeLng)

        // 2. Sync ke per-app target (Gojek/Grab) + lifecycle + broadcast
        syncAllTargets(safeLat, safeLng, currentConfig)

        return safeLat to safeLng
    }

    fun setCenterCoordinates(lat: Double, lng: Double) {
        VtracingEngine.setCenter(lat, lng)
    }

    // ═════════════════════════════════════════════════════════════════
    // ⭐ Central sync — tulis ke SEMUA storage sekaligus
    // ═════════════════════════════════════════════════════════════════
    private fun syncAllTargets(lat: Double, lng: Double, currentConfig: GpsConfig) {
        // 1. Tulis ke gpm_injection_prefs — sumber per-app override
        try {
            injectionPrefs.edit()
                .putBoolean("gojek_has_target", true)
                .putLong("gojek_lat", lat.toRawBits())
                .putLong("gojek_lng", lng.toRawBits())
                .putBoolean("grab_has_target", true)
                .putLong("grab_lat", lat.toRawBits())
                .putLong("grab_lng", lng.toRawBits())
                .commit()
        } catch (_: Throwable) {}

        // 2. Update SpoofLifecycleManager state (overlay Gojek baca dari sini)
        try {
            val st = SpoofLifecycleManager.getState(context)
            if (st.active) {
                SpoofLifecycleManager.activate(
                    context, st.scope,
                    gojekLat = lat, gojekLng = lng,
                    grabLat = lat, grabLng = lng
                )
            }
        } catch (_: Throwable) {}

        // 3. Broadcast FORCE_REHOOK — hanya kalau Play aktif
        if (!currentConfig.isSpoofActive) return

        val scope = injectionPrefs.getString("target_scope", "ALL") ?: "ALL"

        // Gojek
        if (scope == "GOJEK" || scope == "ALL") {
            try {
                context.sendBroadcast(Intent("com.gpm.virtual.FORCE_REHOOK_GOJEK").apply {
                    setPackage(KnownPackages.GOJEK_PARTNER)
                    putExtra("trigger", "marker_moved")
                    putExtra("override", true)
                    putExtra("latitude", lat)
                    putExtra("longitude", lng)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                })
            } catch (_: Throwable) {}
        }

        // Grab
        if (scope == "GRAB" || scope == "ALL") {
            for (pkg in listOf("com.grabtaxi.driver2", "com.grab.driver")) {
                try {
                    context.sendBroadcast(Intent("com.gpm.virtual.FORCE_REHOOK_GRAB").apply {
                        setPackage(pkg)
                        putExtra("trigger", "marker_moved")
                        putExtra("override", true)
                        putExtra("latitude", lat)
                        putExtra("longitude", lng)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    })
                } catch (_: Throwable) {}
            }
        }

        // 4. Push ke MockLocationService
        try {
            MockLocationService.pushImmediate(context)
            MockLocationService.pingLocationToScope(context)
        } catch (_: Throwable) {}
    }

    private fun getGojekTarget(): Pair<Double, Double>? {
        if (!injectionPrefs.getBoolean("gojek_has_target", false)) return null
        val lat = Double.fromBits(injectionPrefs.getLong("gojek_lat", 0L))
        val lng = Double.fromBits(injectionPrefs.getLong("gojek_lng", 0L))
        if (lat.isNaN() || lng.isNaN()) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private fun getGrabTarget(): Pair<Double, Double>? {
        if (!injectionPrefs.getBoolean("grab_has_target", false)) return null
        val lat = Double.fromBits(injectionPrefs.getLong("grab_lat", 0L))
        val lng = Double.fromBits(injectionPrefs.getLong("grab_lng", 0L))
        if (lat.isNaN() || lng.isNaN()) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }
}