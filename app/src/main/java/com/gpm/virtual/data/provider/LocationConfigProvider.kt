package com.gpm.virtual.data.provider

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.gpm.virtual.data.KnownPackages
import com.gpm.virtual.data.preferences.GpsConfig
import com.gpm.virtual.data.preferences.GpsPreferences
import java.util.concurrent.ConcurrentHashMap

class LocationConfigProvider : ContentProvider() {

    private fun getPrefs(): SharedPreferences? {
        return context?.getSharedPreferences(GpsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(): Boolean {
        sApplicationContext = context?.applicationContext
        val prefs = getPrefs()
        val savedHeartbeat = prefs?.getLong(GpsPreferences.KEY_LAST_HEARTBEAT, 0L) ?: 0L
        if (savedHeartbeat > 0) {
            lastHeartbeatTime = savedHeartbeat
            lastPingedPackage = prefs?.getString(GpsPreferences.KEY_LAST_TARGET_PKG, null)
        }

        // Handshake receiver dengan whitelist check
        try {
            val ctx = context
            if (ctx != null) {
                val filter = IntentFilter(ACTION_TARGET_APP_CONNECTED)
                val handshakeReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val pkg = intent?.getStringExtra("package_name") ?: return
                        if (!isCallerAllowed(pkg)) {
                            Log.w(TAG, "Handshake blocked: $pkg")
                            return
                        }
                        val fw = intent.getStringExtra("framework") ?: "LSPosed API 102"
                        val now = System.currentTimeMillis()

                        // Debounce per package
                        val previousPing = lastHandshakeTimeMap[pkg] ?: 0L
                        if (now - previousPing < 5000L) return
                        lastHandshakeTimeMap[pkg] = now

                        lastHeartbeatTime = now
                        lastPingedPackage = pkg
                        totalPingCount++
                        com.gpm.virtual.util.XposedStatus.isHookedDirectly = true

                        getPrefs()?.edit()?.apply {
                            putLong(GpsPreferences.KEY_LAST_HEARTBEAT, now)
                            putBoolean(GpsPreferences.KEY_MODULE_ACTIVE_VERIFIED, true)
                            putString(GpsPreferences.KEY_LAST_TARGET_PKG, pkg)
                            putString(GpsPreferences.KEY_LAST_FRAMEWORK, fw)
                        }?.apply()
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(handshakeReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    ctx.registerReceiver(handshakeReceiver, filter)
                }
            }
        } catch (_: Throwable) {
        }

        return true
    }

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        lastHeartbeatTime = System.currentTimeMillis()
        return handleCall(method, arg, extras)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        lastHeartbeatTime = System.currentTimeMillis()
        return handleCall(method, arg, extras)
    }

    private fun handleCall(method: String, arg: String?, extras: Bundle?): Bundle {
        val bundle = Bundle()
        val prefs = getPrefs()
        val injPrefs = context?.getSharedPreferences("gpm_injection_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        lastHeartbeatTime = now

        val callingPkg = try { callingPackage } catch (_: Throwable) { null }
            ?: extras?.getString("pkg") ?: arg

        // ✅ FIX: Validasi caller (whitelist)
        // Catatan: com.gpm.virtual sendiri pasti boleh, target app di whitelist boleh,
        // app lain di-block.
        if (!isCallerAllowed(callingPkg)) {
            bundle.putBoolean("error", true)
            bundle.putString("error_msg", "Caller not allowed: $callingPkg")
            Log.w(TAG, "Blocked caller: $callingPkg (method=$method)")
            return bundle
        }

        val isTargetApp = !callingPkg.isNullOrEmpty() && callingPkg != KnownPackages.GPM_MODULE

        if (isTargetApp || method == METHOD_PING) {
            if (!callingPkg.isNullOrEmpty()) {
                lastPingedPackage = callingPkg
                totalPingCount++
            }
            prefs?.edit()?.apply {
                putLong(GpsPreferences.KEY_LAST_HEARTBEAT, now)
                putBoolean(GpsPreferences.KEY_MODULE_ACTIVE_VERIFIED, true)
                if (!callingPkg.isNullOrEmpty() && callingPkg != KnownPackages.GPM_MODULE) {
                    putString(GpsPreferences.KEY_LAST_TARGET_PKG, callingPkg)
                }
                extras?.getString("framework")?.let { fw ->
                    putString(GpsPreferences.KEY_LAST_FRAMEWORK, fw)
                }
            }?.apply()
        }

        when (method) {
            METHOD_GET_LOCATION_SHARING, "location_sharing" -> {
                val cfg = activeConfig
                var lat = cfg?.latitude ?: java.lang.Double.longBitsToDouble(
                    prefs?.getLong(GpsPreferences.KEY_LATITUDE, java.lang.Double.doubleToLongBits(-6.1683295))
                        ?: java.lang.Double.doubleToLongBits(-6.1683295)
                )
                var lng = cfg?.longitude ?: java.lang.Double.longBitsToDouble(
                    prefs?.getLong(GpsPreferences.KEY_LONGITUDE, java.lang.Double.doubleToLongBits(106.7588494))
                        ?: java.lang.Double.doubleToLongBits(106.7588494)
                )
                if (callingPkg == KnownPackages.GOJEK_PARTNER &&
                    injPrefs?.getBoolean("gojek_has_target", false) == true) {
                    val gLat = java.lang.Double.longBitsToDouble(injPrefs.getLong("gojek_lat", 0L))
                    val gLng = java.lang.Double.longBitsToDouble(injPrefs.getLong("gojek_lng", 0L))
                    if (gLat in -90.0..90.0 && gLng in -180.0..180.0 && (gLat != 0.0 || gLng != 0.0)) {
                        lat = gLat
                        lng = gLng
                    }
                } else if ((callingPkg == KnownPackages.GRAB_TAXI_DRIVER || callingPkg == KnownPackages.GRAB_DRIVER) &&
                    injPrefs?.getBoolean("grab_has_target", false) == true) {
                    val rLat = java.lang.Double.longBitsToDouble(injPrefs.getLong("grab_lat", 0L))
                    val rLng = java.lang.Double.longBitsToDouble(injPrefs.getLong("grab_lng", 0L))
                    if (rLat in -90.0..90.0 && rLng in -180.0..180.0 && (rLat != 0.0 || rLng != 0.0)) {
                        lat = rLat
                        lng = rLng
                    }
                }
                val locSharing = cfg?.isLocationSharingEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_LOCATION_SHARING_ENABLED, true) ?: true)
                val speed = cfg?.walkSpeedMps ?: 1.2f
                val bearing = prefs?.getFloat("bearing", 45.0f) ?: 45.0f
                val acc = cfg?.accuracy ?: 10.0f
                bundle.putBoolean("is_location_sharing_enabled", locSharing)
                bundle.putBoolean("location_sharing", locSharing)
                bundle.putBoolean(GpsPreferences.KEY_LOCATION_SHARING_ENABLED, locSharing)
                bundle.putDouble("latitude", lat)
                bundle.putDouble("longitude", lng)
                bundle.putDouble("lat", lat)
                bundle.putDouble("lng", lng)
                bundle.putDouble("location_sharing_lat", lat)
                bundle.putDouble("location_sharing_lng", lng)
                bundle.putFloat("speed", speed)
                bundle.putFloat("bearing", bearing)
                bundle.putFloat("accuracy", acc)
                bundle.putLong("timestamp", now)
                bundle.putString("provider", "gps")
            }
            METHOD_PING -> {
                bundle.putBoolean("active", true)
                bundle.putLong("time", lastHeartbeatTime)
                bundle.putLong("pingTimestamp", lastHeartbeatTime)
                bundle.putLong("ping_timestamp", lastHeartbeatTime)
                bundle.putString("last_target", lastPingedPackage)
            }
            METHOD_GET_CONFIG -> {
                val cfg = activeConfig
                val isHook = cfg?.isHookEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_IS_HOOK_ENABLED, true) ?: true)
                var isSpoof = cfg?.isSpoofActive
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_IS_SPOOF_ACTIVE, false) ?: false)
                var lat = cfg?.latitude ?: java.lang.Double.longBitsToDouble(
                    prefs?.getLong(GpsPreferences.KEY_LATITUDE, java.lang.Double.doubleToLongBits(-6.1683295))
                        ?: java.lang.Double.doubleToLongBits(-6.1683295)
                )
                var lng = cfg?.longitude ?: java.lang.Double.longBitsToDouble(
                    prefs?.getLong(GpsPreferences.KEY_LONGITUDE, java.lang.Double.doubleToLongBits(106.7588494))
                        ?: java.lang.Double.doubleToLongBits(106.7588494)
                )
                if (callingPkg == KnownPackages.GOJEK_PARTNER) {
                    if (injPrefs?.getBoolean("gojek_has_target", false) == true) {
                        val gLat = java.lang.Double.longBitsToDouble(injPrefs.getLong("gojek_lat", 0L))
                        val gLng = java.lang.Double.longBitsToDouble(injPrefs.getLong("gojek_lng", 0L))
                        if (gLat in -90.0..90.0 && gLng in -180.0..180.0 && (gLat != 0.0 || gLng != 0.0)) {
                            lat = gLat
                            lng = gLng
                        }
                    }
                    if (injPrefs?.contains("gojek_spoof_active") == true) {
                        isSpoof = injPrefs.getBoolean("gojek_spoof_active", isSpoof)
                    }
                } else if (callingPkg == KnownPackages.GRAB_TAXI_DRIVER || callingPkg == KnownPackages.GRAB_DRIVER) {
                    if (injPrefs?.getBoolean("grab_has_target", false) == true) {
                        val rLat = java.lang.Double.longBitsToDouble(injPrefs.getLong("grab_lat", 0L))
                        val rLng = java.lang.Double.longBitsToDouble(injPrefs.getLong("grab_lng", 0L))
                        if (rLat in -90.0..90.0 && rLng in -180.0..180.0 && (rLat != 0.0 || rLng != 0.0)) {
                            lat = rLat
                            lng = rLng
                        }
                    }
                    if (injPrefs?.contains("grab_spoof_active") == true) {
                        isSpoof = injPrefs.getBoolean("grab_spoof_active", isSpoof)
                    }
                }
                val locName = cfg?.locationName
                    ?: (prefs?.getString(GpsPreferences.KEY_LOCATION_NAME, "Jakarta Barat") ?: "Jakarta Barat")
                val acc = cfg?.accuracy ?: (prefs?.getFloat(GpsPreferences.KEY_ACCURACY, 10.0f) ?: 10.0f)
                val randLoc = cfg?.isRandomLocationEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_RANDOM_LOCATION, false) ?: false)
                val randRad = cfg?.randomRadiusMeters
                    ?: (prefs?.getFloat(GpsPreferences.KEY_RANDOM_RADIUS, 8.0f) ?: 8.0f)
                val randAcc = cfg?.isRandomAccuracyEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_RANDOM_ACCURACY, true) ?: true)
                val antiMock = cfg?.isAntiMockEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_ANTI_MOCK, true) ?: true)
                val connStab = cfg?.isConnectionStabilityEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_CONNECTION_STABILITY_ENABLED, false) ?: false)
                val okHttpStab = cfg?.isOkHttpStabilityEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_OKHTTP_STABILITY_ENABLED, true) ?: true)
                val fastTcp = cfg?.isFastTcpEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_FAST_TCP_ENABLED, true) ?: true)
                val fastCapture = cfg?.isFastestCaptureModeEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_FASTEST_CAPTURE_MODE_ENABLED, true) ?: true)
                val gcmSupport = cfg?.isGcmSupportEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_GCM_SUPPORT_ENABLED, true) ?: true)
                val useVirtual = cfg?.useVirtual
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_USE_VIRTUAL, false) ?: false)
                val useVirtualRacing = cfg?.useVirtualRacing
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_USE_VIRTUAL_RACING, false) ?: false)
                val useRandom = cfg?.useRandom
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_USE_RANDOM, randLoc) ?: randLoc)
                val rawSpeed = cfg?.walkSpeedMps ?: (prefs?.getFloat("speed", 1.2f) ?: 1.2f)
                val speed = if (isSpoof) {
                    com.gpm.virtual.xposed.RealisticGpsEngine.computeRealisticSpeed(rawSpeed).coerceIn(0.0f, 1.5f)
                } else rawSpeed
                val bearing = prefs?.getFloat("bearing", 45.0f) ?: 45.0f
                val pingTs = if (lastHeartbeatTime > 0L) lastHeartbeatTime else now
                val locTs = now
                val accSpeed = if (isSpoof) {
                    com.gpm.virtual.xposed.RealisticGpsEngine.computeAccelerometerSpeed(speed).coerceIn(0.0f, 1.5f)
                } else (speed + 0.05f)
                val accBearing = bearing

                bundle.putBoolean(GpsPreferences.KEY_IS_HOOK_ENABLED, isHook)
                bundle.putBoolean("is_hook_enabled", isHook)
                bundle.putBoolean(GpsPreferences.KEY_IS_SPOOF_ACTIVE, isSpoof)
                bundle.putBoolean("is_spoof_active", isSpoof)
                bundle.putBoolean("use_virtual", useVirtual)
                bundle.putBoolean("use_virtual_racing", useVirtualRacing)
                bundle.putBoolean("use_random", useRandom)
                bundle.putDouble("latitude", lat)
                bundle.putDouble("longitude", lng)
                bundle.putDouble(GpsPreferences.KEY_LATITUDE, lat)
                bundle.putDouble(GpsPreferences.KEY_LONGITUDE, lng)
                bundle.putString(GpsPreferences.KEY_LOCATION_NAME, locName)
                bundle.putFloat(GpsPreferences.KEY_ACCURACY, acc)
                bundle.putBoolean(GpsPreferences.KEY_RANDOM_LOCATION, randLoc)
                bundle.putFloat(GpsPreferences.KEY_RANDOM_RADIUS, randRad)
                bundle.putBoolean(GpsPreferences.KEY_RANDOM_ACCURACY, randAcc)
                bundle.putBoolean(GpsPreferences.KEY_ANTI_MOCK, antiMock)
                bundle.putBoolean(GpsPreferences.KEY_CONNECTION_STABILITY_ENABLED, connStab)
                bundle.putBoolean("connection_stability_enabled", connStab)
                bundle.putBoolean(GpsPreferences.KEY_OKHTTP_STABILITY_ENABLED, okHttpStab)
                bundle.putBoolean("okhttp_stability_enabled", okHttpStab)
                bundle.putBoolean(GpsPreferences.KEY_FAST_TCP_ENABLED, fastTcp)
                bundle.putBoolean("fast_tcp_enabled", fastTcp)
                bundle.putBoolean(GpsPreferences.KEY_FASTEST_CAPTURE_MODE_ENABLED, fastCapture)
                bundle.putBoolean("fastest_capture_mode_enabled", fastCapture)
                bundle.putBoolean(GpsPreferences.KEY_GCM_SUPPORT_ENABLED, gcmSupport)
                bundle.putBoolean("gcm_support_enabled", gcmSupport)
                val locSharing = cfg?.isLocationSharingEnabled
                    ?: (prefs?.getBoolean(GpsPreferences.KEY_LOCATION_SHARING_ENABLED, true) ?: true)
                bundle.putBoolean("is_location_sharing_enabled", locSharing)
                bundle.putBoolean("location_sharing", locSharing)
                bundle.putBoolean(GpsPreferences.KEY_LOCATION_SHARING_ENABLED, locSharing)
                bundle.putDouble("location_sharing_lat", lat)
                bundle.putDouble("location_sharing_lng", lng)
                bundle.putFloat("speed", speed)
                bundle.putFloat("bearing", bearing)
                bundle.putLong("pingTimestamp", pingTs)
                bundle.putLong("ping_timestamp", pingTs)
                bundle.putLong("locationTimestamp", locTs)
                bundle.putLong("location_timestamp", locTs)
                bundle.putFloat("accelerometerSpeed", accSpeed)
                bundle.putFloat("accelerometer_speed", accSpeed)
                bundle.putFloat("accelerometerBearing", accBearing)
                bundle.putFloat("accelerometer_bearing", accBearing)
            }
            METHOD_UPDATE_LOCATION -> {
                extras?.let {
                    val lat = it.getDouble("lat")
                    val lng = it.getDouble("lng")
                    val current = activeConfig
                    if (current != null) {
                        activeConfig = current.copy(latitude = lat, longitude = lng)
                    }
                    prefs?.edit()?.apply {
                        putLong(GpsPreferences.KEY_LATITUDE, java.lang.Double.doubleToLongBits(lat))
                        putLong(GpsPreferences.KEY_LONGITUDE, java.lang.Double.doubleToLongBits(lng))
                    }?.commit()
                }
                bundle.putBoolean("success", true)
            }
        }
        return bundle
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val now = System.currentTimeMillis()
        lastHeartbeatTime = now
        val cfg = activeConfig
        val prefs = getPrefs()
        val cursor = MatrixCursor(arrayOf(
            "is_hook", "is_hook_enabled", "is_spoof_active", "latitude", "longitude", "accuracy", "anti_mock",
            "speed", "bearing", "ping_timestamp", "location_timestamp",
            "accelerometer_speed", "accelerometer_bearing",
            "connection_stability_enabled", "okhttp_stability_enabled",
            "fast_tcp_enabled", "fastest_capture_mode_enabled", "gcm_support_enabled",
            "use_virtual", "use_virtual_racing", "use_random",
            "is_location_sharing_enabled", "location_sharing", "location_sharing_lat", "location_sharing_lng"
        ))
        val isHook = if (cfg != null) {
            if (cfg.isHookEnabled) 1 else 0
        } else {
            if (prefs?.getBoolean(GpsPreferences.KEY_IS_HOOK_ENABLED, true) == true) 1 else 0
        }
        val isSpoof = if (cfg != null) {
            if (cfg.isSpoofActive) 1 else 0
        } else {
            if (prefs?.getBoolean(GpsPreferences.KEY_IS_SPOOF_ACTIVE, false) == true) 1 else 0
        }
        val lat = cfg?.latitude ?: java.lang.Double.longBitsToDouble(
            prefs?.getLong(GpsPreferences.KEY_LATITUDE, java.lang.Double.doubleToLongBits(-6.1683295))
                ?: java.lang.Double.doubleToLongBits(-6.1683295)
        )
        val lng = cfg?.longitude ?: java.lang.Double.longBitsToDouble(
            prefs?.getLong(GpsPreferences.KEY_LONGITUDE, java.lang.Double.doubleToLongBits(106.7588494))
                ?: java.lang.Double.doubleToLongBits(106.7588494)
        )
        val acc = cfg?.accuracy ?: (prefs?.getFloat(GpsPreferences.KEY_ACCURACY, 10.0f) ?: 10.0f)
        val antiMock = if (cfg != null) {
            if (cfg.isAntiMockEnabled) 1 else 0
        } else {
            if (prefs?.getBoolean(GpsPreferences.KEY_ANTI_MOCK, true) == true) 1 else 0
        }
        val connStab = if (cfg != null) {
            if (cfg.isConnectionStabilityEnabled) 1 else 0
        } else {
            if (prefs?.getBoolean(GpsPreferences.KEY_CONNECTION_STABILITY_ENABLED, false) == true) 1 else 0
        }
        val okHttpStab = if (cfg != null) {
            if (cfg.isOkHttpStabilityEnabled) 1 else 0
        } else {
            if (prefs?.getBoolean(GpsPreferences.KEY_OKHTTP_STABILITY_ENABLED, true) == true) 1 else 0
        }
        val fastTcp = if (cfg != null) {
            if (cfg.isFastTcpEnabled) 1 else 0
        } else {
            if (prefs?.getBoolean(GpsPreferences.KEY_FAST_TCP_ENABLED, true) == true) 1 else 0
        }
        val fastCapture = if (cfg != null) {
            if (cfg.isFastestCaptureModeEnabled) 1 else 0
        } else {
            if (prefs?.getBoolean(GpsPreferences.KEY_FASTEST_CAPTURE_MODE_ENABLED, true) == true) 1 else 0
        }
        val gcmSupport = if (cfg != null) {
            if (cfg.isGcmSupportEnabled) 1 else 0
        } else {
            if (prefs?.getBoolean(GpsPreferences.KEY_GCM_SUPPORT_ENABLED, true) == true) 1 else 0
        }
        val rawSpeed = cfg?.walkSpeedMps ?: (prefs?.getFloat("speed", 1.2f) ?: 1.2f)
        val speed = if (isSpoof == 1) {
            com.gpm.virtual.xposed.RealisticGpsEngine.computeRealisticSpeed(rawSpeed).coerceIn(0.0f, 1.5f)
        } else rawSpeed
        val bearing = prefs?.getFloat("bearing", 45.0f) ?: 45.0f
        val pingTs = if (lastHeartbeatTime > 0L) lastHeartbeatTime else now
        val locTs = now
        val accSpeed = if (isSpoof == 1) {
            com.gpm.virtual.xposed.RealisticGpsEngine.computeAccelerometerSpeed(speed).coerceIn(0.0f, 1.5f)
        } else (speed + 0.05f)
        val accBearing = bearing

        val useVirtualVal = if (cfg?.useVirtual == true
            || (cfg == null && prefs?.getBoolean(GpsPreferences.KEY_USE_VIRTUAL, false) == true)) 1 else 0
        val useVirtualRacingVal = if (cfg?.useVirtualRacing == true
            || (cfg == null && prefs?.getBoolean(GpsPreferences.KEY_USE_VIRTUAL_RACING, false) == true)) 1 else 0
        val useRandomVal = if (cfg?.useRandom == true
            || (cfg == null && prefs?.getBoolean(GpsPreferences.KEY_USE_RANDOM, false) == true)) 1 else 0
        val locSharing = cfg?.isLocationSharingEnabled
            ?: (prefs?.getBoolean(GpsPreferences.KEY_LOCATION_SHARING_ENABLED, true) ?: true)
        val locSharingVal = if (locSharing) 1 else 0

        cursor.addRow(arrayOf<Any>(
            isHook, isHook, isSpoof, lat, lng, acc, antiMock, speed, bearing, pingTs, locTs, accSpeed, accBearing,
            connStab, okHttpStab, fastTcp, fastCapture, gcmSupport,
            useVirtualVal, useVirtualRacingVal, useRandomVal,
            locSharingVal, locSharingVal, lat, lng
        ))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "GPM-Provider"

        const val AUTHORITY = "com.gpm.virtual.provider"
        const val METHOD_GET_CONFIG = "getConfig"
        const val METHOD_GET_LOCATION_SHARING = "getLocationSharing"
        const val METHOD_UPDATE_LOCATION = "updateLocation"
        const val METHOD_PING = "ping"
        const val ACTION_TARGET_APP_CONNECTED = "com.gpm.virtual.TARGET_APP_CONNECTED"
        const val ACTION_LOCATION_SHARING = "com.gpm.virtual.ACTION_LOCATION_SHARING"

        val BROADCAST_TARGET_PACKAGES = listOf(
            KnownPackages.GOOGLE_MAPS,
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
            KnownPackages.WAZE,
            "hk.gogovan.GoGoVanDriver2",
            "com.lalamove.driver",
            "pt.dostavista",
            "com.deliveree",
            "id.anteraja.satria",
            "com.sicepat.kurir",
            "com.jtexpress",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.life360.android.safetym"
        )

        // ✅ FIX: Whitelist caller yang benar-benar dipakai
        private val ALLOWED_CALLERS = setOf(
            // Modul sendiri
            KnownPackages.GPM_MODULE,
            KnownPackages.GPM_MODULE_ALT,
            // Gojek
            KnownPackages.GOJEK_PARTNER,
            KnownPackages.GOJEK_APP,
            // Grab
            KnownPackages.GRAB_TAXI_DRIVER,
            KnownPackages.GRAB_DRIVER,
            KnownPackages.GRAB_PASSENGER,
            // Shopee
            "com.shopee.driver.id",
            // Maxim, InDriver
            "com.maxim.driver",
            "com.indriver.driver",
            "sinet.startup.inDriver",
            // Maps & Waze
            KnownPackages.GOOGLE_MAPS,
            KnownPackages.WAZE,
            // Lalamove, Sicepat, Anteraja
            "com.lalamove.driver",
            "com.sicepat.kurir",
            "id.anteraja.satria",
            // GMS
            KnownPackages.GMS,
            // WhatsApp, Telegram (untuk share location)
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.life360.android.safetym"
        )

        internal fun isCallerAllowed(callerPkg: String?): Boolean {
            if (callerPkg.isNullOrEmpty()) return false
            // Modul sendiri selalu boleh
            if (callerPkg.startsWith(KnownPackages.GPM_MODULE)) return true
            // Whitelist
            if (ALLOWED_CALLERS.contains(callerPkg)) return true
            // Package turunan (mis. com.grab.driver.x)
            if (callerPkg.startsWith("com.grabtaxi.") || callerPkg.startsWith("com.grab.")) return true
            if (callerPkg.startsWith("com.gojek.")) return true
            if (com.gpm.virtual.xposed.LocationHook.isTargetPackage(callerPkg)) return true
            // Block lainnya
            return false
        }

        @Volatile
        var activeConfig: GpsConfig? = null

        @Volatile
        var lastHeartbeatTime: Long = 0L

        @Volatile
        var lastPingedPackage: String? = null

        @Volatile
        var totalPingCount: Int = 0

        @Volatile
        var sApplicationContext: Context? = null

        val lastHandshakeTimeMap = ConcurrentHashMap<String, Long>()

        fun sendLocationSharingBroadcast(context: Context, lat: Double, lng: Double, speed: Float, bearing: Float) {
            try {
                fun fillIntent(intent: Intent) {
                    intent.putExtra("latitude", lat)
                    intent.putExtra("longitude", lng)
                    intent.putExtra("lat", lat)
                    intent.putExtra("lng", lng)
                    intent.putExtra("location_sharing_lat", lat)
                    intent.putExtra("location_sharing_lng", lng)
                    intent.putExtra("speed", speed)
                    intent.putExtra("bearing", bearing)
                    intent.putExtra("location_sharing", true)
                    intent.putExtra("is_location_sharing_enabled", true)
                    intent.putExtra("provider", "gps")
                    intent.putExtra("timestamp", System.currentTimeMillis())
                }

                val intent = Intent(ACTION_LOCATION_SHARING).also { fillIntent(it) }
                context.sendBroadcast(intent)

                val targets = (BROADCAST_TARGET_PACKAGES + listOfNotNull(lastPingedPackage)).distinct()
                for (pkg in targets) {
                    try {
                        val direct = Intent(ACTION_LOCATION_SHARING).also {
                            fillIntent(it)
                            it.setPackage(pkg)
                        }
                        context.sendBroadcast(direct)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }
}