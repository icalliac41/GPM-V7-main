package com.gpm.virtual.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Helper terpusat untuk akses gpm_injection_prefs.
 * Semua penulisan/pembacaan koordinat per-app HARUS lewat sini
 * untuk mencegah inkonsistensi key.
 */
object InjectionPrefsHelper {

    const val PREFS_NAME = "gpm_injection_prefs"

    private const val KEY_PREFIX_GOJEK = "gojek"
    private const val KEY_PREFIX_GRAB = "grab"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefixFor(target: String): String = when (target.lowercase()) {
        "gojek", "gojek.partner", KnownPackages.GOJEK_PARTNER -> KEY_PREFIX_GOJEK
        "grab", "grab.driver", KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER -> KEY_PREFIX_GRAB
        else -> throw IllegalArgumentException("Unknown target: $target")
    }

    fun setTarget(ctx: Context, target: String, lat: Double?, lng: Double?) {
        val p = prefixFor(target)
        val editor = prefs(ctx).edit()
        if (lat == null || lng == null) {
            editor.putBoolean("${p}_has_target", false)
                .remove("${p}_lat")
                .remove("${p}_lng")
        } else {
            editor.putBoolean("${p}_has_target", true)
                .putLong("${p}_lat", lat.toRawBits())
                .putLong("${p}_lng", lng.toRawBits())
        }
        editor.commit()
    }

    fun getTarget(ctx: Context, target: String): Pair<Double, Double>? {
        val p = prefixFor(target)
        val sp = prefs(ctx)
        if (!sp.getBoolean("${p}_has_target", false)) return null
        val lat = Double.fromBits(sp.getLong("${p}_lat", 0L))
        val lng = Double.fromBits(sp.getLong("${p}_lng", 0L))
        if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    fun setSpoofActive(ctx: Context, target: String, active: Boolean) {
        val p = prefixFor(target)
        prefs(ctx).edit().putBoolean("${p}_spoof_active", active).commit()
    }

    fun isSpoofActive(ctx: Context, target: String): Boolean {
        val p = prefixFor(target)
        return prefs(ctx).getBoolean("${p}_spoof_active", false)
    }

    fun getScope(ctx: Context): String =
        prefs(ctx).getString("target_scope", "ALL") ?: "ALL"

    fun setScope(ctx: Context, scope: String) {
        prefs(ctx).edit().putString("target_scope", scope.uppercase()).commit()
    }

    fun clear(ctx: Context, target: String) {
        val p = prefixFor(target)
        prefs(ctx).edit()
            .putBoolean("${p}_has_target", false)
            .putBoolean("${p}_spoof_active", false)
            .remove("${p}_lat")
            .remove("${p}_lng")
            .commit()
    }
}