package com.gpm.virtual.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Hooker yang mengganti return value method menjadi `false`
 * HANYA jika modul sedang aktif dan anti-mock diaktifkan.
 */
class MockLocationBypassHook(
    @Suppress("unused") private val module: XposedModule
) : XposedInterface.Hooker {

    override fun intercept(chain: XposedInterface.Chain): Any? {
        return if (LocationHook.shouldBypassMock()) {
            false
        } else {
            chain.proceed()
        }
    }
}