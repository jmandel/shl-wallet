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
import android.util.Base64 // Added for Base64URL decoding
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import org.json.JSONArray

// Nimbus JOSE for JWE Decryption
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.DirectDecrypter
import com.nimbusds.jose.EncryptionMethod // Required for algorithm constants
import com.nimbusds.jose.JWEAlgorithm // Required for algorithm constants
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jose.util.DeflateUtils
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream

// Room Database imports
import me.fhir.shcwallet.data.db.AppDatabase
import me.fhir.shcwallet.data.db.CombinedShcEntity
import me.fhir.shcwallet.data.db.ShcDao

// Minimal state for now, can be expanded later if needed
data class HomeScreenUiState(
    val registrationStatus: String? = null,
    val message: String? = null,
    val shlInputUri: String = "",
    val shlProcessingLog: List<String> = emptyList(),
    val credentialManifestForRegistration: String? = null, // For the SHL-derived credential manifest
    // Summary data
    val totalShcCount: Int = 0,
    val totalVcCount: Int = 0,
    val totalManifestCount: Int = 0,
    val storedManifestsSummary: List<String> = emptyList() // e.g., "Manifest ID: 1 (for SHC ID: 1)"
)

// Data class to hold the parsed SHL payload
data class ShlPayload(
    val url: String,
    val key: String,
    val exp: Long? = null,
    val flag: String? = null,
    val label: String? = null,
    val version: Int? = null // 'v' in JSON, maps to 'version'
)

// Data class for the manifest request body
data class ManifestRequestBody(
    val recipient: String,
    val passcode: String? = null, // Will be null if 'P' flag is not set or not handled yet
    val embeddedLengthMax: Int? = null // Optional
)

// Data classes for SHL Manifest File
data class ShlManifestFile(
    val files: List<ManifestEntry>
)

data class ManifestEntry(
    val contentType: String,
    val location: String? = null,
    val embedded: String? = null
)

class HomeViewModel : ViewModel() {
    // If you need state management later, uncomment these lines:
    private val _uiState = MutableStateFlow(HomeScreenUiState())
    val uiState: StateFlow<HomeScreenUiState> = _uiState.asStateFlow()

    private val collectedShcJsonStrings = mutableListOf<String>() // To store decrypted SHC JSONs
    private var shcDao: ShcDao? = null // DAO instance
    private var currentShlPayloadUrl: String? = null // To store the URL of the SHL being processed

    // Hobbit names for variety
    private val hobbitNames = listOf("Frodo Baggins", "Samwise Gamgee", "Merry Brandybuck", "Pippin Took", "Bilbo Baggins")

    companion object {
         private const val TAG = "SHCWalletHomeVM" // Use a specific tag
         private const val RECIPIENT_NAME = "SHCWallet Android App"
         private const val GLOBAL_CREDENTIAL_REGISTRATION_ID = "SHCWALLET_GLOBAL_CREDENTIALS_V1"
    }

    private fun addShlLog(logMessage: String) {
        _uiState.value = _uiState.value.copy(
            shlProcessingLog = _uiState.value.shlProcessingLog + logMessage,
            message = logMessage // Also update general message for immediate feedback
        )
        Log.i(TAG, logMessage)
    }

    private fun clearShlLog() {
        _uiState.value = _uiState.value.copy(shlProcessingLog = emptyList())
    }

    fun onShlInputChange(uri: String) {
        _uiState.value = _uiState.value.copy(shlInputUri = uri)
    }

    private suspend fun updateGlobalStoredCredentialsManifest(context: Context) {
        if (shcDao == null) {
            shcDao = AppDatabase.getDatabase(context.applicationContext).shcDao()
        }
        addShlLog("Rebuilding global stored credentials manifest...")
        try {
            val allShcs = shcDao?.getAllCombinedShcs() ?: emptyList()
            val credentialsArray = JSONArray()
            allShcs.forEach { shcEntity ->
                val credentialEntry = JSONObject()
                val shcIdForManifest = "shc_id_${shcEntity.id}"
                credentialEntry.put("id", shcIdForManifest) // Use DB id, prefixed
                
                val tagsArray = JSONArray()
                val attributesArray = JSONArray() // Prepare for attributes

                // --- Populate Tags (current C4DIC tag) ---
                val c4dicTag = "http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage"
                tagsArray.put(c4dicTag) // Assuming all SHCs processed get this for now for testing
                credentialEntry.put("tags", tagsArray)

                // --- Populate Attributes --- 
                // Standard attribute: SHC Database ID
                attributesArray.put(JSONObject().apply {
                    put("name", "SHC Database ID")
                    put("value", shcIdForManifest)
                })

                // If it's our target C4DIC coverage card, add Hobbit-themed attributes
                // This check is a bit redundant if all cards get the tag, but good for future flexibility
                var tagFound = false
                for (i in 0 until tagsArray.length()) {
                    if (tagsArray.optString(i) == c4dicTag) {
                        tagFound = true
                        break
                    }
                }
                if (tagFound) {
                    val randomHobbitName = hobbitNames.random()
                    val randomInsuranceNumericId = (10000..99999).random()

                    attributesArray.put(JSONObject().apply {
                        put("name", "Policy Holder")
                        put("value", randomHobbitName)
                    })
                    attributesArray.put(JSONObject().apply {
                        put("name", "Insurance ID")
                        put("value", "SHIRE-PLAN-$randomInsuranceNumericId")
                    })
                    attributesArray.put(JSONObject().apply {
                        put("name", "Plan Type")
                        put("value", "Mithril Tier Coverage")
                    })
                    attributesArray.put(JSONObject().apply {
                        put("name", "Issuer")
                        put("value", "The Shire Council Mutual")
                    })
                }
                credentialEntry.put("attributes", attributesArray)
                
                credentialsArray.put(credentialEntry)
            }

            val globalManifest = JSONObject()
            globalManifest.put("credentials", credentialsArray)
            // Optionally, add a top-level ID to the manifest itself, and other metadata
            // globalManifest.put("manifest_id", "shcwallet_master_manifest_v1.0")
            // globalManifest.put("description", "Aggregated SMART Health Cards from SHCWallet")

            val globalManifestJsonString = globalManifest.toString(2) // Pretty print

            _uiState.value = _uiState.value.copy(credentialManifestForRegistration = globalManifestJsonString)
            addShlLog("Global stored credentials manifest updated. Contains ${allShcs.size} entries.")
            Log.i(TAG, "Global Manifest for Registration: $globalManifestJsonString")

        } catch (e: Exception) {
            addShlLog("Error rebuilding global stored credentials manifest: ${e.message}")
            Log.e(TAG, "Error rebuilding global manifest", e)
            _uiState.value = _uiState.value.copy(credentialManifestForRegistration = null) // Clear on error
        }
    }

    fun processShl(context: Context) { // context might be needed later for network/db
        viewModelScope.launch(Dispatchers.IO) {
            clearShlLog()
            collectedShcJsonStrings.clear() // Clear previous SHC data
            _uiState.value = _uiState.value.copy(credentialManifestForRegistration = _uiState.value.credentialManifestForRegistration) // Preserve existing global manifest for now
            currentShlPayloadUrl = null // Clear previous SHL payload URL
            // Initialize DAO if not already
            if (shcDao == null) {
                shcDao = AppDatabase.getDatabase(context.applicationContext).shcDao()
            }

            val shlUri = _uiState.value.shlInputUri.trim()
            addShlLog("Starting SHL processing for: $shlUri")

            if (shlUri.isBlank()) {
                addShlLog("Error: SHL URI is empty.")
                return@launch
            }

            try {
                // 1. Extract payload string from SHL URI
                val payloadPart = when {
                    shlUri.startsWith("shlink:/") -> shlUri.substring("shlink:/".length)
                    shlUri.contains("#shlink:/") -> shlUri.substringAfter("#shlink:/")
                    else -> {
                        addShlLog("Error: Invalid SHL URI format. Must start with 'shlink:/' or contain '#shlink:/'.")
                        return@launch
                    }
                }

                if (payloadPart.isBlank()) {
                    addShlLog("Error: Extracted SHL payload is empty.")
                    return@launch
                }
                addShlLog("Extracted payload part: $payloadPart")

                // 2. Base64URL decode the payload
                val decodedJsonBytes = try {
                    // URL_SAFE for '-' and '_', NO_WRAP if the string is continuous, NO_PADDING if padding is omitted
                    Base64.decode(payloadPart, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                } catch (e: IllegalArgumentException) {
                    addShlLog("Error: Base64URL decoding failed. ${e.message}")
                    Log.e(TAG, "Base64URL decoding error", e)
                    return@launch
                }
                val decodedJsonString = String(decodedJsonBytes, Charsets.UTF_8)
                addShlLog("Decoded JSON string: $decodedJsonString")

                // 3. Parse JSON string into ShlPayload data class
                val jsonObj = JSONObject(decodedJsonString)
                val payload = ShlPayload(
                    url = jsonObj.getString("url"),
                    key = jsonObj.getString("key"),
                    exp = if (jsonObj.has("exp")) jsonObj.getLong("exp") else null,
                    flag = if (jsonObj.has("flag")) jsonObj.getString("flag") else null,
                    label = if (jsonObj.has("label")) jsonObj.getString("label") else null,
                    version = if (jsonObj.has("v")) jsonObj.getInt("v") else null
                )

                addShlLog("Successfully parsed SHL payload:")
                addShlLog("  URL: ${payload.url}")
                addShlLog("  Key: ${payload.key}")
                payload.flag?.let { addShlLog("  Flag: $it") }
                payload.label?.let { addShlLog("  Label: $it") }
                payload.exp?.let { addShlLog("  Expires: $it") }
                payload.version?.let { addShlLog("  Version: $it") }

                currentShlPayloadUrl = payload.url // Store for DB entry

                // Placeholder for next steps (network request, decryption, etc.)
                // addShlLog("Next steps: Perform network request based on flags...") // Remove or keep as needed

                val shlKey = payload.key // To be used for decryption later

                if (payload.flag?.contains("U") == true) {
                    addShlLog("Detected 'U' flag: Direct file download.")
                    val directFileUrl = "${payload.url}?recipient=${RECIPIENT_NAME.replace(" ", "%20")}"
                    addShlLog("Attempting GET from: $directFileUrl")
                    try {
                        val response = httpGet(directFileUrl)
                        addShlLog("Direct file response (encrypted): ${response.take(200)}...") // Log first 200 chars
                        // TODO: Decrypt and process 'response' using 'shlKey'
                        val decryptedPayload = decryptJwePayload(response, shlKey)
                        if (decryptedPayload != null) {
                            addShlLog("Successfully decrypted direct file payload:")
                            val logMsg = decryptedPayload.take(500) + if (decryptedPayload.length > 500) "..." else ""
                            addShlLog(logMsg)

                            // Check actual content for verifiableCredential array
                            try {
                                val jsonContent = JSONObject(decryptedPayload) // decryptedPayload is non-null here
                                if (jsonContent.has("verifiableCredential") && jsonContent.get("verifiableCredential") is JSONArray) {
                                    addShlLog("Decrypted content is a JSON object with a 'verifiableCredential' array. Adding for SHC aggregation.")
                                    collectedShcJsonStrings.add(decryptedPayload)
                                } else {
                                    addShlLog("Decrypted content is JSON, but does not appear to be a SMART Health Card (missing/invalid 'verifiableCredential' array).")
                                }
                            } catch (e: org.json.JSONException) {
                                addShlLog("Decrypted content is not valid JSON. Cannot determine if it's an SHC. ${e.message}")
                                Log.w(TAG, "Failed to parse decrypted direct file payload as JSON", e)
                            }
                        } else {
                            addShlLog("Failed to decrypt direct file payload.")
                        }
                    } catch (e: Exception) {
                        addShlLog("Error during direct file GET or decryption: ${e.message}")
                        Log.e(TAG, "Direct file GET or decryption error", e)
                    }
                } else {
                    addShlLog("No 'U' flag: Manifest download.")
                    val manifestRequestBody = ManifestRequestBody(recipient = RECIPIENT_NAME)
                    // TODO: Handle 'P' flag for passcode if (payload.flag?.contains("P") == true) { addShlLog("Passcode required - UI needed"); return@launch }

                    addShlLog("Attempting POST to manifest URL: ${payload.url}")
                    try {
                        val manifestJsonString = httpPost(payload.url, JSONObject().apply {
                            put("recipient", manifestRequestBody.recipient)
                            // putOpt("passcode", manifestRequestBody.passcode) // Add when passcode UI is ready
                            // putOpt("embeddedLengthMax", manifestRequestBody.embeddedLengthMax)
                        }.toString())
                        addShlLog("Manifest response: ${manifestJsonString.take(500)}...")

                        val manifestJson = JSONObject(manifestJsonString)
                        val filesArray = manifestJson.getJSONArray("files")
                        val manifestFiles = mutableListOf<ManifestEntry>()
                        for (i in 0 until filesArray.length()) {
                            val fileObj = filesArray.getJSONObject(i)
                            manifestFiles.add(
                                ManifestEntry(
                                    contentType = fileObj.getString("contentType"),
                                    location = if (fileObj.has("location")) fileObj.getString("location") else null,
                                    embedded = if (fileObj.has("embedded")) fileObj.getString("embedded") else null
                                )
                            )
                        }
                        val shlManifestFile = ShlManifestFile(files = manifestFiles)
                        addShlLog("Successfully parsed manifest. Found ${shlManifestFile.files.size} file entries.")

                        shlManifestFile.files.forEachIndexed { index, entry ->
                            addShlLog("Processing manifest file #${index + 1}: Type: ${entry.contentType}")
                            when {
                                entry.embedded != null -> {
                                    addShlLog("  File #${index + 1} is embedded (encrypted): ${entry.embedded.take(100)}...")
                                    // TODO: Decrypt and process 'entry.embedded' using 'shlKey'
                                    val decryptedEmbedded = decryptJwePayload(entry.embedded, shlKey)
                                    if (decryptedEmbedded != null) {
                                        addShlLog("  Successfully decrypted embedded file #${index + 1} (type: ${entry.contentType}):")
                                        val embeddedLogMsg = "    " + decryptedEmbedded.take(400) + if(decryptedEmbedded.length > 400) "..." else ""
                                        addShlLog(embeddedLogMsg)
                                        if (entry.contentType == "application/smart-health-card") {
                                            addShlLog("    SMART Health Card content found. Adding for aggregation.")
                                            // Store for aggregation: val shcJson = decryptedEmbedded
                                            decryptedEmbedded?.let { collectedShcJsonStrings.add(it) }
                                        }
                                    } else {
                                        addShlLog("  Failed to decrypt embedded file #${index + 1}.")
                                    }
                                }
                                entry.location != null -> {
                                    addShlLog("  File #${index + 1} at location: ${entry.location}. Attempting GET.")
                                    try {
                                        val fileResponse = httpGet(entry.location)
                                        addShlLog("  File #${index + 1} response (encrypted): ${fileResponse.take(200)}...")
                                        // TODO: Decrypt and process 'fileResponse' using 'shlKey'
                                        val decryptedFile = decryptJwePayload(fileResponse, shlKey)
                                        if (decryptedFile != null) {
                                            addShlLog("  Successfully decrypted file from location #${index + 1} (type: ${entry.contentType}):")
                                            val fileLogMsg = "    " + decryptedFile.take(400) + if(decryptedFile.length > 400) "..." else ""
                                            addShlLog(fileLogMsg)
                                            if (entry.contentType == "application/smart-health-card") {
                                                addShlLog("    SMART Health Card content found. Adding for aggregation.")
                                                // Store for aggregation: val shcJson = decryptedFile
                                                decryptedFile?.let { collectedShcJsonStrings.add(it) }
                                            }
                                        } else {
                                            addShlLog("  Failed to decrypt file from location #${index + 1}.")
                                        }
                                    } catch (e: Exception) {
                                        addShlLog("  Error GETting or decrypting file #${index + 1} from ${entry.location}: ${e.message}")
                                        Log.e(TAG, "Manifest file GET or decryption error", e)
                                    }
                                }
                                else -> {
                                    addShlLog("  File #${index + 1} has neither embedded content nor location.")
                                }
                            }
                        }

                    } catch (e: Exception) {
                        addShlLog("Error during manifest request/processing: ${e.message}")
                        Log.e(TAG, "Manifest processing error", e)
                    }
                }

                // After all processing, if SHCs were collected, combine them
                if (collectedShcJsonStrings.isNotEmpty()) {
                    addShlLog("Combining ${collectedShcJsonStrings.size} SMART Health Card(s)...")
                    try {
                        val combinedVcArray = JSONArray()
                        collectedShcJsonStrings.forEach { shcJsonString ->
                            val shcJson = JSONObject(shcJsonString)
                            if (shcJson.has("verifiableCredential")) {
                                val vcArray = shcJson.getJSONArray("verifiableCredential")
                                for (i in 0 until vcArray.length()) {
                                    combinedVcArray.put(vcArray.getString(i)) // Add each JWS string
                                }
                            } else {
                                addShlLog("Warning: Collected SHC JSON missing 'verifiableCredential' array: ${shcJsonString.take(100)}...")
                            }
                        }

                        if (combinedVcArray.length() > 0) {
                            val combinedShcFile = JSONObject()
                            combinedShcFile.put("verifiableCredential", combinedVcArray)
                            val combinedShcFileJsonString = combinedShcFile.toString(2) // Pretty print
                            addShlLog("Successfully combined SHCs into a single file object:")
                            addShlLog(combinedShcFileJsonString.take(1000) + if(combinedShcFileJsonString.length > 1000) "..." else "")

                            val shlUrlForDb = currentShlPayloadUrl ?: "unknown_shl_url"
                            val newShcEntity = CombinedShcEntity(
                                shcJsonString = combinedShcFileJsonString,
                                shlPayloadUrl = shlUrlForDb
                            )

                            var savedCombinedShcId: Long? = null
                            shcDao?.let {
                                val insertedShcId = it.insertCombinedShc(newShcEntity)
                                savedCombinedShcId = insertedShcId
                            }

                            if (savedCombinedShcId != null) {
                                addShlLog("Saved combined SHC (ID: $savedCombinedShcId) to DB.")
                                // Now, update the single global manifest
                                updateGlobalStoredCredentialsManifest(context)
                                // Reload summary data which now also updates the global manifest for UI consistency
                                loadStoredCredentialSummary(context)
                            } else {
                                addShlLog("Error: Failed to save combined SHC to database.")
                            }
                        } else {
                            addShlLog("No verifiable credentials found in the collected SHCs to combine.")
                        }
                    } catch (e: Exception) {
                        addShlLog("Error combining SHCs or saving to DB: ${e.message}")
                        Log.e(TAG, "SHC combination/DB error", e)
                    }
                } else {
                    addShlLog("No SMART Health Card content was successfully decrypted and collected from this SHL.")
                }

            } catch (e: Exception) {
                addShlLog("Error processing SHL: ${e.message}")
                Log.e(TAG, "SHL processing error", e)
            }
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    fun registerHobbitCredential(context: Context) {
        viewModelScope.launch(Dispatchers.IO) { // Use IO dispatcher for potential file/registry operations
            try {
                val registryManager = RegistryManager.create(context)
                if (shcDao == null) { // Ensure DAO is initialized
                    shcDao = AppDatabase.getDatabase(context.applicationContext).shcDao()
                }

                // Ensure the global manifest is up-to-date before registration
                updateGlobalStoredCredentialsManifest(context)
                // Wait for the manifest to be updated in the state if updateGlobalStoredCredentialsManifest is suspend and posts to state.
                // However, it directly modifies _uiState here if called from the same coroutine context.
                // For safety, especially if it were to become more complex or truly async, one might collect the state
                // or ensure the update completes. For now, direct call should be fine.

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

                val type = "com.credman.IdentityCredential" // Undocumented but required ??!
                val id = GLOBAL_CREDENTIAL_REGISTRATION_ID // Use the constant global ID

                val credentialsData = credentialManifestJsonString.toByteArray(Charsets.UTF_8)

                // Load matcher data from assets
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
                _uiState.value = _uiState.value.copy(message = "Global Credential Manifest Registration failed: ${e.message}")
                Log.e(TAG, "Global Credential Manifest Registration failed", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Error registering global manifest: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Helper function for HTTP GET requests
    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        // connection.setRequestProperty("Accept", "application/json") // Or other relevant types
        // connection.connectTimeout = 15000 // 15 seconds
        // connection.readTimeout = 15000 // 15 seconds

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()
            return response
        } else {
            val errorStream = connection.errorStream ?: connection.inputStream
            val errorResponse = errorStream?.bufferedReader()?.readText() ?: "No error body"
            throw Exception("HTTP GET failed with code $responseCode. Url: $urlString. Body: $errorResponse")
        }
    }

    // Helper function for HTTP POST requests
    private fun httpPost(urlString: String, jsonPayload: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        // connection.connectTimeout = 15000 // 15 seconds
        // connection.readTimeout = 15000 // 15 seconds

        OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
            writer.write(jsonPayload)
            writer.flush()
        }

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()
            return response
        } else {
            val errorStream = connection.errorStream ?: connection.inputStream
            val errorResponse = errorStream?.bufferedReader()?.readText() ?: "No error body"
            throw Exception("HTTP POST failed with code $responseCode. Url: $urlString. Body: $errorResponse")
        }
    }

    // Helper function for JWE Decryption using Nimbus JOSE+JWT
    private suspend fun decryptJwePayload(jweString: String, base64UrlKey: String): String? {
        addShlLog("Attempting to decrypt JWE payload...")
        try {
            // 1. Decode the Base64URL key
            // Nimbus key must be of specific length for A256GCM (32 bytes)
            val keyBytes = Base64.decode(base64UrlKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            if (keyBytes.size != 32) {
                addShlLog("Error: Decryption key must be 32 bytes for A256GCM, got ${keyBytes.size} bytes.")
                return null
            }

            // 2. Parse the JWE string
            val jweObject = JWEObject.parse(jweString)
            addShlLog("JWE Header: ${jweObject.header}")

            // 3. Check algorithm compatibility
            if (jweObject.header.algorithm != JWEAlgorithm.DIR || jweObject.header.encryptionMethod != EncryptionMethod.A256GCM) {
                addShlLog("Error: Unsupported JWE algorithm or encryption method. Expected DIR and A256GCM.")
                addShlLog("Got alg: ${jweObject.header.algorithm}, enc: ${jweObject.header.encryptionMethod}")
                return null
            }

            // 4. Create a DirectDecrypter
            val decrypter = DirectDecrypter(keyBytes)

            // 5. Decrypt
            jweObject.decrypt(decrypter)

            // 6. Handle potential compression (zip: DEF)
            val payloadBytes = if (jweObject.header.compressionAlgorithm?.name == "DEF") {
                addShlLog("JWE payload is DEFLATE compressed. Decompressing...")
                try {
                    // Using java.util.zip.Inflater for raw DEFLATE
                    val inflater = Inflater(true) // true for raw DEFLATE (no zlib header/checksum)
                    inflater.setInput(jweObject.payload.toBytes())
                    val outputStream = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        if (count == 0 && inflater.needsInput()) {
                            // Should not happen with complete payload
                            addShlLog("Error: Inflater needs input unexpectedly.")
                            inflater.end()
                            return null
                        }
                        outputStream.write(buffer, 0, count)
                    }
                    inflater.end()
                    val decompressedBytes = outputStream.toByteArray()
                    addShlLog("Successfully decompressed ${jweObject.payload.toBytes().size} to ${decompressedBytes.size} bytes.")
                    decompressedBytes
                } catch (e: Exception) {
                    addShlLog("Error during DEFLATE decompression: ${e.message}")
                    Log.e(TAG, "DEFLATE decompression error", e)
                    return null
                }
            } else {
                jweObject.payload.toBytes()
            }

            val decryptedString = String(payloadBytes, Charsets.UTF_8)
            addShlLog("JWE decryption successful. Content type from header: ${jweObject.header.contentType}")
            return decryptedString

        } catch (e: Exception) {
            addShlLog("JWE decryption failed: ${e.message}")
            Log.e(TAG, "JWE decryption error", e)
            return null
        }
    }

    // Call this when ViewModel is created, and after successful SHL processing
    fun loadStoredCredentialSummary(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            if (shcDao == null) {
                shcDao = AppDatabase.getDatabase(context.applicationContext).shcDao()
            }
            try {
                val shcCount = shcDao?.getTotalShcCount() ?: 0
                val allShcs = shcDao?.getAllCombinedShcs() ?: emptyList()
                // val allManifests = shcDao?.getAllCredentialManifests() ?: emptyList() // Removed

                var vcCount = 0
                allShcs.forEach { shcEntity ->
                    try {
                        val shcJson = JSONObject(shcEntity.shcJsonString)
                        if (shcJson.has("verifiableCredential")) {
                            vcCount += shcJson.getJSONArray("verifiableCredential").length()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing stored SHC JSON for VC count: ${shcEntity.id}", e)
                    }
                }

                // val manifestsSummary = allManifests.map { // Removed
                // "DB Manifest ID: ${it.id} (for SHC ID: ${it.combinedShcId}) - Registered ID: ${JSONObject(it.manifestJsonString).optString("id", "N/A")}"
                // }

                _uiState.value = _uiState.value.copy(
                    totalShcCount = shcCount,
                    totalVcCount = vcCount
                    // totalManifestCount = manifestCount, // Removed
                    // storedManifestsSummary = manifestsSummary // Removed
                )
                addShlLog("Stored credential summary loaded: $shcCount SHCs, $vcCount VCs.")

                // Also update/rebuild the global manifest whenever summary is loaded
                updateGlobalStoredCredentialsManifest(context)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading stored credential summary", e)
                addShlLog("Error loading credential summary: ${e.message}")
            }
        }
    }

    init {
        // Cannot call loadStoredCredentialSummary(context) here directly as ViewModel doesn't have context at init.
        // It should be called from the Activity/Fragment after the ViewModel is created.
        // Or, if Application context is acceptable and viewModel has it (e.g. via AndroidViewModel)
        // Consider calling updateGlobalStoredCredentialsManifest directly on init if context can be obtained,
        // or ensure loadStoredCredentialSummary is called early from MainActivity.
    }
} 