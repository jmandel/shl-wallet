use std::collections::BTreeMap;
use std::ffi::CString;
use std::os::raw::c_char;
use serde::{Deserialize, Serialize}; // Added Serialize

// Define the CallingAppInfo struct equivalent to the C struct
#[repr(C)]
#[allow(dead_code)] // Allow dead code for the struct if not used locally yet
struct CallingAppInfo {
    package_name: [c_char; 256],
    origin: [c_char; 512],
}

// Define the functions imported from the WASM host ("credman" module)
// These signatures should match what the host expects and credentialmanager.h implies.
#[link(wasm_import_module = "credman")]
extern "C" {
    // Adds a credential entry to the UI.
    fn AddStringIdEntry(
        cred_id: *const c_char,
        icon: *const u8, // char* for icon data is treated as *const u8
        icon_len: usize,
        title: *const c_char,
        subtitle: *const c_char,
        disclaimer: *const c_char,
        warning: *const c_char,
    );

    // Adds a field (attribute) to a previously added credential entry.
    fn AddFieldForStringIdEntry(
        cred_id: *const c_char,
        field_display_name: *const c_char,
        field_display_value: *const c_char,
    );

    // --- Functions updated/added based on credentialmanager.h ---

    // Corresponds to: void GetRequestBuffer(void* buffer);
    #[allow(dead_code)]
    fn GetRequestBuffer(buffer: *mut u8); // void* typically becomes *mut u8 or *mut c_void for opaque buffers

    // Corresponds to: void GetRequestSize(uint32_t* size);
    #[allow(dead_code)]
    fn GetRequestSize(size: *mut u32);

    // Corresponds to: size_t ReadCredentialsBuffer(void* buffer, size_t offset, size_t len);
    #[allow(dead_code)]
    fn ReadCredentialsBuffer(buffer: *mut u8, offset: usize, len: usize) -> usize;

    // Corresponds to: void GetCredentialsSize(uint32_t* size);
    #[allow(dead_code)]
    fn GetCredentialsSize(size: *mut u32);

    // Corresponds to: void AddPaymentEntry(...);
    #[allow(dead_code)]
    fn AddPaymentEntry(
        cred_id: *const c_char,
        merchant_name: *const c_char,
        payment_method_name: *const c_char,
        payment_method_subtitle: *const c_char,
        payment_method_icon: *const u8, // char* for icon data
        payment_method_icon_len: usize,
        transaction_amount: *const c_char,
        bank_icon: *const u8, // char* for icon data
        bank_icon_len: usize,
        payment_provider_icon: *const u8, // char* for icon data
        payment_provider_icon_len: usize,
    );

    // Corresponds to: void GetCallingAppInfo(CallingAppInfo* info);
    #[allow(dead_code)]
    fn GetCallingAppInfo(info: *mut CallingAppInfo);
}

// Embed the icon data at compile time
const ICON_DATA: &[u8] = include_bytes!("../credit-card.png"); // Uncommented for potential use

// --- Structs for deserializing the Credentials Manifest JSON ---
#[derive(Deserialize, Debug)]
struct ManifestAttribute { 
    name: String,
    value: Option<String>, // MODIFIED to Option<String>
}

#[derive(Deserialize, Debug)]
struct ManifestCredentialEntry {
    id: String,
    tags: Vec<String>,
    attributes: Vec<ManifestAttribute>,
    title: String,
    subtitle: Option<String>,
    disclaimer: Option<String>, // ADDED optional disclaimer per entry
}

#[derive(Deserialize, Debug)]
struct GlobalCredentialManifest {
    credentials: Vec<ManifestCredentialEntry>,
}

// --- Structs for deserializing the NEW Request JSON structure ---
#[derive(Deserialize, Serialize, Debug)] 
struct ProfileData { // Holds {"_profile": "URL"} or other constraints
    _profile: String, 
    // Add other potential constraint fields here if needed, e.g., format: Option<String>
}

// DigitalRequestObject now directly contains the map for constraints
#[derive(Deserialize, Serialize, Debug)] 
struct DigitalRequestObject { 
    #[allow(dead_code)] 
    protocol: Option<String>,
    data: Option<BTreeMap<String, ProfileData>>, // Changed from HashMap to BTreeMap
}

// RequestDataWithFhirResources struct is NO LONGER NEEDED and should be removed.

#[derive(Deserialize, Serialize, Debug)] 
struct DirectRequestPayload { 
    requests: Vec<DigitalRequestObject>,
}

// --- Rust-idiomatic credential presentation structure ---

struct CredentialAttribute {
    display_name: String,
    value: Option<String>, // To support NULL for value
}

struct CredentialPresentation {
    cred_id_json: String,
    title: String,
    subtitle: String,
    icon_data: Option<&'static [u8]>, // Using &'static [u8] for ICON_DATA
    disclaimer: Option<String>,
    warning: Option<String>,
    attributes: Vec<CredentialAttribute>,
}

impl CredentialPresentation {
    fn present(&self) {
        let cstr_cred_id_json = CString::new(self.cred_id_json.as_str()).unwrap_or_default();
        let cstr_title = CString::new(self.title.as_str()).unwrap_or_default();
        let cstr_subtitle = CString::new(self.subtitle.as_str()).unwrap_or_default();
        let (icon_ptr, icon_len) = self.icon_data.map_or((std::ptr::null(), 0), |d| (d.as_ptr(), d.len()));
        let cstr_disclaimer_opt = self.disclaimer.as_ref().and_then(|s| CString::new(s.as_str()).ok());
        let disclaimer_ptr = cstr_disclaimer_opt.as_ref().map_or(std::ptr::null(), |cs| cs.as_ptr());
        let cstr_warning_opt = self.warning.as_ref().and_then(|s| CString::new(s.as_str()).ok());
        let warning_ptr = cstr_warning_opt.as_ref().map_or(std::ptr::null(), |cs| cs.as_ptr());

        if cstr_cred_id_json.as_bytes().is_empty() || cstr_title.as_bytes().is_empty() || cstr_subtitle.as_bytes().is_empty() {
            return;
        }
        unsafe {
            AddStringIdEntry(
                cstr_cred_id_json.as_ptr(),
                icon_ptr, icon_len,
                cstr_title.as_ptr(), cstr_subtitle.as_ptr(),
                disclaimer_ptr, warning_ptr,
            );
        }
        for attr in &self.attributes {
            let cstr_attr_display_name = CString::new(attr.display_name.as_str()).unwrap_or_default();
            let cstr_attr_value_opt = attr.value.as_ref().and_then(|s| CString::new(s.as_str()).ok());
            let attr_value_ptr = cstr_attr_value_opt.as_ref().map_or(std::ptr::null(), |cs| cs.as_ptr());
            if cstr_attr_display_name.as_bytes().is_empty() { continue; }
            unsafe {
                AddFieldForStringIdEntry(
                    cstr_cred_id_json.as_ptr(),
                    cstr_attr_display_name.as_ptr(),
                    attr_value_ptr,
                );
            }
        }
    }
}

// Helper function to present a debug/error card (modified to ensure details are non-empty)
// KEPT FOR FUTURE USE, BUT CALLS WILL BE REMOVED FROM MAIN
#[allow(dead_code)]
fn present_debug_info_card(context_description: &str, details: Vec<String>) {
    let mut attributes = Vec::new();
    let mut final_details = details;
    if final_details.is_empty() {
        final_details.push("(No specific details provided)".to_string());
    }
    for (i, detail_msg) in final_details.iter().enumerate() {
        attributes.push(CredentialAttribute {
            display_name: format!("Detail {}", i + 1),
            value: Some(detail_msg.clone()),
        });
    }
    let card_specific_id = format!(
        "DEBUG_ID_{}", 
        context_description.replace(" ", "_").chars().filter(|c| c.is_alphanumeric() || *c == '_').collect::<String>().to_uppercase()
    );
    let debug_card = CredentialPresentation {
        cred_id_json: format!(r#"{{"id":"{}"}}"#, card_specific_id),
        title: format!("Debug Info: {}", context_description),
        subtitle: "Matcher diagnostic information".to_string(),
        icon_data: None, disclaimer: None, warning: None, attributes,
    };
    debug_card.present();
}

// Helper function to get the credentials manifest JSON string from the host
fn get_credentials_json_string() -> Result<String, String> {
    let mut size: u32 = 0;
    unsafe { GetCredentialsSize(&mut size as *mut u32); }
    if size == 0 { return Err("Credentials manifest size reported as 0.".to_string()); }
    let mut buffer: Vec<u8> = vec![0; size as usize];
    let bytes_read = unsafe { ReadCredentialsBuffer(buffer.as_mut_ptr(), 0, size as usize) };
    if bytes_read == 0 && size != 0 { return Err("ReadCredentialsBuffer read 0 bytes for manifest.".to_string()); }
    buffer.truncate(bytes_read);
    String::from_utf8(buffer).map_err(|e| format!("Manifest UTF-8 conversion error: {}", e))
}

// Helper function to get the request JSON string from the host
fn get_request_json_string() -> Result<String, String> {
    let mut size: u32 = 0;
    unsafe { GetRequestSize(&mut size as *mut u32); }
    if size == 0 { return Err("Request JSON size reported as 0.".to_string()); }
    let mut buffer: Vec<u8> = vec![0; size as usize];
    unsafe { GetRequestBuffer(buffer.as_mut_ptr()); }
    String::from_utf8(buffer).map_err(|e| format!("Request UTF-8 conversion error: {}", e))
}

// Helper function to parse requested profiles from the request JSON string
fn parse_requested_profiles(request_json_str: &str) -> Result<Vec<String>, String> {
    match serde_json::from_str::<DirectRequestPayload>(request_json_str) {
        Ok(parsed_direct_payload) => {
            let mut profiles: Vec<String> = Vec::new();
            for req_obj in parsed_direct_payload.requests {
                if let Some(data_map) = req_obj.data {
                    for (_resource_type, profile_data_obj) in data_map {
                        profiles.push(profile_data_obj._profile.clone());
                    }
                }
            }
            Ok(profiles)
        }
        Err(e) => Err(format!("Request JSON Deserialization Error: {}", e)),
    }
}

// Helper function to process the manifest and present matching credentials
fn process_manifest_and_present_matches(manifest_json_str: &str, profiles_to_match: &[String]) -> Result<(), String> {
    match serde_json::from_str::<GlobalCredentialManifest>(manifest_json_str) {
        Ok(manifest) => {
            // let mut matches_presented_count = 0; // This variable is unused as per the warning, removing

            for entry in manifest.credentials {
                if entry.tags.iter().any(|tag| profiles_to_match.contains(tag)) {
                    let cred_ui_id_json = format!(r#"{{"id":"{}"}}"#, entry.id);
                    
                    let presentation_attributes: Vec<CredentialAttribute> = entry.attributes.into_iter().map(|attr_from_manifest| {
                        CredentialAttribute {
                            display_name: attr_from_manifest.name,
                            value: attr_from_manifest.value, // Directly use the Option<String>
                        }
                    }).collect();

                    let card = CredentialPresentation {
                        cred_id_json: cred_ui_id_json,
                        title: entry.title.clone(),
                        subtitle: entry.subtitle.clone().unwrap_or_else(|| format!("SHC ID: {}", entry.id)),
                        icon_data: Some(ICON_DATA), 
                        disclaimer: entry.disclaimer.clone(),
                        warning: None,
                        attributes: presentation_attributes,
                    };
                    card.present();
                    // matches_presented_count += 1; // Part of the unused variable logic
                }
            }
            // If matches_presented_count was 0, no cards were presented.
            // The host UI will reflect this by showing nothing new.
            Ok(())
        }
        Err(e) => Err(format!("Manifest Deserialization Error: {}", e)),
    }
}

fn main() {
    let request_json_string = match get_request_json_string() {
        Ok(json_str) => json_str,
        Err(_e) => {
            // Error is reported by get_request_json_string if it returns Err.
            // Host can decide if/how to show an error based on lack of presented creds.
            // present_debug_info_card("GetRequestError", vec![e]); // Example: if debug card is desired
            return;
        }
    };

    let profiles_to_match = match parse_requested_profiles(&request_json_string) {
        Ok(profiles) => profiles,
        Err(_e) => {
            // present_debug_info_card("ParseRequestProfilesError", vec![e]);
            return;
        }
    };

    if profiles_to_match.is_empty() {
        // No specific profiles requested, or parsing yielded no profiles to match against.
        // present_debug_info_card("NoProfilesToMatch", vec!["Request did not specify any profiles to match.".to_string()]);
        return;
    }
    
    let manifest_json_string = match get_credentials_json_string() {
        Ok(json_str) => json_str,
        Err(_e) => {
            // present_debug_info_card("GetCredentialsError", vec![e]);
            return;
        }
    };

    if let Err(_e) = process_manifest_and_present_matches(&manifest_json_string, &profiles_to_match) {
        // present_debug_info_card("ProcessManifestError", vec![e]);
        return;
    }
    // Successfully processed.
}

// To compile this for WASM:
// 1. Ensure you have the wasm32-unknown-unknown target: `rustup target add wasm32-unknown-unknown`
// 2. Compile: `cargo build --target wasm32-unknown-unknown --release`
// The output WASM file will be in `target/wasm32-unknown-unknown/release/matcher_rs.wasm` 