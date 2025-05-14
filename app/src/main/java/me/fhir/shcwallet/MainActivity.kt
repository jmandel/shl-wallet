package me.fhir.shcwallet

import android.app.PendingIntent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.fhir.shcwallet.ui.theme.SHCWalletTheme // Corrected theme import

class MainActivity : ComponentActivity() { // Changed to ComponentActivity
    private val TAG = "SHCWalletMain"
    private val homeViewModel: HomeViewModel by viewModels()

    private lateinit var issuanceActivityResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "SHC Wallet MainActivity created with Compose.")

        issuanceActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            Log.i(TAG, "Issuance activity result: ${result.resultCode}")
            if (result.data != null) {
                Log.i(TAG, "Issuance activity result data: ${result.data.toString()}")
            }
        }

        setContent {
            SHCWalletTheme { // Apply your app theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RegisterCredentialScreen(homeViewModel = homeViewModel)
                }
            }
        }
    }

    // This function can be called if the ViewModel needs to launch an intent sender
    fun launchIssuanceIntent(pendingIntent: PendingIntent) {
        try {
            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            issuanceActivityResultLauncher.launch(intentSenderRequest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build or launch issuance intent", e)
        }
    }
}

@Composable
fun RegisterCredentialScreen(homeViewModel: HomeViewModel) {
    val context = LocalContext.current // Get context for the ViewModel call
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SHC Wallet", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { homeViewModel.registerHobbitCredential(context) }) {
            Text("Register Bilbo Credential")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    SHCWalletTheme {
        // You might need to pass a mock/dummy ViewModel for preview if it doesn't rely on Hilt/DI for preview
        // For simplicity, just showing the screen structure if homeViewModel is not strictly needed for basic preview
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SHC Wallet", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = { /* Preview doesn't need real action */ }) {
                Text("Register Credential")
            }
        }
    }
}