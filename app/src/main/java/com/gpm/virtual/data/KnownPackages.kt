package com.gpm.virtual.data

/**
 * Konstanta package target terpusat.
 * Gunakan ini di SEMUA tempat untuk mencegah typo.
 */
object KnownPackages {
    // Gojek
    const val GOJEK_PARTNER = "com.gojek.partner"
    const val GOJEK_APP = "com.gojek.app"

    // Grab
    const val GRAB_TAXI_DRIVER = "com.grabtaxi.driver2"
    const val GRAB_DRIVER = "com.grab.driver"
    const val GRAB_PASSENGER = "com.grabtaxi.passenger"

    // Maps
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"

    // GMS
    const val GMS = "com.google.android.gms"

    // Modul sendiri
    const val GPM_MODULE = "com.gpm.virtual"
    const val GPM_MODULE_ALT = "com.gpm.virtual.mchaji"

    val GOJEK_ALL = setOf(GOJEK_PARTNER, GOJEK_APP)
    val GRAB_ALL = setOf(GRAB_TAXI_DRIVER, GRAB_DRIVER, GRAB_PASSENGER)
    val ALL_TARGETS = GOJEK_ALL + GRAB_ALL + setOf(GOOGLE_MAPS, WAZE)
    val MODULE_PACKAGES = setOf(GPM_MODULE, GPM_MODULE_ALT)
}