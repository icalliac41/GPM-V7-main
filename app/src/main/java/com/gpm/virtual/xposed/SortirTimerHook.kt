package com.gpm.virtual.xposed

import android.os.CountDownTimer
import android.os.Handler
import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule

object SortirTimerHook {

    private const val TAG = "GMP:Sortir:[TIMER]"
    private const val EXTEND_FACTOR = 15L
    private const val MIN_DELAY_MS = 10_000L
    private const val MAX_DELAY_MS = 120_000L
    private const val EXTRA_TIME_MS = 300_000L // +5 menit

    fun install(module: XposedModule, classLoader: ClassLoader) {
        // Paksa SortirConfig baca ulang dari prefs di proses baru
        try { SortirConfig.invalidateCache() } catch (_: Throwable) {}

        hookTimerModel(module, classLoader)
        hookTimerJsonAdapter(module, classLoader)
        hookCountDownTimer(module)
        hookHandlerPostDelayed(module)
    }

    private fun hookTimerModel(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = ReflectUtil.findClassOrNull("com.grab.driver.job.ad.model.Timer", cl) ?: return
            val m = ReflectUtil.findMethodExactOrNull(cls, "a") ?: return

            LocationHook.hookMethod(m) { chain ->
                if (!isActive()) return@hookMethod chain.proceed()
                val original = chain.proceed() as? Long ?: return@hookMethod null
                val extended = original + EXTRA_TIME_MS
                log(module, "Timer Model: Deadline extended +5m ($original -> $extended)")
                return@hookMethod extended
            }
            log(module, "✓ Timer Model hooked")
        } catch (t: Throwable) { log(module, "✗ Timer Model: ${t.message}") }
    }

    private fun hookTimerJsonAdapter(module: XposedModule, cl: ClassLoader) {
        try {
            // Ambil class JsonReader dari classloader target app, BUKAN dari modul.
            val jsonReaderClass = try {
                Class.forName("com.squareup.moshi.JsonReader", false, cl)
            } catch (_: Throwable) {
                log(module, "✗ TimerJsonAdapter: JsonReader tidak tersedia di target")
                return
            }

            val m = ReflectUtil.findMethodOrNull(
                className = "com.grab.driver.job.ad.model.TimerJsonAdapter",
                classLoader = cl,
                methodName = "a",
                jsonReaderClass
            ) ?: return

            LocationHook.hookMethod(m) { chain ->
                if (!isActive()) return@hookMethod chain.proceed()
                val timerObj = chain.proceed() ?: return@hookMethod null
                try {
                    val field = timerObj.javaClass.getDeclaredField("a")
                    field.isAccessible = true
                    val original = field.getLong(timerObj)
                    field.setLong(timerObj, original + EXTRA_TIME_MS)
                    log(module, "JsonAdapter: Deadline modified in object")
                } catch (_: Throwable) {}
                return@hookMethod timerObj
            }
            log(module, "✓ TimerJsonAdapter hooked")
        } catch (t: Throwable) { log(module, "✗ TimerJsonAdapter: ${t.message}") }
    }

    private fun hookCountDownTimer(module: XposedModule) {
        try {
            val cls = Class.forName("android.os.CountDownTimer")
            val m = cls.getMethod("start").apply { isAccessible = true }
            LocationHook.hookMethod(m) { chain ->
                if (!isActive()) return@hookMethod chain.proceed()
                val timer = chain.thisObject as? CountDownTimer ?: return@hookMethod chain.proceed()
                try {
                    val f = CountDownTimer::class.java.getDeclaredField("mMillisInFuture").apply { isAccessible = true }
                    val original = f.getLong(timer)
                    if (isJobTimerStack() && original in MIN_DELAY_MS..MAX_DELAY_MS) {
                        val extended = original * EXTEND_FACTOR
                        f.setLong(timer, extended)
                        logDiscovery(module, "CountDownTimer", original, extended)
                    }
                } catch (_: Throwable) {}
                chain.proceed()
            }
            log(module, "✓ CountDownTimer hooked")
        } catch (t: Throwable) { log(module, "✗ CountDownTimer: ${t.message}") }
    }

    private fun hookHandlerPostDelayed(module: XposedModule) {
        try {
            val cls = Class.forName("android.os.Handler")
            try {
                val m = cls.getMethod("postDelayed", Runnable::class.java, java.lang.Long.TYPE)
                LocationHook.hookMethod(m) { chain ->
                    if (!isActive()) return@hookMethod chain.proceed()
                    val delay = chain.args.getOrNull(1) as? Long ?: return@hookMethod chain.proceed()
                    if (delay !in MIN_DELAY_MS..MAX_DELAY_MS || !isJobTimerStack()) return@hookMethod chain.proceed()
                    val extended = delay * EXTEND_FACTOR
                    logDiscovery(module, "Handler.postDelayed", delay, extended)
                    val args = chain.args.toTypedArray()
                    args[1] = extended
                    chain.proceed(args)
                }
            } catch (_: Throwable) {}
            log(module, "✓ Handler hooked")
        } catch (t: Throwable) { log(module, "✗ Handler: ${t.message}") }
    }

    private fun isActive(): Boolean = try {
        SortirConfig.isEnabled() && SortirConfig.isExtendTimer()
    } catch (_: Throwable) { false }

    private fun isJobTimerStack(): Boolean = try {
        Thread.currentThread().stackTrace.any {
            it.className.startsWith("com.grab.driver.job") &&
            !it.className.contains("Widget", true) &&
            !it.className.contains("Animation", true)
        }
    } catch (_: Throwable) { false }

    private fun logDiscovery(module: XposedModule, source: String, orig: Long, ext: Long) {
        if (!BuildConfig.DEBUG) return
        try { module.log(Log.INFO, TAG, "[$source] ${orig}ms -> ${ext}ms") } catch (_: Throwable) {}
    }

    private fun log(module: XposedModule, msg: String) {
        if (BuildConfig.DEBUG) { try { module.log(Log.INFO, TAG, msg) } catch (_: Throwable) {} }
    }
}