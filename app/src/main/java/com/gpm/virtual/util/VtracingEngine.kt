package com.gpm.virtual.util

import android.location.Location
import android.os.Build
import android.os.SystemClock
import kotlin.random.Random

object VtracingEngine {

    // [PATCH-VT-1] Throttle 30ms — sinkron dengan cache hook side
    private const val THROTTLE_MS = 30L
    private const val OFFSET_SCALE = 1.0E7

    private const val MAX_BEARING_DELTA_DEG = 4.0f
    private const val ACCURACY_MIN_M = 2.5f
    private const val ACCURACY_MAX_M = 4.5f
    private const val SPEED_MIN_MPS = 0.5f
    private const val SPEED_MAX_MPS = 2.0f

    private fun roundTo7Decimals(value: Double): Double {
        return java.math.BigDecimal.valueOf(value)
            .setScale(7, java.math.RoundingMode.HALF_UP)
            .toDouble()
    }

    private fun getElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    @Volatile private var centerLat: Double = -6.168330
    @Volatile private var centerLng: Double = 106.758849

    @Volatile private var lato: Double = centerLat
    @Volatile private var longo: Double = centerLng
    @Volatile private var currentBearing: Float = 0.0f
    @Volatile private var currentAccuracy: Float = 3.0f
    @Volatile private var currentSpeed: Float = 0.0f
    @Volatile private var lastUpdated: Long = 0L

    fun setCenter(lat: Double, lng: Double) {
        val newLat = roundTo7Decimals(lat)
        val newLng = roundTo7Decimals(lng)

        // [PATCH-VT-1] Reset throttle saat center berubah >1cm
        val centerChanged =
            kotlin.math.abs(newLat - centerLat) > 1e-7 ||
            kotlin.math.abs(newLng - centerLng) > 1e-7

        centerLat = newLat
        centerLng = newLng

        if (lastUpdated == 0L || centerChanged) {
            lato = centerLat
            longo = centerLng
            lastUpdated = 0L
        }
    }

    fun setCenter(latStr: String, lngStr: String) {
        val lat = latStr.toDoubleOrNull() ?: centerLat
        val lng = lngStr.toDoubleOrNull() ?: centerLng
        setCenter(lat, lng)
    }

    fun getCenter(): Pair<Double, Double> = Pair(centerLat, centerLng)
    fun getSpoofedPosition(): Pair<Double, Double> =
        Pair(roundTo7Decimals(lato), roundTo7Decimals(longo))
    fun getBearing(): Float = currentBearing
    fun getAccuracy(): Float = currentAccuracy
    fun getSpeed(): Float = currentSpeed
    fun getLastUpdated(): Long = lastUpdated

    @Synchronized
    fun update(movement: Boolean): Boolean {
        val now = getElapsedRealtime()
        if (lastUpdated != 0L && (now - lastUpdated < THROTTLE_MS)) {
            return false
        }
        lastUpdated = now

        if (movement) {
            val signLat = if (Random.nextBoolean()) 1.0 else -1.0
            val signLng = if (Random.nextBoolean()) 1.0 else -1.0
            val rawLat = centerLat + ((Random.nextDouble(1.0, 2.0) / OFFSET_SCALE) * signLat)
            val rawLng = centerLng + ((Random.nextDouble(1.0, 2.0) / OFFSET_SCALE) * signLng)
            lato = roundTo7Decimals(rawLat)
            longo = roundTo7Decimals(rawLng)
        } else {
            lato = centerLat
            longo = centerLng
        }

        currentBearing = smoothBearing(currentBearing)

        currentAccuracy = ACCURACY_MIN_M + Random.nextFloat() * (ACCURACY_MAX_M - ACCURACY_MIN_M)
        currentSpeed = if (movement) {
            SPEED_MIN_MPS + Random.nextFloat() * (SPEED_MAX_MPS - SPEED_MIN_MPS)
        } else {
            0.0f
        }

        return true
    }

    private fun smoothBearing(prev: Float): Float {
        val delta = (Random.nextFloat() - 0.5f) * 2.0f * MAX_BEARING_DELTA_DEG
        var next = prev + delta
        if (next < 0f) next += 360f
        if (next >= 360f) next -= 360f
        return next
    }

    fun buildLocation(provider: String = "gps", accuracy: Float? = null): Location {
        val loc = Location(provider)
        loc.latitude = roundTo7Decimals(lato)
        loc.longitude = roundTo7Decimals(longo)
        loc.accuracy = accuracy ?: currentAccuracy
        loc.bearing = currentBearing
        loc.speed = currentSpeed
        loc.altitude = 0.0
        loc.time = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.bearingAccuracyDegrees = 1.0f
            loc.speedAccuracyMetersPerSecond = 0.1f
            loc.verticalAccuracyMeters = 2.0f
        }

        // [PATCH-VT-1b] `loc.isMock = false` DIHAPUS — tidak ada setter publik.
        return loc
    }

    fun reset() {
        lastUpdated = 0L
        lato = centerLat
        longo = centerLng
        currentBearing = 0.0f
        currentAccuracy = 3.0f
        currentSpeed = 0.0f
    }
}