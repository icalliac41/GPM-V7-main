package com.gpm.virtual.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ConfigRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        // ⭐ FIX #2: UID check
        if (!isCallerTrusted(context)) return

        when (intent.action) {
            "com.gpm.virtual.SORTIR_CONFIG_REQUEST" -> {
                // ... logika existing ...
            }
            "com.gpm.virtual.CANCEL_CONFIG_REQUEST" -> {
                // ... logika existing ...
            }
        }
    }

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
        } catch (_: Throwable) { false }
    }
}