package com.gpm.virtual.data.repository

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.gpm.virtual.data.model.NearbyPlace
import com.gpm.virtual.network.OkHttpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NearbyRepository(private val context: Context) {
    private val geocoder = Geocoder(context, Locale.forLanguageTag("id-ID"))

    suspend fun getNearbyPlaces(
        centerLat: Double,
        centerLng: Double,
        category: String = "Semua"
    ): List<NearbyPlace> = withContext(Dispatchers.IO) {
        val collectedPlaces = mutableListOf<NearbyPlace>()

        // 1. Resolve real area and street info from Android Geocoder
        var primaryArea = "Area Terdekat"
        var primaryStreet = "Jalan Utama"
        val realAddresses = mutableListOf<Address>()

        try {
            @Suppress("DEPRECATION")
            val direct = geocoder.getFromLocation(centerLat, centerLng, 10)
            if (!direct.isNullOrEmpty()) {
                realAddresses.addAll(direct)
                val first = direct.first()
                primaryStreet = first.thoroughfare ?: first.featureName ?: "Jl. Raya Terdekat"
                primaryArea = first.subLocality ?: first.locality ?: first.subAdminArea ?: "Area Terdekat"
            }
        } catch (_: Throwable) {}

        // Add real addresses from direct Geocoder that have distinct coordinates
        var geoIndex = 0
        for (addr in realAddresses) {
            if (addr.hasLatitude() && addr.hasLongitude()) {
                val aLat = addr.latitude
                val aLng = addr.longitude
                val dist = calculateDistanceMeters(centerLat, centerLng, aLat, aLng)
                val placeName = addr.featureName ?: addr.thoroughfare ?: ""
                val fullAddress = addr.getAddressLine(0) ?: "${addr.thoroughfare ?: ""}, $primaryArea"

                if (placeName.isNotBlank() && !placeName.all { it.isDigit() }) {
                    val assignedCat = determineCategory(placeName, fullAddress)
                    collectedPlaces.add(
                        NearbyPlace(
                            id = "geo_${geoIndex++}",
                            name = placeName,
                            category = assignedCat,
                            latitude = aLat,
                            longitude = aLng,
                            distanceMeters = dist,
                            address = fullAddress
                        )
                    )
                }
            }
        }

        // 2. Fetch real POIs via OpenStreetMap Nominatim around the bounding box (~3 km radius)
        try {
            withTimeoutOrNull(4000L) {
                val osmPlaces = fetchOsmNearbyPlaces(centerLat, centerLng, primaryArea, category)
                collectedPlaces.addAll(osmPlaces)
            }
        } catch (_: Throwable) {}

        // 3. Query Geocoder by location name in bounding box for specific key amenities if needed
        if (collectedPlaces.size < 6) {
            try {
                val queries = when (category) {
                    "Pickup Point" -> listOf("Indomaret", "Alfamart", "Halte", "Stasiun", "Pasar")
                    "Transportasi" -> listOf("Stasiun", "Terminal", "Halte", "Shelter")
                    "Belanja" -> listOf("Indomaret", "Alfamart", "Pasar", "Supermarket")
                    "Kuliner" -> listOf("Restoran", "Warung", "Cafe", "Bakso")
                    "Fasilitas" -> listOf("SPBU", "Masjid", "Klinik", "Apotek", "Kantor")
                    else -> listOf("Indomaret", "Alfamart", "Masjid", "SPBU", "Stasiun", "Pasar")
                }

                val delta = 0.025 // ~2.5 km box
                val minLat = centerLat - delta
                val maxLat = centerLat + delta
                val minLng = centerLng - delta
                val maxLng = centerLng + delta

                for (q in queries) {
                    try {
                        @Suppress("DEPRECATION")
                        val results = geocoder.getFromLocationName(
                            "$q $primaryArea",
                            3,
                            minLat,
                            minLng,
                            maxLat,
                            maxLng
                        )
                        results?.forEach { addr ->
                            if (addr.hasLatitude() && addr.hasLongitude()) {
                                val dist = calculateDistanceMeters(centerLat, centerLng, addr.latitude, addr.longitude)
                                val name = addr.featureName ?: "$q $primaryArea"
                                val fullAddr = addr.getAddressLine(0) ?: "${addr.thoroughfare ?: ""}, $primaryArea"
                                collectedPlaces.add(
                                    NearbyPlace(
                                        id = "box_${name.hashCode()}_${addr.latitude}",
                                        name = name,
                                        category = determineCategory(name, fullAddr),
                                        latitude = addr.latitude,
                                        longitude = addr.longitude,
                                        distanceMeters = dist,
                                        address = fullAddr
                                    )
                                )
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        // 4. Generate designated "Pickup Point" variants for real discovered POIs
        // Every pickup point MUST inherit the exact real coordinates of that actual building/venue!
        val pickupList = mutableListOf<NearbyPlace>()
        val distinctPoiMap = mutableMapOf<String, NearbyPlace>()

        for (p in collectedPlaces) {
            val key = "${String.format(Locale.US, "%.5f", p.latitude)}_${String.format(Locale.US, "%.5f", p.longitude)}"
            if (!distinctPoiMap.containsKey(key)) {
                distinctPoiMap[key] = p
            }
        }

        var pickupCounter = 1
        for (poi in distinctPoiMap.values) {
            val cleanName = poi.name.removePrefix("Titik Jemput: ").trim()
            val pickupTitle = "Titik Jemput: $cleanName"
            pickupList.add(
                NearbyPlace(
                    id = "pickup_real_${pickupCounter++}_${poi.id}",
                    name = pickupTitle,
                    category = "Pickup Point",
                    latitude = poi.latitude, // Exact real coordinates of the POI!
                    longitude = poi.longitude, // Exact real coordinates of the POI!
                    distanceMeters = poi.distanceMeters,
                    address = poi.address
                )
            )
        }

        // Combine all items
        val allItems = mutableListOf<NearbyPlace>()
        allItems.addAll(collectedPlaces)
        allItems.addAll(pickupList)

        // 5. De-duplicate by exact name and coordinate proximity (< 10 meters)
        val uniqueItems = mutableListOf<NearbyPlace>()
        for (item in allItems) {
            val duplicate = uniqueItems.any { existing ->
                (existing.name.equals(item.name, ignoreCase = true) && existing.category == item.category) ||
                (calculateDistanceMeters(existing.latitude, existing.longitude, item.latitude, item.longitude) < 10 && existing.category == item.category)
            }
            if (!duplicate) {
                uniqueItems.add(item)
            }
        }

        // 6. Filter by requested category
        val filtered = if (category == "Semua") {
            uniqueItems
        } else {
            uniqueItems.filter { it.category.equals(category, ignoreCase = true) }
        }

        // Always sort by proximity to the current pin
        filtered.sortedBy { it.distanceMeters }
    }

    private suspend fun fetchOsmNearbyPlaces(
        centerLat: Double,
        centerLng: Double,
        primaryArea: String,
        category: String
    ): List<NearbyPlace> = withContext(Dispatchers.IO) {
        val result = Collections.synchronizedList(mutableListOf<NearbyPlace>())
        val delta = 0.035 // ~3.5 km bounding box
        val minLat = centerLat - delta
        val maxLat = centerLat + delta
        val minLng = centerLng - delta
        val maxLng = centerLng + delta

        val searchTerms = when (category) {
            "Pickup Point" -> listOf("indomaret", "alfamart", "stasiun", "halte", "pasar")
            "Transportasi" -> listOf("stasiun", "terminal", "halte", "shelter")
            "Belanja" -> listOf("indomaret", "alfamart", "supermarket", "pasar")
            "Kuliner" -> listOf("restoran", "cafe", "warung")
            "Fasilitas" -> listOf("spbu", "masjid", "klinik", "apotek")
            else -> listOf("indomaret", "alfamart", "stasiun", "spbu", "masjid", "restoran")
        }

        // Concurrent execution using OkHttp HTTP/2 multiplexing + connection pool
        coroutineScope {
            searchTerms.map { term ->
                async {
                    try {
                        val encodedTerm = URLEncoder.encode(term, "UTF-8")
                        val urlStr = "https://nominatim.openstreetmap.org/search?format=json&q=$encodedTerm&bounded=1&viewbox=$minLng,$maxLat,$maxLng,$minLat&limit=4&addressdetails=1"

                        // Using OkHttpManager with ConnectionPool, HTTP/2, Cache, Interceptors, Compression
                        val responseText = OkHttpManager.get(
                            url = urlStr,
                            headers = mapOf(
                                "User-Agent" to "GPM-VirtualMaps/1.0 (Android; OkHttp)",
                                "Accept" to "application/json"
                            )
                        )

                        val jsonArray = JSONArray(responseText)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val lat = obj.getString("lat").toDoubleOrNull() ?: continue
                            val lon = obj.getString("lon").toDoubleOrNull() ?: continue
                            val rawName = obj.optString("name")
                            val displayName = obj.optString("display_name")
                            val name = if (rawName.isNotBlank()) rawName else displayName.split(",").firstOrNull()?.trim() ?: term.replaceFirstChar { it.uppercase() }

                            val placeId = obj.optString("place_id", "${lat}_$lon")
                            val dist = calculateDistanceMeters(centerLat, centerLng, lat, lon)
                            val cat = determineCategory(name, displayName)

                            result.add(
                                NearbyPlace(
                                    id = "osm_$placeId",
                                    name = name,
                                    category = cat,
                                    latitude = lat,
                                    longitude = lon,
                                    distanceMeters = dist,
                                    address = displayName
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.d("NearbyRepository", "OkHttp OSM query skipped: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        result
    }

    private fun determineCategory(name: String, address: String): String {
        val lower = "${name.lowercase()} ${address.lowercase()}"
        return when {
            lower.contains("stasiun") || lower.contains("halte") || lower.contains("terminal") ||
            lower.contains("shelter") || lower.contains("transit") || lower.contains("pangkalan") -> "Transportasi"

            lower.contains("indomaret") || lower.contains("alfamart") || lower.contains("pasar") ||
            lower.contains("supermarket") || lower.contains("minimarket") || lower.contains("mall") ||
            lower.contains("toko") || lower.contains("mart") -> "Belanja"

            lower.contains("restoran") || lower.contains("rumah makan") || lower.contains("warung") ||
            lower.contains("cafe") || lower.contains("kopi") || lower.contains("kuliner") ||
            lower.contains("bakso") || lower.contains("kfc") || lower.contains("mcd") -> "Kuliner"

            lower.contains("spbu") || lower.contains("pertamina") || lower.contains("masjid") ||
            lower.contains("musholla") || lower.contains("klinik") || lower.contains("apotek") ||
            lower.contains("rumah sakit") || lower.contains("rsud") || lower.contains("bank") ||
            lower.contains("atm") || lower.contains("kantor") || lower.contains("puskesmas") -> "Fasilitas"

            lower.contains("pickup") || lower.contains("jemput") || lower.contains("drop off") ||
            lower.contains("lobby") || lower.contains("gerbang") -> "Pickup Point"

            else -> "Pickup Point"
        }
    }

    private fun calculateDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Int {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toInt()
    }
}
