package com.gpm.virtual.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gpm.virtual.data.model.FavoriteLocation
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_locations ORDER BY createdAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteLocation>>

    @Query("SELECT * FROM favorite_locations WHERE id = :id LIMIT 1")
    suspend fun getFavoriteById(id: Long): FavoriteLocation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteLocation): Long

    @Update
    suspend fun updateFavorite(favorite: FavoriteLocation)

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteLocation)

    @Query("DELETE FROM favorite_locations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
