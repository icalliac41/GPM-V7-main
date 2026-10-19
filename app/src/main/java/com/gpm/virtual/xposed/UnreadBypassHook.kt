package com.gpm.virtual.xposed

import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule

/**
 * UnreadBypassHook — Mencegah Grab mendeteksi perilaku "unread" yang aneh.
 *
 * Rekomendasi dari log ALL-STAR v47.11.0:
 *   "Bypass unread M aktif"
 *   "Bypass unread R aktif"
 *   "Bypass unread X aktif"
 *
 * Tujuan: Saat Sortir aktif, counter unread di JobQueueManager selalu 0
 * agar server tidak curiga driver sengaja tidak membuka order.
 */
object UnreadBypassHook {

    private const val TAG = "GMP:UnreadBypass"

    fun install(module: XposedModule, cl: ClassLoader) {
        var count = 0

        // ═══ 1. Hook JobQueueManager: counter unread ═══
        try {
            val queueCls = ReflectUtil.findClassOrNull("com.grab.driver.job.ad.queue.JobQueueManager", cl)
            if (queueCls != null) {
                for (m in queueCls.declaredMethods) {
                    val nameLower = m.name.lowercase()
                    val isUnreadMethod = nameLower.contains("unread") || nameLower.contains("count") ||
                                         nameLower.contains("badge") || nameLower.contains("pending")
                    val isNumericReturn = m.returnType == Integer.TYPE ||
                                          m.returnType == Integer::class.java ||
                                          m.returnType == Long::class.javaPrimitiveType ||
                                          m.returnType == Long::class.java
                    if (!isUnreadMethod || !isNumericReturn) continue
                    if (m.parameterCount > 1) continue

                    m.isAccessible = true
                    LocationHook.hookMethod(m) { chain ->
                        if (SortirConfig.isEnabled()) {
                            return@hookMethod if (m.returnType == Long::class.javaPrimitiveType ||
                                                  m.returnType == Long::class.java) 0L else 0
                        }
                        chain.proceed()
                    }
                    count++
                    log(module, "✅ ${m.name}() di-bypass (return 0)")
                }
            }
        } catch (t: Throwable) {
            log(module, "JobQueueManager bypass warning: ${t.message}")
        }

        // ═══ 2. Hook JobAdViewModel: unread observed state ═══
        try {
            val aaVMCls = ReflectUtil.findClassOrNull("com.grab.driver.aa.ui.jobad.JobAdViewModel", cl)
            if (aaVMCls != null) {
                for (m in aaVMCls.declaredMethods) {
                    val nameLower = m.name.lowercase()
                    if (!nameLower.contains("unread")) continue
                    val isBooleanReturn = m.returnType == Boolean::class.javaPrimitiveType
                    val isIntReturn = m.returnType == Integer.TYPE
                    if (!isBooleanReturn && !isIntReturn) continue

                    m.isAccessible = true
                    LocationHook.hookMethod(m) { chain ->
                        if (SortirConfig.isEnabled()) {
                            return@hookMethod if (isBooleanReturn) false else 0
                        }
                        chain.proceed()
                    }
                    count++
                    log(module, "✅ JobAdViewModel.${m.name}() di-bypass")
                }
            }
        } catch (_: Throwable) {}

        // ═══ 3. Hook JobAdHandlerImpl: unread flag ═══
        try {
            val handlerCls = ReflectUtil.findClassOrNull("com.grab.driver.job.ad.ui.JobAdHandlerImpl", cl)
            if (handlerCls != null) {
                for (m in handlerCls.declaredMethods) {
                    val nameLower = m.name.lowercase()
                    if (!nameLower.contains("unread") && !nameLower.contains("badge")) continue
                    if (m.returnType != Boolean::class.javaPrimitiveType &&
                        m.returnType != Integer.TYPE) continue

                    m.isAccessible = true
                    LocationHook.hookMethod(m) { chain ->
                        if (SortirConfig.isEnabled()) {
                            return@hookMethod if (m.returnType == Boolean::class.javaPrimitiveType) false else 0
                        }
                        chain.proceed()
                    }
                    count++
                    log(module, "✅ JobAdHandlerImpl.${m.name}() di-bypass")
                }
            }
        } catch (_: Throwable) {}

        log(module, "🔌 UnreadBypass installed: $count methods")
    }

    private fun log(module: XposedModule, msg: String) {
        if (BuildConfig.DEBUG) {
            try { module.log(Log.INFO, TAG, msg) } catch (_: Throwable) {}
        }
    }
}