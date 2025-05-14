package me.fhir.shcwallet // Updated package

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import android.view.WindowManager
import android.graphics.Color
import androidx.fragment.app.FragmentActivity // Changed from AppCompatActivity
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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONObject // Added import for JSON manipulation
import org.json.JSONArray // Added for parsing VCs
import org.json.JSONException

// Define your custom credential type constant (should match service)
const val CUSTOM_TYPE_HOBBIT_ID = "com.credman.IdentityCredential" // Defined in service, ensure consistency if needed here

// Use FragmentActivity for BiometricPrompt and lifecycleScope
class GetCredentialActivity : FragmentActivity() {

    companion object {
        private const val TAG = "GetCredentialActivity"
        // Hobbit names for variety in UI display
        private val hobbitNames = listOf("Frodo Baggins", "Samwise Gamgee", "Merry Brandybuck", "Pippin Took", "Bilbo Baggins")
    }

    // UI Elements - class members to be accessible by helper
    private lateinit var titleTextView: TextView
    private lateinit var nameTextView: TextView
    private lateinit var knownForTextView: TextView
    private lateinit var detailsTextView: TextView // Re-purposing/renaming speciesTextView

    private lateinit var shcDao: ShcDao

    @OptIn(ExperimentalDigitalCredentialApi::class) // For DigitalCredential
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "GetCredentialActivity launched")

        // Initialize DAO
        shcDao = AppDatabase.getDatabase(applicationContext).shcDao()

        // Window adjustments for bottom-sheet like appearance
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        // Optional: If you want to ensure no dimming of the background if it occurs
        // window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // --- Programmatic UI Setup (remains as placeholder) ---
        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER // For children of LinearLayout
            setPadding(50, 50, 50, 50)
            setBackgroundColor(Color.WHITE) // Set white background for the content area
        }
        titleTextView = TextView(this).apply {
            text = "Confirm Credential Share" 
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0,0,0,30)
        }
        nameTextView = TextView(this).apply { 
            text = "Loading details..."
            textSize = 18f 
            setPadding(0,0,0,10)
        }
        knownForTextView = TextView(this).apply { 
            text = ""
            textSize = 18f
            setPadding(0,0,0,10)
        }
        detailsTextView = TextView(this).apply { // Renamed from speciesTextView
            text = ""
            textSize = 16f
            setPadding(0,0,0,30) 
        }

        val shareButton = Button(this).apply { text = "Share Selected Credential" }
        val cancelButton = Button(this).apply { text = "Cancel" }

        linearLayout.addView(titleTextView)
        linearLayout.addView(nameTextView)
        linearLayout.addView(knownForTextView)
        linearLayout.addView(detailsTextView)
        linearLayout.addView(shareButton)
        linearLayout.addView(cancelButton)
        setContentView(linearLayout)
        // --- End UI Setup ---

        // Retrieve the request using the new PendingIntentHandler method
        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        if (providerRequest == null) {
            Log.e(TAG, "No ProviderGetCredentialRequest found in intent.")
            finishWithError(GetCredentialUnknownException("Request not found in intent"))
            return
        }

        Log.d(TAG, "ProviderGetCredentialRequest.selectedEntryId: ${providerRequest.selectedEntryId}")
        Log.d(TAG, "CallingAppInfo: ${providerRequest.callingAppInfo?.packageName}")

        // Attempt to load and display details based on selectedEntryId
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
                        nameTextView.text = "Error: Invalid credential ID format."
                    }
                } else {
                    nameTextView.text = "Error: Malformed credential ID."
                }
            } catch (e: JSONException) {
                nameTextView.text = "Error: Cannot parse credential ID."
                Log.e(TAG, "Failed to parse selectedEntryId JSON for UI display", e)
            }
        } else {
            nameTextView.text = "Error: No credential selected."
        }

        shareButton.setOnClickListener {
            handleCredentialSelection(providerRequest)
        }
        cancelButton.setOnClickListener {
            Log.d(TAG, "User cancelled via button.")
            finishWithError(GetCredentialCancellationException("User cancelled via UI"))
        }
    }

    private fun loadAndDisplaySelectionDetails(dbId: Long, shcDbIdFullString: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val entity = shcDao.getCombinedShcById(dbId)
            withContext(Dispatchers.Main) {
                if (entity != null) {
                    val randomPolicyHolder = hobbitNames.random()
                    val randomInsuranceNumericId = (10000..99999).random()
                    val displayInsuranceId = "SHIRE-PLAN-$randomInsuranceNumericId"
                    val displayPlanType = "Mithril Tier Coverage"
                    val displayIssuer = "The Shire Council Mutual"

                    var vcCount = 0
                    var firstVcPrefix = "N/A"
                    try {
                        val shcJson = JSONObject(entity.shcJsonString)
                        if (shcJson.has("verifiableCredential")) {
                            val vcArray = shcJson.getJSONArray("verifiableCredential")
                            vcCount = vcArray.length()
                            if (vcCount > 0) {
                                firstVcPrefix = vcArray.getString(0).take(70) + "..."
                            }
                        }
                    } catch (e: JSONException) {
                        Log.e(TAG, "Error parsing SHC JSON for display details", e)
                        firstVcPrefix = "Error parsing VCs"
                    }

                    titleTextView.text = "Share ${randomPolicyHolder}'s Insurance?"
                    nameTextView.text = "Policy Holder: $randomPolicyHolder\nInsurance ID: $displayInsuranceId"
                    knownForTextView.text = "Plan: $displayPlanType\nIssuer: $displayIssuer"
                    detailsTextView.text = "DB ID: $shcDbIdFullString\nVCs: $vcCount\nFirst VC: $firstVcPrefix"

                } else {
                    titleTextView.text = "Error"
                    nameTextView.text = "Could not load details for ID: $shcDbIdFullString"
                    knownForTextView.text = ""
                    detailsTextView.text = "Please try again or cancel."
                }
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
                            val shcData = combinedShcEntity.shcJsonString // This is the "{\"verifiableCredential\":[...]}"
                            
                            // Construct the DigitalCredential with the SHC data directly
                            val digitalCredential = DigitalCredential(shcData)
                            
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
} 