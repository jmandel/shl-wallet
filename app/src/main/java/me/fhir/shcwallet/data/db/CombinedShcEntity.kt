package me.fhir.shcwallet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "combined_shc")
data class CombinedShcEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val shcJsonString: String, // Stores the entire JSON string of the combined SHC
    val shlPayloadUrl: String, // The 'url' from the original SHL payload, for reference
    val creationTimestamp: Long = System.currentTimeMillis()
) 