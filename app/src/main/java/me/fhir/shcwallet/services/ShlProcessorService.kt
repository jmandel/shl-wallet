package me.fhir.shcwallet.services

import android.util.Base64
import android.util.Log
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.DirectDecrypter
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jose.util.DeflateUtils
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// --- Data Classes (Moved from HomeViewModel) ---
data class ShlPayload(
    val url: String,
    val key: String,
    val exp: Long? = null,
    val flag: String? = null,
    val label: String? = null,
    val version: Int? = null // 'v' in JSON, maps to 'version'
)

data class ManifestRequestBody(
    val recipient: String,
    val passcode: String? = null,
    val embeddedLengthMax: Int? = null
)

data class ShlManifestFile(
    val files: List<ManifestEntry>
)

data class ManifestEntry(
    val contentType: String,
    val location: String? = null,
    val embedded: String? = null
)

// --- Result Class for SHL Processing ---
data class ProcessedShlResult(
    val success: Boolean,
    val combinedShcJsonString: String? = null,
    val nonVerifiableFhirResourcesJsonString: String? = null,
    val shlPayloadUrl: String? = null,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null
)

// Helper sealed class for unified processing
private sealed class FileProcessingInfo {
    abstract val sourceDescription: String // Declare as abstract property in the base class

    data class JweToDecrypt(
        val jweString: String,
        val key: String,
        val outerContentTypeHint: String?,
        override val sourceDescription: String // Override in subclass
    ) : FileProcessingInfo()

    data class DirectContent(
        val payload: String,
        val declaredContentType: String?,
        override val sourceDescription: String // Override in subclass
    ) : FileProcessingInfo()
}

class ShlProcessorService {

    companion object {
        private const val TAG = "ShlProcessorService"
    }

    private val currentLogs = mutableListOf<String>()

    private fun log(message: String) {
        Log.i(TAG, message)
        currentLogs.add(message)
    }

    // --- JWE Decryption (Moved from HomeViewModel) ---
    private fun decryptJwePayload(jweString: String, base64UrlKey: String): Pair<String?, String?> {
        log("Attempting to decrypt JWE...")
        try {
            val jweObject = JWEObject.parse(jweString)
            val keyBytes = Base64URL(base64UrlKey).decode()
            if (keyBytes.size != 32) { // A256GCM key size
                log("Error: Invalid key length. Expected 32 bytes for A256GCM, got ${keyBytes.size}")
                return Pair(null, null)
            }

            jweObject.decrypt(DirectDecrypter(keyBytes))

            var payloadBytes = jweObject.payload.toBytes()
            if (jweObject.header.compressionAlgorithm?.name == "DEF") {
                log("Decompressing DEFLATE payload...")
                payloadBytes = DeflateUtils.decompress(payloadBytes)
            }
            val decryptedPayload = String(payloadBytes)
            val contentTypeHeader = jweObject.header.contentType
            log("JWE decrypted successfully. Payload length: ${decryptedPayload.length}. CTY: $contentTypeHeader")
            return Pair(decryptedPayload, contentTypeHeader)
        } catch (e: Exception) {
            log("Error decrypting JWE: ${e.message ?: "Unknown error"}")
            Log.e(TAG, "JWE Decryption failed", e)
            return Pair(null, null)
        }
    }

    // --- HTTP Helpers (Moved from HomeViewModel) ---
    private fun httpGet(urlString: String, shlKeyForAuth: String? = null): String? {
        log("Performing HTTP GET to: $urlString")
        val url = URL(urlString)
        var connection: HttpURLConnection? = null
        return try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000 // 15 seconds
            connection.readTimeout = 15000  // 15 seconds
            shlKeyForAuth?.let {
                // Implement SHL-specific auth if needed
            }

            val responseCode = connection.responseCode
            log("HTTP GET Response Code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                log("HTTP GET success. Response length: ${response.length}")
                response
            } else {
                log("Error: HTTP GET failed with code $responseCode. Message: ${connection.responseMessage}")
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    val reader = BufferedReader(InputStreamReader(errorStream))
                    val errorResponse = reader.readText()
                    log("Error stream content: $errorResponse")
                    reader.close()
                }
                null
            }
        } catch (e: Exception) {
            log("Exception during HTTP GET: ${e.message}")
            Log.e(TAG, "HTTP GET Exception", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun httpPost(urlString: String, body: String, shlKeyForAuth: String? = null): String? {
        log("Performing HTTP POST to: $urlString")
        val url = URL(urlString)
        var connection: HttpURLConnection? = null
        return try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000 // 15 seconds
            connection.readTimeout = 15000  // 15 seconds

            shlKeyForAuth?.let {
                // Auth if needed
            }

            val outputStreamWriter = OutputStreamWriter(connection.outputStream)
            outputStreamWriter.write(body)
            outputStreamWriter.flush()
            outputStreamWriter.close()

            val responseCode = connection.responseCode
            log("HTTP POST Response Code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                log("HTTP POST success. Response length: ${response.length}")
                response
            } else {
                log("Error: HTTP POST failed with code $responseCode. Message: ${connection.responseMessage}")
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    val reader = BufferedReader(InputStreamReader(errorStream))
                    val errorResponse = reader.readText()
                    log("Error stream content: $errorResponse")
                    reader.close()
                }
                null
            }
        } catch (e: Exception) {
            log("Exception during HTTP POST: ${e.message}")
            Log.e(TAG, "HTTP POST Exception", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
    
    suspend fun processShl(shlUri: String, recipientName: String): ProcessedShlResult {
        currentLogs.clear()
        var finalShlPayloadUrl: String? = null

        log("Starting SHL processing for: $shlUri with recipient: $recipientName")

        if (shlUri.isBlank()) {
            log("Error: SHL URI is empty.")
            return ProcessedShlResult(success = false, logs = currentLogs, errorMessage = "SHL URI is empty.")
        }

        val shlPayloadEncoded = when {
            shlUri.startsWith("shlink:/", ignoreCase = true) -> shlUri.substring("shlink:/".length)
            shlUri.contains("#shlink:/", ignoreCase = true) -> shlUri.substringAfter("#shlink:/")
            else -> {
                log("Error: Invalid SHL format. Must start with 'shlink:/' or contain '#shlink:/'.")
                return ProcessedShlResult(success = false, logs = currentLogs, errorMessage = "Invalid SHL URI format.")
            }
        }

        if (shlPayloadEncoded.isBlank()) {
            log("Error: SHL payload is empty after prefix/fragment stripping.")
            return ProcessedShlResult(success = false, logs = currentLogs, errorMessage = "SHL payload is empty.")
        }

        log("Extracted SHL payload part: $shlPayloadEncoded")
        log("Base64URL decoding SHL payload...")
        val shlPayloadJsonString: String
        try {
            shlPayloadJsonString = String(Base64.decode(shlPayloadEncoded, Base64.URL_SAFE or Base64.NO_WRAP))
            log("SHL Payload JSON: $shlPayloadJsonString")
        } catch (e: IllegalArgumentException) {
            log("Error: Invalid Base64URL encoding in SHL payload. ${e.message ?: "Unknown error"}")
            return ProcessedShlResult(success = false, logs = currentLogs, errorMessage = "Invalid Base64URL encoding.")
        }

        val shlPayload: ShlPayload
        try {
            val json = JSONObject(shlPayloadJsonString)
            shlPayload = ShlPayload(
                url = json.getString("url"),
                key = json.getString("key"),
                exp = json.optLong("exp", 0L).takeIf { it > 0 },
                flag = json.optString("flag", null),
                label = json.optString("label", null),
                version = json.optInt("v", 0).takeIf { it > 0 }
            )
            finalShlPayloadUrl = shlPayload.url
            log("SHL Payload parsed successfully: ${shlPayload.label ?: "No Label"}")
        } catch (e: Exception) {
            log("Error parsing SHL payload JSON: ${e.message ?: "Unknown error"}")
            return ProcessedShlResult(success = false, logs = currentLogs, errorMessage = "Cannot parse SHL payload JSON.")
        }

        val filesToProcess = mutableListOf<FileProcessingInfo>()

        if (shlPayload.flag?.contains("U", ignoreCase = true) == true) {
            log("'U' flag detected. Content URL: ${shlPayload.url}. Attempting direct GET.")
            val directJweContent = httpGet(shlPayload.url + "?recipient=$recipientName", shlPayload.key)

            if (directJweContent != null) {
                log("Direct JWE content received for 'U' flag.")
                filesToProcess.add(FileProcessingInfo.JweToDecrypt(
                    jweString = directJweContent,
                    key = shlPayload.key,
                    outerContentTypeHint = null as String?, // Explicit cast
                    sourceDescription = ("direct JWE from U-flag URL: ${shlPayload.url}") as String // Explicit cast
                ))
            } else {
                log("Failed to GET JWE content from SHL payload URL for 'U' flag.")
                // No specific ProcessedShlResult to return here, allow to proceed to unified check
            }
        } else {
            log("No 'U' flag. Proceeding with manifest flow. Requesting manifest from: ${shlPayload.url}")
            val manifestRequestBody = ManifestRequestBody(recipient = recipientName)
            val manifestRequestJson = JSONObject().apply { put("recipient", manifestRequestBody.recipient) }
            val manifestResponseJsonString = httpPost(shlPayload.url, manifestRequestJson.toString(), shlPayload.key)

            if (manifestResponseJsonString != null) {
                log("Manifest received. Parsing files...")
                try {
                    val manifestJson = JSONObject(manifestResponseJsonString)
                    val filesArray = manifestJson.optJSONArray("files")
                    if (filesArray != null && filesArray.length() > 0) {
                        for (i in 0 until filesArray.length()) {
                            val fileEntryJson = filesArray.getJSONObject(i)
                            val manifestEntry = ManifestEntry(
                                contentType = fileEntryJson.getString("contentType"),
                                location = fileEntryJson.optString("location", null),
                                embedded = fileEntryJson.optString("embedded", null)
                            )

                            log("Preparing to process manifest entry (Type: ${manifestEntry.contentType}, Location: ${manifestEntry.location != null}, Embedded: ${manifestEntry.embedded != null})")
                            when {
                                manifestEntry.embedded != null -> {
                                    filesToProcess.add(FileProcessingInfo.JweToDecrypt(
                                        jweString = manifestEntry.embedded,
                                        key = shlPayload.key,
                                        outerContentTypeHint = manifestEntry.contentType as String?, // This is for embedded, NOT lines 298/299
                                        sourceDescription = ("embedded JWE in manifest (declared outer type: ${manifestEntry.contentType})") as String // This is for embedded, NOT lines 298/299
                                    ))
                                }
                                manifestEntry.location != null -> {
                                    log("Fetching content from location URL: ${manifestEntry.location} (declared manifest type: ${manifestEntry.contentType})")
                                    val fileContent = httpGet(manifestEntry.location, shlPayload.key)
                                    if (fileContent != null) {
                                        if (manifestEntry.contentType.startsWith("application/jose", ignoreCase = true)) {
                                            filesToProcess.add(FileProcessingInfo.JweToDecrypt(
                                                jweString = fileContent,
                                                key = shlPayload.key,
                                                outerContentTypeHint = manifestEntry.contentType as String?, // This corresponds to line 298
                                                sourceDescription = ("JWE from manifest location ${manifestEntry.location} (declared outer type: ${manifestEntry.contentType})") as String // This corresponds to line 299
                                            ))
                                        } else if (manifestEntry.contentType.equals("application/smart-health-card", ignoreCase = true) ||
                                                   manifestEntry.contentType.startsWith("application/json", ignoreCase = true) ||
                                                   manifestEntry.contentType.startsWith("application/fhir+json", ignoreCase = true)) {
                                            filesToProcess.add(FileProcessingInfo.DirectContent(
                                                payload = fileContent,
                                                declaredContentType = manifestEntry.contentType,
                                                sourceDescription = "direct content from manifest location ${manifestEntry.location} (type: ${manifestEntry.contentType})"
                                            ))
                                        } else {
                                            log("Unsupported direct content type ('${manifestEntry.contentType}') from location: ${manifestEntry.location}. Skipping item.")
                                        }
                                    } else {
                                        log("Failed to fetch content from manifest location: ${manifestEntry.location}. Skipping entry.")
                                    }
                                }
                                else -> {
                                    log("Manifest entry has neither embedded content nor a location URL. Skipping.")
                                }
                            }
                        }
                    } else {
                        log(if (filesArray == null) "Manifest response does not contain a 'files' array." else "Manifest 'files' array is empty.")
                    }
                } catch (e: Exception) {
                    log("Error parsing manifest JSON: ${e.message ?: "Unknown error"}")
                    // Allow to proceed, filesToProcess might be empty, handled by final check
                }
            } else {
                log("Failed to retrieve manifest from POST request.")
                 // Allow to proceed, filesToProcess will be empty, handled by final check
            }
        }

        val collectedShcJsonStrings = mutableListOf<String>()
        val collectedNonVerifiableFhirJsonStrings = mutableListOf<String>()

        if (filesToProcess.isEmpty()) {
            log("No files or content items were successfully prepared for processing from SHL.")
            // Proceed to the end, where it will likely result in "No health cards or FHIR resources found"
        }

        for (item in filesToProcess) {
            var actualPayload: String? = null
            var contentTypeToConsider: String? = null

            log("Unified processing for item from: ${item.sourceDescription}")

            when (item) {
                is FileProcessingInfo.JweToDecrypt -> {
                    log("Attempting to decrypt JWE for item from ${item.sourceDescription}")
                    val (decryptedPayload, jweCty) = decryptJwePayload(item.jweString, item.key)
                    if (decryptedPayload == null) {
                        log("Failed to decrypt JWE for item from ${item.sourceDescription}. Skipping this item.")
                        continue 
                    }
                    actualPayload = decryptedPayload
                    if (item.outerContentTypeHint == null || item.outerContentTypeHint.startsWith("application/jose", ignoreCase = true)) {
                        contentTypeToConsider = jweCty
                        log("Using JWE CTY ('$jweCty') as primary. Outer hint was '${item.outerContentTypeHint}'. Source: ${item.sourceDescription}")
                    } else {
                        contentTypeToConsider = item.outerContentTypeHint
                        log("Using outer manifest CTY ('${item.outerContentTypeHint}') over JWE CTY ('$jweCty'). Source: ${item.sourceDescription}")
                    }
                }
                is FileProcessingInfo.DirectContent -> {
                    actualPayload = item.payload
                    contentTypeToConsider = item.declaredContentType
                    log("Using direct content type: '$contentTypeToConsider'. Source: ${item.sourceDescription}")
                }
            }

            var effectiveCty = contentTypeToConsider
            if (effectiveCty.isNullOrBlank()) {
                log("Content type for item from '${item.sourceDescription}' is null/blank (was '$contentTypeToConsider'). Attempting refined duck typing based on JSON structure.")
                val trimmedPayload = actualPayload!!.trim() // actualPayload is non-null here

                var duckTypedSuccessfully = false
                try {
                    val jsonObject = JSONObject(trimmedPayload)
                    if (jsonObject.has("verifiableCredential")) { 
                        effectiveCty = "application/smart-health-card"
                        log("Duck-typed to: application/smart-health-card (JSON has 'verifiableCredential' property)")
                        duckTypedSuccessfully = true
                    } else if (jsonObject.has("resourceType")) {
                        effectiveCty = "application/fhir+json"
                        log("Duck-typed to: application/fhir+json (JSON has 'resourceType' property)")
                        duckTypedSuccessfully = true
                    } else {
                        effectiveCty = "application/json" 
                        log("Duck-typed to: application/json (generic JSON object)")
                        duckTypedSuccessfully = true
                    }
                } catch (e: JSONException) {
                    // Payload is not a valid JSON object.
                    log("Payload from '${item.sourceDescription}' is not a valid JSON object. Cannot duck-type based on JSON structure. Error: ${e.message}")
                    // effectiveCty remains as it was (null or blank), will be handled by the outer 'else' or default case.
                }

                if (!duckTypedSuccessfully && effectiveCty.isNullOrBlank()) { // Check if still null/blank after trying JSON
                    log("Warning: Could not confidently duck-type CTY for content from '${item.sourceDescription}' and it was not identifiable JSON. Initial CTY was: '$contentTypeToConsider'. Payload starts with: '${trimmedPayload.take(60)}'")
                }
            }
            log("Effective CTY for item from ${item.sourceDescription} is '$effectiveCty' (was initially '$contentTypeToConsider').")

            when {
                effectiveCty.equals("application/smart-health-card", ignoreCase = true) -> {
                    try {
                        JSONObject(actualPayload!!) // Test if it's a JSON object (could be a bundle)
                        // If it is JSON and has verifiableCredential, it's likely a bundle.
                        if (JSONObject(actualPayload).has("verifiableCredential")) {
                            collectedShcJsonStrings.add(actualPayload)
                            log("Added valid SHC JSON (bundle or pre-wrapped) to collection from '${item.sourceDescription}'.")
                        } else {
                            log("Content from '${item.sourceDescription}' (effective CTY: SHC) is JSON but not a bundle. Wrapping as single JWS.")
                            val wrappedJws = JSONObject().put("verifiableCredential", JSONArray().put(actualPayload))
                            collectedShcJsonStrings.add(wrappedJws.toString())
                        }
                    } catch (e: JSONException) {
                        // Not a valid JSON object, assume it's a raw JWS string.
                        log("Content from '${item.sourceDescription}' (effective CTY: SHC) is not valid JSON ('${e.message}'). Assuming raw JWS and wrapping.")
                        val wrappedJws = JSONObject().put("verifiableCredential", JSONArray().put(actualPayload))
                        collectedShcJsonStrings.add(wrappedJws.toString())
                    }
                }
                effectiveCty?.startsWith("application/json", ignoreCase = true) == true ||
                effectiveCty?.startsWith("application/fhir+json", ignoreCase = true) == true -> {
                    collectedNonVerifiableFhirJsonStrings.add(actualPayload!!)
                    log("Added non-verifiable FHIR/JSON to collection from '${item.sourceDescription}'.")
                }
                else -> {
                    log("Unhandled effective content type '$effectiveCty' for item from '${item.sourceDescription}'. Content will be ignored. Payload snippet: '${actualPayload!!.take(100)}'")
                }
            }
        }

        val finalCombinedShcJsonString = if (collectedShcJsonStrings.isNotEmpty()) {
            log("Combining ${collectedShcJsonStrings.size} collected SHC JSON strings...")
            val combinedVcArray = JSONArray()
            collectedShcJsonStrings.forEach { shcJsonString ->
                try {
                    val shcJson = JSONObject(shcJsonString)
                    val vcArray = shcJson.optJSONArray("verifiableCredential")
                    if (vcArray != null) {
                        for (i in 0 until vcArray.length()) {
                            combinedVcArray.put(vcArray.getString(i))
                        }
                    }
                } catch (e: Exception) {
                    log("Warning: Could not parse one of the collected SHC JSON strings during final VC combination: ${e.message}. Skipping it.")
                }
            }
            if (combinedVcArray.length() == 0 && collectedShcJsonStrings.isNotEmpty()) {
                 // This case might occur if all collected SHC strings were malformed or not bundles, and wrapping failed or produced empty VCs.
                log("Warning: No verifiable credentials successfully extracted to combine, though ${collectedShcJsonStrings.size} SHC JSON items were processed.")
                 // Still, return whatever JSONObject structure we have, which might be an empty verifiableCredential array.
            }
            JSONObject().put("verifiableCredential", combinedVcArray).toString()
        } else null

        val finalNonVerifiableFhirResourcesJsonString = if (collectedNonVerifiableFhirJsonStrings.isNotEmpty()) {
            log("Combining ${collectedNonVerifiableFhirJsonStrings.size} non-verifiable FHIR JSON strings into JSONObjects...")
            val jsonArrayOfStringsOrObjects = JSONArray()
            collectedNonVerifiableFhirJsonStrings.forEach { jsonString ->
                try {
                    // Test if it's an array or object already
                    if (jsonString.trim().startsWith("[")) {
                         val parsedArray = JSONArray(jsonString)
                         for(i in 0 until parsedArray.length()) {
                            jsonArrayOfStringsOrObjects.put(parsedArray.get(i))
                         }
                    } else {
                         jsonArrayOfStringsOrObjects.put(JSONObject(jsonString)) // Parse string to JSONObject
                    }
                } catch (e: JSONException) {
                    log("Warning: Could not parse one of the collected non-verifiable FHIR JSON strings into a JSONObject/JSONArray: ${e.message ?: "Unknown error"}. Storing as raw string as fallback.")
                    jsonArrayOfStringsOrObjects.put(jsonString) // Fallback: store as string
                }
            }
            jsonArrayOfStringsOrObjects.toString()
        } else null

        if (finalCombinedShcJsonString == null && finalNonVerifiableFhirResourcesJsonString == null) {
            log("No SHC VCs nor non-verifiable FHIR resources were collected after processing.")
            // Check original error conditions more specifically
            if (currentLogs.any { it.contains("Failed to GET JWE content from SHL payload URL for 'U' flag")}) {
                 return ProcessedShlResult(success = false, logs = currentLogs, shlPayloadUrl = finalShlPayloadUrl, errorMessage = "Failed to fetch direct content for 'U' flag SHL.")
            }
            if (currentLogs.any { it.contains("Failed to retrieve manifest from POST request")}) {
                 return ProcessedShlResult(success = false, logs = currentLogs, shlPayloadUrl = finalShlPayloadUrl, errorMessage = "Failed to retrieve manifest.")
            }
            if (currentLogs.any { it.contains("Error parsing manifest JSON")}) {
                 return ProcessedShlResult(success = false, logs = currentLogs, shlPayloadUrl = finalShlPayloadUrl, errorMessage = "Cannot parse manifest JSON.")
            }
            // Generic message if no specific upstream error logged as fatal for result
            return ProcessedShlResult(success = false, logs = currentLogs, shlPayloadUrl = finalShlPayloadUrl, errorMessage = "No health cards or FHIR resources found in SHL.")
        }
        
        val vcCountInFinalShc = finalCombinedShcJsonString?.let { 
            try { JSONObject(it).optJSONArray("verifiableCredential")?.length() ?: 0 } catch (e: JSONException) { 0 }
        } ?: 0
        val nonVerifiableCount = finalNonVerifiableFhirResourcesJsonString?.let {
            try { JSONArray(it).length() } catch (e: JSONException) { 0 } // Assuming it's an array of items
        } ?: 0

        log("Successfully processed. Final SHC VCs: $vcCountInFinalShc. Non-verifiable FHIR items: $nonVerifiableCount.")

        return ProcessedShlResult(
            success = true,
            combinedShcJsonString = finalCombinedShcJsonString,
            nonVerifiableFhirResourcesJsonString = finalNonVerifiableFhirResourcesJsonString,
            shlPayloadUrl = finalShlPayloadUrl,
            logs = currentLogs
        )
    }
}