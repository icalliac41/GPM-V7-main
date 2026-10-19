package com.gpm.virtual.xposed

import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ModernModule : XposedModule() {

    @Volatile private var processName: String = "unknown"
    @Volatile private var isSystemServer: Boolean = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        if (isSystemServer || processName == "system_server" || processName == "android") {
            logDebug("Bypassing onModuleLoaded for system_server / android")
            return
        }
        LocationHook.attachModule(this, isSystemServer, processName)
        if (LocationHook.isModulePackage(processName)) {
            try { com.gpm.virtual.util.XposedStatus.isHookedDirectly = true } catch (_: Throwable) {}
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {}

    private val hookedClassLoaders = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<ClassLoader, Boolean>()
    )

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        if (isSystemServer || pkg == "android" || pkg == "system_server" || pkg.startsWith("android.")) return

        if (LocationHook.isModulePackage(pkg)) {
            try {
                val statusClass = ReflectUtil.findClassOrNull("com.gpm.virtual.util.XposedStatus", param.classLoader)
                if (statusClass != null) {
                    val field = statusClass.getDeclaredField("isHookedDirectly")
                    field.isAccessible = true
                    field.setBoolean(null, true)
                    val m = statusClass.getDeclaredMethod("isModuleActive")
                    m.isAccessible = true
                    hook(m).intercept { true }
                }
            } catch (_: Throwable) {}
            return
        }

        if (pkg == "com.google.android.gms") {
            try { ConnectionStabilityHook.initGmsGcmHooks(this, param.classLoader) } catch (_: Throwable) {}
            return
        }

        synchronized(hookedClassLoaders) {
            if (hookedClassLoaders.contains(param.classLoader)) return
            hookedClassLoaders.add(param.classLoader)
        }

        if (pkg == GojekHookEntry.TARGET_PACKAGE) {
            installGojekHooks(param.classLoader)
            try { LitmusObserver.invalidate(); LitmusObserver.logSnapshot(this) } catch (_: Throwable) {}
        }

        if (GrabHookEntry.isTargetPackage(pkg)) {
            try {
                GrabHookEntry.initGrabLifecycleHooks(this, param.classLoader)
                GrabHookEntry.initLatLongHooks(this, param.classLoader)
                GrabHookEntry.initLatLngBuilderHooks(this, param.classLoader)
                GrabHookEntry.initSortirHooks(this, param.classLoader)
                TurboDisabler.install(this, param.classLoader)
                // ⭐ REKOMENDASI ALL-STAR: bypass unread counter
                UnreadBypassHook.install(this, param.classLoader)
            } catch (_: Throwable) {}
        }

        try { LocationHook.initPackage(pkg, param.classLoader) } catch (_: Throwable) {}

        try {
            if (ConnectionStabilityHook.isTargetPackage(pkg)) {
                try { FcmPriorityBridge.reset() } catch (_: Throwable) {}
                ConnectionStabilityHook.init(this, param.classLoader, pkg)
            }
        } catch (_: Throwable) {}
    }

    private fun installGojekHooks(classLoader: ClassLoader) {
        runCatching { GojekHookEntry.initGojekHooks(this, classLoader) }
        runCatching { GojekHookEntry.initMockLocationBypassHooks(this, classLoader) }
        runCatching { GojekHookEntry.initAntiXposedBypassHooks(this, classLoader) }
        runCatching { GojekHookEntry.initAppsFlyerBypassHooks(this, classLoader) }
        runCatching { GojekHookEntry.initZygotePreloadBypassHooks(this, classLoader) }
        runCatching { GojekHookEntry.initGojekPingHooks(this, classLoader) }
        runCatching { GojekHookEntry.initGojekPingInjectorHooks(this, classLoader) }
        runCatching { CancelCantikHook.install(this, classLoader) }
        runCatching { SignatureHook.install(this, classLoader) }
    }

    private fun logDebug(msg: String) {
        if (!BuildConfig.DEBUG) return
        try { log(Log.INFO, TAG, msg) } catch (_: Throwable) {}
    }

    companion object {
        const val TAG = "GPM-Xposed"
    }
}