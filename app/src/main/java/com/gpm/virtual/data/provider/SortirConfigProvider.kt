package com.gpm.virtual.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log
import org.json.JSONObject

/**
 * ContentProvider untuk baca config Sortir secara LIVE (tanpa cache).
 *
 * Mengapa ini penting?
 * LSPosed `getRemotePreferences()` menyimpan cache di memori proses target.
 * Jika file prefs diubah setelah aplikasi target berjalan, LSPosed tetap
 * membaca nilai lama (atau kosong) sampai proses tersebut dimatikan.
 * 
 * Provider ini mem-bypass cache tersebut dengan melakukan query IPC langsung
 * ke SharedPreferences aplikasi GPM.
 */
class SortirConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        if (!isCallerAllowed()) {
            Log.w(TAG, "query: caller not allowed (uid=${Binder.getCallingUid()})")
            return null
        }

        val ctx = context?.applicationContext ?: return null
        return try {
            val pOld = ctx.getSharedPreferences("gpm_sortir_config", Context.MODE_PRIVATE)
            val pNew = ctx.getSharedPreferences("net_okhttp_prefs", Context.MODE_PRIVATE)

            val enabled = pOld.getBoolean("shadow_mode", false) ||
                          pNew.getBoolean("sortir_shadow_mode", false)

            val json = JSONObject().apply {
                put("shadow_mode", enabled)
                put("extend_timer", pOld.getBoolean("extend_timer", true))
                put("filter_enabled", pOld.getBoolean("filter_enabled", false))
                put("verbose", pOld.getBoolean("verbose", false))
                put("allow_other_services", pOld.getBoolean("allow_other_services", true))
                put("ts", System.currentTimeMillis())
            }

            val cursor = MatrixCursor(arrayOf("json"))
            cursor.addRow(arrayOf(json.toString()))
            Log.d(TAG, "query → $json")
            cursor
        } catch (t: Throwable) {
            Log.e(TAG, "query fail: ${t.message}", t)
            null
        }
    }

    private fun isCallerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val ctx = context ?: return false
        val pkgs = try {
            ctx.packageManager.getPackagesForUid(uid)
        } catch (_: Throwable) { null } ?: return false
        
        return pkgs.any { p ->
            p == "com.grabtaxi.driver2" ||
            p == "com.grab.driver" ||
            p == "com.gojek.partner" ||
            p == "com.gpm.virtual" ||
            p == "com.gpm.virtual.mchaji"
        }
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        private const val TAG = "SortirConfigProvider"
    }
}