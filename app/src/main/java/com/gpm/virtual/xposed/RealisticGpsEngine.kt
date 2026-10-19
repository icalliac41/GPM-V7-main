package com.gpm.virtual.xposed

import android.os.SystemClock
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random

/**
 * Standard realistic GPS jitter and coordinate generator.
 *
 * Update terbaru:
 *  - FIX 7: Magnetometer sync dengan bearing spoof
 *  - FIX 10: Speed realistic — 20% chance diam (stationary)
 *  - Clamp bearing (max 2°/detik), clamp altitude (max 0.1 m/detik)
 *  - Range accuracy 3-12m, range speed ±0.4 m/s
 *  - ⭐ Litmus-aware: adaptasi Kalman filter (jitter halus, interval 2x)
 */
object RealisticGpsEngine {
    private const val RANDOM_INTERVAL_MS = 10_000L
    private const val MAX_BEARING_DELTA_DEG_PER_SEC = 2.0f
    private const val MAX_ALT_DELTA_M_PER_SEC = 0.1

    private fun getElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    private val random = Random()
    @Volatile private var lastBearing = 45.0f
    @Volatile private var lastSpeed = 0.0f
    @Volatile private var lastAltitude = 18.5
    @Volatile private var lastBearingTime = 0L
    @Volatile private var lastAltTime = 0L

    @Volatile private var lastRandomCoordTime = 0L
    @Volatile private var cachedRandomLat = 0.0
    @Volatile private var cachedRandomLng = 0.0
    @Volatile private var cachedBaseLat = Double.NaN
    @Volatile private var cachedBaseLng = Double.NaN
    @Volatile private var cachedIsRandomLoc = false

    @Volatile private var lastRandomAccTime = 0L
    @Volatile private var cachedRandomAcc = 0.0f
    @Volatile private var cachedBaseAcc = Float.NaN
    @Volatile private var cachedIsRandomAcc = false

    @Volatile private var lastSpeedUpdateTime = 0L

    // ⭐ FIX 10 — Tracking periode "diam" (stationary)
    @Volatile private var stationaryUntilMs = 0L

    // ⭐ Litmus-aware state
    @Volatile private var kalmanAwareAt: Long = 0L
    @Volatile private var kalmanActive: Boolean = false
    private const val KALMAN_AWARE_TTL_MS = 5_000L

    private fun isKalmanActive(): Boolean {
        val now = getElapsedRealtime()
        if (now - kalmanAwareAt < KALMAN_AWARE_TTL_MS) return kalmanActive
        kalmanActive = try { LitmusObserver.isKalmanActive() } catch (_: Throwable) { false }
        kalmanAwareAt = now
        return kalmanActive
    }

    fun normalizeLat(lat: Double): Double {
        return lat.coerceIn(-90.0, 90.0)
    }

    fun normalizeLng(lng: Double): Double {
        var l = lng % 360.0
        if (l > 180.0) l -= 360.0
        if (l < -180.0) l += 360.0
        return l
    }

    fun roundTo6Decimals(value: Double): Double {
        return value
    }

    fun roundTo7Decimals(value: Double): Double {
        return value
    }

    fun formatGoogleMapsCoord(value: Double): String {
        return try {
            BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        } catch (_: Throwable) {
            value.toString()
        }
    }

    fun randomize6thDecimal(value: Double): Double {
        val bd = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)
        val str = bd.toPlainString()
        val dotIdx = str.indexOf('.')
        if (dotIdx == -1 || str.length <= dotIdx + 6) {
            return value
        }
        val current6th = str[dotIdx + 6].digitToInt()
        var new6th = random.nextInt(9)
        if (new6th >= current6th) {
            new6th += 1
        }
        val prefix = str.substring(0, dotIdx + 6)
        val newStr = "$prefix$new6th"
        return try {
            BigDecimal(newStr).toDouble()
        } catch (_: Throwable) {
            value
        }
    }

    fun randomize7thDecimal(value: Double): Double {
        val bd = BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP)
        val str = bd.toPlainString()
        val dotIdx = str.indexOf('.')
        if (dotIdx == -1 || str.length <= dotIdx + 7) {
            return value
        }
        val current7th = str[dotIdx + 7].digitToInt()
        var new7th = random.nextInt(9)
        if (new7th >= current7th) {
            new7th += 1
        }
        val prefix = str.substring(0, dotIdx + 7)
        val newStr = "$prefix$new7th"
        return try {
            BigDecimal(newStr).toDouble()
        } catch (_: Throwable) {
            value
        }
    }

    fun randomizeGoogleMapsCoord(value: Double, maxMeters: Float = 5.0f): Double {
        val metersDelta = (random.nextGaussian() * (maxMeters / 3.0)).coerceIn(-maxMeters.toDouble(), maxMeters.toDouble())
        val degDelta = metersDelta / 111320.0
        return value + degDelta
    }

    // ⭐ Kalman-aware coordinate jitter
    fun computeRealisticCoordinates(
        baseLat: Double,
        baseLng: Double,
        isRandomEnabled: Boolean,
        radiusMeters: Float = 0.0f
    ): Pair<Double, Double> {
        val safeLat = normalizeLat(baseLat)
        val safeLng = normalizeLng(baseLng)

        if (!isRandomEnabled) {
            cachedBaseLat = baseLat
            cachedBaseLng = baseLng
            cachedRandomLat = safeLat
            cachedRandomLng = safeLng
            cachedIsRandomLoc = false
            return Pair(safeLat, safeLng)
        }

        val kalman = isKalmanActive()

        // ⭐ Kalman-aware: perlebar interval supaya perubahan tidak terlalu cepat
        val effectiveInterval = if (kalman) RANDOM_INTERVAL_MS * 2 else RANDOM_INTERVAL_MS

        val now = getElapsedRealtime()
        val elapsed = now - lastRandomCoordTime
        val baseChanged = baseLat != cachedBaseLat ||
                          baseLng != cachedBaseLng ||
                          !cachedIsRandomLoc

        if (baseChanged || elapsed >= effectiveInterval || lastRandomCoordTime == 0L) {
            if (radiusMeters > 0.0f) {
                // ⭐ Kalman-aware: kurangi amplitude jitter (Kalman sudah smooth)
                val damped = if (kalman) radiusMeters * 0.4f else radiusMeters
                val deltaLat = (random.nextGaussian() * (damped / 3.0))
                    .coerceIn(-damped.toDouble(), damped.toDouble()) / 111320.0
                val deltaLng = (random.nextGaussian() * (damped / 3.0))
                    .coerceIn(-damped.toDouble(), damped.toDouble()) /
                    (111320.0 * kotlin.math.cos(Math.toRadians(safeLat)).coerceAtLeast(0.01))
                cachedRandomLat = normalizeLat(safeLat + deltaLat)
                cachedRandomLng = normalizeLng(safeLng + deltaLng)
            } else {
                cachedRandomLat = normalizeLat(randomize6thDecimal(safeLat))
                cachedRandomLng = normalizeLng(randomize6thDecimal(safeLng))
            }
            cachedBaseLat = baseLat
            cachedBaseLng = baseLng
            cachedIsRandomLoc = true
            lastRandomCoordTime = now
        }

        return Pair(cachedRandomLat, cachedRandomLng)
    }

    // ⭐ Kalman-aware accuracy jitter
    fun computeRealisticAccuracy(baseAccuracy: Float, isRandomAccuracyEnabled: Boolean): Float {
        val kalman = isKalmanActive()
        val targetBase = if (baseAccuracy > 15f || baseAccuracy <= 0f) 5.0f else baseAccuracy

        if (!isRandomAccuracyEnabled) {
            val fixedAcc = targetBase.coerceIn(3.0f, 12.0f)
            cachedBaseAcc = baseAccuracy
            cachedRandomAcc = fixedAcc
            cachedIsRandomAcc = false
            return fixedAcc
        }

        val now = getElapsedRealtime()
        val elapsed = now - lastRandomAccTime
        val baseChanged = baseAccuracy != cachedBaseAcc || !cachedIsRandomAcc

        // ⭐ Kalman-aware: interval lebih lambat + jitter lebih kecil
        val effectiveInterval = if (kalman) RANDOM_INTERVAL_MS * 2 else RANDOM_INTERVAL_MS
        val jitterMul = if (kalman) 0.4f else 1.0f

        if (baseChanged || elapsed >= effectiveInterval || lastRandomAccTime == 0L) {
            val jitter = (random.nextGaussian() * jitterMul).toFloat()
            cachedRandomAcc = (targetBase + jitter).coerceIn(3.0f, 12.0f)
            cachedBaseAcc = baseAccuracy
            cachedIsRandomAcc = true
            lastRandomAccTime = now
        }

        return cachedRandomAcc
    }

    fun computeRealisticAltitude(): Double {
        val now = getElapsedRealtime()
        val dtSec = if (lastAltTime == 0L) 1.0
                    else ((now - lastAltTime) / 1000.0).coerceIn(0.0, 1.0)
        val raw = lastAltitude + (random.nextGaussian() * 0.15)
        val maxDelta = MAX_ALT_DELTA_M_PER_SEC * dtSec
        val clamped = lastAltitude + (raw - lastAltitude).coerceIn(-maxDelta, maxDelta)
        lastAltitude = clamped.coerceIn(5.0, 150.0)
        lastAltTime = now
        return lastAltitude
    }

    // ⭐ Kalman-aware bearing (max 1°/s saat Kalman aktif)
    fun computeRealisticBearing(): Float {
        val now = getElapsedRealtime()
        val kalman = isKalmanActive()

        val dtSec = if (lastBearingTime == 0L) 1.0
                    else ((now - lastBearingTime) / 1000.0).coerceIn(0.0, 1.0)

        val maxDegPerSec = if (kalman) 1.0f else MAX_BEARING_DELTA_DEG_PER_SEC
        val noiseMul = if (kalman) 1.0 else 2.0

        val raw = (lastBearing + (random.nextGaussian() * noiseMul).toFloat()) % 360f
        val rawNorm = if (raw < 0) raw + 360f else raw

        val maxDelta = maxDegPerSec * dtSec.toFloat()
        var diff = rawNorm - lastBearing
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f

        val clamped = lastBearing + diff.coerceIn(-maxDelta, maxDelta)
        lastBearing = if (clamped < 0) clamped + 360f else clamped % 360f
        lastBearingTime = now
        return lastBearing
    }

    const val MAX_SPOOF_SPEED_MPS = 1.5f

    // ⭐ Kalman-aware speed (35% stationary chance saat Kalman aktif)
    fun computeRealisticSpeed(baseSpeedMps: Float): Float {
        val now = getElapsedRealtime()
        val kalman = isKalmanActive()

        val stationaryChance = if (kalman) 35 else 20

        if (now < stationaryUntilMs) return 0.0f

        if (now - lastSpeedUpdateTime >= 1000L || lastSpeedUpdateTime == 0L) {
            if (random.nextInt(100) < stationaryChance) {
                val stationaryDurationMs = (5_000L + random.nextInt(10_000)).toLong()
                stationaryUntilMs = now + stationaryDurationMs
                lastSpeedUpdateTime = now
                lastSpeed = 0.0f
                return 0.0f
            }

            val effectiveBase = if (baseSpeedMps <= 0f) 0.8f
                                else baseSpeedMps.coerceIn(0.1f, MAX_SPOOF_SPEED_MPS)

            val variationMul = if (kalman) 0.12f else 0.25f
            val variation = (random.nextGaussian() * variationMul).toFloat()
            lastSpeed = (effectiveBase + variation).coerceIn(0.0f, MAX_SPOOF_SPEED_MPS)
            lastSpeedUpdateTime = now
        }
        return lastSpeed
    }

    fun resetRandomCache() {
        lastRandomCoordTime = 0L
        lastRandomAccTime = 0L
        cachedBaseLat = Double.NaN
        cachedBaseLng = Double.NaN
        cachedBaseAcc = Float.NaN
        cachedIsRandomLoc = false
        cachedIsRandomAcc = false
        stationaryUntilMs = 0L
    }

    /**
     * ⭐ Consistency guard — validasi cross-field (speed ↔ pergerakan).
     * Dipanggil dari LocationHook setelah snapshot di-refresh.
     */
    fun applyConsistencyGuard(
        lat: Double,
        lng: Double,
        speed: Float,
        bearing: Float
    ): Pair<Float, Float> {
        return try {
            val adj = SpoofConsistencyGuard.validate(lat, lng, speed, bearing)
            adj.speed to adj.bearing
        } catch (_: Throwable) {
            speed to bearing
        }
    }

    fun computeAccelerometerSpeed(speedMps: Float): Float {
        // ⭐ Kalau speed = 0 (diam), accel speed juga 0
        if (speedMps <= 0.01f) return 0.0f
        val clampedSpeed = speedMps.coerceIn(0.0f, MAX_SPOOF_SPEED_MPS)
        val variation = (random.nextGaussian() * 0.08).toFloat()
        return (clampedSpeed + variation).coerceIn(0.0f, MAX_SPOOF_SPEED_MPS)
    }

    fun computeAccelerometerBearing(bearingDegrees: Float): Float {
        var b = (bearingDegrees + (random.nextGaussian() * 0.5).toFloat()) % 360f
        if (b < 0) b += 360f
        return b
    }

    fun applyAccelerometerValues(values: FloatArray, speedMps: Float, bearingDegrees: Float) {
        if (values.size < 3) return
        // ⭐ Kalau diam, accelerometer hanya gravitasi + noise kecil
        if (speedMps <= 0.01f) {
            values[0] = (random.nextGaussian() * 0.01).toFloat()
            values[1] = (random.nextGaussian() * 0.01).toFloat()
            values[2] = 9.80665f + (random.nextGaussian() * 0.02).toFloat()
            return
        }
        val forwardAcc = (0.12f * (speedMps / 4f).coerceIn(0f, 2f)) +
                         (random.nextGaussian() * 0.04).toFloat()
        values[0] = (random.nextGaussian() * 0.03).toFloat()
        values[1] = forwardAcc
        values[2] = 9.80665f + (random.nextGaussian() * 0.04).toFloat()
    }

    fun applyLinearAccelerationValues(values: FloatArray, speedMps: Float, bearingDegrees: Float) {
        if (values.size < 3) return
        if (speedMps <= 0.01f) {
            values[0] = (random.nextGaussian() * 0.005).toFloat()
            values[1] = (random.nextGaussian() * 0.005).toFloat()
            values[2] = (random.nextGaussian() * 0.005).toFloat()
            return
        }
        val forwardAcc = (0.12f * (speedMps / 4f).coerceIn(0f, 2f)) +
                         (random.nextGaussian() * 0.03).toFloat()
        values[0] = (random.nextGaussian() * 0.02).toFloat()
        values[1] = forwardAcc
        values[2] = (random.nextGaussian() * 0.03).toFloat()
    }

    fun applyOrientationValues(values: FloatArray, bearingDegrees: Float) {
        if (values.isEmpty()) return
        var azimuth = (bearingDegrees + (random.nextGaussian() * 0.5).toFloat()) % 360f
        if (azimuth < 0) azimuth += 360f
        values[0] = azimuth
        if (values.size > 1) values[1] = (random.nextGaussian() * 0.3).toFloat()
        if (values.size > 2) values[2] = (random.nextGaussian() * 0.2).toFloat()
    }

    fun applyRotationVectorValues(values: FloatArray, bearingDegrees: Float) {
        if (values.size < 3) return
        val rad = Math.toRadians(bearingDegrees.toDouble()).toFloat()
        values[0] = 0.0f
        values[1] = 0.0f
        values[2] = kotlin.math.sin(rad / 2.0f)
        if (values.size > 3) values[3] = kotlin.math.cos(rad / 2.0f)
        if (values.size > 4) values[4] = 0.5f
    }

    fun applyGyroscopeValues(values: FloatArray, bearingDegrees: Float) {
        if (values.size < 3) return
        values[0] = (random.nextGaussian() * 0.005).toFloat()
        values[1] = (random.nextGaussian() * 0.005).toFloat()
        values[2] = (random.nextGaussian() * 0.008).toFloat()
    }

    /**
     * ⭐ FIX 7 — Magnetometer sync dengan bearing spoof.
     *
     * Earth magnetic field ~25-65 μT tergantung lokasi.
     * Untuk Indonesia ~45 μT. Rotasi horizontal field
     * sesuai bearing yang kita spoof, supaya konsisten.
     */
    fun applyMagnetometerValues(values: FloatArray, bearingDegrees: Float) {
        if (values.size < 3) return

        val magnitude = 40.0 + random.nextGaussian() * 3.0

        val bearingRad = Math.toRadians(bearingDegrees.toDouble())

        val horizontalComponent = magnitude * 0.85
        val verticalComponent = magnitude * 0.15

        val x = horizontalComponent * kotlin.math.cos(bearingRad)
        val y = horizontalComponent * kotlin.math.sin(bearingRad)
        val z = verticalComponent

        values[0] = (x + random.nextGaussian() * 0.5).toFloat()
        values[1] = (y + random.nextGaussian() * 0.5).toFloat()
        values[2] = (z + random.nextGaussian() * 0.3).toFloat()
    }
}