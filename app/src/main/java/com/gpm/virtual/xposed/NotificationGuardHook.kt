package com.gpm.virtual.xposed

import android.app.Notification
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule

/**
 * NotificationGuardHook — Mencegah deteksi spoofing lewat notifikasi sistem.
 *
 * Menyembunyikan:
 *  1. Notifikasi "Mock location app set" dari getActiveNotifications()
 *  2. Status Developer Options (Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
 *  3. Status ADB/USB Debugging (Settings.Global.ADB_ENABLED)
 *  4. Notification Listener Services (ENABLED_NOTIFICATION_LISTENERS)
 *  5. Accessibility Services (ENABLED_ACCESSIBILITY_SERVICES)
 *  6. Mock Location AppOps (OP_MOCK_LOCATION = 58)
 *  7. Notifikasi dari GPM sendiri (persistent foreground notification)
 *
 *  PRINSIP: Hanya aktif di target app (Gojek/Grab/Maps) saat spoofing ON.
 */
object NotificationGuardHook {

    private const val TAG = "GMP:NotifGuard"

    // Keyword notifikasi yang harus disembunyikan
    private val BLOCKED_NOTIF_KEYWORDS = listOf(
        "mock location", "location spoof", "fake gps",
        "palsu", "pemalsuan", "lokasi palsu", "gps palsu",
        "developer options", "usb debugging", "adb enabled",
        "running in background", "berjalan di latar belakang"
    )

    // Package sistem yang notifikasinya perlu difilter
    private val BLOCKED_NOTIF_PACKAGES = setOf(
        "android", "com.android.systemui"
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!isTargetApp()) return

        var ok = 0
        var total = 0

        total++; if (safe(module, "notif-active") { hookGetActiveNotifications(module, classLoader) }) ok++
        total++; if (safe(module, "settings-global") { hookSettingsGlobal(module, classLoader) }) ok++
        total++; if (safe(module, "settings-secure") { hookSettingsSecure(module, classLoader) }) ok++
        total++; if (safe(module, "appops-mock") { hookAppOpsMockLocation(module, classLoader) }) ok++

        if (BuildConfig.DEBUG) {
            try { module.log(Log.INFO, TAG, "NotificationGuard: $ok/$total layers active") } catch (_: Throwable) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAYER 1: Filter NotificationManager.getActiveNotifications()
    // ═══════════════════════════════════════════════════════════════
    private fun hookGetActiveNotifications(module: XposedModule, cl: ClassLoader): Boolean {
        val nmCls = try {
            Class.forName("android.app.NotificationManager", false, cl)
        } catch (_: Throwable) { return false }

        var hooked = false

        // getActiveNotifications() -> StatusBarNotification[]
        try {
            val m = nmCls.getMethod("getActiveNotifications")
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                val result = chain.proceed()
                if (!LocationHook.isHookActive) return@hookMethod result
                if (!isTargetApp()) return@hookMethod result
                filterStatusBarNotifications(result)
            }
            hooked = true
        } catch (_: Throwable) {}

        // getActiveNotifications(String[] keys) -> StatusBarNotification[]
        try {
            val m = nmCls.getMethod("getActiveNotifications", Array<String>::class.java)
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                val result = chain.proceed()
                if (!LocationHook.isHookActive) return@hookMethod result
                if (!isTargetApp()) return@hookMethod result
                filterStatusBarNotifications(result)
            }
            hooked = true
        } catch (_: Throwable) {}

        return hooked
    }

    private fun filterStatusBarNotifications(original: Any?): Any? {
        if (original !is Array<*>) return original
        val nonNull = original.filterNotNull()
        if (nonNull.isEmpty()) return original

        val filtered = nonNull.filter { !isDangerousNotification(it) }
        if (filtered.size == nonNull.size) return original

        return try {
            val componentType = original.javaClass.componentType ?: return original
            val newArray = java.lang.reflect.Array.newInstance(componentType, filtered.size)
            for (i in filtered.indices) {
                java.lang.reflect.Array.set(newArray, i, filtered[i])
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Filtered ${nonNull.size - filtered.size} dangerous notification(s)")
            }
            newArray
        } catch (_: Throwable) { original }
    }

    private fun isDangerousNotification(sbn: Any): Boolean {
        return try {
            val pkg = try {
                sbn.javaClass.getMethod("getPackageName").invoke(sbn) as? String
            } catch (_: Throwable) { null }

            if (pkg == null) return false

            // Filter semua notifikasi dari GPM sendiri
            if (pkg.startsWith("com.gpm.virtual")) {
                return true
            }

            // Filter notifikasi sistem yang bocorkan mock location
            if (BLOCKED_NOTIF_PACKAGES.contains(pkg)) {
                val notif = try {
                    sbn.javaClass.getMethod("getNotification").invoke(sbn) as? Notification
                } catch (_: Throwable) { null }

                if (notif != null && isMockOrSpoofNotification(notif)) {
                    return true
                }
            }

            false
        } catch (_: Throwable) { false }
    }

    private fun isMockOrSpoofNotification(notif: Notification): Boolean {
        return try {
            // Cek title/text/subtext
            val extras = notif.extras
            if (extras != null) {
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase() ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lowercase() ?: ""
                val subtext = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.lowercase() ?: ""
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.lowercase() ?: ""
                val combined = "$title $text $subtext $bigText"

                if (BLOCKED_NOTIF_KEYWORDS.any { combined.contains(it) }) return true
            }

            // Cek ticker text
            val ticker = notif.tickerText?.toString()?.lowercase() ?: ""
            if (BLOCKED_NOTIF_KEYWORDS.any { ticker.contains(it) }) return true

            // Cek channel ID (Android O+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelId = notif.channelId?.lowercase() ?: ""
                if (channelId.contains("mock") ||
                    channelId.contains("spoof") ||
                    channelId.contains("location") ||
                    channelId.contains("fake")) {
                    return true
                }
            }

            false
        } catch (_: Throwable) { false }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAYER 2: Hook Settings.Global
    // ═══════════════════════════════════════════════════════════════
    private fun hookSettingsGlobal(module: XposedModule, cl: ClassLoader): Boolean {
        val globalCls = try {
            Class.forName("android.provider.Settings\$Global", false, cl)
        } catch (_: Throwable) { return false }

        val crCls = try {
            Class.forName("android.content.ContentResolver", false, cl)
        } catch (_: Throwable) { return false }

        var hooked = false

        // getInt(ContentResolver, String, int)
        try {
            val m = globalCls.getMethod(
                "getInt", crCls, String::class.java, Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!LocationHook.isHookActive) return@hookMethod chain.proceed()
                if (!isTargetApp()) return@hookMethod chain.proceed()

                val key = chain.getArg(1) as? String
                if (key != null && isDangerousGlobalKey(key)) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Blocked Settings.Global.getInt($key) → 0")
                    }
                    return@hookMethod 0
                }
                chain.proceed()
            }
            hooked = true
        } catch (_: Throwable) {}

        // getString(ContentResolver, String)
        try {
            val m = globalCls.getMethod("getString", crCls, String::class.java)
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!LocationHook.isHookActive) return@hookMethod chain.proceed()
                if (!isTargetApp()) return@hookMethod chain.proceed()

                val key = chain.getArg(1) as? String
                if (key != null && isDangerousGlobalKey(key)) {
                    return@hookMethod "0"
                }
                chain.proceed()
            }
            hooked = true
        } catch (_: Throwable) {}

        return hooked
    }

    private fun isDangerousGlobalKey(key: String): Boolean {
        return when (key) {
            "adb_enabled",
            "development_settings_enabled",
            "device_provisioned", // ← beberapa app deteksi ini (0 = belum setup, aneh)
            "stay_on_while_plugged_in" -> key != "device_provisioned" && key != "stay_on_while_plugged_in"
            Settings.Global.ADB_ENABLED,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED -> true
            else -> false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAYER 3: Hook Settings.Secure
    //  (Notification Listeners, Accessibility, Mock Location)
    // ═══════════════════════════════════════════════════════════════
    private fun hookSettingsSecure(module: XposedModule, cl: ClassLoader): Boolean {
        val secureCls = try {
            Class.forName("android.provider.Settings\$Secure", false, cl)
        } catch (_: Throwable) { return false }

        val crCls = try {
            Class.forName("android.content.ContentResolver", false, cl)
        } catch (_: Throwable) { return false }

        var hooked = false

        // getString(ContentResolver, String)
        try {
            val m = secureCls.getMethod("getString", crCls, String::class.java)
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!LocationHook.isHookActive) return@hookMethod chain.proceed()
                if (!isTargetApp()) return@hookMethod chain.proceed()

                val key = chain.getArg(1) as? String
                if (key != null) {
                    when (key) {
                        "enabled_notification_listeners",
                        "enabled_accessibility_services",
                        "enabled_notification_policy_access_packages",
                        "enabled_vr_listeners" -> {
                            val original = chain.proceed() as? String ?: return@hookMethod null
                            return@hookMethod filterServiceList(original)
                        }
                        "mock_location" -> return@hookMethod "0"
                    }
                }
                chain.proceed()
            }
            hooked = true
        } catch (_: Throwable) {}

        // getInt(ContentResolver, String, int)
        try {
            val m = secureCls.getMethod(
                "getInt", crCls, String::class.java, Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            LocationHook.hookMethod(m) { chain ->
                if (!LocationHook.isHookActive) return@hookMethod chain.proceed()
                if (!isTargetApp()) return@hookMethod chain.proceed()

                val key = chain.getArg(1) as? String
                if (key == "mock_location") return@hookMethod 0
                chain.proceed()
            }
            hooked = true
        } catch (_: Throwable) {}

        return hooked
    }

    private fun filterServiceList(original: String): String {
        if (original.isEmpty()) return original
        return try {
            val parts = original.split(":")
            val filtered = parts.filter { service ->
                !service.contains("gpm", ignoreCase = true) &&
                !service.contains("virtual", ignoreCase = true) &&
                !service.contains("spoof", ignoreCase = true) &&
                !service.contains("fake", ignoreCase = true)
            }
            val result = filtered.joinToString(":")
            if (BuildConfig.DEBUG && filtered.size != parts.size) {
                Log.d(TAG, "Filtered service list: ${parts.size} → ${filtered.size}")
            }
            result
        } catch (_: Throwable) { original }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAYER 4: Hook AppOpsManager (Mock Location op = 58)
    // ═══════════════════════════════════════════════════════════════
    private fun hookAppOpsMockLocation(module: XposedModule, cl: ClassLoader): Boolean {
        val aomCls = try {
            Class.forName("android.app.AppOpsManager", false, cl)
        } catch (_: Throwable) { return false }

        var hooked = false

        for (methodName in arrayOf("checkOp", "checkOpNoThrow", "noteOp", "noteOpNoThrow")) {
            for (m in aomCls.methods.filter { it.name == methodName }) {
                if (m.parameterCount < 1) continue
                m.isAccessible = true
                LocationHook.hookMethod(m) { chain ->
                    if (!LocationHook.isHookActive) return@hookMethod chain.proceed()
                    if (!isTargetApp()) return@hookMethod chain.proceed()

                    val op = chain.getArg(0)
                    val isMockOp = (op == 58) || (op == "android:mock_location")
                    if (isMockOp) {
                        // MODE_ALLOWED = 0, AOP_MODE_ALLOWED (untuk noteOp) = 0
                        return@hookMethod 0
                    }
                    chain.proceed()
                }
                hooked = true
            }
        }

        return hooked
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helper
    // ═══════════════════════════════════════════════════════════════
    private fun isTargetApp(): Boolean {
        val pkg = LocationHook.getCurrentPackageName()
        return pkg == "com.gojek.partner" ||
               pkg == "com.grabtaxi.driver2" ||
               pkg == "com.grab.driver" ||
               pkg == "com.google.android.apps.maps" ||
               GrabHookEntry.isTargetPackage(pkg)
    }

    private inline fun safe(module: XposedModule, label: String, block: () -> Boolean): Boolean =
        try { block() } catch (t: Throwable) {
            if (BuildConfig.DEBUG) {
                try { module.log(Log.WARN, TAG, "[$label] ${t.message}") } catch (_: Throwable) {}
            }
            false
        }
}