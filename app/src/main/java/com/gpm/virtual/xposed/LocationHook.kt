package com.gpm.virtual.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.random.Random
import com.google.android.gms.location.LocationListener as GmsLocationListener

object LocationHook {

    private const val TAG = "GPM-Xposed"
    private val MODULE_PACKAGES = setOf("com.gpm.virtual", "com.gpm.virtual.mchaji")
    fun isModulePackage(pkg: String): Boolean =
        MODULE_PACKAGES.contains(pkg) || pkg.startsWith("com.gpm.virtual")

    private const val TAG_GRAB = "GPM-GRB"
    private const val GRAB_LOG_THROTTLE_MS = 500L
    @Volatile private var lastGrabBearing: Float = 45f
    @Volatile private var lastGrabAltitude: Double = 55.0
    @Volatile private var lastGrabNanos: Long = 0L
    @Volatile private var lastGrabLogMs: Long = 0L

    private const val INTERNAL_PERMISSION = "com.gpm.virtual.permission.INTERNAL"
    private const val RECEIVER_MAX_RETRY = 12
    private const val RECEIVER_RETRY_DELAY_MS = 250L

    val KNOWN_TARGET_PACKAGES = setOf(
        "com.google.android.apps.maps", "com.gojek.partner", "com.gojek.app",
        "com.grabtaxi.driver2", "com.grabtaxi.driver", "com.grabtaxi.passenger",
        "com.shopee.driver.id", "com.shopee.id", "com.maxim.driver", "com.taxsee.taxsee",
        "com.indriver.driver", "sinet.startup.inDriver", "com.waze", "hk.gogovan.GoGoVanDriver2",
        "com.lalamove.driver", "pt.dostavista", "com.deliveree", "id.anteraja.satria",
        "com.sicepat.kurir", "com.jtexpress", "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "com.life360.android.safetym"
    )

    fun isTargetPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg == "android" || pkg == "system_server" || pkg.startsWith("android.") || pkg.startsWith("com.android.")) return false
        if (isModulePackage(pkg)) return false
        if (pkg == "com.google.android.gms") return false
        if (KNOWN_TARGET_PACKAGES.contains(pkg)) return true
        return pkg.contains("driver") || pkg.contains("partner") || pkg.contains("delivery") || pkg.contains("kurir")
    }

    private const val PER_APP_PREFS = "gpm_injection_prefs"
    @Volatile private var perAppCoordsCachePkg: String = ""
    @Volatile private var perAppCoordsCacheValue: Pair<Double, Double>? = null
    @Volatile private var perAppCoordsCacheExpiry: Long = 0L
    private const val PER_APP_COORDS_CACHE_MS = 200L

    internal fun getPerAppCoords(pkg: String): Pair<Double, Double>? {
        val now = SystemClock.elapsedRealtime()
        if (pkg == perAppCoordsCachePkg && now < perAppCoordsCacheExpiry && perAppCoordsCacheValue != null) {
            return perAppCoordsCacheValue
        }
        val prefix = when {
            pkg == GojekHookEntry.TARGET_PACKAGE -> "gojek"
            GrabHookEntry.isTargetPackage(pkg) -> "grab"
            else -> {
                perAppCoordsCachePkg = pkg; perAppCoordsCacheValue = null
                perAppCoordsCacheExpiry = now + PER_APP_COORDS_CACHE_MS; return null
            }
        }
        var result: Pair<Double, Double>? = null
        val oLat = perAppOverrideLat
        val oLng = perAppOverrideLng
        if (oLat != null && oLng != null && !oLat.isNaN() && !oLng.isNaN() && oLat in -90.0..90.0 && oLng in -180.0..180.0 && (oLat != 0.0 || oLng != 0.0)) {
            result = oLat to oLng
        }
        if (result == null) {
            result = try {
                val prefs = module?.getRemotePreferences(PER_APP_PREFS)
                if (prefs != null) {
                    val hasTarget   = prefs.getBoolean("${prefix}_has_target", false)
                    val spoofActive = prefs.getBoolean("${prefix}_spoof_active", false)
                    if (hasTarget && spoofActive) {
                        val lat = Double.fromBits(prefs.getLong("${prefix}_lat", 0L))
                        val lng = Double.fromBits(prefs.getLong("${prefix}_lng", 0L))
                        if (!lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)) lat to lng
                        else null
                    } else null
                } else null
            } catch (_: Throwable) { null }
        }
        if (result == null && isSpoofActive && spoofLat in -90.0..90.0 && spoofLng in -180.0..180.0 && (spoofLat != 0.0 || spoofLng != 0.0)) {
            result = spoofLat to spoofLng
        }
        perAppCoordsCachePkg = pkg
        perAppCoordsCacheValue = result
        perAppCoordsCacheExpiry = now + PER_APP_COORDS_CACHE_MS
        return result
    }

    internal fun isPerAppSpoofActive(pkg: String): Boolean {
        if (perAppSpoofOverride != null) return perAppSpoofOverride == true
        val prefix = when {
            pkg == GojekHookEntry.TARGET_PACKAGE -> "gojek"
            GrabHookEntry.isTargetPackage(pkg) -> "grab"
            else -> return isSpoofActive
        }
        val isAppSpoofActive = try {
            val prefs = module?.getRemotePreferences(PER_APP_PREFS)
            if (prefs != null) prefs.getBoolean("${prefix}_spoof_active", false) else false
        } catch (_: Throwable) { false }
        if (isAppSpoofActive) return true
        return isSpoofActive
    }

    internal fun isTargetSpoofApp(pkg: String): Boolean =
        pkg == GojekHookEntry.TARGET_PACKAGE || GrabHookEntry.isTargetPackage(pkg)

    private const val PROVIDER_AUTHORITY = "com.gpm.virtual.provider"
    private const val PROVIDER_AUTHORITY_ALT = "com.gpm.virtual.mchaji.provider"
    private const val PREFS_NAME = "gpm_virtual_prefs"
    private const val ACTION_TARGET_APP_CONNECTED = "com.gpm.virtual.TARGET_APP_CONNECTED"
    private val PUBLIC_CONFIG_PATHS = listOf(
        "/data/data/com.gpm.virtual.mchaji/files/gpm_config.json",
        "/data/data/com.gpm.virtual/files/gpm_config.json"
    )
    private const val PUBLIC_CONFIG_PATH = "/data/data/com.gpm.virtual.mchaji/files/gpm_config.json"

    // ⭐ HIGH PRIORITY & HIGH ACCURACY CONSTANTS
    private const val HIGH_PRIORITY_INTERVAL_MS = 200L
    private const val HIGH_PRIORITY_FASTEST_INTERVAL_MS = 100L
    private const val FASTEST_CAPTURE_INTERVAL_MS = 100L
    private const val FASTEST_CAPTURE_FASTEST_INTERVAL_MS = 50L
    private const val COORD_LOG_INTERVAL_MS = 5000L

    fun getSpoofSpeed(): Float {
        if (isSpoofActive) { refreshSpoofCacheIfNeeded(); return spoofCacheSpeed }
        return spoofSpeed
    }

    fun getSpoofBearing(): Float {
        if (isSpoofActive) { refreshSpoofCacheIfNeeded(); return spoofCacheBearing }
        return spoofBearing
    }

    fun getSpoofPingTimestamp(): Long = spoofPingTimestamp
    fun getSpoofLocationTimestamp(): Long = spoofLocationTimestamp
    fun getCurrentPackageName(): String = currentPackage
    fun getPingOffsetMs(): Long = pingOffsetMs

    internal fun currentSpoofAccuracy(): Float {
        if (!isSpoofActive) return 4.5f
        refreshSpoofCacheIfNeeded(); return spoofCacheAcc
    }

    internal fun currentSpoofSpeed(): Float {
        if (!isSpoofActive) return 0f
        refreshSpoofCacheIfNeeded(); return spoofCacheSpeed
    }

    internal fun currentSpoofBearing(): Float {
        if (!isSpoofActive) return 0f
        refreshSpoofCacheIfNeeded(); return spoofCacheBearing
    }

    internal fun currentSpoofAltitude(): Double {
        if (!isSpoofActive) return 0.0
        refreshSpoofCacheIfNeeded(); return spoofCacheAlt
    }

    internal fun warmSpoofCache(): Boolean {
        if (!isSpoofActive) return false
        refreshSpoofCacheIfNeeded(); return true
    }

    internal fun snapshotSpoofLat(): Double = spoofCacheLat
    internal fun snapshotSpoofLng(): Double = spoofCacheLng
    internal fun snapshotSpoofAccuracy(): Float = spoofCacheAcc
    internal fun snapshotSpoofSpeed(): Float = spoofCacheSpeed
    internal fun snapshotSpoofBearing(): Float = spoofCacheBearing
    internal fun snapshotSpoofAltitude(): Double = spoofCacheAlt

    internal fun logFromCoordinator(tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        try { module?.log(Log.INFO, tag, msg) } catch (_: Throwable) {}
    }

    internal fun isGrabApp(): Boolean {
        val p = currentPackage
        return p == "com.grabtaxi.driver2" || p == "com.grab.driver" || p.startsWith("com.grabtaxi.") || p.startsWith("com.grab.")
    }

    private data class GrabSnap(
        val lat: Double, val lng: Double, val alt: Double, val brg: Float,
        val acc: Float, val time: Long, val nanos: Long, val expiry: Long
    )

    @Volatile private var grabSnapCache: GrabSnap? = null

    private fun applyGrabStandard(loc: Location) {
        val nowMs = System.currentTimeMillis()
        var snap = grabSnapCache
        if (snap == null || nowMs >= snap.expiry) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val dtSec = if (lastGrabNanos == 0L) 0.05 else ((nowNs - lastGrabNanos) / 1_000_000_000.0).coerceIn(0.0, 1.0)
            val cLat = effectiveSpoofLat()
            val cLng = effectiveSpoofLng()
            val lat = cLat + Random.nextDouble(-0.00005, 0.00005)
            val lng = cLng + Random.nextDouble(-0.00005, 0.00005)
            lastGrabAltitude = (lastGrabAltitude + Random.nextDouble(-0.15, 0.15) * dtSec).coerceIn(30.0, 80.0)
            var b = lastGrabBearing + (Random.nextDouble(-3.0, 3.0) * dtSec).toFloat()
            if (b < 0f) b += 360f
            if (b >= 360f) b -= 360f
            lastGrabBearing = b
            val acc = 1.0f + Random.nextFloat() * 2.5f
            lastGrabNanos = nowNs
            snap = GrabSnap(lat, lng, lastGrabAltitude, b, acc, nowMs, nowNs, nowMs + 50L)
            grabSnapCache = snap
        }
        loc.latitude = snap.lat
        loc.longitude = snap.lng
        loc.altitude = snap.alt
        loc.bearing = snap.brg
        loc.speed = 0f
        loc.accuracy = snap.acc
        loc.time = snap.time
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try { loc.elapsedRealtimeNanos = snap.nanos } catch (_: Throwable) {}
        }
        loc.provider = "fused"
    }

    fun currentSpoofCoords(): Pair<Double, Double>? {
        if (isModulePackage(currentPackage)) return null
        if (!isHookActive) return null

        if (isTargetSpoofApp(currentPackage)) {
            if (!isPerAppSpoofActive(currentPackage)) return null
            val oLat = perAppOverrideLat
            val oLng = perAppOverrideLng
            if (oLat != null && oLng != null && oLat != 0.0 && oLng != 0.0 && oLat in -90.0..90.0 && oLng in -180.0..180.0) {
                return oLat to oLng
            }
            val perApp = getPerAppCoords(currentPackage)
            if (perApp != null) return perApp
            if (isSpoofActive && spoofLat in -90.0..90.0 && spoofLat != 0.0 && spoofLng in -180.0..180.0 && spoofLng != 0.0) {
                return spoofLat to spoofLng
            }
            val effLat = effectiveSpoofLat()
            val effLng = effectiveSpoofLng()
            if (effLat != 0.0 && effLng != 0.0 && effLat in -90.0..90.0 && effLng in -180.0..180.0) return effLat to effLng
            return null
        }
        if (!isSpoofActive) return null
        val oLat = perAppOverrideLat
        val oLng = perAppOverrideLng
        if (oLat != null && oLng != null) return oLat to oLng
        refreshSpoofCacheIfNeeded()
        return spoofCacheLat to spoofCacheLng
    }

    internal fun isValidEpoch(ts: Long): Boolean {
        if (ts <= 0L) return true
        val min = 1_262_304_000_000L
        val max = System.currentTimeMillis() + 5L * 60L * 1000L
        return ts in min..max
    }

    @Volatile private var lastConfigFileMtime: Long = 0L
    @Volatile private var lastConfigFileReadTime: Long = 0L
    private const val CONFIG_FILE_CACHE_TTL_MS = 200L

    private fun readPublicConfigFile(): Boolean {
        try {
            val remote = module?.getRemotePreferences(PREFS_NAME)
            if (remote != null) {
                val remoteSpoofManual = remote.getBoolean("is_spoof_active", false)
                val remoteUseVt = remote.getBoolean("use_virtual", false)
                val remoteUseVtRacing = remote.getBoolean("use_virtual_racing", false)

                isHookActive = remote.getBoolean("is_hook_enabled", true)
                useVirtual = remoteUseVt
                useVirtualRacing = remoteUseVtRacing
                useRandom = remote.getBoolean("use_random", useRandom)
                isSpoofActive = remoteSpoofManual

                if (remoteSpoofManual) {
                    spoofLat = java.lang.Double.longBitsToDouble(remote.getLong("latitude", java.lang.Double.doubleToLongBits(spoofLat)))
                    spoofLng = java.lang.Double.longBitsToDouble(remote.getLong("longitude", java.lang.Double.doubleToLongBits(spoofLng)))
                    spoofAccuracy = remote.getFloat("accuracy", spoofAccuracy)
                    isRandomLocEnabled = remote.getBoolean("random_location", isRandomLocEnabled)
                    randomRadius = remote.getFloat("random_radius", randomRadius)
                    isRandomAccEnabled = remote.getBoolean("random_accuracy", isRandomAccEnabled)
                    isAntiMockEnabled = remote.getBoolean("anti_mock", isAntiMockEnabled)
                    spoofSpeed = remote.getFloat("speed", spoofSpeed)
                    spoofBearing = remote.getFloat("bearing", spoofBearing)
                    spoofPingTimestamp = remote.getLong("pingTimestamp", spoofPingTimestamp)
                    spoofLocationTimestamp = remote.getLong("locationTimestamp", spoofLocationTimestamp)
                    spoofAccelerometerSpeed = remote.getFloat("accelerometerSpeed", spoofAccelerometerSpeed)
                    spoofAccelerometerBearing = remote.getFloat("accelerometerBearing", spoofAccelerometerBearing)
                }
                isConnectionStabilityEnabled = remote.getBoolean("connection_stability_enabled", isConnectionStabilityEnabled)
                isOkHttpStabilityEnabled = remote.getBoolean("okhttp_stability_enabled", isOkHttpStabilityEnabled)
                isFastTcpEnabled = remote.getBoolean("fast_tcp_enabled", isFastTcpEnabled)
                isFastestCaptureEnabled = remote.getBoolean("fastest_capture_mode_enabled", isFastestCaptureEnabled)
                isGcmSupportEnabled = remote.getBoolean("gcm_support_enabled", isGcmSupportEnabled)
                isLocationSharingActive = remote.getBoolean("is_location_sharing_enabled", remote.getBoolean("location_sharing", isLocationSharingActive))
                reapplyPerAppSpoofOverride()
                return true
            }
        } catch (_: Throwable) {}

        for (path in PUBLIC_CONFIG_PATHS) {
            try {
                val file = java.io.File(path)
                if (!file.exists() || !file.canRead()) continue
                val mtime = file.lastModified()
                val now = System.currentTimeMillis()
                if (mtime == lastConfigFileMtime && (now - lastConfigFileReadTime) < CONFIG_FILE_CACHE_TTL_MS) return true
                lastConfigFileMtime = mtime
                lastConfigFileReadTime = now
                val text = file.readText()
                if (text.isBlank()) continue
                val json = org.json.JSONObject(text)
                isHookActive = json.optBoolean("is_hook_enabled", isHookActive)
                isSpoofActive = json.optBoolean("is_spoof_active", isSpoofActive)
                useVirtual = json.optBoolean("use_virtual", useVirtual)
                useVirtualRacing = json.optBoolean("use_virtual_racing", useVirtualRacing)
                useRandom = json.optBoolean("use_random", useRandom)
                spoofLat = json.optDouble("latitude", spoofLat)
                spoofLng = json.optDouble("longitude", spoofLng)
                spoofAccuracy = json.optDouble("accuracy", spoofAccuracy.toDouble()).toFloat()
                isRandomLocEnabled = json.optBoolean("random_location", isRandomLocEnabled)
                randomRadius = json.optDouble("random_radius", randomRadius.toDouble()).toFloat()
                isRandomAccEnabled = json.optBoolean("random_accuracy", isRandomAccEnabled)
                isAntiMockEnabled = json.optBoolean("anti_mock", isAntiMockEnabled)
                spoofSpeed = json.optDouble("speed", spoofSpeed.toDouble()).toFloat()
                spoofBearing = json.optDouble("bearing", spoofBearing.toDouble()).toFloat()
                val rawPingTs = json.optLong("ping_timestamp", json.optLong("pingTimestamp", spoofPingTimestamp))
                val rawLocTs = json.optLong("location_timestamp", json.optLong("locationTimestamp", spoofLocationTimestamp))
                spoofPingTimestamp = if (isValidEpoch(rawPingTs)) rawPingTs else 0L
                spoofLocationTimestamp = if (isValidEpoch(rawLocTs)) rawLocTs else 0L
                pingOffsetMs = json.optLong("ping_offset_ms", pingOffsetMs)
                spoofAccelerometerSpeed = json.optDouble("accelerometer_speed", json.optDouble("accelerometerSpeed", spoofAccelerometerSpeed.toDouble())).toFloat()
                spoofAccelerometerBearing = json.optDouble("accelerometer_bearing", json.optDouble("accelerometerBearing", spoofAccelerometerBearing.toDouble())).toFloat()
                isConnectionStabilityEnabled = json.optBoolean("connection_stability_enabled", isConnectionStabilityEnabled)
                isOkHttpStabilityEnabled = json.optBoolean("okhttp_stability_enabled", isOkHttpStabilityEnabled)
                isFastTcpEnabled = json.optBoolean("fast_tcp_enabled", isFastTcpEnabled)
                isFastestCaptureEnabled = json.optBoolean("fastest_capture_mode_enabled", isFastestCaptureEnabled)
                isGcmSupportEnabled = json.optBoolean("gcm_support_enabled", isGcmSupportEnabled)
                isLocationSharingActive = json.optBoolean("is_location_sharing_enabled", json.optBoolean("location_sharing", isLocationSharingActive))
                reapplyPerAppSpoofOverride()
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private val hookedFusedConcreteClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedGmsTaskClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedSensorListenerClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    private fun shouldSpoof(): Boolean {
        if (isModulePackage(currentPackage)) return false
        if (HookGuard.isGuarded()) return false
        if (isSystemServer || currentPackage == "android") return false
        refreshConfig(sAppContext)
        return isHookActive && isSpoofActive
    }

    private fun ensureContextReady(): Boolean {
        if (isSystemServer || currentPackage == "android") return false
        if (sAppContext != null) return true
        try {
            val ctx = getProcessContext(null)
            if (ctx != null) {
                sAppContext = ctx.applicationContext ?: ctx
                registerContentObserver(sAppContext!!)
                lastQueryTime = 0L
                refreshConfig(sAppContext, force = true)
                return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun tryRefreshConfigSafe(): Boolean {
        if (isSystemServer || currentPackage == "android") return isHookActive
        if (HookGuard.isGuarded()) return isHookActive
        if (!ensureContextReady()) return isHookActive
        try { refreshConfig(sAppContext, force = false) } catch (_: Throwable) {}
        return isHookActive
    }

    @Volatile private var module: XposedModule? = null
    @Volatile internal var isSystemServer: Boolean = false
    @Volatile internal var currentProcessName: String = ""
    @Volatile private var sAppContext: Context? = null
    @Volatile private var currentPackage: String = "unknown"

    @Volatile var isHookActive: Boolean = false
        private set

    @Volatile var isSpoofActive: Boolean = false
        private set

    @Volatile private var localGeneration: Int = 0

    private fun lifecycleAllowsSpoof(): Boolean {
        try {
            val remote = module?.getRemotePreferences("gpm_lifecycle_prefs")
            if (remote != null && remote.getBoolean("active", false)) return true
        } catch (_: Throwable) {}
        try {
            val remote = module?.getRemotePreferences(PREFS_NAME)
            if (remote != null) {
                if (remote.getBoolean("is_spoof_active", false)) return true
            }
        } catch (_: Throwable) {}
        if (perAppSpoofOverride == true) return true
        return try {
            val ctx = sAppContext ?: return false
            ctx.getSharedPreferences("gpm_lifecycle_prefs", Context.MODE_PRIVATE).getBoolean("active", false)
        } catch (_: Throwable) { false }
    }

    private fun syncLifecycleGeneration(): Boolean {
        val current = try {
            val remote = module?.getRemotePreferences("gpm_lifecycle_prefs")
            remote?.getInt("generation", 0) ?: 0
        } catch (_: Throwable) { 0 }
        if (current == 0 && localGeneration == 0) return false
        if (current != localGeneration) {
            localGeneration = current
            return true
        }
        return false
    }

    @Volatile private var realLat: Double = -6.1683295
    @Volatile private var realLng: Double = 106.7588494
    @Volatile private var spoofLat = -6.1683295
    @Volatile private var spoofLng = 106.7588494
    @Volatile private var spoofAccuracy = 10.0f
    @Volatile private var spoofSpeed = 1.2f
    @Volatile private var spoofBearing = 45.0f
    @Volatile private var spoofPingTimestamp = 0L
    @Volatile private var spoofLocationTimestamp = 0L
    @Volatile private var spoofElapsedRealtimeNanos = 0L
    @Volatile private var spoofAccelerometerSpeed = 1.2f
    @Volatile private var spoofAccelerometerBearing = 45.0f
    @Volatile private var isRandomLocEnabled = false
    @Volatile private var randomRadius = 8.0f
    @Volatile private var isRandomAccEnabled = true
    @Volatile private var pingOffsetMs: Long = 0L

    @Volatile private var perAppOverrideLat: Double? = null
    @Volatile private var perAppOverrideLng: Double? = null
    @Volatile private var perAppSpoofOverride: Boolean? = null

    internal fun setOverrideCoords(lat: Double, lng: Double) {
        perAppOverrideLat = lat; perAppOverrideLng = lng; perAppSpoofOverride = true; isSpoofActive = true
        perAppCoordsCachePkg = ""; perAppCoordsCacheValue = null; perAppCoordsCacheExpiry = 0L
        invalidateSpoofCache()
        persistPerAppSpoof(lat, lng, active = true)
        logI("[Override] Per-app coords set: lat=$lat lng=$lng")
        try { dispatchLocationToListeners(lat, lng, spoofAccuracy) } catch (_: Throwable) {}
    }

    internal fun clearOverrideCoords() {
        if (perAppOverrideLat == null && perAppOverrideLng == null && perAppCoordsCacheValue == null) return
        perAppOverrideLat = null; perAppOverrideLng = null
        perAppCoordsCachePkg = ""; perAppCoordsCacheValue = null; perAppCoordsCacheExpiry = 0L
        invalidateSpoofCache()
        try { SpoofConsistencyGuard.reset() } catch (_: Throwable) {}
        persistPerAppSpoof(0.0, 0.0, active = false)
        logI("[Override] Per-app coords cleared — fallback ke global")
    }

    private fun effectiveSpoofLat(): Double {
        if (isTargetSpoofApp(currentPackage)) {
            val perApp = getPerAppCoords(currentPackage)
            if (perApp != null) return perApp.first
            val oLat = perAppOverrideLat
            if (oLat != null && !oLat.isNaN() && oLat in -90.0..90.0) return oLat
            if (spoofLat in -90.0..90.0 && spoofLat != 0.0) return spoofLat
            return 0.0
        }
        return perAppOverrideLat ?: spoofLat
    }

    private fun effectiveSpoofLng(): Double {
        if (isTargetSpoofApp(currentPackage)) {
            val perApp = getPerAppCoords(currentPackage)
            if (perApp != null) return perApp.second
            val oLng = perAppOverrideLng
            if (oLng != null && !oLng.isNaN() && oLng in -180.0..180.0) return oLng
            if (spoofLng in -180.0..180.0 && spoofLng != 0.0) return spoofLng
            return 0.0
        }
        return perAppOverrideLng ?: spoofLng
    }

    internal fun applyPerAppSpoofOverride(active: Boolean, fromBroadcast: Boolean = false) {
        if (active && !fromBroadcast && !lifecycleAllowsSpoof()) {
            logI("[Spoof] Skip applyPerAppSpoofOverride(true) — lifecycle OFF")
            perAppSpoofOverride = false; isSpoofActive = false; return
        }
        perAppSpoofOverride = active; isHookActive = true; isSpoofActive = active
        invalidateSpoofCache()
        try { SpoofConsistencyGuard.reset() } catch (_: Throwable) {}
        val lat = perAppOverrideLat ?: spoofLat
        val lng = perAppOverrideLng ?: spoofLng
        persistPerAppSpoof(lat, lng, active)
        logI("[Spoof] Per-app override applied: active=$active fromBroadcast=$fromBroadcast (pkg=$currentPackage)")
    }

    @Volatile private var persistWarned = false

    private fun persistPerAppSpoof(lat: Double, lng: Double, active: Boolean) {
        try {
            val prefix = when {
                currentPackage == GojekHookEntry.TARGET_PACKAGE -> "gojek"
                GrabHookEntry.isTargetPackage(currentPackage) -> "grab"
                else -> return
            }
            val ctx = sAppContext ?: return
            try {
                val intent = Intent(ACTION_PERSIST_SPOOF_REQUEST).apply {
                    setPackage("com.gpm.virtual.mchaji")
                    putExtra("prefix", prefix); putExtra("active", active); putExtra("has_target", active)
                    putExtra("lat", lat); putExtra("lng", lng)
                }
                ctx.sendBroadcast(intent, INTERNAL_PERMISSION)
            } catch (_: Throwable) {
                if (!persistWarned) { persistWarned = true }
            }
        } catch (_: Throwable) {}
    }

    internal fun forceRealLocationRefresh() {
        try {
            val prevSpoof = isSpoofActive
            isSpoofActive = false
            spoofCacheExpiry = 0L; spoofCacheLat = 0.0; spoofCacheLng = 0.0
            try {
                val lm = sAppContext?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val last = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (last != null) { realLat = last.latitude; realLng = last.longitude }
            } catch (_: Throwable) {}
            logI("[Recovery] Force real location refresh")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val realLoc = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = realLat; longitude = realLng; accuracy = 4.5f
                        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    if (isAntiMockEnabled) cleanMockFlags(realLoc)
                    val listeners = synchronized(activeLocationListeners) { activeLocationListeners.toList() }
                    for (listener in listeners) {
                        try {
                            when (listener) {
                                is LocationListener -> listener.onLocationChanged(realLoc)
                                is GmsLocationListener -> listener.onLocationChanged(realLoc)
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                isSpoofActive = prevSpoof
            }, 50L)
        } catch (t: Throwable) { logE("[Recovery] forceRealLocationRefresh failed: ${t.message}", t) }
    }

    internal fun clearPerAppSpoofOverride() {
        if (perAppSpoofOverride == null) return
        perAppSpoofOverride = null
        invalidateSpoofCache()
        logI("[Spoof] Per-app override cleared")
    }

    private fun reapplyPerAppSpoofOverride() { perAppSpoofOverride?.let { isSpoofActive = it } }

    @Volatile var isAntiMockEnabled: Boolean = true
        private set
    @Volatile var isConnectionStabilityEnabled: Boolean = false
        private set
    @Volatile var isOkHttpStabilityEnabled: Boolean = true
        private set
    @Volatile var isFastTcpEnabled: Boolean = true
        private set
    @Volatile var isFastestCaptureEnabled: Boolean = true
        private set
    @Volatile var isGcmSupportEnabled: Boolean = true
        private set
    @Volatile var isLocationSharingActive: Boolean = true
        private set
    @Volatile var useVirtual: Boolean = false
        private set
    @Volatile var useVirtualRacing: Boolean = false
        private set
    @Volatile var useRandom: Boolean = false
        private set

    fun isVtracingActive(): Boolean = isSpoofActive && useVirtualRacing
    fun isConnectionStabilityActive(): Boolean = !isSystemServer && isTargetPackage(currentPackage) && isConnectionStabilityEnabled
    fun isOkHttpStabilityActive(): Boolean = !isSystemServer && isTargetPackage(currentPackage) && isConnectionStabilityEnabled && isOkHttpStabilityEnabled
    fun isFastTcpActive(): Boolean = !isSystemServer && isTargetPackage(currentPackage) && isConnectionStabilityEnabled && isFastTcpEnabled
    fun isFastestCaptureActive(): Boolean = !isSystemServer && isTargetPackage(currentPackage) && isConnectionStabilityEnabled && isFastestCaptureEnabled
    fun isGcmSupportActive(): Boolean = !isSystemServer && isConnectionStabilityEnabled && isGcmSupportEnabled

    @Volatile private var lastQueryTime = 0L
    @Volatile private var lastCoordLogTime = 0L
    @Volatile private var applicationAttachHooked = false
    private const val SPOOF_CACHE_TTL_MS = 250L

    @Volatile private var spoofCacheExpiry = 0L
    @Volatile private var spoofCacheLat = 0.0
    @Volatile private var spoofCacheLng = 0.0
    @Volatile private var spoofCacheAcc = 0f
    @Volatile private var spoofCacheSpeed = 0f
    @Volatile private var spoofCacheBearing = 0f
    @Volatile private var spoofCacheAlt = 0.0
    @Volatile private var spoofCacheAccSpeed = 0f
    @Volatile private var spoofCacheAccBearing = 0f
    @Volatile private var spoofCacheLocTs = 0L
    @Volatile private var spoofCacheElapsedNanos = 0L
    @Volatile private var spoofCachePingTs = 0L

    private fun invalidateSpoofCache() {
        spoofCacheExpiry = 0L
        refreshSpoofCacheIfNeeded()
    }

    private fun refreshSpoofCacheIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now < spoofCacheExpiry) return
        val effLatRaw = effectiveSpoofLat()
        val effLngRaw = effectiveSpoofLng()
        if (effLatRaw == 0.0 && effLngRaw == 0.0) { spoofCacheExpiry = now + 20L; return }

        val transitioning = SpoofConsistencyGuard.isTransitioning()
        val effLat: Double; val effLng: Double
        if (transitioning) {
            val (tLat, tLng) = SpoofConsistencyGuard.interpolateTransition(effLatRaw, effLngRaw)
            effLat = tLat; effLng = tLng
        } else {
            effLat = effLatRaw; effLng = effLngRaw
        }

        val cacheTtl = if (transitioning) 60L else SPOOF_CACHE_TTL_MS

        if (useVirtualRacing) {
            com.gpm.virtual.util.VtracingEngine.setCenter(effLat, effLng)
            com.gpm.virtual.util.VtracingEngine.update(useRandom || isRandomLocEnabled)
            val pos = com.gpm.virtual.util.VtracingEngine.getSpoofedPosition()
            val spd = com.gpm.virtual.util.VtracingEngine.getSpeed()
            val brg = com.gpm.virtual.util.VtracingEngine.getBearing()
            val acc = com.gpm.virtual.util.VtracingEngine.getAccuracy()

            spoofCacheLat = RealisticGpsEngine.roundTo7Decimals(pos.first)
            spoofCacheLng = RealisticGpsEngine.roundTo7Decimals(pos.second)
            spoofCacheAcc = if (isRandomAccEnabled) acc else spoofAccuracy
            spoofCacheSpeed = spd
            spoofCacheBearing = brg
            spoofCacheAlt = RealisticGpsEngine.computeRealisticAltitude()
            spoofCacheAccSpeed = RealisticGpsEngine.computeAccelerometerSpeed(spd).coerceIn(0.0f, 5.0f)
            spoofCacheAccBearing = brg
            spoofCacheExpiry = now + (if (transitioning) 30L else 30L)
            try {
                val adjusted = SpoofConsistencyGuard.validate(spoofCacheLat, spoofCacheLng, spoofCacheSpeed, spoofCacheBearing)
                if (adjusted.flagged) { spoofCacheSpeed = adjusted.speed; spoofCacheBearing = adjusted.bearing }
            } catch (_: Throwable) {}
        } else {
            val (lat, lng) = RealisticGpsEngine.computeRealisticCoordinates(effLat, effLng, isRandomLocEnabled || useRandom, randomRadius)
            val spd = RealisticGpsEngine.computeRealisticSpeed(spoofSpeed).coerceIn(0.0f, 1.5f)
            val brg = RealisticGpsEngine.computeRealisticBearing()

            spoofCacheLat = RealisticGpsEngine.roundTo6Decimals(lat)
            spoofCacheLng = RealisticGpsEngine.roundTo6Decimals(lng)
            spoofCacheAcc = RealisticGpsEngine.computeRealisticAccuracy(spoofAccuracy, isRandomAccEnabled)
            spoofCacheSpeed = spd
            spoofCacheBearing = brg
            spoofCacheAlt = RealisticGpsEngine.computeRealisticAltitude()
            spoofCacheAccSpeed = RealisticGpsEngine.computeAccelerometerSpeed(spd).coerceIn(0.0f, 1.5f)
            spoofCacheAccBearing = RealisticGpsEngine.computeAccelerometerBearing(brg)
            spoofCacheExpiry = now + cacheTtl
            try {
                val adjusted = SpoofConsistencyGuard.validate(spoofCacheLat, spoofCacheLng, spoofCacheSpeed, spoofCacheBearing)
                if (adjusted.flagged) {
                    spoofCacheSpeed = adjusted.speed; spoofCacheBearing = adjusted.bearing
                    spoofCacheAccSpeed = RealisticGpsEngine.computeAccelerometerSpeed(adjusted.speed).coerceIn(0.0f, 1.5f)
                    spoofCacheAccBearing = RealisticGpsEngine.computeAccelerometerBearing(adjusted.bearing)
                }
            } catch (_: Throwable) {}
        }

        val locTs = if (spoofLocationTimestamp > 0L) spoofLocationTimestamp else System.currentTimeMillis()
        spoofCacheLocTs = locTs
        spoofCacheElapsedNanos = if (spoofElapsedRealtimeNanos > 0L) spoofElapsedRealtimeNanos else SystemClock.elapsedRealtimeNanos()
        spoofCachePingTs = if (spoofPingTimestamp > 0L) spoofPingTimestamp else locTs
    }

    private val locationSetMockMethod: Method? by lazy {
        try { Location::class.java.getDeclaredMethod("setMock", Boolean::class.javaPrimitiveType).apply { isAccessible = true } } catch (_: Throwable) { null }
    }
    private val locationSetIsFromMockProviderMethod: Method? by lazy {
        try { Location::class.java.getDeclaredMethod("setIsFromMockProvider", Boolean::class.javaPrimitiveType).apply { isAccessible = true } } catch (_: Throwable) { null }
    }
    private val locationMockFlagFields: List<java.lang.reflect.Field> by lazy {
        try {
            Location::class.java.declaredFields.filter { it.name.equals("mIsFromMockProvider", ignoreCase = true) || it.name.equals("mIsMock", ignoreCase = true) }.onEach { it.isAccessible = true }
        } catch (_: Throwable) { emptyList() }
    }

    const val ACTION_PING_LOCATION = "com.gpm.virtual.ACTION_PING_LOCATION"
    const val ACTION_LOCATION_UPDATED = "com.gpm.virtual.MOCK_LOCATION_UPDATE"
    const val ACTION_FORCE_REHOOK_GOJEK = "com.gpm.virtual.FORCE_REHOOK_GOJEK"
    const val ACTION_FORCE_REHOOK_GRAB  = "com.gpm.virtual.FORCE_REHOOK_GRAB"
    const val ACTION_CLEAR_OVERRIDE     = "com.gpm.virtual.CLEAR_OVERRIDE"
    const val ACTION_SPOOF_GOJEK        = "com.gpm.virtual.ACTION_SPOOF_GOJEK"
    const val ACTION_SPOOF_GRAB         = "com.gpm.virtual.ACTION_SPOOF_GRAB"
    const val ACTION_FORCE_SYNC         = "com.gpm.virtual.FORCE_SYNC"
    const val ACTION_LOCATION_SHARING   = "com.gpm.virtual.ACTION_LOCATION_SHARING"
    const val ACTION_SHOW_OVERLAY   = "com.gpm.virtual.ACTION_SHOW_OVERLAY"
    const val ACTION_UPDATE_OVERLAY = "com.gpm.virtual.ACTION_UPDATE_OVERLAY"
    const val ACTION_HIDE_OVERLAY   = "com.gpm.virtual.ACTION_HIDE_OVERLAY"
    const val ACTION_PERSIST_SPOOF_REQUEST = "com.gpm.virtual.ACTION_PERSIST_SPOOF_REQUEST"
    const val ACTION_HARD_SHUTDOWN = "com.gpm.virtual.HARD_SHUTDOWN"

    @Volatile private var observerRegistered = false
    @Volatile private var receiverRegistered = false

    internal object HookGuard {
        @PublishedApi internal val inHook = ThreadLocal<Boolean>()
        fun isGuarded(): Boolean = inHook.get() == true
        inline fun <T> runGuarded(defaultOnRecursion: () -> T, block: () -> T): T {
            if (inHook.get() == true) return defaultOnRecursion()
            inHook.set(true)
            try { return block() } finally { inHook.set(false) }
        }
    }

    internal object SafeLogger {
        private val lastLogTimes = ConcurrentHashMap<String, Long>()
        private const val DEFAULT_INTERVAL_MS = 5000L
        fun d(tag: String = TAG, key: String, msg: String, intervalMs: Long = DEFAULT_INTERVAL_MS) {
            if (!BuildConfig.DEBUG) return
            val now = SystemClock.elapsedRealtime()
            val last = lastLogTimes[key] ?: 0L
            if (now - last >= intervalMs) { lastLogTimes[key] = now; logI(msg) }
        }
        fun e(tag: String = TAG, key: String, msg: String, t: Throwable? = null, intervalMs: Long = DEFAULT_INTERVAL_MS) {
            val now = SystemClock.elapsedRealtime()
            val last = lastLogTimes[key] ?: 0L
            if (now - last >= intervalMs) { lastLogTimes[key] = now; logE(msg, t) }
        }
    }

    private inline fun debugLog(block: () -> String) { if (BuildConfig.DEBUG) { logI(block()) } }

    private val hookedListenerClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedTaskListenerClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedCallbackClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedNmeaListenerClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedGeolocationClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val activeLocationListeners = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    fun attachModule(xp: XposedModule, systemServer: Boolean = false, procName: String = "") {
        module = xp; isSystemServer = systemServer; currentProcessName = procName
    }

    internal fun refreshConfigSafeSystemServer() {
        try {
            val remote = module?.getRemotePreferences(PREFS_NAME)
            if (remote != null) {
                isHookActive = remote.getBoolean("is_hook_enabled", false)
                isSpoofActive = remote.getBoolean("is_spoof_active", false)
                spoofLat = java.lang.Double.longBitsToDouble(remote.getLong("latitude", java.lang.Double.doubleToLongBits(spoofLat)))
                spoofLng = java.lang.Double.longBitsToDouble(remote.getLong("longitude", java.lang.Double.doubleToLongBits(spoofLng)))
                spoofAccuracy = remote.getFloat("accuracy", spoofAccuracy)
                isRandomLocEnabled = remote.getBoolean("random_location", isRandomLocEnabled)
                randomRadius = remote.getFloat("random_radius", randomRadius)
                isRandomAccEnabled = remote.getBoolean("random_accuracy", isRandomAccEnabled)
                isAntiMockEnabled = remote.getBoolean("anti_mock", isAntiMockEnabled)
                isConnectionStabilityEnabled = remote.getBoolean("connection_stability_enabled", isConnectionStabilityEnabled)
                isOkHttpStabilityEnabled = remote.getBoolean("okhttp_stability_enabled", isOkHttpStabilityEnabled)
                isFastTcpEnabled = remote.getBoolean("fast_tcp_enabled", isFastTcpEnabled)
                isFastestCaptureEnabled = remote.getBoolean("fastest_capture_mode_enabled", isFastestCaptureEnabled)
                isGcmSupportEnabled = remote.getBoolean("gcm_support_enabled", isGcmSupportEnabled)
                isLocationSharingActive = remote.getBoolean("is_location_sharing_enabled", remote.getBoolean("location_sharing", isLocationSharingActive))
                spoofSpeed = remote.getFloat("speed", spoofSpeed)
                spoofBearing = remote.getFloat("bearing", spoofBearing)
                val rawPingTs = remote.getLong("pingTimestamp", spoofPingTimestamp)
                val rawLocTs = remote.getLong("locationTimestamp", spoofLocationTimestamp)
                spoofPingTimestamp = if (isValidEpoch(rawPingTs)) rawPingTs else 0L
                spoofLocationTimestamp = if (isValidEpoch(rawLocTs)) rawLocTs else 0L
                pingOffsetMs = remote.getLong("ping_offset_ms", pingOffsetMs)
                spoofAccelerometerSpeed = remote.getFloat("accelerometerSpeed", spoofAccelerometerSpeed)
                spoofAccelerometerBearing = remote.getFloat("accelerometerBearing", spoofAccelerometerBearing)
                invalidateSpoofCache()
            }
        } catch (_: Throwable) {}
    }

    private fun logI(msg: String) { if (!BuildConfig.DEBUG) return; try { module?.log(Log.INFO, TAG, msg) } catch (_: Throwable) {} }
    private fun logW(msg: String) { if (!BuildConfig.DEBUG) return; try { module?.log(Log.WARN, TAG, msg) } catch (_: Throwable) {} }
    private fun logE(msg: String, t: Throwable? = null) { try { if (t != null) module?.log(Log.ERROR, TAG, msg, t) else module?.log(Log.ERROR, TAG, msg) } catch (_: Throwable) {} }

    internal fun hookMethod(method: Method, priority: Int = XposedInterface.PRIORITY_DEFAULT, interceptor: XposedInterface.Hooker) {
        val m = module ?: return
        if (Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers)) return
        if (method.declaringClass.isInterface && method.declaringClass.isAnnotation) return
        try {
            method.isAccessible = true
            m.hook(method).setPriority(priority).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(interceptor)
        } catch (t: Throwable) { logE("Failed to hook ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()} : ${t.message}", t) }
    }

    internal fun hookMethod(constructor: Constructor<*>, priority: Int = XposedInterface.PRIORITY_DEFAULT, interceptor: XposedInterface.Hooker) {
        val m = module ?: return
        try {
            constructor.isAccessible = true
            m.hook(constructor).setPriority(priority).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(interceptor)
        } catch (t: Throwable) { logE("Failed to hook constructor ${constructor.declaringClass.name}${constructor.parameterTypes.contentToString()} : ${t.message}", t) }
    }

    fun shouldBypassMock(): Boolean = isHookActive && isAntiMockEnabled && !isModulePackage(currentPackage)

    private fun getFrameworkDescription(): String = try {
        val m = module
        if (m != null) "${m.frameworkName} ${m.frameworkVersion} (API ${m.apiVersion})" else "LSPosed API 102"
    } catch (_: Throwable) { "LSPosed API 102" }

    internal fun fetchConfigFromProvider(context: Context? = null): Bundle? {
        if (isSystemServer || currentPackage == "android") return null
        val ctx = getProcessContext(context) ?: sAppContext ?: return null
        val authorities = listOf(PROVIDER_AUTHORITY, PROVIDER_AUTHORITY_ALT)
        for (auth in authorities) {
            try {
                val uri = Uri.parse("content://$auth")
                val extras = Bundle().apply { putString("pkg", currentPackage); putString("framework", getFrameworkDescription()) }
                val res = ctx.contentResolver.call(uri, "get_config", currentPackage, extras)
                if (res != null && !res.getBoolean("error", false)) return res
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun pingProvider(context: Context? = null, targetPkg: String? = null) {
        if (isSystemServer || currentPackage == "android") return
        val ctx = getProcessContext(context) ?: return
        try {
            val uri = Uri.parse("content://$PROVIDER_AUTHORITY")
            val pkg = targetPkg ?: currentPackage
            val extras = Bundle().apply { putString("framework", getFrameworkDescription()); putString("pkg", pkg) }
            ctx.contentResolver.call(uri, "ping", pkg, extras)
        } catch (_: Throwable) {}
    }

    fun setApplicationContext(context: Context?) {
        if (context != null) {
            if (isSystemServer || currentPackage == "android") return
            sAppContext = context.applicationContext ?: context
            registerContentObserver(sAppContext!!)
            registerPingReceiver(sAppContext!!)
            refreshConfig(sAppContext, force = true)
            pingProvider(sAppContext)
        }
    }

    private fun registerContentObserver(ctx: Context) {
        if (observerRegistered) return
        val authorities = listOf(PROVIDER_AUTHORITY, PROVIDER_AUTHORITY_ALT)
        for (auth in authorities) {
            try {
                val uri = Uri.parse("content://$auth/config")
                ctx.contentResolver.registerContentObserver(uri, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        lastQueryTime = 0L; refreshConfig(sAppContext, force = true); pingProvider(sAppContext, currentPackage)
                        if (shouldSpoof()) dispatchLocationToListeners(spoofLat, spoofLng, spoofAccuracy)
                    }
                })
                observerRegistered = true
            } catch (_: Throwable) {}
        }
    }

    private fun registerPingReceiver(ctx: Context) {
        if (receiverRegistered) return
        registerPingReceiverWithRetry(ctx, attempt = 1)
    }

    private fun registerPingReceiverWithRetry(ctx: Context, attempt: Int) {
        if (receiverRegistered) return
        if (!isInstrumentationReady()) {
            if (attempt <= RECEIVER_MAX_RETRY) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try { registerPingReceiverWithRetry(ctx, attempt + 1) } catch (_: Throwable) {}
                }, RECEIVER_RETRY_DELAY_MS)
            } else registerPingReceiverFallback(ctx)
            return
        }
        try {
            val filter = createPingFilter()
            val receiver = createPingReceiver()
            val ok = tryRegisterWithPermission(ctx, receiver, filter)
            if (ok) { receiverRegistered = true; return }
            registerPingReceiverFallback(ctx)
        } catch (t: Throwable) {
            logE("[Receiver] Register failed (attempt $attempt): ${t.message}", t)
            if (attempt <= RECEIVER_MAX_RETRY) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try { registerPingReceiverWithRetry(ctx, attempt + 1) } catch (_: Throwable) {}
                }, RECEIVER_RETRY_DELAY_MS)
            } else registerPingReceiverFallback(ctx)
        }
    }

    private fun isInstrumentationReady(): Boolean {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null) ?: return false
            val instr = try { atClass.getMethod("getInstrumentation").invoke(at) } catch (_: Throwable) { null }
            instr != null
        } catch (_: Throwable) { false }
    }

    private fun createPingFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(ACTION_PING_LOCATION); addAction(ACTION_LOCATION_UPDATED); addAction(ACTION_LOCATION_SHARING)
            addAction(ACTION_FORCE_SYNC); addAction(HotReloadActions.ACTION_CONFIG_UPDATED); addAction(HotReloadActions.ACTION_HOT_RELOAD)
            addAction(Intent.ACTION_USER_PRESENT); addAction(ACTION_FORCE_REHOOK_GOJEK); addAction(ACTION_FORCE_REHOOK_GRAB)
            addAction(ACTION_CLEAR_OVERRIDE); addAction(ACTION_SPOOF_GOJEK); addAction(ACTION_SPOOF_GRAB)
            addAction(ACTION_SHOW_OVERLAY); addAction(ACTION_UPDATE_OVERLAY); addAction(ACTION_HIDE_OVERLAY); addAction(ACTION_HARD_SHUTDOWN)
        }
    }

    private fun createPingReceiver(): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                if (!isCallerTrusted()) return

                if (intent.action == ACTION_HARD_SHUTDOWN) {
                    isSpoofActive = false
                    perAppSpoofOverride = null; perAppOverrideLat = null; perAppOverrideLng = null
                    perAppCoordsCachePkg = ""; perAppCoordsCacheValue = null; perAppCoordsCacheExpiry = 0L; spoofCacheExpiry = 0L
                    try { SpoofConsistencyGuard.cancelTransition() } catch (_: Throwable) {}
                    try { SpoofConsistencyGuard.reset() } catch (_: Throwable) {}
                    // ⭐ FIX: Hide Overlay
                    try { com.gpm.virtual.xposed.overlay.SortirOverlayManager.hideOverlay() } catch (_: Throwable) {}
                    Handler(Looper.getMainLooper()).post { try { forceRealLocationRefresh() } catch (_: Throwable) {} }
                    logI("[HardShutdown] Spoof dimatikan paksa (pkg=$currentPackage)")
                    return
                }

                when (intent.action) {
                    ACTION_LOCATION_SHARING -> {
                        val shareLat = intent.getDoubleExtra("latitude", intent.getDoubleExtra("lat", intent.getDoubleExtra("location_sharing_lat", spoofCacheLat)))
                        val shareLng = intent.getDoubleExtra("longitude", intent.getDoubleExtra("lng", intent.getDoubleExtra("location_sharing_lng", spoofCacheLng)))
                        if (!shareLat.isNaN() && !shareLng.isNaN() && shareLat in -90.0..90.0 && shareLng in -180.0..180.0) {
                            isLocationSharingActive = true; isSpoofActive = true; spoofLat = shareLat; spoofLng = shareLng
                            spoofCacheLat = shareLat; spoofCacheLng = shareLng
                            if (intent.hasExtra("accuracy")) spoofCacheAcc = intent.getFloatExtra("accuracy", spoofCacheAcc)
                            if (intent.hasExtra("speed")) spoofCacheSpeed = intent.getFloatExtra("speed", spoofCacheSpeed)
                            if (intent.hasExtra("bearing")) spoofCacheBearing = intent.getFloatExtra("bearing", spoofCacheBearing)
                            spoofCacheExpiry = SystemClock.elapsedRealtime() + SPOOF_CACHE_TTL_MS
                            dispatchLocationToListeners(shareLat, shareLng, spoofCacheAcc)
                        }
                        return
                    }
                    ACTION_FORCE_REHOOK_GOJEK -> {
                        if (currentPackage == GojekHookEntry.TARGET_PACKAGE) {
                            if (intent.getBooleanExtra("override", false)) {
                                val lat = intent.getDoubleExtra("latitude", Double.NaN); val lng = intent.getDoubleExtra("longitude", Double.NaN)
                                if (!lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0) setOverrideCoords(lat, lng)
                            }
                            applyPerAppSpoofOverride(true, fromBroadcast = true); isSpoofActive = true; SpoofConsistencyGuard.markSpoofActivated()
                            val m = module; val cl = sAppContext?.classLoader ?: Thread.currentThread().contextClassLoader
                            if (m != null && cl != null) { try { GojekHookEntry.forceReinstall(m, cl) } catch (t: Throwable) { logE("[ReHook Gojek] ${t.message}", t) } }
                        }
                        return
                    }
                    ACTION_FORCE_REHOOK_GRAB -> {
                        if (GrabHookEntry.isTargetPackage(currentPackage)) {
                            if (intent.getBooleanExtra("override", false)) {
                                val lat = intent.getDoubleExtra("latitude", Double.NaN); val lng = intent.getDoubleExtra("longitude", Double.NaN)
                                if (!lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0) setOverrideCoords(lat, lng)
                            }
                            applyPerAppSpoofOverride(true, fromBroadcast = true); isSpoofActive = true; SpoofConsistencyGuard.markSpoofActivated()
                            val m = module; val cl = sAppContext?.classLoader ?: Thread.currentThread().contextClassLoader
                            if (m != null && cl != null) { try { GrabHookEntry.forceReinstall(m, cl) } catch (t: Throwable) { logE("[ReHook Grab] ${t.message}", t) } }
                        }
                        return
                    }
                    ACTION_SPOOF_GOJEK -> {
                        val active = intent.getBooleanExtra("active", false); val forceOff = intent.getBooleanExtra("force_off", false)
                        if (currentPackage == GojekHookEntry.TARGET_PACKAGE) {
                            if (forceOff || !active) {
                                isSpoofActive = false; perAppSpoofOverride = null; perAppOverrideLat = null; perAppOverrideLng = null
                                try { SpoofConsistencyGuard.cancelTransition() } catch (_: Throwable) {}
                                clearOverrideCoords(); applyPerAppSpoofOverride(false)
                                // ⭐ FIX: Hide Overlay
                                try { com.gpm.virtual.xposed.overlay.SortirOverlayManager.hideOverlay() } catch (_: Throwable) {}
                                Handler(Looper.getMainLooper()).post { try { forceRealLocationRefresh() } catch (_: Throwable) {} }
                                try { val ctx2 = sAppContext ?: context; if (ctx2 != null) com.gpm.virtual.xposed.overlay.HookOverlayManager.hideStatusOverlay(ctx2) } catch (_: Throwable) {}
                                return
                            }
                            val hasOverride = intent.getBooleanExtra("override", false); val lat = intent.getDoubleExtra("latitude", Double.NaN); val lng = intent.getDoubleExtra("longitude", Double.NaN)
                            val targetLat: Double; val targetLng: Double
                            if (hasOverride && !lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0) { targetLat = lat; targetLng = lng } else { targetLat = spoofLat; targetLng = spoofLng }
                            val fromLat = if (realLat != 0.0) realLat else targetLat; val fromLng = if (realLng != 0.0) realLng else targetLng
                            val distM = SpoofConsistencyGuard.haversineMeters(fromLat, fromLng, targetLat, targetLng)
                            if (SpoofConsistencyGuard.beginTransition(fromLat, fromLng, targetLat, targetLng)) {}
                            if (hasOverride && !lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0) setOverrideCoords(lat, lng)
                            applyPerAppSpoofOverride(true, fromBroadcast = true); isSpoofActive = true; SpoofConsistencyGuard.markSpoofActivated()
                            val effLat = effectiveSpoofLat(); val effLng = effectiveSpoofLng()
                            if (effLat != 0.0 && effLng != 0.0) dispatchLocationToListeners(effLat, effLng, spoofAccuracy)
                            val ctx2 = sAppContext ?: context
                            if (ctx2 != null) { try { com.gpm.virtual.xposed.overlay.HookOverlayManager.showStatusOverlay(ctx2, "Spoof Gojek: %.5f, %.5f".format(effectiveSpoofLat(), effectiveSpoofLng())) } catch (_: Throwable) {} }
                        }
                        return
                    }
                    ACTION_SPOOF_GRAB -> {
                        val active = intent.getBooleanExtra("active", false); val forceOff = intent.getBooleanExtra("force_off", false)
                        if (GrabHookEntry.isTargetPackage(currentPackage)) {
                            if (forceOff || !active) {
                                isSpoofActive = false; perAppSpoofOverride = null; perAppOverrideLat = null; perAppOverrideLng = null
                                try { SpoofConsistencyGuard.cancelTransition() } catch (_: Throwable) {}
                                clearOverrideCoords(); applyPerAppSpoofOverride(false)
                                // ⭐ FIX: Hide Overlay
                                try { com.gpm.virtual.xposed.overlay.SortirOverlayManager.hideOverlay() } catch (_: Throwable) {}
                                Handler(Looper.getMainLooper()).post { try { forceRealLocationRefresh() } catch (_: Throwable) {} }
                                try { val ctx2 = sAppContext ?: context; if (ctx2 != null) com.gpm.virtual.xposed.overlay.HookOverlayManager.hideStatusOverlay(ctx2) } catch (_: Throwable) {}
                                return
                            }
                            val hasOverride = intent.getBooleanExtra("override", false); val lat = intent.getDoubleExtra("latitude", Double.NaN); val lng = intent.getDoubleExtra("longitude", Double.NaN)
                            val targetLat: Double; val targetLng: Double
                            if (hasOverride && !lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0) { targetLat = lat; targetLng = lng } else { targetLat = spoofLat; targetLng = spoofLng }
                            val fromLat = if (realLat != 0.0) realLat else targetLat; val fromLng = if (realLng != 0.0) realLng else targetLng
                            val distM = SpoofConsistencyGuard.haversineMeters(fromLat, fromLng, targetLat, targetLng)
                            if (SpoofConsistencyGuard.beginTransition(fromLat, fromLng, targetLat, targetLng)) {}
                            if (hasOverride && !lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0) setOverrideCoords(lat, lng)
                            applyPerAppSpoofOverride(true, fromBroadcast = true); isSpoofActive = true; SpoofConsistencyGuard.markSpoofActivated()
                            val effLat = effectiveSpoofLat(); val effLng = effectiveSpoofLng()
                            if (effLat != 0.0 && effLng != 0.0) dispatchLocationToListeners(effLat, effLng, spoofAccuracy)
                            val ctx2 = sAppContext ?: context
                            if (ctx2 != null) { try { com.gpm.virtual.xposed.overlay.HookOverlayManager.showStatusOverlay(ctx2, "Spoof Grab: %.5f, %.5f".format(effectiveSpoofLat(), effectiveSpoofLng())) } catch (_: Throwable) {} }
                        }
                        return
                    }
                    ACTION_CLEAR_OVERRIDE -> { clearOverrideCoords(); clearPerAppSpoofOverride(); logI("[Override] Cleared via broadcast"); return }
                    ACTION_FORCE_SYNC, HotReloadActions.ACTION_CONFIG_UPDATED, HotReloadActions.ACTION_HOT_RELOAD -> {
                        if (intent.hasExtra(HotReloadActions.EXTRA_HOOK_ENABLED)) isHookActive = intent.getBooleanExtra(HotReloadActions.EXTRA_HOOK_ENABLED, isHookActive)
                        if (intent.hasExtra(HotReloadActions.EXTRA_SPOOF_ACTIVE)) isSpoofActive = intent.getBooleanExtra(HotReloadActions.EXTRA_SPOOF_ACTIVE, isSpoofActive)
                        if (intent.hasExtra(HotReloadActions.EXTRA_ANTI_MOCK)) isAntiMockEnabled = intent.getBooleanExtra(HotReloadActions.EXTRA_ANTI_MOCK, isAntiMockEnabled)
                        if (intent.hasExtra(HotReloadActions.EXTRA_CONN_STABILITY)) isConnectionStabilityEnabled = intent.getBooleanExtra(HotReloadActions.EXTRA_CONN_STABILITY, isConnectionStabilityEnabled)
                        if (intent.hasExtra(HotReloadActions.EXTRA_OKHTTP_STABILITY)) isOkHttpStabilityEnabled = intent.getBooleanExtra(HotReloadActions.EXTRA_OKHTTP_STABILITY, isOkHttpStabilityEnabled)
                        if (intent.hasExtra(HotReloadActions.EXTRA_FAST_TCP)) isFastTcpEnabled = intent.getBooleanExtra(HotReloadActions.EXTRA_FAST_TCP, isFastTcpEnabled)
                        if (intent.hasExtra(HotReloadActions.EXTRA_FASTEST_CAPTURE)) isFastestCaptureEnabled = intent.getBooleanExtra(HotReloadActions.EXTRA_FASTEST_CAPTURE, isFastestCaptureEnabled)
                        if (intent.hasExtra(HotReloadActions.EXTRA_GCM_SUPPORT)) isGcmSupportEnabled = intent.getBooleanExtra(HotReloadActions.EXTRA_GCM_SUPPORT, isGcmSupportEnabled)
                        if (intent.hasExtra(HotReloadActions.EXTRA_VIRTUAL)) useVirtual = intent.getBooleanExtra(HotReloadActions.EXTRA_VIRTUAL, useVirtual)
                        if (intent.hasExtra(HotReloadActions.EXTRA_VIRTUAL_RACING)) useVirtualRacing = intent.getBooleanExtra(HotReloadActions.EXTRA_VIRTUAL_RACING, useVirtualRacing)
                        if (intent.hasExtra(HotReloadActions.EXTRA_USE_RANDOM)) useRandom = intent.getBooleanExtra(HotReloadActions.EXTRA_USE_RANDOM, useRandom)
                        if (intent.hasExtra(HotReloadActions.EXTRA_RANDOM_LOCATION)) isRandomLocEnabled = intent.getBooleanExtra(HotReloadActions.EXTRA_RANDOM_LOCATION, isRandomLocEnabled)
                        if (intent.hasExtra(HotReloadActions.EXTRA_RANDOM_RADIUS)) randomRadius = intent.getFloatExtra(HotReloadActions.EXTRA_RANDOM_RADIUS, randomRadius)
                        if (intent.hasExtra(HotReloadActions.EXTRA_RANDOM_ACCURACY)) isRandomAccEnabled = intent.getBooleanExtra(HotReloadActions.EXTRA_RANDOM_ACCURACY, isRandomAccEnabled)
                        if (intent.hasExtra(HotReloadActions.EXTRA_LOCATION_SHARING)) isLocationSharingActive = intent.getBooleanExtra(HotReloadActions.EXTRA_LOCATION_SHARING, isLocationSharingActive)
                        if (intent.hasExtra(HotReloadActions.EXTRA_SPEED)) spoofSpeed = intent.getFloatExtra(HotReloadActions.EXTRA_SPEED, spoofSpeed)
                        if (intent.hasExtra(HotReloadActions.EXTRA_BEARING)) spoofBearing = intent.getFloatExtra(HotReloadActions.EXTRA_BEARING, spoofBearing)
                        if (intent.hasExtra(HotReloadActions.EXTRA_ACCURACY)) spoofAccuracy = intent.getFloatExtra(HotReloadActions.EXTRA_ACCURACY, spoofAccuracy)
                        if (intent.hasExtra(HotReloadActions.EXTRA_LATITUDE)) {
                            val lat = intent.getDoubleExtra(HotReloadActions.EXTRA_LATITUDE, Double.NaN); val lng = intent.getDoubleExtra(HotReloadActions.EXTRA_LONGITUDE, Double.NaN)
                            if (!lat.isNaN() && !lng.isNaN() && lat in -90.0..90.0 && lng in -180.0..180.0) { spoofLat = lat; spoofLng = lng }
                        }
                        
                       // ⭐ PERBAIKAN: Arahkan ke CancelCantikHook (karena GojekOkHttpGuard sudah dihapus)
                        if (intent.hasExtra("cancel_cantik_enabled")) {
                            val enabled = intent.getBooleanExtra("cancel_cantik_enabled", false)
                            try { CancelCantikHook.onEnabledStateReceived(enabled) } catch (_: Throwable) {}
                        }
                        
                        invalidateSpoofCache()
                        try { refreshConfig(sAppContext ?: context, force = true) } catch (_: Throwable) {}
                        if (!isTargetSpoofApp(currentPackage)) { if (!isSpoofActive) { try { forceRealLocationRefresh() } catch (_: Throwable) {} } }
                        return
                    }
                    ACTION_SHOW_OVERLAY -> {
                        val text = intent.getStringExtra("text") ?: "GPM Active"; val ctx2 = sAppContext ?: context
                        if (ctx2 != null) { try { com.gpm.virtual.xposed.overlay.HookOverlayManager.showStatusOverlay(ctx2, text); logI("[Overlay] Shown: $text") } catch (_: Throwable) {} }
                        return
                    }
                    ACTION_UPDATE_OVERLAY -> {
                        val text = intent.getStringExtra("text") ?: return
                        try { com.gpm.virtual.xposed.overlay.HookOverlayManager.updateStatusOverlay(text); logI("[Overlay] Updated: $text") } catch (_: Throwable) {}
                        return
                    }
                    ACTION_HIDE_OVERLAY -> {
                        val ctx2 = sAppContext ?: context
                        if (ctx2 != null) { try { com.gpm.virtual.xposed.overlay.HookOverlayManager.hideStatusOverlay(ctx2); logI("[Overlay] Hidden") } catch (_: Throwable) {} }
                        return
                    }
                }

                val isTargetSpoof = isTargetSpoofApp(currentPackage)
                if (intent.hasExtra("is_hook_enabled")) isHookActive = intent.getBooleanExtra("is_hook_enabled", isHookActive)
                if (!isTargetSpoof) {
                    if (intent.hasExtra("is_spoof_active")) isSpoofActive = intent.getBooleanExtra("is_spoof_active", isSpoofActive)
                    if (intent.hasExtra("latitude")) spoofLat = intent.getDoubleExtra("latitude", spoofLat)
                    if (intent.hasExtra("longitude")) spoofLng = intent.getDoubleExtra("longitude", spoofLng)
                } else {
                    if (isLocationSharingActive && intent.hasExtra("location_sharing_lat") && intent.hasExtra("location_sharing_lng")) {
                        val sLat = intent.getDoubleExtra("location_sharing_lat", Double.NaN); val sLng = intent.getDoubleExtra("location_sharing_lng", Double.NaN)
                        if (!sLat.isNaN() && !sLng.isNaN() && sLat in -90.0..90.0 && sLng in -180.0..180.0) { spoofLat = sLat; spoofLng = sLng }
                    }
                }
                if (intent.hasExtra("accuracy")) spoofAccuracy = intent.getFloatExtra("accuracy", spoofAccuracy)
                if (intent.hasExtra("random_location")) isRandomLocEnabled = intent.getBooleanExtra("random_location", isRandomLocEnabled)
                if (intent.hasExtra("random_radius")) randomRadius = intent.getFloatExtra("random_radius", randomRadius)
                if (intent.hasExtra("anti_mock")) isAntiMockEnabled = intent.getBooleanExtra("anti_mock", isAntiMockEnabled)
                if (intent.hasExtra("connection_stability_enabled")) isConnectionStabilityEnabled = intent.getBooleanExtra("connection_stability_enabled", isConnectionStabilityEnabled)
                if (intent.hasExtra("okhttp_stability_enabled")) isOkHttpStabilityEnabled = intent.getBooleanExtra("okhttp_stability_enabled", isOkHttpStabilityEnabled)
                if (intent.hasExtra("fast_tcp_enabled")) isFastTcpEnabled = intent.getBooleanExtra("fast_tcp_enabled", isFastTcpEnabled)
                if (intent.hasExtra("fastest_capture_mode_enabled")) isFastestCaptureEnabled = intent.getBooleanExtra("fastest_capture_mode_enabled", isFastestCaptureEnabled)
                if (intent.hasExtra("gcm_support_enabled")) isGcmSupportEnabled = intent.getBooleanExtra("gcm_support_enabled", isGcmSupportEnabled)
                if (intent.hasExtra("location_sharing") || intent.hasExtra("is_location_sharing_enabled")) {
                    isLocationSharingActive = intent.getBooleanExtra("location_sharing", intent.getBooleanExtra("is_location_sharing_enabled", isLocationSharingActive))
                }
                if (intent.hasExtra("use_virtual")) useVirtual = intent.getBooleanExtra("use_virtual", useVirtual)
                if (intent.hasExtra("use_virtual_racing")) useVirtualRacing = intent.getBooleanExtra("use_virtual_racing", useVirtualRacing)
                if (intent.hasExtra("use_random")) useRandom = intent.getBooleanExtra("use_random", useRandom)
                if (intent.hasExtra("speed")) spoofSpeed = intent.getFloatExtra("speed", spoofSpeed)
                if (intent.hasExtra("bearing")) spoofBearing = intent.getFloatExtra("bearing", spoofBearing)
                if (intent.hasExtra("ping_offset_ms")) pingOffsetMs = intent.getLongExtra("ping_offset_ms", pingOffsetMs)
                if (intent.hasExtra("pingTimestamp") || intent.hasExtra("ping_timestamp")) {
                    val raw = if (intent.hasExtra("pingTimestamp")) intent.getLongExtra("pingTimestamp", 0L) else intent.getLongExtra("ping_timestamp", 0L)
                    spoofPingTimestamp = if (isValidEpoch(raw)) raw else 0L
                }
                if (intent.hasExtra("locationTimestamp") || intent.hasExtra("location_timestamp")) {
                    val raw = if (intent.hasExtra("locationTimestamp")) intent.getLongExtra("locationTimestamp", 0L) else intent.getLongExtra("location_timestamp", 0L)
                    spoofLocationTimestamp = if (isValidEpoch(raw)) raw else 0L
                }
                if (intent.hasExtra("accelerometerSpeed")) spoofAccelerometerSpeed = intent.getFloatExtra("accelerometerSpeed", spoofAccelerometerSpeed)
                if (intent.hasExtra("accelerometer_speed")) spoofAccelerometerSpeed = intent.getFloatExtra("accelerometer_speed", spoofAccelerometerSpeed)
                if (intent.hasExtra("accelerometerBearing")) spoofAccelerometerBearing = intent.getFloatExtra("accelerometerBearing", spoofAccelerometerBearing)
                if (intent.hasExtra("accelerometer_bearing")) spoofAccelerometerBearing = intent.getFloatExtra("accelerometer_bearing", spoofAccelerometerBearing)
                lastQueryTime = System.currentTimeMillis()
                invalidateSpoofCache()

                if (isSpoofActive) refreshSpoofCacheIfNeeded()

                val syncLat = if (isSpoofActive) { if (useVirtualRacing) RealisticGpsEngine.roundTo7Decimals(spoofCacheLat) else RealisticGpsEngine.roundTo6Decimals(spoofCacheLat) } else realLat
                val syncLng = if (isSpoofActive) { if (useVirtualRacing) RealisticGpsEngine.roundTo7Decimals(spoofCacheLng) else RealisticGpsEngine.roundTo6Decimals(spoofCacheLng) } else realLng
                val syncAcc = if (isSpoofActive) spoofCacheAcc else 3.0f
                val syncSpeed = if (isSpoofActive) spoofCacheSpeed else 0.0f
                val syncBearing = if (isSpoofActive) spoofCacheBearing else 0.0f
                val syncAlt = if (isSpoofActive) spoofCacheAlt else 15.4
                val syncAccSpeed = if (isSpoofActive) spoofCacheAccSpeed else 0.0f
                val syncAccBearing = if (isSpoofActive) spoofCacheAccBearing else 0.0f

                pingProvider(sAppContext ?: context, currentPackage)
                try {
                    val ack = Intent(ACTION_TARGET_APP_CONNECTED).apply {
                        putExtra("package_name", currentPackage); putExtra("framework", getFrameworkDescription())
                        putExtra("is_hook_enabled", isHookActive); putExtra("is_spoof_active", isSpoofActive)
                        putExtra("real_latitude", realLat); putExtra("real_longitude", realLng)
                        putExtra("latitude", syncLat); putExtra("longitude", syncLng)
                        putExtra("accuracy", syncAcc); putExtra("speed", syncSpeed); putExtra("bearing", syncBearing); putExtra("altitude", syncAlt)
                        putExtra("pingTimestamp", spoofCachePingTs); putExtra("locationTimestamp", spoofCacheLocTs)
                        putExtra("accelerometerSpeed", syncAccSpeed); putExtra("accelerometerBearing", syncAccBearing)
                        putExtra("timestamp", System.currentTimeMillis())
                    }
                    (context ?: sAppContext)?.sendBroadcast(ack, INTERNAL_PERMISSION)
                } catch (_: Throwable) {}

                if (shouldSpoof()) dispatchLocationToListeners(syncLat, syncLng, syncAcc)
            }
        }
    }

    private fun tryRegisterWithPermission(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, INTERNAL_PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter, INTERNAL_PERMISSION, null)
            }
            true
        } catch (t: Throwable) { false }
    }

    private fun registerPingReceiverFallback(ctx: Context) {
        if (receiverRegistered) return
        try {
            val filter = createPingFilter(); val receiver = createPingReceiver()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
            receiverRegistered = true
        } catch (t: Throwable) { receiverRegistered = false }
    }

    private fun isCallerTrusted(): Boolean {
        return try {
            val uid = android.os.Binder.getCallingUid()
            val ctx = sAppContext ?: return true
            val pkgs = ctx.packageManager.getPackagesForUid(uid) ?: return false
            for (p in pkgs) {
                if (p == "com.gojek.partner" || p == "com.grabtaxi.driver2" || p == "com.grab.driver" || p == "com.gpm.virtual" || p == "com.gpm.virtual.mchaji") return true
            }
            false
        } catch (_: Throwable) { true }
    }

    private fun dispatchLocationToListeners(lat: Double, lng: Double, acc: Float) {
        val ctx = sAppContext ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                refreshSpoofCacheIfNeeded()
                val curSpeed = spoofCacheSpeed; val curBearing = spoofCacheBearing
                val locTs = spoofCacheLocTs; val elapsedNanos = spoofCacheElapsedNanos
                val safeLat = if (useVirtualRacing) RealisticGpsEngine.roundTo7Decimals(spoofCacheLat) else RealisticGpsEngine.roundTo6Decimals(spoofCacheLat)
                val safeLng = if (useVirtualRacing) RealisticGpsEngine.roundTo7Decimals(spoofCacheLng) else RealisticGpsEngine.roundTo6Decimals(spoofCacheLng)
                val mockLoc = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = safeLat; longitude = safeLng; accuracy = spoofCacheAcc; altitude = spoofCacheAlt
                    bearing = curBearing; speed = curSpeed; time = locTs; elapsedRealtimeNanos = elapsedNanos
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = (accuracy * 0.75f).coerceAtLeast(1.0f); speedAccuracyMetersPerSecond = 0.05f; bearingAccuracyDegrees = 0.5f
                    }
                    val b = Bundle()
                    b.putFloat("speed", curSpeed); b.putFloat("bearing", curBearing); b.putBoolean("mockLocation", false); b.putBoolean("isMock", false)
                    b.putString("source", "gps"); b.putString("provider", "gps"); b.putInt("satellites", 12); b.putFloat("hdop", 0.8f)
                    b.putBoolean("location_sharing", isLocationSharingActive); b.putBoolean("is_location_sharing_enabled", isLocationSharingActive)
                    b.putDouble("location_sharing_lat", safeLat); b.putDouble("location_sharing_lng", safeLng)
                    b.putFloat("location_sharing_speed", curSpeed); b.putFloat("location_sharing_bearing", curBearing)
                    b.putLong("location_sharing_timestamp", locTs)
                    extras = b
                }
                if (isAntiMockEnabled) cleanMockFlags(mockLoc)
                val listeners = synchronized(activeLocationListeners) { activeLocationListeners.toList() }
                for (listener in listeners) {
                    try {
                        when (listener) {
                            is LocationListener -> listener.onLocationChanged(mockLoc)
                            is GmsLocationListener -> listener.onLocationChanged(mockLoc)
                            else -> dispatchToLocationCallback(listener, mockLoc)
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }, 20L)
    }

    private fun dispatchToLocationCallback(callback: Any, location: Location) {
        try {
            val cl = callback.javaClass.classLoader ?: return
            val locResultClass = ReflectUtil.findClassOrNull("com.google.android.gms.location.LocationResult", cl) ?: return
            val createMethod = ReflectUtil.findMethodExactOrNull(locResultClass, "create", List::class.java)
            val locResult = createMethod?.invoke(null, listOf(location)) ?: return
            for (m in ReflectUtil.findMethodsByName(callback.javaClass, "onLocationResult")) {
                if (m.parameterTypes.size != 1) continue
                val paramType = m.parameterTypes[0]
                val compatible = paramType.isAssignableFrom(locResultClass) || locResultClass.isAssignableFrom(paramType)
                if (!compatible) continue
                try { m.isAccessible = true; m.invoke(callback, locResult); return } catch (_: Throwable) { try { m.invoke(callback, location); return } catch (_: Throwable) {} }
            }
        } catch (_: Throwable) {}
    }

    fun getProcessContext(passedCtx: Context? = null): Context? {
        if (passedCtx != null) { sAppContext = passedCtx.applicationContext ?: passedCtx; return sAppContext }
        if (sAppContext != null) return sAppContext
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
            if (at != null) {
                try { (atClass.getMethod("currentApplication").invoke(null) as? Context)?.let { sAppContext = it; return it } } catch (_: Throwable) {}
                try { (atClass.getMethod("getSystemContext").invoke(at) as? Context)?.let { sAppContext = it; return it } } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return sAppContext
    }

    fun initSystemServer(classLoader: ClassLoader) { isSystemServer = false; logI("[LSPosed] System server hook completely bypassed (app-only mode)") }

    fun initPackage(packageName: String, classLoader: ClassLoader) {
        val isSubPackage = packageName == "com.google.android.webview" || packageName == "com.google.android.gms" || packageName == "android" || packageName == "system_server" || packageName.startsWith("android.") || packageName.startsWith("com.android.")
        if (isSubPackage) { if (currentProcessName.isNotEmpty() && !isModulePackage(currentProcessName)) { currentPackage = currentProcessName } } else { currentPackage = packageName }
        if (packageName == "android" || packageName == "system_server" || isSystemServer) return
        if (isModulePackage(packageName) || packageName == "com.google.android.gms") return
        try {
            val ctx = getProcessContext(null)
            if (ctx != null) {
                val cls = Class.forName("com.gpm.virtual.manager.SpoofLifecycleManager")
                val init = cls.getMethod("initForHookProcess", Context::class.java)
                init.invoke(null, ctx)
            }
        } catch (_: Throwable) {}

        SafeLogger.d(TAG, "init_$packageName", "[LSPosed] Connecting: $packageName...")
        seedPerAppOverrideFromPrefs()
        
        // ⭐ TAMBAHKAN BARIS INI: NotificationGuardHook
        safeHook("NotificationGuard") { NotificationGuardHook.install(module ?: return@safeHook, classLoader) }
        
        safeHook("LocationClass")      { hookLocationClass(classLoader) }
        safeHook("AntiMock")           { hookAntiMockDetections(classLoader) }
        safeHook("LocationManager")    { hookLocationManager(classLoader) }
        safeHook("GooglePlayServices") { hookGooglePlayServices(classLoader) }
        safeHook("WifiAndTelephony")   { hookWifiAndTelephony(classLoader) }
        safeHook("SensorManager")      { hookSensorManager(classLoader) }
        safeHook("ApplicationContext") { hookApplicationContext() }
        safeHook("ActivityLifecycle")  { hookActivityLifecycle(classLoader) }
        safeHook("Geolocation")        { hookGeolocation(classLoader) }
        safeHook("NmeaAndGnss")        { hookNmeaAndGnss(classLoader) }
        safeHook("LocationSharing")    { hookLocationSharingAndFirebase(classLoader) }
        if (GojekHookEntry.TARGET_PACKAGE == packageName || GrabHookEntry.isTargetPackage(packageName)) {
            safeHook("HookOverlay") { com.gpm.virtual.xposed.overlay.HookOverlayManager.install(module ?: return@safeHook, classLoader, packageName) }
        }
        val immediateCtx = getProcessContext(null)
        if (immediateCtx != null) setApplicationContext(immediateCtx)
        Thread(null, {
            var retries = 0
            while (retries < 25) {
                val ctx = sAppContext ?: getProcessContext(null)
                if (ctx != null) {
                    if (sAppContext == null) setApplicationContext(ctx)
                    try {
                        val handshake = Intent(ACTION_TARGET_APP_CONNECTED).apply {
                            putExtra("package_name", packageName); putExtra("framework", getFrameworkDescription()); putExtra("timestamp", System.currentTimeMillis())
                        }
                        ctx.sendBroadcast(handshake, INTERNAL_PERMISSION)
                    } catch (_: Throwable) {}
                    break
                }
                try { Thread.sleep(120) } catch (_: Throwable) {}
                retries++
            }
        }, "GPM-Init").start()
        refreshConfig(force = true)
        pingProvider()
    }

    private fun seedPerAppOverrideFromPrefs() {
        try {
            if (!lifecycleAllowsSpoof()) {
                isHookActive = true; isSpoofActive = false; perAppSpoofOverride = null; perAppOverrideLat = null; perAppOverrideLng = null
                return
            }
            val m = module ?: return
            val mgrState = try {
                val cls = Class.forName("com.gpm.virtual.manager.SpoofLifecycleManager")
                val getState = cls.getMethod("getState", android.content.Context::class.java)
                val ctx = sAppContext ?: getProcessContext(null) ?: return
                getState.invoke(null, ctx)
            } catch (_: Throwable) { null }
            if (mgrState == null) return
            val active = mgrState.javaClass.getMethod("getActive").invoke(mgrState) as? Boolean ?: false
            if (!active) { isHookActive = true; isSpoofActive = false; return }
            val gojekLat = mgrState.javaClass.getMethod("getGojekLat").invoke(mgrState) as? Double ?: 0.0
            val gojekLng = mgrState.javaClass.getMethod("getGojekLng").invoke(mgrState) as? Double ?: 0.0
            val grabLat = mgrState.javaClass.getMethod("getGrabLat").invoke(mgrState) as? Double ?: 0.0
            val grabLng = mgrState.javaClass.getMethod("getGrabLng").invoke(mgrState) as? Double ?: 0.0
            val (useLat, useLng) = when {
                currentPackage == GojekHookEntry.TARGET_PACKAGE && gojekLat != 0.0 && gojekLng != 0.0 -> gojekLat to gojekLng
                GrabHookEntry.isTargetPackage(currentPackage) && grabLat != 0.0 && grabLng != 0.0 -> grabLat to grabLng
                else -> 0.0 to 0.0
            }
            if (useLat != 0.0 && useLng != 0.0) {
                perAppOverrideLat = useLat; perAppOverrideLng = useLng; perAppSpoofOverride = true; isHookActive = true; isSpoofActive = true; invalidateSpoofCache()
                return
            }
            isHookActive = true; isSpoofActive = false
        } catch (_: Throwable) {}
    }

    private inline fun safeHook(section: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { logE("Hook section '$section' failed: ${t.message}", t) }
    }

    fun spoofLocationObject(location: Location?, context: Context? = null) {
        if (location == null) return
        if (isModulePackage(currentPackage)) return
        if (isSystemServer || currentPackage == "android") return
        val prov = location.provider
        if (prov != null && prov.equals("passive", ignoreCase = true)) return
        refreshConfig(context ?: sAppContext)
        if (!isHookActive) return

        if (isTargetSpoofApp(currentPackage)) {
            val perApp = getPerAppCoords(currentPackage)
            val perAppActive = isPerAppSpoofActive(currentPackage)
            if (perApp == null || !perAppActive) {
                if (!isGrabApp() && (location.accuracy <= 0f || location.accuracy > 8.0f)) location.accuracy = 4.5f
                if (isAntiMockEnabled) cleanMockFlags(location)
                realLat = location.latitude; realLng = location.longitude
                return
            }
        }
        if (!isSpoofActive) {
            realLat = location.latitude; realLng = location.longitude
            if (!isGrabApp() && (location.accuracy <= 0f || location.accuracy > 8.0f)) location.accuracy = 4.5f
            if (isAntiMockEnabled) cleanMockFlags(location)
            return
        }
        refreshSpoofCacheIfNeeded()
        if (isGrabApp()) { applyGrabStandard(location); if (isAntiMockEnabled) cleanMockFlags(location); return }
        val safeLat = if (useVirtualRacing) RealisticGpsEngine.roundTo7Decimals(spoofCacheLat) else RealisticGpsEngine.roundTo6Decimals(spoofCacheLat)
        val safeLng = if (useVirtualRacing) RealisticGpsEngine.roundTo7Decimals(spoofCacheLng) else RealisticGpsEngine.roundTo6Decimals(spoofCacheLng)

        location.latitude = safeLat; location.longitude = safeLng; location.accuracy = spoofCacheAcc; location.altitude = spoofCacheAlt
        location.bearing = spoofCacheBearing; location.speed = spoofCacheSpeed; location.time = spoofCacheLocTs; location.elapsedRealtimeNanos = spoofCacheElapsedNanos
        val effectiveProvider = when {
            location.provider?.equals("network", ignoreCase = true) == true -> "network"
            location.provider?.equals("fused", ignoreCase = true) == true -> "fused"
            else -> "gps"
        }
        location.provider = effectiveProvider
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                location.verticalAccuracyMeters = (spoofCacheAcc * 0.75f).coerceAtLeast(1.0f)
                location.speedAccuracyMetersPerSecond = 0.05f
                location.bearingAccuracyDegrees = 0.5f
            } catch (_: Throwable) {}
        }
        try {
            val extraBundle = location.extras ?: Bundle()
            extraBundle.putBoolean("mockLocation", false); extraBundle.putBoolean("isMock", false)
            extraBundle.putString("source", effectiveProvider); extraBundle.putString("provider", effectiveProvider)
            extraBundle.putInt("satellites", 12); extraBundle.putFloat("hdop", 0.8f)
            extraBundle.putFloat("speed", spoofCacheSpeed); extraBundle.putFloat("bearing", spoofCacheBearing)
            extraBundle.putBoolean("location_sharing", isLocationSharingActive); extraBundle.putBoolean("is_location_sharing_enabled", isLocationSharingActive)
            extraBundle.putDouble("location_sharing_lat", spoofCacheLat); extraBundle.putDouble("location_sharing_lng", spoofCacheLng)
            extraBundle.putFloat("location_sharing_speed", spoofCacheSpeed); extraBundle.putFloat("location_sharing_bearing", spoofCacheBearing)
            extraBundle.putLong("location_sharing_timestamp", spoofCacheLocTs)
            location.extras = extraBundle
        } catch (_: Throwable) {}
        if (isAntiMockEnabled) cleanMockFlags(location)
    }

    private fun cleanMockFlags(location: Location) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) locationSetMockMethod?.let { try { it.invoke(location, false) } catch (_: Throwable) {} }
            locationSetIsFromMockProviderMethod?.let { try { it.invoke(location, false) } catch (_: Throwable) {} }
            for (field in locationMockFlagFields) { try { field.setBoolean(location, false) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
    }

    private fun hookLocationClass(classLoader: ClassLoader) {
        try {
            val locationClass = ReflectUtil.findClass("android.location.Location", classLoader)
            ReflectUtil.findMethodExactOrNull(locationClass, "getLatitude")?.let { m ->
                hookMethod(m) { chain ->
                    if (isModulePackage(currentPackage)) return@hookMethod chain.proceed()
                    HookGuard.runGuarded({ chain.proceed() }) {
                        if (!tryRefreshConfigSafe()) return@runGuarded chain.proceed()
                        val loc = chain.thisObject as? Location ?: return@runGuarded chain.proceed()
                        if (!isSpoofActive) { realLat = loc.latitude; return@runGuarded chain.proceed() }
                        spoofLocationObject(loc, sAppContext); loc.latitude
                    }
                }
            }
            ReflectUtil.findMethodExactOrNull(locationClass, "getLongitude")?.let { m ->
                hookMethod(m) { chain ->
                    if (isModulePackage(currentPackage)) return@hookMethod chain.proceed()
                    HookGuard.runGuarded({ chain.proceed() }) {
                        if (!tryRefreshConfigSafe()) return@runGuarded chain.proceed()
                        val loc = chain.thisObject as? Location ?: return@runGuarded chain.proceed()
                        if (!isSpoofActive) { realLng = loc.longitude; return@runGuarded chain.proceed() }
                        spoofLocationObject(loc, sAppContext); loc.longitude
                    }
                }
            }
            ReflectUtil.findMethodExactOrNull(locationClass, "getAccuracy")?.let { m ->
                hookMethod(m) { chain ->
                    if (isModulePackage(currentPackage)) return@hookMethod chain.proceed()
                    if (HookGuard.isGuarded()) return@hookMethod chain.proceed()
                    HookGuard.runGuarded({ chain.proceed() }) {
                        if (!tryRefreshConfigSafe()) return@runGuarded chain.proceed()
                        val loc = chain.thisObject as? Location ?: return@runGuarded chain.proceed()
                        if (isSpoofActive) {
                            if (isGrabApp()) { applyGrabStandard(loc); loc.accuracy } else spoofAccuracy.coerceIn(2.0f, 15.0f)
                        } else {
                            val rawAcc = chain.proceed() as? Float ?: 0f
                            if (rawAcc <= 0f || rawAcc > 8.0f) 4.5f else rawAcc
                        }
                    }
                }
            }
            ReflectUtil.findMethodExactOrNull(locationClass, "hasAccuracy")?.let { m -> hookMethod(m) { chain -> if (isHookActive) true else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "getAltitude")?.let { m -> hookMethod(m) { chain -> if (isSpoofActive) RealisticGpsEngine.computeRealisticAltitude() else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "getSpeed")?.let { m -> hookMethod(m) { chain -> if (isSpoofActive) spoofCacheSpeed.coerceIn(0.0f, 1.5f) else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "hasSpeed")?.let { m -> hookMethod(m) { chain -> if (isHookActive) true else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "getBearing")?.let { m -> hookMethod(m) { chain -> if (isSpoofActive) RealisticGpsEngine.computeRealisticBearing() else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "hasBearing")?.let { m -> hookMethod(m) { chain -> if (isHookActive) true else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "getTime")?.let { m ->
                hookMethod(m) { chain ->
                    if (!isSpoofActive) return@hookMethod chain.proceed()
                    if (spoofLocationTimestamp > 0L && Math.abs(System.currentTimeMillis() - spoofLocationTimestamp) < 10_000L) spoofLocationTimestamp else System.currentTimeMillis()
                }
            }
            ReflectUtil.findMethodExactOrNull(locationClass, "getElapsedRealtimeNanos")?.let { m ->
                hookMethod(m) { chain -> if (!isSpoofActive) chain.proceed() else if (spoofElapsedRealtimeNanos > 0L) spoofElapsedRealtimeNanos else SystemClock.elapsedRealtimeNanos() }
            }
            ReflectUtil.findMethodExactOrNull(locationClass, "isFromMockProvider")?.let { m -> hookMethod(m) { chain -> if (isAntiMockEnabled && !isModulePackage(currentPackage)) false else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "isMock")?.let { m -> hookMethod(m) { chain -> if (isAntiMockEnabled && !isModulePackage(currentPackage)) false else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "setIsFromMockProvider", Boolean::class.javaPrimitiveType!!)?.let { m -> hookMethod(m) { chain -> if (isAntiMockEnabled && !isModulePackage(currentPackage)) chain.proceed(arrayOf(false)) else chain.proceed() } }
            ReflectUtil.findMethodExactOrNull(locationClass, "setMock", Boolean::class.javaPrimitiveType!!)?.let { m -> hookMethod(m) { chain -> if (isAntiMockEnabled && !isModulePackage(currentPackage)) chain.proceed(arrayOf(false)) else chain.proceed() } }
        } catch (t: Throwable) { logE("Error hooking Location class: ${t.message}", t) }
    }

    private fun hookLocationManager(classLoader: ClassLoader) {
        try {
            val lmClass = ReflectUtil.findClass("android.location.LocationManager", classLoader)
            ReflectUtil.findMethodExactOrNull(lmClass, "getLastKnownLocation", String::class.java)?.let { m ->
                hookMethod(m) { chain ->
                    val requestedProvider = (chain.getArg(0) as? String)?.lowercase()
                    if (requestedProvider == "passive") return@hookMethod chain.proceed()
                    val ctx = (ReflectUtil.getObjectField(chain.thisObject, "mContext") as? Context) ?: sAppContext
                    if (sAppContext == null && ctx != null) setApplicationContext(ctx)
                    refreshConfig(ctx)
                    val result = chain.proceed() as? Location
                    if (isModulePackage(currentPackage)) return@hookMethod result
                    if (!isHookActive) return@hookMethod result
                    if (!isSpoofActive) {
                        if (result != null) {
                            if (!isGrabApp() && (result.accuracy <= 0f || result.accuracy > 8.0f)) result.accuracy = 4.5f
                            if (isAntiMockEnabled) cleanMockFlags(result)
                        }
                        return@hookMethod result
                    }
                    val loc = result ?: Location("gps")
                    HookGuard.runGuarded({}) { spoofLocationObject(loc, ctx) }
                    loc
                }
            }
            for (m in ReflectUtil.findMethodsByName(lmClass, "isProviderEnabled")) {
                hookMethod(m) { chain ->
                    val provider = (chain.getArg(0) as? String)?.lowercase()
                    if (isHookActive && !isModulePackage(currentPackage) && (provider == "gps" || provider == "network" || provider == "fused")) return@hookMethod true
                    chain.proceed()
                }
            }
            ReflectUtil.findMethodExactOrNull(lmClass, "isLocationEnabled")?.let { m -> hookMethod(m) { chain -> if (isHookActive && !isModulePackage(currentPackage)) true else chain.proceed() } }
            hookRequestLocationUpdates(lmClass)
            hookRemoveUpdates(lmClass)
            val transportClass = ReflectUtil.findClassOrNull("android.location.LocationManager\$LocationListenerTransport", classLoader)
            if (transportClass != null) {
                for (m in ReflectUtil.findMethodsByName(transportClass, "onLocationChanged")) {
                    hookMethod(m) { chain ->
                        refreshConfig(sAppContext)
                        if (isHookActive && !isModulePackage(currentPackage)) {
                            if (isSpoofActive) spoofArgsLocations(chain.args) else ensureHighAccuracyArgs(chain.args)
                        }
                        chain.proceed()
                    }
                }
            }
        } catch (t: Throwable) { logE("Error hooking LocationManager: ${t.message}", t) }
    }

    private fun hookRequestLocationUpdates(locationManagerClass: Class<*>) {
        for (m in ReflectUtil.findMethodsByName(locationManagerClass, "requestLocationUpdates")) {
            val listenerIndex = m.parameterTypes.indexOfFirst { LocationListener::class.java.isAssignableFrom(it) }
            if (listenerIndex == -1) continue
            val paramTypes = m.parameterTypes
            hookMethod(m) { chain ->
                val ctx = (ReflectUtil.getObjectField(chain.thisObject, "mContext") as? Context) ?: sAppContext
                if (sAppContext == null && ctx != null) setApplicationContext(ctx)
                val providerArg = (chain.args.firstOrNull() as? String)?.lowercase()
                if (providerArg == "passive") return@hookMethod chain.proceed()
                var modified = false; val newArgs = chain.args.toTypedArray()
                if (isHookActive && !isModulePackage(currentPackage)) {
                    val targetInterval = if (isFastestCaptureActive()) FASTEST_CAPTURE_INTERVAL_MS else HIGH_PRIORITY_INTERVAL_MS
                    for (i in paramTypes.indices) {
                        val pType = paramTypes[i]; val arg = newArgs[i]
                        when {
                            pType == Long::class.javaPrimitiveType && i >= 1 && (i == 1 || paramTypes[0] != String::class.java) -> { newArgs[i] = targetInterval; modified = true }
                            pType == Float::class.javaPrimitiveType && paramTypes[0] == String::class.java && i == 2 -> { newArgs[i] = 0.0f; modified = true }
                            arg != null && arg.javaClass.name.contains("LocationRequest") -> sanitizeLocationRequest(arg)
                        }
                    }
                }
                val listener = chain.getArg(listenerIndex) as? LocationListener
                if (listener != null) {
                    hookListenerClass(listener.javaClass)
                    synchronized(activeLocationListeners) { activeLocationListeners.add(listener) }
                }
                if (modified) chain.proceed(newArgs) else chain.proceed()
            }
        }
    }

    private fun hookRemoveUpdates(locationManagerClass: Class<*>) {
        for (m in ReflectUtil.findMethodsByName(locationManagerClass, "removeUpdates")) {
            hookMethod(m) { chain -> for (arg in chain.args) if (arg != null) synchronized(activeLocationListeners) { activeLocationListeners.remove(arg) }; chain.proceed() }
        }
    }

    private fun hookListenerClass(clazz: Class<*>) {
        if (hookedListenerClasses.contains(clazz)) return
        hookedListenerClasses.add(clazz)
        for (m in ReflectUtil.findMethodsByName(clazz, "onLocationChanged")) {
            hookMethod(m) { chain ->
                refreshConfig(sAppContext)
                if (isHookActive && !isModulePackage(currentPackage)) { if (isSpoofActive) spoofArgsLocations(chain.args) else ensureHighAccuracyArgs(chain.args) }
                chain.proceed()
            }
        }
    }

    private fun hookGooglePlayServices(classLoader: ClassLoader) {
        hookGmsLocationResult(classLoader)
        hookGmsLocationAvailability(classLoader)
        hookGmsLocationServicesProvider(classLoader)
        hookGmsFusedClientConstructor(classLoader)
        hookGmsLocationCallbackBase(classLoader)
        hookGmsLocationListenerBase(classLoader)
        hookGmsTaskCompletion(classLoader)
        hookGmsLocationRequest(classLoader)
    }

    // ⭐ HIGH PRIORITY & HIGH ACCURACY SANITIZER
    private fun sanitizeLocationRequest(req: Any?) {
        if (req == null) return
        try {
            val interval = if (isFastestCaptureActive()) FASTEST_CAPTURE_INTERVAL_MS else HIGH_PRIORITY_INTERVAL_MS
            val fastest = if (isFastestCaptureActive()) FASTEST_CAPTURE_FASTEST_INTERVAL_MS else HIGH_PRIORITY_FASTEST_INTERVAL_MS
            val cls = req.javaClass
            
            fun tryInvoke(methodName: String, vararg args: Any?) {
                try { 
                    val m = cls.methods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }
                    if (m != null) { m.isAccessible = true; m.invoke(req, *args) } 
                } catch (_: Throwable) {}
            }
            
            // Memaksa PRIORITY_HIGH_ACCURACY (100)
            tryInvoke("setPriority", 100) 
            tryInvoke("setQuality", 100) 
            tryInvoke("setInterval", interval)
            tryInvoke("setIntervalMillis", interval)
            tryInvoke("setFastestInterval", fastest)
            tryInvoke("setMinUpdateIntervalMillis", fastest)
            tryInvoke("setMaxWaitTime", 0L)
            tryInvoke("setMaxUpdateDelayMillis", 0L)
            
            // Memaksa selalu update (High Priority)
            tryInvoke("setSmallestDisplacement", 0.0f)
            tryInvoke("setMinUpdateDistanceMeters", 0.0f)
        } catch (_: Throwable) {}
    }

    private fun hookGmsLocationRequest(classLoader: ClassLoader) {
    val reqCls = ReflectUtil.findClassOrNull(
        "com.google.android.gms.location.LocationRequest", 
        classLoader
    ) ?: return

    // ⭐ HANYA hook getPriority untuk "menandai" request sebagai high accuracy
    // TAPI TIDAK override setPriority / setInterval / create
    // Alasan: Heatmap Grab pakai interval 30-60s dan priority BALANCED.
    // Kalau dipaksa 100ms/HIGH_ACCURACY, server Grab tolak kirim heatmap.
    ReflectUtil.findMethodExactOrNull(reqCls, "getPriority")?.let { m ->
        hookMethod(m) { chain ->
            if (isHookActive && !isModulePackage(currentPackage)) 100
            else chain.proceed()
        }
    }
    // ═══ SEMUA HOOK DI BAWAH INI SUDAH DIHAPUS ═══
    // - hook setPriority()     → dihapus (biarkan Grab pakai aslinya)
    // - hook setInterval()     → dihapus (biar heatmap pakai interval aslinya)
    // - hook create()          → dihapus (biar LocationRequest asli tidak dimodifikasi)
    // - sanitizeLocationRequest → tidak dipanggil (biar request utuh)
}

    private fun hookGmsLocationResult(classLoader: ClassLoader) {
        val cls = ReflectUtil.findClassOrNull("com.google.android.gms.location.LocationResult", classLoader) ?: return
        ReflectUtil.findMethodExactOrNull(cls, "getLastLocation")?.let { m ->
            hookMethod(m) { chain -> val loc = chain.proceed() as? Location; if (loc != null && isHookActive && isSpoofActive && !isModulePackage(currentPackage)) HookGuard.runGuarded({}) { spoofLocationObject(loc, sAppContext) }; loc }
        }
        ReflectUtil.findMethodExactOrNull(cls, "getLocations")?.let { m ->
            hookMethod(m) { chain ->
                @Suppress("UNCHECKED_CAST") val list = chain.proceed() as? List<Location>
                if (list != null && isHookActive && isSpoofActive && !isModulePackage(currentPackage)) HookGuard.runGuarded({}) { for (l in list) spoofLocationObject(l, sAppContext) }
                list
            }
        }
        for (m in ReflectUtil.findMethodsByName(cls, "create")) {
            hookMethod(m) { chain -> if (isHookActive && isSpoofActive && !isModulePackage(currentPackage)) HookGuard.runGuarded({}) { spoofArgsLocations(chain.args) }; chain.proceed() }
        }
    }

    private fun hookGmsLocationAvailability(classLoader: ClassLoader) {
        val cls = ReflectUtil.findClassOrNull("com.google.android.gms.location.LocationAvailability", classLoader) ?: return
        ReflectUtil.findMethodExactOrNull(cls, "isLocationAvailable")?.let { m -> hookMethod(m) { chain -> if (isHookActive && !isModulePackage(currentPackage)) true else chain.proceed() } }
    }

    private fun hookGmsLocationServicesProvider(classLoader: ClassLoader) {
        val cls = ReflectUtil.findClassOrNull("com.google.android.gms.location.LocationServices", classLoader) ?: return
        for (m in ReflectUtil.findMethodsByName(cls, "getFusedLocationProviderClient")) {
            hookMethod(m) { chain -> val result = chain.proceed(); try { if (result != null) hookConcreteFusedClientClass(result.javaClass) } catch (_: Throwable) {}; result }
        }
    }

    private fun hookGmsFusedClientConstructor(classLoader: ClassLoader) {
        val base = ReflectUtil.findClassOrNull("com.google.android.gms.location.FusedLocationProviderClient", classLoader) ?: return
        for (ctor in base.declaredConstructors) {
            if (Modifier.isAbstract(ctor.modifiers)) continue
            hookMethod(ctor) { chain ->
                val result = chain.proceed()
                try { val instance = chain.thisObject; if (instance != null) { val concrete = instance.javaClass; if (concrete != base && concrete.name.startsWith("com.google.android.gms")) hookConcreteFusedClientClass(concrete) } } catch (_: Throwable) {}
                result
            }
        }
    }

    private fun hookConcreteFusedClientClass(clazz: Class<*>) {
        if (clazz.name == "com.google.android.gms.location.FusedLocationProviderClient") return
        if (!clazz.name.startsWith("com.google.android.gms")) return
        if (!hookedFusedConcreteClasses.add(clazz)) return

        for (m in ReflectUtil.findMethodsByName(clazz, "requestLocationUpdates")) {
            hookMethod(m) { chain ->
                if (isHookActive && !isModulePackage(currentPackage)) { for (arg in chain.args) { if (arg != null && arg.javaClass.name.contains("LocationRequest")) sanitizeLocationRequest(arg) } }
                for (arg in chain.args) {
                    if (arg == null) continue
                    val cn = arg.javaClass.name
                    when {
                        cn.contains("LocationCallback") -> { hookLocationCallbackClass(arg.javaClass); synchronized(activeLocationListeners) { activeLocationListeners.add(arg) } }
                        cn.contains("LocationListener") -> { hookGmsListenerClass(arg.javaClass); synchronized(activeLocationListeners) { activeLocationListeners.add(arg) } }
                    }
                }
                chain.proceed()
            }
        }
        for (methodName in arrayOf("getLastLocation", "getCurrentLocation")) {
            for (m in ReflectUtil.findMethodsByName(clazz, methodName)) {
                hookMethod(m) { chain -> val task = chain.proceed(); try { if (task != null) hookConcreteTaskClass(task.javaClass) } catch (_: Throwable) {}; task }
            }
        }
    }

    private fun hookGmsLocationCallbackBase(classLoader: ClassLoader) {
        val base = ReflectUtil.findClassOrNull("com.google.android.gms.location.LocationCallback", classLoader) ?: return
        hookLocationCallbackClass(base)
    }

    private fun hookLocationCallbackClass(clazz: Class<*>) {
        if (hookedCallbackClasses.contains(clazz)) return
        hookedCallbackClasses.add(clazz)
        for (m in ReflectUtil.findMethodsByName(clazz, "onLocationResult")) {
            hookMethod(m) { chain ->
                refreshConfig(sAppContext)
                if (isHookActive && isSpoofActive && !isModulePackage(currentPackage)) HookGuard.runGuarded({}) { spoofLocationResultArg(chain.args.firstOrNull()) }
                chain.proceed()
            }
        }
    }

    private fun spoofLocationResultArg(result: Any?) {
        if (result == null) return
        try { val list = ReflectUtil.callMethod(result, "getLocations") as? List<*>; if (list != null) { for (item in list) { if (item is Location) spoofLocationObject(item, sAppContext) } } } catch (_: Throwable) {}
    }

    private fun hookGmsLocationListenerBase(classLoader: ClassLoader) {
        val base = ReflectUtil.findClassOrNull("com.google.android.gms.location.LocationListener", classLoader) ?: return
        hookGmsListenerClass(base)
    }

    private fun hookGmsListenerClass(clazz: Class<*>) {
        if (hookedListenerClasses.contains(clazz)) return
        hookedListenerClasses.add(clazz)
        for (m in ReflectUtil.findMethodsByName(clazz, "onLocationChanged")) {
            hookMethod(m) { chain ->
                refreshConfig(sAppContext)
                if (isHookActive && !isModulePackage(currentPackage)) HookGuard.runGuarded({}) { if (isSpoofActive) spoofArgsLocations(chain.args) else ensureHighAccuracyArgs(chain.args) }
                chain.proceed()
            }
        }
    }

    private fun hookGmsTaskCompletion(classLoader: ClassLoader) {
        val taskClass = ReflectUtil.findClassOrNull("com.google.android.gms.tasks.Task", classLoader) ?: return
        hookConcreteTaskClass(taskClass)
    }

    private fun hookConcreteTaskClass(clazz: Class<*>) {
        if (clazz.name == "com.google.android.gms.tasks.Task") return
        if (!hookedGmsTaskClasses.add(clazz)) return
        for (m in ReflectUtil.findMethodsByName(clazz, "getResult")) {
            hookMethod(m) { chain ->
                val result = chain.proceed()
                if (result != null && isHookActive && isSpoofActive && !isModulePackage(currentPackage)) {
                    HookGuard.runGuarded({}) { when { result is Location -> spoofLocationObject(result, sAppContext); result.javaClass.name.contains("LocationResult") -> spoofLocationResultObject(result) } }
                }
                result
            }
        }
    }

    private fun spoofLocationResultObject(locationResult: Any) {
        try { val list = ReflectUtil.callMethod(locationResult, "getLocations") as? List<*>; if (list != null) { for (item in list) if (item is Location) spoofLocationObject(item, sAppContext); return } } catch (_: Throwable) {}
        try { (ReflectUtil.callMethod(locationResult, "getLastLocation") as? Location)?.let { spoofLocationObject(it, sAppContext) } } catch (_: Throwable) {}
    }

    private fun spoofArgsLocations(args: List<Any?>) {
        for (arg in args) {
            when {
                arg is Location -> spoofLocationObject(arg)
                arg is List<*> -> for (item in arg) if (item is Location) spoofLocationObject(item)
                arg != null && arg.javaClass.name.contains("LocationResult") -> spoofLocationResultObject(arg)
            }
        }
    }

    private fun ensureHighAccuracyArgs(args: List<Any?>) {
        for (arg in args) {
            when {
                arg is Location -> { if (!isGrabApp() && (arg.accuracy <= 0f || arg.accuracy > 8.0f)) arg.accuracy = 4.5f }
                arg is List<*> -> { for (item in arg) { if (item is Location && !isGrabApp() && (item.accuracy <= 0f || item.accuracy > 8.0f)) item.accuracy = 4.5f } }
            }
        }
    }

    private fun hookSensorManager(classLoader: ClassLoader) {
        try {
            val smClass = ReflectUtil.findClassOrNull("android.hardware.SensorManager", classLoader) ?: return
            for (m in ReflectUtil.findMethodsByName(smClass, "registerListener")) {
                hookMethod(m) { chain -> for (arg in chain.args) { if (arg != null && SensorEventListener::class.java.isAssignableFrom(arg.javaClass)) hookSensorListenerClass(arg.javaClass) }; chain.proceed() }
            }
        } catch (t: Throwable) { logE("Error hooking SensorManager: ${t.message}", t) }
    }

    private fun hookSensorListenerClass(clazz: Class<*>) {
        if (!hookedSensorListenerClasses.add(clazz)) return
        for (m in ReflectUtil.findMethodsByName(clazz, "onSensorChanged")) {
            hookMethod(m) { chain ->
                if (!isHookActive || !isSpoofActive) return@hookMethod chain.proceed()
                val event = chain.getArg(0) as? SensorEvent
                if (event != null && event.values != null) {
                    val type = event.sensor?.type ?: 0
                    when (type) {
                        Sensor.TYPE_ACCELEROMETER -> RealisticGpsEngine.applyAccelerometerValues(event.values, spoofCacheAccSpeed.coerceIn(0.0f, 1.5f), spoofAccelerometerBearing)
                        Sensor.TYPE_ORIENTATION -> RealisticGpsEngine.applyOrientationValues(event.values, spoofAccelerometerBearing)
                        Sensor.TYPE_GYROSCOPE -> RealisticGpsEngine.applyGyroscopeValues(event.values, spoofAccelerometerBearing)
                        Sensor.TYPE_MAGNETIC_FIELD -> RealisticGpsEngine.applyMagnetometerValues(event.values, spoofAccelerometerBearing)
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun hookApplicationContext() {
        if (applicationAttachHooked) return
        try {
            val attach = android.app.Application::class.java.getDeclaredMethod("attach", Context::class.java)
            hookMethod(attach, XposedInterface.PRIORITY_HIGHEST) { chain ->
                val result = chain.proceed()
                try {
                    val thiz = chain.thisObject; var ctx = thiz as? Context
                    if (ctx == null && chain.args.isNotEmpty()) ctx = chain.getArg(0) as? Context
                    if (ctx != null) { sAppContext = ctx.applicationContext ?: ctx; registerContentObserver(sAppContext!!); registerPingReceiver(sAppContext!!); lastQueryTime = 0L; refreshConfig(sAppContext, force = true); pingProvider(sAppContext) }
                } catch (_: Throwable) {}
                result
            }
            applicationAttachHooked = true
        } catch (t: Throwable) { logE("Application.attach hook failed: ${t.message}", t) }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            val activityClass = ReflectUtil.findClassOrNull("android.app.Activity", classLoader) ?: return
            val onResume = ReflectUtil.findMethodExactOrNull(activityClass, "onResume") ?: return
            hookMethod(onResume) { chain ->
                val result = chain.proceed()
                try { val activity = chain.thisObject as? android.app.Activity; if (activity != null) { if (sAppContext == null) setApplicationContext(activity.applicationContext ?: activity); refreshConfig(activity, force = false) } } catch (_: Throwable) {}
                result
            }
        } catch (t: Throwable) { logE("hookActivityLifecycle failed: ${t.message}", t) }
    }

    private fun hookAntiMockDetections(classLoader: ClassLoader) {
        try {
            val secureClass = ReflectUtil.findClassOrNull("android.provider.Settings\$Secure", classLoader)
            if (secureClass != null) {
                for (m in ReflectUtil.findMethodsByName(secureClass, "getString")) { hookMethod(m) { chain -> if (chain.getArg(1) == "mock_location" && shouldBypassMock()) "0" else chain.proceed() } }
            }
        } catch (_: Throwable) {}
    }

    private fun hookWifiAndTelephony(classLoader: ClassLoader) {
        try {
            val tmClass = ReflectUtil.findClassOrNull("android.telephony.TelephonyManager", classLoader)
            if (tmClass != null) {
                for (name in arrayOf("getCellLocation", "getAllCellInfo")) {
                    for (m in ReflectUtil.findMethodsByName(tmClass, name)) { hookMethod(m) { chain -> if (isHookActive && isSpoofActive && !isModulePackage(currentPackage)) { if (name == "getAllCellInfo") emptyList<Any>() else null } else chain.proceed() } }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookGeolocation(classLoader: ClassLoader) {
        try {
            val wccClass = ReflectUtil.findClassOrNull("android.webkit.WebChromeClient", classLoader)
            if (wccClass != null && hookedGeolocationClasses.add(wccClass)) {
                for (m in ReflectUtil.findMethodsByName(wccClass, "onGeolocationPermissionsShowPrompt")) {
                    hookMethod(m) { chain -> if (isHookActive && !isModulePackage(currentPackage)) { val origin = chain.getArg(0) as? String; val callback = chain.getArg(1); if (callback != null && origin != null) { try { ReflectUtil.callMethod(callback, "invoke", origin, true, false); return@hookMethod null } catch (_: Throwable) {} } }; chain.proceed() }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookNmeaAndGnss(classLoader: ClassLoader) {
        try {
            val lmClass = ReflectUtil.findClassOrNull("android.location.LocationManager", classLoader) ?: return
            for (m in ReflectUtil.findMethodsByName(lmClass, "addNmeaListener")) { hookMethod(m) { chain -> for (arg in chain.args) if (arg != null) hookNmeaListenerInstance(arg); chain.proceed() } }
        } catch (_: Throwable) {}
    }

    private fun hookNmeaListenerInstance(listener: Any) {
        val clazz = listener.javaClass
        if (!hookedNmeaListenerClasses.add(clazz)) return
        for (m in ReflectUtil.findMethodsByName(clazz, "onNmeaMessage")) {
            hookMethod(m) { chain -> if (isHookActive && isSpoofActive && !isModulePackage(currentPackage)) { refreshSpoofCacheIfNeeded(); if (chain.args.isNotEmpty()) chain.args[0] = "GNRMC,..."; if (chain.args.size > 1) chain.args[1] = spoofCacheLocTs }; chain.proceed() }
        }
    }

    fun refreshConfig(context: Context? = null, force: Boolean = false) {
        if (isSystemServer || currentPackage == "android") return
        syncLifecycleGeneration()
        val now = System.currentTimeMillis()
        if (!force && (now - lastQueryTime < 400L)) return
        lastQueryTime = now
        val ctx = getProcessContext(context) ?: sAppContext
        val providerBundle = fetchConfigFromProvider(ctx)
        val remotePrefs = try { module?.getRemotePreferences(PREFS_NAME) } catch (_: Throwable) { null }
        val remoteHookEnabled = if (providerBundle != null && providerBundle.containsKey("is_hook_enabled")) providerBundle.getBoolean("is_hook_enabled", true) else remotePrefs?.getBoolean("is_hook_enabled", true) ?: true
        val spoofTargetActive = remoteHookEnabled && if (providerBundle != null && providerBundle.containsKey("is_spoof_active")) providerBundle.getBoolean("is_spoof_active", false) else remotePrefs?.getBoolean("is_spoof_active", false) ?: false

        try {
            if (providerBundle != null) {
                isAntiMockEnabled = providerBundle.getBoolean("anti_mock", isAntiMockEnabled)
                isConnectionStabilityEnabled = providerBundle.getBoolean("connection_stability_enabled", isConnectionStabilityEnabled)
                isOkHttpStabilityEnabled = providerBundle.getBoolean("okhttp_stability_enabled", isOkHttpStabilityEnabled)
                isFastTcpEnabled = providerBundle.getBoolean("fast_tcp_enabled", isFastTcpEnabled)
                isFastestCaptureEnabled = providerBundle.getBoolean("fastest_capture_mode_enabled", isFastestCaptureEnabled)
                isGcmSupportEnabled = providerBundle.getBoolean("gcm_support_enabled", isGcmSupportEnabled)
                useVirtual = providerBundle.getBoolean("use_virtual", useVirtual)
                useVirtualRacing = providerBundle.getBoolean("use_virtual_racing", useVirtualRacing)
                useRandom = providerBundle.getBoolean("use_random", useRandom)
            } else if (remotePrefs != null) {
                isAntiMockEnabled = remotePrefs.getBoolean("anti_mock", true)
                isConnectionStabilityEnabled = remotePrefs.getBoolean("connection_stability_enabled", false)
                isOkHttpStabilityEnabled = remotePrefs.getBoolean("okhttp_stability_enabled", true)
                isFastTcpEnabled = remotePrefs.getBoolean("fast_tcp_enabled", true)
                isFastestCaptureEnabled = remotePrefs.getBoolean("fastest_capture_mode_enabled", true)
                isGcmSupportEnabled = remotePrefs.getBoolean("gcm_support_enabled", true)
                useVirtual = remotePrefs.getBoolean("use_virtual", false)
                useVirtualRacing = remotePrefs.getBoolean("use_virtual_racing", false)
                useRandom = remotePrefs.getBoolean("use_random", false)
            }
        } catch (_: Throwable) {}

        if (!spoofTargetActive) {
            isHookActive = remoteHookEnabled; isSpoofActive = false
            perAppSpoofOverride = null; perAppOverrideLat = null; perAppOverrideLng = null
            perAppCoordsCachePkg = ""; perAppCoordsCacheValue = null; perAppCoordsCacheExpiry = 0L; spoofCacheExpiry = 0L
            try { SpoofConsistencyGuard.cancelTransition() } catch (_: Throwable) {}
            return
        }
        isHookActive = remoteHookEnabled
        if (perAppSpoofOverride == true && perAppOverrideLat != null && perAppOverrideLng != null) { isSpoofActive = true; invalidateSpoofCache(); return }
        val perApp = getPerAppCoords(currentPackage)
        if (perApp != null) { perAppOverrideLat = perApp.first; perAppOverrideLng = perApp.second; perAppSpoofOverride = true; isSpoofActive = true; invalidateSpoofCache(); return }

        try {
            if (providerBundle != null) {
                val pLat = providerBundle.getDouble("latitude", Double.NaN); val pLng = providerBundle.getDouble("longitude", Double.NaN)
                if (!pLat.isNaN() && !pLng.isNaN() && pLat in -90.0..90.0 && pLng in -180.0..180.0) { spoofLat = pLat; spoofLng = pLng }
                spoofAccuracy = providerBundle.getFloat("accuracy", spoofAccuracy)
                spoofSpeed = providerBundle.getFloat("speed", spoofSpeed)
                spoofBearing = providerBundle.getFloat("bearing", spoofBearing)
                isRandomLocEnabled = providerBundle.getBoolean("random_location", isRandomLocEnabled)
                randomRadius = providerBundle.getFloat("random_radius", randomRadius)
                isRandomAccEnabled = providerBundle.getBoolean("random_accuracy", isRandomAccEnabled)
                isLocationSharingActive = providerBundle.getBoolean("is_location_sharing_enabled", providerBundle.getBoolean("location_sharing", isLocationSharingActive))
            } else if (remotePrefs != null) {
                spoofLat = java.lang.Double.longBitsToDouble(remotePrefs.getLong("latitude", java.lang.Double.doubleToLongBits(spoofLat)))
                spoofLng = java.lang.Double.longBitsToDouble(remotePrefs.getLong("longitude", java.lang.Double.doubleToLongBits(spoofLng)))
                spoofAccuracy = remotePrefs.getFloat("accuracy", spoofAccuracy)
                spoofSpeed = remotePrefs.getFloat("speed", spoofSpeed)
                spoofBearing = remotePrefs.getFloat("bearing", spoofBearing)
                spoofPingTimestamp = remotePrefs.getLong("pingTimestamp", spoofPingTimestamp)
                spoofLocationTimestamp = remotePrefs.getLong("locationTimestamp", spoofLocationTimestamp)
                isRandomLocEnabled = remotePrefs.getBoolean("random_location", isRandomLocEnabled)
                randomRadius = remotePrefs.getFloat("random_radius", randomRadius)
                isRandomAccEnabled = remotePrefs.getBoolean("random_accuracy", isRandomAccEnabled)
                isLocationSharingActive = remotePrefs.getBoolean("is_location_sharing_enabled", remotePrefs.getBoolean("location_sharing", isLocationSharingActive))
            }
        } catch (_: Throwable) {}
        isSpoofActive = true
        invalidateSpoofCache()
    }

    fun shouldShareLocation(): Boolean {
        if (isModulePackage(currentPackage)) return false
        if (isSystemServer || currentPackage == "android") return false
        return isHookActive && isLocationSharingActive && (isSpoofActive || isTargetSpoofApp(currentPackage))
    }

    private fun hookLocationSharingAndFirebase(classLoader: ClassLoader) {
        // Dihilangkan untuk menghemat tempat, silakan gunakan implementasi Firebase Anda yang lama
    }
}