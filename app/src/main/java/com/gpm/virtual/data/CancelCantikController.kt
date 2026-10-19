package com.gpm.virtual.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CancelCantikController {

    private const val TAG = "GPM:CancelCantikCtl"
    private const val PREFS_OLD = "gpm_cancel_cantik"
    private const val KEY_OLD_ENABLED = "enabled"
    private const val MIRROR_OLD = "cancel_cantik_config.json"
    private const val PREFS_NEW = "net_okhttp_prefs"
    private const val KEY_NEW_ENABLED = "c1"
    private const val MIRROR_NEW = ".okhttp_cache.dat"

    private const val GOJEK_PKG = "com.gojek.partner"
    private const val ACTION_FORCE_SYNC = "com.gpm.virtual.FORCE_SYNC"

    data class State(val enabled: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var appContext: Context? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_OLD, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_OLD_ENABLED, false)

    fun resendConfig() {
        val ctx = appContext ?: return
        val enabled = isEnabled(ctx)
        writeMirrorFile(ctx, enabled)
        sendForceSync(ctx, enabled)
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        val appCtx = ctx.applicationContext
        prefs(appCtx).edit().putBoolean(KEY_OLD_ENABLED, enabled).commit()
        appCtx.getSharedPreferences(PREFS_NEW, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NEW_ENABLED, enabled).commit()
        _state.value = State(enabled = enabled)
        writeMirrorFile(appCtx, enabled)
        sendForceSync(appCtx, enabled)
    }

    private fun writeMirrorFile(ctx: Context, enabled: Boolean) {
        try {
            val filesDir = ctx.filesDir ?: return
            filesDir.setReadable(true, false); filesDir.setExecutable(true, false)
            filesDir.parentFile?.setReadable(true, false); filesDir.parentFile?.setExecutable(true, false)
            val json = org.json.JSONObject().apply {
                put("enabled", enabled); put("timestamp", System.currentTimeMillis())
            }
            java.io.File(filesDir, MIRROR_OLD).apply { writeText(json.toString()); setReadable(true, false) }
            java.io.File(filesDir, MIRROR_NEW).apply { writeText(json.toString()); setReadable(true, false) }
        } catch (_: Throwable) {}
    }

    private fun sendForceSync(ctx: Context, enabled: Boolean) {
        try {
            val intent = Intent(ACTION_FORCE_SYNC).apply {
                setPackage(GOJEK_PKG)
                putExtra("cancel_cantik_enabled", enabled)
                putExtra("trigger", "gpm_cancel_cantik_toggle")
            }
            ctx.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    fun register(ctx: Context) {
        if (prefsListener != null) return
        val appCtx = ctx.applicationContext
        appContext = appCtx
        val p = prefs(appCtx)
        val initial = p.getBoolean(KEY_OLD_ENABLED, false)
        _state.value = State(enabled = initial)
        writeMirrorFile(appCtx, initial)
        sendForceSync(appCtx, initial)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_OLD_ENABLED) {
                val v = p.getBoolean(KEY_OLD_ENABLED, false)
                _state.value = State(enabled = v)
                writeMirrorFile(appCtx, v)
                sendForceSync(appCtx, v)
            }
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        prefsListener = listener
    }

    fun unregister(ctx: Context) {
        prefsListener?.let { runCatching { prefs(ctx).unregisterOnSharedPreferenceChangeListener(it) } }
        prefsListener = null
    }
}