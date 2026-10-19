package com.gpm.virtual.xposed

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gpm.virtual.BuildConfig
import com.gpm.virtual.xposed.overlay.GrabSoundPlayer
import com.gpm.virtual.xposed.overlay.SortirOverlayManager
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.Locale

object SortirHook {

    val TAG: String        = if (BuildConfig.DEBUG) "GMP:Sortir:" else "T"
    private val TAG_ACTION = if (BuildConfig.DEBUG) "GMP:Sortir:[ACTION]" else "T"
    private val TAG_SETUP  = if (BuildConfig.DEBUG) "GMP:Sortir:[SETUP]" else "T"
    private val TAG_ORDER  = if (BuildConfig.DEBUG) "GMP:Sortir:[ORDER]" else "T"

    private const val ACTION_LOG_INTERVAL_MS = 1_500L

    @Volatile private var sortirHooked = false
    @Volatile private var jobAdsImplInstance: Any? = null
    @Volatile private var vmap: SortirVersionMapper.VersionMap = SortirVersionMapper.default()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (sortirHooked) return
        vmap = SortirVersionMapper.detect(classLoader) ?: SortirVersionMapper.default()
        setupLog(module, "📦 MAPPING ${vmap.version} | tap=${vmap.manualAcceptMethod} | fallback=${vmap.manualAcceptFallback}")

        SortirConfig.init(module, classLoader)
        SortirConfig.invalidateCache()

        var ok = 0
        var total = 0
        total++; if (safe(module, "anti-turbo-state") { installForceManualStateHook(module, classLoader) }) ok++
        total++; if (safe(module, "capture-service")  { installCaptureServiceInstance(module, classLoader) }) ok++
        total++; if (safe(module, "accept-hooks")     { installAcceptHooks(module, classLoader) }) ok++
        total++; if (safe(module, "reject-hooks")     { installRejectHooks(module, classLoader) }) ok++
        total++; if (safe(module, "timer")            { installTimerHook(module, classLoader) }) ok++
        total++; if (safe(module, "aa-background")    { installAaBackgroundHook(module, classLoader) }) ok++
        total++; if (safe(module, "accept-sweep")     { installAcceptSweep(module, classLoader) }) ok++
        total++; if (safe(module, "filter")           { installFilterHooks(module, classLoader) }) ok++
        total++; if (safe(module, "auto-bind")        { installAutoBindHooks(module, classLoader) }) ok++
        total++; if (safe(module, "jobad-validator")  { installJobAdValidatorHook(module, classLoader) }) ok++
        total++; if (safe(module, "sortir-timer") {
            try { SortirTimerHook.install(module, classLoader); true } catch (t: Throwable) { false }
        }) ok++

        if (ok > 0) sortirHooked = true
        setupLog(module, "📊 SortirHook v8 (Reguler+AA): $ok/$total hooks | enabled=${SortirConfig.isEnabled()}")
    }

    fun resetInstallState() {
        sortirHooked = false
        jobAdsImplInstance = null
    }

    private fun installAutoBindHooks(module: XposedModule, cl: ClassLoader): Boolean {
        var count = 0
        val jobAdCls = ReflectUtil.findClassOrNull(vmap.jobAd, cl)
        val ctx = LocationHook.getProcessContext(null)
        if (ctx != null) GrabSoundPlayer.init(ctx)

        // Jalur 1: Reguler
        val handlerCls = ReflectUtil.findClassOrNull(vmap.jobAdHandler, cl)
        if (handlerCls != null) {
            for (m in handlerCls.declaredMethods) {
                if (m.name == "U" && m.parameterCount >= 1) {
                    m.isAccessible = true
                    LocationHook.hookMethod(m) { chain ->
                        if (handleJobAdChain(module, chain, jobAdCls, cl, "Reguler")) return@hookMethod null
                        chain.proceed()
                    }
                    count++
                    setupLog(module, "✅ JobAdHandlerImpl.U hooked (Reguler)")
                }
            }
        }

        // Jalur 2: AA / Auto-Assign
        val aaViewModelCls = ReflectUtil.findClassOrNull(vmap.jobAdViewModel, cl)
        if (aaViewModelCls != null) {
            for (m in aaViewModelCls.declaredMethods) {
                if (m.name == "U" && m.parameterCount >= 1) {
                    m.isAccessible = true
                    LocationHook.hookMethod(m) { chain ->
                        if (handleJobAdChain(module, chain, jobAdCls, cl, "AA")) return@hookMethod null
                        chain.proceed()
                    }
                    count++
                    setupLog(module, "✅ JobAdViewModel.U hooked (AA/Auto-Assign)")
                }
            }
        }

        return count > 0
    }

    private fun handleJobAdChain(
        module: XposedModule,
        chain: io.github.libxposed.api.XposedInterface.Chain,
        jobAdCls: Class<*>?,
        cl: ClassLoader,
        source: String
    ): Boolean {
        if (!SortirConfig.isEnabled() || SortirConfig.isFilterEnabled()) {
            Log.d(TAG_ORDER, "[$source] Sortir OFF atau Filter ON — passthrough")
            return false
        }

        val jobAd = chain.args.firstOrNull { jobAdCls != null && jobAdCls.isInstance(it) }
        if (jobAd == null) {
            Log.d(TAG_ORDER, "[$source] No JobAd di args")
            return false
        }

        val parsed = SortirJobParser.parse(jobAd)
        if (parsed == null) {
            Log.w(TAG_ORDER, "[$source] ❌ Parser return null")
            return false
        }

        Log.i(TAG_ORDER, "📥 [$source] ${parsed.variant} Rp ${parsed.price} bkg=${parsed.bookingCode}")
        val shown = showOverlayForJob(module, jobAd, cl)
        if (shown) {
            Log.i(TAG_ORDER, "[$source] ✅ OVERLAY SHOWN — blokir UI")
        } else {
            Log.w(TAG_ORDER, "[$source] ❌ Overlay gagal — passthrough")
        }
        return shown
    }

    private fun showOverlayForJob(module: XposedModule, jobAd: Any, cl: ClassLoader): Boolean {
        val parsed = SortirJobParser.parse(jobAd) ?: return false
        val ctx = LocationHook.getProcessContext(null) ?: return false
        GrabSoundPlayer.play()

        val formattedPrice = String.format(Locale.US, "Rp %,.0f", parsed.price).replace(",", ".")
        var overlayShown = false
        val latch = java.util.concurrent.CountDownLatch(1)

        Handler(Looper.getMainLooper()).post {
            try {
                overlayShown = SortirOverlayManager.showOverlay(
                    context = ctx,
                    serviceType = "${parsed.variant} | Lebih Lebar",
                    price = formattedPrice,
                    pickupDist = parsed.pickupDist,
                    pickupAddr = parsed.pickupAddr,
                    dropoffDist = parsed.dropoffDist,
                    dropoffAddr = parsed.dropoffAddr,
                    countdownSeconds = 20,
                    onAccept = {
                        logAction(module, "overlay_accept", "✅ User Accept via Overlay")
                        invokeManualAccept(cl, jobAd)
                    },
                    onCancel = { logAction(module, "overlay_cancel", "🛑 User Cancel via Overlay") }
                )
            } catch (_: Throwable) { overlayShown = false }
            finally { latch.countDown() }
        }
        try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
        return overlayShown
    }

    private fun installCaptureServiceInstance(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = ReflectUtil.findClassOrNull(vmap.jobAdsImpl, cl) ?: return false
        val ctor = cls.declaredConstructors.firstOrNull() ?: return false
        ctor.isAccessible = true
        LocationHook.hookMethod(ctor) { chain ->
            val result = chain.proceed()
            if (jobAdsImplInstance == null) {
                jobAdsImplInstance = chain.thisObject
                logAction(module, "capture_service", "🕵️ Instance JobAdsServiceImpl dicuri")
            }
            result
        }
        return true
    }

    private fun invokeManualAccept(cl: ClassLoader, jobAd: Any) {
        val instance = jobAdsImplInstance ?: run {
            Log.w(TAG_ORDER, "invokeManualAccept: no instance")
            return
        }
        val candidates = listOf(vmap.manualAcceptMethod, vmap.manualAcceptFallback, "p", "q").distinct()
        for (name in candidates) {
            try {
                val cls = instance.javaClass
                var m = ReflectUtil.findMethodExactOrNull(cls, name, jobAd.javaClass, String::class.java)
                if (m == null) m = ReflectUtil.findMethodExactOrNull(cls, name, jobAd.javaClass)
                if (m != null) {
                    m.isAccessible = true
                    if (m.parameterCount == 2) m.invoke(instance, jobAd, "accepted_via_overlay")
                    else m.invoke(instance, jobAd)
                    Log.i(TAG_ORDER, "✅ Manual accept via $name berhasil")
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG_ORDER, "Manual accept $name gagal: ${t.message}")
            }
        }
        Log.e(TAG_ORDER, "❌ Semua manual accept method gagal ($candidates)")
    }

    private fun installAcceptHooks(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = ReflectUtil.findClassOrNull(vmap.jobAdsImpl, cl) ?: return false
        val jobAdCls = ReflectUtil.findClassOrNull(vmap.jobAd, cl) ?: return false
        val baseJobCls = ReflectUtil.findClassOrNull(vmap.baseJob, cl) ?: return false
        var found = false
        for (methodName in listOf("o", "k", "q")) {
            ReflectUtil.findMethodExactOrNull(cls, methodName, baseJobCls)?.let { m ->
                m.isAccessible = true
                LocationHook.hookMethod(m) { chain ->
                    if (SortirConfig.isEnabled()) return@hookMethod emptyCompletableOrNever(cl)
                    chain.proceed()
                }
                found = true
            }
        }
        ReflectUtil.findMethodExactOrNull(cls, "c", String::class.java, String::class.java)?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (SortirConfig.isEnabled()) return@hookMethod emptyCompletableOrNever(cl)
                chain.proceed()
            }
            found = true
        }
        for (name in listOf(vmap.manualAcceptMethod, vmap.manualAcceptFallback)) {
            var m = ReflectUtil.findMethodExactOrNull(cls, name, jobAdCls, String::class.java)
            if (m == null) m = ReflectUtil.findMethodExactOrNull(cls, name, jobAdCls)
            m?.let { method ->
                method.isAccessible = true
                LocationHook.hookMethod(method) { chain -> chain.proceed() }
                found = true
                break
            }
        }
        return found
    }

    private fun installRejectHooks(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = ReflectUtil.findClassOrNull(vmap.jobAdsImpl, cl) ?: return false
        val jobAdCls = ReflectUtil.findClassOrNull(vmap.jobAd, cl) ?: return false
        val eventCls = ReflectUtil.findClassOrNull(vmap.jobCardEvent, cl) ?: return false
        var found = false
        ReflectUtil.findMethodExactOrNull(cls, "n", jobAdCls)?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (SortirConfig.isEnabled()) return@hookMethod emptyCompletableOrNever(cl)
                chain.proceed()
            }
            found = true
        }
        ReflectUtil.findMethodExactOrNull(cls, "b", eventCls)?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (SortirConfig.isEnabled()) return@hookMethod emptyCompletableOrNever(cl)
                chain.proceed()
            }
            found = true
        }
        return found
    }

    private fun installTimerHook(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = ReflectUtil.findClassOrNull(vmap.timer, cl) ?: return false
        var hooked = 0
        for (name in listOf("a", "b")) {
            ReflectUtil.findMethodExactOrNull(cls, name)?.let { m ->
                m.isAccessible = true
                LocationHook.hookMethod(m) { chain ->
                    val original = chain.proceed() as? Long
                    if (SortirConfig.isEnabled() && SortirConfig.isExtendTimer() && original != null) {
                        return@hookMethod original + 300_000L
                    }
                    original
                }
                hooked++
            }
        }
        return hooked > 0
    }

    private fun installForceManualStateHook(module: XposedModule, cl: ClassLoader): Boolean {
        var ok = false
        try {
            val cls = Class.forName(vmap.autoValueBase, false, cl)
            val yMethod = cls.getDeclaredMethod("Y").apply { isAccessible = true }
            LocationHook.hookMethod(yMethod) { chain ->
                val original = chain.proceed()
                if (original != null && original.toString() == "ACCEPTED" && SortirConfig.isEnabled()) {
                    val stateCls = ReflectUtil.findClassOrNull(vmap.state, cl)
                    val surfaced = stateCls?.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "SURFACED" }
                    if (surfaced != null) return@hookMethod surfaced
                }
                original
            }
            ok = true
        } catch (_: Throwable) {}
        return ok
    }

    private fun installAaBackgroundHook(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = ReflectUtil.findClassOrNull(vmap.aaJobService, cl) ?: return false
        val m = ReflectUtil.findMethodExactOrNull(cls, "r") ?: return false
        m.isAccessible = true
        LocationHook.hookMethod(m) { chain ->
            if (SortirConfig.isEnabled()) return@hookMethod emptyCompletableOrNever(cl)
            chain.proceed()
        }
        return true
    }

    // PERBAIKAN: Skip method yang berkaitan dengan heatmap/map/area/location
    private fun installAcceptSweep(module: XposedModule, cl: ClassLoader): Boolean {
        var count = 0
        val whitelist = setOf(
            "f", "o", "k", "q", "c", "n", "b", "g", "e", "a", "p", "p0",
            "i", "m", "r", "j",
            vmap.manualAcceptMethod, vmap.manualAcceptFallback
        )
        // Skip method yang berkaitan dengan heatmap, map, area, lokasi, dsb
        val safePatterns = listOf(
            "heat", "map", "grid", "location", "position",
            "nearby", "area", "zone", "geo", "coordinate",
            "region", "radius", "distance"
        )

        val jobAdsImpl = ReflectUtil.findClassOrNull(vmap.jobAdsImpl, cl)
        if (jobAdsImpl != null) {
            for (m in jobAdsImpl.declaredMethods) {
                if (Modifier.isStatic(m.modifiers) || m.name in whitelist) continue
                val nameLower = m.name.lowercase()
                if (safePatterns.any { nameLower.contains(it) }) continue

                val retName = m.returnType.name
                if (retName != "io.reactivex.Completable" && retName != "io.reactivex.CompletableSource") continue
                m.isAccessible = true
                LocationHook.hookMethod(m) { chain ->
                    if (SortirConfig.isEnabled()) return@hookMethod emptyCompletableOrNever(cl)
                    chain.proceed()
                }
                count++
            }
        }
        return count > 0
    }

    private fun installFilterHooks(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = ReflectUtil.findClassOrNull(vmap.surfaceChecker, cl) ?: return false
        val baseJobCls = ReflectUtil.findClassOrNull(vmap.baseJob, cl) ?: return false
        val m = ReflectUtil.findMethodExactOrNull(cls, "b", baseJobCls) ?: return false
        m.isAccessible = true
        LocationHook.hookMethod(m) { chain ->
            if (SortirConfig.isEnabled() && SortirConfig.isFilterEnabled()) {
                SortirJobParser.parse(chain.args[0])?.let { info ->
                    if (SortirFilterEngine.decide(info) == SortirDecision.DROP) {
                        val reasonCls = ReflectUtil.findClassOrNull(vmap.notSurfaced, cl)
                        val blockedEnum = reasonCls?.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "BLOCKED" }
                        if (blockedEnum != null) return@hookMethod blockedEnum
                    }
                }
            }
            chain.proceed()
        }
        return true
    }

    private fun installJobAdValidatorHook(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = ReflectUtil.findClassOrNull(vmap.jobAdValidator, cl) ?: return false
        ReflectUtil.findMethodExactOrNull(cls, "t")?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain -> chain.proceed() }
        }
        return true
    }

    private fun emptyCompletableOrNever(cl: ClassLoader): Any? {
        ShadowModeHelper.emptyCompletable(cl)?.let { return it }
        val candidates = listOf("io.reactivex.Completable" to listOf("complete", "t"), "io.reactivex.rxjava3.core.Completable" to listOf("complete", "t"))
        for ((clsName, methods) in candidates) {
            val cls = try { Class.forName(clsName, false, cl) } catch (_: Throwable) { continue }
            for (name in methods) {
                try {
                    val method = cls.getMethod(name)
                    if (!Modifier.isStatic(method.modifiers)) continue
                    method.invoke(null)?.let { return it }
                } catch (_: Throwable) {}
            }
            try { cls.getMethod("never").invoke(null)?.let { return it } } catch (_: Throwable) {}
        }
        return null
    }

    private inline fun safe(module: XposedModule, label: String, block: () -> Boolean): Boolean =
        try { block().also { setupLog(module, if (it) "✅ $label" else "⚠️ $label fail") } }
        catch (t: Throwable) { setupLog(module, "❌ $label EX: ${t.message}"); false }

    private fun setupLog(module: XposedModule, msg: String) {
        if (BuildConfig.DEBUG) try { module.log(Log.INFO, TAG_SETUP, msg) } catch (_: Throwable) {}
    }

    private fun logAction(module: XposedModule, key: String, msg: String) {
        try { OnceLogger.logThrottled(module, TAG_ACTION, key, msg, ACTION_LOG_INTERVAL_MS) } catch (_: Throwable) {}
    }
}