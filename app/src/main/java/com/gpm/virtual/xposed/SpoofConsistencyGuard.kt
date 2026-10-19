package com.gpm.virtual.xposed

import android.os.SystemClock
import com.gpm.virtual.BuildConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SpoofConsistencyGuard {

    private const val TAG = "GMP:SpoofGuard"

    private const val MAX_SPEED_MPS = 30.0
    private const val MIN_INTERVAL_MS = 200L
    private const val BEARING_MISMATCH_DEG = 30.0
    private const val SPEED_MISMATCH_FACTOR = 3.0
    private const val ACTIVATION_GRACE_MS = 15_000L

    private const val TRANSITION_DISTANCE_THRESHOLD_M = 500.0
    private const val TARGET_TRANSITION_SPEED_MPS = 16.67
    private const val MIN_TRANSITION_MS = 3_000L
    private const val MAX_TRANSITION_MS = 15_000L

    private data class LastPing(
        val lat: Double, val lng: Double, val speed: Float,
        val bearing: Float, val timestampMs: Long, val elapsedNanos: Long
    )

    @Volatile private var lastPing: LastPing? = null
    @Volatile private var warningCount: Long = 0
    @Volatile private var pingCount: Long = 0
    @Volatile private var lastWarnAt: Long = 0L
    @Volatile private var activatedAtMs: Long = 0L

    @Volatile private var transitionActive = false
    @Volatile private var transitionStartMs = 0L
    @Volatile private var transitionDurationMs = MIN_TRANSITION_MS
    @Volatile private var transitionFromLat = 0.0
    @Volatile private var transitionFromLng = 0.0
    @Volatile private var transitionToLat = 0.0
    @Volatile private var transitionToLng = 0.0
    @Volatile private var transitionDistanceM = 0.0
    @Volatile private var transitionEffectiveSpeedKmh = 0.0

    data class Adjusted(val speed: Float, val bearing: Float, val flagged: Boolean)

    data class TransitionInfo(
        val active: Boolean, val progress: Float,
        val distanceM: Double, val durationMs: Long,
        val effectiveSpeedKmh: Double,
        val fromLat: Double, val fromLng: Double,
        val toLat: Double, val toLng: Double
    )

    fun markSpoofActivated() {
        activatedAtMs = SystemClock.elapsedRealtime()
        lastPing = null
        warningCount = 0
        pingCount = 0
    }

    fun beginTransition(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Boolean {
        val dist = haversineMeters(fromLat, fromLng, toLat, toLng)
        if (dist < TRANSITION_DISTANCE_THRESHOLD_M) {
            transitionActive = false
            return false
        }
        val idealMs = (dist / TARGET_TRANSITION_SPEED_MPS * 1000.0).toLong()
        val duration = idealMs.coerceIn(MIN_TRANSITION_MS, MAX_TRANSITION_MS)
        val effectiveSpeedMs = dist / (duration / 1000.0)
        val effectiveSpeedKmh = effectiveSpeedMs * 3.6

        transitionFromLat = fromLat
        transitionFromLng = fromLng
        transitionToLat = toLat
        transitionToLng = toLng
        transitionStartMs = SystemClock.elapsedRealtime()
        transitionDurationMs = duration
        transitionDistanceM = dist
        transitionEffectiveSpeedKmh = effectiveSpeedKmh
        transitionActive = true
        lastPing = null
        return true
    }

    fun isTransitioning(): Boolean {
        if (!transitionActive) return false
        val elapsed = SystemClock.elapsedRealtime() - transitionStartMs
        if (elapsed >= transitionDurationMs) {
            transitionActive = false
            return false
        }
        return true
    }

    fun getTransitionProgress(): Float {
        if (!transitionActive) return 1f
        val elapsed = SystemClock.elapsedRealtime() - transitionStartMs
        return (elapsed.toFloat() / transitionDurationMs).coerceIn(0f, 1f)
    }

    fun getTransitionInfo(): TransitionInfo = TransitionInfo(
        active = isTransitioning(),
        progress = getTransitionProgress(),
        distanceM = transitionDistanceM,
        durationMs = transitionDurationMs,
        effectiveSpeedKmh = transitionEffectiveSpeedKmh,
        fromLat = transitionFromLat,
        fromLng = transitionFromLng,
        toLat = transitionToLat,
        toLng = transitionToLng
    )

    fun interpolateTransition(targetLat: Double, targetLng: Double): Pair<Double, Double> {
        if (!transitionActive) return targetLat to targetLng
        val elapsed = SystemClock.elapsedRealtime() - transitionStartMs
        if (elapsed >= transitionDurationMs) {
            transitionActive = false
            return targetLat to targetLng
        }
        val t = elapsed.toDouble() / transitionDurationMs
        val smooth = if (t < 0.5) 4.0 * t * t * t else 1.0 - ((-2.0 * t + 2.0).pow(3.0)) / 2.0
        val lat = transitionFromLat + (transitionToLat - transitionFromLat) * smooth
        val lng = transitionFromLng + (transitionToLng - transitionFromLng) * smooth
        return lat to lng
    }

    fun cancelTransition() { transitionActive = false }

    fun estimateDurationMs(distanceM: Double): Long {
        if (distanceM < TRANSITION_DISTANCE_THRESHOLD_M) return 0L
        val idealMs = (distanceM / TARGET_TRANSITION_SPEED_MPS * 1000.0).toLong()
        return idealMs.coerceIn(MIN_TRANSITION_MS, MAX_TRANSITION_MS)
    }

    enum class DistanceTier { INSTANT, SHORT, MEDIUM, LONG, EXTREME }

    fun classifyDistance(distanceM: Double): DistanceTier = when {
        distanceM < 500.0 -> DistanceTier.INSTANT
        distanceM < 2_000.0 -> DistanceTier.SHORT
        distanceM < 10_000.0 -> DistanceTier.MEDIUM
        distanceM < 50_000.0 -> DistanceTier.LONG
        else -> DistanceTier.EXTREME
    }

    fun getTierWarning(tier: DistanceTier): String? = when (tier) {
        DistanceTier.INSTANT -> null
        DistanceTier.SHORT -> null
        DistanceTier.MEDIUM -> "Transisi cepat. Hindari toggle saat order aktif."
        DistanceTier.LONG -> "⚠️ Jarak jauh. Server bisa deteksi."
        DistanceTier.EXTREME -> "⚠️⚠️ Jarak sangat jauh. Risiko tinggi."
    }

    /**
     * PERBAIKAN: Validasi speed/bearing tetap jalan di testing mode dengan toleransi tinggi.
     * Ini mencegah anomali speed/bearing yang bikin server Grab blokir heatmap.
     */
    fun validate(lat: Double, lng: Double, baseSpeed: Float, baseBearing: Float): Adjusted {
        if (isTransitioning()) return Adjusted(baseSpeed, baseBearing, false)

        val isTesting = try {
            val f = Class.forName("com.gpm.virtual.xposed.SortirConfig")
                .getDeclaredField("FORCE_ENABLE_FOR_TESTING")
            f.isAccessible = true
            f.getBoolean(null)
        } catch (_: Throwable) { false }

        val now = SystemClock.elapsedRealtime()
        if (activatedAtMs > 0 && (now - activatedAtMs) < ACTIVATION_GRACE_MS) {
            lastPing = LastPing(lat, lng, baseSpeed, baseBearing, now, SystemClock.elapsedRealtimeNanos())
            return Adjusted(baseSpeed, baseBearing, false)
        }

        pingCount++
        val prev = lastPing
        if (prev == null) {
            lastPing = LastPing(lat, lng, baseSpeed, baseBearing, now, SystemClock.elapsedRealtimeNanos())
            return Adjusted(baseSpeed, baseBearing, false)
        }

        val dtMs = now - prev.timestampMs
        if (dtMs < MIN_INTERVAL_MS) return Adjusted(baseSpeed, baseBearing, false)

        val dtSec = dtMs / 1000.0
        val dLat = (lat - prev.lat) * 111320.0
        val dLng = (lng - prev.lng) * 111320.0 * cos(Math.toRadians(lat))
        val distanceM = sqrt(dLat * dLat + dLng * dLng)
        val actualSpeed = distanceM / dtSec

        val maxAllowedSpeed = if (isTesting) 100.0 else MAX_SPEED_MPS
        val isImpossible = actualSpeed > maxAllowedSpeed

        val speedRatio = if (baseSpeed > 0.01f) actualSpeed / baseSpeed
                         else if (actualSpeed > 0.5) 999.0 else 1.0
        val mismatchFactor = if (isTesting) 10.0 else SPEED_MISMATCH_FACTOR
        val speedMismatch = speedRatio > mismatchFactor || speedRatio < (1.0 / mismatchFactor)

        val actualBearing = if (distanceM > 0.5) {
            ((Math.toDegrees(atan2(dLng, dLat)) + 360.0) % 360.0).toFloat()
        } else baseBearing

        val bearingDiff = if (distanceM > 2.0) {
            var diff = abs(actualBearing - baseBearing)
            if (diff > 180f) diff = 360f - diff
            diff
        } else 0f

        val bearingThreshold = if (isTesting) 90f else BEARING_MISMATCH_DEG.toFloat()
        val bearingMismatch = bearingDiff > bearingThreshold
        val flagged = isImpossible || (speedMismatch && distanceM > 2.0)
        if (flagged) warningCount++

        val finalSpeed = when {
            isImpossible -> baseSpeed
            distanceM > 5.0 && actualSpeed in 0.1..MAX_SPEED_MPS ->
                actualSpeed.toFloat().coerceIn(0f, MAX_SPEED_MPS.toFloat())
            distanceM < 1.0 && baseSpeed > 5f -> (baseSpeed * 0.3f).coerceAtLeast(0.1f)
            else -> baseSpeed
        }

        val finalBearing = if (distanceM > 5.0 && bearingMismatch) actualBearing else baseBearing

        lastPing = LastPing(lat, lng, finalSpeed, finalBearing, now, SystemClock.elapsedRealtimeNanos())
        return Adjusted(finalSpeed, finalBearing, flagged)
    }

    fun reset() {
        lastPing = null
        cancelTransition()
        activatedAtMs = 0L
        warningCount = 0
        pingCount = 0
    }

    fun stats(): String = buildString {
        append("pings=$pingCount flagged=$warningCount")
        if (transitionActive) append(" [transition ${(getTransitionProgress() * 100).toInt()}%]")
    }

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2.0).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return R * c
    }
}