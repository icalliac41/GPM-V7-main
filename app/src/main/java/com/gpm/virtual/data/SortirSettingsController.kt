package com.gpm.virtual.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.gpm.virtual.receiver.SortirPrefsSyncReceiver
import com.gpm.virtual.xposed.HotReloadActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object SortirSettingsController {

    private const val TAG = "SysSortir"
    private const val PREFS_OLD = "gpm_sortir_config"
    private const val PREFS_NEW = "net_okhttp_prefs"

    private const val KEY_ENABLED        = "shadow_mode"
    private const val KEY_NEW_ENABLED    = "sortir_shadow_mode"
    private const val KEY_FILTER_ENABLED = "filter_enabled"
    private const val KEY_VERBOSE        = "verbose"
    private const val KEY_EXTEND_TIMER   = "extend_timer"
    private const val KEY_ALLOW_OTHER    = "allow_other_services"
    private const val KEY_MIN_PRICE      = "min_price"
    private const val KEY_MAX_PRICE      = "max_price"

    private val GRAB_PKGS = listOf(KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER)
    private val OTHER_GPM_PKGS = listOf("com.gpm.virtual", "com.gpm.virtual.mchaji")

    data class State(
        val enabled: Boolean = false,
        val filterEnabled: Boolean = false,
        val verbose: Boolean = false,
        val extendTimer: Boolean = true,
        val allowOther: Boolean = true,
        val minPrice: Float = 0f,
        val maxPrice: Float = 0f
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var prefsOld: SharedPreferences? = null
    @Volatile private var prefsNew: SharedPreferences? = null

    fun register(context: Context) {
        appContext = context.applicationContext
        ensurePrefs(context)
        refreshFromPrefs()
    }

    fun unregister(context: Context) {}

    private fun makePrefsReadable(context: Context) {
        try {
            val dataDir = context.applicationInfo.dataDir
            val prefsDir = File(dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
                prefsDir.listFiles()?.forEach { it.setReadable(true, false) }
            }
        } catch (_: Throwable) {}
    }

    fun forceWriteDefaults(context: Context) {
        val app = context.applicationContext
        val pOld = app.getSharedPreferences(PREFS_OLD, Context.MODE_PRIVATE)
        val pNew = app.getSharedPreferences(PREFS_NEW, Context.MODE_PRIVATE)
        try {
            val enabled       = pOld.getBoolean(KEY_ENABLED, false) || pNew.getBoolean(KEY_NEW_ENABLED, false)
            val extendTimer   = pOld.getBoolean(KEY_EXTEND_TIMER, true)
            val filterEnabled = pOld.getBoolean(KEY_FILTER_ENABLED, false)
            val verbose       = pOld.getBoolean(KEY_VERBOSE, false)
            val allowOther    = pOld.getBoolean(KEY_ALLOW_OTHER, true)
            val minPrice      = pOld.getFloat(KEY_MIN_PRICE, 0f)
            val maxPrice      = pOld.getFloat(KEY_MAX_PRICE, 0f)

            pOld.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putBoolean(KEY_EXTEND_TIMER, extendTimer)
                .putBoolean(KEY_FILTER_ENABLED, filterEnabled)
                .putBoolean(KEY_VERBOSE, verbose)
                .putBoolean(KEY_ALLOW_OTHER, allowOther)
                .putFloat(KEY_MIN_PRICE, minPrice)
                .putFloat(KEY_MAX_PRICE, maxPrice)
                .commit()

            pNew.edit()
                .putBoolean(KEY_NEW_ENABLED, enabled)
                .putBoolean("sortir_extend_timer", extendTimer)
                .putBoolean("sortir_filter_enabled", filterEnabled)
                .putBoolean("sortir_verbose", verbose)
                .putBoolean("sortir_allow_other_services", allowOther)
                .putFloat("sortir_min_price", minPrice)
                .putFloat("sortir_max_price", maxPrice)
                .commit()

            _state.value = State(enabled, filterEnabled, verbose, extendTimer, allowOther, minPrice, maxPrice)
            makePrefsReadable(app)
        } catch (t: Throwable) {
            Log.e(TAG, "forceWriteDefaults FAILED: ${t.message}", t)
        }
    }

    fun resendConfig() {
        val ctx = appContext ?: return
        sendHotReload(ctx)
        syncToOtherGpm(ctx)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
        prefsOld?.edit()?.putBoolean(KEY_ENABLED, enabled)?.commit()
        prefsNew?.edit()?.putBoolean(KEY_NEW_ENABLED, enabled)?.commit()
        makePrefsReadable(context)
        sendHotReload(context)
        syncToOtherGpm(context)
    }

    fun setFilterEnabled(context: Context, enabled: Boolean) {
        _state.value = _state.value.copy(filterEnabled = enabled)
        prefsOld?.edit()?.putBoolean(KEY_FILTER_ENABLED, enabled)?.commit()
        prefsNew?.edit()?.putBoolean("sortir_filter_enabled", enabled)?.commit()
        makePrefsReadable(context)
        sendHotReload(context)
        syncToOtherGpm(context)
    }

    fun setMinPrice(context: Context, price: Float) {
        _state.value = _state.value.copy(minPrice = price)
        prefsOld?.edit()?.putFloat(KEY_MIN_PRICE, price)?.commit()
        prefsNew?.edit()?.putFloat("sortir_min_price", price)?.commit()
        makePrefsReadable(context)
        sendHotReload(context)
    }

    fun setMaxPrice(context: Context, price: Float) {
        _state.value = _state.value.copy(maxPrice = price)
        prefsOld?.edit()?.putFloat(KEY_MAX_PRICE, price)?.commit()
        prefsNew?.edit()?.putFloat("sortir_max_price", price)?.commit()
        makePrefsReadable(context)
        sendHotReload(context)
    }

    fun setVerbose(context: Context, enabled: Boolean) {
        _state.value = _state.value.copy(verbose = enabled)
        prefsOld?.edit()?.putBoolean(KEY_VERBOSE, enabled)?.commit()
        prefsNew?.edit()?.putBoolean("sortir_verbose", enabled)?.commit()
        makePrefsReadable(context)
        sendHotReload(context)
    }

    private fun sendHotReload(context: Context) {
        val s = _state.value
        for (pkg in GRAB_PKGS) {
            try {
                val intent = Intent(HotReloadActions.SORTIR_UPDATE).apply {
                    setPackage(pkg)
                    putExtra(HotReloadActions.EXTRA_SHADOW_MODE, s.enabled)
                    putExtra(HotReloadActions.EXTRA_EXTEND_TIMER, s.extendTimer)
                    putExtra(HotReloadActions.EXTRA_FILTER_ENABLED, s.filterEnabled)
                }
                context.sendBroadcast(intent)
            } catch (_: Throwable) {}
        }
    }

    private fun syncToOtherGpm(context: Context) {
        val myPackage = context.packageName
        val s = _state.value
        for (pkg in OTHER_GPM_PKGS) {
            if (pkg == myPackage) continue
            try {
                val intent = Intent(SortirPrefsSyncReceiver.ACTION_SYNC).apply {
                    setPackage(pkg)
                    putExtra(SortirPrefsSyncReceiver.EXTRA_SHADOW_MODE, s.enabled)
                    putExtra(SortirPrefsSyncReceiver.EXTRA_EXTEND_TIMER, s.extendTimer)
                    putExtra(SortirPrefsSyncReceiver.EXTRA_FILTER_ENABLED, s.filterEnabled)
                    putExtra(SortirPrefsSyncReceiver.EXTRA_VERBOSE, s.verbose)
                }
                context.sendBroadcast(intent)
            } catch (_: Throwable) {}
        }
    }

    private fun ensurePrefs(context: Context): SharedPreferences {
        prefsOld?.let { if (prefsNew != null) return it }
        prefsOld = context.applicationContext.getSharedPreferences(PREFS_OLD, Context.MODE_PRIVATE)
        prefsNew = context.applicationContext.getSharedPreferences(PREFS_NEW, Context.MODE_PRIVATE)
        if (prefsOld!!.all.isEmpty() || prefsNew!!.all.isEmpty()) {
            forceWriteDefaults(context)
        }
        return prefsOld!!
    }

    private fun refreshFromPrefs() {
        val pOld = prefsOld ?: return
        val pNew = prefsNew
        val enabled = pOld.getBoolean(KEY_ENABLED, false) || (pNew?.getBoolean(KEY_NEW_ENABLED, false) ?: false)
        _state.value = State(
            enabled       = enabled,
            filterEnabled = pOld.getBoolean(KEY_FILTER_ENABLED, false),
            verbose       = pOld.getBoolean(KEY_VERBOSE, false),
            extendTimer   = pOld.getBoolean(KEY_EXTEND_TIMER, true),
            allowOther    = pOld.getBoolean(KEY_ALLOW_OTHER, true),
            minPrice      = pOld.getFloat(KEY_MIN_PRICE, 0f),
            maxPrice      = pOld.getFloat(KEY_MAX_PRICE, 0f)
        )
    }
}