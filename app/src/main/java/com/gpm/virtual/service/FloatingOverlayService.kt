package com.gpm.virtual.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gpm.virtual.GpmApplication
import com.gpm.virtual.MainActivity
import com.gpm.virtual.data.preferences.GpsPreferences
import com.gpm.virtual.ui.components.TravelMode
import com.gpm.virtual.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
private var overlayView: View? = null
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private lateinit var preferences: GpsPreferences
private lateinit var injectionPrefs: SharedPreferences

// [PATCH-FLO-1] Simpan lifecycle owner agar bisa di-drive ke
// ON_DESTROY saat service di-stop — mencegah leak listener
// SharedPreferences di DisposableEffect.
private var lifecycleOwner: FloatingLifecycleOwner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        preferences = (application as? GpmApplication)?.preferences
            ?: GpsPreferences.getInstance(this)
        injectionPrefs = getSharedPreferences("gpm_injection_prefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Settings.canDrawOverlays(this)) {
            showOverlay()
        } else {
            stopSelf()
        }
    }

    // ==================================================================
    // [FIX PER-APP] Broadcast helper dengan coords opsional
    // ==================================================================
    private fun sendSpoofBroadcast(
        pkg: String,
        active: Boolean,
        action: String,
        lat: Double? = null,
        lng: Double? = null
    ) {
        try {
            sendBroadcast(Intent(action).apply {
                setPackage(pkg)
                putExtra("active", active)
                if (active && lat != null && lng != null) {
                    putExtra("override", true)
                    putExtra("latitude", lat)
                    putExtra("longitude", lng)
                } else if (!active) {
                    putExtra("override", false)
                }
                putExtra("trigger", "gpm_overlay")
            })
        } catch (_: Throwable) {}
    }

    /**
     * [FIX PER-APP] Baca coords target per-app dari SharedPreferences
     * (yang disimpan oleh MainViewModel lewat setGojekTarget/setGrabTarget).
     * Return null bila belum pernah di-set.
     */
    private fun readPerAppTarget(pkg: String): Pair<Double, Double>? {
        val keyPrefix = when {
            pkg.startsWith("com.gojek") -> "gojek"
            pkg.startsWith("com.grab") -> "grab"
            else -> return null
        }
        val hasKey = "${keyPrefix}_has_target"
        if (!injectionPrefs.getBoolean(hasKey, false)) return null
        return try {
            val lat = Double.fromBits(injectionPrefs.getLong("${keyPrefix}_lat", 0L))
            val lng = Double.fromBits(injectionPrefs.getLong("${keyPrefix}_lng", 0L))
            if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0) null
            else lat to lng
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Toggle spoof mengikuti mode scope dari Settings.
     *
     *   - scope = "GOJEK" → toggle Gojek saja, kirim coords Gojek (kalau ada)
     *   - scope = "GRAB"  → toggle Grab saja, kirim coords Grab (kalau ada)
     *   - scope = "ALL"   → toggle keduanya, kirim coords masing-masing
     *
     * Global isSpoofActive = OR(state Gojek, state Grab), bukan langsung dari
     * `enable`, supaya kalau salah satu app masih ON global tetap ON.
     */
    private fun applySpoofByScope(enable: Boolean) {
        val scope = injectionPrefs.getString("target_scope", "ALL") ?: "ALL"

        when (scope) {
            "GOJEK" -> {
    // [PATCH-FLO-2] commit() sinkron — broadcast di bawah
    // membaca prefs ini, apply() async bisa race.
    injectionPrefs.edit().putBoolean("gojek_spoof_active", enable).commit()
                val coord = readPerAppTarget("com.gojek.partner")
                sendSpoofBroadcast(
                    pkg = "com.gojek.partner",
                    active = enable,
                    action = "com.gpm.virtual.ACTION_SPOOF_GOJEK",
                    lat = coord?.first,
                    lng = coord?.second
                )
            }
            "GRAB" -> {
    injectionPrefs.edit().putBoolean("grab_spoof_active", enable).commit()
                val coord = readPerAppTarget("com.grabtaxi.driver2")
                for (pkg in listOf("com.grabtaxi.driver2", "com.grab.driver")) {
                    sendSpoofBroadcast(
                        pkg = pkg,
                        active = enable,
                        action = "com.gpm.virtual.ACTION_SPOOF_GRAB",
                        lat = coord?.first,
                        lng = coord?.second
                    )
                }
            }
            else -> { // ALL
    injectionPrefs.edit()
        .putBoolean("gojek_spoof_active", enable)
        .putBoolean("grab_spoof_active", enable)
        .commit()
                val gojekCoord = readPerAppTarget("com.gojek.partner")
                val grabCoord = readPerAppTarget("com.grabtaxi.driver2")
                sendSpoofBroadcast(
                    pkg = "com.gojek.partner",
                    active = enable,
                    action = "com.gpm.virtual.ACTION_SPOOF_GOJEK",
                    lat = gojekCoord?.first,
                    lng = gojekCoord?.second
                )
                for (pkg in listOf("com.grabtaxi.driver2", "com.grab.driver")) {
                    sendSpoofBroadcast(
                        pkg = pkg,
                        active = enable,
                        action = "com.gpm.virtual.ACTION_SPOOF_GRAB",
                        lat = grabCoord?.first,
                        lng = grabCoord?.second
                    )
                }
            }
        }

        // Global state = OR dari kedua app (jangan asal `enable`)
        val anyActive = injectionPrefs.getBoolean("gojek_spoof_active", false) ||
                injectionPrefs.getBoolean("grab_spoof_active", false)
        preferences.updateConfig { it.copy(isSpoofActive = anyActive) }

        // Mock service untuk app non-LSPosed
        if (anyActive) {
            MockLocationService.start(this)
            MockLocationService.pushImmediate(this)
            MockLocationService.pingLocationToScope(this)
        } else {
            MockLocationService.stop(this)
            MockLocationService.pingLocationToScope(this)
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun showOverlay() {
        if (overlayView != null) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 24
            y = 200
        }

        val composeView = ComposeView(this).apply {
    // [PATCH-FLO-1] Simpan ke field agar bisa di-ON_DESTROY di onDestroy
    val owner = FloatingLifecycleOwner()
    lifecycleOwner = owner
    owner.performRestore(null)
    owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
    owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
    owner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

    setViewTreeLifecycleOwner(owner)
    setViewTreeViewModelStoreOwner(owner)
    setViewTreeSavedStateRegistryOwner(owner)

            setContent {
                MyApplicationTheme {
                    FloatingOverlayContent(
                        preferences = preferences,
                        injectionPrefs = injectionPrefs,
                        onDragDelta = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            try {
                                windowManager?.updateViewLayout(this, params)
                            } catch (_: Throwable) {}
                        },
                        onCloseOverlay = {
                            preferences.updateConfig { it.copy(isFloatingOverlayEnabled = false) }
                            stopSelf()
                        },
                        onOpenApp = {
                            val intent = Intent(
                                this@FloatingOverlayService,
                                MainActivity::class.java
                            ).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(intent)
                        },
                        onToggleHook = { enable ->
                            applySpoofByScope(enable)
                        },
                        onRefreshLocation = {
                            // [FIX PER-APP] Kirim ulang coords per-app sesuai scope,
                            // supaya kalau di hook side override hilang (app baru
                            // start / restart), di-set ulang.
                            val scope = injectionPrefs.getString("target_scope", "ALL") ?: "ALL"
                            val gojekOn = injectionPrefs.getBoolean("gojek_spoof_active", false)
                            val grabOn = injectionPrefs.getBoolean("grab_spoof_active", false)

                            if (scope == "GOJEK" || scope == "ALL") {
                                val coord = readPerAppTarget("com.gojek.partner")
                                sendSpoofBroadcast(
                                    "com.gojek.partner", gojekOn,
                                    "com.gpm.virtual.ACTION_SPOOF_GOJEK",
                                    coord?.first, coord?.second
                                )
                            }
                            if (scope == "GRAB" || scope == "ALL") {
                                val coord = readPerAppTarget("com.grabtaxi.driver2")
                                for (pkg in listOf("com.grabtaxi.driver2", "com.grab.driver")) {
                                    sendSpoofBroadcast(
                                        pkg, grabOn,
                                        "com.gpm.virtual.ACTION_SPOOF_GRAB",
                                        coord?.first, coord?.second
                                    )
                                }
                            }

                            MockLocationService.pushImmediate(this@FloatingOverlayService)
                            MockLocationService.pingLocationToScope(this@FloatingOverlayService)
                        },
                        onMove = { deltaLat, deltaLng ->
                            // [FIX PER-APP] Routing per scope:
                            //   GOJEK → tulis slot Gojek, kirim ke Gojek saja
                            //   GRAB  → tulis slot Grab,  kirim ke Grab saja
                            //   ALL   → update global config + ping semua
                            val scope = injectionPrefs.getString("target_scope", "ALL") ?: "ALL"

                            fun moveIn(baseLat: Double, baseLng: Double): Pair<Double, Double> {
                                val latRad = Math.toRadians(baseLat)
                                val metersPerDegreeLat =
                                    111132.954 - 559.822 * kotlin.math.cos(2 * latRad)
                                val metersPerDegreeLng = 111412.84 * kotlin.math.cos(latRad)
                                val newLat =
                                    com.gpm.virtual.xposed.RealisticGpsEngine.roundTo6Decimals(
                                        baseLat + (deltaLat / metersPerDegreeLat)
                                    )
                                val newLng =
                                    com.gpm.virtual.xposed.RealisticGpsEngine.roundTo6Decimals(
                                        baseLng + (deltaLng / metersPerDegreeLng)
                                    )
                                return newLat to newLng
                            }

                            when (scope) {
                                "GOJEK" -> {
                                    val current = preferences.getConfig()
                                    val base = readPerAppTarget("com.gojek.partner")
                                        ?: (current.latitude to current.longitude)
                                    val (newLat, newLng) = moveIn(base.first, base.second)
                                    injectionPrefs.edit()
                                        .putBoolean("gojek_has_target", true)
                                        .putLong("gojek_lat", newLat.toRawBits())
                                        .putLong("gojek_lng", newLng.toRawBits())
                                        .apply()
                                    preferences.updateConfig {
                                        it.copy(latitude = newLat, longitude = newLng)
                                    }
                                    val gojekOn = injectionPrefs.getBoolean(
                                        "gojek_spoof_active", false
                                    )
                                    if (gojekOn) {
                                        sendSpoofBroadcast(
                                            "com.gojek.partner", true,
                                            "com.gpm.virtual.ACTION_SPOOF_GOJEK",
                                            newLat, newLng
                                        )
                                    }
                                    MockLocationService.pushImmediate(
                                        this@FloatingOverlayService
                                    )
                                    MockLocationService.pingLocationToScope(
                                        this@FloatingOverlayService
                                    )
                                }
                                "GRAB" -> {
                                    val current = preferences.getConfig()
                                    val base = readPerAppTarget("com.grabtaxi.driver2")
                                        ?: (current.latitude to current.longitude)
                                    val (newLat, newLng) = moveIn(base.first, base.second)
                                    injectionPrefs.edit()
                                        .putBoolean("grab_has_target", true)
                                        .putLong("grab_lat", newLat.toRawBits())
                                        .putLong("grab_lng", newLng.toRawBits())
                                        .apply()
                                    preferences.updateConfig {
                                        it.copy(latitude = newLat, longitude = newLng)
                                    }
                                    val grabOn = injectionPrefs.getBoolean(
                                        "grab_spoof_active", false
                                    )
                                    if (grabOn) {
                                        for (pkg in listOf(
                                            "com.grabtaxi.driver2", "com.grab.driver"
                                        )) {
                                            sendSpoofBroadcast(
                                                pkg, true,
                                                "com.gpm.virtual.ACTION_SPOOF_GRAB",
                                                newLat, newLng
                                            )
                                        }
                                    }
                                    MockLocationService.pushImmediate(
                                        this@FloatingOverlayService
                                    )
                                    MockLocationService.pingLocationToScope(
                                        this@FloatingOverlayService
                                    )
                                }
                                else -> {
                                    val current = preferences.getConfig()
                                    val (newLat, newLng) = moveIn(
                                        current.latitude, current.longitude
                                    )
                                    preferences.updateConfig {
                                        it.copy(latitude = newLat, longitude = newLng)
                                    }
                                    if (current.isSpoofActive) {
                                        MockLocationService.pushImmediate(
                                            this@FloatingOverlayService
                                        )
                                    }
                                    MockLocationService.pingLocationToScope(
                                        this@FloatingOverlayService
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        overlayView = composeView
        try {
            windowManager?.addView(composeView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    override fun onDestroy() {
    // [PATCH-FLO-1] Drive lifecycle ke DESTROY SEBELUM removeView.
    // Ini memicu DisposableEffect.onDispose di Compose —
    // listener SharedPreferences di-unregister, tidak menumpuk.
    lifecycleOwner?.let {
        try {
            it.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
            it.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            it.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        } catch (_: Throwable) {}
    }
    lifecycleOwner = null

    super.onDestroy()
    serviceScope.cancel()
    overlayView?.let {
        try {
            windowManager?.removeView(it)
        } catch (_: Throwable) {}
        overlayView = null
    }
}

    companion object {
        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                val intent = Intent(context, FloatingOverlayService::class.java)
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }
}

/**
 * Minimalist LifecycleOwner for Compose in Service.
 */
private class FloatingLifecycleOwner :
    androidx.lifecycle.LifecycleOwner,
    androidx.lifecycle.ViewModelStoreOwner,
    androidx.savedstate.SavedStateRegistryOwner {

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val store = androidx.lifecycle.ViewModelStore()
    private val savedStateRegistryController =
        androidx.savedstate.SavedStateRegistryController.create(this)

    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
    override val viewModelStore: androidx.lifecycle.ViewModelStore get() = store
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun performRestore(savedState: android.os.Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun handleLifecycleEvent(event: androidx.lifecycle.Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}

/**
 * Reactive helper — baca Boolean dari SharedPreferences.
 */
@Composable
private fun rememberSharedPrefBoolean(
    prefs: SharedPreferences,
    key: String,
    default: Boolean = false
): Boolean {
    var value by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
            if (k == key) value = p.getBoolean(key, default)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

/**
 * Reactive helper — baca String dari SharedPreferences (untuk mode scope).
 */
@Composable
private fun rememberSharedPrefString(
    prefs: SharedPreferences,
    key: String,
    default: String = ""
): String {
    var value by remember { mutableStateOf(prefs.getString(key, default) ?: default) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
            if (k == key) value = p.getString(key, default) ?: default
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

/**
 * UI Floating Overlay.
 *
 * Tombol Play/Pause = scope-aware:
 *   - Mode GOJEK → toggle Gojek saja (kirim coords Gojek)
 *   - Mode GRAB  → toggle Grab saja (kirim coords Grab)
 *   - Mode ALL   → toggle keduanya (kirim coords masing-masing)
 */
@Composable
fun FloatingOverlayContent(
    preferences: GpsPreferences,
    injectionPrefs: SharedPreferences,
    onDragDelta: (Float, Float) -> Unit,
    onCloseOverlay: () -> Unit,
    onOpenApp: () -> Unit,
    onToggleHook: (Boolean) -> Unit,
    onRefreshLocation: () -> Unit,
    onMove: (deltaLatMeters: Double, deltaLngMeters: Double) -> Unit
) {
    val config by preferences.configFlow.collectAsState()

    // Mode scope aktif — reactive
    val scope = rememberSharedPrefString(injectionPrefs, "target_scope", "ALL")

    // State per-app — reactive
    val gojekSpoofActive = rememberSharedPrefBoolean(injectionPrefs, "gojek_spoof_active", false)
    val grabSpoofActive = rememberSharedPrefBoolean(injectionPrefs, "grab_spoof_active", false)

    // Status "aktif" menyesuaikan scope
    val isAnyActive = when (scope) {
        "GOJEK" -> gojekSpoofActive
        "GRAB" -> grabSpoofActive
        else -> gojekSpoofActive || grabSpoofActive
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        // 1. Tombol close
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF1E1E1E))
                .clickable { onCloseOverlay() }
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Tutup Overlay",
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Tombol fungsi paling bawah (Play/Pause)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFFE8EAED))
                .border(2.dp, Color(0xFF202124), CircleShape)
                .clickable { onToggleHook(!isAnyActive) }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isAnyActive) Color(0xFFD93025) else Color(0xFF1A73E8))
            ) {
                Icon(
                    imageVector = if (isAnyActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isAnyActive) "Hentikan" else "Mulai",
                    tint = Color.White,
                    modifier = Modifier.size(if (isAnyActive) 18.dp else 20.dp)
                )
            }
        }
    }
}

/**
 * Compact Joystick for on-screen movement.
 */
@Composable
private fun MiniOverlayJoystick(
    walkSpeed: Float,
    onMove: (deltaLatMeters: Double, deltaLngMeters: Double) -> Unit,
    onOpenApp: () -> Unit
) {
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }
    var selectedTravelMode by remember { mutableStateOf(TravelMode.WALK) }
    val maxRadius = 40f

    LaunchedEffect(thumbOffset, selectedTravelMode) {
        if (thumbOffset != Offset.Zero) {
            while (isActive) {
                val speed = when (selectedTravelMode) {
                    TravelMode.WALK -> walkSpeed
                    TravelMode.BIKE -> 6.0f
                    TravelMode.CAR -> 16.0f
                }
                val factor = (speed / maxRadius).toDouble()
                val deltaLng = thumbOffset.x.toDouble() * factor * 0.2
                val deltaLat = -thumbOffset.y.toDouble() * factor * 0.2
                onMove(deltaLat, deltaLng)
                delay(200)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF0202124),
        shadowElevation = 8.dp,
        modifier = Modifier.padding(4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(TravelMode.WALK, TravelMode.BIKE, TravelMode.CAR).forEach { mode ->
                    val isSelected = selectedTravelMode == mode
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Color(0xFF1A73E8) else Color.Transparent)
                            .clickable { selectedTravelMode = mode }
                    ) {
                        Icon(
                            imageVector = when (mode) {
                                TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
                                TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
                                TravelMode.CAR -> Icons.Default.DirectionsCar
                            },
                            contentDescription = mode.label,
                            tint = if (isSelected) Color.White else Color(0xFF9AA0A6),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF303134))
                    .border(1.dp, Color(0xFF5F6368), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { thumbOffset = Offset.Zero },
                            onDragCancel = { thumbOffset = Offset.Zero }
                        ) { change, dragAmount ->
                            change.consume()
                            val newOffset = thumbOffset + dragAmount
                            val dist = sqrt(newOffset.x * newOffset.x + newOffset.y * newOffset.y)
                            thumbOffset = if (dist <= maxRadius) {
                                newOffset
                            } else {
                                Offset(
                                    (newOffset.x / dist) * maxRadius,
                                    (newOffset.y / dist) * maxRadius
                                )
                            }
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(thumbOffset.x.roundToInt(), thumbOffset.y.roundToInt())
                        }
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10C484))
                        .border(2.dp, Color.White, CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Buka Peta",
                color = Color(0xFF8AB4F8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { onOpenApp() }
                    .padding(4.dp)
            )
        }
    }
}