package com.gpm.virtual.xposed

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.gpm.virtual.util.NetworkStabilityEngine
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.net.Socket
import java.net.SocketAddress

/**
 * Xposed hook implementation for connection stability in target scope apps:
 * 1. OkHttp Request Stability (connection pooling, fast auto-retry, anti-hang, quick capture)
 * 2. Fast TCP (Socket TCP_NODELAY Nagle bypass, SO_KEEPALIVE, low latency IPTOS)
 * 3. Fastest Capture Mode (high-speed response between module & target scope apps)
 * 4. GCM / FCM Push Priority (FCM callback → OkHttp priority window)
 *
 * ⭐ Litmus-aware: tidak override tuning Gojek kalau courier sudah customized.
 */
object ConnectionStabilityHook {

    private const val TAG = "GPM:Stability"
    @Volatile private var okHttpHookInstalled = false
    @Volatile private var fastTcpHookInstalled = false
    @Volatile private var gcmTargetHooksInstalled = false
    @Volatile private var gmsGcmHookInstalled = false

    val KNOWN_TARGET_PACKAGES = setOf(
        "com.gojek.partner",
        "com.grabtaxi.driver2",
        "com.shopee.driver.id",
        "com.maxim.driver",
        "com.indriver.driver",
        "com.google.android.apps.maps"
    )

    fun isTargetPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg == "android" || pkg == "system_server" ||
            pkg.startsWith("android.") || pkg.startsWith("com.android.")) return false
        if (LocationHook.isModulePackage(pkg)) return false
        if (pkg == "com.google.android.gms") return false
        if (KNOWN_TARGET_PACKAGES.contains(pkg)) return true
        return pkg.contains("driver") || pkg.contains("partner")
    }

    private fun contextFromChain(chain: XposedInterface.Chain): Context? {
        return try {
            (chain.thisObject as? Context) ?: LocationHook.getProcessContext(null)
        } catch (_: Throwable) {
            null
        }
    }

    fun init(module: XposedModule, classLoader: ClassLoader, packageName: String) {
        if (!isTargetPackage(packageName)) {
            return
        }
        initFastTcpHooks(module, classLoader)
        initOkHttpStabilityHooks(module, classLoader)
        initTargetGcmHooks(module, classLoader, packageName)
    }

    fun initGmsGcmHooks(module: XposedModule, classLoader: ClassLoader) {
        if (gmsGcmHookInstalled) return
        try {
            val connectWithTimeout = ReflectUtil.findMethodExactOrNull(
                Socket::class.java,
                "connect",
                SocketAddress::class.java,
                java.lang.Integer.TYPE
            )
            if (connectWithTimeout != null) {
                LocationHook.hookMethod(connectWithTimeout, interceptor = GmsGcmSocketHook(module))
            }

            val connectNoTimeout = ReflectUtil.findMethodExactOrNull(
                Socket::class.java,
                "connect",
                SocketAddress::class.java
            )
            if (connectNoTimeout != null) {
                LocationHook.hookMethod(connectNoTimeout, interceptor = GmsGcmSocketHook(module))
            }

            gmsGcmHookInstalled = true
            OnceLogger.logOnce(module, TAG, "gms_gcm_init",
                "Google Play Services GCM/MCS push connection hook installed")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "GMS GCM hook notice: ${t.message}")
        }
    }

    fun initFastTcpHooks(module: XposedModule, classLoader: ClassLoader) {
        if (fastTcpHookInstalled) return
        try {
            val connectWithTimeout = ReflectUtil.findMethodExactOrNull(
                Socket::class.java,
                "connect",
                SocketAddress::class.java,
                java.lang.Integer.TYPE
            )
            if (connectWithTimeout != null) {
                LocationHook.hookMethod(connectWithTimeout, interceptor = FastTcpSocketHook(module))
            }

            val connectNoTimeout = ReflectUtil.findMethodExactOrNull(
                Socket::class.java,
                "connect",
                SocketAddress::class.java
            )
            if (connectNoTimeout != null) {
                LocationHook.hookMethod(connectNoTimeout, interceptor = FastTcpSocketHook(module))
            }

            fastTcpHookInstalled = true
            OnceLogger.logOnce(module, TAG, "fast_tcp_init",
                "Fast TCP Socket hook (TCP_NODELAY & IPTOS_LOWDELAY) installed successfully")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Fast TCP hook notice: ${t.message}")
        }
    }

    fun initOkHttpStabilityHooks(module: XposedModule, classLoader: ClassLoader) {
        if (okHttpHookInstalled) return
        try {
            val builderClass = ReflectUtil.findClassOrNull("okhttp3.OkHttpClient\$Builder", classLoader)
            if (builderClass != null) {
                val buildMethod = ReflectUtil.findMethodExactOrNull(builderClass, "build")
                if (buildMethod != null) {
                    LocationHook.hookMethod(buildMethod, interceptor = OkHttpBuilderHook(module))
                    okHttpHookInstalled = true
                    OnceLogger.logOnce(module, TAG, "okhttp_stability_init",
                        "OkHttp stability & FCM priority hook installed")
                }
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "OkHttp stability hook notice: ${t.message}")
        }
    }

    fun initTargetGcmHooks(module: XposedModule, classLoader: ClassLoader, packageName: String) {
        if (gcmTargetHooksInstalled) return
        var installedAny = false

        // ═══════════════════════════════════════════════════════════════
        // 1. Legacy GcmListenerService.onMessageReceived
        // ═══════════════════════════════════════════════════════════════
        val gcmListenerCls = ReflectUtil.findClassOrNull(
            "com.google.android.gms.gcm.GcmListenerService", classLoader
        )
        if (gcmListenerCls != null) {
            val onMsg = ReflectUtil.findMethodExactOrNull(
                gcmListenerCls,
                "onMessageReceived",
                String::class.java,
                Bundle::class.java
            )
            if (onMsg != null) {
                LocationHook.hookMethod(onMsg) { chain ->
                    if (LocationHook.isGcmSupportActive()) {
                        try { FcmPriorityBridge.notifyFcmReceived() } catch (_: Throwable) {}
                        try {
                            android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                            )
                        } catch (_: Throwable) {}

                        val bundle = chain.getArg(1) as? Bundle
                        if (bundle != null && LocationHook.isSpoofActive) {
                            val shift = TimestampCoordinator.computeAppliedShift()
                            if (shift != 0L) {
                                val sentTime = bundle.getLong("google.sent_time", 0L)
                                if (sentTime > 0L) {
                                    bundle.putLong("google.sent_time", sentTime + shift)
                                }
                            }
                        }
                        OnceLogger.logOnce(
                            module, TAG, "gcm_msg_received_$packageName",
                            "Legacy GCM message received & prioritized"
                        )
                    }
                    chain.proceed()
                }
                installedAny = true
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 2. FirebaseMessagingService.onMessageReceived (FCM — UTAMA)
        // ═══════════════════════════════════════════════════════════════
        val fcmServiceCls = ReflectUtil.findClassOrNull(
            "com.google.firebase.messaging.FirebaseMessagingService", classLoader
        )
        val remoteMsgCls = ReflectUtil.findClassOrNull(
            "com.google.firebase.messaging.RemoteMessage", classLoader
        )
        if (fcmServiceCls != null && remoteMsgCls != null) {
            val onFcmMsg = ReflectUtil.findMethodExactOrNull(
                fcmServiceCls, "onMessageReceived", remoteMsgCls
            )
            if (onFcmMsg != null) {
                LocationHook.hookMethod(onFcmMsg) { chain ->
                    if (LocationHook.isGcmSupportActive()) {
                        try { FcmPriorityBridge.notifyFcmReceived() } catch (_: Throwable) {}
                        try {
                            android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                            )
                        } catch (_: Throwable) {}

                        try {
                            val ctx = contextFromChain(chain)
                            val pm = ctx?.getSystemService(
                                Context.POWER_SERVICE
                            ) as? android.os.PowerManager
                            pm?.newWakeLock(
                                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                                "GCM"
                            )?.let { wl ->
                                wl.setReferenceCounted(false)
                                wl.acquire(3000L)
                            }
                        } catch (_: Throwable) {}

                        OnceLogger.logOnce(
                            module, TAG, "fcm_msg_received_$packageName",
                            "FCM push received & prioritized for $packageName"
                        )
                    }
                    chain.proceed()
                }
                installedAny = true
            }

            try {
                val onDeleted = ReflectUtil.findMethodExactOrNull(
                    fcmServiceCls, "onDeletedMessages"
                )
                if (onDeleted != null) {
                    LocationHook.hookMethod(onDeleted) { chain ->
                        try { FcmPriorityBridge.notifyFcmReceived() } catch (_: Throwable) {}
                        try {
                            android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                            )
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
            } catch (_: Throwable) {}

            try {
                val onNewToken = ReflectUtil.findMethodExactOrNull(
                    fcmServiceCls, "onNewToken", String::class.java
                )
                if (onNewToken != null) {
                    LocationHook.hookMethod(onNewToken) { chain ->
                        if (LocationHook.isGcmSupportActive()) {
                            try { FcmPriorityBridge.notifyFcmReceived() } catch (_: Throwable) {}
                            try {
                                android.os.Process.setThreadPriority(
                                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                                )
                            } catch (_: Throwable) {}
                            OnceLogger.logOnce(
                                module, TAG, "fcm_token_refresh_$packageName",
                                "FCM token refreshed — priority window opened"
                            )
                        }
                        chain.proceed()
                    }
                }
            } catch (_: Throwable) {}
        }

        // ═══════════════════════════════════════════════════════════════
        // 3. CloudMessagingReceiver.onMessageReceive
        // ═══════════════════════════════════════════════════════════════
        val cloudReceiverCls = ReflectUtil.findClassOrNull(
            "com.google.android.gms.cloudmessaging.CloudMessagingReceiver", classLoader
        )
        if (cloudReceiverCls != null) {
            val onReceiveMethods = ReflectUtil.findMethodsByName(cloudReceiverCls, "onMessageReceive")
            for (m in onReceiveMethods) {
                LocationHook.hookMethod(m) { chain ->
                    if (LocationHook.isGcmSupportActive()) {
                        try { FcmPriorityBridge.notifyFcmReceived() } catch (_: Throwable) {}
                        OnceLogger.logOnce(
                            module, TAG, "cloud_msg_received_$packageName",
                            "CloudMessaging push packet processed for $packageName"
                        )
                    }
                    chain.proceed()
                }
                installedAny = true
            }
        }

        if (installedAny) {
            gcmTargetHooksInstalled = true
            OnceLogger.logOnce(
                module, TAG, "target_gcm_init_$packageName",
                "GCM/FCM client push reception hooks installed for $packageName"
            )
        }
    }

    class FastTcpSocketHook(private val module: XposedModule) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val pkg = LocationHook.getCurrentPackageName()
            if (LocationHook.isSystemServer || !isTargetPackage(pkg)) {
                return chain.proceed()
            }

            val socket = chain.thisObject as? Socket
            val targetAddr = chain.args.getOrNull(0) as? SocketAddress

            try {
                if (socket != null) {
                    val isGcm = NetworkStabilityEngine.isGcmEndpoint(targetAddr)
                    if (isGcm && LocationHook.isGcmSupportActive()) {
                        NetworkStabilityEngine.applyGcmSocketTuning(socket)
                    } else if (LocationHook.isFastTcpActive()) {
                        NetworkStabilityEngine.applyFastTcp(socket)
                    }
                }
            } catch (_: Throwable) {}

            val result = chain.proceed()

            try {
                if (socket != null) {
                    val isGcm = NetworkStabilityEngine.isGcmEndpoint(targetAddr)
                    if (isGcm && LocationHook.isGcmSupportActive()) {
                        NetworkStabilityEngine.applyGcmSocketTuning(socket)
                        OnceLogger.logOnce(module, TAG, "gcm_socket_tuned",
                            "GCM/MCS push socket connection optimized for $pkg")
                    } else if (LocationHook.isFastTcpActive()) {
                        NetworkStabilityEngine.applyFastTcp(socket)
                        OnceLogger.logOnce(module, TAG, "fast_tcp_applied",
                            "Fast TCP applied to active socket in $pkg")
                    }
                }
            } catch (_: Throwable) {}

            return result
        }
    }

    class GmsGcmSocketHook(private val module: XposedModule) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val socket = chain.thisObject as? Socket
            val targetAddr = chain.args.getOrNull(0) as? SocketAddress

            val isGcm = socket != null &&
                NetworkStabilityEngine.isGcmEndpoint(targetAddr)

            try {
                if (isGcm) {
                    try {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                        )
                    } catch (_: Throwable) {}

                    NetworkStabilityEngine.applyGcmSocketTuning(socket!!)
                }
            } catch (_: Throwable) {}

            val result = chain.proceed()

            try {
                if (isGcm) {
                    NetworkStabilityEngine.applyGcmSocketTuning(socket!!)

                    try {
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                        socket.soTimeout = 0
                        socket.trafficClass = 0x10
                    } catch (_: Throwable) {}

                    OnceLogger.logOnce(module, TAG, "gms_gcm_socket_tuned",
                        "GMS persistent push socket (mtalk.google.com) tuned — URGENT priority + zero-delay keep-alive")
                }
            } catch (_: Throwable) {}

            return result
        }
    }

    class OkHttpBuilderHook(private val module: XposedModule) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val pkg = LocationHook.getCurrentPackageName()
            if (LocationHook.isSystemServer || !isTargetPackage(pkg)) {
                return chain.proceed()
            }

            val builder = chain.thisObject
            try {
                if (builder != null && LocationHook.isOkHttpStabilityActive()) {

                    // ⭐ Litmus-aware: hanya optimize kalau Gojek tidak customize
                    val courierCustomized = try {
                        LitmusObserver.isCourierCustomized()
                    } catch (_: Throwable) { false }

                    if (!courierCustomized) {
                        NetworkStabilityEngine.optimizeOkHttpClientBuilder(builder)
                    }

                    // FCM priority interceptor selalu aman (tidak konflik)
                    try {
                        NetworkStabilityEngine.injectFcmPriorityInterceptor(builder)
                    } catch (_: Throwable) {}

                    OnceLogger.logOnce(
                        module, TAG,
                        "okhttp_built_${pkg}_${courierCustomized}",
                        "OkHttp builder ${if (courierCustomized) "passthrough (courier customized)" else "optimized"} for $pkg"
                    )
                }
            } catch (_: Throwable) {}
            return chain.proceed()
        }
    }
}