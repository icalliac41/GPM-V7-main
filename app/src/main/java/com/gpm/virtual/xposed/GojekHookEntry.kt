package com.gpm.virtual.xposed

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

private const val MOVING_ACTIVITY_TYPE_NAME     = ""   
private const val STATIONARY_ACTIVITY_TYPE_NAME = ""   
private const val DRIVER_ACTIVITY_SPEED_THRESHOLD = 0.5f  
private const val DRIVER_ACTIVITY_CONFIDENCE    = 100     

private const val FORCED_PING_SOURCE = "HTTP"
private const val FORCED_PING_ORIGIN = "PING_ORIGIN_GOTO_POSITIONING_SDK_WITH_KALMAN"

private const val AGGRESSIVE_SPEED_THRESHOLD = 3.0f  

private fun coherentAggressiveValue(original: Boolean?, speed: Float): Boolean? {
    if (original == true) return true
    val wantAggressive = speed >= AGGRESSIVE_SPEED_THRESHOLD
    if (!wantAggressive) return original
    return true
}

object GojekHookEntry {

    val TAG: String = if (BuildConfig.DEBUG) "GMP:Gojek:" else "T"
    private val TAG_MOCK: String = if (BuildConfig.DEBUG) "GMP:Gojek:[MOCK]" else "T"
    private val TAG_ANTI: String = if (BuildConfig.DEBUG) "GMP:Gojek:[ANTI]" else "T"
    private val TAG_PING: String = if (BuildConfig.DEBUG) "GMP:Gojek:[PING]" else "T"
    const val TARGET_PACKAGE = "com.gojek.partner"

    private const val PREFS_MARKERS = "gpm_markers"
    private const val MARK_LIFECYCLE = "gojek_lifecycle_v1"
    private const val MARK_MOCK = "gojek_mock_v1"
    private const val MARK_ANTI_XPOSED = "gojek_anti_xposed_v1"
    private const val MARK_ANTI_APPSFLYER = "gojek_anti_appsflyer_v1"
    private const val MARK_ANTI_ZYGOTE = "gojek_anti_zygote_v1"
    private const val MARK_PING = "gojek_ping_v1"
    private const val MARK_PING_INJECT = "gojek_ping_inject_v1"

    private object Targets {
        object Lifecycle {
            const val LABEL_NEW_ACTIVITY   = "Lifecycle.newActivity"
            const val CLASS_INSTRUMENTATION = "android.app.Instrumentation"
            const val METHOD_NEW_ACTIVITY   = "newActivity"
            const val LABEL_APP_ONCREATE    = "Lifecycle.appOnCreate"
            const val CLASS_APPLICATION     = "android.app.Application"
            const val METHOD_ON_CREATE      = "onCreate"
        }

        object MockBypass {
            const val LABEL_CHECK_1  = "MockBypass.checker1"
            const val CLASS_CHECK_1  = "dark.TopAppBar_GHTll3Ulambda1"
            const val METHOD_CHECK_1 = "onMessageChannelReady"
            const val LABEL_CHECK_2  = "MockBypass.checker2"
            const val CLASS_CHECK_2  = "dark.accesssetCursorAlpha"
            const val METHOD_CHECK_2 = "extraCallback"
            val METHOD_NAMES_FALLBACK = listOf("onMessageChannelReady", "ICustomTabsCallback", "extraCallback", "extraCallbackWithResult", "onNavigationEvent")
        }

        object AntiXposed {
            const val LABEL_DETECT_1  = "AntiXposed.detector1"
            const val LABEL_DETECT_2  = "AntiXposed.detector2"
            const val CLASS_DETECTOR  = "dark.accessgetMediaStepForwardcp"
            const val METHOD_DETECT_1 = "ICustomTabsCallback"
            const val METHOD_DETECT_2 = "extraCallbackWithResult"
            val METHOD_NAMES_STRING_RETURN = listOf("ICustomTabsCallback", "onMessageChannelReady", "extraCallbackWithResult")
            val METHOD_NAMES_VOID_RETURN = listOf("extraCallbackWithResult", "onNavigationEvent", "onMessageChannelReady")
        }

        object AntiAppsFlyer {
            const val LABEL              = "AntiAppsFlyer.getRevenue"
            const val CLASS              = "com.appsflyer.internal.AFf1iSDK"
            const val METHOD_GET_REVENUE = "getRevenue"
        }

        object AntiZygote {
            const val LABEL             = "AntiZygote.doPreload"
            const val CLASS             = "com.gojek.driver.zygote.DriverZygotePreload"
            const val METHOD_DO_PRELOAD = "doPreload"
        }

        object Ping {
            const val LABEL_HTTP = "Ping.locationRequestInit"
            const val CLASS_LOCATION_PING_REQUEST = "com.gojek.driver.location.ping.LocationPingRequest"
            const val LABEL_COURIER = "Ping.courierBuilderTimestamp"
            const val CLASS_COURIER_BUILDER = "com.gojek.driver.location.ping.courier.model.CourierPingRequestProto\$CourierPingRequest\$Builder"
            const val METHOD_SET_PING_TIMESTAMP     = "setPingTimestamp"
            const val METHOD_SET_LOCATION_TIMESTAMP = "setLocationTimestamp"
            const val LABEL_HTTP_VALUES    = "Ping.locationRequestValues"
            const val LABEL_COURIER_VALUES = "Ping.courierBuilderValues"
            const val LABEL_AGGRESSIVE     = "Ping.aggressiveForce"
            const val LABEL_AGGRESSIVE_GSON = "Ping.aggressiveGsonMeta"
            const val LABEL_DRIVER_ACTIVITY = "Ping.driverActivitySync"
            const val LABEL_DTO_GETTER = "Ping.locationRequestDtoGetter"
            const val METHOD_DTO_GET_LATITUDE  = "getLatitude"
            const val METHOD_DTO_GET_LONGITUDE = "getLongitude"
            const val CLASS_DRIVER_ACTIVITY_GSON = "com.gojek.driver.models.driver.DriverActivity"
            const val CLASS_DRIVER_ACTIVITY_TYPE = "com.gojek.driver.models.driver.DriverActivityType"
            const val CLASS_LOCATION_META_DATA = "com.gojek.driver.location.ping.LocationPingMetaData"
            const val CLASS_PROTO_DRIVER_ACTIVITY = "com.gojek.driver.location.ping.courier.model.CourierPingRequestProto\$DriverActivity"
            const val CLASS_PROTO_DRIVER_ACTIVITY_BUILDER = "com.gojek.driver.location.ping.courier.model.CourierPingRequestProto\$DriverActivity\$Builder"
            const val CLASS_PROTO_META_DATA_BUILDER = "com.gojek.driver.location.ping.courier.model.CourierPingRequestProto\$LocationPingMetaData\$Builder"
            const val CLASS_PROTO_META_DATA = "com.gojek.driver.location.ping.courier.model.CourierPingRequestProto\$LocationPingMetaData"
            const val CLASS_PING_SOURCE = "com.gojek.driver.location.ping.PingSource"
            const val CLASS_PING_ORIGIN = "com.gojek.driver.location.ping.PingOrigin"
        }

        object PingInject {
            const val LABEL_CURRENT_LOCATION = "PingInject.currentLocation"
            const val CLASS_CURRENT_LOCATION = "com.gojek.driver.location.CurrentLocation"
            const val LABEL_SENDER        = "PingInject.senderObserver"
            const val CLASS_PING_SENDER   = "dark.LocalTwoRowsTopAppBarOverridelambda36"
            const val METHOD_ON_NAV_EVENT = "extraCallbackWithResult"
            const val CLASS_COURIER_REQUEST = "com.gojek.driver.location.ping.courier.model.CourierPingRequestProto\$CourierPingRequest"
            val METHOD_NAMES_FALLBACK = listOf("extraCallbackWithResult", "onMessageChannelReady", "ICustomTabsCallback", "extraCallback")
        }
    }

    @Volatile private var installed = false
    @Volatile private var mockInstalled = false
    @Volatile private var antiXposedInstalled = false
    @Volatile private var antiAppsFlyerInstalled = false
    @Volatile private var antiZygoteInstalled = false
    @Volatile private var pingHooked = false
    @Volatile private var pingInjectHooked = false

    private fun isMarked(module: XposedModule, key: String): Boolean = try {
        module.getRemotePreferences(PREFS_MARKERS).getBoolean(key, false)
    } catch (_: Throwable) { false }

    private fun mark(module: XposedModule, key: String) = try {
        module.getRemotePreferences(PREFS_MARKERS).edit().putBoolean(key, true).apply()
    } catch (_: Throwable) {}

    private fun isSpoofingNow(): Boolean = LocationHook.isHookActive && LocationHook.isSpoofActive

    private inline fun logDebug(module: XposedModule, tag: String, block: () -> String) {
        if (BuildConfig.DEBUG) {
            try { module.log(Log.INFO, tag, block()) } catch (_: Throwable) {}
        }
    }

    private data class SpoofSnapshot(
        val lat: Double, val lng: Double, val acc: Float, val spd: Float, val brg: Float, val alt: Double
    )

    /**
     * ⭐ PERBAIKAN UTAMA: Menggunakan snapshotSpoofLat/Lng() (nilai cache) bukan currentSpoofCoords()
     * Cache sudah termasuk VtracingEngine (gerakan dinamis) sehingga ping selalu real-time.
     */
    private fun snapshotSpoofValues(module: XposedModule): SpoofSnapshot? {
        if (!isSpoofingNow()) return null
        if (!LocationHook.warmSpoofCache()) return null

        var lat = LocationHook.snapshotSpoofLat()
        var lng = LocationHook.snapshotSpoofLng()
        
        if (lat == 0.0 && lng == 0.0) {
            val coords = LocationHook.currentSpoofCoords()
            if (coords != null) { lat = coords.first; lng = coords.second }
        }
        
        if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) {
            logDebug(module, TAG) { "[SNAPSHOT] Invalid coords: ($lat, $lng) — SKIP spoof" }
            return null
        }

        val acc = LocationHook.snapshotSpoofAccuracy().let { if (it.isNaN() || it.isInfinite() || it <= 0f || it > 50f) 5.0f else it }
        val spd = LocationHook.snapshotSpoofSpeed().let { if (it.isNaN() || it.isInfinite() || it < 0f || it > 100f) 0f else it }
        val brg = LocationHook.snapshotSpoofBearing().let { if (it.isNaN() || it.isInfinite()) 0f else ((it % 360f) + 360f) % 360f }
        val alt = LocationHook.snapshotSpoofAltitude().let { if (it.isNaN() || it.isInfinite() || it < -500.0 || it > 9000.0) 0.0 else it }

        return SpoofSnapshot(lat = lat, lng = lng, acc = acc, spd = spd, brg = brg, alt = alt)
    }

    fun forceReinstall(module: XposedModule, classLoader: ClassLoader) {
        pingHooked = false; pingInjectHooked = false; mockInstalled = false; antiXposedInstalled = false
        try {
            module.getRemotePreferences(PREFS_MARKERS).edit()
                .remove(MARK_PING).remove(MARK_PING_INJECT).remove(MARK_MOCK).remove(MARK_ANTI_XPOSED).apply()
        } catch (_: Throwable) {}

        var ok = 0; var total = 0
        total++; if (safePingHook(module, "reinstall-ping") { initGojekPingHooks(module, classLoader) }) ok++
        total++; if (safePingHook(module, "reinstall-pingInject") { initGojekPingInjectorHooks(module, classLoader) }) ok++
        total++; if (safeMockHook(module, "reinstall-mock") { initMockLocationBypassHooks(module, classLoader) }) ok++
        total++; if (safeAntiHook(module, "reinstall-anti") { initAntiXposedBypassHooks(module, classLoader) }) ok++
        logDebug(module, TAG) { "[ReHook] Gojek reinstall: $ok/$total ok" }
    }

    fun initGojekHooks(module: XposedModule, classLoader: ClassLoader) {
        // ⭐ GojekOkHttpGuard telah dihapus, Cancel Cantik ditangani oleh CancelCantikHook di ModernModule.
        
        if (installed || isMarked(module, MARK_LIFECYCLE)) return
        var count = 0; var total = 0

        total++
        safeHook(module, Targets.Lifecycle.LABEL_NEW_ACTIVITY) {
            val method = ReflectUtil.findMethodOrNull(
                className = Targets.Lifecycle.CLASS_INSTRUMENTATION, classLoader = classLoader,
                methodName = Targets.Lifecycle.METHOD_NEW_ACTIVITY,
                ClassLoader::class.java, String::class.java, Intent::class.java
            ) ?: throw NoSuchMethodException(Targets.Lifecycle.LABEL_NEW_ACTIVITY)
            method.isAccessible = true
            LocationHook.hookMethod(method, interceptor = PrefGojek(module = module, mode = PrefGojek.MODE_NEW_ACTIVITY))
        }.also { if (it) count++ }

        total++
        safeHook(module, Targets.Lifecycle.LABEL_APP_ONCREATE) {
            val method = ReflectUtil.findMethodOrNull(
                className = Targets.Lifecycle.CLASS_APPLICATION, classLoader = classLoader,
                methodName = Targets.Lifecycle.METHOD_ON_CREATE
            ) ?: throw NoSuchMethodException(Targets.Lifecycle.LABEL_APP_ONCREATE)
            method.isAccessible = true
            LocationHook.hookMethod(method, interceptor = PrefGojek(module = module, mode = PrefGojek.MODE_APP_ONCREATE))
        }.also { if (it) count++ }

        logSummary(module, TAG, "LIFECYCLE", count, total, MARK_LIFECYCLE) { installed = true }
    }

    fun initMockLocationBypassHooks(module: XposedModule, classLoader: ClassLoader) {
        if (mockInstalled || isMarked(module, MARK_MOCK)) return
        if (!LocationHook.isAntiMockEnabled) return

        var count = 0; val total = 2
        safeMockHook(module, Targets.MockBypass.LABEL_CHECK_1) {
            val method = resolveMockMethod(classLoader, Targets.MockBypass.CLASS_CHECK_1, Targets.MockBypass.METHOD_CHECK_1, Targets.MockBypass.METHOD_NAMES_FALLBACK) ?: throw ClassNotFoundException(Targets.MockBypass.LABEL_CHECK_1)
            method.isAccessible = true
            LocationHook.hookMethod(method, interceptor = MockLocationBypassHook(module))
        }.also { if (it) count++ }

        safeMockHook(module, Targets.MockBypass.LABEL_CHECK_2) {
            val method = resolveMockMethod(classLoader, Targets.MockBypass.CLASS_CHECK_2, Targets.MockBypass.METHOD_CHECK_2, Targets.MockBypass.METHOD_NAMES_FALLBACK) ?: throw ClassNotFoundException(Targets.MockBypass.LABEL_CHECK_2)
            method.isAccessible = true
            LocationHook.hookMethod(method, interceptor = MockLocationBypassHook(module))
        }.also { if (it) count++ }

        logSummary(module, TAG_MOCK, "MOCK", count, total, MARK_MOCK) { mockInstalled = true }
    }

    private fun resolveMockMethod(classLoader: ClassLoader, className: String, primaryMethod: String, fallbacks: List<String>): Method? {
        val cls = ReflectUtil.findClassOrNull(className, classLoader) ?: return null
        ReflectUtil.findMethodExactOrNull(cls, primaryMethod, Location::class.java)?.let { return it }
        for (name in fallbacks) {
            if (name == primaryMethod) continue
            val m = ReflectUtil.findMethodExactOrNull(cls, name, Location::class.java)
            if (m != null) return m
        }
        for (m in cls.declaredMethods) {
            if (m.returnType != Boolean::class.javaPrimitiveType) continue
            if (m.parameterTypes.size != 1) continue
            if (m.parameterTypes[0] != Location::class.java) continue
            m.isAccessible = true; return m
        }
        return null
    }

    fun initAntiXposedBypassHooks(module: XposedModule, classLoader: ClassLoader) {
        if (antiXposedInstalled || isMarked(module, MARK_ANTI_XPOSED)) return
        if (!LocationHook.isAntiMockEnabled) return

        var count = 0; val total = 2
        safeAntiHook(module, Targets.AntiXposed.LABEL_DETECT_1) {
            val method = resolveAntiXposedGetter(classLoader, Targets.AntiXposed.CLASS_DETECTOR, Targets.AntiXposed.METHOD_DETECT_1, Targets.AntiXposed.METHOD_NAMES_STRING_RETURN) ?: throw ClassNotFoundException(Targets.AntiXposed.LABEL_DETECT_1)
            method.isAccessible = true
            LocationHook.hookMethod(method, interceptor = ConstantReturnHook(""))
        }.also { if (it) count++ }

        safeAntiHook(module, Targets.AntiXposed.LABEL_DETECT_2) {
            val method = resolveAntiXposedVoidTrigger(classLoader, Targets.AntiXposed.CLASS_DETECTOR, Targets.AntiXposed.METHOD_DETECT_2, Targets.AntiXposed.METHOD_NAMES_VOID_RETURN) ?: throw ClassNotFoundException(Targets.AntiXposed.LABEL_DETECT_2)
            method.isAccessible = true
            if (method.returnType == Void.TYPE) {
                LocationHook.hookMethod(method) { chain ->
                    if (LocationHook.isHookActive && !LocationHook.isModulePackage(LocationHook.getCurrentPackageName())) null
                    else chain.proceed()
                }
            } else {
                LocationHook.hookMethod(method, interceptor = ConstantReturnHook(false))
            }
        }.also { if (it) count++ }

        logSummary(module, TAG_ANTI, "XPOSED", count, total, MARK_ANTI_XPOSED) { antiXposedInstalled = true }
    }

    private fun resolveAntiXposedGetter(classLoader: ClassLoader, className: String, primaryMethod: String, fallbacks: List<String>): Method? {
        val cls = ReflectUtil.findClassOrNull(className, classLoader) ?: return null
        ReflectUtil.findMethodExactOrNull(cls, primaryMethod)?.takeIf { it.returnType == String::class.java }?.let { return it }
        for (name in fallbacks) {
            if (name == primaryMethod) continue
            val m = ReflectUtil.findMethodExactOrNull(cls, name)
            if (m != null && m.returnType == String::class.java) return m
        }
        for (m in cls.declaredMethods) {
            if (m.parameterCount != 0 || m.returnType != String::class.java || m.name == "toString") continue
            m.isAccessible = true; return m
        }
        return null
    }

    private fun resolveAntiXposedVoidTrigger(classLoader: ClassLoader, className: String, primaryMethod: String, fallbacks: List<String>): Method? {
        val cls = ReflectUtil.findClassOrNull(className, classLoader) ?: return null
        ReflectUtil.findMethodExactOrNull(cls, primaryMethod)?.takeIf { it.returnType == Void.TYPE }?.let { return it }
        for (name in fallbacks) {
            if (name == primaryMethod) continue
            val m = ReflectUtil.findMethodExactOrNull(cls, name)
            if (m != null && m.returnType == Void.TYPE) return m
        }
        for (m in cls.declaredMethods) {
            if (m.parameterCount != 0 || m.returnType != Void.TYPE || m.name == "<init>" || m.name == "<clinit>") continue
            m.isAccessible = true; return m
        }
        return null
    }

    fun initAppsFlyerBypassHooks(module: XposedModule, classLoader: ClassLoader) {
        if (antiAppsFlyerInstalled || isMarked(module, MARK_ANTI_APPSFLYER)) return
        if (!LocationHook.isAntiMockEnabled) return

        var count = 0; val total = 1
        safeAntiHook(module, Targets.AntiAppsFlyer.LABEL) {
            val method = ReflectUtil.findMethodOrNull(Targets.AntiAppsFlyer.CLASS, classLoader, Targets.AntiAppsFlyer.METHOD_GET_REVENUE) ?: throw ClassNotFoundException(Targets.AntiAppsFlyer.LABEL)
            method.isAccessible = true
            LocationHook.hookMethod(method, interceptor = ConstantReturnHook(""))
        }.also { if (it) count++ }

        logSummary(module, TAG_ANTI, "APPSFLYER", count, total, MARK_ANTI_APPSFLYER) { antiAppsFlyerInstalled = true }
    }

    fun initZygotePreloadBypassHooks(module: XposedModule, classLoader: ClassLoader) {
        if (antiZygoteInstalled || isMarked(module, MARK_ANTI_ZYGOTE)) return
        if (!LocationHook.isAntiMockEnabled) return

        var count = 0; val total = 1
        safeAntiHook(module, Targets.AntiZygote.LABEL) {
            val method = ReflectUtil.findMethodOrNull(Targets.AntiZygote.CLASS, classLoader, Targets.AntiZygote.METHOD_DO_PRELOAD, ApplicationInfo::class.java) ?: throw ClassNotFoundException(Targets.AntiZygote.LABEL)
            method.isAccessible = true
            LocationHook.hookMethod(method, interceptor = ConstantReturnHook(null))
        }.also { if (it) count++ }

        logSummary(module, TAG_ANTI, "ZYGOTE", count, total, MARK_ANTI_ZYGOTE) { antiZygoteInstalled = true }
    }

    fun initGojekPingHooks(module: XposedModule, classLoader: ClassLoader) {
        if (pingHooked || isMarked(module, MARK_PING)) return
        var count = 0; var total = 0

        total++
        safePingHook(module, Targets.Ping.LABEL_HTTP) {
            val cls = ReflectUtil.findClassOrNull(Targets.Ping.CLASS_LOCATION_PING_REQUEST, classLoader) ?: throw ClassNotFoundException(Targets.Ping.LABEL_HTTP)
            val ctors = ReflectUtil.findAllConstructors(cls)
            if (ctors.isEmpty()) throw NoSuchMethodException(Targets.Ping.LABEL_HTTP)
            val hook = PingTimestampHook(module, methodName = Targets.Ping.LABEL_HTTP)
            for (ctor in ctors) LocationHook.hookMethod(ctor, interceptor = hook)
        }.also { if (it) count++ }

        total++
        safePingHook(module, Targets.Ping.LABEL_HTTP_VALUES) { installLocationPingRequestConstructorValueHook(module, classLoader) }.also { if (it) count++ }

        total++
        safePingHook(module, Targets.Ping.LABEL_COURIER) {
            val builderCls = ReflectUtil.findClassOrNull(Targets.Ping.CLASS_COURIER_BUILDER, classLoader) ?: throw ClassNotFoundException(Targets.Ping.LABEL_COURIER)
            var setterCount = 0
            ReflectUtil.findMethodExactOrNull(builderCls, Targets.Ping.METHOD_SET_PING_TIMESTAMP, java.lang.Long.TYPE)?.let { m ->
                LocationHook.hookMethod(m, interceptor = PingTimestampHook(module, methodName = Targets.Ping.LABEL_COURIER)); setterCount++
            }
            ReflectUtil.findMethodExactOrNull(builderCls, Targets.Ping.METHOD_SET_LOCATION_TIMESTAMP, java.lang.Long.TYPE)?.let { m ->
                LocationHook.hookMethod(m, interceptor = PingTimestampHook(module, methodName = Targets.Ping.LABEL_COURIER)); setterCount++
            }
            if (setterCount == 0) throw NoSuchMethodException(Targets.Ping.LABEL_COURIER)
        }.also { if (it) count++ }

        total++
        safePingHook(module, Targets.Ping.LABEL_COURIER_VALUES) { installCourierPingBuilderValueHook(module, classLoader) }.also { if (it) count++ }

        total++
        safePingHook(module, Targets.Ping.LABEL_AGGRESSIVE) { installAggressivePingForceHook(module, classLoader) }.also { if (it) count++ }

        total++
        safePingHook(module, Targets.Ping.LABEL_AGGRESSIVE_GSON) { installGsonMetaDataAggressiveHook(module, classLoader) }.also { if (it) count++ }

        total++
        safePingHook(module, Targets.Ping.LABEL_DTO_GETTER) { installLocationPingRequestGetterHook(module, classLoader) }.also { if (it) count++ }

        total++
        safePingHook(module, Targets.Ping.LABEL_DRIVER_ACTIVITY) { installDriverActivitySync(module, classLoader) }.also { if (it) count++ }

        logSummary(module, TAG, "PING", count, total, MARK_PING) { pingHooked = true }
    }

    private fun resolveEnumConstant(classLoader: ClassLoader, className: String, enumName: String): Any? {
        if (enumName.isBlank()) return null
        val cls = ReflectUtil.findClassOrNull(className, classLoader) ?: return null
        if (cls.isEnum) { for (v in cls.enumConstants ?: emptyArray()) { if ((v as Enum<*>).name.equals(enumName, ignoreCase = true)) return v } }
        for (f in cls.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (f.name.equals(enumName, ignoreCase = true)) { try { f.isAccessible = true; return f.get(null) } catch (_: Throwable) {} }
        }
        return null
    }

    /**
     * ⭐ PERBAIKAN UTAMA: Menambahkan hook getSpeed, getBearing, getAccuracy, getAltitude.
     */
    private fun installLocationPingRequestGetterHook(module: XposedModule, classLoader: ClassLoader) {
        val label = Targets.Ping.LABEL_DTO_GETTER
        val cls = ReflectUtil.findClassOrNull(Targets.Ping.CLASS_LOCATION_PING_REQUEST, classLoader) ?: throw ClassNotFoundException(label)
        var hooked = 0

        // Latitude
        ReflectUtil.findMethodExactOrNull(cls, Targets.Ping.METHOD_DTO_GET_LATITUDE)?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snapshot = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                snapshot.lat
            }; hooked++
        }

        // Longitude
        ReflectUtil.findMethodExactOrNull(cls, Targets.Ping.METHOD_DTO_GET_LONGITUDE)?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snapshot = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                snapshot.lng
            }; hooked++
        }

        // ⭐ Accuracy
        ReflectUtil.findMethodExactOrNull(cls, "getAccuracy")?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snapshot = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                if (m.returnType == java.lang.Double.TYPE) snapshot.acc.toDouble() else snapshot.acc
            }; hooked++
        }

        // ⭐ Speed
        ReflectUtil.findMethodExactOrNull(cls, "getSpeed")?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snapshot = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                if (m.returnType == java.lang.Double.TYPE) snapshot.spd.toDouble() else snapshot.spd
            }; hooked++
        }

        // ⭐ Bearing
        ReflectUtil.findMethodExactOrNull(cls, "getBearing")?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snapshot = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                if (m.returnType == java.lang.Double.TYPE) snapshot.brg.toDouble() else snapshot.brg
            }; hooked++
        }

        // ⭐ Altitude
        ReflectUtil.findMethodExactOrNull(cls, "getAltitude")?.let { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snapshot = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                if (m.returnType == java.lang.Double.TYPE) snapshot.alt.toDouble() else snapshot.alt.toFloat()
            }; hooked++
        }

        val forcedSource = resolveEnumConstant(classLoader, Targets.Ping.CLASS_PING_SOURCE, FORCED_PING_SOURCE)
        val forcedOrigin = resolveEnumConstant(classLoader, Targets.Ping.CLASS_PING_ORIGIN, FORCED_PING_ORIGIN)

        if (forcedSource != null) {
            ReflectUtil.findMethodExactOrNull(cls, "getSource")?.let { m ->
                m.isAccessible = true; LocationHook.hookMethod(m) { chain -> if (isSpoofingNow()) forcedSource else chain.proceed() }; hooked++
            }
        }
        if (forcedOrigin != null) {
            ReflectUtil.findMethodExactOrNull(cls, "getPingOrigin")?.let { m ->
                m.isAccessible = true; LocationHook.hookMethod(m) { chain -> if (isSpoofingNow()) forcedOrigin else chain.proceed() }; hooked++
            }
        }
        if (hooked == 0) throw NoSuchMethodException(label)
    }

    private enum class PingValueKind { LAT, LNG, ACCURACY, SPEED, BEARING, ALTITUDE, UNKNOWN }

    private fun classifyPingField(methodName: String): PingValueKind {
        val n = methodName.lowercase().replace("_", "")
        return when {
            n.contains("latitude") -> PingValueKind.LAT
            n.contains("longitude") -> PingValueKind.LNG
            n.contains("accuracy") -> PingValueKind.ACCURACY
            n.contains("speed") -> PingValueKind.SPEED
            n.contains("bearing") || n.contains("heading") -> PingValueKind.BEARING
            n.contains("altitude") -> PingValueKind.ALTITUDE
            else -> PingValueKind.UNKNOWN
        }
    }

    private fun spoofValueFor(kind: PingValueKind, snap: SpoofSnapshot): Double? = when (kind) {
        PingValueKind.LAT -> snap.lat; PingValueKind.LNG -> snap.lng
        PingValueKind.ACCURACY -> snap.acc.toDouble(); PingValueKind.SPEED -> snap.spd.toDouble()
        PingValueKind.BEARING -> snap.brg.toDouble(); PingValueKind.ALTITUDE -> snap.alt
        else -> null
    }

    private fun installLocationPingRequestConstructorValueHook(module: XposedModule, classLoader: ClassLoader) {
        val label = Targets.Ping.LABEL_HTTP_VALUES
        val cls = ReflectUtil.findClassOrNull(Targets.Ping.CLASS_LOCATION_PING_REQUEST, classLoader) ?: throw ClassNotFoundException(label)
        val ctor = cls.declaredConstructors.firstOrNull { it.parameterCount == 20 } ?: throw NoSuchMethodException("$label — 20-arg constructor tidak ditemukan")
        val forcedSource = resolveEnumConstant(classLoader, Targets.Ping.CLASS_PING_SOURCE, FORCED_PING_SOURCE)
        val forcedOrigin = resolveEnumConstant(classLoader, Targets.Ping.CLASS_PING_ORIGIN, FORCED_PING_ORIGIN)

        ctor.isAccessible = true
        LocationHook.hookMethod(ctor) { chain ->
            if (!isSpoofingNow()) return@hookMethod chain.proceed()
            val snap = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
            val args = chain.args.toMutableList()
            if (args.size != 20) return@hookMethod chain.proceed()

            try {
                if (args[1] is Double) args[1] = snap.lat
                if (args[2] is Double) args[2] = snap.lng
                if (args[4] is Double) args[4] = snap.alt
                if (args[5] is Float)  args[5] = snap.brg
                if (args[6] is Float)  args[6] = snap.acc
                if (args[7] is Float)  args[7] = snap.spd
                if (snap.spd > 0.01f && args[13] != null) args[13] = snap.spd
                if (snap.brg > 0.01f && args[14] != null) args[14] = snap.brg
                if (forcedSource != null && args[18] != null) args[18] = forcedSource
                if (forcedOrigin != null && args[19] != null) args[19] = forcedOrigin
                chain.proceed(args.toTypedArray())
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) { try { module.log(Log.WARN, TAG_PING, "Constructor transform fail: ${t.message}") } catch (_: Throwable) {} }
                chain.proceed()
            }
        }
    }

    private fun installCourierPingBuilderValueHook(module: XposedModule, classLoader: ClassLoader) {
        val label = Targets.Ping.LABEL_COURIER_VALUES
        val builderCls = ReflectUtil.findClassOrNull(Targets.Ping.CLASS_COURIER_BUILDER, classLoader) ?: throw ClassNotFoundException(label)
        var count = 0
        for (m in builderCls.declaredMethods) {
            if (m.parameterCount != 1) continue
            val kind = classifyPingField(m.name)
            if (kind == PingValueKind.UNKNOWN) continue
            val p0 = m.parameterTypes[0]
            val isDouble = p0 == java.lang.Double.TYPE || p0 == java.lang.Double::class.java
            val isFloat  = p0 == java.lang.Float.TYPE  || p0 == java.lang.Float::class.java
            if (!isDouble && !isFloat) continue
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snap = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                val v = spoofValueFor(kind, snap) ?: return@hookMethod chain.proceed()
                if (isDouble) chain.proceed(arrayOf<Any?>(v)) else chain.proceed(arrayOf<Any?>(v.toFloat()))
            }
            count++
        }
        if (count == 0) throw NoSuchMethodException("$label — tidak ada setter numerik")
    }

    private fun installAggressivePingForceHook(module: XposedModule, classLoader: ClassLoader) {
        val label = Targets.Ping.LABEL_AGGRESSIVE
        var hookedAny = false

        val metaBuilderCls = ReflectUtil.findClassOrNull(Targets.Ping.CLASS_PROTO_META_DATA_BUILDER, classLoader)
        metaBuilderCls?.declaredMethods?.firstOrNull { it.name == "setIsAggressivePing" && it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE }?.also { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snap = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                val original = chain.args[0] as? Boolean ?: false
                val desired  = coherentAggressiveValue(original, snap.spd) ?: false
                if (desired != original) chain.proceed(arrayOf<Any?>(desired)) else chain.proceed()
            }
            hookedAny = true
        }

        ReflectUtil.findClassOrNull(Targets.Ping.CLASS_COURIER_BUILDER, classLoader)?.declaredMethods?.firstOrNull { it.name == "setIsAggressivePing" && it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE }?.also { m ->
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!isSpoofingNow()) return@hookMethod chain.proceed()
                val snap = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
                val original = chain.args[0] as? Boolean ?: false
                val desired  = coherentAggressiveValue(original, snap.spd) ?: false
                if (desired != original) chain.proceed(arrayOf<Any?>(desired)) else chain.proceed()
            }
            hookedAny = true
        }

        if (!hookedAny) throw NoSuchMethodException("$label — setIsAggressivePing missing")
    }

    private fun installGsonMetaDataAggressiveHook(module: XposedModule, classLoader: ClassLoader) {
        val label = Targets.Ping.LABEL_AGGRESSIVE_GSON
        val metaCls = ReflectUtil.findClassOrNull(Targets.Ping.CLASS_LOCATION_META_DATA, classLoader) ?: throw ClassNotFoundException(label)
        val ctor = metaCls.declaredConstructors.firstOrNull {
            it.parameterCount == 2 && List::class.java.isAssignableFrom(it.parameterTypes[0]) && (it.parameterTypes[1] == java.lang.Boolean.TYPE || it.parameterTypes[1] == java.lang.Boolean::class.java)
        } ?: throw NoSuchMethodException("$label — LocationPingMetaData(List, Boolean)")

        ctor.isAccessible = true
        LocationHook.hookMethod(ctor) { chain ->
            if (!isSpoofingNow()) return@hookMethod chain.proceed()
            val snap = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
            val args = chain.args.toMutableList()
            if (args.size != 2) return@hookMethod chain.proceed()
            val original = args[1] as? Boolean
            val desired  = coherentAggressiveValue(original, snap.spd)
            if (desired != original) { args[1] = desired; chain.proceed(args.toTypedArray()) } else chain.proceed()
        }
    }

    private fun installDriverActivitySync(module: XposedModule, classLoader: ClassLoader) {
        val configFilled = MOVING_ACTIVITY_TYPE_NAME.isNotBlank() || STATIONARY_ACTIVITY_TYPE_NAME.isNotBlank()
        if (!configFilled) return
    }

    fun initGojekPingInjectorHooks(module: XposedModule, classLoader: ClassLoader) {
        if (pingInjectHooked || isMarked(module, MARK_PING_INJECT)) return
        var count = 0; var total = 0

        total++; safePingHook(module, Targets.PingInject.LABEL_CURRENT_LOCATION) { installCurrentLocationGetterHook(module, classLoader) }.also { if (it) count++ }
        total++; safePingHook(module, Targets.PingInject.LABEL_SENDER) { installSenderObserverHook(module, classLoader) }.also { if (it) count++ }

        logSummary(module, TAG, "INJECT", count, total, MARK_PING_INJECT) { pingInjectHooked = true }
    }

    private fun installCurrentLocationGetterHook(module: XposedModule, classLoader: ClassLoader) {
        val label = Targets.PingInject.LABEL_CURRENT_LOCATION
        val cls = ReflectUtil.findClassOrNull(Targets.PingInject.CLASS_CURRENT_LOCATION, classLoader) ?: throw ClassNotFoundException(label)
        val latLngPair = discoverLatLngMethods(cls) ?: throw NoSuchMethodException("$label (lat/lng discovery gagal)")

        val latMethod = latLngPair.first; val lngMethod = latLngPair.second
        latMethod.isAccessible = true
        LocationHook.hookMethod(latMethod) { chain ->
            if (!isSpoofingNow()) return@hookMethod chain.proceed()
            val snap = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
            snap.lat
        }
        lngMethod.isAccessible = true
        LocationHook.hookMethod(lngMethod) { chain ->
            if (!isSpoofingNow()) return@hookMethod chain.proceed()
            val snap = snapshotSpoofValues(module) ?: return@hookMethod chain.proceed()
            snap.lng
        }
    }

    private fun discoverLatLngMethods(cls: Class<*>): Pair<Method, Method>? {
        val UNIQUE_LAT = 11.111111111111; val UNIQUE_LNG = 22.222222222222
        return try {
            val dummyLoc = Location("gpm_discover").apply { latitude = UNIQUE_LAT; longitude = UNIQUE_LNG }
            val ctor = try {
                cls.getConstructor(Location::class.java, java.lang.Long.TYPE, String::class.java)
            } catch (_: Throwable) {
                cls.declaredConstructors.firstOrNull {
                    it.parameterTypes.size == 3 && it.parameterTypes[0] == Location::class.java && it.parameterTypes[1] == java.lang.Long.TYPE && it.parameterTypes[2] == String::class.java
                } ?: return null
            }
            ctor.isAccessible = true
            val dummy = ctor.newInstance(dummyLoc, 999_999L, "gpm")

            var latM: Method? = null; var lngM: Method? = null
            for (m in cls.declaredMethods) {
                if (m.parameterCount != 0 || m.returnType != java.lang.Double.TYPE) continue
                m.isAccessible = true
                val v = try { m.invoke(dummy) as? Double } catch (_: Throwable) { null } ?: continue
                when (v) { UNIQUE_LAT -> latM = m; UNIQUE_LNG -> lngM = m }
                if (latM != null && lngM != null) break
            }
            if (latM != null && lngM != null) latM to lngM else null
        } catch (_: Throwable) { null }
    }

    /**
     * ⭐ PERBAIKAN: Sender observer sekarang mencatat ping yang dikirim untuk debugging.
     */
    private fun installSenderObserverHook(module: XposedModule, classLoader: ClassLoader) {
        val label = Targets.PingInject.LABEL_SENDER
        val cls = ReflectUtil.findClassOrNull(Targets.PingInject.CLASS_PING_SENDER, classLoader) ?: throw ClassNotFoundException(label)
        val reqCls = ReflectUtil.findClassOrNull(Targets.PingInject.CLASS_COURIER_REQUEST, classLoader) ?: throw ClassNotFoundException(label)
        
        var senderMethod: Method? = null
        for (methodName in (listOf(Targets.PingInject.METHOD_ON_NAV_EVENT) + Targets.PingInject.METHOD_NAMES_FALLBACK).distinct()) {
            for (m in ReflectUtil.findMethodsByName(cls, methodName)) {
                if (m.parameterTypes.size == 2 && m.parameterTypes[0] == String::class.java && m.parameterTypes[1] == reqCls) { senderMethod = m; break }
            }
            if (senderMethod != null) break
        }
        val target = senderMethod ?: throw NoSuchMethodException(label)
        target.isAccessible = true
        LocationHook.hookMethod(target) { chain ->
            if (isSpoofingNow()) {
                // Log snapshot values saat ping dikirim
                val snap = snapshotSpoofValues(module)
                if (snap != null && BuildConfig.DEBUG) {
                    try {
                        module.log(Log.INFO, TAG_PING,
                            "[PING SENT] lat=%.6f lng=%.6f acc=%.1f spd=%.2f brg=%.0f".format(
                                snap.lat, snap.lng, snap.acc, snap.spd, snap.brg
                            ))
                    } catch (_: Throwable) {}
                }
            }
            chain.proceed()
        }
    }

    private inline fun logSummary(
        module: XposedModule, tag: String, category: String, count: Int, total: Int, markKey: String, onAllSuccess: () -> Unit
    ) {
        when {
            count == total -> {
                onAllSuccess(); mark(module, markKey)
                logDebug(module, tag) { "[$category] ✅ Hooks: $count/$total terpasang" }
            }
            count > 0 -> {
                if (BuildConfig.DEBUG) {
                    try { module.log(Log.WARN, tag, "[$category] ⚠️ PARTIAL: $count/$total (no mark, will retry)") } catch (_: Throwable) {}
                }
            }
            else -> { module.log(Log.ERROR, tag, "[$category] ❌ 0/$total GAGAL") }
        }
    }

    private fun safeHook(module: XposedModule, label: String, block: () -> Unit): Boolean =
        try { block(); true } catch (t: Throwable) { module.log(Log.ERROR, TAG, "$label gagal: ${t.message}", t); false }

    private fun safeMockHook(module: XposedModule, label: String, block: () -> Unit): Boolean =
        try { block(); true } catch (t: Throwable) { logDebug(module, TAG_MOCK) { "$label dilewati: ${t.message}" }; false }

    private fun safeAntiHook(module: XposedModule, label: String, block: () -> Unit): Boolean =
        try { block(); true } catch (t: Throwable) { logDebug(module, TAG_ANTI) { "$label dilewati: ${t.message}" }; false }

    private fun safePingHook(module: XposedModule, label: String, block: () -> Unit): Boolean =
        try { block(); true } catch (t: Throwable) { logDebug(module, TAG) { "$label dilewati: ${t.message}" }; false }
}