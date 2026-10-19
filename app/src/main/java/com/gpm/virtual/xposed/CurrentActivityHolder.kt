package com.gpm.virtual.xposed

import android.app.Activity
import java.lang.ref.WeakReference

object CurrentActivityHolder {
    @Volatile private var current: WeakReference<Activity>? = null

    fun set(activity: Activity) {
        current = WeakReference(activity)
    }

    fun get(): Activity? {
        val act = current?.get()
        if (act == null || act.isFinishing || act.isDestroyed) return null
        return act
    }

    fun clear() {
        current = null
    }
}