package com.gpm.virtual.xposed

import android.os.SystemClock
import com.gpm.virtual.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object TimestampCoordinator {

    private val TAG: String =
        if (BuildConfig.DEBUG) "GMP:Gojek:[TIMESTAMP]" else "Trace"

    private const val MIN_GAP_MS = 200L
    private const val MAX_GAP_MS = 5L * 60L * 1000L
    private const val DEFAULT_GAP_MS = 1500L

    // ⭐ FITUR BARU: Jitter & Cache
    private const val SHIFT_CACHE_TTL_MS = 3000L
    private const val MAX_JITTER_MS = 150L

    @Volatile private var cachedShift: Long = 0L
    @Volatile private var cachedShiftAt: Long = 0L

    private val lastLogTime = AtomicLong(0L)
    private const val LOG_INTERVAL_MS = 5_000L

    fun computeAppliedShift(): Long {
        if (!LocationHook.isHookActive || !LocationHook.isSpoofActive) return 0L
        val baseShift = LocationHook.getPingOffsetMs()
        if (baseShift == 0L) return 0L

        val now = SystemClock.elapsedRealtime()
        
        if (now - cachedShiftAt < SHIFT_CACHE_TTL_MS && cachedShift != 0L) {
            return cachedShift
        }

        val jitter = Random.nextLong(-MAX_JITTER_MS, MAX_JITTER_MS + 1)
        cachedShift = baseShift + jitter
        cachedShiftAt = now

        logThrottled("New Dynamic Shift applied: $cachedShift ms (Base: $baseShift, Jitter: $jitter)")
        return cachedShift
    }

    @Deprecated("Gunakan computeAppliedShift()", ReplaceWith("computeAppliedShift()"))
    fun computeDelta(): Long {
        if (!LocationHook.isHookActive || !LocationHook.isSpoofActive) return 0L
        val shift = computeAppliedShift()
        return if (shift != 0L) -shift else 0L
    }

    data class Result(
        val locationTimestamp: Long,
        val pingTimestamp: Long,
        val deviceTime: Long,
        val applied: Boolean,
        val shift: Long
    ) {
        @Deprecated("Gunakan shift", ReplaceWith("shift"))
        val delta: Long get() = -shift
    }

    fun coordinate(
        originalLocationTs: Long,
        originalPingTs: Long,
        originalDeviceTime: Long
    ): Result {
        val shift = computeAppliedShift()
        if (shift == 0L) {
            return Result(originalLocationTs, originalPingTs, originalDeviceTime, false, 0L)
        }

        val newLocTs = originalLocationTs + shift
        val newPingTs = originalPingTs + shift
        val newDeviceTime = originalDeviceTime + shift

        val (finalLocTs, finalPingTs) = ensureNaturalGap(newLocTs, newPingTs)

        return Result(finalLocTs, finalPingTs, newDeviceTime, true, shift)
    }

    fun applyDelta(originalValue: Long): Long {
        val shift = computeAppliedShift()
        return if (shift != 0L) originalValue + shift else originalValue
    }

    private fun ensureNaturalGap(locTs: Long, pingTs: Long): Pair<Long, Long> {
        val gap = pingTs - locTs
        return when {
            gap < MIN_GAP_MS -> locTs to (locTs + DEFAULT_GAP_MS)
            gap > MAX_GAP_MS -> locTs to (locTs + MAX_GAP_MS)
            else -> locTs to pingTs
        }
    }

    @Suppress("unused")
    private fun logThrottled(msg: String) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val last = lastLogTime.get()
        if (now - last >= LOG_INTERVAL_MS && lastLogTime.compareAndSet(last, now)) {
            try {
                LocationHook.logFromCoordinator(TAG, msg)
            } catch (_: Throwable) {}
        }
    }
}