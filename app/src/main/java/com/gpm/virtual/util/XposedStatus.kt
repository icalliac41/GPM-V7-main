package com.gpm.virtual.util

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import com.gpm.virtual.data.preferences.GpsPreferences
import com.gpm.virtual.data.provider.LocationConfigProvider

/**
 * Detect apakah GPM sedang aktif via LSPosed.
 *
 * Di LSPosed modern (API 102), modul berjalan di proses aplikasi target
 * (Gojek, Grab, Maps). Modul sendiri TIDAK di-hook.
 *
 * Sumber deteksi (urut prioritas):
 *   1. isHookedDirectly — di-set true saat onPackageReady trigger
 *   2. Handshake heartbeat dari target app (LocationConfigProvider)
 *   3. Persistent flag KEY_MODULE_ACTIVE_VERIFIED dari SharedPreferences
 *
 * Catatan: TIDAK ada fallback ke "user buka toggle hook" karena itu
 * tidak membuktikan modul LSPosed aktif (bisa saja toggle tersimpan
 * tapi modul tidak loaded).
 */
object XposedStatus {

    @Volatile
    @JvmField
    var isHookedDirectly: Boolean = false

    @Volatile
    private var verifiedFrameworkName: String = "LSPosed Modern (API 102)"

    @JvmStatic
    fun isModuleActive(context: Context? = null): Boolean {
        // Sumber 1: Flag dari hook init
        if (isHookedDirectly) return true

        val ctx = context ?: LocationConfigProvider.sApplicationContext
            ?: return false

        // Sumber 2: In-memory heartbeat dari target app handshake
        val memHeartbeat = LocationConfigProvider.lastHeartbeatTime
        val now = System.currentTimeMillis()
        if (memHeartbeat > 0 && (now - memHeartbeat < 7 * 24 * 60 * 60 * 1000L)) {
            return true
        }

        // Sumber 3: Persistent flag dari handshake sebelumnya
        try {
            val prefs = ctx.getSharedPreferences(GpsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(GpsPreferences.KEY_MODULE_ACTIVE_VERIFIED, false)) {
                return true
            }
        } catch (_: Throwable) {
        }

        // Tidak ada sumber valid → modul TIDAK aktif di scope
        return false
    }

    @JvmStatic
    fun getStatusDetail(context: Context? = null): String {
        val ctx = context ?: LocationConfigProvider.sApplicationContext
        val prefs = ctx?.getSharedPreferences(GpsPreferences.PREFS_NAME, Context.MODE_PRIVATE)

        val lastPkg = LocationConfigProvider.lastPingedPackage
            ?: prefs?.getString(GpsPreferences.KEY_LAST_TARGET_PKG, null)
        val fw = prefs?.getString(GpsPreferences.KEY_LAST_FRAMEWORK, verifiedFrameworkName)
            ?: verifiedFrameworkName

        return if (!lastPkg.isNullOrEmpty()) {
            "LSPosed Aktif • Terhubung: $lastPkg"
        } else if (isModuleActive(context)) {
            "LSPosed Aktif di Target Scope"
        } else {
            "Modul belum aktif • Buka salah satu app target (Gojek/Maps) untuk verifikasi"
        }
    }

    @JvmStatic
    fun getFrameworkName(): String = verifiedFrameworkName

    /**
     * Dipanggil manual dari hook saat modul berhasil terpasang di target app.
     * Menggantikan logika probe yang tidak reliable.
     */
    @JvmStatic
    fun markActiveFromHook(ctx: Context?, framework: String = "LSPosed API 102") {
        isHookedDirectly = true
        verifiedFrameworkName = framework
        if (ctx != null) {
            try {
                ctx.getSharedPreferences(GpsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(GpsPreferences.KEY_MODULE_ACTIVE_VERIFIED, true)
                    .putString(GpsPreferences.KEY_LAST_FRAMEWORK, framework)
                    .putLong(GpsPreferences.KEY_LAST_HEARTBEAT, System.currentTimeMillis())
                    .apply()
            } catch (_: Throwable) {
            }
        }
    }
}