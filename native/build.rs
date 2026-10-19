use std::path::PathBuf;
use std::{env, fs};

/// jemalloc still asks the linker for `-lgcc`, which NDK r23 and later no longer ship.
/// Provide a linker script named `libgcc.a` that redirects to the NDK's `libunwind`
/// (same workaround as Servo's own Android shell).
fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }

    let out_dir = PathBuf::from(env::var_os("OUT_DIR").expect("Cargo sets OUT_DIR"));
    fs::write(out_dir.join("libgcc.a"), b"INPUT(-lunwind)").expect("write libgcc.a");
    println!("cargo:rustc-link-search=native={}", out_dir.display());
}
