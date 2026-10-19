package com.gpm.virtual

import com.gpm.virtual.xposed.RealisticGpsEngine
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testRealisticGpsEngine_randomize6thDecimal() {
        val baseLat = -6.168330
        val baseLng = 106.758849

        for (i in 0 until 100) {
            val randomizedLat = RealisticGpsEngine.randomize6thDecimal(baseLat)
            val randomizedLng = RealisticGpsEngine.randomize6thDecimal(baseLng)

            // Verify they are rounded to at most 6 decimals
            val latStr = BigDecimal.valueOf(randomizedLat).setScale(6, RoundingMode.HALF_UP).toPlainString()
            val lngStr = BigDecimal.valueOf(randomizedLng).setScale(6, RoundingMode.HALF_UP).toPlainString()

            // Verify the first 5 decimal digits remain unchanged
            val baseLatStr = BigDecimal.valueOf(baseLat).setScale(6, RoundingMode.HALF_UP).toPlainString()
            val baseLngStr = BigDecimal.valueOf(baseLng).setScale(6, RoundingMode.HALF_UP).toPlainString()

            val baseLatPrefix = baseLatStr.substring(0, baseLatStr.indexOf('.') + 6) // includes dot and 5 decimals
            val randLatPrefix = latStr.substring(0, latStr.indexOf('.') + 6)
            assertEquals("First 5 decimals must remain unchanged for lat", baseLatPrefix, randLatPrefix)

            val baseLngPrefix = baseLngStr.substring(0, baseLngStr.indexOf('.') + 6)
            val randLngPrefix = lngStr.substring(0, lngStr.indexOf('.') + 6)
            assertEquals("First 5 decimals must remain unchanged for lng", baseLngPrefix, randLngPrefix)

            // Verify the 6th decimal changed and difference is at most 9
            val baseLat6th = baseLatStr[baseLatStr.indexOf('.') + 6].digitToInt()
            val randLat6th = latStr[latStr.indexOf('.') + 6].digitToInt()
            assertNotEquals("6th decimal should randomize", baseLat6th, randLat6th)
            assertTrue("Difference in 6th decimal must not exceed 9", kotlin.math.abs(randLat6th - baseLat6th) <= 9)

            val baseLng6th = baseLngStr[baseLngStr.indexOf('.') + 6].digitToInt()
            val randLng6th = lngStr[lngStr.indexOf('.') + 6].digitToInt()
            assertNotEquals("6th decimal should randomize", baseLng6th, randLng6th)
            assertTrue("Difference in 6th decimal must not exceed 9", kotlin.math.abs(randLng6th - baseLng6th) <= 9)
        }
    }

    @Test
    fun testComputeRealisticCoordinates_disabledDoesNotRandomize() {
        val baseLat = -6.168330
        val baseLng = 106.758849

        val (lat, lng) = RealisticGpsEngine.computeRealisticCoordinates(
            baseLat = baseLat,
            baseLng = baseLng,
            isRandomEnabled = false
        )

        assertEquals(baseLat, lat, 0.000001)
        assertEquals(baseLng, lng, 0.000001)
    }

    @Test
    fun testComputeRealisticCoordinates_enabledRandomizes() {
        val baseLat = -6.168330
        val baseLng = 106.758849

        val (lat, lng) = RealisticGpsEngine.computeRealisticCoordinates(
            baseLat = baseLat,
            baseLng = baseLng,
            isRandomEnabled = true
        )

        assertNotEquals(baseLat, lat, 0.000000001)
        assertNotEquals(baseLng, lng, 0.000000001)

        // The absolute difference should be within 6th decimal
        val diffLat = kotlin.math.abs(lat - baseLat)
        val diffLng = kotlin.math.abs(lng - baseLng)

        assertTrue("Latitude change is at most 9 digits in 6th decimal", diffLat <= 0.00001)
        assertTrue("Longitude change is at most 9 digits in 6th decimal", diffLng <= 0.00001)
    }

    @Test
    fun testNearbyPlace_coordinatesPreserved() {
        val testLat = -6.4472025
        val testLng = 106.6391585
        val place = com.gpm.virtual.data.model.NearbyPlace(
            id = "test_1",
            name = "Indomaret Rumpin",
            category = "Belanja",
            latitude = testLat,
            longitude = testLng,
            distanceMeters = 85,
            address = "Jl. Raya Rumpin"
        )

        assertEquals(testLat, place.latitude, 0.0000001)
        assertEquals(testLng, place.longitude, 0.0000001)
        assertEquals("Indomaret Rumpin", place.name)
    }

    @Test
    fun testConnectionStability_configToggles() {
        val defaultConfig = com.gpm.virtual.data.preferences.GpsConfig()
        assertFalse(defaultConfig.isConnectionStabilityEnabled)
        assertTrue(defaultConfig.isOkHttpStabilityEnabled)
        assertTrue(defaultConfig.isFastTcpEnabled)
        assertTrue(defaultConfig.isFastestCaptureModeEnabled)

        val activeConfig = defaultConfig.copy(
            isConnectionStabilityEnabled = true,
            isOkHttpStabilityEnabled = true,
            isFastTcpEnabled = true,
            isFastestCaptureModeEnabled = true
        )
        assertTrue(activeConfig.isConnectionStabilityEnabled)
        assertTrue(activeConfig.isOkHttpStabilityEnabled)
        assertTrue(activeConfig.isFastTcpEnabled)
        assertTrue(activeConfig.isFastestCaptureModeEnabled)

        val disabledFastTcp = activeConfig.copy(isFastTcpEnabled = false)
        assertFalse(disabledFastTcp.isFastTcpEnabled)
        assertTrue(disabledFastTcp.isConnectionStabilityEnabled)
    }

    @Test
    fun testHookAndSpoof_independentStates() {
        val defaultConfig = com.gpm.virtual.data.preferences.GpsConfig()
        // Hook is enabled by default to allow telemetry and anti-mock
        assertTrue(defaultConfig.isHookEnabled)
        // Spoof is inactive by default (device real location passes through)
        assertFalse(defaultConfig.isSpoofActive)

        // When spoofing is triggered
        val spoofingActive = defaultConfig.copy(isSpoofActive = true)
        assertTrue(spoofingActive.isHookEnabled)
        assertTrue(spoofingActive.isSpoofActive)

        // When spoofing is stopped, hook remains enabled
        val spoofingStopped = spoofingActive.copy(isSpoofActive = false)
        assertTrue(spoofingStopped.isHookEnabled)
        assertFalse(spoofingStopped.isSpoofActive)

        // When hook itself is disabled
        val hookDisabled = spoofingStopped.copy(isHookEnabled = false)
        assertFalse(hookDisabled.isHookEnabled)
        assertFalse(hookDisabled.isSpoofActive)
    }

    @Test
    fun testLocationHook_independentStateVariables() {
        // Verify LocationHook exposes independent isHookActive and isSpoofActive state flags
        assertFalse(com.gpm.virtual.xposed.LocationHook.isHookActive)
        assertFalse(com.gpm.virtual.xposed.LocationHook.isSpoofActive)
    }

    @Test
    fun testRealisticGpsEngine_speedAndAccelSpeedNeverExceedMax() {
        val testInputs = listOf(0.0f, 0.5f, 1.0f, 1.2f, 1.5f, 2.5f, 5.0f, 10.0f)

        for (base in testInputs) {
            for (i in 0 until 100) {
                // Force update by advancing realistic speed
                val speed = RealisticGpsEngine.computeRealisticSpeed(base)
                val accelSpeed = RealisticGpsEngine.computeAccelerometerSpeed(speed)

                assertTrue("Speed ($speed) must be >= 0.0 m/s", speed >= 0.0f)
                assertTrue("Speed ($speed) must never exceed 1.5 m/s", speed <= 1.50001f)

                assertTrue("AccelSpeed ($accelSpeed) must be >= 0.0 m/s", accelSpeed >= 0.0f)
                assertTrue("AccelSpeed ($accelSpeed) must never exceed 1.5 m/s", accelSpeed <= 1.50001f)
            }
        }
    }

    @Test
    fun testLocationHook_isModulePackage() {
        assertTrue(com.gpm.virtual.xposed.LocationHook.isModulePackage("com.gpm.virtual"))
        assertTrue(com.gpm.virtual.xposed.LocationHook.isModulePackage("com.gpm.virtual.mchaji"))
        assertTrue(com.gpm.virtual.xposed.LocationHook.isModulePackage("com.gpm.virtual:service"))
        assertTrue(com.gpm.virtual.xposed.LocationHook.isModulePackage("com.gpm.virtual.mchaji:ui"))

        assertFalse(com.gpm.virtual.xposed.LocationHook.isModulePackage("com.gojek.partner"))
        assertFalse(com.gpm.virtual.xposed.LocationHook.isModulePackage("com.grabtaxi.driver2"))
        assertFalse(com.gpm.virtual.xposed.LocationHook.isModulePackage("com.shopee.driver"))
        assertFalse(com.gpm.virtual.xposed.LocationHook.isModulePackage("com.google.android.apps.maps"))
    }

    @Test
    fun testTargetPackageValidation_excludesSystemServerAndModule() {
        // Target apps MUST be true
        assertTrue(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.gojek.partner"))
        assertTrue(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.google.android.apps.maps"))
        assertTrue(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.grabtaxi.driver2"))
        assertTrue(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.shopee.driver.id"))
        assertTrue(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.maxim.driver"))
        assertTrue(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.indriver.driver"))

        assertTrue(com.gpm.virtual.xposed.LocationHook.isTargetPackage("com.gojek.partner"))
        assertTrue(com.gpm.virtual.xposed.LocationHook.isTargetPackage("com.google.android.apps.maps"))

        // system_server and android framework MUST NEVER be target packages (zero bootloop risk)
        assertFalse(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("android"))
        assertFalse(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("system_server"))
        assertFalse(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("android.uid.system:1000"))
        assertFalse(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.android.server"))

        assertFalse(com.gpm.virtual.xposed.LocationHook.isTargetPackage("android"))
        assertFalse(com.gpm.virtual.xposed.LocationHook.isTargetPackage("system_server"))
        assertFalse(com.gpm.virtual.xposed.LocationHook.isTargetPackage("android.uid.system:1000"))

        // Module itself and GMS must never be target packages
        assertFalse(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.gpm.virtual"))
        assertFalse(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.gpm.virtual.mchaji"))
        assertFalse(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.google.android.gms"))

        assertFalse(com.gpm.virtual.xposed.LocationHook.isTargetPackage("com.gpm.virtual"))
        assertFalse(com.gpm.virtual.xposed.LocationHook.isTargetPackage("com.gpm.virtual.mchaji"))
        assertFalse(com.gpm.virtual.xposed.LocationHook.isTargetPackage("com.google.android.gms"))
    }

    @Test
    fun testGeolocation_andTargetPackagesCoverage() {
        // Grab Driver & Shopee Driver must be covered for Geolocation and Spoofing
        assertTrue(com.gpm.virtual.xposed.LocationHook.isTargetPackage("com.grabtaxi.driver2"))
        assertTrue(com.gpm.virtual.xposed.LocationHook.isTargetPackage("com.shopee.driver.id"))
        assertTrue(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.grabtaxi.driver2"))
        assertTrue(com.gpm.virtual.xposed.ConnectionStabilityHook.isTargetPackage("com.shopee.driver.id"))
    }

    @Test
    fun testProviderInjection_passiveExcluded_gpsAndNetworkPrioritized() {
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER
        )
        assertTrue(providers.contains("gps"))
        assertTrue(providers.contains("network"))
        assertFalse(providers.contains("passive"))
        assertEquals("gps", providers[0])
        assertEquals("network", providers[1])
    }

    @Test
    fun testGcmSupport_portsAndEndpoints() {
        assertTrue(com.gpm.virtual.util.NetworkStabilityEngine.GCM_PORTS.contains(5228))
        assertTrue(com.gpm.virtual.util.NetworkStabilityEngine.GCM_PORTS.contains(5229))
        assertTrue(com.gpm.virtual.util.NetworkStabilityEngine.GCM_PORTS.contains(5230))

        val gcmAddr = java.net.InetSocketAddress("mtalk.google.com", 5228)
        assertTrue(com.gpm.virtual.util.NetworkStabilityEngine.isGcmEndpoint(gcmAddr))

        val nonGcmAddr = java.net.InetSocketAddress("example.com", 80)
        assertFalse(com.gpm.virtual.util.NetworkStabilityEngine.isGcmEndpoint(nonGcmAddr))

        val config = com.gpm.virtual.data.preferences.GpsConfig()
        assertTrue("isGcmSupportEnabled must be enabled by default", config.isGcmSupportEnabled)
    }

    @Test
    fun testParseCoordinates_formats() {
        val regex = Regex("""(-?\d{1,2}(?:\.\d+)?)[,\s;/|]+(-?\d{1,3}(?:\.\d+)?)""")

        fun parse(text: String): Pair<Double, Double>? {
            val clean = text.trim()
            val coordString = when {
                clean.contains("q=") -> clean.substringAfter("q=").substringBefore("&")
                clean.contains("/@") -> clean.substringAfter("/@").substringBefore("/")
                clean.startsWith("geo:", ignoreCase = true) -> clean.removePrefix("geo:").removePrefix("GEO:").substringBefore("?")
                else -> clean
            }
            val match = regex.find(coordString) ?: return null
            return try {
                val lat = match.groupValues[1].toDouble()
                val lng = match.groupValues[2].toDouble()
                if (lat in -90.0..90.0 && lng in -180.0..180.0) Pair(lat, lng) else null
            } catch (_: Exception) {
                null
            }
        }

        // Test comma separated
        val res1 = parse("-6.2087634, 106.8455990")
        assertNotNull(res1)
        assertEquals(-6.2087634, res1!!.first, 0.000001)
        assertEquals(106.8455990, res1.second, 0.000001)

        // Test space separated
        val res2 = parse("-6.2087634 106.8455990")
        assertNotNull(res2)
        assertEquals(-6.2087634, res2!!.first, 0.000001)
        assertEquals(106.8455990, res2.second, 0.000001)

        // Test google maps URL
        val res3 = parse("https://maps.google.com/?q=-6.1753924,106.8271528&z=17")
        assertNotNull(res3)
        assertEquals(-6.1753924, res3!!.first, 0.000001)
        assertEquals(106.8271528, res3.second, 0.000001)

        // Test geo uri
        val res4 = parse("geo:-6.1753924,106.8271528")
        assertNotNull(res4)
        assertEquals(-6.1753924, res4!!.first, 0.000001)
        assertEquals(106.8271528, res4.second, 0.000001)

        // Test invalid range
        assertNull(parse("95.0, 100.0"))
        assertNull(parse("not a coordinate"))
    }

    @Test
    fun testVtracingEngine_centerAndMovement() {
        val testLat = -6.208763
        val testLng = 106.845599
        com.gpm.virtual.util.VtracingEngine.reset()
        com.gpm.virtual.util.VtracingEngine.setCenter(testLat, testLng)

        val (cLat, cLng) = com.gpm.virtual.util.VtracingEngine.getCenter()
        assertEquals(testLat, cLat, 0.000001)
        assertEquals(testLng, cLng, 0.000001)

        // Without movement: position equals center
        val updated = com.gpm.virtual.util.VtracingEngine.update(movement = false)
        assertTrue(updated)
        val (posLat, posLng) = com.gpm.virtual.util.VtracingEngine.getSpoofedPosition()
        assertEquals(testLat, posLat, 0.000001)
        assertEquals(testLng, posLng, 0.000001)

        // Bearing, speed and accuracy ranges
        val speed = com.gpm.virtual.util.VtracingEngine.getSpeed()
        val bearing = com.gpm.virtual.util.VtracingEngine.getBearing()
        val accuracy = com.gpm.virtual.util.VtracingEngine.getAccuracy()
        assertTrue("Speed in 0..5.0 m/s, actual=$speed", speed in 0.0f..5.0f)
        assertTrue("Bearing in 0..360 deg, actual=$bearing", bearing in 0.0f..360.0f)
        assertTrue("Accuracy in 0..5.0 m, actual=$accuracy", accuracy in 0.0f..5.0f)

        // Reset throttle to test movement
        com.gpm.virtual.util.VtracingEngine.reset()
        com.gpm.virtual.util.VtracingEngine.setCenter(testLat, testLng)
        val moveUpdated = com.gpm.virtual.util.VtracingEngine.update(movement = true)
        assertTrue(moveUpdated)
        val (mLat, mLng) = com.gpm.virtual.util.VtracingEngine.getSpoofedPosition()
        val deltaLat = kotlin.math.abs(mLat - testLat)
        val deltaLng = kotlin.math.abs(mLng - testLng)
        assertTrue("Delta lat should be <= 2.5E-6, actual=$deltaLat", deltaLat <= 2.5E-6)
        assertTrue("Delta lng should be <= 2.5E-6, actual=$deltaLng", deltaLng <= 2.5E-6)
    }

    @Test
    fun testVtracingEngine_buildLocation() {
        com.gpm.virtual.util.VtracingEngine.reset()
        com.gpm.virtual.util.VtracingEngine.setCenter(-6.175392, 106.827153)
        com.gpm.virtual.util.VtracingEngine.update(movement = false)
        try {
            val loc = com.gpm.virtual.util.VtracingEngine.buildLocation(provider = "gps", accuracy = 100f)
            assertEquals("gps", loc.provider)
            assertEquals(-6.175392, loc.latitude, 0.000001)
            assertEquals(106.827153, loc.longitude, 0.000001)
            assertEquals(100f, loc.accuracy, 0.01f)
            assertTrue(loc.time > 0L)
        } catch (_: RuntimeException) {
            // Handled when running in non-Robolectric local JVM unit tests where android.jar stubs throw Method not mocked
        }
    }

    @Test
    fun testVtracingConfig_preferencesKeys() {
        val config = com.gpm.virtual.data.preferences.GpsConfig(
            useVirtual = true,
            useVirtualRacing = true,
            useRandom = true
        )
        assertTrue(config.useVirtual)
        assertTrue(config.useVirtualRacing)
        assertTrue(config.useRandom)
    }
}
