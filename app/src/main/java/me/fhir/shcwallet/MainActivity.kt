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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
        // Initial load of summary data
        homeViewModel.loadStoredCredentialSummary(applicationContext)
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
    val uiState by homeViewModel.uiState.collectAsState()

    // LaunchedEffect to load summary when the composable enters the composition
    // and context is available. This is an alternative to calling from onCreate if context is preferred.
    // However, calling from onCreate with applicationContext is generally fine for initial load.
    // If you want to ensure it runs every time this screen is shown (if it can be navigated away and back):
    // LaunchedEffect(Unit) { // Use a key that changes if re-load is needed on screen re-entry, or true for once
    // homeViewModel.loadStoredCredentialSummary(context)
    // }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SHC Wallet", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = uiState.shlInputUri,
            onValueChange = { homeViewModel.onShlInputChange(it) },
            label = { Text("Paste SHL URI here") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { homeViewModel.processShl(context) }) {
            Text("Process SHL")
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { homeViewModel.registerHobbitCredential(context) }) {
            Text("Register Processed SHL Credential")
        }
        Spacer(modifier = Modifier.height(16.dp))

        uiState.message?.let {
            Text("Status: $it", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text("SHL Processing Log:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.shlProcessingLog) { logEntry ->
                    Text(logEntry, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Stored Data Summary Section
        Text("Stored Data Summary", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Total Combined SHCs: ${uiState.totalShcCount}", style = MaterialTheme.typography.bodyMedium)
        Text("Total Verifiable Credentials (VCs) Stored: ${uiState.totalVcCount}", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // Optionally, display the global manifest for debugging
        uiState.credentialManifestForRegistration?.let {
            Text("Global Credential Manifest for Registration:", style = MaterialTheme.typography.titleSmall)
            Box(modifier = Modifier.heightIn(max = 150.dp).fillMaxWidth().padding(4.dp)) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SHC Wallet", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = "shlink:/example",
                onValueChange = { },
                label = { Text("Paste SHL URI here") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { /* Preview doesn't need real action */ }) {
                Text("Process SHL")
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { /* Preview doesn't need real action */ }) {
                Text("Register Processed SHL Credential")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Status: Idle", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("SHL Processing Log:", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(listOf("Log entry 1", "Log entry 2", "Log entry 3")) { logEntry ->
                        Text(logEntry, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Stored Data Summary Section Preview
            Text("Stored Data Summary", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Total Combined SHCs: 2", style = MaterialTheme.typography.bodyMedium)
            Text("Total Verifiable Credentials (VCs) Stored: 5", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            // Optionally, add preview for the global manifest display if desired
            Text("Global Credential Manifest for Registration:", style = MaterialTheme.typography.titleSmall)
            Box(modifier = Modifier.heightIn(max = 100.dp).fillMaxWidth().padding(4.dp)) {
                LazyColumn {
                    item {
                        Text("{ \"credentials\": [ { \"id\": \"shc_id_1\", \"tags\": [\"http://example.tag\"] } ] }", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}