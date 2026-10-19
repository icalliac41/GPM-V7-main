package com.gpm.virtual.xposed

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

class PrefGojek(
    private val module: XposedModule,
    private val mode: Int
) : XposedInterface.Hooker {

    companion object {
        const val MODE_APP_ONCREATE = 0
        const val MODE_NEW_ACTIVITY = 1

        private val TAG = GojekHookEntry.TAG
        private const val GOJEK_INTERNAL_PREFIX = "com.gojek"

        @Volatile private var callbacksRegistered = false
    }

    override fun intercept(chain: XposedInterface.Chain): Any? {
        return when (mode) {
            MODE_APP_ONCREATE -> handleApplicationOnCreate(chain)
            MODE_NEW_ACTIVITY -> handleNewActivity(chain)
            else -> chain.proceed()
        }
    }

    private fun handleApplicationOnCreate(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        try {
            val app = chain.thisObject as? Application
            if (app != null && !callbacksRegistered) {
                app.registerActivityLifecycleCallbacks(GojekActivityLifecycle(module))
                callbacksRegistered = true
                OnceLogger.logOnce(
                    module, TAG, "app_oncreate_injected",
                    "Gojek Application.onCreate -> LifecycleCallbacks injected"
                )
            }
        } catch (t: Throwable) {
            module.log(
                Log.ERROR, TAG,
                "Failed to inject ActivityLifecycleCallbacks: ${t.message}", t
            )
        }
        return result
    }

    private fun handleNewActivity(chain: XposedInterface.Chain): Any? {
        val clsName = chain.args.getOrNull(1) as? String ?: "?"
        val result = chain.proceed()
        OnceLogger.logOnce(
            module, TAG, "new_activity_$clsName",
            "Gojek newActivity -> $clsName"
        )
        return result
    }

    private class GojekActivityLifecycle(
        private val module: XposedModule
    ) : Application.ActivityLifecycleCallbacks {

        private val tag = GojekHookEntry.TAG

        private fun isGojekActivity(activity: Activity?): Boolean {
            val name = activity?.javaClass?.name ?: return false
            return name.startsWith(GOJEK_INTERNAL_PREFIX)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            if (!isGojekActivity(activity)) return

            try {
                LocationHook.refreshConfig(activity.applicationContext, force = false)
            } catch (_: Throwable) {}

            try {
                val cl = activity.javaClass.classLoader ?: activity.classLoader
                if (cl != null) {
                    // ⭐ PERBAIKAN: Ganti GojekOkHttpGuard dengan CancelCantikHook
                    try { 
                        CancelCantikHook.install(module, cl)
                        OnceLogger.logOnce(module, tag, "cancel_cantik_resumed",
                            "[CancelCantikHook] Retry from onActivityResumed")
                    } catch (_: Throwable) {}
                    
                    GojekHookEntry.initGojekPingHooks(module, cl)
                    GojekHookEntry.initGojekPingInjectorHooks(module, cl)
                    GojekHookEntry.initMockLocationBypassHooks(module, cl)
                    GojekHookEntry.initAntiXposedBypassHooks(module, cl)
                    GojekHookEntry.initAppsFlyerBypassHooks(module, cl)
                    GojekHookEntry.initZygotePreloadBypassHooks(module, cl)

                    try {
                        com.gpm.virtual.xposed.overlay.HookOverlayManager
                            .install(module, cl, activity.packageName)
                    } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                module.log(Log.WARN, tag, "Hooks deferred: ${t.message}")
            }

            OnceLogger.logOnce(
                module, tag, "resume_${activity.javaClass.name}",
                "Gojek Activity resumed -> ${activity.javaClass.name}"
            )
        }

        override fun onActivityPaused(activity: Activity) {
            if (!isGojekActivity(activity)) return
            OnceLogger.logOnce(
                module, tag, "pause_${activity.javaClass.name}",
                "Gojek Activity paused -> ${activity.javaClass.name}"
            )
        }

        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}