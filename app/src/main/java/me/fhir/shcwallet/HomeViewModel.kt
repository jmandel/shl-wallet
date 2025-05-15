package me.fhir.shcwallet // Updated package

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.credentials.registry.provider.RegisterCredentialsRequest
import androidx.credentials.registry.provider.RegistryManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import org.json.JSONObject // Added for SHL payload parsing
import org.json.JSONArray
import org.json.JSONException // Import for try-catch

// Room Database imports
import me.fhir.shcwallet.data.db.AppDatabase
import me.fhir.shcwallet.data.db.CombinedShcEntity
import me.fhir.shcwallet.data.repository.ShcRepository

// FHIR Parser
import me.fhir.shcwallet.util.FhirShcParser // Import the new parser

// Import ShlProcessorService
import me.fhir.shcwallet.services.ShlProcessorService
import me.fhir.shcwallet.services.ProcessedShlResult // Import result data class

// Minimal state for now, can be expanded later if needed
data class HomeScreenUiState(
    val registrationStatus: String? = null,
    val message: String? = null,
    val shlInputUri: String = "",
    val shlProcessingLog: List<String> = emptyList(),
    val credentialManifestForRegistration: String? = null, // For the SHL-derived credential manifest
    // Summary data - Updated names and new field
    val totalShlsLoadedCount: Int = 0,          // Renamed from totalShcCount
    val totalVerifiablePayloadsCount: Int = 0, // Renamed from totalVcCount
    val totalNonVerifiableItemsCount: Int = 0, // New field
    val storedManifestsSummary: List<String> = emptyList() // e.g., "Manifest ID: 1 (for SHC ID: 1)"
)

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HomeScreenUiState())
    val uiState: StateFlow<HomeScreenUiState> = _uiState.asStateFlow()

    private var shcRepository: ShcRepository? = null
    private val shlProcessorService = ShlProcessorService() // Instantiate the service

    companion object {
         private const val TAG = "SHCWalletHomeVM"
         private const val RECIPIENT_NAME = "SHCWallet Android App"
         private const val GLOBAL_CREDENTIAL_REGISTRATION_ID = "SHCWALLET_GLOBAL_CREDENTIALS_V1"
    }

    private fun addShlLog(logMessage: String) {
        _uiState.value = _uiState.value.copy(
            shlProcessingLog = _uiState.value.shlProcessingLog + logMessage,
            message = logMessage 
        )
        Log.i(TAG, logMessage)
    }
    
    private fun addShlLogs(logs: List<String>) {
        _uiState.value = _uiState.value.copy(
            shlProcessingLog = _uiState.value.shlProcessingLog + logs,
            message = logs.lastOrNull() ?: _uiState.value.message
        )
        logs.forEach { Log.i(TAG, it) }
    }

    private fun clearShlLog() {
        _uiState.value = _uiState.value.copy(shlProcessingLog = emptyList())
    }

    fun onShlInputChange(uri: String, context: Context) {
        _uiState.value = _uiState.value.copy(shlInputUri = uri)

        // Basic check to see if it looks like an SHL that can be processed.
        // More sophisticated validation (e.g., regex) could be used here.
        val trimmedUri = uri.trim()
        if (trimmedUri.startsWith("shlink:/", ignoreCase = true) || 
            (trimmedUri.contains("#shlink:/", ignoreCase = true) && trimmedUri.length > "#shlink:/".length + 5)) { // Ensure some payload after #shlink:/
            
            addShlLog("Potential SHL detected in input: $trimmedUri. Attempting auto-processing...")
            
            viewModelScope.launch(Dispatchers.IO) {
                executeShlProcessing(trimmedUri, context)
            }
            // Clear the input field in the UI state immediately after initiating processing.
            _uiState.value = _uiState.value.copy(shlInputUri = "")
        }
    }

    // Helper function to extract display text from a CodeableConcept JSONObject
    private fun getTextFromCodeableConcept(codeableConcept: JSONObject?): String? {
        if (codeableConcept == null) return null
        val text = codeableConcept.optString("text", null)
        if (!text.isNullOrEmpty()) {
            return text
        }
        val codingArray = codeableConcept.optJSONArray("coding")
        if (codingArray != null) {
            for (i in 0 until codingArray.length()) {
                val coding = codingArray.optJSONObject(i)
                val display = coding?.optString("display", null)
                if (!display.isNullOrEmpty()) {
                    return display
                }
            }
        }
        return null
    }

    // Helper function to extract cost string from a costToBeneficiary.valueMoney JSONObject
    private fun getCostString(valueMoney: JSONObject?): String? {
        if (valueMoney == null) return null
        val extensionArray = valueMoney.optJSONArray("extension")
        if (extensionArray != null) {
            for (i in 0 until extensionArray.length()) {
                val ext = extensionArray.optJSONObject(i)
                if (ext?.optString("url") == "http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-BeneficiaryCostString-extension") {
                    val valueStr = ext.optString("valueString", null)
                    if (!valueStr.isNullOrEmpty()) return valueStr
                }
            }
        }
        val value = valueMoney.optDouble("value", -1.0)
        val currency = valueMoney.optString("currency", "")
        if (value != -1.0) {
            return "$value $currency".trim().ifEmpty { null }
        }
        return null
    }

    private suspend fun updateGlobalStoredCredentialsManifest(context: Context) {
        if (shcRepository == null) { 
            shcRepository = ShcRepository(AppDatabase.getDatabase(context.applicationContext).shcDao()) 
        }
        addShlLog("Rebuilding global stored credentials manifest...")
        try {
            val allShcs = shcRepository?.getAllCombinedShcs() ?: emptyList() 
            val credentialsArray = JSONArray()

            allShcs.forEach { shcEntity ->
                val credentialEntry = JSONObject()
                val shcIdForManifest = "shc_id_${shcEntity.id}"
                credentialEntry.put("id", shcIdForManifest)

                if (shcEntity.shcJsonString != null) {
                    // Use FhirShcParser to get details
                    val extractedDetails = FhirShcParser.extractShcDetailsForManifest(
                        shcEntity.shcJsonString, // Now non-null
                        shcIdForManifest,
                        ::addShlLog // Pass the logging function
                    )

                    credentialEntry.put("title", extractedDetails.title)
                    extractedDetails.subtitle?.let { credentialEntry.put("subtitle", it) }

                    val tagsArrayJson = JSONArray()
                    extractedDetails.tags.forEach { tagsArrayJson.put(it) }
                    credentialEntry.put("tags", tagsArrayJson)

                    val attributesArrayJson = JSONArray()
                    extractedDetails.attributes.forEach { attr ->
                        attributesArrayJson.put(JSONObject().apply {
                            put("name", attr.name)
                            attr.value?.let { put("value", it) } ?: putOpt("value", JSONObject.NULL)
                        })
                    }
                    credentialEntry.put("attributes", attributesArrayJson)
                    credentialsArray.put(credentialEntry)

                } else if (shcEntity.nonVerifiableFhirResourcesJson != null) {
                    addShlLog("Generating manifest entry for SHC ID ${shcEntity.id} from non-verifiable FHIR resources.")
                    credentialEntry.put("title", "Non-Verifiable Health Information")
                    credentialEntry.put("subtitle", "Contains general FHIR data (not digitally signed as a Health Card)")

                    val tagsArrayJson = JSONArray()
                    tagsArrayJson.put("Non-Verifiable")
                    tagsArrayJson.put("FHIR Data")
                    shcEntity.shlPayloadUrl?.let { if(it != "unknown_shl_url") tagsArrayJson.put("SHL Source") }
                    credentialEntry.put("tags", tagsArrayJson)

                    val attributesArrayJson = JSONArray()
                    attributesArrayJson.put(JSONObject().apply {
                        put("name", "Content Type")
                        put("value", "Non-Verifiable FHIR Resources")
                    })
                    shcEntity.shlPayloadUrl?.let {
                        if(it != "unknown_shl_url") {
                            attributesArrayJson.put(JSONObject().apply {
                                put("name", "Source URL")
                                put("value", it)
                            })
                        }
                    }
                    // Attempt to show count of resources if possible (simple parsing)
                    try {
                        val resources = JSONArray(shcEntity.nonVerifiableFhirResourcesJson)
                        attributesArrayJson.put(JSONObject().apply {
                            put("name", "Resource Count")
                            put("value", resources.length().toString())
                        })
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse nonVerifiableFhirResourcesJson for resource count for SHC ID ${shcEntity.id}")
                    }

                    credentialEntry.put("attributes", attributesArrayJson)
                    credentialsArray.put(credentialEntry)
                } else {
                    addShlLog("Skipping SHC ID ${shcEntity.id} for manifest generation as both shcJsonString and nonVerifiableFhirResourcesJson are null.")
                }
            }

            val globalManifest = JSONObject()
            globalManifest.put("credentials", credentialsArray)

            val globalManifestJsonString = globalManifest.toString(2)

            _uiState.value = _uiState.value.copy(credentialManifestForRegistration = globalManifestJsonString)
            addShlLog("Global stored credentials manifest updated. Contains ${allShcs.size} entries.")
            Log.i(TAG, "Global Manifest for Registration: $globalManifestJsonString")

        } catch (e: Exception) {
            addShlLog("Error rebuilding global stored credentials manifest: ${e.message ?: "Unknown error"}")
            Log.e(TAG, "Error rebuilding global manifest", e)
            _uiState.value = _uiState.value.copy(credentialManifestForRegistration = null) 
        }
    }

    // Private function containing the core SHL processing logic
    private suspend fun executeShlProcessing(shlUriToProcess: String, context: Context) {
        clearShlLog()
        // Ensure credentialManifestForRegistration is not reset if it was already populated by a previous op
        // _uiState.value = _uiState.value.copy(credentialManifestForRegistration = _uiState.value.credentialManifestForRegistration)
        // The line above is commented out as it seems redundant if we are not clearing it. If it should be cleared, adjust accordingly.

        if (shcRepository == null) {
            shcRepository = ShcRepository(AppDatabase.getDatabase(context.applicationContext).shcDao())
        }

        addShlLog("Handing off SHL processing to ShlProcessorService for: $shlUriToProcess")

        val result = shlProcessorService.processShl(shlUriToProcess, RECIPIENT_NAME)

        addShlLogs(result.logs) // Add all logs from the service

        if (result.success && (result.combinedShcJsonString != null || result.nonVerifiableFhirResourcesJsonString != null)) {
            val shlUrlForDb = result.shlPayloadUrl ?: "unknown_shl_url"
            if (shlUrlForDb == "unknown_shl_url") {
                addShlLog("Warning: SHL Payload URL is unknown. Cannot check for existing entry. Will attempt to insert as new.")
            }

            val existingEntity = if (shlUrlForDb != "unknown_shl_url") shcRepository?.findByShlPayloadUrl(shlUrlForDb) else null

            val entityToSave: CombinedShcEntity
            var operationType: String

            if (existingEntity != null) {
                addShlLog("Existing entry found for SHL URL: $shlUrlForDb (ID: ${existingEntity.id}). Updating content.")
                entityToSave = CombinedShcEntity(
                    id = existingEntity.id, // Use existing ID for update
                    shcJsonString = result.combinedShcJsonString,
                    nonVerifiableFhirResourcesJson = result.nonVerifiableFhirResourcesJsonString,
                    shlPayloadUrl = shlUrlForDb,
                    creationTimestamp = existingEntity.creationTimestamp // Preserve original creation timestamp
                )
                operationType = "Updated"
            } else {
                addShlLog("No existing entry for SHL URL: $shlUrlForDb. Inserting as new.")
                entityToSave = CombinedShcEntity(
                    // id is auto-generated for new entries
                    shcJsonString = result.combinedShcJsonString,
                    nonVerifiableFhirResourcesJson = result.nonVerifiableFhirResourcesJsonString,
                    shlPayloadUrl = shlUrlForDb
                    // creationTimestamp is set by default in the entity constructor
                )
                operationType = "Saved (New)"
            }

            var savedOrUpdatedEntityId: Long? = null
            try {
                shcRepository?.let {
                    val returnedId = it.insertCombinedShc(entityToSave)
                    savedOrUpdatedEntityId = if (existingEntity != null) entityToSave.id else returnedId
                }

                if (savedOrUpdatedEntityId != null) {
                    addShlLog("$operationType entity (ID: $savedOrUpdatedEntityId) to DB. VCs: ${if (result.combinedShcJsonString != null) "Present" else "Absent"}, Non-VCs: ${if (result.nonVerifiableFhirResourcesJsonString != null) "Present" else "Absent"}")
                    updateGlobalStoredCredentialsManifest(context)
                    loadStoredCredentialSummary(context)
                    // Automatically re-register the global credentials after successful processing
                    addShlLog("Automatically re-registering global credentials manifest after SHL processing...")
                    registerHobbitCredential(context) 
                } else {
                    addShlLog("Error: Failed to $operationType entity in database (repository returned null or unexpected ID).")
                }
            } catch (e: Exception) {
                addShlLog("Error $operationType entity in DB: ${e.message ?: "Unknown error"}")
                Log.e(TAG, "SHC DB $operationType error", e)
            }
        } else {
            addShlLog("SHL processing failed or produced no SHC data. Error: ${result.errorMessage ?: "Unknown error"}")
        }
    }

    fun processShl(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val shlUri = _uiState.value.shlInputUri.trim()
            if (shlUri.isBlank()) {
                addShlLog("Cannot process: SHL Input URI is empty.")
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Please enter an SHL link.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            executeShlProcessing(shlUri, context)
            // Optionally clear shlInputUri from state here if processShl is triggered by a button
            // and the user expectation is for the field to clear after button press.
            // For now, leaving it to be cleared by onShlInputChange if auto-processing is the primary flow.
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    fun registerHobbitCredential(context: Context) {
        viewModelScope.launch(Dispatchers.IO) { 
            try {
                val registryManager = RegistryManager.create(context)
                if (shcRepository == null) { 
                    shcRepository = ShcRepository(AppDatabase.getDatabase(context.applicationContext).shcDao())
                }

                updateGlobalStoredCredentialsManifest(context)
                
                val credentialManifestJsonString = uiState.value.credentialManifestForRegistration

                if (credentialManifestJsonString == null || JSONObject(credentialManifestJsonString).getJSONArray("credentials").length() == 0) {
                    val logMsg = "Error: No SHL-derived credentials available in the global manifest for registration. Manifest: $credentialManifestJsonString"
                    addShlLog(logMsg)
                    Log.e(TAG, logMsg)
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, "Process SHLs to add credentials to the manifest first.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                addShlLog("Using global SHL-derived manifest for registration: ${credentialManifestJsonString.take(500)}...")

                val type = "com.credman.IdentityCredential" 
                val id = GLOBAL_CREDENTIAL_REGISTRATION_ID 

                val credentialsData = credentialManifestJsonString.toByteArray(Charsets.UTF_8)

                    val matcherData = context.assets.open("matcher_rs.wasm").readBytes()
                    Log.i(
                        TAG,
                "Successfully loaded ${matcherData.size} bytes from matcher_rs.wasm for SHL registration"
                    )

                val request = object : RegisterCredentialsRequest(
                    type,
                    id,
                    credentialsData,
                    matcherData
                ) {}
                Log.i(TAG, "Attempting to register global credential manifest (ID: $id)...")
                val result = registryManager.registerCredentials(request)
                _uiState.value = _uiState.value.copy(message = "Global Credential Manifest Registration flow completed.")
                Log.i(TAG, "Got result for Global Credential Manifest: " + result.type + result.toString())
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Global Credential Manifest Registration: Success", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(message = "Global Credential Manifest Registration failed: ${e.message ?: "Unknown error"}")
                Log.e(TAG, "Global Credential Manifest Registration failed", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Error registering global manifest: ${e.message ?: "Default error message"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun loadStoredCredentialSummary(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            if (shcRepository == null) {
                shcRepository = ShcRepository(AppDatabase.getDatabase(context.applicationContext).shcDao())
            }
            try {
                val shlsLoadedCount = shcRepository?.getTotalShcCount() ?: 0 // Counts all entities with content
                val allShcs = shcRepository?.getAllCombinedShcs() ?: emptyList()
                
                var verifiablePayloadsCount = 0
                var nonVerifiableItemsCount = 0

                allShcs.forEach { shcEntity ->
                    // Count verifiable payloads (JWS/VCs)
                    shcEntity.shcJsonString?.let {
                        try {
                            val shcJson = JSONObject(it)
                            if (shcJson.has("verifiableCredential")) {
                                verifiablePayloadsCount += shcJson.getJSONArray("verifiableCredential").length()
                            }
                        } catch (e: JSONException) {
                            Log.e(TAG, "Error parsing stored SHC JSON for VC count: ${shcEntity.id}", e)
                        }
                    }

                    // Count non-verifiable items
                    shcEntity.nonVerifiableFhirResourcesJson?.let {
                        try {
                            val jsonArray = JSONArray(it) // Assumes it's stored as a JSON array string
                            nonVerifiableItemsCount += jsonArray.length()
                        } catch (e: JSONException) {
                            Log.e(TAG, "Error parsing nonVerifiableFhirResourcesJson for count: ${shcEntity.id}. Content: $it", e)
                            // Optionally, if it's not an array but a single object string that failed to parse as array, count as 1?
                            // For now, only counting items if successfully parsed as an array.
                        }
                    }
                }

                _uiState.value = _uiState.value.copy(
                    totalShlsLoadedCount = shlsLoadedCount,
                    totalVerifiablePayloadsCount = verifiablePayloadsCount,
                    totalNonVerifiableItemsCount = nonVerifiableItemsCount
                )
                addShlLog("Stored summary: $shlsLoadedCount SHLs, $verifiablePayloadsCount Verifiable, $nonVerifiableItemsCount Non-Verifiable.")

                updateGlobalStoredCredentialsManifest(context)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading stored credential summary", e)
                addShlLog("Error loading credential summary: ${e.message ?: "Unknown error"}")
            }
        }
    }
    
    fun refreshShcCount(context: Context) {
        viewModelScope.launch {
            if (shcRepository == null) {
                shcRepository = ShcRepository(AppDatabase.getDatabase(context.applicationContext).shcDao())
            }
            try {
                val count = shcRepository?.getTotalShcCount() ?: 0
                _uiState.value = _uiState.value.copy(totalShlsLoadedCount = count)
                addShlLog("Total SHCs in DB: $count")
            } catch (e: Exception) {
                addShlLog("Error fetching SHC count: ${e.message ?: "Unknown error"}")
                Log.e(TAG, "Error fetching SHC count", e)
            }
        }
    }

    fun clearAllCredentials(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            if (shcRepository == null) {
                shcRepository = ShcRepository(AppDatabase.getDatabase(context.applicationContext).shcDao())
            }
            try {
                val rowsDeleted = shcRepository?.clearAllCombinedShcs() ?: 0
                addShlLog("$rowsDeleted credentials cleared from the database.")
                
                // After clearing, update the manifest and summary data
                updateGlobalStoredCredentialsManifest(context)
                loadStoredCredentialSummary(context)

                launch(Dispatchers.Main) {
                    Toast.makeText(context, "All credentials cleared ($rowsDeleted removed).", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                val errorMessage = "Error clearing credentials: ${e.message ?: "Unknown error"}"
                addShlLog(errorMessage)
                Log.e(TAG, "Error clearing credentials from DB", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    init {
        // Initial load can be triggered from UI (e.g. MainActivity's onResume or similar)
        // Or, pass application context if using AndroidViewModel for an initial load here.
    }
} 