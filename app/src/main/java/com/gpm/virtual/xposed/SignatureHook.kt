package com.gpm.virtual.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * SignatureHook v3 — Runtime discovery mode + release-safe.
 *
 * ⭐ FIX #3: DISCOVERY_MODE bukan lagi const. Hanya aktif kalau:
 *   1. BuildConfig.DEBUG == true, DAN
 *   2. System property "gpm.signature.discovery" == "true"
 *
 * Untuk mengaktifkan saat testing:
 *   adb shell setprop gpm.signature.discovery true
 *
 * Cara pakai discovery:
 *  1. Set property di atas.
 *  2. Build + install modul (debug).
 *  3. Buka Gojek, trigger endpoint cancel reasons.
 *  4. Filter logcat "GPM:Signature".
 *  5. Set property "false" atau reboot untuk kembali pasif.
 */
object SignatureHook {

    private const val TAG = "GPM:Signature"

    // ⭐ FIX #3: Bukan const lagi
    private const val DISCOVERY_PROPERTY = "gpm.signature.discovery"

    private val SIGNATURE_HEADER_HINTS = listOf(
        "X-E1", "X-E2", "X-E3",
        "x-e1", "x-e2", "x-e3",
        "Signature", "signature",
        "X-Gojek", "x-gojek",
        "X-Request-Sign", "X-Signature",
        "X-Auth", "x-auth"
    )

    @Volatile private var installed = false
    @Volatile private var regenerationEnabled = false
    @Volatile private var discoveryMode: Boolean = false

    private fun initDiscoveryMode() {
        discoveryMode = try {
            BuildConfigCompat.isDebug() &&
            "true".equals(
                System.getProperty(DISCOVERY_PROPERTY),
                ignoreCase = true
            )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Entry point dari ModernModule.
     */
    fun install(module: XposedModule, classLoader: ClassLoader): Boolean {
        if (installed) return true

        initDiscoveryMode()

        return if (discoveryMode) {
            installDiscovery(module, classLoader)
        } else {
            regenerationEnabled = true
            if (BuildConfigCompat.isDebug()) {
                try {
                    module.log(Log.INFO, TAG,
                        "SignatureHook: passive mode (discovery=$discoveryMode)")
                } catch (_: Throwable) {}
            }
            true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DISCOVERY — hanya aktif kalau debug + property
    // ═══════════════════════════════════════════════════════════════
    private fun installDiscovery(
        module: XposedModule,
        classLoader: ClassLoader
    ): Boolean {
        // ⭐ FIX #3: Double guard — tolak kalau bukan debug
        if (!BuildConfigCompat.isDebug()) {
            try {
                module.log(Log.WARN, TAG,
                    "Discovery diblokir di release build")
            } catch (_: Throwable) {}
            return false
        }

        val builderCls = try {
            Class.forName("okhttp3.Request\$Builder", false, classLoader)
        } catch (_: Throwable) {
            try { module.log(Log.WARN, TAG, "Request\$Builder not found") }
            catch (_: Throwable) {}
            return false
        }

        val headerMethod: Method? = try {
            builderCls.getMethod("header", String::class.java, String::class.java)
        } catch (_: Throwable) { null }

        val addHeaderMethod: Method? = try {
            builderCls.getMethod("addHeader", String::class.java, String::class.java)
        } catch (_: Throwable) { null }

        var hooked = 0

        headerMethod?.let {
            it.isAccessible = true
            LocationHook.hookMethod(it) { chain ->
                val name = chain.getArg(0) as? String
                val value = chain.getArg(1) as? String
                if (name != null && isSignatureHeader(name)) {
                    safeLogSignature(module, "header()", name, value)
                }
                chain.proceed()
            }
            hooked++
        }

        addHeaderMethod?.let {
            it.isAccessible = true
            LocationHook.hookMethod(it) { chain ->
                val name = chain.getArg(0) as? String
                val value = chain.getArg(1) as? String
                if (name != null && isSignatureHeader(name)) {
                    safeLogSignature(module, "addHeader()", name, value)
                }
                chain.proceed()
            }
            hooked++
        }

        try {
            val headersBuilderCls = Class.forName(
                "okhttp3.Headers\$Builder", false, classLoader
            )
            val headersAdd = headersBuilderCls.methods.firstOrNull {
                (it.name == "add" || it.name == "set") &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
            headersAdd?.let {
                it.isAccessible = true
                LocationHook.hookMethod(it) { chain ->
                    val name = chain.getArg(0) as? String
                    val value = chain.getArg(1) as? String
                    if (name != null && isSignatureHeader(name)) {
                        safeLogSignature(module, "Headers.Builder.${it.name}()", name, value)
                    }
                    chain.proceed()
                }
                hooked++
            }
        } catch (_: Throwable) {}

        installed = hooked > 0
        try {
            module.log(Log.INFO, TAG,
                "SignatureHook discovery: $hooked hook terpasang")
        } catch (_: Throwable) {}

        return installed
    }

    private fun isSignatureHeader(name: String): Boolean {
        return SIGNATURE_HEADER_HINTS.any { hint ->
            name.contains(hint, ignoreCase = true)
        }
    }

    // ⭐ FIX #3: Guard BuildConfig.DEBUG + property
    private fun safeLogSignature(
        module: XposedModule,
        via: String,
        name: String,
        value: String?
    ) {
        if (!BuildConfigCompat.isDebug()) return
        if (!discoveryMode) return

        try {
            module.log(Log.INFO, TAG, "[SIGN] via=$via name=$name")
            module.log(Log.INFO, TAG, "[SIGN] value=${value ?: "null"}")
            val stack = Throwable("signature-set")
                .stackTrace
                .take(12)
                .joinToString("\n") { "    at $it" }
            module.log(Log.INFO, TAG, "[SIGN] caller:\n$stack")
        } catch (_: Throwable) {}
    }

    // ═══════════════════════════════════════════════════════════════
    //  REGENERATION — diisi setelah RE berhasil
    // ═══════════════════════════════════════════════════════════════

    fun regenerate(
        body: String,
        timestamp: Long,
        method: String = "POST",
        path: String = "/"
    ): String? {
        // TODO: implementasi setelah RE selesai.
        return null
    }

    fun needsRegenerationForRequest(): Boolean = false
}

/**
 * Kompat BuildConfig tanpa import khusus.
 */
private object BuildConfigCompat {
    fun isDebug(): Boolean = try {
        com.gpm.virtual.BuildConfig.DEBUG
    } catch (_: Throwable) { false }
}