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
// import androidx.credentials.provider.GetCustomCredentialOption // We expect this option type

// Imports for GMS workaround
import com.google.android.gms.identitycredentials.Credential as GmsCredential
import com.google.android.gms.identitycredentials.GetCredentialResponse as GmsGetCredentialResponse
import com.google.android.gms.identitycredentials.IntentHelper

import org.json.JSONObject // Added import for JSON manipulation

// Define your custom credential type constant (should match service)
//const val CUSTOM_TYPE_HOBBIT_ID = "hobbit-id" // Defined in service, ensure consistency if needed here
const val CUSTOM_TYPE_HOBBIT_ID = "com.credman.IdentityCredential" // Defined in service, ensure consistency if needed here

// Use FragmentActivity for BiometricPrompt
class GetCredentialActivity : FragmentActivity() {

    companion object {
        private const val TAG = "GetCredentialActivity"
    }

    private var providerRequest: ProviderGetCredentialRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "GetCredentialActivity launched")

        // Window adjustments for bottom-sheet like appearance
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        // Optional: If you want to ensure no dimming of the background if it occurs
        // window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // --- Programmatic UI Setup ---
        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER // For children of LinearLayout
            setPadding(50, 50, 50, 50)
            setBackgroundColor(Color.WHITE) // Set white background for the content area
        }
        val titleTextView = TextView(this).apply {
            text = "Share Your Hobbit ID?"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0,0,0,40)
        }
        val nameTextView = TextView(this).apply { text = "Name: Bilbo Baggins"; textSize = 18f }
        val knownForTextView = TextView(this).apply { text = "Known For: Finding The One Ring, Burglar"; textSize = 18f}
        val speciesTextView = TextView(this).apply { text = "Species: Hobbit"; textSize = 18f; setPadding(0,0,0,40) }

        val shareButton = Button(this).apply { text = "Share" }
        val cancelButton = Button(this).apply { text = "Cancel" }

        linearLayout.addView(titleTextView)
        linearLayout.addView(nameTextView)
        linearLayout.addView(knownForTextView)
        linearLayout.addView(speciesTextView)
        linearLayout.addView(shareButton)
        linearLayout.addView(cancelButton)
        setContentView(linearLayout)
        // --- End UI Setup ---

        // Retrieve the request using the new PendingIntentHandler method
        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        if (request == null) {
            Log.e(TAG, "No ProviderGetCredentialRequest found in intent.")
            finishWithError(GetCredentialUnknownException("Request not found in intent"))
            return
        }

        val entryId = intent.getStringExtra("entry_id")
        Log.d(TAG, "Launched for entry ID: $entryId from RP: ${request.callingAppInfo?.packageName}")

        providerRequest = request

        shareButton.setOnClickListener {
            proceedWithHardcodedResponse(request)
        }
        cancelButton.setOnClickListener {
            Log.d(TAG, "User cancelled via button.")
            finishWithError(GetCredentialCancellationException("User cancelled"))
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private fun proceedWithHardcodedResponse(providerRequest: ProviderGetCredentialRequest) {
        // 1. Define simple Bilbo data as a JSONObject
        val bilboSimpleJsonObject = JSONObject()
        bilboSimpleJsonObject.put("name", "Bilbo Baggins")
        bilboSimpleJsonObject.put("species", "Hobbit")
        bilboSimpleJsonObject.put("residence", "Bag End, The Shire")
        bilboSimpleJsonObject.put("occupation", "Burglar (retired)")
        bilboSimpleJsonObject.put("famous_for", listOf("Finding the One Ring", "There and Back Again"))

        // 2. Construct the outer JSON for Jetpack DigitalCredential
        val jetpackDigitalCredentialOuterJsonObject = JSONObject()
//        jetpackDigitalCredentialOuterJsonObject.put("protocol", "smart-health-cards")
        jetpackDigitalCredentialOuterJsonObject.put("data", bilboSimpleJsonObject) // Use the simple Bilbo JSONObject

//        val jetpackDigitalCredentialOuterJsonString = jetpackDigitalCredentialOuterJsonObject.toString()
        val jetpackDigitalCredentialOuterJsonString = bilboSimpleJsonObject.toString()

        val resultIntent = Intent()

        // Jetpack Part
        // The DigitalCredential constructor takes the *entire* outer JSON string
        val jetpackDigitalCredential = DigitalCredential(jetpackDigitalCredentialOuterJsonString)
        val jetpackResponse = GetCredentialResponse(jetpackDigitalCredential)
        PendingIntentHandler.setGetCredentialResponse(resultIntent, jetpackResponse)

        setResult(Activity.RESULT_OK, resultIntent)
        Log.i(TAG, "Returning Smart Health Cards like response via PendingIntentHandler (Jetpack).")
        finish()
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