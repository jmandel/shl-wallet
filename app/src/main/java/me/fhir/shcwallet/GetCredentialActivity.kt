package me.fhir.shcwallet // Updated package

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
// Correct imports for the new flow
import androidx.credentials.CustomCredential
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.registry.provider.selectedEntryId


// Room Database imports for data fetching
import me.fhir.shcwallet.data.db.AppDatabase
import me.fhir.shcwallet.data.db.CombinedShcEntity // Added for type hint
import me.fhir.shcwallet.data.db.ShcDao
import me.fhir.shcwallet.util.SmartHealthCardParser // Added for parsing JWS

// UI Composables
import me.fhir.shcwallet.ui.GetCredentialScreenContent
import me.fhir.shcwallet.ui.theme.SHCWalletTheme // Added theme import

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONObject // Added import for JSON manipulation
import org.json.JSONArray // Added for parsing VCs
import org.json.JSONException

// Define your custom credential type constant (should match service)
const val CUSTOM_TYPE_HOBBIT_ID_GET_ACTIVITY = "com.credman.IdentityCredential" // Defined in service, ensure consistency if needed here

// Use FragmentActivity for BiometricPrompt and lifecycleScope
class GetCredentialActivity : FragmentActivity() {

    companion object {
        private const val TAG = "GetCredentialActivity"
        private const val C4DIC_PROFILE_URL = "http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage"
        private const val US_COVID19_IMMUNIZATION_PROFILE_URL = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-immunization" // Example for another type

    }

    // Compose State Variables
    private var screenTitle by mutableStateOf("Confirm Credential Share")
    // Removed individual attribute states: policyHolderName, insuranceId, planType, issuer, dbIdDisplay, vcCount, firstVcPrefix
    private var isLoading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)

    // New state for FHIR bundles display logic
    private var showFhirDetailsToggleState by mutableStateOf(false)
    private var fhirBundlesDisplayString by mutableStateOf<String?>(null)


    private lateinit var shcDao: ShcDao
    private var currentProviderRequest: ProviderGetCredentialRequest? = null

    @OptIn(ExperimentalDigitalCredentialApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "GetCredentialActivity launched")

        shcDao = AppDatabase.getDatabase(applicationContext).shcDao()

        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)

        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        currentProviderRequest = providerRequest // Store for the share button action

        if (providerRequest == null) {
            Log.e(TAG, "No ProviderGetCredentialRequest found in intent.")
            errorMessage = "Request not found in intent."
            isLoading = false
            updateContent() // Call a unified content update function
            finishWithError(GetCredentialUnknownException("Request not found in intent"))
            return
        }

        Log.d(TAG, "ProviderGetCredentialRequest.selectedEntryId: ${providerRequest.selectedEntryId}")
        Log.d(TAG, "CallingAppInfo: ${providerRequest.callingAppInfo?.packageName}")

        val selectedEntryIdJsonString = providerRequest.selectedEntryId
        if (selectedEntryIdJsonString != null) {
            try {
                val idJsonObject = JSONObject(selectedEntryIdJsonString)
                val shcDbIdString = idJsonObject.optString("id")
                if (shcDbIdString.startsWith("shc_id_")) {
                    val numericIdString = shcDbIdString.substring("shc_id_".length)
                    val dbId = numericIdString.toLongOrNull()
                    if (dbId != null) {
                        loadAndDisplaySelectionDetails(dbId, shcDbIdString)
                    } else {
                        errorMessage = "Invalid credential ID format."
                        isLoading = false
                    }
                } else {
                    errorMessage = "Malformed credential ID."
                    isLoading = false
                }
            } catch (e: JSONException) {
                errorMessage = "Cannot parse credential ID."
                isLoading = false
                Log.e(TAG, "Failed to parse selectedEntryId JSON for UI display", e)
            }
        } else {
            errorMessage = "No credential selected by framework."
            isLoading = false
        }
        updateContent() // Initial content update call
    }

    private fun updateContent() {
        setContent {
            SHCWalletTheme {
                GetCredentialScreenContent(
                    title = screenTitle,
                    onShareClick = {
                        currentProviderRequest?.let { req -> handleCredentialSelection(req) }
                            ?: finishWithError(GetCredentialUnknownException("Request became null before sharing"))
                    },
                    showFhirDetailsToggleState = showFhirDetailsToggleState,
                    onShowFhirDetailsToggleChanged = {
                        showFhirDetailsToggleState = it
                        // The dialog visibility is now directly tied to this state
                    },
                    showFhirBundlesDialog = showFhirDetailsToggleState, // Dialog visibility controlled by toggle
                    fhirBundlesString = fhirBundlesDisplayString,
                    onDismissFhirDialog = { showFhirDetailsToggleState = false }, // Dismissing dialog also turns off toggle
                    isLoading = isLoading,
                    errorMessage = errorMessage
                )
            }
        }
    }

    private fun determineCardTitleFromBundle(fhirBundleJson: JSONObject?): String {
        if (fhirBundleJson == null) return "Credential Details"

        val entries = fhirBundleJson.optJSONArray("entry")
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i)
                val resource = entry?.optJSONObject("resource")
                if (resource != null) {
                    val resourceType = resource.optString("resourceType")
                    // Check for C4DIC Coverage first
                    if (resourceType == "Coverage") {
                        val meta = resource.optJSONObject("meta")
                        val profiles = meta?.optJSONArray("profile")
                        if (profiles != null) {
                            for (k in 0 until profiles.length()) {
                                if (profiles.optString(k) == C4DIC_PROFILE_URL) {
                                    return "Insurance Card" // Specific title for C4DIC
                                }
                            }
                        }
                    }
                    // Example for another type like Immunization
                    if (resourceType == "Immunization") {
                         val meta = resource.optJSONObject("meta")
                        val profiles = meta?.optJSONArray("profile")
                        if (profiles != null) {
                            for (k in 0 until profiles.length()) {
                                // This is a generic check, specific US Core COVID immunization might have a more specific profile
                                if (profiles.optString(k) == US_COVID19_IMMUNIZATION_PROFILE_URL) {
                                     return "Immunization Record" // Specific title for Immunization
                                }
                            }
                        }
                        // Fallback for any immunization
                        return "Immunization"
                    }
                    // Could add more resource type checks here (e.g., "Patient" -> "Patient Summary")
                }
            }
        }
        return "SMART Health Card" // Generic fallback
    }


    private fun loadAndDisplaySelectionDetails(dbId: Long, shcDbIdFullString: String) {
        isLoading = true
        errorMessage = null
        // dbIdDisplay removed

        lifecycleScope.launch(Dispatchers.IO) {
            val entity = shcDao.getCombinedShcById(dbId)
            var determinedTitle = "Credential Details" // Default title
            val allBundlesString = StringBuilder()

            if (entity != null) {
                if (entity.shcJsonString != null) {
                    try {
                        val shcJson = JSONObject(entity.shcJsonString) // Safe now
                        val vcArray = shcJson.optJSONArray("verifiableCredential")

                        if (vcArray != null && vcArray.length() > 0) {
                            // Try to determine title from the first bundle
                            val firstJwsString = vcArray.optString(0)
                            if (firstJwsString.isNotEmpty()) {
                                val firstFhirBundleJson = SmartHealthCardParser.fromJws(firstJwsString)
                                determinedTitle = determineCardTitleFromBundle(firstFhirBundleJson)
                            }

                            for (i in 0 until vcArray.length()) {
                                val jwsString = vcArray.optString(i)
                                if (jwsString.isNotEmpty()) {
                                    val fhirBundleJson = SmartHealthCardParser.fromJws(jwsString)
                                    if (fhirBundleJson != null) {
                                        allBundlesString.append("FHIR Bundle ${i + 1} of ${vcArray.length()}:\n")
                                        allBundlesString.append(fhirBundleJson.toString(2))
                                        allBundlesString.append("\n\n")
                                        // Removed detailed attribute extraction here (policy holder, insurance id, etc.)
                                    } else {
                                        allBundlesString.append("Failed to parse JWS to FHIR Bundle for VC #${i + 1}\n\n")
                                    }
                                }
                            }
                        } else {
                            determinedTitle = "Empty Verifiable Credential"
                            allBundlesString.append("No verifiable credentials found in this entry.")
                        }
                    } catch (e: JSONException) {
                        Log.e(TAG, "Error parsing SHC JSON for display details", e)
                        determinedTitle = "Parsing Error"
                        withContext(Dispatchers.Main) { errorMessage = "Could not parse credential content." }
                    }
                } else {
                    // Handle case where shcJsonString is null (e.g. only non-verifiable resources)
                    determinedTitle = "No Verifiable Credential Data"
                    allBundlesString.append("This entry does not contain verifiable credential data (SHC JSON is null).")
                    // Check for non-verifiable resources to potentially display something about them
                    if (entity.nonVerifiableFhirResourcesJson != null) {
                        try {
                            val nonVerifiableArray = JSONArray(entity.nonVerifiableFhirResourcesJson)
                            allBundlesString.append("\n\nContains ${nonVerifiableArray.length()} non-verifiable FHIR resource(s).")
                            // Optionally, pretty print them too if desired, similar to VCs
                        } catch (e: JSONException) {
                            Log.e(TAG, "Error parsing nonVerifiableFhirResourcesJson for display", e)
                            allBundlesString.append("\n\nError displaying non-verifiable FHIR resources.")
                        }
                    }
                }
            } else {
                 withContext(Dispatchers.Main) { errorMessage = "Could not load details for ID: $shcDbIdFullString." }
                determinedTitle = "Error Loading"
            }

            withContext(Dispatchers.Main) {
                isLoading = false
                screenTitle = determinedTitle // Set the determined title
                // Removed updates for deleted attribute states
                fhirBundlesDisplayString = if(allBundlesString.isNotEmpty()) allBundlesString.toString() else "No FHIR Bundles to display or parse."
                updateContent() // Refresh UI with loaded data
            }
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private fun handleCredentialSelection(providerRequest: ProviderGetCredentialRequest) {
        val selectedEntryIdJsonString = providerRequest.selectedEntryId

        if (selectedEntryIdJsonString == null) {
            Log.e(TAG, "selectedEntryId is null.")
            finishWithError(GetCredentialUnknownException("No credential was selected by the framework."))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonObject = JSONObject(selectedEntryIdJsonString)
                val shcDbIdString = jsonObject.optString("id") // e.g., "shc_id_1"

                if (shcDbIdString.startsWith("shc_id_")) {
                    val numericIdString = shcDbIdString.substring("shc_id_".length)
                    val dbId = numericIdString.toLongOrNull()

                    if (dbId != null) {
                        val combinedShcEntity = shcDao.getCombinedShcById(dbId)

                        if (combinedShcEntity != null) {
                            // Start with the VCs part, which might be null
                            val finalJsonToSend = combinedShcEntity.shcJsonString?.let { JSONObject(it) } ?: JSONObject()

                            // Add non-verifiable FHIR resources if they exist
                            combinedShcEntity.nonVerifiableFhirResourcesJson?.let {
                                try {
                                    val nonVerifiableArray = JSONArray(it)
                                    finalJsonToSend.put("nonVerifiableFhirResources", nonVerifiableArray)
                                    Log.i(TAG, "Added ${nonVerifiableArray.length()} non-verifiable FHIR resources to the output.")
                                } catch (e: JSONException) {
                                    Log.e(TAG, "Failed to parse nonVerifiableFhirResourcesJson from DB or add to output json", e)
                                    // Decide if this is a critical error. For now, continue without them.
                                }
                            }
                            
                            // Construct the DigitalCredential with the potentially augmented JSON
                            val digitalCredential = DigitalCredential(finalJsonToSend.toString())
                            
                            val response = GetCredentialResponse(digitalCredential)

                            withContext(Dispatchers.Main) {
                                val resultIntent = Intent()
                                PendingIntentHandler.setGetCredentialResponse(resultIntent, response)
                                setResult(Activity.RESULT_OK, resultIntent)
                                Log.i(TAG, "Returning selected SHC (DB ID: $dbId) data via PendingIntentHandler.")
                                finish()
                            }
                        } else {
                            Log.e(TAG, "SHC Entity not found in DB for ID: $dbId")
                            withContext(Dispatchers.Main) {
                                finishWithError(GetCredentialUnknownException("Selected credential data not found in database."))
                            }
                        }
                    } else {
                        Log.e(TAG, "Could not parse numeric ID from: $numericIdString")
                        withContext(Dispatchers.Main) {
                            finishWithError(GetCredentialUnknownException("Malformed credential ID format after parsing."))
                        }
                    }
                } else {
                    Log.e(TAG, "selectedEntryId JSON does not contain a valid 'id' starting with 'shc_id_': $shcDbIdString")
                    withContext(Dispatchers.Main) {
                        finishWithError(GetCredentialUnknownException("Invalid selected credential ID format."))
                    }
                }
            } catch (e: JSONException) {
                Log.e(TAG, "Failed to parse selectedEntryId JSON: $selectedEntryIdJsonString", e)
                withContext(Dispatchers.Main) {
                    finishWithError(GetCredentialUnknownException("Selected credential ID is not valid JSON."))
                }
            } catch (e: Exception) {
                Log.e(TAG, "An unexpected error occurred during credential selection handling", e)
                withContext(Dispatchers.Main) {
                    finishWithError(GetCredentialUnknownException("An unexpected error occurred: ${e.message}"))
                }
            }
        }
    }

    private fun finishWithError(exception: GetCredentialException) {
         Log.e(TAG, "Finishing with error: ${exception.type} - ${exception.message}", exception)
         Toast.makeText(this, "Error: ${exception.message ?: exception.type}", Toast.LENGTH_LONG).show()

         val resultIntent = Intent()
         PendingIntentHandler.setGetCredentialException(resultIntent, exception)
         setResult(Activity.RESULT_OK, resultIntent)
         finish()
    }

    // Removed getTextFromCodeableConcept as it's no longer directly used here for display attributes
} 