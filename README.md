# SHCWallet - SMART Health Card Wallet & Provider

## Overview

SHCWallet is an Android application designed to:
1.  Process SMART Health Links (SHLs) to retrieve and decrypt SMART Health Cards (SHCs).
2.  Store these SHCs securely in an on-device Room database.
3.  Act as an Android Credential Manager provider, making stored SHCs available to websites (Relying Parties) through the `navigator.credentials.get()` Web API.
4.  Utilize a Rust-based WebAssembly (WASM) module (`matcher_rs.wasm`) for credential matching logic and to provide display hints for the Credential Manager UI.

## Core Functionality (Android App)

### 1. SMART Health Link (SHL) Processing (`HomeViewModel.kt`)

-   **Input:** The user pastes an SHL URI (e.g., `shlink:/...` or `https://viewer.example.com#shlink:/...`) into the app.
-   **Parsing:**
    -   The base64url encoded payload is extracted from the SHL URI.
    -   This payload is decoded into a JSON string representing the SHL's core information (`ShlPayload` data class), including the manifest URL (`url`), decryption key (`key`), and flags (`flag`).
-   **Manifest/File Retrieval:**
    -   **Direct File (if 'U' flag is present):** If the SHL payload's `flag` contains 'U', the app performs an HTTP GET request directly to the `payload.url` (appending a `recipient` query parameter) to fetch an encrypted JWE file.
    -   **Manifest Flow (no 'U' flag):**
        -   An HTTP POST request is made to `payload.url` with a `ManifestRequestBody` (containing a `recipient` string) to fetch the SHL manifest JSON.
        -   The manifest lists one or more files. For each file entry:
            -   If `embedded` content is present, this JWE data is used directly.
            -   If a `location` URL is provided, an HTTP GET request is made to fetch the JWE file.
-   **JWE Decryption:**
    -   All fetched or embedded JWE payloads are decrypted using the `key` from the SHL payload.
    -   The Nimbus JOSE+JWT library handles decryption.
    -   Encryption method: `A256GCM` (AES GCM using 256-bit key).
    -   Algorithm: `dir` (Direct Encryption).
    -   Compression: Handles `zip: "DEF"` (DEFLATE) compressed payloads using `java.util.zip.Inflater`.
-   **SHC Aggregation:**
    -   Decrypted files with `contentType: "application/smart-health-card"` are processed.
    -   The `verifiableCredential` array (containing JWS strings) from each such SHC JSON is extracted.
    -   All collected JWS strings are merged into a single new JSON object: `{"verifiableCredential": [jws1, jws2, ...]}`. This forms the "combined SHC file."

### 2. Credential Storage (Room Database)

-   **`CombinedShcEntity.kt`:**
    -   An `@Entity(tableName = "combined_shc")` Room entity.
    -   Stores:
        -   `id`: Auto-generated primary key.
        -   `shcJsonString`: The complete JSON string of the "combined SHC file."
        -   `shlPayloadUrl`: The original `url` from the SHL payload, for reference.
        -   `creationTimestamp`: Timestamp of when the record was created.
-   **Global Credential Manifest Generation (`HomeViewModel.updateGlobalStoredCredentialsManifest`):**
    -   The app does **not** store individual manifest entities per SHC.
    -   Instead, a single, global JSON manifest string is dynamically generated whenever new SHCs are processed or when a summary is loaded.
    -   This global manifest represents *all* `CombinedShcEntity` objects currently in the database.
    -   **Structure:**
        ```json
        {
          "credentials": [
            {
              "id": "shc_id_1", // "shc_id_" + CombinedShcEntity.id
              "tags": ["http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage"],
              "attributes": [
                { "name": "SHC Database ID", "value": "shc_id_1" },
                { "name": "Policy Holder", "value": "Frodo Baggins" }, // Example
                { "name": "Insurance ID", "value": "SHIRE-PLAN-12345" }, // Example
                { "name": "Plan Type", "value": "Mithril Tier Coverage" } // Example
              ]
            },
            // ... more entries for other stored SHCs
          ]
        }
        ```
    -   The `tags` currently include a hardcoded C4DIC Coverage tag for demonstration.
    -   The `attributes` include a standard "SHC Database ID" and, for C4DIC-tagged items, illustrative "Hobbit-themed" insurance details for demonstration.
    -   This global manifest string is stored in `HomeScreenUiState.credentialManifestForRegistration`.

### 3. Credential Registration (Android Credential Manager - Provider Side)

-   **`HomeViewModel.registerHobbitCredential`:**
    -   Uses `androidx.credentials.registry.provider.RegistryManager.registerCredentials()` to register the app's ability to provide credentials.
    -   **`type`**: `com.credman.IdentityCredential` (Note: This type was found to be necessary for interaction, though it might differ from standard `DigitalCredential.TYPE_DIGITAL_CREDENTIAL` in some contexts).
    -   **`id`**: A fixed `GLOBAL_CREDENTIAL_REGISTRATION_ID` (`"SHCWALLET_GLOBAL_CREDENTIALS_V1"`) is used, as we are registering one provider capability backed by our dynamic global manifest.
    -   **`credentialsData`**: The dynamically generated global manifest JSON string (from `uiState.credentialManifestForRegistration`) is provided as a byte array. This data is made available to the WASM matcher during a `get()` call.
    -   **`matcherData`**: The compiled `matcher_rs.wasm` module is loaded from assets and provided as a byte array.

## Rust WASM Matcher (`matcher_rs.wasm`)

-   **Location:** `matcher_rs/` directory in the project, compiled to `app/src/main/assets/matcher_rs.wasm`.
-   **Purpose:**
    -   Invoked by the Android Credential Manager framework when a Relying Party (website) calls `navigator.credentials.get()`.
    -   Its primary roles are:
        1.  To determine which of the app's stored credentials (represented in the global manifest) should be offered to the user based on the RP's request (though current matching is simplified).
        2.  To provide the necessary information (title, attributes, icon) for the system to construct the credential selection UI.
-   **Communication with Host (`credman` module):**
    -   The WASM module imports functions from a host-provided module named `"credman"` (as defined by `#[link(wasm_import_module = "credman")]`). These functions are part of the Android Credential Manager's execution environment for WASM matchers.
    -   **`GetCredentialsSize()` & `ReadCredentialsBuffer()`**: Used by the WASM module at runtime to read the `credentialsData` (the global manifest JSON string that SHCWallet provided during registration) from the host environment.
    -   **`GetRequestSize()` & `GetRequestBuffer()`**: Available to read the RP's actual request data. *Currently, these are NOT used by `matcher_rs.wasm` for its matching logic, which simplifies matching to only checking tags in the stored manifest.*
    -   **`AddStringIdEntry()` & `AddFieldForStringIdEntry()`**: Called by the WASM module to tell the host (Credential Manager UI) to display a credential choice to the user. `AddStringIdEntry` creates the main card (title, subtitle, icon), and `AddFieldForStringIdEntry` adds individual attribute lines to that card.
-   **Logic (`matcher_rs/src/main.rs`):**
    1.  **Fetch Manifest:** Calls `get_credentials_json_string()` (a helper in `main.rs`) which uses `GetCredentialsSize` and `ReadCredentialsBuffer` to load the global manifest JSON.
    2.  **Deserialize Manifest:** Parses the JSON string into Rust structs (`GlobalCredentialManifest`, `ManifestCredentialEntry`, `ManifestAttribute`) using `serde_json`.
    3.  **Iterate and Match:**
        -   Loops through each `ManifestCredentialEntry` in the deserialized manifest.
        -   **Matching (Simplified):** Checks if the `entry.tags` vector contains the hardcoded `TARGET_TAG` (`http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage`).
        -   *Note: It does not currently use the RP's request data (e.g., FHIR query from `credentialRequest.digital.requests[0].data`) for matching.*
    4.  **Present Credential UI Hint:**
        -   If an entry matches the `TARGET_TAG`:
            -   A `CredentialPresentation` struct is populated.
            -   `cred_id_json`: Formatted as a JSON string like `{"id":"shc_id_X"}` using the `id` from the manifest entry. This is the ID passed to `AddStringIdEntry`.
            -   `title`, `subtitle`: Set to generic "SMART Health Coverage" and the SHC ID.
            -   `icon_data`: Uses an embedded credit card icon.
            -   `attributes`: The `Vec<ManifestAttribute>` from the manifest entry is directly mapped to `Vec<CredentialAttribute>` for presentation.
            -   The `present()` method is called, which in turn calls `AddStringIdEntry` and `AddFieldForStringIdEntry` for each attribute.

## Interacting with the Wallet (Relying Party - RP Website)

This describes how a website (Relying Party) can request credentials from SHCWallet using the Web Credentials API (`navigator.credentials.get`).

1.  **RP Initiates Request:**
    -   The website uses JavaScript to call `navigator.credentials.get()`.
    -   The `digital` parameter is key, specifying the type of credential interaction.
    -   Example request structure (as provided in the user query):
        ```javascript
        const credentialRequest = {
            digital: {
                requests: [{
                    protocol: "smart-health-cards", // Indicates the type of credential/protocol
                    data: { fhir: "Immunization?vaccineCode:in=COVID" } // RP-specific request data
                }],
            }
        };
        const result = await navigator.credentials.get({ digital: credentialRequest.digital });
        ```
    -   The `protocol` tells the system what kind of credential handler to look for.
    -   The `data` (e.g., `fhir` query) contains the specific criteria for the credential.

2.  **Android System Routes to Wallet:**
    -   The Android Credential Manager receives this request from the browser.
    -   It identifies potential provider apps based on their manifest declarations and the `protocol` in the request.
    -   SHCWallet's `AndroidManifest.xml` declares `GetCredentialActivity` with an intent filter for actions like `androidx.credentials.registry.provider.action.GET_CREDENTIAL`. This makes it a candidate.

3.  **`GetCredentialActivity` is Invoked:**
    -   The system launches SHCWallet's `GetCredentialActivity`. (Note: The full implementation of this activity, including parsing the `GetCredentialRequest` from the system and packaging the `GetCredentialResponse`, is a standard part of being a Credential Provider but is not detailed in the current project scope beyond its manifest declaration).

4.  **Credential Matching and UI Construction (via WASM):**
    -   The Android Credential Manager framework, which hosts `GetCredentialActivity`, prepares the environment for the WASM matcher:
        -   It makes the `credentialsData` (the global manifest JSON string provided by SHCWallet at registration time) available to the WASM module via the `credman` host functions (`GetCredentialsSize`, `ReadCredentialsBuffer`).
        -   It would also make the RP's specific request (e.g., the `fhir` query from `credentialRequest.digital.requests[0].data`) available via `GetRequestSize` and `GetRequestBuffer`.
        -   The `matcher_rs.wasm` module is executed.
    -   **Current WASM Behavior for Matching & Presentation:**
        -   The WASM module loads and parses the **global manifest**.
        -   It **filters** credentials based on the presence of the hardcoded C4DIC `TARGET_TAG` within each entry's `tags` array in the global manifest.
        -   *It does **not** currently use the actual `data` (e.g., `fhir: "Immunization?vaccineCode:in=COVID"`) from the RP's request for its matching logic.*
        -   For each credential entry in the global manifest that contains the `TARGET_TAG`, the WASM module calls `AddStringIdEntry` and `AddFieldForStringIdEntry`. These calls instruct the Credential Manager system on how to build the UI elements for each selectable credential card (displaying titles, subtitles, and attributes from the global manifest).

5.  **User Selection:**
    -   The Android Credential Manager system displays a UI to the user (often a bottom sheet). This UI lists the credential cards constructed based on the WASM module's calls to `AddStringIdEntry`.
    -   The user can then select one of the presented credentials.

6.  **Result Returned to RP:**
    -   If the user selects a credential, `GetCredentialActivity` (after being notified by the system) is responsible for packaging the chosen credential data (e.g., the actual SHC JWS) into a `androidx.credentials.provider.ProviderGetCredentialResponse`.
    -   The system then delivers this response back to the browser.
    -   The `navigator.credentials.get()` promise on the website resolves with a `DigitalCredential` object. The `result.data` would contain the selected credential content, and `result.protocol` would indicate the protocol used.

### Future Enhancements for RP Interaction:

For the wallet to respond more dynamically to the RP's specific request (e.g., the FHIR query):

-   The `matcher_rs.wasm` module would need to be enhanced to:
    1.  Use `GetRequestBuffer` and `GetRequestSize` to read the RP's actual request `data`.
    2.  Parse this request data (e.g., parse the FHIR query).
    3.  Implement more sophisticated matching logic. This might involve:
        -   Comparing the parsed RP request against the content of the SHCs (if the full SHC data were also made available to WASM).
        -   Or, if queries are too complex for WASM, the WASM could signal back to the Android host (via a custom host function, if supported by the Credential Manager SDK for WASM) to perform the filtering on the Android side using its FHIR libraries.
        -   Alternatively, the filtering could happen primarily in `GetCredentialActivity` before even invoking the WASM, with WASM primarily used for fine-grained selection or UI hints.

## Build & Run

### Android Application:

1.  Open the project in Android Studio.
2.  Ensure you have an Android emulator or physical device connected.
3.  Build and run the `app` module.
    -   A clean build might be needed after significant changes: `./gradlew clean build` from the project root.
    -   Due to database schema changes (version increments in `AppDatabase.kt`), you may need to uninstall the app from the device/emulator to allow Room to create the database fresh if migrations are not set up.

### Rust WASM Module (`matcher_rs`):

1.  Navigate to the `matcher_rs` directory in your terminal.
2.  Ensure you have the `wasm32-unknown-unknown` Rust target: `rustup target add wasm32-unknown-unknown`
3.  Compile the WASM module: `cargo build --target wasm32-unknown-unknown --release`
4.  **Copy the output:** The compiled WASM file will be located at `matcher_rs/target/wasm32-unknown-unknown/release/matcher_rs.wasm`. Copy this file to the Android app's assets directory at `app/src/main/assets/matcher_rs.wasm`, replacing any existing file.
5.  Rebuild and reinstall the Android app for the changes to take effect.

## Key Dependencies

### Android:

-   **Jetpack Compose:** For UI development.
-   **Room:** For on-device database storage.
-   **Android Credential Manager:**
    -   `androidx.credentials:credentials` (for client-side, though not heavily used in this provider-focused app).
    -   `androidx.credentials.registry:registry-provider` (for provider-side implementation).
-   **Nimbus JOSE+JWT:** For JWE decryption.
-   **Kotlin Coroutines & ViewModel:** For asynchronous operations and UI state management.

### Rust (`matcher_rs`):

-   **`serde` & `serde_json`:** For deserializing the JSON credential manifest.
-   (Standard library features like `std::ffi::CString` are also used). 