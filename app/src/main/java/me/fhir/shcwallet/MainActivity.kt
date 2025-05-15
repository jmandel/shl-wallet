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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.fhir.shcwallet.ui.theme.SHCWalletTheme

class MainActivity : ComponentActivity() {
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

        homeViewModel.loadStoredCredentialSummary(applicationContext)

        setContent {
            SHCWalletTheme {
                MainScreen(homeViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.loadStoredCredentialSummary(applicationContext)
    }

    fun launchIssuanceIntent(pendingIntent: PendingIntent) {
        try {
            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            issuanceActivityResultLauncher.launch(intentSenderRequest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build or launch issuance intent", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: HomeViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SHC Wallet") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = uiState.shlInputUri,
                onValueChange = {
                    viewModel.onShlInputChange(it, context)
                },
                label = { Text("Enter SHLink URI") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.clearAllCredentials(context)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear Credentials Icon")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear All Credentials")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Status: ${uiState.message ?: "Idle"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "SHLs Loaded: ${uiState.totalShlsLoadedCount}, Verifiable: ${uiState.totalVerifiablePayloadsCount}, Non-Verifiable: ${uiState.totalNonVerifiableItemsCount}",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("SHL Processing Log:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(uiState.shlProcessingLog.reversed()) { logEntry ->
                    Text(logEntry, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                    HorizontalDivider()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Credential Manifest for Registration:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                item {
                    Text(uiState.credentialManifestForRegistration ?: "(No manifest generated yet)", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                }
            }

            Text(
                text = uiState.message ?: "Status not available",
                modifier = Modifier.padding(8.dp),
                fontStyle = FontStyle.Italic
            )
        }
    }
}