package com.gpm.virtual.xposed

import io.github.libxposed.api.XposedInterface

/**
 * Hooker generik yang menggantikan total return value method menjadi
 * sebuah nilai konstan (full replace, tidak memanggil chain.proceed()).
 *
 * Ekuivalen dengan XC_MethodReplacement di API lama.
 *
 * @param returnValue Nilai konstan yang selalu dikembalikan.
 */
class ConstantReturnHook(
    private val returnValue: Any?
) : XposedInterface.Hooker {

    override fun intercept(chain: XposedInterface.Chain): Any? {
        return returnValue
    }
}