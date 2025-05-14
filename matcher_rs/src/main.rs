use std::ffi::CString;
use std::os::raw::c_char;

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

// This is the main entry point for the WASM module.
fn main() {
    let hobbit_credential = CredentialPresentation {
        cred_id_json: r#"{"id":"hobbit-credential-001","dcql_cred_id":"hobbit_data_request","provider_idx":0}"#.to_string(),
        title: "Hobbit Identity Record".to_string(),
        subtitle: "Issued by The Shire Council".to_string(),
        icon_data: Some(ICON_DATA), // Pass the static icon data
        disclaimer: None,
        warning: None,
        attributes: vec![
            CredentialAttribute {
                display_name: "Name".to_string(),
                value: Some("Bilbo Baggins".to_string()),
            },
            CredentialAttribute {
                display_name: "Birthplace".to_string(),
                value: Some("The Shire, Middle-earth".to_string()),
            },
            // Example of an attribute with no value (NULL for C API)
            // CredentialAttribute {
            //     display_name: "Known Aliases".to_string(),
            //     value: None,
            // },
        ],
    };

    hobbit_credential.present();

    // No explicit return value needed for main returning ()
}

// To compile this for WASM:
// 1. Ensure you have the wasm32-unknown-unknown target: `rustup target add wasm32-unknown-unknown`
// 2. Compile: `cargo build --target wasm32-unknown-unknown --release`
// The output WASM file will be in `target/wasm32-unknown-unknown/release/matcher_rs.wasm` 