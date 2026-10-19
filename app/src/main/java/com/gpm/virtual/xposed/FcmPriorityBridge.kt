package com.gpm.virtual.xposed

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════
 *  FcmPriorityBridge — jembatan antara FCM callback & OkHttp
 * ═══════════════════════════════════════════════════════════════
 *
 *  Ketika FCM message masuk, Gojek akan SEGERA fetch detail
 *  order via OkHttp (biasanya dalam 200-500ms). Jendela ini
 *  kritis untuk "push priority".
 *
 *  Bridge ini:
 *    1. Menandai timestamp FCM terakhir masuk
 *    2. Memberitahu OkHttp interceptor "sedang dalam jendela FCM"
 *    3. OkHttp interceptor menaikkan prioritas request yang masuk
 *       dalam jendela tersebut
 *
 *  Tidak menyimpan payload / isi pesan — hanya timestamp. Aman.
 */
object FcmPriorityBridge {

    @Volatile private var lastFcmTimestamp: Long = 0L
    private val fcmCount = AtomicLong(0L)

    // Jendela prioritas setelah FCM masuk. 2000ms cukup untuk
    // fetch order + retry kalau network lambat.
    private const val PRIORITY_WINDOW_MS = 2000L

    /**
     * Dipanggil dari hook FirebaseMessagingService.onMessageReceived.
     */
    fun notifyFcmReceived() {
        lastFcmTimestamp = SystemClock.elapsedRealtime()
        fcmCount.incrementAndGet()
    }

    /**
     * Dipanggil dari OkHttp interceptor untuk cek apakah kita
     * sedang dalam jendela prioritas FCM.
     */
    fun isInFcmWindow(): Boolean {
        val last = lastFcmTimestamp
        if (last == 0L) return false
        val age = SystemClock.elapsedRealtime() - last
        return age in 0..PRIORITY_WINDOW_MS
    }

    fun getFcmCount(): Long = fcmCount.get()

    fun reset() {
        lastFcmTimestamp = 0L
        fcmCount.set(0L)
    }
}