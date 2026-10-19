package com.gpm.virtual.xposed

import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper log anti-spam dengan 2 mekanisme:
 *  1. Once-only per key — untuk event berulang (lifecycle, newActivity).
 *  2. Time-throttled per key — untuk log yang perlu update tapi jarang.
 *
 * Thread-safe global. Bisa dipanggil dari mana saja.
 */
object OnceLogger {

    private val seenOnce = ConcurrentHashMap.newKeySet<String>()
    private val lastLogAt = ConcurrentHashMap<String, Long>()

    /**
     * Log hanya sekali per [key] dalam siklus hidup proses.
     */
    fun logOnce(
        module: XposedModule?,
        tag: String,
        key: String,
        msg: String,
        level: Int = Log.INFO
    ) {
        if (!seenOnce.add(key)) return
        emit(module, level, tag, msg)
    }

    /**
     * Log dibatasi minimal setiap [intervalMs] untuk [key] yang sama.
     */
    fun logThrottled(
        module: XposedModule?,
        tag: String,
        key: String,
        msg: String,
        intervalMs: Long = 5_000L,
        level: Int = Log.INFO
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = lastLogAt[key] ?: 0L
        if (now - last < intervalMs) return
        lastLogAt[key] = now
        emit(module, level, tag, msg)
    }

    private fun emit(module: XposedModule?, level: Int, tag: String, msg: String) {
        try { module?.log(level, tag, msg) } catch (_: Throwable) {}
        try { Log.println(level, tag, msg) } catch (_: Throwable) {}
    }

    /** Reset state — untuk testing. */
    fun reset() {
        seenOnce.clear()
        lastLogAt.clear()
    }
}