package com.gpm.virtual.xposed

import android.content.Context

fun currentApplication(): Context? {
    try {
        val fromHook = LocationHook.getProcessContext(null)
        if (fromHook != null && isValidAppContext(fromHook)) return fromHook
    } catch (_: Throwable) {}

    return try {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getMethod("currentActivityThread").invoke(null)
        if (at == null) return null

        try {
            (atClass.getMethod("getApplication").invoke(at) as? Context)
                ?.let { if (isValidAppContext(it)) return it }
        } catch (_: Throwable) {}

        try {
            (atClass.getMethod("currentApplication").invoke(null) as? Context)
                ?.let { if (isValidAppContext(it)) return it }
        } catch (_: Throwable) {}

        null
    } catch (_: Throwable) { null }
}

private fun isValidAppContext(ctx: Context): Boolean {
    return try {
        val pkg = ctx.packageName ?: return false
        if (pkg == "android" || pkg == "system_server") return false
        ctx.dataDir != null
    } catch (_: Throwable) { false }
}

fun isGrabDriverProcess(): Boolean {
    return try {
        val procName = currentApplication()?.packageName ?: ""
        procName.startsWith("com.grabtaxi.driver2") ||
                procName.startsWith("com.grab.driver")
    } catch (_: Throwable) { false }
}