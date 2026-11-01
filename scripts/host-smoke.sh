#!/usr/bin/env bash
# Compile and run scripts/host-smoke.c against a host build of
# libghostty-vt (same pinned source as the Android vendor libs).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIG="$ROOT/toolchain/zig/zig"
GHOSTTY="$ROOT/third_party/ghostty"

(cd "$GHOSTTY" && "$ZIG" build -Demit-lib-vt -Doptimize=ReleaseFast \
  --prefix "$ROOT/build/host")

cc -O2 -I "$ROOT/build/host/include" "$ROOT/scripts/host-smoke.c" \
  -L "$ROOT/build/host/lib" -lghostty-vt -o /tmp/ghostty-smoke
LD_LIBRARY_PATH="$ROOT/build/host/lib" /tmp/ghostty-smoke
