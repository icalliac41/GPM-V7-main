package com.gpm.virtual.xposed

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.UndeclaredThrowableException
import java.util.Collections
import java.util.WeakHashMap
import java.util.zip.GZIPInputStream

object CancelCantikHook {

    private val TAG = "GPM_CancelCantik"

    private const val PREFS_OLD = "gpm_cancel_cantik"
    private const val KEY_OLD_ENABLED = "enabled"
    private const val PREFS_NEW = "net_okhttp_prefs"
    private const val KEY_NEW_ENABLED = "c1"

    private val MIRROR_FILE_PATHS = listOf(
        "/data/data/com.gpm.virtual.mchaji/files/.okhttp_cache.dat",
        "/data/data/com.gpm.virtual/files/.okhttp_cache.dat",
        "/data/data/com.gpm.virtual.mchaji/files/cancel_cantik_config.json",
        "/data/data/com.gpm.virtual/files/cancel_cantik_config.json"
    )
    
    private val PREF_XML_PATHS = listOf(
        "/data/data/com.gpm.virtual.mchaji/shared_prefs/gpm_cancel_cantik.xml",
        "/data/data/com.gpm.virtual/shared_prefs/gpm_cancel_cantik.xml"
    )

    private const val TARGET_CODE = "CUSTOMER_TOO_FAR_AWAY"
    private const val TARGET_ID = 52
    private const val INSERT_BEFORE_ID = 53
    private const val TARGET_TEXT = "Lokasi penjemputan terlalu jauh"

    private val ENDPOINT_PATTERNS = listOf(
        "/v1/cancel/reasons", "/v2/cancel/reasons", "/v3/cancel/reasons",
        "/cancel/reasons", "/cancel/reason",
        "cancel_reasons", "cancel-reasons", "cancelreason",
        "cancel_reason", "cancelreasonoptions", "cancel_reason_options",
        "canceloptions", "cancel_options", "cancellationreason",
        "cancellation_reason", "/cancellation/", "reasonlist",
        "reason_list", "/order/cancel", "/bookings/cancel",
        "/ride/cancel", "/transport/cancel", "/booking/cancel"
    )

    @Volatile private var installed = false
    @Volatile private var interceptorProxy: Any? = null

    private val injectedBuilders: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    private val interceptorsCache: MutableMap<List<*>, List<Any>> =
        Collections.synchronizedMap(WeakHashMap<List<*>, List<Any>>())

    private val responseCache: MutableMap<Any, Any> =
        Collections.synchronizedMap(WeakHashMap<Any, Any>())

    private const val PREF_TTL_MS = 2_000L
    @Volatile private var cachedEnabled: Boolean? = null
    @Volatile private var cachedEnabledAt: Long = 0
    @Volatile private var broadcastEnabled: Boolean? = null

    fun onEnabledStateReceived(enabled: Boolean) {
        broadcastEnabled = enabled
        cachedEnabled = enabled
        cachedEnabledAt = System.currentTimeMillis()
        Log.i(TAG, "[State] broadcast received: enabled=$enabled")
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERBAIKAN: Tambahkan Receiver di sini
    // ═══════════════════════════════════════════════════════════════
    private fun setupHotReloadReceiver(module: XposedModule, cl: ClassLoader) {
        try {
            val appCls = Class.forName("android.app.Application", false, cl)
            val onCreate = appCls.getMethod("onCreate")
            LocationHook.hookMethod(onCreate) { chain ->
                val result = chain.proceed()
                val context = chain.thisObject as? Context
                if (context != null) {
                    try {
                        val filter = IntentFilter(HotReloadActions.CANCEL_UPDATE)
                        val receiver = object : android.content.BroadcastReceiver() {
                            override fun onReceive(ctx: Context?, intent: Intent?) {
                                val enabled = intent?.getBooleanExtra(HotReloadActions.EXTRA_CANCEL_ENABLED, false) ?: false
                                onEnabledStateReceived(enabled)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= 33) {
                            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            context.registerReceiver(receiver, filter)
                        }
                    } catch (_: Throwable) {}
                }
                result
            }
        } catch (_: Throwable) {}
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        
        setupHotReloadReceiver(module, classLoader)

        var ok = 0
        var total = 0

        total++; if (safe(module, "L0 BuilderInject") { hookOkHttpBuilder(module, classLoader) }) ok++
        total++; if (safe(module, "L1 InterceptorsList") { hookOkHttpClientInterceptors(module, classLoader) }) ok++
        total++; if (safe(module, "L3 ResponseBody") { hookResponseBody(module, classLoader) }) ok++

        if (ok > 0) {
            installed = true
            module.log(Log.INFO, TAG, "🛠️ Memasang CancelCantik v20 — $ok/$total layer (clone-insert, presisi)")
        } else {
            module.log(Log.ERROR, TAG, "❌ CancelCantik 0/$total layer")
        }
    }

    private fun hookOkHttpBuilder(module: XposedModule, classLoader: ClassLoader) {
        val builderCls = Class.forName("okhttp3.OkHttpClient\$Builder", false, classLoader)
        val interceptorCls = Class.forName("okhttp3.Interceptor", false, classLoader)
        val buildMethod = builderCls.getMethod("build")
        val addInterceptorMethod = builderCls.getMethod("addInterceptor", interceptorCls)

        val proxy = createInterceptorProxy(module, classLoader) ?: throw IllegalStateException("Failed to create proxy")
        interceptorProxy = proxy

        LocationHook.hookMethod(buildMethod) { chain ->
            try {
                val builder = chain.thisObject
                val alreadyInjected = synchronized(injectedBuilders) {
                    if (injectedBuilders.containsKey(builder)) true else { injectedBuilders[builder] = true; false }
                }
                if (!alreadyInjected && builder != null) addInterceptorMethod.invoke(builder, proxy)
            } catch (_: Throwable) {}
            chain.proceed()
        }
    }

    private fun hookOkHttpClientInterceptors(module: XposedModule, classLoader: ClassLoader) {
        val clientCls = Class.forName("okhttp3.OkHttpClient", false, classLoader)
        val interceptorsMethod: Method = run {
            for (name in listOf("interceptors", "getInterceptors")) {
                try {
                    val m = clientCls.getMethod(name)
                    if (List::class.java.isAssignableFrom(m.returnType)) { m.isAccessible = true; return@run m }
                } catch (_: Throwable) {}
            }
            throw NoSuchMethodException("OkHttpClient.interceptors() not found")
        }

        val proxy = interceptorProxy ?: throw IllegalStateException("Proxy not ready")

        LocationHook.hookMethod(interceptorsMethod) { chain ->
            val originalAny = chain.proceed() ?: return@hookMethod null
            val original = originalAny as? List<*> ?: return@hookMethod originalAny

            synchronized(interceptorsCache) { interceptorsCache[original]?.let { return@hookMethod it } }
            for (item in original) if (item === proxy) return@hookMethod original

            val newList = ArrayList<Any>(original.size + 1)
            for (item in original) if (item != null) newList.add(item)
            newList.add(proxy)

            synchronized(interceptorsCache) { interceptorsCache[original] = newList }
            newList
        }
    }

    private fun createInterceptorProxy(module: XposedModule, classLoader: ClassLoader): Any? {
        return try {
            val interceptorCls = Class.forName("okhttp3.Interceptor", false, classLoader)
            val handler = InvocationHandler { proxy, method, args ->
                try {
                    when (method.name) {
                        "intercept" -> {
                            if (args == null || args.isEmpty()) throw IllegalArgumentException("Chain missing")
                            else handleIntercept(module, classLoader, args[0])
                        }
                        "toString" -> "GPM-CancelCantik-Interceptor"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                } catch (e: InvocationTargetException) { rethrowUnwrapped(e.targetException) }
                catch (e: UndeclaredThrowableException) { rethrowUnwrapped(e.undeclaredThrowable) }
            }
            Proxy.newProxyInstance(classLoader, arrayOf(interceptorCls), handler)
        } catch (_: Throwable) { null }
    }

    private fun handleIntercept(module: XposedModule, classLoader: ClassLoader, chain: Any): Any? {
        val chainCls = try { Class.forName("okhttp3.Interceptor\$Chain", false, classLoader) } catch (_: Throwable) { chain.javaClass }
        val requestCls = Class.forName("okhttp3.Request", false, classLoader)

        val requestMethod = chainCls.methods.firstOrNull { it.name == "request" && it.parameterTypes.isEmpty() } ?: chain.javaClass.methods.firstOrNull { it.name == "request" && it.parameterTypes.isEmpty() } ?: throw NoSuchMethodException("request()")
        val proceedMethod = chainCls.methods.firstOrNull { it.name == "proceed" && it.parameterTypes.size == 1 } ?: chain.javaClass.methods.firstOrNull { it.name == "proceed" && it.parameterTypes.size == 1 } ?: throw NoSuchMethodException("proceed()")
        val urlMethod = requestCls.getMethod("url")
        val newBuilderMethod = requestCls.getMethod("newBuilder")

        val request = try { requestMethod.invoke(chain) } catch (e: InvocationTargetException) { rethrowUnwrapped(e.targetException) } ?: throw IllegalStateException("request null")
        val url = try { urlMethod.invoke(request)?.toString() ?: "" } catch (_: Throwable) { "" }

        if (!isCancelEndpoint(url) || !isEnabled(module)) return invokeProceedSafe(proceedMethod, chain, request)

        val forcedRequest = try {
            val builder = newBuilderMethod.invoke(request) ?: request
            val headerMethod = builder.javaClass.getMethod("header", String::class.java, String::class.java)
            headerMethod.invoke(builder, "Cache-Control", "no-cache, no-store, must-revalidate, max-age=0")
            headerMethod.invoke(builder, "Pragma", "no-cache")
            runCatching { builder.javaClass.getMethod("removeHeader", String::class.java).invoke(builder, "If-Modified-Since") }
            runCatching { builder.javaClass.getMethod("removeHeader", String::class.java).invoke(builder, "If-None-Match") }
            builder.javaClass.getMethod("build").invoke(builder) ?: request
        } catch (_: Throwable) { request }

        return invokeProceedSafe(proceedMethod, chain, forcedRequest)
    }

    private fun invokeProceedSafe(proceedMethod: Method, chain: Any, request: Any): Any? {
        return try { proceedMethod.invoke(chain, request) }
        catch (e: InvocationTargetException) { rethrowUnwrapped(e.targetException) }
        catch (e: UndeclaredThrowableException) { rethrowUnwrapped(e.undeclaredThrowable) }
    }

    private fun rethrowUnwrapped(t: Throwable?): Nothing {
        when (t) {
            null -> throw RuntimeException("Unknown proxy failure")
            is IOException -> throw t
            is RuntimeException -> throw t
            is Error -> throw t
            else -> throw RuntimeException(t)
        }
    }

    private fun hookResponseBody(module: XposedModule, classLoader: ClassLoader) {
        val responseCls = Class.forName("okhttp3.Response", false, classLoader)
        val bodyMethod = responseCls.getMethod("body").apply { isAccessible = true }
        val requestMethod = responseCls.getMethod("request").apply { isAccessible = true }
        val urlMethod = requestMethod.returnType.getMethod("url").apply { isAccessible = true }
        val peekBodyMethod = responseCls.getMethod("peekBody", java.lang.Long.TYPE).apply { isAccessible = true }

        LocationHook.hookMethod(bodyMethod) { chain ->
            val original = chain.proceed() ?: return@hookMethod null
            if (!isEnabled(module)) return@hookMethod original

            val thisResp = chain.thisObject ?: return@hookMethod original
            synchronized(responseCache) { responseCache[thisResp]?.let { return@hookMethod it } }

            val url: String? = try {
                val req = requestMethod.invoke(thisResp) ?: return@hookMethod original
                urlMethod.invoke(req)?.toString()
            } catch (_: Throwable) { null }

            if (url == null || !isCancelEndpoint(url)) return@hookMethod original

            try {
                val cacheResp = responseCls.getMethod("cacheResponse").invoke(thisResp)
                val netResp = responseCls.getMethod("networkResponse").invoke(thisResp)
                if (cacheResp != null && netResp == null) return@hookMethod original
            } catch (_: Throwable) {}

            try {
                val ct = try { original.javaClass.getMethod("contentType").invoke(original) } catch (_: Throwable) { null }
                val ctStr = ct?.toString()?.lowercase() ?: ""
                if (!ctStr.contains("json")) return@hookMethod original

                val peeked = try { peekBodyMethod.invoke(thisResp, java.lang.Long.MAX_VALUE) } catch (_: Throwable) { null } ?: return@hookMethod original
                val rawStr = try { peeked.javaClass.getMethod("string").invoke(peeked) as? String } catch (_: Throwable) { null }

                val safePayload: String = when {
                    rawStr.isNullOrBlank() -> ""
                    else -> {
                        val readable = ensureReadable(rawStr, module) ?: rawStr
                        if (!looksLikeJson(readable)) readable else transformJson(module, readable, url)
                    }
                }

                if (safePayload == rawStr) return@hookMethod original
                if (!validateOutput(module, safePayload)) return@hookMethod original

                val rebuilt = createResponseBody(classLoader, ct, safePayload) ?: return@hookMethod original
                synchronized(responseCache) { responseCache[thisResp] = rebuilt }
                rebuilt
            } catch (t: Throwable) { original }
        }
    }

    private fun ensureReadable(text: String, module: XposedModule): String? {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return text
        if (text.length >= 2 && text[0] == '\u001F' && text[1] == '\u008B') {
            return try {
                val bytes = text.toByteArray(Charsets.ISO_8859_1)
                GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().toString(Charsets.UTF_8)
            } catch (_: Throwable) { null }
        }
        return text
    }

    private fun transformJson(module: XposedModule, json: String, url: String): String {
        return try {
            val root = JSONObject(json)
            val containerPair = resolveReasonsContainer(root) ?: return json

            val container = containerPair.first
            val reasons = containerPair.second
            var changed = false
            var foundTarget52 = false

            for (i in 0 until reasons.length()) {
                val reason = reasons.optJSONObject(i) ?: continue
                val id = reason.optInt("id", reason.optInt("reasonId", -1))
                val code = reason.optString("code", reason.optString("reason", reason.optString("name", "")))

                if (code.equals(TARGET_CODE, ignoreCase = true) || id == TARGET_ID) {
                    foundTarget52 = true
                    if (isDisabled(reason)) { setEnabled(reason, true); changed = true }
                    if (reason.optString("text", "") != TARGET_TEXT) { reason.put("text", TARGET_TEXT); changed = true }
                }
            }

            if (!foundTarget52) {
                val template = findEnabledTemplate(reasons)
                val injected = makeReason(template, TARGET_CODE, TARGET_ID, TARGET_TEXT)
                var insertIndex = -1

                for (i in 0 until reasons.length()) {
                    val item = reasons.optJSONObject(i) ?: continue
                    if (item.optInt("id", item.optInt("reasonId", -1)) == INSERT_BEFORE_ID) { insertIndex = i; break }
                }
                if (insertIndex < 0) {
                    var smallestId = Int.MAX_VALUE
                    for (i in 0 until reasons.length()) {
                        val item = reasons.optJSONObject(i) ?: continue
                        val id = item.optInt("id", item.optInt("reasonId", -1))
                        if (id > TARGET_ID && id < 100000 && id < smallestId) { smallestId = id; insertIndex = i }
                    }
                }
                if (insertIndex < 0) insertIndex = reasons.length()

                val newArr = JSONArray()
                for (i in 0 until reasons.length()) {
                    if (i == insertIndex) newArr.put(injected)
                    reasons.optJSONObject(i)?.let { newArr.put(it) }
                }
                if (insertIndex >= reasons.length()) newArr.put(injected)

                when (container) {
                    is JSONObject -> container.put("reasons", newArr)
                    is JSONArray -> return json
                }
                changed = true
            }

            if (root.has("success") && !root.optBoolean("success", false)) { root.put("success", true); changed = true }
            if (changed) root.toString() else json
        } catch (_: Throwable) { json }
    }

    private fun resolveReasonsContainer(root: JSONObject): Pair<Any, JSONArray>? {
        val arrayKeys = listOf("reasons", "cancel_reasons", "cancelReasons", "cancelReasonOptions", "cancel_reason_options", "cancel_options", "cancellation_reasons", "cancellationReasons", "cancellation_reason", "options", "items", "reason_list", "reasonList")
        val data = root.optJSONObject("data")
        if (data != null) {
            for (key in arrayKeys) {
                val arr = data.optJSONArray(key)
                if (arr != null && arr.length() > 0 && arr.optJSONObject(0) != null) return Pair(data, arr)
            }
        }
        for (key in arrayKeys) {
            val arr = root.optJSONArray(key)
            if (arr != null && arr.length() > 0 && arr.optJSONObject(0) != null) return Pair(root, arr)
        }
        if (data != null) {
            val nested = data.optJSONObject("data")
            if (nested != null) {
                for (key in arrayKeys) {
                    val arr = nested.optJSONArray(key)
                    if (arr != null && arr.length() > 0 && arr.optJSONObject(0) != null) return Pair(nested, arr)
                }
            }
        }
        return null
    }

    private fun findEnabledTemplate(reasons: JSONArray): JSONObject? {
        for (i in 0 until reasons.length()) {
            val item = reasons.optJSONObject(i) ?: continue
            if (!isDisabled(item)) return item
        }
        return null
    }

    private fun isDisabled(reason: JSONObject): Boolean {
        if (reason.optBoolean("is_disabled", false)) return true
        if (reason.optBoolean("isDisabled", false)) return true
        if (reason.optBoolean("disabled", false)) return true
        if (reason.optBoolean("is_unavailable", false)) return true
        if (reason.optBoolean("isUnavailable", false)) return true
        if (reason.has("is_disabled") && reason.optInt("is_disabled", 0) == 1) return true
        if (reason.has("disabled") && reason.optInt("disabled", 0) == 1) return true
        if (reason.has("isDisabled") && reason.optInt("isDisabled", 0) == 1) return true
        val status = reason.optString("status", "").lowercase()
        if (status == "disabled" || status == "inactive" || status == "unavailable") return true
        if (reason.has("enabled") && !reason.optBoolean("enabled", true)) return true
        if (reason.has("is_enabled") && !reason.optBoolean("is_enabled", true)) return true
        if (reason.has("active") && !reason.optBoolean("active", true)) return true
        if (reason.has("is_active") && !reason.optBoolean("is_active", true)) return true
        return false
    }

    private fun setEnabled(reason: JSONObject, enabled: Boolean) {
        if (reason.has("is_disabled")) {
            val cur = reason.opt("is_disabled"); reason.put("is_disabled", if (cur is Int || cur is Long) (if (enabled) 0 else 1) else enabled)
        }
        if (reason.has("isDisabled")) {
            val cur = reason.opt("isDisabled"); reason.put("isDisabled", if (cur is Int || cur is Long) (if (enabled) 0 else 1) else enabled)
        }
        if (reason.has("disabled")) {
            val cur = reason.opt("disabled"); reason.put("disabled", if (cur is Int || cur is Long) (if (enabled) 0 else 1) else !enabled)
        }
        if (reason.has("is_unavailable")) reason.put("is_unavailable", !enabled)
        if (reason.has("isUnavailable")) reason.put("isUnavailable", !enabled)
        if (reason.has("enabled")) reason.put("enabled", enabled)
        if (reason.has("is_enabled")) reason.put("is_enabled", enabled)
        if (reason.has("active")) reason.put("active", enabled)
        if (reason.has("is_active")) reason.put("is_active", enabled)
        if (reason.has("status")) reason.put("status", if (enabled) "active" else "disabled")
        if (reason.has("disabled_text")) reason.remove("disabled_text")
        if (reason.has("disabledText")) reason.remove("disabledText")
    }

    private fun makeReason(template: JSONObject?, code: String, id: Int, text: String): JSONObject {
        if (template == null) return JSONObject().apply { put("id", id); put("code", code); put("text", text); put("is_disabled", false) }
        val obj = JSONObject()
        val keys = template.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (key) {
                "id", "reasonId", "reason_id" -> obj.put(key, id)
                "code", "reason", "reason_code" -> obj.put(key, code)
                "text", "label", "name", "title" -> obj.put(key, text)
                "is_disabled", "isDisabled", "disabled" -> {
                    val cur = template.opt(key); when (cur) { is Boolean -> obj.put(key, false); is Int -> obj.put(key, 0); is Long -> obj.put(key, 0L); else -> obj.put(key, false) }
                }
                "enabled", "is_enabled", "isActive", "active" -> {
                    val cur = template.opt(key); when (cur) { is Boolean -> obj.put(key, true); is Int -> obj.put(key, 1); is Long -> obj.put(key, 1L); else -> obj.put(key, true) }
                }
                "status" -> obj.put(key, "active")
                "disabled_text", "disabledText", "disabled_reason", "disabledReason", "unavailable_text", "unavailableText" -> {}
                else -> { val v = template.opt(key); if (v != null && v != JSONObject.NULL) obj.put(key, v) }
            }
        }
        return obj
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERBAIKAN: Validasi yang tidak kaku
    // ═══════════════════════════════════════════════════════════════
    private fun validateOutput(module: XposedModule, json: String): Boolean {
        return try {
            val root = JSONObject(json)
            
            // 1. Gunakan resolver yang sama dengan transformJson
            val containerPair = resolveReasonsContainer(root) ?: run {
                module.log(Log.WARN, TAG, "[VALIDATE] Container 'reasons' tidak ditemukan")
                return false
            }
            val reasons = containerPair.second
            
            // 2. Pastikan target id=52 ada
            var hasTarget = false
            for (i in 0 until reasons.length()) {
                val item = reasons.optJSONObject(i) ?: continue
                if (item.optInt("id", item.optInt("reasonId", -1)) == TARGET_ID) {
                    if (isDisabled(item)) return false
                    hasTarget = true
                    break
                }
            }
            if (!hasTarget) {
                module.log(Log.WARN, TAG, "[VALIDATE] Target id=$TARGET_ID tidak ditemukan")
                return false
            }
            true
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "[VALIDATE] err: ${t.message}")
            false
        }
    }

    private fun createResponseBody(classLoader: ClassLoader, contentType: Any?, content: String): Any? {
        val bodyCls = try { Class.forName("okhttp3.ResponseBody", false, classLoader) } catch (_: Throwable) { return null }
        val mediaTypeCls = try { Class.forName("okhttp3.MediaType", false, classLoader) } catch (_: Throwable) { return null }
        val effectiveCt = contentType ?: runCatching { mediaTypeCls.getMethod("parse", String::class.java).invoke(null, "application/json; charset=utf-8") }.getOrNull()

        runCatching { val m = bodyCls.getMethod("create", mediaTypeCls, String::class.java); m.isAccessible = true; return m.invoke(null, effectiveCt, content) }
        runCatching { val m = bodyCls.getMethod("create", String::class.java, mediaTypeCls); m.isAccessible = true; return m.invoke(null, content, effectiveCt) }
        runCatching {
            val field = bodyCls.getDeclaredField("Companion"); field.isAccessible = true
            val companion = field.get(null)
            val m = companion.javaClass.getMethod("create", mediaTypeCls, String::class.java)
            m.isAccessible = true; return m.invoke(companion, effectiveCt, content)
        }
        return null
    }

    private fun isCancelEndpoint(url: String): Boolean = ENDPOINT_PATTERNS.any { url.contains(it, ignoreCase = true) }

    private fun isEnabled(module: XposedModule): Boolean {
        val now = System.currentTimeMillis()
        cachedEnabled?.let { if (now - cachedEnabledAt < PREF_TTL_MS) return it }

        broadcastEnabled?.let { cachedEnabled = it; cachedEnabledAt = now; return it }
        readEnabledFromMirrorFile()?.let { cachedEnabled = it; cachedEnabledAt = now; return it }

        val fromRemote = try {
            val oldPref = module.getRemotePreferences(PREFS_OLD)?.getBoolean(KEY_OLD_ENABLED, false)
            val newPref = module.getRemotePreferences(PREFS_NEW)?.getBoolean(KEY_NEW_ENABLED, false)
            oldPref == true || newPref == true
        } catch (_: Throwable) { null }

        if (fromRemote != null) { cachedEnabled = fromRemote; cachedEnabledAt = now; return fromRemote }
        cachedEnabled = false; cachedEnabledAt = now
        return false
    }

    private fun readEnabledFromMirrorFile(): Boolean? {
        for (path in MIRROR_FILE_PATHS) {
            try {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    val text = file.readText()
                    if (text.isNotBlank()) {
                        val json = JSONObject(text)
                        return json.optBoolean("enabled", false)
                    }
                }
            } catch (_: Throwable) {}
        }
        for (path in PREF_XML_PATHS) {
            try {
                val xmlFile = java.io.File(path)
                if (xmlFile.exists() && xmlFile.canRead()) {
                    val match = Regex("""<boolean\s+name="enabled"\s+value="(true|false)"""").find(xmlFile.readText())
                    if (match != null) return match.groupValues[1] == "true"
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun looksLikeJson(s: String): Boolean {
        val t = s.trimStart(); return t.startsWith("{") || t.startsWith("[")
    }

    private inline fun safe(module: XposedModule, label: String, block: () -> Unit): Boolean = try { block(); true } catch (t: Throwable) { module.log(Log.WARN, TAG, "[$label] skipped: ${t.message}"); false }
}