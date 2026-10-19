package com.gpm.virtual.xposed

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule

object GrabHookEntry {

    val TAG: String = if (BuildConfig.DEBUG) "GMP:Grab:" else "T"
    private val TAG_LIFECYCLE: String = if (BuildConfig.DEBUG) "GMP:Grab:[LIFECYCLE]" else "T"
    private val TAG_LATLONG: String = if (BuildConfig.DEBUG) "GMP:Grab:[LATLONG]" else "T"
    private val TAG_BUILDER: String = if (BuildConfig.DEBUG) "GMP:Grab:[BUILDER]" else "T"

    private const val THROTTLE_LATLONG_MS = 15_000L
    private const val KEY_LATLONG = "grab.latlng"

    val TARGET_PACKAGES = setOf(
        "com.grabtaxi.driver2",
        "com.grab.driver"
    )

    fun isTargetPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (TARGET_PACKAGES.contains(pkg)) return true
        return pkg.startsWith("com.grabtaxi.") || pkg.startsWith("com.grab.")
    }

    private const val PREFS_MARKERS  = "gpm_markers"
    private const val MARK_LIFECYCLE = "grab_lifecycle_v1"
    private const val MARK_LATLONG   = "grab_latlong_v1"
    private const val MARK_BUILDER   = "grab_builder_v1"
    // ⭐ FIX: samakan dengan SortirHook
    private const val MARK_SORTIR    = "grab_sortir_v8"

    private const val CLS_LATLONG = "com.grab.position.model.LatLong"
    private const val CLS_AUTO_VALUE_LATLNG_BUILDER =
        "com.grab.driver.job.transit.model.v2.\$AutoValue_LatLngImpl\$Builder"
    private const val CLS_AUTO_VALUE_LATLNG_IMPL =
        "com.grab.driver.job.transit.model.v2.AutoValue_LatLngImpl"

    @Volatile private var lifecycleHooked = false
    @Volatile private var latLongHooked   = false
    @Volatile private var builderHooked   = false
    @Volatile private var sortirHooked    = false

    private fun isMarked(module: XposedModule, key: String): Boolean = try {
        module.getRemotePreferences(PREFS_MARKERS).getBoolean(key, false)
    } catch (_: Throwable) { false }

    private fun mark(module: XposedModule, key: String) = try {
        module.getRemotePreferences(PREFS_MARKERS)
            .edit().putBoolean(key, true).apply()
    } catch (_: Throwable) {}

    // ==================================================================
    //  FORCE RE-INSTALL — dipanggil dari FAB 🚗 via broadcast
    // ==================================================================
    fun forceReinstall(module: XposedModule, classLoader: ClassLoader) {
        latLongHooked = false
        builderHooked = false
        sortirHooked  = false

        // ⭐ FIX: reset marker juga, termasuk marker Sortir baru
        try {
            module.getRemotePreferences(PREFS_MARKERS).edit()
                .remove(MARK_LATLONG)
                .remove(MARK_BUILDER)
                .remove(MARK_SORTIR)
                .apply()
        } catch (_: Throwable) {}

        try { SortirHook.resetInstallState() } catch (_: Throwable) {}
        try { SortirConfig.invalidateCache() } catch (_: Throwable) {}

        var ok = 0
        var total = 0

        total++
        if (safeLatLongHook(module, "ReHook LatLong") {
            initLatLongHooks(module, classLoader)
        }) ok++

        total++
        if (safeBuilderHook(module, "ReHook Builder") {
            initLatLngBuilderHooks(module, classLoader)
        }) ok++

        total++
        if (safeBuilderHook(module, "ReHook Sortir") {
            initSortirHooks(module, classLoader)
        }) ok++

        module.log(
            Log.INFO, TAG,
            "[ReHook] Grab reinstall: $ok/$total ok (lokasi + sortir)"
        )
    }

    private fun logLatLong(module: XposedModule, message: String) {
        OnceLogger.logThrottled(
            module, TAG_LATLONG, KEY_LATLONG,
            message,
            intervalMs = THROTTLE_LATLONG_MS
        )
    }

    // ==================================================================
    //  ENTRY 1 — Lifecycle hooks
    // ==================================================================
    fun initGrabLifecycleHooks(module: XposedModule, classLoader: ClassLoader) {
        if (lifecycleHooked || isMarked(module, MARK_LIFECYCLE)) return

        var count = 0
        var total = 0

        total++
        safeLifecycleHook(module, "Lifecycle 1") {
            val method = ReflectUtil.findMethodOrNull(
                className = Instrumentation::class.java.name,
                classLoader = classLoader,
                methodName = "newActivity",
                ClassLoader::class.java, String::class.java, Intent::class.java
            ) ?: throw NoSuchMethodException("Instrumentation.newActivity not found")
            method.isAccessible = true
            LocationHook.hookMethod(
                method,
                interceptor = PrefGrab(module = module, mode = PrefGrab.MODE_NEW_ACTIVITY)
            )
        }.also { if (it) count++ }

        total++
        safeLifecycleHook(module, "Lifecycle 2") {
            val method = ReflectUtil.findMethodOrNull(
                className = Application::class.java.name,
                classLoader = classLoader,
                methodName = "onCreate"
            ) ?: throw NoSuchMethodException("Application.onCreate not found")
            method.isAccessible = true
            LocationHook.hookMethod(
                method,
                interceptor = PrefGrab(module = module, mode = PrefGrab.MODE_APP_ONCREATE)
            )
        }.also { if (it) count++ }

        if (count > 0) {
            lifecycleHooked = true
            mark(module, MARK_LIFECYCLE)
        }
        OnceLogger.logOnce(module, TAG_LIFECYCLE, "grab_lifecycle_summary",
            "Lifecycle hooks: $count/$total installed")
    }

    // ==================================================================
    //  ENTRY 2 — LatLong hooks
    // ==================================================================
    fun initLatLongHooks(module: XposedModule, classLoader: ClassLoader) {
        if (latLongHooked) return

        val cls = ReflectUtil.findClassOrNull(CLS_LATLONG, classLoader)
        if (cls == null) {
            OnceLogger.logOnce(module, TAG_LATLONG, "latlong_missing",
                "LatLong class not found yet — will retry on next Activity resume")
            return
        }

        var count = 0
        var total = 0

        total++
        safeLatLongHook(module, "LatLong.<init>(D,D)") {
            val ctor = ReflectUtil.findConstructorOrNull(
                cls, java.lang.Double.TYPE, java.lang.Double.TYPE
            ) ?: throw NoSuchMethodException("LatLong(D,D) constructor not found")

            LocationHook.hookMethod(ctor) { chain ->
                val coords = LocationHook.currentSpoofCoords()
                val origLat = chain.args.getOrNull(0) as? Double ?: 0.0
                val origLng = chain.args.getOrNull(1) as? Double ?: 0.0

                if (coords == null) {
                    logLatLong(module, "[REAL] ctor lat=$origLat lng=$origLng")
                    chain.proceed()
                } else {
                    val (lat, lng) = coords
                    logLatLong(module,
                        "[SPOOF] ctor orig=(lat=$origLat lng=$origLng) → lat=$lat lng=$lng")
                    chain.proceed(arrayOf<Any?>(lat, lng))
                }
            }
        }.also { if (it) count++ }

        total++
        safeLatLongHook(module, "LatLong.getLatitude") {
            val m = ReflectUtil.findMethodExactOrNull(cls, "getLatitude")
                ?: throw NoSuchMethodException("getLatitude() not found")

            LocationHook.hookMethod(m) { chain ->
                val coords = LocationHook.currentSpoofCoords()
                if (coords == null) {
                    val real = chain.proceed()
                    logLatLong(module, "[REAL] getLatitude() lat=$real")
                    real
                } else {
                    val (lat, _) = coords
                    logLatLong(module, "[SPOOF] getLatitude() lat=$lat")
                    lat
                }
            }
        }.also { if (it) count++ }

        total++
        safeLatLongHook(module, "LatLong.getLongitude") {
            val m = ReflectUtil.findMethodExactOrNull(cls, "getLongitude")
                ?: throw NoSuchMethodException("getLongitude() not found")

            LocationHook.hookMethod(m) { chain ->
                val coords = LocationHook.currentSpoofCoords()
                if (coords == null) {
                    val real = chain.proceed()
                    logLatLong(module, "[REAL] getLongitude() lng=$real")
                    real
                } else {
                    val (_, lng) = coords
                    logLatLong(module, "[SPOOF] getLongitude() lng=$lng")
                    lng
                }
            }
        }.also { if (it) count++ }

        total++
        safeLatLongHook(module, "LatLong.b()") {
            val m = ReflectUtil.findMethodExactOrNull(cls, "b")
                ?: throw NoSuchMethodException("b() not found")

            LocationHook.hookMethod(m) { chain ->
                val coords = LocationHook.currentSpoofCoords()
                if (coords == null) {
                    val real = chain.proceed()
                    logLatLong(module, "[REAL] b() lat=$real")
                    real
                } else {
                    val (lat, _) = coords
                    logLatLong(module, "[SPOOF] b() lat=$lat")
                    lat
                }
            }
        }.also { if (it) count++ }

        total++
        safeLatLongHook(module, "LatLong.c()") {
            val m = ReflectUtil.findMethodExactOrNull(cls, "c")
                ?: throw NoSuchMethodException("c() not found")

            LocationHook.hookMethod(m) { chain ->
                val coords = LocationHook.currentSpoofCoords()
                if (coords == null) {
                    val real = chain.proceed()
                    logLatLong(module, "[REAL] c() lng=$real")
                    real
                } else {
                    val (_, lng) = coords
                    logLatLong(module, "[SPOOF] c() lng=$lng")
                    lng
                }
            }
        }.also { if (it) count++ }

        if (count > 0) {
            latLongHooked = true
            mark(module, MARK_LATLONG)
        }
        OnceLogger.logOnce(module, TAG_LATLONG, "latlong_summary",
            "LatLong hooks: $count/$total installed")
    }

    // ==================================================================
    //  ENTRY 3 — AutoValue LatLng Builder hooks
    // ==================================================================
    fun initLatLngBuilderHooks(module: XposedModule, classLoader: ClassLoader) {
        if (builderHooked) return

        val builderCls = ReflectUtil.findClassOrNull(CLS_AUTO_VALUE_LATLNG_BUILDER, classLoader)
        if (builderCls == null) {
            OnceLogger.logOnce(module, TAG_BUILDER, "latlng_builder_missing",
                "AutoValue_LatLngImpl\$Builder not found yet — will retry")
            return
        }

        var count = 0
        var total = 0

        total++
        safeBuilderHook(module, "Builder.b(D)") {
            val m = ReflectUtil.findMethodExactOrNull(builderCls, "b", java.lang.Double.TYPE)
                ?: throw NoSuchMethodException("Builder.b(D) not found")

            LocationHook.hookMethod(m) { chain ->
                val coords = LocationHook.currentSpoofCoords()
                if (coords == null) {
                    chain.proceed()
                } else {
                    val (lat, _) = coords
                    OnceLogger.logThrottled(
                        module, TAG_BUILDER, "grab.setter.b.spoof",
                        "[SPOOF] b(D) → lat=$lat",
                        intervalMs = THROTTLE_LATLONG_MS
                    )
                    chain.proceed(arrayOf<Any?>(lat))
                }
            }
        }.also { if (it) count++ }

        total++
        safeBuilderHook(module, "Builder.c(D)") {
            val m = ReflectUtil.findMethodExactOrNull(builderCls, "c", java.lang.Double.TYPE)
                ?: throw NoSuchMethodException("Builder.c(D) not found")

            LocationHook.hookMethod(m) { chain ->
                val coords = LocationHook.currentSpoofCoords()
                if (coords == null) {
                    chain.proceed()
                } else {
                    val (_, lng) = coords
                    OnceLogger.logThrottled(
                        module, TAG_BUILDER, "grab.setter.c.spoof",
                        "[SPOOF] c(D) → lng=$lng",
                        intervalMs = THROTTLE_LATLONG_MS
                    )
                    chain.proceed(arrayOf<Any?>(lng))
                }
            }
        }.also { if (it) count++ }

        val implCls = ReflectUtil.findClassOrNull(CLS_AUTO_VALUE_LATLNG_IMPL, classLoader)
        if (implCls != null) {
            total++
            safeBuilderHook(module, "AutoValue_LatLngImpl.<init>(D,D)") {
                val ctor = ReflectUtil.findConstructorOrNull(
                    implCls, java.lang.Double.TYPE, java.lang.Double.TYPE
                ) ?: throw NoSuchMethodException("AutoValue_LatLngImpl(D,D) not found")

                LocationHook.hookMethod(ctor) { chain ->
                    val coords = LocationHook.currentSpoofCoords()
                    if (coords == null) {
                        chain.proceed()
                    } else {
                        val (lat, lng) = coords
                        OnceLogger.logThrottled(
                            module, TAG_BUILDER, "grab.autovalue.spoof",
                            "[SPOOF] autoValue ctor → lat=$lat lng=$lng",
                            intervalMs = THROTTLE_LATLONG_MS
                        )
                        chain.proceed(arrayOf<Any?>(lat, lng))
                    }
                }
            }.also { if (it) count++ }
        }

        if (count > 0) {
            builderHooked = true
            mark(module, MARK_BUILDER)
        }
        OnceLogger.logOnce(module, TAG_BUILDER, "latlng_builder_summary",
            "AutoValue_LatLngImpl\$Builder hooks: $count/$total installed")
    }

    // ==================================================================
    //  ENTRY 4 — Sortir (shadow mode / filter mode)
    //  ⭐ FIX: JANGAN pakai marker gating. Hook harus reinstall tiap
    //  process start. `sortirHooked` in-memory cukup sebagai guard.
    // ==================================================================
    fun initSortirHooks(module: XposedModule, classLoader: ClassLoader) {
        if (sortirHooked) return
        try {
            SortirHook.install(module, classLoader)
            sortirHooked = true
            // Marker tetap di-set untuk konsistensi log, tapi TIDAK
            // menjadi gate install berikutnya.
            mark(module, MARK_SORTIR)
        } catch (t: Throwable) {
            OnceLogger.logOnce(
                module, TAG, "sortir_hook_fail",
                "Sortir hook install failed: ${t.message}"
            )
        }
    }

    // ==================================================================
    //  Safe hook helpers
    // ==================================================================

    private inline fun safeLifecycleHook(
        module: XposedModule, label: String, block: () -> Unit
    ): Boolean = runCatching { block(); true }
        .onFailure {
            module.log(Log.ERROR, TAG_LIFECYCLE, "$label FAILED: ${it.message}", it)
        }
        .getOrDefault(false)

    private inline fun safeLatLongHook(
        module: XposedModule, label: String, block: () -> Unit
    ): Boolean = runCatching { block(); true }
        .onFailure {
            OnceLogger.logOnce(module, TAG_LATLONG, "fail_$label",
                "$label skipped: ${it.message}")
        }
        .getOrDefault(false)

    private inline fun safeBuilderHook(
        module: XposedModule, label: String, block: () -> Unit
    ): Boolean = runCatching { block(); true }
        .onFailure {
            OnceLogger.logOnce(module, TAG_BUILDER, "fail_$label",
                "$label skipped: ${it.message}")
        }
        .getOrDefault(false)
}