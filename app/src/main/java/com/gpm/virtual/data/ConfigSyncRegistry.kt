package com.gpm.virtual.data

import android.content.Context
import android.util.Log

object ConfigSyncRegistry {

    private const val TAG = "ConfigSyncRegistry"

    private val registered = mutableListOf<String>()

    fun registerAll(context: Context) {
        registered.clear()

        // ═══ CancelCantik ═══
        try {
            CancelCantikController.register(context)
            registered.add("CancelCantik")
            Log.i(TAG, "✅ CancelCantik registered")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ CancelCantik register fail: ${t.message}", t)
        }

        // ═══ Sortir ═══
        try {
            SortirSettingsController.register(context)
            registered.add("Sortir")
            Log.i(TAG, "✅ Sortir registered")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Sortir register fail: ${t.message}", t)
        }
    }

    fun resendAll() {
        var successCount = 0
        var failCount = 0

        // ═══ CancelCantik ═══
        try {
            CancelCantikController.resendConfig()
            successCount++
            Log.i(TAG, "📤 [ResendAll] CancelCantik ok")
        } catch (t: Throwable) {
            failCount++
            Log.e(TAG, "❌ [ResendAll] CancelCantik fail: ${t.message}", t)
        }

        // ═══ Sortir ═══
        try {
            SortirSettingsController.resendConfig()
            successCount++
            Log.i(TAG, "📤 [ResendAll] Sortir ok")
        } catch (t: Throwable) {
            failCount++
            Log.e(TAG, "❌ [ResendAll] Sortir fail: ${t.message}", t)
        }

        Log.i(TAG, "📤 [ResendAll] Done — success=$successCount fail=$failCount " +
                "(total controller=${registered.size})")
    }

    fun count(): Int = registered.size

    fun describeAll(): String =
        if (registered.isEmpty()) "(none)"
        else registered.joinToString(", ")

    fun isRegistered(name: String): Boolean = registered.contains(name)

    fun snapshot(): Map<String, Boolean> = registered.associateWith { true }
}