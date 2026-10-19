package com.gpm.virtual.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

object SpoofLifecycleManager {

    private const val TAG = "GPM:Lifecycle"

    private const val PREFS = "gpm_lifecycle_prefs"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SCOPE = "scope"
    private const val KEY_GOJEK_LAT = "gojek_lat"
    private const val KEY_GOJEK_LNG = "gojek_lng"
    private const val KEY_GRAB_LAT = "grab_lat"
    private const val KEY_GRAB_LNG = "grab_lng"
    private const val KEY_GEN = "generation"
    private const val KEY_LAST_CHANGE_MS = "last_change_ms"

    // ⭐ FIX #1: PUBLIC_MIRROR_PATH dihapus — tidak ada lagi file di /data/local/tmp/
    // ⭐ FIX #2: Internal permission untuk broadcast
    private const val INTERNAL_PERMISSION = "com.gpm.virtual.permission.INTERNAL"

    private val generation = AtomicInteger(0)
    @Volatile private var appContext: Context? = null

    @Volatile private var memoryActive: Boolean = false
    @Volatile private var memoryScope: String = "ALL"
    @Volatile private var memoryGojekLat: Double = 0.0
    @Volatile private var memoryGojekLng: Double = 0.0
    @Volatile private var memoryGrabLat: Double = 0.0
    @Volatile private var memoryGrabLng: Double = 0.0
    @Volatile private var memoryInitialized: Boolean = false

    private val listeners = mutableListOf<(State) -> Unit>()

    data class State(
        val active: Boolean, val scope: String,
        val gojekLat: Double, val gojekLng: Double,
        val grabLat: Double, val grabLng: Double,
        val generation: Int, val lastChangeMs: Long
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        loadFromPrefs()
        log("init: active=$memoryActive scope=$memoryScope gen=${generation.get()}")
        if (!memoryActive) enforceOffCleanup(context)
        // ⭐ FIX #1: writePublicMirror dihapus
    }

    fun initForHookProcess(context: Context) {
        appContext = context.applicationContext
        loadFromPrefs()
        log("initForHookProcess: active=$memoryActive scope=$memoryScope gen=${generation.get()}")
    }

    private fun loadFromPrefs() {
        val ctx = appContext ?: return
        try {
            val p = prefs(ctx)
            memoryActive = p.getBoolean(KEY_ACTIVE, false)
            memoryScope = p.getString(KEY_SCOPE, "ALL") ?: "ALL"
            memoryGojekLat = Double.fromBits(p.getLong(KEY_GOJEK_LAT, 0L))
            memoryGojekLng = Double.fromBits(p.getLong(KEY_GOJEK_LNG, 0L))
            memoryGrabLat = Double.fromBits(p.getLong(KEY_GRAB_LAT, 0L))
            memoryGrabLng = Double.fromBits(p.getLong(KEY_GRAB_LNG, 0L))
            generation.set(p.getInt(KEY_GEN, 0))
            memoryInitialized = true
        } catch (t: Throwable) {
            log("loadFromPrefs error: ${t.message}")
        }
    }

    fun activate(
        context: Context, scope: String,
        gojekLat: Double, gojekLng: Double,
        grabLat: Double, grabLng: Double
    ) {
        if (appContext == null) appContext = context.applicationContext
        val newGen = generation.incrementAndGet()

        memoryActive = true
        memoryScope = scope.uppercase().let {
            if (it in listOf("GOJEK", "GRAB", "ALL")) it else "ALL"
        }
        memoryGojekLat = gojekLat
        memoryGojekLng = gojekLng
        memoryGrabLat = grabLat
        memoryGrabLng = grabLng
        memoryInitialized = true
        val now = System.currentTimeMillis()

        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SCOPE, memoryScope)
            .putLong(KEY_GOJEK_LAT, java.lang.Double.doubleToLongBits(gojekLat))
            .putLong(KEY_GOJEK_LNG, java.lang.Double.doubleToLongBits(gojekLng))
            .putLong(KEY_GRAB_LAT, java.lang.Double.doubleToLongBits(grabLat))
            .putLong(KEY_GRAB_LNG, java.lang.Double.doubleToLongBits(grabLng))
            .putInt(KEY_GEN, newGen)
            .putLong(KEY_LAST_CHANGE_MS, now)
            .commit()

        setLegacyActiveFlags(context, true, memoryScope, gojekLat, gojekLng, grabLat, grabLng)
        // ⭐ FIX #1: writePublicMirror dihapus
        log("activate: scope=$memoryScope gen=$newGen")
        dispatchActivate(context)
        notifyListeners()
    }

    fun deactivate(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        val newGen = generation.incrementAndGet()
        memoryActive = false
        val now = System.currentTimeMillis()

        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_SCOPE)
            .remove(KEY_GOJEK_LAT).remove(KEY_GOJEK_LNG)
            .remove(KEY_GRAB_LAT).remove(KEY_GRAB_LNG)
            .putInt(KEY_GEN, newGen)
            .putLong(KEY_LAST_CHANGE_MS, now)
            .commit()

        // ⭐ FIX #1: writePublicMirror dihapus
        log("deactivate: gen=$newGen")
        enforceOffCleanup(context)
        dispatchDeactivate(context)
        notifyListeners()
    }

    // ⭐ FIX #1: Method writePublicMirror() DIHAPUS SELURUHNYA

    fun isActive(): Boolean {
        val ctx = appContext
        if (ctx != null) {
            return try { prefs(ctx).getBoolean(KEY_ACTIVE, memoryActive) }
            catch (_: Throwable) { memoryActive }
        }
        return memoryActive
    }

    fun getScope(): String {
        val ctx = appContext
        if (ctx != null) {
            return try { prefs(ctx).getString(KEY_SCOPE, "ALL") ?: "ALL" }
            catch (_: Throwable) { memoryScope }
        }
        return memoryScope
    }

    fun getGeneration(): Int {
        val ctx = appContext
        if (ctx != null) {
            return try { prefs(ctx).getInt(KEY_GEN, 0) }
            catch (_: Throwable) { generation.get() }
        }
        return generation.get()
    }

    fun getState(context: Context): State {
        val p = prefs(context)
        return State(
            active = p.getBoolean(KEY_ACTIVE, false),
            scope = p.getString(KEY_SCOPE, "ALL") ?: "ALL",
            gojekLat = Double.fromBits(p.getLong(KEY_GOJEK_LAT, 0L)),
            gojekLng = Double.fromBits(p.getLong(KEY_GOJEK_LNG, 0L)),
            grabLat = Double.fromBits(p.getLong(KEY_GRAB_LAT, 0L)),
            grabLng = Double.fromBits(p.getLong(KEY_GRAB_LNG, 0L)),
            generation = p.getInt(KEY_GEN, 0),
            lastChangeMs = p.getLong(KEY_LAST_CHANGE_MS, 0L)
        )
    }

    fun registerListener(l: (State) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unregisterListener(l: (State) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun dispatchActivate(context: Context) {
        val st = getState(context)
        if (st.scope == "GOJEK" || st.scope == "ALL") {
            safeBroadcast(context, "com.gojek.partner", "com.gpm.virtual.ACTION_SPOOF_GOJEK") {
                putExtra("active", true); putExtra("override", true)
                putExtra("latitude", st.gojekLat); putExtra("longitude", st.gojekLng)
                putExtra("trigger", "lifecycle_activate")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
        }
        if (st.scope == "GRAB" || st.scope == "ALL") {
            for (pkg in listOf("com.grabtaxi.driver2", "com.grab.driver")) {
                safeBroadcast(context, pkg, "com.gpm.virtual.ACTION_SPOOF_GRAB") {
                    putExtra("active", true); putExtra("override", true)
                    putExtra("latitude", st.grabLat); putExtra("longitude", st.grabLng)
                    putExtra("trigger", "lifecycle_activate")
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
            }
        }
    }

    private fun dispatchDeactivate(context: Context) {
        safeBroadcast(context, "com.gojek.partner", "com.gpm.virtual.ACTION_SPOOF_GOJEK") {
            putExtra("active", false); putExtra("override", false)
            putExtra("force_off", true); putExtra("trigger", "lifecycle_deactivate")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        for (pkg in listOf("com.grabtaxi.driver2", "com.grab.driver")) {
            safeBroadcast(context, pkg, "com.gpm.virtual.ACTION_SPOOF_GRAB") {
                putExtra("active", false); putExtra("override", false)
                putExtra("force_off", true); putExtra("trigger", "lifecycle_deactivate")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
        }
        safeBroadcast(context, "com.gojek.partner", "com.gpm.virtual.HARD_SHUTDOWN") {
            putExtra("trigger", "lifecycle_deactivate")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        for (pkg in listOf("com.grabtaxi.driver2", "com.grab.driver")) {
            safeBroadcast(context, pkg, "com.gpm.virtual.HARD_SHUTDOWN") {
                putExtra("trigger", "lifecycle_deactivate")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
        }
    }

    private fun setLegacyActiveFlags(
        context: Context, active: Boolean, scope: String,
        gojekLat: Double, gojekLng: Double, grabLat: Double, grabLng: Double
    ) {
        try {
            val lat = if (scope == "GRAB") grabLat else gojekLat
            val lng = if (scope == "GRAB") grabLng else gojekLng

            context.getSharedPreferences("gpm_virtual_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_hook_enabled", true)
                .putBoolean("is_spoof_active", active)
                .apply {
                    if (active) {
                        putLong("latitude", java.lang.Double.doubleToLongBits(lat))
                        putLong("longitude", java.lang.Double.doubleToLongBits(lng))
                        putString("latitude_str", lat.toString())
                        putString("longitude_str", lng.toString())
                    } else {
                        remove("latitude"); remove("longitude")
                        remove("latitude_str"); remove("longitude_str")
                    }
                }.commit()

            context.getSharedPreferences("gpm_injection_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("gojek_spoof_active", active && (scope == "GOJEK" || scope == "ALL"))
                .putBoolean("grab_spoof_active", active && (scope == "GRAB" || scope == "ALL"))
                .putBoolean("gojek_has_target", active && (scope == "GOJEK" || scope == "ALL"))
                .putBoolean("grab_has_target", active && (scope == "GRAB" || scope == "ALL"))
                .putString("target_scope", scope)
                .apply {
                    if (active && (scope == "GOJEK" || scope == "ALL")) {
                        putLong("gojek_lat", java.lang.Double.doubleToLongBits(gojekLat))
                        putLong("gojek_lng", java.lang.Double.doubleToLongBits(gojekLng))
                    } else { remove("gojek_lat"); remove("gojek_lng") }
                    if (active && (scope == "GRAB" || scope == "ALL")) {
                        putLong("grab_lat", java.lang.Double.doubleToLongBits(grabLat))
                        putLong("grab_lng", java.lang.Double.doubleToLongBits(grabLng))
                    } else { remove("grab_lat"); remove("grab_lng") }
                }.commit()
        } catch (t: Throwable) {
            log("setLegacyActiveFlags error: ${t.message}")
        }
    }

    // ⭐ Cleanup runtime saja, JANGAN sentuh VT toggles
    private fun enforceOffCleanup(context: Context) {
        try {
            context.getSharedPreferences("gpm_virtual_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_spoof_active", false)
                .remove("latitude").remove("longitude")
                .remove("latitude_str").remove("longitude_str")
                .commit()

            context.getSharedPreferences("gpm_injection_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("gojek_spoof_active", false)
                .putBoolean("grab_spoof_active", false)
                .putBoolean("gojek_has_target", false)
                .putBoolean("grab_has_target", false)
                .remove("gojek_lat").remove("gojek_lng")
                .remove("grab_lat").remove("grab_lng")
                .commit()

            val filesDir = context.filesDir
            if (filesDir != null) {
                for (f in listOf("gpm_config.json", "spoof_state.json", ".gpm_state")) {
                    try {
                        val file = java.io.File(filesDir, f)
                        if (file.exists()) {
                            val json = try { org.json.JSONObject(file.readText()) }
                                       catch (_: Throwable) { org.json.JSONObject() }
                            json.put("is_hook_enabled", true)
                            json.put("is_spoof_active", false)
                            if (!json.has("use_virtual")) json.put("use_virtual", false)
                            if (!json.has("use_virtual_racing")) json.put("use_virtual_racing", false)
                            if (!json.has("use_random")) json.put("use_random", false)
                            file.writeText(json.toString())
                        }
                    } catch (_: Throwable) {}
                }
            }
            log("enforceOffCleanup: runtime spoof state direset (VT toggles preserved)")
        } catch (t: Throwable) {
            log("enforceOffCleanup error: ${t.message}")
        }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ⭐ FIX #2: Kirim broadcast dengan signature permission
    private inline fun safeBroadcast(
        ctx: Context, pkg: String, action: String,
        fill: Intent.() -> Unit = {}
    ) {
        try {
            val intent = Intent(action).apply { setPackage(pkg); fill() }
            ctx.sendBroadcast(intent, INTERNAL_PERMISSION)
        } catch (t: Throwable) {
            log("safeBroadcast failed action=$action pkg=$pkg: ${t.message}")
        }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        val st = State(
            active = memoryActive, scope = memoryScope,
            gojekLat = memoryGojekLat, gojekLng = memoryGojekLng,
            grabLat = memoryGrabLat, grabLng = memoryGrabLng,
            generation = generation.get(), lastChangeMs = System.currentTimeMillis()
        )
        for (l in snapshot) { try { l(st) } catch (_: Throwable) {} }
    }

    private fun log(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
    }
}