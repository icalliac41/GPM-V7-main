package com.gpm.virtual.xposed

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

object ShadowState {

    private data class Entry(val markedAtMs: Long)

    private const val TTL_MS = 5L * 60_000L
    private const val MAX_ENTRIES = 256

    private val shadows = ConcurrentHashMap<String, Entry>()

    @Synchronized
    fun markShadow(bookingCode: String) {
        if (bookingCode.isBlank()) return
        evictExpiredLocked()
        if (shadows.size >= MAX_ENTRIES) {
            shadows.entries.minByOrNull { it.value.markedAtMs }?.let {
                shadows.remove(it.key)
            }
        }
        shadows[bookingCode] = Entry(SystemClock.elapsedRealtime())
    }

    fun clearShadow(bookingCode: String) {
        if (bookingCode.isBlank()) return
        shadows.remove(bookingCode)
    }

    fun isShadowed(bookingCode: String): Boolean {
        if (bookingCode.isBlank()) return false
        val e = shadows[bookingCode] ?: return false
        if (SystemClock.elapsedRealtime() - e.markedAtMs > TTL_MS) {
            shadows.remove(bookingCode)
            return false
        }
        return true
    }

    fun clearAll() { shadows.clear() }

    fun size(): Int {
        evictExpiredLocked()
        return shadows.size
    }

    private fun evictExpiredLocked() {
        val now = SystemClock.elapsedRealtime()
        val it = shadows.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.markedAtMs > TTL_MS) it.remove()
        }
    }
}