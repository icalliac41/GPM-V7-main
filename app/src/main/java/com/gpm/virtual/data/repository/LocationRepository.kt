package com.gpm.virtual.data.repository

import com.gpm.virtual.data.db.FavoriteDao
import com.gpm.virtual.data.model.FavoriteLocation
import com.gpm.virtual.data.preferences.GpsConfig
import com.gpm.virtual.data.preferences.GpsPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class LocationRepository(
    private val favoriteDao: FavoriteDao,
    private val preferences: GpsPreferences
) {
    val allFavorites: Flow<List<FavoriteLocation>> = favoriteDao.getAllFavorites()
    val configFlow: StateFlow<GpsConfig> = preferences.configFlow

    fun getConfig(): GpsConfig = preferences.getConfig()

    fun updateConfig(update: (GpsConfig) -> GpsConfig) {
        preferences.updateConfig(update)
    }

    suspend fun saveFavorite(name: String, latitude: Double, longitude: Double, address: String = ""): Long {
        val favorite = FavoriteLocation(
            name = name.ifBlank { "Custom Location (${String.format("%.4f, %.4f", latitude, longitude)})" },
            latitude = latitude,
            longitude = longitude,
            address = address
        )
        return favoriteDao.insertFavorite(favorite)
    }

    suspend fun deleteFavorite(favorite: FavoriteLocation) {
        favoriteDao.deleteFavorite(favorite)
    }

    suspend fun deleteFavoriteById(id: Long) {
        favoriteDao.deleteById(id)
    }
}
