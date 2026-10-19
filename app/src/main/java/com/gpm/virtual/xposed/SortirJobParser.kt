package com.gpm.virtual.xposed

import android.util.Log
import com.gpm.virtual.BuildConfig
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object SortirJobParser {

    private val TAG: String = if (BuildConfig.DEBUG) "GMP:Sortir:Parser" else "T"
    private val methodCache = ConcurrentHashMap<String, Method?>()

    fun parse(baseJob: Any?): SortirJobInfo? {
        if (baseJob == null) return null
        return try {
            val bookingCode = callString(baseJob, "d")
            if (bookingCode.isNullOrBlank()) return null

            val jobType     = callObject(baseJob, "w")
            val jobVertical = callObject(baseJob, "x")

            val flags = if (jobType != null) extractJobTypeFlags(jobType) else emptyMap()
            val verticalId = callInt(jobVertical, "b")
            val vertical   = mapVertical(verticalId)
            val variant    = deriveVariant(verticalId, flags)

            val fareObj    = callObject(baseJob, "n")
            val price      = parsePrice(fareObj)

            val pickupList = callObject(baseJob, "j") as? List<*>
            val pickupObj = pickupList?.firstOrNull()
            val dropoffList = callObject(baseJob, "E") as? List<*>
            val dropoffObj = dropoffList?.firstOrNull()
            val distanceList = callObject(baseJob, "t") as? List<*>

            val pickupAddr = callString(pickupObj, "getAddress") ?: "Lokasi Jemput"
            val dropoffAddr = callString(dropoffObj, "getAddress") ?: "Lokasi Antar"

            val pickupDistObj = distanceList?.getOrNull(0)
            val dropoffDistObj = distanceList?.getOrNull(1)

            val pickupDist = callString(pickupDistObj, "getDistance") ?: "0.0 km"
            val dropoffDist = callString(dropoffDistObj, "getDistance") ?: "0.0 km"

            SortirJobInfo(
                bookingCode, vertical, verticalId, variant, flags, price, 0f,
                pickupAddr, dropoffAddr, pickupDist, dropoffDist
            )
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.e(TAG, "parse error: ${t.message}", t)
            null
        }
    }

    private fun extractJobTypeFlags(jobType: Any): Map<String, Boolean> {
        val flags = mutableMapOf<String, Boolean>()
        for (name in listOf("c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","s","t","u","v","w")) {
            val m = findMethod(jobType.javaClass, name) ?: continue
            try { (m.invoke(jobType) as? Boolean)?.let { flags[name] = it } } catch (_: Throwable) {}
        }
        return flags
    }

    private fun findMethod(clazz: Class<*>, name: String): Method? {
        val key = "${clazz.name}#$name"
        return methodCache.getOrPut(key) {
            try { clazz.getMethod(name).apply { isAccessible = true } }
            catch (_: Throwable) { clazz.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.apply { isAccessible = true } }
        }
    }

    private fun mapVertical(id: Int): String = when (id) {
        0 -> "NORMAL"; 1 -> "Bike"; 2 -> "Car"; 3 -> "Food"; else -> "Vertical_$id"
    }

    private fun deriveVariant(verticalId: Int, flags: Map<String, Boolean>): String {
        return when (verticalId) {
            1 -> when {
                flags["c"] == true -> "Bike Hemat"
                flags["t"] == true -> "Bike XL"
                flags["q"] == true -> "Bike Share"
                else -> "Bike Standard"
            }
            2 -> when {
                flags["c"] == true -> "Car Hemat"
                flags["t"] == true -> "Car XL"
                else -> "Car Standard"
            }
            3 -> when {
                flags["i"] == true -> "Food Scheduled"
                flags["d"] == true -> "Food Special"
                else -> "Food Standard"
            }
            else -> "Standard"
        }
    }

    private fun parsePrice(fare: Any?): Float {
        if (fare == null) return 0f
        return try {
            val amount = callObject(fare, "getAmount") ?: callObject(fare, "getDisplayAmount") ?: return 0f
            amount.toString().replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
        } catch (_: Throwable) { 0f }
    }

    fun callObject(obj: Any?, method: String): Any? {
        if (obj == null) return null
        return try { findMethod(obj.javaClass, method)?.invoke(obj) } catch (_: Throwable) { null }
    }

    fun callString(obj: Any?, method: String): String? = callObject(obj, method) as? String
    fun callInt(obj: Any?, method: String): Int = (callObject(obj, method) as? Number)?.toInt() ?: -1
}