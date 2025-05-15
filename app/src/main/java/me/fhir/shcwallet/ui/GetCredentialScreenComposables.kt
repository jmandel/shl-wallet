package me.fhir.shcwallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun GetCredentialScreenContent(
    title: String,
    onShareClick: () -> Unit,
    showFhirDetailsToggleState: Boolean,
    onShowFhirDetailsToggleChanged: (Boolean) -> Unit,
    showFhirBundlesDialog: Boolean,
    fhirBundlesString: String?,
    onDismissFhirDialog: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Loading credential details...", style = MaterialTheme.typography.bodyLarge)
            } else if (errorMessage != null) {
                Text(
                    text = "Error: $errorMessage",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Show FHIR Details",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = showFhirDetailsToggleState,
                        onCheckedChange = onShowFhirDetailsToggleChanged
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onShareClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && errorMessage == null
            ) {
                Text("Share Credential")
            }
        }
    }

    if (showFhirBundlesDialog) {
        Dialog(onDismissRequest = onDismissFhirDialog) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.8f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "FHIR Bundles",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(
                            text = fhirBundlesString ?: "No FHIR data available.",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = onDismissFhirDialog,
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
} 