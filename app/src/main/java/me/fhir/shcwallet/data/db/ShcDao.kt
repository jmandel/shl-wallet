package me.fhir.shcwallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ShcDao {
    @Insert
    suspend fun insertCombinedShc(shcEntity: CombinedShcEntity): Long

    @Query("SELECT * FROM combined_shc WHERE id = :shcId")
    suspend fun getCombinedShcById(shcId: Long): CombinedShcEntity?

    @Query("SELECT * FROM combined_shc ORDER BY creationTimestamp DESC")
    suspend fun getAllCombinedShcs(): List<CombinedShcEntity>

    @Query("SELECT COUNT(*) FROM combined_shc")
    suspend fun getTotalShcCount(): Int

} 