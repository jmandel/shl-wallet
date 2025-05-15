package me.fhir.shcwallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ShcDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCombinedShc(shcEntity: CombinedShcEntity): Long

    @Query("SELECT * FROM combined_shc WHERE id = :shcId")
    suspend fun getCombinedShcById(shcId: Long): CombinedShcEntity?

    @Query("SELECT * FROM combined_shc WHERE shl_payload_url = :url LIMIT 1")
    suspend fun findByShlPayloadUrl(url: String): CombinedShcEntity?

    @Query("SELECT * FROM combined_shc ORDER BY creation_timestamp DESC")
    suspend fun getAllCombinedShcs(): List<CombinedShcEntity>

    @Query("SELECT COUNT(*) FROM combined_shc")
    suspend fun getTotalShcCount(): Int

    @Query("DELETE FROM combined_shc")
    suspend fun clearAll(): Int // Returns the number of rows deleted
} 