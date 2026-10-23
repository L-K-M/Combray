# shellcheck shell=bash
# Build environment for Combray's M0 engine spike. Source it; do not execute it.
#
#   . scripts/m0/env.sh            # x86_64 (emulator)
#   COMBRAY_ABI=arm64 . scripts/m0/env.sh
#
# Mirrors what Servo's `mach` sets for an Android cross build (python/servo/platform/
# build_target.py at v0.6.0), so the external `native/` crate builds Servo the same way.
# Machine-specific inputs, all optional:
#   ANDROID_SDK_ROOT   default: ANDROID_HOME, local.properties' sdk.dir, then the usual locations
#   ANDROID_NDK_ROOT   default: $ANDROID_SDK_ROOT/ndk/$COMBRAY_NDK_VERSION (installed with
#                      sdkmanager when missing)
#   COMBRAY_HOST_CC    host C compiler for build scripts   (default: clang)
#   COMBRAY_HOST_CXX   host C++ compiler for build scripts (default: clang++)
#   COMBRAY_ABI        x86_64 | arm64                      (default: x86_64)

# shellcheck source=servo-pin.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/servo-pin.sh"

# Pinned inputs. Change them only together with docs/ENGINE_SPIKE.md and docs/BUILDING.md.
export COMBRAY_RUST_TOOLCHAIN="1.97.1"
export COMBRAY_NDK_VERSION="29.0.14206865"
# Upstream compiles against API 29 but links with API 30 linker wrappers (.cargo/config.toml).
export COMBRAY_ANDROID_API_LEVEL="29"
COMBRAY_LINKER_API_LEVEL="30"

# The SDK where Android Studio or Gradle already find it: macOS, then Linux default locations.
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    ANDROID_SDK_ROOT="${ANDROID_HOME:-}"
fi
if [ -z "$ANDROID_SDK_ROOT" ] && [ -f "$COMBRAY_ROOT/local.properties" ]; then
    ANDROID_SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$COMBRAY_ROOT/local.properties" | head -n 1)"
fi
if [ -z "$ANDROID_SDK_ROOT" ]; then
    for _sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -d "$_sdk" ]; then
            ANDROID_SDK_ROOT="$_sdk"
            break
        fi
    done
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
_ndk_default="$ANDROID_SDK_ROOT/ndk/$COMBRAY_NDK_VERSION"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$_ndk_default}"
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"

# The NDK names its tools after the build host. On Apple silicon it still says darwin-x86_64:
# those binaries are universal.
case "$(uname -s)" in
    Linux) _host_tag="linux-x86_64" ;;
    Darwin) _host_tag="darwin-x86_64" ;;
    *)
        echo "!! unsupported build host $(uname -s); build on Linux or macOS" >&2
        return 1
        ;;
esac

case "${COMBRAY_ABI:-x86_64}" in
    x86_64)
        export COMBRAY_RUST_TARGET="x86_64-linux-android"
        export COMBRAY_ANDROID_ABI="x86_64"
        _clang_target="x86_64-linux-android$COMBRAY_ANDROID_API_LEVEL"
        ;;
    arm64)
        export COMBRAY_RUST_TARGET="aarch64-linux-android"
        export COMBRAY_ANDROID_ABI="arm64-v8a"
        # mach spells the aarch64 clang target with "androideabi"; mirror it exactly.
        _clang_target="aarch64-linux-androideabi$COMBRAY_ANDROID_API_LEVEL"
        ;;
    *)
        echo "!! COMBRAY_ABI must be x86_64 or arm64" >&2
        return 1
        ;;
esac

_llvm="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$_host_tag"
# Only the SDK's own NDK directory is installed into; an explicit ANDROID_NDK_ROOT is left alone.
_sdkmanager="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$_sdkmanager" ]; then
    _sdkmanager="$(command -v sdkmanager || true)"
fi
if [ ! -x "$_llvm/bin/clang" ] && [ "$ANDROID_NDK_ROOT" = "$_ndk_default" ] && [ -x "$_sdkmanager" ]; then
    echo "==> installing NDK $COMBRAY_NDK_VERSION with sdkmanager (about 2.4 GB)"
    "$_sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" "ndk;$COMBRAY_NDK_VERSION" || return 1
fi
if [ ! -x "$_llvm/bin/clang" ]; then
    echo "!! NDK $COMBRAY_NDK_VERSION not found at $ANDROID_NDK_ROOT." >&2
    echo "!! Install it with Android Studio's SDK Manager or: sdkmanager \"ndk;$COMBRAY_NDK_VERSION\"" >&2
    return 1
fi
export COMBRAY_NDK_LLVM="$_llvm"

# rustup's proxies first: bypass any cargo wrapper so the pinned toolchain is used.
export PATH="$HOME/.cargo/bin:$PATH:$_llvm/bin"
# .cargo/config.toml names the linker wrapper, as upstream does; another NDK earlier on PATH
# would silently link against its sysroot.
_linker="$COMBRAY_RUST_TARGET$COMBRAY_LINKER_API_LEVEL-clang"
if [ "$(command -v "$_linker")" != "$_llvm/bin/$_linker" ]; then
    echo "!! $_linker resolves to '$(command -v "$_linker")', not the pinned NDK; fix PATH" >&2
    return 1
fi
if [ -d "$COMBRAY_SERVO_SRC/.venv/bin" ]; then
    # Servo's build scripts run Python code generators from mach's virtual environment.
    export PATH="$COMBRAY_SERVO_SRC/.venv/bin:$PATH"
fi

export HOST_CC="${COMBRAY_HOST_CC:-clang}"
export HOST_CXX="${COMBRAY_HOST_CXX:-clang++}"
export HOST_CFLAGS=""
export HOST_CXXFLAGS=""
export CC="$HOST_CC"
export CXX="$HOST_CXX"

export RUST_TARGET="$COMBRAY_RUST_TARGET"
export TARGET_CC="$_llvm/bin/clang"
export TARGET_CPP="$_llvm/bin/clang -E"
export TARGET_CXX="$_llvm/bin/clang++"
export TARGET_AR="$_llvm/bin/llvm-ar"
export TARGET_RANLIB="$_llvm/bin/llvm-ranlib"
export TARGET_OBJCOPY="$_llvm/bin/llvm-objcopy"
export TARGET_STRIP="$_llvm/bin/llvm-strip"
export TARGET_YASM="$_llvm/bin/yasm"
export TARGET_CFLAGS=" --target=$_clang_target"
export TARGET_CXXFLAGS=" --target=$_clang_target"
export TARGET_PKG_CONFIG_SYSROOT_DIR="$_llvm/sysroot"
# jemalloc ignores TARGET_AR; without these it links with the host archiver.
export AR="$TARGET_AR"
export RANLIB="$TARGET_RANLIB"
export LIBCLANG_PATH="$_llvm/lib"
export CLANG_PATH="$_llvm/bin/clang"
export BINDGEN_EXTRA_CLANG_ARGS="--target=$_clang_target --sysroot=$_llvm/sysroot"
export ANDROID_API_LEVEL="$COMBRAY_ANDROID_API_LEVEL"
export RUST_FONTCONFIG_DLOPEN="on"
export SERVO_STYLE_THREAD_STACK_SIZE_KB="8192"

if [ "$COMBRAY_RUST_TARGET" = "x86_64-linux-android" ]; then
    # mach links the compiler-rt builtins explicitly for x86_64 (NDK libgcc removal).
    RUSTFLAGS="-C link-arg=$("$TARGET_CC" --target="$_clang_target" --print-libgcc-file-name)"
    export RUSTFLAGS
else
    export RUSTFLAGS=""
fi

# Share Servo's target directory so artifacts of an upstream `mach` build can be reused.
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$COMBRAY_SERVO_SRC/target}"

unset _llvm _clang_target _host_tag _ndk_default _sdk _sdkmanager

# SHA-256 lines like sha256sum's; macOS has only shasum.
combray_sha256() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$@"
    else
        shasum -a 256 "$@"
    fi
}

# Fails with how to get TOOL when it is not on PATH.
#   combray_require TOOL HOW
combray_require() {
    command -v "$1" > /dev/null 2>&1 && return 0
    echo "!! $1 is required: $2" >&2
    return 1
}
