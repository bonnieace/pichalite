#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
ndk="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ "$(uname -s)" == Darwin ]]; then toolchain="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"; fi
if [[ $# == 0 ]]; then set -- arm64-v8a x86_64; fi
for abi in "$@"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android; cc=aarch64-linux-android26-clang ;;
        x86_64) target=x86_64-linux-android; cc=x86_64-linux-android26-clang ;;
        *) echo "Supported ABIs: arm64-v8a x86_64" >&2; exit 1 ;;
    esac
    rustup target add "$target"
    key="${target//-/_}"
    upper="${key^^}"
    env "CC_${key}=$toolchain/$cc" "AR_${key}=$toolchain/llvm-ar" \
        "CARGO_TARGET_${upper}_LINKER=$toolchain/$cc" \
        "CARGO_TARGET_${upper}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
        cargo build --locked --release --lib --no-default-features --features jni --target "$target"
    mkdir -p "android/app/src/main/jniLibs/$abi"
    cp "target/$target/release/libpichalite.so" "android/app/src/main/jniLibs/$abi/"
    "$toolchain/llvm-strip" --strip-unneeded "android/app/src/main/jniLibs/$abi/libpichalite.so"
done
if [[ "${PICHALITE_NATIVE_ONLY:-0}" == 1 ]]; then exit 0; fi
# Explicit path matches Gradle and CI caching, including Android tools that use XDG paths.
preview_key="$HOME/.android/pichalite-preview04.keystore"
if [[ ! -f "$preview_key" ]]; then
    mkdir -p "$(dirname "$preview_key")"
    keytool -genkeypair -keystore "$preview_key" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=PichaLite Preview, O=PichaLite, C=KE"
fi
cd android
./gradlew --no-daemon assembleDebug lintDebug
