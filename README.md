# SHCWallet - SMART Health Card Wallet & Provider

## Overview

SHCWallet is an Android application designed to:
1.  Process SMART Health Links (SHLs) to retrieve and decrypt SMART Health Cards (SHCs), managed by `ShlProcessorService.kt`.
2.  Store these SHCs securely in an on-device Room database, accessed via `ShcRepository.kt`.
3.  Parse stored SHCs using `FhirShcParser.kt` to extract relevant details (e.g., patient name, insurance plan information for C4DIC-tagged cards).
4.  Act as an Android Credential Manager provider, making stored SHCs available to websites (Relying Parties) through the `navigator.credentials.get()` Web API. The displayed information and matching logic are powered by a dynamically generated manifest based on parsed SHC details.
5.  Utilize a Rust-based WebAssembly (WASM) module (`matcher_rs.wasm`) for credential matching logic (currently tag-based) and to provide display hints for the Credential Manager UI using attributes extracted by `FhirShcParser.kt`.

## Core Functionality (Android App)

### 1. SMART Health Link (SHL) Processing (`ShlProcessorService.kt`)

-   **Service Responsibility:** `ShlProcessorService.kt` encapsulates all logic related to SHL URI parsing, network communication (HTTP GET/POST for manifest or direct JWE files), and JWE decryption. `HomeViewModel.kt` delegates SHL processing tasks to this service.
-   **Input:** The user pastes an SHL URI (e.g., `shlink:/...` or `https://viewer.example.com#shlink:/...`) into the app.
-   **Parsing (within `ShlProcessorService`):**
    -   The base64url encoded payload is extracted from the SHL URI (handles both `shlink:/` prefix and `#shlink:/` fragment).
    -   This payload is decoded into a JSON string representing the SHL's core information (`ShlPayload` data class), including the manifest URL (`url`), decryption key (`key`), and flags (`flag`).
-   **Manifest/File Retrieval (within `ShlProcessorService`):**
    -   **Direct File (if 'U' flag is present):** If the SHL payload's `flag` contains 'U', the service performs an HTTP GET request directly to the `payload.url` (appending a `recipient` query parameter) to fetch an encrypted JWE file. The content is treated as a JWE.
    -   **Manifest Flow (no 'U' flag):**
        -   An HTTP POST request is made to `payload.url` with a `ManifestRequestBody` (containing a `recipient` string) to fetch the SHL manifest JSON.
        -   The manifest lists one or more files. For each file entry:
            -   If `embedded` content (JWE) is present, this is used directly.
            -   If a `location` URL is provided, an HTTP GET request is made to fetch the file. If the content type is `application/jose`, it's treated as a JWE; if `application/smart-health-card`, it's taken as the direct SHC JSON (though typically JWE is expected from locations).
-   **JWE Decryption (within `ShlProcessorService`):**
    -   All fetched or embedded JWE payloads are decrypted using the `key` from the SHL payload.
    -   The Nimbus JOSE+JWT library handles decryption.
    -   Encryption method: `A256GCM` (AES GCM using 256-bit key).
    -   Algorithm: `dir` (Direct Encryption).
    -   Compression: Handles `zip: "DEF"` (DEFLATE) compressed payloads.
-   **SHC Aggregation (within `ShlProcessorService`):**
    -   Decrypted payloads (expected to be SHC JSONs with a `verifiableCredential` array) are processed.
    -   The `verifiableCredential` array (containing JWS strings) from each SHC JSON is extracted.
    -   All collected JWS strings are merged into a single new JSON object: `{"verifiableCredential": [jws1, jws2, ...]}`. This forms the "combined SHC file" string.
-   **Result:** The service returns a `ProcessedShlResult` containing the success status, the combined SHC JSON string, logs, and any error message. `HomeViewModel.kt` then uses `ShcRepository.kt` to store the successful result.

### 2. Credential Storage & Manifest Generation

#### Storage (`ShcRepository.kt` & `ShcDao.kt`)
-   **`ShcRepository.kt`:** Provides an abstraction layer over the `ShcDao`. `HomeViewModel.kt` interacts with the repository to perform database operations (insert, retrieve, clear SHCs).
-   **`CombinedShcEntity.kt`:**
    -   An `@Entity(tableName = "combined_shc")` Room entity.
    -   Stores:
        -   `id`: Auto-generated primary key.
        -   `shcJsonString`: The complete JSON string of the "combined SHC file" (output from `ShlProcessorService`).
        -   `shlPayloadUrl`: The original `url` from the SHL payload, for reference.
        -   `creationTimestamp`: Timestamp of when the record was created.

#### FHIR Parsing & Manifest Attribute Generation (`FhirShcParser.kt`)
-   **`FhirShcParser.kt`:** This utility object is responsible for parsing a "combined SHC JSON string" (retrieved from the database) to extract detailed information for display and manifest generation.
-   **Input:** Takes the `shcJsonString`, a display ID (e.g., `shc_db_id_X`), and a logging lambda.
-   **Processing:**
    -   Deserializes the input JSON to get the array of JWS strings.
    -   For each JWS:
        -   Parses it using `ca.uhn.fhir.shc.SmartHealthCardParser` (from the `org.hl7.fhir.uv.shc:shc-validator` library) to get a FHIR Bundle.
        -   Extracts relevant information from the FHIR Bundle. For example:
            -   Patient Name (from Patient resource).
            -   For Insurance Cards (identified by profiles like `http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage` or `http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Patient` within `Bundle.meta.profile`):
                -   Policy Holder Name.
                -   Subscriber ID / Member ID.
                -   Group Number.
                -   Payer / Insurance Company Name.
                -   Plan Type/Name.
    -   Aggregates these details into an `ExtractedShcDetails` object, which includes:
        -   `displayId`: The provided display ID.
        -   `title`: e.g., "Insurance Card" or "COVID-19 Vaccination".
        -   `subtitle`: e.g., Patient's name.
        -   `tags`: List of profile URIs found in the SHC.
        -   `attributes`: A list of `ManifestAttribute` (name-value pairs) like "Policy Holder: John Doe", "Plan: Gold Plan".
-   **Output:** Returns a list of `ExtractedShcDetails` objects, one for each successfully parsed SHC (though typically an SHL might yield one primary SHC with multiple JWS, this parser processes the combined structure).

#### Global Credential Manifest (`HomeViewModel.updateGlobalStoredCredentialsManifest`)
-   The app generates a single, global JSON manifest string dynamically whenever new SHCs are processed or when a summary is loaded. This manifest represents *all* `CombinedShcEntity` objects currently in the database.
-   **Process:**
    1.  `HomeViewModel` retrieves all `CombinedShcEntity` objects via `ShcRepository`.
    2.  For each entity, it calls `FhirShcParser.extractShcDetailsForManifest()` to get the structured `ExtractedShcDetails`.
    3.  These details are then formatted into the global manifest JSON.
-   **Structure:**
    ```json
    {
      "credentials": [
        {
          "id": "shc_db_id_1", // Generated display ID based on CombinedShcEntity.id
          "title": "Insurance Card", // From FhirShcParser
          "subtitle": "Jane Doe",     // From FhirShcParser
          "tags": ["http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage"], // From FhirShcParser
          "attributes": [ // From FhirShcParser
            { "name": "SHC Database ID", "value": "shc_db_id_1" },
            { "name": "Policy Holder", "value": "Jane Doe" },
            { "name": "Insurance ID", "value": "MEMBER123" },
            { "name": "Plan Type", "value": "Gold Plan" }
          ]
        },
        // ... more entries for other stored SHCs
      ]
    }
    ```
-   This global manifest string is stored in `HomeScreenUiState.credentialManifestForRegistration`.

### 3. Credential Registration (Android Credential Manager - Provider Side)

-   **`HomeViewModel.registerHobbitCredential`:**
    -   Uses `androidx.credentials.registry.provider.RegistryManager.registerCredentials()` to register the app's ability to provide credentials.
    -   **`type`**: `com.credman.IdentityCredential` (Note: This type was found to be necessary for interaction).
    -   **`id`**: A fixed `GLOBAL_CREDENTIAL_REGISTRATION_ID` (`"SHCWALLET_GLOBAL_CREDENTIALS_V1"`) is used.
    -   **`credentialsData`**: The dynamically generated global manifest JSON string (populated with details from `FhirShcParser.kt` via `uiState.credentialManifestForRegistration`) is provided as a byte array. This data is made available to the WASM matcher.
    -   **`matcherData`**: The compiled `matcher_rs.wasm` module is loaded from assets and provided as a byte array.

## Rust WASM Matcher (`matcher_rs.wasm`)

-   **Location:** `matcher_rs/` directory, compiled to `app/src/main/assets/matcher_rs.wasm` (target `wasm32-wasi`).
-   **Purpose:**
    -   Invoked by the Android Credential Manager framework when a Relying Party (website) calls `navigator.credentials.get()`.
    -   Its primary roles are:
        1.  To determine which of the app's stored credentials (represented in the global manifest) should be offered based on the RP's request (current matching is simplified to profile tag checking).
        2.  To provide the necessary information (title, subtitle, attributes, icon) for the system to construct the credential selection UI, using the data prepared by `FhirShcParser.kt` and stored in the global manifest.
-   **Communication with Host (`credman` module):**
    -   Imports functions from host-provided `"credman"` module.
    -   **`GetCredentialsSize()` & `ReadCredentialsBuffer()`**: Used to read the `credentialsData` (global manifest JSON).
    -   **`GetRequestSize()` & `GetRequestBuffer()`**: Available to read RP's request. *Currently, these are NOT used by `matcher_rs.wasm` for matching.*
    -   **`AddStringIdEntry()` & `AddFieldForStringIdEntry()`**: Called to instruct the host on UI construction for each credential choice.
-   **Logic (`matcher_rs/src/main.rs`):**
    1.  **Fetch Manifest:** Calls `get_credentials_json_string()` which uses `GetCredentialsSize` and `ReadCredentialsBuffer` to load the global manifest JSON.
    2.  **Deserialize Manifest:** Parses the JSON string into Rust structs (`GlobalCredentialManifest`, `ManifestCredentialEntry`, `ManifestAttribute`) using `serde_json`. These structs mirror the JSON structure produced by `HomeViewModel` (which in turn uses data from `FhirShcParser`).
    3.  **Iterate and Match:**
        -   Loops through each `ManifestCredentialEntry` in the deserialized manifest.
        -   **Matching (Profile Tag Based):** Checks if the `entry.tags` vector contains the hardcoded `TARGET_TAG` (e.g., `http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage`).
        -   *Note: It does not currently use the RP's request data (e.g., FHIR query) for matching.*
    4.  **Present Credential UI Hint:**
        -   If an entry matches the `TARGET_TAG`:
            -   A `CredentialPresentation` struct is populated.
            -   `cred_id_json`: Formatted as a JSON string like `{"id":"shc_db_id_X"}` using the `id` from the manifest entry.
            -   `title`, `subtitle`: Uses the `title` and `subtitle` fields directly from the `ManifestCredentialEntry` (which were originally populated by `FhirShcParser.kt`).
            -   `icon_data`: Uses an embedded credit card icon.
            -   `attributes`: The `Vec<ManifestAttribute>` from the manifest entry (also from `FhirShcParser.kt`) is directly mapped to `Vec<CredentialAttribute>` for presentation.
            -   The `present()` method calls `AddStringIdEntry` (passing `title`, `subtitle`, `icon_data`) and `AddFieldForStringIdEntry` for each attribute.

## Interacting with the Wallet (Relying Party - RP Website)

This describes how a website (Relying Party) can request credentials from SHCWallet using the Web Credentials API (`navigator.credentials.get`).

1.  **RP Initiates Request:**
    -   The website uses JavaScript to call `navigator.credentials.get()`.
    -   Example request:
        ```javascript
        const credentialRequest = {
            digital: {
                requests: [{
                    protocol: "smart-health-cards",
                    data: { fhir: "Coverage?member-identifier=URN_TEST|SYSTEM|ID123" } // Example RP request data
                }],
            }
        };
        const result = await navigator.credentials.get({ digital: credentialRequest.digital });
        ```
    -   The `protocol` and `data` guide the selection.

2.  **Android System Routes to Wallet:** (As described previously)

3.  **`GetCredentialActivity` is Invoked:** (As described previously)

4.  **Credential Matching and UI Construction (via WASM):**
    -   The Android Credential Manager framework prepares the environment for the WASM matcher:
        -   It makes `credentialsData` (global manifest from `FhirShcParser` via `HomeViewModel`) available.
        -   It makes the RP's request `data` available (though currently unused by the matcher).
        -   The `matcher_rs.wasm` module is executed.
    -   **Current WASM Behavior for Matching & Presentation:**
        -   The WASM module loads and parses the **global manifest**.
        -   It **filters** credentials based on the presence of the hardcoded C4DIC `TARGET_TAG` in each entry's `tags` (parsed by `FhirShcParser.kt`).
        -   *It does **not** currently use the actual `data` (e.g., the FHIR query) from the RP's request for matching.*
        -   For each matching entry, the WASM module calls `AddStringIdEntry` and `AddFieldForStringIdEntry`, using the `title`, `subtitle`, and `attributes` that were originally extracted by `FhirShcParser.kt` and stored in the global manifest.

5.  **User Selection:** (As described previously)

6.  **Result Returned to RP:** (As described previously)

### Future Enhancements for RP Interaction:

(Content remains largely the same, emphasizing the need for WASM to use RP request data and potentially interact with host for complex FHIR queries.)

-   The `matcher_rs.wasm` module would need to be enhanced to:
    1.  Use `GetRequestBuffer` and `GetRequestSize` to read the RP's actual request `data`.
    2.  Parse this request data (e.g., parse the FHIR query).
    3.  Implement more sophisticated matching logic. This might involve:
        -   Comparing the parsed RP request against the detailed attributes or even raw FHIR content of the SHCs (if more data were made available to WASM or if WASM could query back to the host).
        -   The current structure with `FhirShcParser.kt` providing rich attributes in the manifest makes client-side (WASM) matching against these attributes more feasible for some use cases.

## Build & Run

### Android Application:

1.  Open the project in Android Studio.
2.  Ensure you have an Android emulator or physical device connected.
3.  Build and run the `app` module.
    -   A clean build might be needed: `./gradlew clean build`.
    -   The `copyMatcherWasm` Gradle task automatically compiles the Rust WASM (if `cargo` is in PATH) and copies it to assets before each build.
    -   Due to database schema changes, you may need to uninstall the app for Room to create the database fresh if migrations aren't set up.

### Rust WASM Module (`matcher_rs`):

1.  The `copyMatcherWasm` Gradle task in `app/build.gradle.kts` attempts to compile the WASM module automatically using `cargo build --target wasm32-wasi --release` in the `matcher_rs` directory and copies the output.
2.  **Manual Compilation (if needed or for direct testing):**
    -   Navigate to the `matcher_rs` directory.
    -   Ensure you have the `wasm32-wasi` Rust target: `rustup target add wasm32-wasi`
    -   Compile: `cargo build --target wasm32-wasi --release`
3.  **Output Location:** The compiled WASM file is `matcher_rs/target/wasm32-wasi/release/matcher_rs.wasm`. The Gradle task copies this to `app/src/main/assets/matcher_rs.wasm`.
4.  If you modify the Rust code, the Gradle task should pick up the changes on the next build. If not, a manual copy or `./gradlew clean build` might be necessary.

## Key Dependencies

### Android:

-   **Jetpack Compose:** UI development.
-   **Room:** On-device database storage.
-   **Android Credential Manager:** `androidx.credentials.registry:registry-provider`.
-   **Nimbus JOSE+JWT:** JWE decryption (used by `ShlProcessorService`).
-   **HAPI FHIR `shc-validator`:** For parsing JWS into FHIR Bundles and extracting data (used by `FhirShcParser`).
-   **Kotlin Coroutines & ViewModel:** Async operations and UI state.

### Rust (`matcher_rs`):

-   **`serde` & `serde_json`:** Deserializing the global manifest JSON.
-   `wasm-bindgen` and `js-sys` are NOT used as communication is via raw buffer passing defined by the `credman` host module ABI.

(Minor tweaks to Key Dependencies to reflect usage) 