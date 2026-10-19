package com.gpm.virtual.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Sinkronisasi prefs Sortir antar package GPM
 * (com.gpm.virtual ↔ com.gpm.virtual.mchaji).
 *
 * Menulis KEDUA file (gpm_sortir_config + net_okhttp_prefs)
 * agar modul Xposed bisa baca dari package manapun yang aktif.
 */
class SortirPrefsSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != ACTION_SYNC) return

        try {
            val shadowMode    = intent.getBooleanExtra(EXTRA_SHADOW_MODE, false)
            val extendTimer   = intent.getBooleanExtra(EXTRA_EXTEND_TIMER, true)
            val filterEnabled = intent.getBooleanExtra(EXTRA_FILTER_ENABLED, false)
            val verbose       = intent.getBooleanExtra(EXTRA_VERBOSE, false)

            val app = context.applicationContext
            Log.i(TAG, "📥 Sync received by ${app.packageName}: shadow=$shadowMode extend=$extendTimer filter=$filterEnabled")

            val pOld = app.getSharedPreferences(PREFS_OLD, Context.MODE_PRIVATE)
            val okOld = pOld.edit()
                .putBoolean(KEY_ENABLED, shadowMode)
                .putBoolean(KEY_EXTEND_TIMER, extendTimer)
                .putBoolean(KEY_FILTER_ENABLED, filterEnabled)
                .putBoolean(KEY_VERBOSE, verbose)
                .commit()

            val pNew = app.getSharedPreferences(PREFS_NEW, Context.MODE_PRIVATE)
            val okNew = pNew.edit()
                .putBoolean(KEY_NEW_ENABLED, shadowMode)
                .putBoolean("sortir_extend_timer", extendTimer)
                .putBoolean("sortir_filter_enabled", filterEnabled)
                .putBoolean("sortir_verbose", verbose)
                .commit()

            Log.i(TAG, "✅ Synced to ${app.packageName}: old=$okOld new=$okNew")
        } catch (t: Throwable) {
            Log.e(TAG, "Sync failed: ${t.message}", t)
        }
    }

    companion object {
        private const val TAG = "SortirPrefsSync"
        const val ACTION_SYNC = "com.gpm.virtual.SYNC_SORTIR_PREFS"

        const val EXTRA_SHADOW_MODE    = "shadow_mode"
        const val EXTRA_EXTEND_TIMER   = "extend_timer"
        const val EXTRA_FILTER_ENABLED = "filter_enabled"
        const val EXTRA_VERBOSE        = "verbose"

        private const val PREFS_OLD = "gpm_sortir_config"
        private const val PREFS_NEW = "net_okhttp_prefs"
        private const val KEY_ENABLED        = "shadow_mode"
        private const val KEY_NEW_ENABLED    = "sortir_shadow_mode"
        private const val KEY_FILTER_ENABLED = "filter_enabled"
        private const val KEY_VERBOSE        = "verbose"
        private const val KEY_EXTEND_TIMER   = "extend_timer"
    }
}