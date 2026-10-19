package com.gpm.virtual.xposed

object ShadowModeHelper {

    private const val RX2_COMPLETABLE = "io.reactivex.Completable"
    private const val RX2_SINGLE      = "io.reactivex.Single"
    private const val RX3_COMPLETABLE = "io.reactivex.rxjava3.core.Completable"
    private const val RX3_SINGLE      = "io.reactivex.rxjava3.core.Single"

    fun emptyCompletable(classLoader: ClassLoader): Any? {
        tryBuild(classLoader, RX2_COMPLETABLE)?.let { return it }
        return tryBuild(classLoader, RX3_COMPLETABLE)
    }

    fun singleJustTrue(classLoader: ClassLoader): Any? {
        tryBuild(classLoader, RX2_SINGLE)?.let { return it }
        return tryBuild(classLoader, RX3_SINGLE)
    }

    fun isCompletable(value: Any?, classLoader: ClassLoader): Boolean {
        if (value == null) return false
        for (name in listOf(RX2_COMPLETABLE, RX3_COMPLETABLE)) {
            try {
                if (Class.forName(name, false, classLoader).isInstance(value)) return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun tryBuild(classLoader: ClassLoader, className: String): Any? = try {
        val cls = Class.forName(className, false, classLoader)
        if (className.endsWith("Completable")) {
            cls.getMethod("complete").invoke(null)
        } else {
            cls.getMethod("just", Any::class.java).invoke(null, true as Any)
        }
    } catch (_: Throwable) { null }
}