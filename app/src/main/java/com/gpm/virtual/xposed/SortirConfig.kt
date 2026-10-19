package com.gpm.virtual.xposed

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object SortirConfig {

    private val TAG: String = if (BuildConfig.DEBUG) "GMP:Sortir:Cfg" else "T"
    private const val PREFS_OLD = "gpm_sortir_config"
    private const val PREFS_NEW = "net_okhttp_prefs"

    private const val KEY_ENABLED        = "shadow_mode"
    private const val KEY_NEW_ENABLED    = "sortir_shadow_mode"
    private const val KEY_FILTER_ENABLED = "filter_enabled"
    private const val KEY_VERBOSE        = "verbose"
    private const val KEY_EXTEND_TIMER   = "extend_timer"

    private const val FORCE_ENABLE_FOR_TESTING = true

    private const val CACHE_TTL_MS = 500L

    private val PREFS_FILE_PATHS = listOf(
        "/data/data/com.gpm.virtual.mchaji/shared_prefs/gpm_sortir_config.xml",
        "/data/data/com.gpm.virtual/shared_prefs/gpm_sortir_config.xml",
        "/data/user/0/com.gpm.virtual.mchaji/shared_prefs/gpm_sortir_config.xml",
        "/data/user/0/com.gpm.virtual/shared_prefs/gpm_sortir_config.xml",
        "/data/user_de/0/com.gpm.virtual.mchaji/shared_prefs/gpm_sortir_config.xml",
        "/data/user_de/0/com.gpm.virtual/shared_prefs/gpm_sortir_config.xml",
        "/data/data/com.gpm.virtual.mchaji/shared_prefs/net_okhttp_prefs.xml",
        "/data/data/com.gpm.virtual/shared_prefs/net_okhttp_prefs.xml",
        "/data/user/0/com.gpm.virtual.mchaji/shared_prefs/net_okhttp_prefs.xml",
        "/data/user/0/com.gpm.virtual/shared_prefs/net_okhttp_prefs.xml",
        "/data/user_de/0/com.gpm.virtual.mchaji/shared_prefs/net_okhttp_prefs.xml",
        "/data/user_de/0/com.gpm.virtual/shared_prefs/net_okhttp_prefs.xml"
    )

    @Volatile private var module: XposedModule? = null
    @Volatile private var receiverHooked = false
    private val receiverRegistered = AtomicBoolean(false)

    private data class Cached<T>(val value: T, val at: Long)

    @Volatile private var cEnabled:     Cached<Boolean>? = null
    @Volatile private var cFilter:      Cached<Boolean>? = null
    @Volatile private var cExtendTimer: Cached<Boolean>? = null
    @Volatile private var cVerbose:     Cached<Boolean>? = null
    @Volatile private var cMinPrice:    Cached<Float>? = null
    @Volatile private var cMaxPrice:    Cached<Float>? = null

    fun init(m: XposedModule, cl: ClassLoader) {
        module = m
        invalidateCache()
        setupHotReloadReceiver(cl)
        if (FORCE_ENABLE_FOR_TESTING) {
            try { m.log(Log.WARN, TAG, "⚠️⚠️⚠️ FORCE ENABLE MODE ACTIVE — Sortir=TRUE, Filter=FALSE (for testing only)") } catch (_: Throwable) {}
        }
    }

    private fun setupHotReloadReceiver(cl: ClassLoader) {
        if (receiverHooked) return
        receiverHooked = true
        try {
            val appClass = ReflectUtil.findClassOrNull("android.app.Application", cl) ?: return
            val onCreate = ReflectUtil.findMethodExactOrNull(appClass, "onCreate") ?: return
            LocationHook.hookMethod(onCreate) { chain ->
                val result = chain.proceed()
                val ctx = chain.thisObject as? Context
                if (ctx != null) registerReceiver(ctx)
                result
            }
        } catch (_: Throwable) { receiverHooked = false }
    }

    private fun registerReceiver(ctx: Context) {
        if (receiverRegistered.get()) return
        try {
            val filter = IntentFilter(HotReloadActions.SORTIR_UPDATE)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    val enabled = intent.getBooleanExtra(HotReloadActions.EXTRA_SHADOW_MODE, isEnabled())
                    val extend = intent.getBooleanExtra(HotReloadActions.EXTRA_EXTEND_TIMER, isExtendTimer())
                    val filterOn = intent.getBooleanExtra(HotReloadActions.EXTRA_FILTER_ENABLED, isFilterEnabled())
                    val now = System.currentTimeMillis()
                    cEnabled     = Cached(enabled, now)
                    cExtendTimer = Cached(extend, now)
                    cFilter      = Cached(filterOn, now)
                    Log.i(TAG, "📡 HotReload received: enabled=$enabled filter=$filterOn")
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val method = Context::class.java.getMethod("registerReceiver", android.content.BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType)
                method.invoke(ctx, receiver, filter, 2)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            receiverRegistered.set(true)
        } catch (_: Throwable) {}
    }

    private fun getRemotePrefs(name: String): SharedPreferences? = try {
        module?.getRemotePreferences(name)
    } catch (_: Throwable) { null }

    private fun readFromFile(): Boolean {
        for (path in PREFS_FILE_PATHS) {
            try {
                val file = File(path)
                if (!file.exists()) continue
                if (!file.canRead()) continue
                val content = file.readText()
                if (content.isBlank()) continue
                val regexShadow = Regex("""<boolean\s+name="shadow_mode"\s+value="true"\s*/?>""")
                val regexSortir = Regex("""<boolean\s+name="sortir_shadow_mode"\s+value="true"\s*/?>""")
                val regexC1     = Regex("""<boolean\s+name="c1"\s+value="true"\s*/?>""")
                if (regexShadow.containsMatchIn(content) ||
                    regexSortir.containsMatchIn(content) ||
                    regexC1.containsMatchIn(content)) return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun readEnabledRaw(): Boolean {
        if (FORCE_ENABLE_FOR_TESTING) return true
        if (readFromFile()) return true
        val pOld = getRemotePrefs(PREFS_OLD)
        val pNew = getRemotePrefs(PREFS_NEW)
        val eOld = pOld?.getBoolean(KEY_ENABLED, false) ?: false
        val eNew = pNew?.getBoolean(KEY_NEW_ENABLED, false) ?: false
        if (eOld || eNew) return true
        for (i in 1..3) {
            try { Thread.sleep(80) } catch (_: Throwable) {}
            if (readFromFile()) return true
        }
        return false
    }

    fun isEnabled(): Boolean {
        if (FORCE_ENABLE_FOR_TESTING) return true
        val now = System.currentTimeMillis()
        cEnabled?.let { if (now - it.at < CACHE_TTL_MS) return it.value }
        val fresh = readEnabledRaw()
        cEnabled = Cached(fresh, now)
        return fresh
    }

    fun isFilterEnabled(): Boolean {
        if (FORCE_ENABLE_FOR_TESTING) return false
        val now = System.currentTimeMillis()
        cFilter?.let { if (now - it.at < CACHE_TTL_MS) return it.value }
        val fresh = getRemotePrefs(PREFS_OLD)?.getBoolean(KEY_FILTER_ENABLED, false) ?: false
        cFilter = Cached(fresh, now)
        return fresh
    }

    fun isExtendTimer(): Boolean {
        val now = System.currentTimeMillis()
        cExtendTimer?.let { if (now - it.at < CACHE_TTL_MS) return it.value }
        val pOld = getRemotePrefs(PREFS_OLD)?.getBoolean(KEY_EXTEND_TIMER, true) ?: true
        val pNew = getRemotePrefs(PREFS_NEW)?.getBoolean("sortir_extend_timer", true) ?: true
        val fresh = pOld || pNew
        cExtendTimer = Cached(fresh, now)
        return fresh
    }

    fun getMinPrice(): Float {
        val now = System.currentTimeMillis()
        cMinPrice?.let { if (now - it.at < CACHE_TTL_MS) return it.value }
        val pOld = getRemotePrefs(PREFS_OLD)?.getFloat("min_price", 0f) ?: 0f
        val pNew = getRemotePrefs(PREFS_NEW)?.getFloat("sortir_min_price", 0f) ?: 0f
        val fresh = if (pOld > 0f) pOld else pNew
        cMinPrice = Cached(fresh, now)
        return fresh
    }

    fun getMaxPrice(): Float {
        val now = System.currentTimeMillis()
        cMaxPrice?.let { if (now - it.at < CACHE_TTL_MS) return it.value }
        val pOld = getRemotePrefs(PREFS_OLD)?.getFloat("max_price", 0f) ?: 0f
        val pNew = getRemotePrefs(PREFS_NEW)?.getFloat("sortir_max_price", 0f) ?: 0f
        val fresh = if (pOld > 0f) pOld else pNew
        cMaxPrice = Cached(fresh, now)
        return fresh
    }

    fun isVariantAllowed(variant: String): Boolean = true
    fun isVerticalAllowed(vertical: String): Boolean = true
    fun isAllowOtherServices(): Boolean = true

    fun invalidateCache() {
        cEnabled = null; cFilter = null; cExtendTimer = null; cVerbose = null
        cMinPrice = null; cMaxPrice = null
    }
}