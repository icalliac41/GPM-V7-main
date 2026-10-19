package com.gpm.virtual.util

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.UndeclaredThrowableException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

object NetworkStabilityEngine {

    private const val TAG = "GPM-NetStability"
    private var wifiLock: WifiManager.WifiLock? = null

    val GCM_PORTS = setOf(5228, 5229, 5230)
    private const val GMS_PACKAGE = "com.google.android.gms"

    // [PATCH-PUSH-1] ACTION_MCS_HEARTBEAT dan ACTION_GTALK_HEARTBEAT
    // DIHAPUS. Sejak Android Oreo, GMS tidak lagi menerima broadcast
    // eksternal untuk action ini.

    private const val TCP_SEND_BUFFER_BYTES = 96 * 1024
    private const val TCP_RECEIVE_BUFFER_BYTES = 96 * 1024

    private const val OKHTTP_CONNECT_TIMEOUT_MS = 4_000L
    private const val OKHTTP_READ_TIMEOUT_MS = 7_000L
    private const val OKHTTP_WRITE_TIMEOUT_MS = 7_000L
    private const val OKHTTP_POOL_MAX_IDLE = 32
    private const val OKHTTP_POOL_KEEP_ALIVE_MINUTES = 5L

    private const val HEARTBEAT_WARMUP_PULSES = 3
    private const val HEARTBEAT_WARMUP_INTERVAL_MS = 15_000L

    // [PATCH-PUSH-2] Interval GCM heartbeat realistis.
    private const val GCM_HEARTBEAT_MIN_SAFE_SECONDS = 120L
    private const val GCM_HEARTBEAT_DEFAULT_SECONDS = 180L

    private var heartbeatScope: CoroutineScope? = null
    private var heartbeatJob: Job? = null

    // [PATCH-FCM-4] Track builder yang sudah di-inject interceptor.
    private val injectedBuilders: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun isGcmEndpoint(socketAddress: SocketAddress?): Boolean {
        if (socketAddress !is InetSocketAddress) return false
        val port = socketAddress.port
        if (GCM_PORTS.contains(port)) return true
        val host = socketAddress.hostString?.lowercase()
            ?: socketAddress.hostName?.lowercase() ?: ""
        return host.contains("mtalk.google.com") ||
                host.contains("android.clients.google.com") ||
                host.contains("fcm.googleapis.com")
    }

    /**
     * Satu implementasi untuk semua socket tuning.
     */
    fun applySocketTuning(socket: Socket) {
        try {
            if (socket.isClosed) return
            socket.tcpNoDelay = true
            socket.keepAlive = true
            try { socket.trafficClass = 0x10 } catch (_: Throwable) {}
            try {
                socket.sendBufferSize = TCP_SEND_BUFFER_BYTES
                socket.receiveBufferSize = TCP_RECEIVE_BUFFER_BYTES
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Log.w(TAG, "applySocketTuning notice: ${t.message}")
        }
    }

    /** Backward compatibility alias */
    fun applyGcmSocketTuning(socket: Socket) {
        try {
            if (!socket.isClosed) {
                socket.soTimeout = 0
            }
        } catch (_: Throwable) {}
        applySocketTuning(socket)
    }

    /** Backward compatibility alias */
    fun applyFastTcp(socket: Socket) = applySocketTuning(socket)

    /**
     * [PATCH-PUSH-1] Mekanisme push priority yang WORK di Android modern.
     *
     * Strategi:
     *  1. Ambil PARTIAL_WAKE_LOCK dengan tag "GCM" — pattern ini
     *     dikenali GMS sebagai "aplikasi sedang menunggu push".
     *  2. Kirim explicit intent ke GMS GcmService (fallback).
     *
     * Yang DIHAPUS: broadcast MCS_HEARTBEAT/GTALK_HEARTBEAT (dead).
     */
    fun sendGcmHeartbeat(context: Context) {
        try {
            val appCtx = context.applicationContext ?: context

            // 1. WakeLock sebentar dengan tag "GCM" — pattern khusus.
            try {
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GCM")?.let { wl ->
                    wl.setReferenceCounted(false)
                    wl.acquire(2000L)
                    Log.d(TAG, "GCM-flagged wakelock acquired (2000ms)")
                }
            } catch (_: Throwable) {}

            // 2. Explicit intent ke GMS GCM service (best-effort).
            try {
                val svc = Intent().apply {
                    setClassName(
                        GMS_PACKAGE,
                        "com.google.android.gms.gcm.GcmService"
                    )
                    putExtra("heartbeat", true)
                }
                appCtx.startService(svc)
            } catch (_: Throwable) {}

            Log.d(TAG, "GCM heartbeat pulse sent (wakelock + service intent)")
        } catch (t: Throwable) {
            Log.w(TAG, "sendGcmHeartbeat notice: ${t.message}")
        }
    }

    @Synchronized
    fun startGcmHeartbeatKeeper(context: Context, intervalSeconds: Long = GCM_HEARTBEAT_DEFAULT_SECONDS) {
        if (heartbeatJob?.isActive == true) return

        val safeInterval = intervalSeconds.coerceAtLeast(GCM_HEARTBEAT_MIN_SAFE_SECONDS)
        val appCtx = context.applicationContext ?: context
        heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        heartbeatJob = heartbeatScope?.launch {
            Log.i(TAG, "GCM Heartbeat Keeper aktif (interval=${safeInterval}s)")

            for (i in 1..HEARTBEAT_WARMUP_PULSES) {
                if (!isActive) return@launch
                pulseHeartbeatWithWakeLock(appCtx)
                delay(HEARTBEAT_WARMUP_INTERVAL_MS)
            }

            while (isActive) {
                pulseHeartbeatWithWakeLock(appCtx)
                delay(safeInterval * 1000L)
            }
        }
    }

    @Synchronized
    fun stopGcmHeartbeatKeeper() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatScope = null
        Log.i(TAG, "GCM Heartbeat Keeper stopped")
    }

    private fun pulseHeartbeatWithWakeLock(context: Context) {
        var lock: PowerManager.WakeLock? = null
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            // [PATCH-PUSH-3] Pakai tag "GCM" persis — pattern GMS-recognized.
            lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GCM")
            lock?.setReferenceCounted(false)
            lock?.acquire(3000L)
            sendGcmHeartbeat(context)
        } catch (_: Throwable) {
            try { sendGcmHeartbeat(context) } catch (_: Throwable) {}
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun acquireHighPerfWifiLock(context: Context) {
        if (wifiLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(mode, "GPM:NetworkStabilityLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "High-Perf WiFi lock acquired (mode=$mode)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire WiFi lock: ${t.message}")
        }
    }

    @Synchronized
    fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i(TAG, "High-Perf WiFi lock released")
            }
        } catch (_: Throwable) {}
        wifiLock = null
    }

    fun optimizeOkHttpClientBuilder(builder: Any) {
        try {
            val cls = builder.javaClass

            try {
                cls.getMethod("retryOnConnectionFailure", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
            } catch (_: Throwable) {}

            try {
                cls.getMethod("connectTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
                    .invoke(builder, OKHTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                cls.getMethod("readTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
                    .invoke(builder, OKHTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                cls.getMethod("writeTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
                    .invoke(builder, OKHTTP_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}

            try {
                val poolCls = Class.forName("okhttp3.ConnectionPool", false, cls.classLoader)
                val poolCtor = poolCls.getConstructor(
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    TimeUnit::class.java
                )
                val poolInstance = poolCtor.newInstance(
                    OKHTTP_POOL_MAX_IDLE,
                    OKHTTP_POOL_KEEP_ALIVE_MINUTES,
                    TimeUnit.MINUTES
                )
                cls.getMethod("connectionPool", poolCls).invoke(builder, poolInstance)
            } catch (_: Throwable) {}

        } catch (t: Throwable) {
            Log.w(TAG, "optimizeOkHttpClientBuilder notice: ${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  [PATCH-FCM-4] FCM-aware OkHttp Interceptor
    // ═══════════════════════════════════════════════════════════════

    /**
     * Injeksi interceptor ke OkHttpClient.Builder.
     *
     * Interceptor tugasnya:
     *   1. Cek apakah kita dalam jendela FCM (2 detik setelah FCM masuk)
     *   2. Filter endpoint — hanya Gojek/Grab backend yang diprioritaskan
     *   3. Kalau ya: set thread priority + tambah header X-Priority
     *   4. Passthrough untuk endpoint lain
     */
    fun injectFcmPriorityInterceptor(builder: Any) {
        try {
            val cls = builder.javaClass
            val interceptorCls = Class.forName(
                "okhttp3.Interceptor", false, cls.classLoader
            )

            // Cek apakah sudah pernah di-inject
            if (injectedBuilders.containsKey(builder)) return

            val classLoader = cls.classLoader ?: ClassLoader.getSystemClassLoader()
            val handler = InvocationHandler { proxy, method, args ->
                try {
                    when (method.name) {
                        "intercept" -> {
                            if (args.isNullOrEmpty()) {
                                throw IllegalArgumentException("Interceptor.intercept called without arguments")
                            }
                            handleFcmIntercept(args[0], classLoader)
                        }
                        "toString" -> "GPM-FcmPriorityInterceptor"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                } catch (e: InvocationTargetException) {
                    rethrowUnwrapped(e.targetException)
                } catch (e: UndeclaredThrowableException) {
                    rethrowUnwrapped(e.undeclaredThrowable)
                }
            }

            val proxy = Proxy.newProxyInstance(
                classLoader, arrayOf(interceptorCls), handler
            )

            val addMethod = cls.methods.firstOrNull {
                it.name == "addInterceptor" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == interceptorCls
            }
            addMethod?.invoke(builder, proxy)
            injectedBuilders[builder] = true
            Log.i(TAG, "FCM priority interceptor injected into OkHttpClient.Builder")
        } catch (t: Throwable) {
            Log.w(TAG, "injectFcmPriorityInterceptor: ${t.message}")
        }
    }

    private fun handleFcmIntercept(chain: Any, classLoader: ClassLoader): Any? {
        val chainCls = try {
            Class.forName("okhttp3.Interceptor\$Chain", false, classLoader)
        } catch (_: Throwable) {
            chain.javaClass
        }

        val requestMethod = try {
            chainCls.getMethod("request")
        } catch (_: Throwable) {
            chainCls.methods.firstOrNull {
                it.name == "request" && it.parameterTypes.isEmpty()
            } ?: chain.javaClass.methods.firstOrNull {
                it.name == "request" && it.parameterTypes.isEmpty()
            } ?: throw NoSuchMethodException("No request() found on ${chain.javaClass}")
        }

        val request = try {
            requestMethod.invoke(chain)
        } catch (e: InvocationTargetException) {
            rethrowUnwrapped(e.targetException)
        } ?: throw IllegalStateException("Chain.request() returned null")

        val requestCls = request.javaClass

        val proceedMethod = try {
            chainCls.getMethod("proceed", requestCls)
        } catch (_: Throwable) {
            chainCls.methods.firstOrNull {
                it.name == "proceed" && it.parameterTypes.size == 1
            } ?: chain.javaClass.methods.firstOrNull {
                it.name == "proceed" && it.parameterTypes.size == 1
            } ?: throw NoSuchMethodException("No proceed(Request) found on ${chain.javaClass}")
        }

        // Cek jendela FCM
        val inFcmWindow = try {
            com.gpm.virtual.xposed.FcmPriorityBridge.isInFcmWindow()
        } catch (_: Throwable) { false }

        if (!inFcmWindow) {
            return invokeProceedSafe(proceedMethod, chain, request)
        }

        // [PATCH-FCM-8] Filter endpoint — hanya prioritaskan request
        // ke backend Gojek/Grab.
        val isTargetBackend = try {
            val urlMethod = requestCls.getMethod("url")
            val url = urlMethod.invoke(request)?.toString()?.lowercase() ?: ""
            url.contains("gojek") ||
                url.contains("go-jek") ||
                url.contains("grab.com") ||
                url.contains("grabtaxi")
        } catch (_: Throwable) { false }

        if (!isTargetBackend) {
            return invokeProceedSafe(proceedMethod, chain, request)
        }

        // Dalam window + endpoint Gojek/Grab — naikkan prioritas
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            )
        } catch (_: Throwable) {}

        // Tambah header ke request
        val finalRequest = try {
            val newBuilderMethod = requestCls.getMethod("newBuilder")
            val newBuilder = newBuilderMethod.invoke(request) ?: request
            val headerMethod = newBuilder.javaClass.getMethod(
                "header", String::class.java, String::class.java
            )
            headerMethod.invoke(newBuilder, "X-Priority", "high")
            headerMethod.invoke(newBuilder, "X-GPM-FCM-Window", "1")

            val buildMethod = newBuilder.javaClass.getMethod("build")
            buildMethod.invoke(newBuilder) ?: request
        } catch (_: Throwable) {
            request
        }

        return invokeProceedSafe(proceedMethod, chain, finalRequest)
    }

    private fun invokeProceedSafe(
        proceedMethod: Method,
        chain: Any,
        request: Any
    ): Any? {
        return try {
            proceedMethod.invoke(chain, request)
        } catch (e: InvocationTargetException) {
            rethrowUnwrapped(e.targetException)
        } catch (e: UndeclaredThrowableException) {
            rethrowUnwrapped(e.undeclaredThrowable)
        } catch (e: IllegalAccessException) {
            throw RuntimeException("Interceptor.proceed() access denied", e)
        }
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
}