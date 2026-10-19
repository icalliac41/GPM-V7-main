package com.gpm.virtual.xposed

import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule

object TurboDisabler {
    private const val TAG = "GMP:TurboDisabler"

    fun install(module: XposedModule, cl: ClassLoader) {
        try {
            val jobAdsCls = ReflectUtil.findClassOrNull("com.grab.driver.job.ad.service.JobAdsServiceImpl", cl)
            if (jobAdsCls != null) {
                val cMethod = ReflectUtil.findMethodExactOrNull(jobAdsCls, "c", String::class.java, String::class.java)
                if (cMethod != null) {
                    LocationHook.hookMethod(cMethod) { chain ->
                        if (SortirConfig.isEnabled()) {
                            log(module, "🛑 [ANTI-AA] Blocked SYSTEM_ACCEPT")
                            return@hookMethod ShadowModeHelper.emptyCompletable(cl)
                        }
                        chain.proceed()
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            val aaJobServiceCls = ReflectUtil.findClassOrNull("com.grab.driver.aa.service.AAJobService", cl)
            val baseJobCls = ReflectUtil.findClassOrNull("com.grab.driver.job.model.BaseJob", cl)
            if (aaJobServiceCls != null && baseJobCls != null) {
                val xMethod = ReflectUtil.findMethodExactOrNull(aaJobServiceCls, "x", aaJobServiceCls, baseJobCls)
                if (xMethod != null) {
                    LocationHook.hookMethod(xMethod) { chain ->
                        if (SortirConfig.isEnabled()) {
                            log(module, "🛑 [ANTI-AA] Blocked AAJobService.x")
                            return@hookMethod ShadowModeHelper.emptyCompletable(cl)
                        }
                        chain.proceed()
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun log(module: XposedModule, msg: String) {
        if (BuildConfig.DEBUG) { try { module.log(Log.INFO, TAG, msg) } catch (_: Throwable) {} }
    }
}