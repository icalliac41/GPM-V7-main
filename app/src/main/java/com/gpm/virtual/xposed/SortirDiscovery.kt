package com.gpm.virtual.xposed

import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule

/**
 * Dump class & method Grab untuk discovery mode.
 * Mirip `[DUMP]` di log ALL-STAR. Aktif hanya di debug build.
 *
 * Kalau Grab update: aktifkan `dumpAll()` di SortirHook.install,
 * buka logcat filter "GMP:Sortir:DUMP", lalu update VersionMapper.
 */
object SortirDiscovery {

    private const val TAG = "GMP:Sortir:DUMP"

    fun dumpAll(module: XposedModule, cl: ClassLoader) {
        if (!BuildConfig.DEBUG) return
        val map = SortirVersionMapper.detect(cl) ?: SortirVersionMapper.default()

        val targets = listOf(
            "surfaceChecker"     to map.surfaceChecker,
            "jobAdHandler"       to map.jobAdHandler,
            "jobAdViewModel"     to map.jobAdViewModel,
            "jobAdsImpl"         to map.jobAdsImpl,
            "aaJobService"       to map.aaJobService,
            "jobQueueManager"    to map.jobQueueManager,
            "jobCardRepo"        to map.jobCardRepo,
            "jobCardV2ViewModel" to map.jobCardV2ViewModel,
            "dispatcherV2"       to map.dispatcherV2,
            "dispatcherProvider" to map.dispatcherProvider,
            "bottomSheetUsecase" to map.bottomSheetUsecase,
        )

        for ((label, name) in targets) {
            val cls = ReflectUtil.findClassOrNull(name, cl)
            if (cls == null) {
                safeLog(module, Log.WARN, "MISSING [$label]: $name")
                continue
            }
            dumpClass(module, label, cls)
        }
    }

    private fun dumpClass(module: XposedModule, label: String, cls: Class<*>) {
        if (!BuildConfig.DEBUG) return
        try {
            val methods = cls.declaredMethods
            val sb = StringBuilder()
            sb.append("[DUMP] ===== $label :: ${cls.name} (${methods.size} method) =====\n")
            methods.sortedBy { it.name + it.parameterCount }.forEach { m ->
                sb.append("  ")
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)) sb.append("static ")
                sb.append(m.name)
                sb.append('(')
                sb.append(m.parameterTypes.joinToString { it.simpleName })
                sb.append(")->")
                sb.append(m.returnType.simpleName)
                sb.append('\n')
            }
            safeLog(module, Log.INFO, sb.toString())
        } catch (_: Throwable) {}
    }

    private fun safeLog(module: XposedModule, level: Int, msg: String) {
        try { module.log(level, TAG, msg) } catch (_: Throwable) {}
    }
}