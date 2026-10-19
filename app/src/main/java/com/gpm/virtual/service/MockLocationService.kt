package com.gpm.virtual.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.gpm.virtual.MainActivity
import com.gpm.virtual.R
import com.gpm.virtual.data.KnownPackages
import com.gpm.virtual.data.preferences.GpsConfig
import com.gpm.virtual.data.preferences.GpsPreferences
import com.gpm.virtual.xposed.RealisticGpsEngine

class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var gpsPreferences: GpsPreferences
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var isRunning = false
    private var lastTelemetryLogTime = 0L

    private val supportedProviders = mutableListOf<String>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingRemoveProviders: Runnable? = null

    /**
     * ⭐ [FIX-FINAL] Service aktif HANYA kalau Play button ON.
     * VT toggles tidak pernah trigger service start.
     */
    private fun isServiceActive(config: GpsConfig): Boolean =
        config.isSpoofActive

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                pushMockLocation()
                val config = gpsPreferences.getConfig()
                val interval = when {
                    config.useVirtualRacing -> 100L
                    config.isConnectionStabilityEnabled &&
                            config.isFastestCaptureModeEnabled -> 5L
                    else -> 500L
                }
                handler?.postDelayed(this, interval)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gpsPreferences = GpsPreferences.getInstance(this)
        createNotificationChannel()

        val thread = HandlerThread("MockLocationBackgroundThread").apply { start() }
        handlerThread = thread
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            try { safeStartForeground() } catch (_: Throwable) {}
            try { gpsPreferences.updateConfig { it.copy(isSpoofActive = false) } } catch (_: Throwable) {}
            stopMocking()
            stopSelf()
            pingLocationToScope(this)
            return START_NOT_STICKY
        }

        if (action == ACTION_PUSH_NOW) {
            if (isRunning) {
                handler?.removeCallbacks(updateRunnable)
                handler?.post(updateRunnable)
            }
            return START_STICKY
        }

        safeStartForeground()
        startMocking()
        return START_STICKY
    }

    private fun safeStartForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e("MockLocationService", "safeStartForeground failed: ${t.message}", t)
            try { startForeground(NOTIFICATION_ID, notification) } catch (_: Throwable) {}
        }
    }

    private fun startMocking() {
        isRunning = true
        pendingRemoveProviders?.let {
            handler?.removeCallbacks(it)
            pendingRemoveProviders = null
        }
        setupTestProviders()

        val config = gpsPreferences.getConfig()
        if (config.isConnectionStabilityEnabled) {
            com.gpm.virtual.util.NetworkStabilityEngine.acquireHighPerfWifiLock(this)
            if (config.isGcmSupportEnabled) {
                com.gpm.virtual.util.NetworkStabilityEngine.startGcmHeartbeatKeeper(this)
            }
        }
        handler?.removeCallbacks(updateRunnable)
        handler?.post(updateRunnable)
    }

    private fun stopMocking() {
        isRunning = false
        com.gpm.virtual.util.NetworkStabilityEngine.stopGcmHeartbeatKeeper()
        com.gpm.virtual.util.NetworkStabilityEngine.releaseWifiLock()
        handler?.removeCallbacksAndMessages(null)

        pushRealLocationToProviders()

        val remover = Runnable {
            removeTestProviders()
            pendingRemoveProviders = null
        }
        pendingRemoveProviders = remover
        handler?.postDelayed(remover, 500L)

        try {
            sendBroadcast(Intent(ACTION_STOPPED))
        } catch (_: Throwable) {}
    }

    private fun pushRealLocationToProviders() {
        if (supportedProviders.isEmpty()) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            var realLoc: Location? = null

            for (provider in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )) {
                try {
                    val l = lm.getLastKnownLocation(provider)
                    if (l != null && (realLoc == null || l.time > realLoc.time)) realLoc = l
                } catch (_: Throwable) {}
            }

            val useReal = realLoc != null &&
                    !realLoc!!.latitude.isNaN() && !realLoc!!.longitude.isNaN() &&
                    realLoc!!.latitude in -90.0..90.0 &&
                    realLoc!!.longitude in -180.0..180.0

            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtimeNanos()

            for (provider in supportedProviders) {
                try {
                    val loc = Location(provider).apply {
                        if (useReal) {
                            latitude = realLoc!!.latitude
                            longitude = realLoc!!.longitude
                            accuracy = realLoc!!.accuracy.coerceIn(3.0f, 50.0f)
                            altitude = realLoc!!.altitude
                            speed = realLoc!!.speed
                            bearing = realLoc!!.bearing
                        } else {
                            val cfg = gpsPreferences.getConfig()
                            latitude = cfg.latitude
                            longitude = cfg.longitude
                            accuracy = 10.0f
                            altitude = 15.4
                            speed = 0.0f
                            bearing = 0.0f
                        }
                        time = now
                        elapsedRealtimeNanos = elapsed
                    }
                    lm.setTestProviderLocation(provider, loc)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun setupTestProviders() {
        supportedProviders.clear()
        val candidateProviders = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            candidateProviders.add(LocationManager.FUSED_PROVIDER)
        }

        for (provider in candidateProviders) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    locationManager.addTestProvider(
                        provider,
                        false, false, false, false, true, true, true,
                        ProviderProperties.POWER_USAGE_HIGH,
                        ProviderProperties.ACCURACY_FINE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        provider,
                        false, false, false, false, true, true, true,
                        Criteria.POWER_HIGH,
                        Criteria.ACCURACY_FINE
                    )
                }
                locationManager.setTestProviderEnabled(provider, true)
                supportedProviders.add(provider)
                Log.i("MockLocationService", "Test provider added: $provider")
            } catch (t: Throwable) {
                Log.w("MockLocationService", "addTestProvider($provider) failed: ${t.message}")
            }
        }
    }

    private fun removeTestProviders() {
        for (provider in supportedProviders.toList()) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Throwable) {}
        }
        supportedProviders.clear()
    }

    private fun pushMockLocation() {
        val config: GpsConfig = gpsPreferences.getConfig()
        if (!isServiceActive(config)) {
            stopMocking()
            stopSelf()
            return
        }

        val lat: Double
        val lng: Double
        val speedVal: Float
        val bearingVal: Float
        val acc: Float

        val useJitter = config.isRandomLocationEnabled || config.useRandom

        if (config.useVirtualRacing) {
            // VT Racing engine
            val injPrefs = getSharedPreferences("gpm_injection_prefs", Context.MODE_PRIVATE)
            val scope = injPrefs.getString("target_scope", "ALL") ?: "ALL"
            val gojekOn = injPrefs.getBoolean("gojek_spoof_active", false)
            val grabOn = injPrefs.getBoolean("grab_spoof_active", false)
            val perAppCoord: Pair<Double, Double>? = when {
                scope == "GOJEK" && gojekOn && injPrefs.getBoolean("gojek_has_target", false) ->
                    Double.fromBits(injPrefs.getLong("gojek_lat", 0L)) to
                    Double.fromBits(injPrefs.getLong("gojek_lng", 0L))
                scope == "GRAB" && grabOn && injPrefs.getBoolean("grab_has_target", false) ->
                    Double.fromBits(injPrefs.getLong("grab_lat", 0L)) to
                    Double.fromBits(injPrefs.getLong("grab_lng", 0L))
                gojekOn && injPrefs.getBoolean("gojek_has_target", false) ->
                    Double.fromBits(injPrefs.getLong("gojek_lat", 0L)) to
                    Double.fromBits(injPrefs.getLong("gojek_lng", 0L))
                grabOn && injPrefs.getBoolean("grab_has_target", false) ->
                    Double.fromBits(injPrefs.getLong("grab_lat", 0L)) to
                    Double.fromBits(injPrefs.getLong("grab_lng", 0L))
                else -> null
            }
            val vtrCenter = if (perAppCoord != null &&
                perAppCoord.first in -90.0..90.0 &&
                perAppCoord.second in -180.0..180.0
            ) perAppCoord else (config.latitude to config.longitude)

            com.gpm.virtual.util.VtracingEngine.setCenter(vtrCenter.first, vtrCenter.second)
            com.gpm.virtual.util.VtracingEngine.update(useJitter)
            val pos = com.gpm.virtual.util.VtracingEngine.getSpoofedPosition()
            lat = pos.first; lng = pos.second
            speedVal = com.gpm.virtual.util.VtracingEngine.getSpeed()
            bearingVal = com.gpm.virtual.util.VtracingEngine.getBearing()
            acc = com.gpm.virtual.util.VtracingEngine.getAccuracy()
        } else {
            // Realistic GPS engine
            val injPrefs = getSharedPreferences("gpm_injection_prefs", Context.MODE_PRIVATE)
            val scope = injPrefs.getString("target_scope", "ALL") ?: "ALL"
            val gojekOn = injPrefs.getBoolean("gojek_spoof_active", false)
            val grabOn = injPrefs.getBoolean("grab_spoof_active", false)
            val perAppCoord: Pair<Double, Double>? = when {
                scope == "GOJEK" && gojekOn && injPrefs.getBoolean("gojek_has_target", false) ->
                    Double.fromBits(injPrefs.getLong("gojek_lat", 0L)) to
                    Double.fromBits(injPrefs.getLong("gojek_lng", 0L))
                scope == "GRAB" && grabOn && injPrefs.getBoolean("grab_has_target", false) ->
                    Double.fromBits(injPrefs.getLong("grab_lat", 0L)) to
                    Double.fromBits(injPrefs.getLong("grab_lng", 0L))
                gojekOn && injPrefs.getBoolean("gojek_has_target", false) ->
                    Double.fromBits(injPrefs.getLong("gojek_lat", 0L)) to
                    Double.fromBits(injPrefs.getLong("gojek_lng", 0L))
                grabOn && injPrefs.getBoolean("grab_has_target", false) ->
                    Double.fromBits(injPrefs.getLong("grab_lat", 0L)) to
                    Double.fromBits(injPrefs.getLong("grab_lng", 0L))
                else -> null
            }
            val baseLat = if (perAppCoord != null && perAppCoord.first in -90.0..90.0)
                perAppCoord.first else config.latitude
            val baseLng = if (perAppCoord != null && perAppCoord.second in -180.0..180.0)
                perAppCoord.second else config.longitude

            val (cLat, cLng) = RealisticGpsEngine.computeRealisticCoordinates(
                baseLat, baseLng, useJitter, config.randomRadiusMeters
            )
            lat = cLat; lng = cLng
            speedVal = RealisticGpsEngine.computeRealisticSpeed(config.walkSpeedMps).coerceIn(0.0f, 1.5f)
            bearingVal = if (config.isCustomBearingEnabled) {
                ((config.bearing % 360f) + 360f) % 360f
            } else {
                RealisticGpsEngine.computeRealisticBearing()
            }
            acc = RealisticGpsEngine.computeRealisticAccuracy(config.accuracy, config.isRandomAccuracyEnabled)
        }

        val safeLat = if (config.useVirtualRacing) RealisticGpsEngine.roundTo7Decimals(lat)
        else RealisticGpsEngine.roundTo6Decimals(lat)
        val safeLng = if (config.useVirtualRacing) RealisticGpsEngine.roundTo7Decimals(lng)
        else RealisticGpsEngine.roundTo6Decimals(lng)
        val locTimestamp = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val accSpeedVal = RealisticGpsEngine.computeAccelerometerSpeed(speedVal).coerceIn(0.0f, 1.5f)
        val accBearingVal = RealisticGpsEngine.computeAccelerometerBearing(bearingVal)
        val altVal = RealisticGpsEngine.computeRealisticAltitude()

        for (provider in supportedProviders) {
            try {
                val mockLoc = Location(provider).apply {
                    latitude = safeLat
                    longitude = safeLng
                    accuracy = acc
                    altitude = altVal
                    bearing = bearingVal
                    speed = speedVal
                    time = locTimestamp
                    elapsedRealtimeNanos = elapsedNanos
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = (acc * 0.75f).coerceAtLeast(1.0f)
                        speedAccuracyMetersPerSecond = 0.05f
                        bearingAccuracyDegrees = 0.5f
                    }
                    val b = Bundle()
                    b.putFloat("speed", speedVal)
                    b.putFloat("bearing", bearingVal)
                    b.putLong("pingTimestamp", locTimestamp)
                    b.putLong("ping_timestamp", locTimestamp)
                    b.putLong("locationTimestamp", locTimestamp)
                    b.putLong("location_timestamp", locTimestamp)
                    b.putFloat("accelerometerSpeed", accSpeedVal)
                    b.putFloat("accelerometer_speed", accSpeedVal)
                    b.putFloat("accelerometerBearing", accBearingVal)
                    b.putFloat("accelerometer_bearing", accBearingVal)
                    b.putBoolean("location_sharing", config.isLocationSharingEnabled)
                    b.putBoolean("is_location_sharing_enabled", config.isLocationSharingEnabled)
                    b.putDouble("location_sharing_lat", safeLat)
                    b.putDouble("location_sharing_lng", safeLng)
                    b.putFloat("location_sharing_speed", speedVal)
                    b.putFloat("location_sharing_bearing", bearingVal)
                    b.putLong("location_sharing_timestamp", locTimestamp)
                    extras = b
                }
                locationManager.setTestProviderLocation(provider, mockLoc)
            } catch (_: Throwable) {}
        }

        try {
            fun fillExtras(intent: Intent) {
                intent.putExtra("is_hook_enabled", config.isHookEnabled)
                intent.putExtra("is_spoof_active", config.isSpoofActive)
                intent.putExtra("use_virtual", config.useVirtual)
                intent.putExtra("use_virtual_racing", config.useVirtualRacing)
                intent.putExtra("use_random", config.useRandom)
                intent.putExtra("latitude", safeLat)
                intent.putExtra("longitude", safeLng)
                intent.putExtra("lat", safeLat)
                intent.putExtra("lng", safeLng)
                intent.putExtra("location_sharing_lat", safeLat)
                intent.putExtra("location_sharing_lng", safeLng)
                intent.putExtra("accuracy", acc)
                intent.putExtra("speed", speedVal)
                intent.putExtra("bearing", bearingVal)
                intent.putExtra("altitude", altVal)
                intent.putExtra("pingTimestamp", locTimestamp)
                intent.putExtra("ping_timestamp", locTimestamp)
                intent.putExtra("locationTimestamp", locTimestamp)
                intent.putExtra("location_timestamp", locTimestamp)
                intent.putExtra("accelerometerSpeed", accSpeedVal)
                intent.putExtra("accelerometer_speed", accSpeedVal)
                intent.putExtra("accelerometerBearing", accBearingVal)
                intent.putExtra("accelerometer_bearing", accBearingVal)
                intent.putExtra("connection_stability_enabled", config.isConnectionStabilityEnabled)
                intent.putExtra("okhttp_stability_enabled", config.isOkHttpStabilityEnabled)
                intent.putExtra("fast_tcp_enabled", config.isFastTcpEnabled)
                intent.putExtra("fastest_capture_mode_enabled", config.isFastestCaptureModeEnabled)
                intent.putExtra("is_location_sharing_enabled", config.isLocationSharingEnabled)
                intent.putExtra("location_sharing", config.isLocationSharingEnabled)
                intent.putExtra("timestamp", locTimestamp)
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                if (config.isConnectionStabilityEnabled && config.isFastestCaptureModeEnabled) {
                    intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            }

            val broadcastIntent = Intent(ACTION_LOCATION_UPDATED).also {
                fillExtras(it); it.setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)

            if (config.isLocationSharingEnabled) {
                com.gpm.virtual.data.provider.LocationConfigProvider.sendLocationSharingBroadcast(
                    this, safeLat, safeLng, speedVal, bearingVal
                )
            }

            val targetList = (COMMON_TARGET_PACKAGES +
                    listOfNotNull(com.gpm.virtual.data.provider.LocationConfigProvider.lastPingedPackage))
                .distinct()
            for (pkg in targetList) {
                try {
                    val direct = Intent(ACTION_LOCATION_UPDATED).also {
                        fillExtras(it); it.setPackage(pkg)
                    }
                    sendBroadcast(direct)

                    if (config.isLocationSharingEnabled) {
                        val directSharing = Intent(
                            com.gpm.virtual.data.provider.LocationConfigProvider.ACTION_LOCATION_SHARING
                        ).also { fillExtras(it); it.setPackage(pkg) }
                        sendBroadcast(directSharing)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        val now = SystemClock.elapsedRealtime()
        if (now - lastTelemetryLogTime >= TELEMETRY_LOG_INTERVAL_MS) {
            lastTelemetryLogTime = now
            val engineTag = if (config.useVirtualRacing) "[VT-Racing]" else "[RealisticGPS]"
            Log.i("MockLocationService",
                "[GPM Telemetry]$engineTag[SPOOF] Service | Lat: $safeLat, Lng: $safeLng | " +
                "Acc: ${"%.1f".format(acc)}m | Speed: ${"%.2f".format(speedVal)}m/s | " +
                "Bearing: ${"%.1f".format(bearingVal)}° | Alt: ${"%.1f".format(altVal)}m")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GPM (virtual maps) Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Notifikasi status lokasi palsu GPM (virtual maps)" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val config = gpsPreferences.getConfig()
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, MockLocationService::class.java).apply { action = ACTION_STOP }
        val pendingStopIntent = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val statusLabel = when {
            config.useVirtualRacing -> "VT Racing"
            config.useVirtual -> "Virtual Location"
            config.useRandom -> "Random Movement"
            else -> "Spoof Aktif"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPM (virtual maps) — $statusLabel")
            .setContentText("${config.locationName} (${String.format(java.util.Locale.US,
                "%.7f, %.7f", config.latitude, config.longitude)})")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingOpenIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Hentikan", pendingStopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopMocking()
        try { serviceScope.cancel() } catch (_: Throwable) {}
        try { handlerThread?.quitSafely(); handlerThread = null; handler = null } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TELEMETRY_LOG_INTERVAL_MS = 10_000L
        const val CHANNEL_ID = "gpm_virtual_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.gpm.virtual.ACTION_STOP"
        const val ACTION_STOPPED = "com.gpm.virtual.ACTION_STOPPED"
        const val ACTION_PUSH_NOW = "com.gpm.virtual.ACTION_PUSH_NOW"
        const val ACTION_PING_LOCATION = "com.gpm.virtual.ACTION_PING_LOCATION"
        const val ACTION_LOCATION_UPDATED = "com.gpm.virtual.MOCK_LOCATION_UPDATE"

        fun start(context: Context) {
            val intent = Intent(context, MockLocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun pushImmediate(context: Context) {
            try {
                val intent = Intent(context, MockLocationService::class.java).apply { action = ACTION_PUSH_NOW }
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        fun sendImmediateBroadcast(context: Context) { pingLocationToScope(context) }

        val COMMON_TARGET_PACKAGES = listOf(
            KnownPackages.GOOGLE_MAPS, KnownPackages.GOJEK_PARTNER, KnownPackages.GOJEK_APP,
            KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER, KnownPackages.GRAB_PASSENGER,
            "com.shopee.driver.id", "com.shopee.id", "com.maxim.driver", "com.taxsee.taxsee",
            "com.indriver.driver", "sinet.startup.inDriver", KnownPackages.WAZE,
            "hk.gogovan.GoGoVanDriver2", "com.lalamove.driver", "pt.dostavista", "com.deliveree",
            "id.anteraja.satria", "com.sicepat.kurir", "com.jtexpress", "com.whatsapp",
            "com.whatsapp.w4b", "org.telegram.messenger", "com.life360.android.safetym"
        )

        private val pingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun pingLocationToScope(context: Context) {
            pingScope.launch {
                try {
                    val gpsPreferences = GpsPreferences.getInstance(context)
                    val config = gpsPreferences.getConfig()
                    val now = System.currentTimeMillis()
                    val effectiveSpoof = config.isSpoofActive

                    com.gpm.virtual.data.provider.LocationConfigProvider.activeConfig = config
                    if (effectiveSpoof) pushImmediate(context)

                    val speedVal = RealisticGpsEngine.computeRealisticSpeed(config.walkSpeedMps).coerceIn(0.0f, 1.5f)
                    val bearingVal = 45.0f
                    val accSpeedVal = RealisticGpsEngine.computeAccelerometerSpeed(speedVal).coerceIn(0.0f, 1.5f)
                    val accBearingVal = RealisticGpsEngine.computeAccelerometerBearing(bearingVal)

                    fun populate(intent: Intent) {
                        intent.putExtra("is_hook_enabled", config.isHookEnabled)
                        intent.putExtra("is_spoof_active", effectiveSpoof)
                        intent.putExtra("use_virtual", config.useVirtual)
                        intent.putExtra("use_virtual_racing", config.useVirtualRacing)
                        intent.putExtra("use_random", config.useRandom)
                        intent.putExtra("latitude", config.latitude)
                        intent.putExtra("longitude", config.longitude)
                        intent.putExtra("accuracy", config.accuracy)
                        intent.putExtra("speed", speedVal)
                        intent.putExtra("bearing", bearingVal)
                        intent.putExtra("pingTimestamp", now)
                        intent.putExtra("ping_timestamp", now)
                        intent.putExtra("locationTimestamp", now)
                        intent.putExtra("location_timestamp", now)
                        intent.putExtra("accelerometerSpeed", accSpeedVal)
                        intent.putExtra("accelerometer_speed", accSpeedVal)
                        intent.putExtra("accelerometerBearing", accBearingVal)
                        intent.putExtra("accelerometer_bearing", accBearingVal)
                        intent.putExtra("random_location", config.isRandomLocationEnabled)
                        intent.putExtra("random_radius", config.randomRadiusMeters)
                        intent.putExtra("random_accuracy", config.isRandomAccuracyEnabled)
                        intent.putExtra("anti_mock", config.isAntiMockEnabled)
                        intent.putExtra("connection_stability_enabled", config.isConnectionStabilityEnabled)
                        intent.putExtra("okhttp_stability_enabled", config.isOkHttpStabilityEnabled)
                        intent.putExtra("fast_tcp_enabled", config.isFastTcpEnabled)
                        intent.putExtra("fastest_capture_mode_enabled", config.isFastestCaptureModeEnabled)
                        intent.putExtra("is_location_sharing_enabled", config.isLocationSharingEnabled)
                        intent.putExtra("location_sharing", config.isLocationSharingEnabled)
                        intent.putExtra("location_sharing_lat", config.latitude)
                        intent.putExtra("location_sharing_lng", config.longitude)
                        intent.putExtra("lat", config.latitude)
                        intent.putExtra("lng", config.longitude)
                        intent.putExtra("timestamp", now)
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        if (config.isConnectionStabilityEnabled && config.isFastestCaptureModeEnabled) {
                            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                    }

                    val targetList = (COMMON_TARGET_PACKAGES +
                            listOfNotNull(com.gpm.virtual.data.provider.LocationConfigProvider.lastPingedPackage))
                        .distinct()
                    for (targetPkg in targetList) {
                        try {
                            context.sendBroadcast(Intent(ACTION_PING_LOCATION).also {
                                populate(it); it.setPackage(targetPkg)
                            })
                            context.sendBroadcast(Intent(ACTION_LOCATION_UPDATED).also {
                                populate(it); it.setPackage(targetPkg)
                            })
                            if (config.isLocationSharingEnabled) {
                                context.sendBroadcast(Intent(
                                    com.gpm.virtual.data.provider.LocationConfigProvider.ACTION_LOCATION_SHARING
                                ).also { populate(it); it.setPackage(targetPkg) })
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MockLocationService::class.java))
            } catch (t: Throwable) {
                Log.w("MockLocationService", "stopService gagal: ${t.message}")
            }

            try {
                GpsPreferences.getInstance(context).updateConfig { it.copy(isSpoofActive = false) }
            } catch (_: Throwable) {}

            try { pingLocationToScope(context) } catch (_: Throwable) {}
        }
    }
}