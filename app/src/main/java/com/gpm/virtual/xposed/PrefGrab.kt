package com.gpm.virtual.xposed

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

class PrefGrab(
    private val module: XposedModule,
    private val mode: Int
) : XposedInterface.Hooker {

    companion object {
        const val MODE_APP_ONCREATE = 0
        const val MODE_NEW_ACTIVITY = 1

        private val TAG = GrabHookEntry.TAG
        private const val GRAB_ACTIVITY_PREFIX = "com.grab"

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
                app.registerActivityLifecycleCallbacks(GrabActivityLifecycle(module))
                callbacksRegistered = true
                OnceLogger.logOnce(module, TAG, "grab_app_oncreate_injected",
                    "Grab Application.onCreate -> LifecycleCallbacks injected")
            }
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "Failed to inject ActivityLifecycleCallbacks: ${t.message}", t)
        }
        return result
    }

    private fun handleNewActivity(chain: XposedInterface.Chain): Any? {
        val clsName = chain.args.getOrNull(1) as? String ?: "?"
        val result = chain.proceed()
        OnceLogger.logOnce(module, TAG, "grab_new_activity_$clsName", "Grab newActivity -> $clsName")
        return result
    }

    private class GrabActivityLifecycle(private val module: XposedModule) : Application.ActivityLifecycleCallbacks {

        private val tag = GrabHookEntry.TAG

        private fun isGrabActivity(activity: Activity?): Boolean {
            val name = activity?.javaClass?.name ?: return false
            return name.startsWith(GRAB_ACTIVITY_PREFIX)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            if (!isGrabActivity(activity)) return
            CurrentActivityHolder.set(activity)

            try { LocationHook.refreshConfig(activity.applicationContext, force = false) } catch (_: Throwable) {}

            try {
                val cl = activity.javaClass.classLoader ?: activity.classLoader
                if (cl != null) {
                    GrabHookEntry.initLatLongHooks(module, cl)
                    GrabHookEntry.initLatLngBuilderHooks(module, cl)
                    GrabHookEntry.initSortirHooks(module, cl)
                }
            } catch (t: Throwable) {
                module.log(Log.WARN, tag, "Grab model hooks deferred: ${t.message}")
            }

            OnceLogger.logOnce(module, tag, "grab_resume_${activity.javaClass.name}",
                "Grab Activity resumed -> ${activity.javaClass.name}")
        }

        override fun onActivityPaused(activity: Activity) {
            if (!isGrabActivity(activity)) return
            if (CurrentActivityHolder.get() === activity) {
                CurrentActivityHolder.clear()
            }
            OnceLogger.logOnce(module, tag, "grab_pause_${activity.javaClass.name}",
                "Grab Activity paused -> ${activity.javaClass.name}")
        }

        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}