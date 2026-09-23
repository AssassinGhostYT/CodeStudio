#!/usr/bin/env bash
set -euo pipefail

# The two libraries below are checked-in prebuilt JNI binaries. Re-linking them here is
# intentional: Gradle's native linker flags cannot change an ELF file that is only copied
# from jniLibs. The bootstrap payload is extracted from the existing library unchanged.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ndk_root="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$ndk_root" ]]; then
  ndk_root="${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}/ndk/27.1.12297006"
fi

toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
objcopy="$toolchain/llvm-objcopy"
readelf="$toolchain/llvm-readelf"
[[ -x "$objcopy" ]] || { echo "Missing NDK tool: $objcopy" >&2; exit 1; }
[[ -x "$readelf" ]] || { echo "Missing NDK tool: $readelf" >&2; exit 1; }

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

page_flags=(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
execbridge_source="$repo_root/ide-android/src/main/cpp/execbridge.c"
execbridge_output="$repo_root/ide-android/src/main/jniLibs/arm64-v8a/libexecbridge.so"

"$toolchain/aarch64-linux-android26-clang" \
  -shared -fPIC -O2 -fvisibility=hidden "${page_flags[@]}" -Wl,--build-id=none \
  "$execbridge_source" -ldl -o "$execbridge_output"

build_bootstrap() {
  local abi="$1"
  local target_clang="$2"
  local old_library="$repo_root/termux/application/src/main/jniLibs/$abi/libtermux-bootstrap.so"
  local output_library="$repo_root/termux/application/src/main/jniLibs/$abi/libtermux-bootstrap.so"
  local blob="$tmp_dir/bootstrap-$abi.bin"
  local assembly="$tmp_dir/bootstrap-$abi.S"
  local temporary_output="$tmp_dir/libtermux-bootstrap-$abi.so"

  "$objcopy" --dump-section ".data=$blob" "$old_library"
  test -s "$blob"

  cat > "$assembly" <<EOF
.section .rodata
.balign 8
.global blob
.type blob, %object
blob:
  .incbin "$blob"
.size blob, .-blob
.global blob_size
.type blob_size, %object
blob_size = . - blob
EOF

  "$toolchain/$target_clang" \
    -shared -fPIC -O2 "${page_flags[@]}" -Wl,--build-id=none \
    "$repo_root/termux/application/src/main/cpp/termux-bootstrap.c" \
    "$assembly" -o "$temporary_output"
  mv "$temporary_output" "$output_library"
}

build_bootstrap arm64-v8a aarch64-linux-android26-clang
build_bootstrap armeabi-v7a armv7a-linux-androideabi26-clang

verify_16k() {
  local library="$1"
  local bad_alignment
  bad_alignment="$("$readelf" -lW "$library" | awk '$1 == "LOAD" && $NF != "0x4000" { print $NF }')"
  if [[ -n "$bad_alignment" ]]; then
    echo "16 KB alignment check failed for $library: $bad_alignment" >&2
    exit 1
  fi
}

verify_16k "$execbridge_output"
verify_16k "$repo_root/termux/application/src/main/jniLibs/arm64-v8a/libtermux-bootstrap.so"
verify_16k "$repo_root/termux/application/src/main/jniLibs/armeabi-v7a/libtermux-bootstrap.so"

echo "Rebuilt native libraries with 16 KB ELF LOAD alignment."
