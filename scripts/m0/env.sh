# shellcheck shell=bash
# Build environment for Combray's M0 engine spike. Source it; do not execute it.
#
#   . scripts/m0/env.sh            # x86_64 (emulator)
#   COMBRAY_ABI=arm64 . scripts/m0/env.sh
#
# Mirrors what Servo's `mach` sets for an Android cross build (python/servo/platform/
# build_target.py at v0.6.0), so the external `native/` crate builds Servo the same way.
# Machine-specific inputs, all optional:
#   ANDROID_SDK_ROOT   default: $HOME/Android/Sdk
#   ANDROID_NDK_ROOT   default: $ANDROID_SDK_ROOT/ndk/$COMBRAY_NDK_VERSION
#   COMBRAY_HOST_CC    host C compiler for build scripts   (default: clang)
#   COMBRAY_HOST_CXX   host C++ compiler for build scripts (default: clang++)
#   COMBRAY_ABI        x86_64 | arm64                      (default: x86_64)

COMBRAY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export COMBRAY_ROOT

# Pinned inputs. Change them only together with docs/ENGINE_SPIKE.md and docs/BUILDING.md.
export COMBRAY_SERVO_VERSION="0.6.0"
export COMBRAY_SERVO_GIT_REVISION="c78d2c206f80a1c8b67eefa97f773bba513205d3"
export COMBRAY_SERVO_TARBALL_SHA256="1d828f9956fcb0fccfd5af02e4c75bfb948344f04ba7af1765faff78d4aec0e6"
export COMBRAY_RUST_TOOLCHAIN="1.97.1"
export COMBRAY_NDK_VERSION="29.0.14206865"
# Upstream compiles against API 29 but links with API 30 linker wrappers (.cargo/config.toml).
export COMBRAY_ANDROID_API_LEVEL="29"
COMBRAY_LINKER_API_LEVEL="30"

export COMBRAY_WORK="$COMBRAY_ROOT/servo-checkout"
export COMBRAY_SERVO_SRC="$COMBRAY_WORK/servo-v$COMBRAY_SERVO_VERSION"

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/$COMBRAY_NDK_VERSION}"
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"

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

_llvm="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -x "$_llvm/bin/clang" ]; then
    echo "!! NDK r29 not found at $ANDROID_NDK_ROOT (install ndk;$COMBRAY_NDK_VERSION)" >&2
    return 1
fi

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

unset _llvm _clang_target
