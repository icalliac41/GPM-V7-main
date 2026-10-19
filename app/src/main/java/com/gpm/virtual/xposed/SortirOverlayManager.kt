package com.gpm.virtual.xposed.overlay

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.gpm.virtual.xposed.CurrentActivityHolder

object SortirOverlayManager {

    private const val TAG = "GMP:SortirOverlay"

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayDialog: Dialog? = null
    private var countdownHandler: Handler? = null
    private var countdownRunnable: Runnable? = null

    @SuppressLint("SetTextI18n")
    fun showOverlay(
        context: Context,
        serviceType: String,
        price: String,
        pickupDist: String,
        pickupAddr: String,
        dropoffDist: String,
        dropoffAddr: String,
        countdownSeconds: Int,
        onAccept: () -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        hideOverlay()
        val activity = CurrentActivityHolder.get()

        val root = buildOverlayView(
            context, serviceType, price, pickupDist, pickupAddr,
            dropoffDist, dropoffAddr, countdownSeconds,
            { hideOverlay(); onAccept() },
            { hideOverlay(); onCancel() }
        )

        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            try {
                val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
                dialog.setContentView(root)
                dialog.setCancelable(false)
                dialog.window?.apply {
                    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    setGravity(Gravity.BOTTOM)
                    setBackgroundDrawableResource(android.R.color.transparent)
                }
                dialog.show()
                overlayDialog = dialog
                startCountdown(root, countdownSeconds)
                Log.i(TAG, "Overlay muncul via Dialog")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Dialog approach failed: ${t.message}")
            }
        }

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = 150
            }
            wm.addView(root, params)
            overlayView = root
            startCountdown(root, countdownSeconds)
            Log.i(TAG, "Overlay muncul via WindowManager")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "WindowManager approach also failed: ${t.message}")
        }
        return false
    }

    private fun buildOverlayView(
        context: Context, serviceType: String, price: String,
        pickupDist: String, pickupAddr: String, dropoffDist: String, dropoffAddr: String,
        countdownSeconds: Int, onAcceptClick: () -> Unit, onCancelClick: () -> Unit
    ): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 40f }
        }
        root.addView(TextView(context).apply {
            text = serviceType; textSize = 18f; setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = price; textSize = 26f; setTextColor(Color.parseColor("#00A859"))
            setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 10, 0, 30)
        })
        root.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, 0, 0, 30) }
        })
        root.addView(TextView(context).apply {
            text = "🟢 $pickupAddr\n$pickupDist"; textSize = 14f; setTextColor(Color.DKGRAY); setLineSpacing(0f, 1.2f)
        })
        root.addView(TextView(context).apply {
            text = "🔴 $dropoffAddr\n$dropoffDist"; textSize = 14f; setTextColor(Color.DKGRAY)
            setPadding(0, 30, 0, 40); setLineSpacing(0f, 1.2f)
        })
        val btnLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val btnCancel = TextView(context).apply {
            text = "Batalkan"; textSize = 16f; setTextColor(Color.RED); gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
            background = GradientDrawable().apply { setColor(Color.parseColor("#FFEBEE")); cornerRadius = 20f }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 20, 0) }
            setOnClickListener { onCancelClick() }
        }
        btnLayout.addView(btnCancel)
        val btnAccept = TextView(context).apply {
            text = "Terima ($countdownSeconds)"; textSize = 16f; setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
            background = GradientDrawable().apply { setColor(Color.parseColor("#00A859")); cornerRadius = 20f }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onAcceptClick() }
        }
        btnLayout.addView(btnAccept)
        root.addView(btnLayout)
        root.tag = btnAccept
        return root
    }

    private fun startCountdown(root: LinearLayout, countdownSeconds: Int) {
        val btnAccept = root.tag as? TextView ?: return
        countdownHandler = Handler(Looper.getMainLooper())
        var currentSec = countdownSeconds
        countdownRunnable = object : Runnable {
            override fun run() {
                currentSec--
                btnAccept.text = "Terima ($currentSec)"
                if (currentSec <= 0) hideOverlay() else countdownHandler?.postDelayed(this, 1000)
            }
        }
        countdownHandler?.postDelayed(countdownRunnable!!, 1000)
    }

    fun hideOverlay() {
        try {
            countdownRunnable?.let { countdownHandler?.removeCallbacks(it) }
            overlayDialog?.let { try { it.dismiss() } catch (_: Throwable) {} }
            overlayDialog = null
            overlayView?.let { try { windowManager?.removeView(it) } catch (_: Throwable) {} }
            overlayView = null
        } catch (_: Throwable) {}
        countdownHandler = null
        countdownRunnable = null
    }
}