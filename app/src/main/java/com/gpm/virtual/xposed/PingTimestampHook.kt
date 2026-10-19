package com.gpm.virtual.xposed

import android.util.Log
import com.gpm.virtual.BuildConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

class PingTimestampHook(
    private val module: XposedModule,
    private val methodName: String
) : XposedInterface.Hooker {

    companion object {
        private val TAG: String =
            if (BuildConfig.DEBUG) "GMP:Gojek:[PING]" else "Trace"

        private const val EXPECTED_CONSTRUCTOR_ARGS = 20
        @Volatile private var warningLogged = false
    }

    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (!LocationHook.isHookActive || !LocationHook.isSpoofActive) {
            return chain.proceed()
        }

        return when (methodName) {
            "<init>" -> handleConstructor(chain)
            "setPingTimestamp", "setLocationTimestamp" -> handleSetter(chain)
            else -> chain.proceed()
        }
    }

    private fun handleConstructor(chain: XposedInterface.Chain): Any? {
        val args = chain.args

        if (args.size != EXPECTED_CONSTRUCTOR_ARGS) {
            if (!warningLogged) {
                warningLogged = true
                logDebug(
                    "⚠️ Constructor arg count = ${args.size} (Expected: $EXPECTED_CONSTRUCTOR_ARGS). " +
                    "Timestamp spoof SKIP."
                )
            }
            return chain.proceed()
        }

        val origDevTime = args[3] as? Long
        val origLocTs = args[11] as? Long
        val origPingTs = args[12] as? Long

        if (origDevTime == null || origLocTs == null || origPingTs == null) {
            return chain.proceed()
        }

        if (origLocTs == 0L || origPingTs == 0L) return chain.proceed()

        val deviceTime = if (origDevTime > 0L) origDevTime else origPingTs

        val result = TimestampCoordinator.coordinate(
            originalLocationTs = origLocTs,
            originalPingTs = origPingTs,
            originalDeviceTime = deviceTime
        )

        if (!result.applied) return chain.proceed()

        val mutableArgs = args.toMutableList()
        mutableArgs[3] = result.deviceTime
        mutableArgs[11] = result.locationTimestamp
        mutableArgs[12] = result.pingTimestamp

        logDebug(
            "HTTP ctor shift=${result.shift}ms | " +
                "loc $origLocTs->${result.locationTimestamp} | " +
                "ping $origPingTs->${result.pingTimestamp}"
        )

        return chain.proceed(mutableArgs.toTypedArray())
    }

    private fun handleSetter(chain: XposedInterface.Chain): Any? {
        val original = chain.args.getOrNull(0) as? Long ?: return chain.proceed()
        val spoofed = TimestampCoordinator.applyDelta(original)
        if (spoofed == original) return chain.proceed()

        logDebug("$methodName Δ=${original - spoofed}ms | $original -> $spoofed")
        return chain.proceed(arrayOf(spoofed))
    }

    private fun logDebug(msg: String) {
        if (!BuildConfig.DEBUG) return
        try {
            module.log(Log.INFO, TAG, msg)
        } catch (_: Throwable) {}
    }
}