package com.gpm.virtual.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gpm.virtual.data.SortirSettingsController

/**
 * Receiver to force-write Sortir defaults and refresh preferences on device boot.
 */
class SortirBootWriter : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        try {
            Log.i(TAG, "SortirBootWriter: onReceive ($action)")
            SortirSettingsController.register(context)
            SortirSettingsController.forceWriteDefaults(context)
            SortirSettingsController.resendConfig()
        } catch (t: Throwable) {
            Log.e(TAG, "SortirBootWriter error: ${t.message}", t)
        }
    }

    companion object {
        private const val TAG = "SortirBootWriter"
    }
}
