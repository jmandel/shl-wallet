use std::ffi::CString;
use std::os::raw::c_char;
use serde::Deserialize; // Added for Serde

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
const ICON_DATA: &[u8] = include_bytes!("../credit-card.png");
const TARGET_TAG: &str = "http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage";

// --- Rust structs for deserializing the manifest JSON ---
#[derive(Deserialize, Debug)]
struct ManifestAttribute { // New struct for attribute deserialization
    name: String,
    value: String,
}

#[derive(Deserialize, Debug)]
struct ManifestCredentialEntry {
    id: String,
    tags: Vec<String>,
    attributes: Vec<ManifestAttribute>, // Added attributes field
}

#[derive(Deserialize, Debug)]
struct GlobalCredentialManifest {
    credentials: Vec<ManifestCredentialEntry>,
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
        let cstr_cred_id_json = CString::new(self.cred_id_json.as_str()).unwrap();
        let cstr_title = CString::new(self.title.as_str()).unwrap();
        let cstr_subtitle = CString::new(self.subtitle.as_str()).unwrap();

        let icon_ptr;
        let icon_len;
        if let Some(data) = self.icon_data {
            icon_ptr = data.as_ptr();
            icon_len = data.len();
        } else {
            icon_ptr = std::ptr::null();
            icon_len = 0;
        }

        let cstr_disclaimer_opt = self.disclaimer.as_ref().map(|s| CString::new(s.as_str()).unwrap());
        let disclaimer_ptr = cstr_disclaimer_opt.as_ref().map_or(std::ptr::null(), |cs| cs.as_ptr());

        let cstr_warning_opt = self.warning.as_ref().map(|s| CString::new(s.as_str()).unwrap());
        let warning_ptr = cstr_warning_opt.as_ref().map_or(std::ptr::null(), |cs| cs.as_ptr());

        unsafe {
            AddStringIdEntry(
                cstr_cred_id_json.as_ptr(),
                icon_ptr,
                icon_len,
                cstr_title.as_ptr(),
                cstr_subtitle.as_ptr(),
                disclaimer_ptr,
                warning_ptr,
            );
        }

        for attr in &self.attributes {
            let cstr_attr_display_name = CString::new(attr.display_name.as_str()).unwrap();
            
            let cstr_attr_value_opt = attr.value.as_ref().map(|s| CString::new(s.as_str()).unwrap());
            let attr_value_ptr = cstr_attr_value_opt.as_ref().map_or(std::ptr::null(), |cs| cs.as_ptr());

            unsafe {
                AddFieldForStringIdEntry(
                    cstr_cred_id_json.as_ptr(), // Use the same cred_id as the main entry
                    cstr_attr_display_name.as_ptr(),
                    attr_value_ptr,
                );
            }
        }
    }
}

// Helper function to get the credentials JSON string from the host
fn get_credentials_json_string() -> Result<String, String> {
    let mut size: u32 = 0;
    unsafe {
        GetCredentialsSize(&mut size as *mut u32);
    }

    if size == 0 {
        return Err("Credentials size reported as 0.".to_string());
    }

    let mut buffer: Vec<u8> = vec![0; size as usize];
    let bytes_read;
    unsafe {
        bytes_read = ReadCredentialsBuffer(buffer.as_mut_ptr(), 0, size as usize);
    }

    if bytes_read == 0 && size != 0 {
        // Allow 0 bytes read if size was also 0 (though we checked above)
        // but if size > 0 and bytes_read = 0, it's an issue.
        return Err("ReadCredentialsBuffer read 0 bytes while size > 0.".to_string());
    }
    
    // Trim the buffer to actual bytes read, though they should ideally match size
    buffer.truncate(bytes_read);

    String::from_utf8(buffer).map_err(|e| format!("UTF-8 conversion error: {}", e))
}

// This is the main entry point for the WASM module.
fn main() {
    match get_credentials_json_string() {
        Ok(json_string) => {
            // For debugging, try to print the fetched JSON string. 
            // This requires the host to capture/log stdout from wasm.
            // println!("Fetched credentials JSON: {}", json_string);

            match serde_json::from_str::<GlobalCredentialManifest>(&json_string) {
                Ok(manifest) => {
                    for entry in manifest.credentials {
                        if entry.tags.iter().any(|tag| tag == TARGET_TAG) {
                            // Format the cred_id for AddStringIdEntry as a JSON string like the original example
                            let cred_ui_id_json = format!(r#"{{"id":"{}"}}"#, entry.id);

                            // Map ManifestAttribute to CredentialAttribute for presentation
                            let presentation_attributes: Vec<CredentialAttribute> = entry.attributes.into_iter().map(|attr_from_manifest| {
                                CredentialAttribute {
                                    display_name: attr_from_manifest.name,
                                    value: Some(attr_from_manifest.value),
                                }
                            }).collect();

                            let card = CredentialPresentation {
                                cred_id_json: cred_ui_id_json,
                                title: "SMART Health Coverage".to_string(),
                                subtitle: format!("SHC ID: {}", entry.id), // Clarified subtitle
                                icon_data: Some(ICON_DATA),
                                disclaimer: None,
                                warning: None,
                                attributes: presentation_attributes, // Use attributes from manifest
                            };
                            card.present();
                        }
                    }
                }
                Err(e) => {
                    // Error deserializing JSON. For now, we don't have a robust way to report this
                    // back to the host other than perhaps not adding any credentials.
                    // println!("Failed to deserialize credentials JSON: {}", e);
                }
            }
        }
        Err(e) => {
            // Error getting JSON string from host.
            // println!("Failed to get credentials JSON from host: {}", e);
        }
    }
}

// To compile this for WASM:
// 1. Ensure you have the wasm32-unknown-unknown target: `rustup target add wasm32-unknown-unknown`
// 2. Compile: `cargo build --target wasm32-unknown-unknown --release`
// The output WASM file will be in `target/wasm32-unknown-unknown/release/matcher_rs.wasm` 