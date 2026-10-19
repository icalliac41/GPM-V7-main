package com.gpm.virtual.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GpsConfig(
    val isHookEnabled: Boolean = true,
    val isSpoofActive: Boolean = false,
    val latitude: Double = -6.1683295,
    val longitude: Double = 106.7588494,
    val locationName: String = "Jakarta Barat",
    val accuracy: Float = 10.0f,
    val isRandomLocationEnabled: Boolean = false,
    val randomRadiusMeters: Float = 8.0f,
    val isRandomAccuracyEnabled: Boolean = true,
    val isAntiMockEnabled: Boolean = true,
    val isJoystickEnabled: Boolean = false,
    val mapType: String = "CUSTOM",
    val themeMode: String = "SYSTEM",
    val walkSpeedMps: Float = 2.5f,
    val isFloatingOverlayEnabled: Boolean = false,
    val isConnectionStabilityEnabled: Boolean = false,
    val isOkHttpStabilityEnabled: Boolean = true,
    val isFastTcpEnabled: Boolean = true,
    val isFastestCaptureModeEnabled: Boolean = true,
    val isGcmSupportEnabled: Boolean = true,
    val useVirtual: Boolean = false,
    val useVirtualRacing: Boolean = false,
    val useRandom: Boolean = false,
    val isCustomBearingEnabled: Boolean = false,
    val bearing: Float = 0.0f,
    val isLocationSharingEnabled: Boolean = true
)

class GpsPreferences(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var isSyncingService = false

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updated = loadConfig()
        _configFlow.value = updated
        com.gpm.virtual.data.provider.LocationConfigProvider.activeConfig = updated
    }

    init { prefs.registerOnSharedPreferenceChangeListener(prefChangeListener) }

    private val _configFlow: MutableStateFlow<GpsConfig> = getOrCreateSharedFlow(loadConfig())
    val configFlow: StateFlow<GpsConfig> = _configFlow.asStateFlow()

    fun getConfig(): GpsConfig = _configFlow.value

    fun isVtRacingActive(): Boolean {
        val cfg = _configFlow.value
        return cfg.useVirtual || cfg.useVirtualRacing
    }

    /**
     * ⭐ [FIX] Service berjalan HANYA kalau Play button ON.
     * VT toggles tidak pernah start service sendiri.
     */
    fun shouldMockServiceRun(): Boolean = _configFlow.value.isSpoofActive

    fun updateConfig(update: (GpsConfig) -> GpsConfig) {
        val oldConfig = _configFlow.value
        val newConfig = update(oldConfig)
        _configFlow.value = newConfig
        com.gpm.virtual.data.provider.LocationConfigProvider.activeConfig = newConfig
        saveConfig(newConfig)

        // ⭐ [FIX] HANYA trigger service sync kalau isSpoofActive berubah.
        // VT toggles perubahan TIDAK trigger start/stop service.
        if (oldConfig.isSpoofActive != newConfig.isSpoofActive) {
            syncMockServiceState(newConfig)
        }

        try {
            val filesDir = appContext.filesDir
            filesDir?.setReadable(true, false)
            filesDir?.setExecutable(true, false)
            filesDir?.parentFile?.setReadable(true, false)
            filesDir?.parentFile?.setExecutable(true, false)
            val file = java.io.File(filesDir, "gpm_config.json")
            if (file.exists()) file.setReadable(true, false)
        } catch (_: Throwable) {}
    }

    /**
     * ⭐ [FIX] Service start HANYA kalau Play button ON.
     */
    private fun syncMockServiceState(config: GpsConfig) {
        if (isSyncingService) return
        isSyncingService = true
        try {
            if (config.isSpoofActive) {
                com.gpm.virtual.service.MockLocationService.start(appContext)
                com.gpm.virtual.service.MockLocationService.pushImmediate(appContext)
            } else {
                com.gpm.virtual.service.MockLocationService.stop(appContext)
            }
        } catch (_: Throwable) {
        } finally {
            isSyncingService = false
        }
    }

    private fun getSafeCoordinate(key: String, strKey: String, defaultValue: Double): Double {
        try {
            if (prefs.contains(strKey)) {
                val strVal = prefs.getString(strKey, null)
                val parsed = strVal?.toDoubleOrNull()
                if (parsed != null) return parsed
            }
            if (prefs.contains(key)) {
                return try {
                    java.lang.Double.longBitsToDouble(
                        prefs.getLong(key, java.lang.Double.doubleToLongBits(defaultValue))
                    )
                } catch (_: ClassCastException) {
                    prefs.getString(key, null)?.toDoubleOrNull() ?: defaultValue
                }
            }
        } catch (_: Throwable) {}
        return defaultValue
    }

    private fun loadConfig(): GpsConfig {
        return GpsConfig(
            isHookEnabled = prefs.getBoolean(KEY_IS_HOOK_ENABLED, true),
            isSpoofActive = prefs.getBoolean(KEY_IS_SPOOF_ACTIVE, false),
            latitude = getSafeCoordinate(KEY_LATITUDE, KEY_LATITUDE_STR, -6.1683295),
            longitude = getSafeCoordinate(KEY_LONGITUDE, KEY_LONGITUDE_STR, 106.7588494),
            locationName = prefs.getString(KEY_LOCATION_NAME, "Jakarta Barat") ?: "Jakarta Barat",
            accuracy = prefs.getFloat(KEY_ACCURACY, 10.0f),
            isRandomLocationEnabled = prefs.getBoolean(KEY_RANDOM_LOCATION, false),
            randomRadiusMeters = prefs.getFloat(KEY_RANDOM_RADIUS, 8.0f),
            isRandomAccuracyEnabled = prefs.getBoolean(KEY_RANDOM_ACCURACY, true),
            isAntiMockEnabled = prefs.getBoolean(KEY_ANTI_MOCK, true),
            isJoystickEnabled = prefs.getBoolean(KEY_JOYSTICK_ENABLED, false),
            mapType = prefs.getString(KEY_MAP_TYPE, "CUSTOM") ?: "CUSTOM",
            themeMode = prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM",
            walkSpeedMps = prefs.getFloat(KEY_WALK_SPEED, 2.5f),
            isFloatingOverlayEnabled = prefs.getBoolean(KEY_FLOATING_OVERLAY_ENABLED, false),
            isConnectionStabilityEnabled = prefs.getBoolean(KEY_CONNECTION_STABILITY_ENABLED, false),
            isOkHttpStabilityEnabled = prefs.getBoolean(KEY_OKHTTP_STABILITY_ENABLED, true),
            isFastTcpEnabled = prefs.getBoolean(KEY_FAST_TCP_ENABLED, true),
            isFastestCaptureModeEnabled = prefs.getBoolean(KEY_FASTEST_CAPTURE_MODE_ENABLED, true),
            isGcmSupportEnabled = prefs.getBoolean(KEY_GCM_SUPPORT_ENABLED, true),
            useVirtual = prefs.getBoolean(KEY_USE_VIRTUAL, false),
            useVirtualRacing = prefs.getBoolean(KEY_USE_VIRTUAL_RACING, false),
            useRandom = prefs.getBoolean(KEY_USE_RANDOM, prefs.getBoolean(KEY_RANDOM_LOCATION, false)),
            isCustomBearingEnabled = prefs.getBoolean(KEY_IS_CUSTOM_BEARING_ENABLED, false),
            bearing = prefs.getFloat(KEY_BEARING, 0.0f),
            isLocationSharingEnabled = prefs.getBoolean(KEY_LOCATION_SHARING_ENABLED, true)
        )
    }

    private fun saveConfig(config: GpsConfig) {
        com.gpm.virtual.data.provider.LocationConfigProvider.activeConfig = config

        prefs.edit().apply {
            putBoolean(KEY_IS_HOOK_ENABLED, config.isHookEnabled)
            putBoolean(KEY_IS_SPOOF_ACTIVE, config.isSpoofActive)
            putBoolean(KEY_USE_VIRTUAL, config.useVirtual)
            putBoolean(KEY_USE_VIRTUAL_RACING, config.useVirtualRacing)
            putBoolean(KEY_USE_RANDOM, config.useRandom || config.isRandomLocationEnabled)
            putLong(KEY_LATITUDE, java.lang.Double.doubleToLongBits(config.latitude))
            putLong(KEY_LONGITUDE, java.lang.Double.doubleToLongBits(config.longitude))
            putString(KEY_LATITUDE_STR, config.latitude.toString())
            putString(KEY_LONGITUDE_STR, config.longitude.toString())
            putString(KEY_LOCATION_NAME, config.locationName)
            putFloat(KEY_ACCURACY, config.accuracy)
            putBoolean(KEY_RANDOM_LOCATION, config.isRandomLocationEnabled || config.useRandom)
            putFloat(KEY_RANDOM_RADIUS, config.randomRadiusMeters)
            putBoolean(KEY_RANDOM_ACCURACY, config.isRandomAccuracyEnabled)
            putBoolean(KEY_ANTI_MOCK, config.isAntiMockEnabled)
            putBoolean(KEY_JOYSTICK_ENABLED, config.isJoystickEnabled)
            putString(KEY_MAP_TYPE, config.mapType)
            putString(KEY_THEME_MODE, config.themeMode)
            putFloat(KEY_WALK_SPEED, config.walkSpeedMps)
            putBoolean(KEY_FLOATING_OVERLAY_ENABLED, config.isFloatingOverlayEnabled)
            putBoolean(KEY_CONNECTION_STABILITY_ENABLED, config.isConnectionStabilityEnabled)
            putBoolean(KEY_OKHTTP_STABILITY_ENABLED, config.isOkHttpStabilityEnabled)
            putBoolean(KEY_FAST_TCP_ENABLED, config.isFastTcpEnabled)
            putBoolean(KEY_FASTEST_CAPTURE_MODE_ENABLED, config.isFastestCaptureModeEnabled)
            putBoolean(KEY_GCM_SUPPORT_ENABLED, config.isGcmSupportEnabled)
            putBoolean(KEY_IS_CUSTOM_BEARING_ENABLED, config.isCustomBearingEnabled)
            putFloat(KEY_BEARING, config.bearing)
            putBoolean(KEY_LOCATION_SHARING_ENABLED, config.isLocationSharingEnabled)
        }.commit()

        // Ensure shared_prefs file permissions are world-readable for LSPosed / Xposed
        try {
            val dataDir = appContext.applicationInfo.dataDir
            val prefsDir = java.io.File(dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
                java.io.File(prefsDir, "${PREFS_NAME}.xml").setReadable(true, false)
            }
        } catch (_: Throwable) {}

        // Synchronously write JSON mirror and ensure readability
        try {
            val json = org.json.JSONObject().apply {
                put("is_hook_enabled", config.isHookEnabled)
                put("is_spoof_active", config.isSpoofActive)
                put("use_virtual", config.useVirtual)
                put("use_virtual_racing", config.useVirtualRacing)
                put("use_random", config.useRandom || config.isRandomLocationEnabled)
                put("latitude", config.latitude)
                put("longitude", config.longitude)
                put("accuracy", config.accuracy.toDouble())
                put("random_location", config.isRandomLocationEnabled)
                put("random_radius", config.randomRadiusMeters.toDouble())
                put("random_accuracy", config.isRandomAccuracyEnabled)
                put("anti_mock", config.isAntiMockEnabled)
                put("connection_stability_enabled", config.isConnectionStabilityEnabled)
                put("okhttp_stability_enabled", config.isOkHttpStabilityEnabled)
                put("fast_tcp_enabled", config.isFastTcpEnabled)
                put("fastest_capture_mode_enabled", config.isFastestCaptureModeEnabled)
                put("gcm_support_enabled", config.isGcmSupportEnabled)
                put("is_custom_bearing_enabled", config.isCustomBearingEnabled)
                put("bearing", config.bearing.toDouble())
                put("speed", config.walkSpeedMps.toDouble())
                put("is_location_sharing_enabled", config.isLocationSharingEnabled)
                put("location_sharing", config.isLocationSharingEnabled)
                put("timestamp", System.currentTimeMillis())
            }
            val filesDir = appContext.filesDir
            filesDir?.parentFile?.setReadable(true, false)
            filesDir?.parentFile?.setExecutable(true, false)
            filesDir?.setReadable(true, false)
            filesDir?.setExecutable(true, false)
            if (filesDir != null) {
                val file = java.io.File(filesDir, "gpm_config.json")
                file.writeText(json.toString())
                file.setReadable(true, false)
            }
        } catch (_: Throwable) {}

        // Notify content provider observers
        try {
            val uri1 = android.net.Uri.parse("content://com.gpm.virtual.provider/config")
            val uri2 = android.net.Uri.parse("content://com.gpm.virtual.mchaji.provider/config")
            appContext.contentResolver.notifyChange(uri1, null)
            appContext.contentResolver.notifyChange(uri2, null)
        } catch (_: Throwable) {}

        // 🔥 Hot Reload: Push config updates immediately to all target apps & hooks
        try {
            com.gpm.virtual.xposed.HotReloadActions.dispatchFullHotReload(appContext, config)
        } catch (_: Throwable) {}
    }

    companion object {
        const val PREFS_NAME = "gpm_virtual_prefs"
        const val KEY_IS_HOOK_ENABLED = "is_hook_enabled"
        const val KEY_IS_SPOOF_ACTIVE = "is_spoof_active"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_LATITUDE_STR = "latitude_str"
        const val KEY_LONGITUDE_STR = "longitude_str"
        const val KEY_USE_VIRTUAL = "use_virtual"
        const val KEY_USE_VIRTUAL_RACING = "use_virtual_racing"
        const val KEY_USE_RANDOM = "use_random"
        const val KEY_LOCATION_NAME = "location_name"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_RANDOM_LOCATION = "random_location"
        const val KEY_RANDOM_RADIUS = "random_radius"
        const val KEY_RANDOM_ACCURACY = "random_accuracy"
        const val KEY_ANTI_MOCK = "anti_mock"
        const val KEY_JOYSTICK_ENABLED = "joystick_enabled"
        const val KEY_MAP_TYPE = "map_type"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_WALK_SPEED = "walk_speed"
        const val KEY_FLOATING_OVERLAY_ENABLED = "floating_overlay_enabled"
        const val KEY_CONNECTION_STABILITY_ENABLED = "connection_stability_enabled"
        const val KEY_OKHTTP_STABILITY_ENABLED = "okhttp_stability_enabled"
        const val KEY_FAST_TCP_ENABLED = "fast_tcp_enabled"
        const val KEY_FASTEST_CAPTURE_MODE_ENABLED = "fastest_capture_mode_enabled"
        const val KEY_GCM_SUPPORT_ENABLED = "gcm_support_enabled"
        const val KEY_IS_CUSTOM_BEARING_ENABLED = "is_custom_bearing_enabled"
        const val KEY_BEARING = "bearing"
        const val KEY_LOCATION_SHARING_ENABLED = "location_sharing_enabled"
        const val KEY_MODULE_ACTIVE_VERIFIED = "module_active_verified"
        const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        const val KEY_LAST_TARGET_PKG = "last_target_pkg"
        const val KEY_LAST_FRAMEWORK = "last_framework"

        @Volatile private var instance: GpsPreferences? = null
        private val sharedConfigFlow = MutableStateFlow<GpsConfig?>(null)

        fun getInstance(context: Context): GpsPreferences {
            return instance ?: synchronized(this) {
                instance ?: GpsPreferences(context.applicationContext ?: context).also { instance = it }
            }
        }

        private fun getOrCreateSharedFlow(initialConfig: GpsConfig): MutableStateFlow<GpsConfig> {
            synchronized(this) {
                if (sharedConfigFlow.value == null) {
                    sharedConfigFlow.value = initialConfig
                }
                @Suppress("UNCHECKED_CAST")
                return sharedConfigFlow as MutableStateFlow<GpsConfig>
            }
        }
    }
}