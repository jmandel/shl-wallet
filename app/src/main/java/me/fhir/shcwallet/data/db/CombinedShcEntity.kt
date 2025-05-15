package me.fhir.shcwallet.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(
    tableName = "combined_shc",
    indices = [Index(value = ["shl_payload_url"])]
)
data class CombinedShcEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "shc_json_string") val shcJsonString: String?, // Nullable if only non-VCs are present
    @ColumnInfo(name = "non_verifiable_fhir_resources_json") val nonVerifiableFhirResourcesJson: String? = null, // New column
    @ColumnInfo(name = "shl_payload_url") val shlPayloadUrl: String?,
    @ColumnInfo(name = "creation_timestamp") val creationTimestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
) 