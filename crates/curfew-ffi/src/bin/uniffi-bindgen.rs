//! Generates the Kotlin bindings. Run by the Android module's Gradle task so the checked-in
//! Kotlin can never drift from the Rust it wraps.
fn main() {
    uniffi::uniffi_bindgen_main()
}
