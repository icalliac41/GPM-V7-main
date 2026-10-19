package com.gpm.virtual.xposed.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * HookOverlayManager — GPM overlay status untuk target app (Gojek/Grab).
 *
 * Fungsi:
 *   1. Menampilkan overlay status spoof real-time di atas app target
 *   2. Intercept overlay yang dibuat oleh app target untuk dimodifikasi/di-hide
 *   3. Lifecycle: addView / updateViewLayout / removeView
 *
 * FIX:
 *   - Log `addView` di-gate dengan VERBOSE (tidak spam di release)
 *   - Fallback WindowManagerGlobal → WindowManagerImpl (Android 12+)
 *   - installed = true hanya kalau semua hook sukses
 *   - install() bisa di-retry
 */
object HookOverlayManager {

    private const val TAG = "GMP:HookOverlay"
    private const val GPM_OVERLAY_TAG = "GPM_SPOOF_OVERLAY"

    // ⭐ Gate log debug yang berpotensi spam
    private const val VERBOSE = false

    @Volatile private var module: XposedModule? = null
    @Volatile private var installed = false

    private val attachedOverlays =
        Collections.synchronizedMap(WeakHashMap<Any, MutableList<OverlayHandle>>())

    private var statusOverlayRef: WeakReference<View>? = null

    data class OverlayHandle(
        val view: View,
        val tag: String,
        val type: Int,
        val removeCallback: () -> Unit
    )

    // ==================================================================
    // Install — dipanggil dari LocationHook.initPackage() + retry di resume
    // ==================================================================
    fun install(m: XposedModule, cl: ClassLoader, targetPkg: String) {
        if (installed) return
        module = m
        try {
            hookWindowManagerAddView(cl, targetPkg)
            hookWindowManagerRemoveView(cl, targetPkg)
            hookWindowManagerUpdateViewLayout(cl, targetPkg)
            // ⭐ Set installed=true HANYA setelah semua hook sukses
            installed = true
            if (VERBOSE) {
                m.log(Log.INFO, TAG, "[HookOverlay] Installed for $targetPkg")
            }
        } catch (t: Throwable) {
            m.log(Log.ERROR, TAG, "[HookOverlay] Install failed: ${t.message}", t)
            // installed tetap false → akan di-retry kalau dipanggil lagi
        }
    }

    /**
     * ⭐ Resolve WindowManager class untuk hook.
     * Android 12+ memindahkan sebagian method dari WindowManagerImpl
     * ke WindowManagerGlobal. Coba dua-duanya.
     */
    private fun resolveWindowManagerClass(cl: ClassLoader): Class<*> {
        return try {
            Class.forName("android.view.WindowManagerGlobal", false, cl)
        } catch (_: Throwable) {
            Class.forName("android.view.WindowManagerImpl", false, cl)
        }
    }

    // ==================================================================
    // Hook 1: addView(View, LayoutParams)
    // ==================================================================
    private fun hookWindowManagerAddView(cl: ClassLoader, targetPkg: String) {
        val wmClass = resolveWindowManagerClass(cl)
        val methods = wmClass.methods.filter {
            it.name == "addView" && it.parameterTypes.size == 2
        }
        for (method in methods) {
            method.isAccessible = true
            module?.hook(method)?.intercept { chain ->
                val view = chain.args.getOrNull(0) as? View
                val params = chain.args.getOrNull(1) as? WindowManager.LayoutParams
                if (view != null && params != null) {
                    try { onAddView(targetPkg, view, params) }
                    catch (t: Throwable) {
                        if (BuildConfig.DEBUG) {
                            module?.log(Log.WARN, TAG, "onAddView failed: ${t.message}")
                        }
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun onAddView(
        targetPkg: String,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        val tag = view.tag as? String
        val isGpmOverlay = tag == GPM_OVERLAY_TAG
        val isSystemOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                params.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // ⭐ hanya log kalau VERBOSE
        if (VERBOSE) {
            module?.log(Log.DEBUG, TAG,
                "[HookOverlay] addView pkg=$targetPkg tag=$tag " +
                "type=${params.type} ${params.width}x${params.height} " +
                "isGpm=$isGpmOverlay isSystem=$isSystemOverlay")
        }

        if (isGpmOverlay) {
            registerOverlay(view, tag ?: "gpm_overlay")
        }

        // Intercept overlay anti-cheat dari app target
        if (!isGpmOverlay && isSystemOverlay && shouldHideOverlay(targetPkg, view)) {
            params.alpha = 0f
            params.flags = params.flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (BuildConfig.DEBUG) {
                module?.log(Log.INFO, TAG,
                    "[HookOverlay] Hidden overlay from $targetPkg (class=${view.javaClass.simpleName})")
            }
        }

        if (isOrderOverlay(tag)) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 16
            params.y = 100
            if (BuildConfig.DEBUG) {
                module?.log(Log.INFO, TAG,
                    "[HookOverlay] Repositioned order overlay to top-left")
            }
        }
    }

    // ==================================================================
    // Hook 2: removeView(View)
    // ==================================================================
    private fun hookWindowManagerRemoveView(cl: ClassLoader, targetPkg: String) {
        val wmClass = resolveWindowManagerClass(cl)
        val methods = wmClass.methods.filter {
            it.name == "removeView" && it.parameterTypes.size == 1
        }
        for (method in methods) {
            method.isAccessible = true
            module?.hook(method)?.intercept { chain ->
                val view = chain.args.getOrNull(0) as? View
                if (view != null) onRemoveView(view)
                chain.proceed()
            }
        }
    }

    private fun onRemoveView(view: View) {
        synchronized(attachedOverlays) {
            attachedOverlays.values.forEach { list ->
                list.removeAll { it.view === view }
            }
        }
        if (statusOverlayRef?.get() === view) {
            statusOverlayRef = null
        }
    }

    // ==================================================================
    // Hook 3: updateViewLayout(View, LayoutParams)
    // ==================================================================
    private fun hookWindowManagerUpdateViewLayout(cl: ClassLoader, targetPkg: String) {
        val wmClass = resolveWindowManagerClass(cl)
        val methods = wmClass.methods.filter {
            it.name == "updateViewLayout" && it.parameterTypes.size == 2
        }
        for (method in methods) {
            method.isAccessible = true
            module?.hook(method)?.intercept { chain ->
                val view = chain.args.getOrNull(0) as? View
                val params = chain.args.getOrNull(1) as? WindowManager.LayoutParams
                if (view != null && params != null && view.tag == GPM_OVERLAY_TAG) {
                    onUpdateLayout(view, params)
                }
                chain.proceed()
            }
        }
    }

    private fun onUpdateLayout(view: View, params: WindowManager.LayoutParams) {
        params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    // ==================================================================
    // Overlay Registry
    // ==================================================================
    private fun registerOverlay(view: View, tag: String) {
        val handle = OverlayHandle(
            view = view,
            tag = tag,
            type = 0,
            removeCallback = { /* cleanup di sini kalau perlu */ }
        )
        synchronized(attachedOverlays) {
            attachedOverlays.getOrPut("global") { mutableListOf() }.add(handle)
        }
    }

    fun detachAll() {
        synchronized(attachedOverlays) {
            attachedOverlays.values.forEach { list ->
                list.forEach { it.removeCallback() }
            }
            attachedOverlays.clear()
        }
        statusOverlayRef = null
    }

    // ==================================================================
    // Show / Update / Hide status overlay (dipanggil dari GPM)
    // ==================================================================
    fun showStatusOverlay(
        context: Context,
        text: String,
        gravity: Int = Gravity.TOP or Gravity.START,
        x: Int = 16,
        y: Int = 120
    ) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(context).apply {
                setText(text)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xAA000000.toInt())
                setPadding(24, 12, 24, 12)
                textSize = 12f
                tag = GPM_OVERLAY_TAG
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                this.gravity = gravity
                this.x = x
                this.y = y
            }
            wm.addView(tv, params)
            statusOverlayRef = WeakReference(tv)
            if (BuildConfig.DEBUG) {
                module?.log(Log.INFO, TAG, "[HookOverlay] Status overlay shown: $text")
            }
        } catch (t: Throwable) {
            module?.log(Log.ERROR, TAG, "showStatusOverlay failed: ${t.message}", t)
        }
    }

    fun updateStatusOverlay(text: String) {
        val tv = statusOverlayRef?.get() as? TextView ?: return
        Handler(Looper.getMainLooper()).post {
            tv.text = text
        }
    }

    fun hideStatusOverlay(context: Context) {
        val tv = statusOverlayRef?.get() ?: return
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(tv)
            statusOverlayRef = null
            if (BuildConfig.DEBUG) {
                module?.log(Log.INFO, TAG, "[HookOverlay] Status overlay hidden")
            }
        } catch (t: Throwable) {
            module?.log(Log.ERROR, TAG, "hideStatusOverlay failed: ${t.message}", t)
        }
    }

    // ==================================================================
    // Deteksi overlay app target
    // ==================================================================
    private fun shouldHideOverlay(pkg: String, view: View): Boolean {
        val clsName = view.javaClass.name
        return clsName.contains("AntiCheat", ignoreCase = true) ||
                (clsName.contains("Warning", ignoreCase = true) &&
                 clsName.contains("Overlay", ignoreCase = true))
    }

    private fun isOrderOverlay(tag: String?): Boolean {
        if (tag == null) return false
        return tag.contains("order", ignoreCase = true) ||
                tag.contains("incoming", ignoreCase = true)
    }
}