#!/usr/bin/env bash
# Rebuild vendor/libghostty-vt.a for both Android ABIs from third_party/ghostty.
#
# Prereqs (already laid down by the initial setup):
#   toolchain/zig/zig   Zig 0.15.2 (ghostty's requireZig pins the minor version)
#   third_party/ghostty pinned checkout (see README for the commit)
#   Android NDK 27.1.12297006 at $HOME/Android/Sdk/ndk/27.1.12297006
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIG="$ROOT/toolchain/zig/zig"
GHOSTTY="$ROOT/third_party/ghostty"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.1.12297006}"

# -Dsimd=false drops the simdutf/highway C++ SIMD paths; lib-vt then only
# needs uucode + compiler_rt.
build() {
  local target="$1" out="$2"
  (cd "$GHOSTTY" && "$ZIG" build -Demit-lib-vt -Dtarget="$target" \
    -Dsimd=false -Doptimize=ReleaseFast --prefix "$ROOT/build/$out")
}

build aarch64-linux-android arm64-v8a
build x86_64-linux-android x86_64

mkdir -p "$ROOT/vendor/arm64-v8a" "$ROOT/vendor/x86_64"
cp "$ROOT/build/arm64-v8a/lib/libghostty-vt.a" "$ROOT/vendor/arm64-v8a/"
cp "$ROOT/build/x86_64/lib/libghostty-vt.a" "$ROOT/vendor/x86_64/"
rm -rf "$ROOT/vendor/include"
cp -R "$ROOT/build/arm64-v8a/include" "$ROOT/vendor/include"

echo "Vendored:"
sha256sum "$ROOT"/vendor/*/libghostty-vt.a
