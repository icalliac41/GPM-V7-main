package com.gpm.virtual.xposed

import android.util.Log
import com.gpm.virtual.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════
 *  LitmusObserver — deteksi konteks runtime Gojek
 * ═══════════════════════════════════════════════════════════════════
 *
 *  PRINSIP: READ-ONLY. Jangan pernah modifikasi file Litmus.
 *  File ini menyimpan feature flag dari server Gojek. Kalau diubah:
 *    - correlation_id server mismatch → detection
 *    - mtime berubah → integrity check gagal
 *    - hash file berbeda → anti-tamper trigger
 *
 *  Tujuan: GPM tahu fitur mana yang aktif untuk menyesuaikan spoof.
 *
 *  Anti-deteksi:
 *    - Cache 30 detik (hindari I/O overhead yang bisa terdeteksi)
 *    - Fail-safe: kalau parsing gagal, return default (Kalman=off)
 *    - Tidak log file path (jangan bocorkan ke logcat)
 *    - Tidak hold file handle (hindari file lock warning)
 */
object LitmusObserver {

    private const val TAG = "GMP:Litmus"

    // Path Litmus Gojek driver (urutan prioritas)
    private val LITMUS_PATHS = listOf(
        "/data/data/com.gojek.partner/shared_prefs/driver_litmus_preference.xml",
        "/data/user/0/com.gojek.partner/shared_prefs/driver_litmus_preference.xml"
    )

    // ═══════════════════════════════════════════════════════════════
    // SNAPSHOT MODEL
    // ═══════════════════════════════════════════════════════════════
    data class Snapshot(
        // Kalman filter untuk Carto (paling kritis untuk spoof)
        val kalmanEnabled: Boolean = false,
        val kalmanVariant: String = "control",

        // Live traffic Carto
        val liveTrafficEnabled: Boolean = false,

        // Courier connection tuning
        val courierVariant: String = "control",
        val courierRetry: Int = -1,
        val courierTimeoutMs: Long = -1L,
        val courierKeepAlive: Int = -1,

        // Food pooling (service area)
        val foodPoolingAreas: List<String> = emptyList(),
        val foodPoolingEnabled: Boolean = false,

        // Rest reminder
        val restReminderEnabled: Boolean = false,
        val restReminderIntervalMs: Long = -1L,

        // VoIP (untuk konteks apakah chat/voice aktif — indikator driver online)
        val voipEnabled: Boolean = false,

        // Ping frequency hint (kalau ada di Litmus)
        val pingIntervalHintMs: Long = -1L,

        // Meta
        val parsedAtMs: Long = 0L,
        val sourceFound: Boolean = false
    ) {
        val isKalmanActive: Boolean get() = kalmanEnabled
        val isCourierCustomized: Boolean get() = courierVariant != "control"
    }

    // ═══════════════════════════════════════════════════════════════
    // CACHE — TTL 30s, hindari I/O spam
    // ═══════════════════════════════════════════════════════════════
    @Volatile private var cachedSnapshot: Snapshot = Snapshot()
    @Volatile private var cachedAt: Long = 0L
    private const val CACHE_TTL_MS = 30_000L

    private val parseCount = AtomicLong(0)
    private val cacheHitCount = AtomicLong(0)

    fun get(): Snapshot {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_TTL_MS && cachedSnapshot.sourceFound) {
            cacheHitCount.incrementAndGet()
            return cachedSnapshot
        }
        val fresh = tryParse()
        cachedSnapshot = fresh
        cachedAt = now
        parseCount.incrementAndGet()
        return fresh
    }

    fun isKalmanActive(): Boolean = get().kalmanEnabled

    fun isCourierCustomized(): Boolean = get().isCourierCustomized

    /** Force refresh — hanya untuk debug/testing. */
    fun invalidate() {
        cachedAt = 0L
    }

    // ═══════════════════════════════════════════════════════════════
    // PARSER — defensive, silent, fail-safe
    // ═══════════════════════════════════════════════════════════════
    private fun tryParse(): Snapshot {
        val file = findLitmusFile() ?: return Snapshot(
            parsedAtMs = System.currentTimeMillis(),
            sourceFound = false
        )

        val xml = try {
            // Baca dengan limit (hindari OOM kalau file corrupt)
            if (file.length() > 2_000_000L) return Snapshot(sourceFound = false)
            file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            return Snapshot(sourceFound = false)
        }

        if (xml.isBlank()) return Snapshot(sourceFound = false)

        return parseXml(xml)
    }

    private fun findLitmusFile(): File? {
        for (path in LITMUS_PATHS) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead() && f.length() > 0) return f
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun parseXml(xml: String): Snapshot {
        val map = extractStringEntries(xml)

        // ─── Kalman filter ───
        val kalmanExp = decodeJson(map["exp_carto_kalman_filter"])
        val kalmanProps = kalmanExp?.optJSONObject("properties")
        val kalmanOn = kalmanProps?.optBoolean("isEnabled", false) ?: false
        val kalmanVariant = kalmanExp?.optString("variant", "control") ?: "control"

        // ─── Live traffic ───
        val trafficExp = decodeJson(map["exp_carto_live_traffic"])
        val trafficOn = trafficExp?.optJSONObject("properties")
            ?.optBoolean("isEnabled", false) ?: false

        // ─── Courier connection ───
        val courierExp = decodeJson(map["release_driver_courier_connection"])
        val courierVariant = courierExp?.optString("variant", "control") ?: "control"
        val courierProps = courierExp?.optJSONObject("properties")
        val courierRetry = courierProps?.optInt("retry", -1) ?: -1
        val courierTimeout = courierProps?.optLong("timeoutMs",
            courierProps.optLong("timeout", -1L)) ?: -1L
        val courierKeepAlive = courierProps?.optInt("keepAlive",
            courierProps.optInt("keepAliveSeconds", -1)) ?: -1

        // ─── Food pooling — service areas ───
        val poolingExp = decodeJson(map["release_food_pooling"])
        val poolingProps = poolingExp?.optJSONObject("properties")
        val poolingEnabled = poolingProps?.optBoolean("isEnabled", false) ?: false
        val areas = mutableListOf<String>()
        poolingProps?.optJSONArray("serviceAreas")?.let { arr: JSONArray ->
            for (i in 0 until minOf(arr.length(), 200)) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) areas.add(v)
            }
        }
        // Fallback: beberapa versi pakai key berbeda
        if (areas.isEmpty()) {
            poolingProps?.optJSONArray("areas")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 200)) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank()) areas.add(v)
                }
            }
        }

        // ─── Rest reminder ───
        val restExp = decodeJson(map["rest_reminder_config"])
        val restProps = restExp?.optJSONObject("properties")
        val restOn = restProps?.optBoolean("isEnabled", false) ?: false
        val restInterval = restProps?.optLong("intervalMs",
            restProps.optLong("interval", -1L)) ?: -1L

        // ─── VoIP ───
        val voipExp = decodeJson(map["exp_android_voip_sdk_driver"])
        val voipOn = voipExp?.optJSONObject("properties")
            ?.optBoolean("isEnabled", false) ?: false

        // ─── Ping interval hint (kalau ada eksperimen khusus) ───
        val pingInterval = try {
            decodeJson(map["exp_location_ping_interval"])
                ?.optJSONObject("properties")
                ?.optLong("intervalMs", -1L) ?: -1L
        } catch (_: Throwable) { -1L }

        return Snapshot(
            kalmanEnabled = kalmanOn,
            kalmanVariant = kalmanVariant,
            liveTrafficEnabled = trafficOn,
            courierVariant = courierVariant,
            courierRetry = courierRetry,
            courierTimeoutMs = courierTimeout,
            courierKeepAlive = courierKeepAlive,
            foodPoolingAreas = areas,
            foodPoolingEnabled = poolingEnabled,
            restReminderEnabled = restOn,
            restReminderIntervalMs = restInterval,
            voipEnabled = voipOn,
            pingIntervalHintMs = pingInterval,
            parsedAtMs = System.currentTimeMillis(),
            sourceFound = true
        )
    }

    /**
     * Parse XML sederhana tanpa dependency.
     * Handle escaped entities yang dipakai Gojek.
     */
    private fun extractStringEntries(xml: String): Map<String, String> {
        val result = HashMap<String, String>(128)
        // Match <string name="key" value="..."/>
        val regex = Regex(
            """<string\s+name="([^"]+)"\s+value="([^"]*)"""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in regex.findAll(xml)) {
            val key = m.groupValues[1]
            val raw = m.groupValues[2]
            result[key] = unescapeXml(raw)
        }
        return result
    }

    private fun unescapeXml(s: String): String {
        if (s.indexOf('&') < 0) return s
        return s
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")
            .replace("&#9;", "\t")
    }

    private fun decodeJson(s: String?): JSONObject? {
        if (s.isNullOrBlank()) return null
        return try { JSONObject(s) } catch (_: Throwable) { null }
    }

    // ═══════════════════════════════════════════════════════════════
    // DIAGNOSTIC LOG (build debug only)
    // ═══════════════════════════════════════════════════════════════
    fun logSnapshot(module: io.github.libxposed.api.XposedModule) {
        if (!BuildConfig.DEBUG) return
        try {
            val s = get()
            if (!s.sourceFound) {
                module.log(Log.INFO, TAG, "[Litmus] File not found (fresh install?)")
                return
            }
            val sb = StringBuilder("[Litmus] Konteks runtime:\n")
            sb.append("  • Kalman filter : ${s.kalmanEnabled}")
            if (s.kalmanVariant != "control") sb.append(" (${s.kalmanVariant})")
            sb.append('\n')
            sb.append("  • Live traffic  : ${s.liveTrafficEnabled}\n")
            sb.append("  • VoIP SDK      : ${s.voipEnabled}\n")
            sb.append("  • Courier       : ${s.courierVariant}")
            if (s.courierRetry > 0) sb.append(" retry=${s.courierRetry}")
            if (s.courierTimeoutMs > 0) sb.append(" timeout=${s.courierTimeoutMs}ms")
            sb.append('\n')
            sb.append("  • Food pooling  : ${s.foodPoolingAreas.size} area")
            if (s.foodPoolingEnabled) sb.append(" (aktif)")
            sb.append('\n')
            sb.append("  • Rest reminder : ${s.restReminderEnabled}")
            module.log(Log.INFO, TAG, sb.toString())
        } catch (_: Throwable) {}
    }

    fun stats(): String =
        "parse=${parseCount.get()} cacheHit=${cacheHitCount.get()}"
}