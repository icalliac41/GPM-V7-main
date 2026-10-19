package com.gpm.virtual.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gpm.virtual.manager.SpoofLifecycleManager

class SpoofPersistReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != "com.gpm.virtual.ACTION_PERSIST_SPOOF_REQUEST") return

        // ⭐ FIX #2: UID check — defense in depth
        // Meski manifest sudah declare android:permission, ini layer tambahan
        if (!isCallerTrusted(context)) return

        val prefix = intent.getStringExtra("prefix") ?: return
        val active = intent.getBooleanExtra("active", false)
        val hasTarget = intent.getBooleanExtra("has_target", active)
        val lat = intent.getDoubleExtra("lat", 0.0)
        val lng = intent.getDoubleExtra("lng", 0.0)

        // ... logika existing untuk update state ...
    }

    /**
     * ⭐ FIX #2: Cek apakah caller adalah package yang diizinkan.
     * Karena SpoofPersistReceiver di-declare dengan android:permission,
     * broadcast dari app lain sudah di-drop oleh sistem.
     * Tapi kita tambah layer ini untuk defense-in-depth.
     */
    private fun isCallerTrusted(context: Context): Boolean {
        return try {
            val uid = android.os.Binder.getCallingUid()
            val pm = context.packageManager
            val pkgs = pm.getPackagesForUid(uid) ?: return false
            for (p in pkgs) {
                if (p == "com.gojek.partner" ||
                    p == "com.grabtaxi.driver2" ||
                    p == "com.grab.driver" ||
                    p == "com.gpm.virtual" ||
                    p == "com.gpm.virtual.mchaji"
                ) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            // Fail-safe: kalau gagal, tolak
            false
        }
    }
}