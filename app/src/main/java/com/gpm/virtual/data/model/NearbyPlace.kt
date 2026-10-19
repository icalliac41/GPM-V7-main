package com.gpm.virtual.data.model

data class NearbyPlace(
    val id: String,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val address: String = ""
)
