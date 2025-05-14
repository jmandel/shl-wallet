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

// Minimal state for now, can be expanded later if needed
data class HomeScreenUiState(
    val registrationStatus: String? = null,
    val message: String? = null,
    val shlInputUri: String = "",
    val shlProcessingLog: List<String> = emptyList()
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

class HomeViewModel : ViewModel() {
    // If you need state management later, uncomment these lines:
    private val _uiState = MutableStateFlow(HomeScreenUiState())
    val uiState: StateFlow<HomeScreenUiState> = _uiState.asStateFlow()

    companion object {
         private const val TAG = "SHCWalletHomeVM" // Use a specific tag
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

    fun processShl(context: Context) { // context might be needed later for network/db
        viewModelScope.launch(Dispatchers.IO) {
            clearShlLog()
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

                // Placeholder for next steps (network request, decryption, etc.)
                addShlLog("Next steps: Perform network request based on flags...")

                // Example: Check for 'U' flag
                if (payload.flag?.contains("U") == true) {
                    addShlLog("Detected 'U' flag: Direct file download.")
                    // TODO: Implement direct file download logic
                } else {
                    addShlLog("No 'U' flag: Manifest download.")
                    // TODO: Implement manifest download logic
                }
                if (payload.flag?.contains("P") == true) {
                    addShlLog("Detected 'P' flag: Passcode required.")
                    // TODO: Implement passcode handling
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
                val type = "me.fhir.shcwallet.HOBBIT_ID" // Updated type to match new package
                val id = "hobbit_registry_01"

                // Simple hardcoded credential data (e.g., JSON)
                val hobbitDataJson = "{\"name\": \"Bilbo Baggins\", \"species\": \"Hobbit\"}"
                val credentialsData = hobbitDataJson.toByteArray(Charsets.UTF_8)

                // Load matcher data from assets
                    val matcherData = context.assets.open("matcher_rs.wasm").readBytes()
                    Log.i(
                        TAG,
                        "Successfully loaded ${matcherData.size} bytes from matcher_rs.wasm"
                    )

                val request = object : RegisterCredentialsRequest(
                    // type,
                    //DigitalCredential.TYPE_DIGITAL_CREDENTIAL,
                    "com.credman.IdentityCredential",
                    //"openid4vpBAD",
                    id,
                    credentialsData,
                    matcherData
                ) {}
                Log.i(TAG, "Attempting to register Hobbit credential...")
                val result = registryManager.registerCredentials(request)
                _uiState.value = _uiState.value.copy(message = "Registration flow completed.")
                Log.i("TAG", "Got result " + result.type + result.toString())
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "3 Hobbit Credential Registration: Success", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(message = "Registration failed: ${e.message}")
                Log.e(TAG, "Registration failed", e) // Using TAG for logging
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Error registering Hobbit credential: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
} 