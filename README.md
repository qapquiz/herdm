# herdm

[![Build APK](https://github.com/qapquiz/herdm/actions/workflows/build.yml/badge.svg)](https://github.com/qapquiz/herdm/actions/workflows/build.yml)

Native Android terminal emulator built directly on
[libghostty-vt](https://github.com/ghostty-org/ghostty) — Ghostty's core
terminal state machine — with a thin JNI shim, a Canvas dirty-row renderer,
and a PTY transport. No Flutter, no React Native: Kotlin + NDK only.

## Architecture

```
┌────────────────────────── Android app process ──────────────────────────┐
│                                                                          │
│  TerminalSession ──── main thread ──── GhosttyTerminalView               │
│      │  (orchestration)                  (Canvas renderer + input)       │
│      │                                        │                            │
│  Pty (reader thread, blocking read)          NativeTerminal (JNI)        │
│      │                                        │                            │
│      └────────── bytes ────────► ghostty_jni.cpp ──► libghostty-vt.a     │
│              ◄──── replies ─────  (thin C shim)                          │
└──────────────────────────────────────────────────────────────────────────┘
```

Data flow, one direction per arrow:

- **PTY output → grid**: `Pty.nativeRead` (reader thread) → chunk handed to
  main thread → `GhosttyTerminalView.feed()` → `NativeTerminal.nativeWrite`
  → `ghostty_terminal_vt_write` updates screen/scrollback state. Any bytes
  the terminal wants back on the PTY (query replies, DSR) are returned by
  `nativeWrite` and written to the PTY.
- **Grid → pixels**: `onDraw` calls `nativeSnapshot`, which flattens only
  dirty viewport rows into a direct `ByteBuffer` (documented binary layout
  at the top of `ghostty_jni.cpp`). Kotlin paints those rows into a bitmap —
  zero per-cell JNI calls.
- **Input → PTY**: IME `commitText` sends UTF-8; hardware keys go through
  `ghostty_key_encoder` (respects DECCKM / Kitty keyboard protocol as the
  running program toggles them). Resizes go to both the terminal (reflow)
  and the PTY (`TIOCSWINSZ` → SIGWINCH).

libghostty-vt provides: VT parsing, screen + scrollback state, selection,
paste safety/encoding, key/mouse encoding. It does **not** provide a
renderer or PTY — those live in this repo on purpose.

## Layout

| Path | What |
|---|---|
| `app/src/main/cpp/ghostty_jni.cpp` | Terminal core shim: session handle, snapshot flattening, key encode, selection, paste |
| `app/src/main/cpp/pty_jni.cpp` | PTY transport: `posix_openpt`/fork/exec, read/write/resize/signals |
| `app/src/main/java/.../NativeTerminal.kt` | JNI declarations for the terminal core |
| `app/src/main/java/.../Pty.kt` | JNI declarations + typed wrapper for the PTY |
| `app/src/main/java/.../GhosttyTerminalView.kt` | Canvas renderer, scroll, IME, hardware keys, long-press copy |
| `app/src/main/java/.../TerminalSession.kt` | Owns terminal + PTY + reader thread; main-thread discipline |
| `app/src/main/java/.../MainActivity.kt` | Minimal local-PTY wiring demo |
| `app/src/main/java/.../hc/*` | herdr-core client: `HcConnection` (sshj + framed JSON-RPC), `HcTerminalSession` (attach channel as a `TerminalLink`), `HomeActivity` (hosts), `TerminalsActivity` (live list), `HcTerminalActivity` (attach UI), `Framed`/`JsonAssembler`, `HostStore` |
| `vendor/{arm64-v8a,x86_64}/libghostty-vt.a` + `vendor/include/` | Prebuilt libghostty-vt (generated; see below) |
| `third_party/ghostty` | Shallow clone at the pinned commit |
| `toolchain/zig` | Zig 0.15.2 (ghostty pins the minor) |
| `scripts/vendor.sh` | Rebuilds `vendor/` from `third_party/ghostty` |
| `scripts/host-smoke.c` / `.sh` | Host-side verification: runs the vendored lib through vt_write/resize/formatter/key-encoder and prints the screen |

## Pinning

libghostty-vt's C ABI is **not stable yet** (upstream says so in
`include/ghostty/vt.h`). This project pins:

- ghostty commit `b0947378349eff70f7030dda0e6d022fae1e6fbd`
- Zig `0.15.2`, NDK `27.1.12297006`, `-Dsimd=false`, `ReleaseFast`

Bumping the pin means: re-clone `third_party/ghostty` at the new commit,
rerun `scripts/vendor.sh`, and fix whatever the shim compile now rejects
(NDK `clang++ -DGHOSTTY_STATIC -Ivendor/include -c` is the fast check).

Bootstrap `third_party/ghostty` from scratch (fresh clone of this repo):

```sh
mkdir -p third_party && git -C third_party init -q ghostty \
  && git -C third_party/ghostty remote add origin https://github.com/ghostty-org/ghostty.git \
  && git -C third_party/ghostty fetch -q --depth 1 origin b0947378349eff70f7030dda0e6d022fae1e6fbd \
  && git -C third_party/ghostty checkout -q FETCH_HEAD
```

## Build

Requirements: JDK 17, Android SDK (platform 36), NDK 27.1.12297006.
`toolchain/zig` and `third_party/ghostty` must be present (see Pinning).

```sh
./toolchain/gradle/bin/gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

To rebuild the vendored libs from source:

```sh
./scripts/vendor.sh
```

## herdr-core client

The app doubles as a [herdr-core](https://github.com/herdrdev/herdr-core)
client. Flow: **Home** (saved SSH hosts) → **Terminals** (live list with
OSC 7501 status dots) → **Terminal** (attach channel in the same renderer
used for the local PTY demo).

### Agent alerts

Terminals that report `blocked` or `error` over OSC 7501 raise a heads-up
notification ("blocked · question · <msg>", title = name + agent badge from
`status.app`). `AgentWatch` (app-scoped singleton) runs its own
`events.subscribe` channel, dedups one notification per terminal (id =
address hash), updates `blocked`↔`error` in place without re-alerting,
and cancels when the state clears, the terminal finishes, or is removed.
Reconnects and unknown facts reconcile against a fresh `terminal.list`.

The **"Alert when an agent needs you"** toggle on Home starts
`AgentWatchService`, a `specialUse` foreground service that keeps the
connection alive past swipe-away (START_STICKY; re-reads the persisted
host id after a process kill and re-reconciles, so still-blocked
terminals re-alert). Invariant: whoever replaces `HcApp.connection`
stops the previous one first (Home, service, Terminals teardown all
follow it). With the service off, alerts exist only while the hc screens'
connection lives (it dies when you back out of Terminals, like before).
Terminals needing attention sort to the top of their group in the list.
The list's agent badge is `status.app` (core 0.1.15 has no
`Terminal.agent` field; `hc ls`'s "agent" column is derived from it too).

### Paste

The accessory bar's **paste** button reads the clipboard and sends it
through `nativeEncodePaste` (libghostty's `ghostty_paste_encode`): unsafe
control bytes are stripped and the text is wrapped in bracketed-paste
markers when the program set mode 2004. Text with newlines asks first
("Paste N lines?" — a newline acts as Enter); empty clipboard toasts.

Test recipe (drives status on a real core): `hc run --bg sh -c 'printf
"\033]7501;state=blocked:app=x:kind=question:msg=<base64>\033\\\\"; sleep
N'` — pairs are COLON-separated, `msg`/`title` are base64 (protocol:
thebestterminal.com/docs/program-status). Note `hc send` into a `cat`
terminal is unreliable for status testing (later reports get dropped).

## Mosh transport

Hosts with **transport = mosh** skip herdr entirely: Home → tap → full-screen
remote shell over mosh's UDP protocol (roaming, survives IP changes and
long dead zones — built for phones).

How it works — no protocol reimplementation, a real client binary:

- `app/src/main/jniLibs/<abi>/` ships Termux's `mosh-client` 1.4.0
  (aarch64 + x86_64) and its 53-library closure (abseil, protobuf,
  ncursesw, openssl, zlib, libc++_shared, libandroid-support). Files with
  version-suffixed names (`.so.6/3/1`) are renamed bare-`.so` with their
  ELF `DT_NEEDED`/`SONAME` strings patched in place (same byte length,
  NUL-padded) because AGP drops `.so.<n>` names from APKs.
- `packaging { jniLibs { useLegacyPackaging = true } }` is required:
  without it `nativeLibraryDir` stays empty (libs load from the APK for
  dlopen, but there is no file to `execve`).
- Bootstrap (sshj, same TOFU host key): `mosh-server new` over SSH →
  parse `MOSH CONNECT <port> <key>` → SSH drops; `MOSH_KEY=<key>
  mosh-client <ip> <port>` runs against a local PTY.
- Rendering/input are the same `GhosttyTerminalView` + libghostty key
  encoder as everywhere else; mosh-client's ncurses output is just VT.
- Runtime env: `TERMINFO` → bundled `xterm-256color` entry (copied from
  assets to filesDir), `LD_LIBRARY_PATH`/`LD_PRELOAD` → nativeLibraryDir
  (libandroid-support for the UTF-8 locale check), `LC_ALL=C.UTF-8`.

Limitations: each connect starts a **new** mosh session (run tmux/herdr
inside for persistence); requires `mosh` on the host; UDP must reach the
host (roams across networks by design).

### herdr protocol notes (`hc schema` is the authority)

- Transport: `ssh host hc connect` over sshj; SSH is the only auth layer.
- Line-mode `hello` (JSON-RPC, generation 1, `framed:true`), then all
  traffic is `[channel u32 LE][kind u8][len u32 LE][payload]` — channel 0
  JSON-RPC, attach/events channels carry bytes.
- Gotcha: in framed mode core writes channel-0 replies **without trailing
  newlines** (`server.rs`), so `JsonAssembler` reassembles by brace depth;
  events channels are newline lines.
- Flow control: credit every 64 KB consumed; core starts each stream with
  256 KB. DATA that arrives before `watchChannel` is buffered and replayed
  (first-paint race).
- Hardware keys are encoded by the view (libghostty key encoder, DECCKM /
  Kitty-protocol aware) and sent as DATA on the attach channel.
- Key files: sshj cannot parse `openssh-key-v1` containers (modern ed25519
  keys). Use RSA PEM keys or convert with `ssh-keygen -p -m PKCS8`.

Tested end-to-end against a live core (emulator → host sshd → `hc
connect`): list, `terminal.run`, attach, typing, output rendering.

## Verification checklist

- `scripts/host-smoke.sh` — builds the vendored lib for the host and runs it
  through the exact API path the shim uses (VT writes, cursor movement, EL,
  scrollback, resize reflow, plain-text formatting, key encoding).
- Local PTY demo (MainActivity): prompt render, typed commands, insets.
- herdr client (HomeActivity → TerminalsActivity → HcTerminalActivity):
  driven on the emulator against a live core — connect (TOFU host key),
  list with statuses, new terminal, typing, remote output render.
- mosh client (HomeActivity → MoshTerminalActivity): bootstrap + UDP
  session verified end-to-end on the emulator — remote prompt render,
  typing, remote command output (aarch64 + x86_64 binaries bundled).

## Notes / gotchas learned building this

- **Main-thread discipline**: the native session has no locks; every
  `NativeTerminal` call happens on the main thread. The PTY reader thread
  only hands byte chunks over (`Handler.post`).
- **Write-before-first-layout**: the terminal exists (and accepts
  `vt_write`) before the view lays out; the grid resizes when the view
  does, so early output is never lost.
- **x86_64 TLS**: Zig emits general-dynamic TLS there; bionic only exports
  `__tls_get_addr` from API 29, so `minSdk=29` and a linker `-z undefs`
  override. arm64 is unaffected.
- **16 KB pages**: Android 15+ requires `max-page-size=16384` on the shim
  (CMake handles it).
- **Callbacks are synchronous**: effects (`WRITE_PTY`, bell, title) fire
  *during* `vt_write`; they never re-enter the terminal and only record
  flags/bytes.
- **Snapshot runs, not cells**: the snapshot's per-row cell records share one
  UTF-16 blob; draw contiguous same-style runs (`runLen`), never a single
  cell's text — the first renderer draft drew one char per row and the
  screen looked empty.
- **Cursor blink** is caller-driven: the snapshot reports `blinking`, the
  view re-invalidates every ~530 ms while it holds.
- **Resilience**: a supervisor thread reconnects with backoff; on
  `onReconnected` the list re-subscribes and open terminals re-attach with
  a full repaint. Writer-thread failures are contained (never fatal).
- **Flood guards** (reader-thread accounting, no main-thread posts):
  events channel trips at 1 MB/s for 3 s, attach channel at 10 MB/s for
  1 s → channel closes, session retries once, then exits to the list.
  Rationale: an intermittent core-side zero-byte stream (~2.5 MB/s on the
  attach/events channel after a mid-stream transport loss) was observed
  with hc 0.1.15; unbuffered it OOMs any client. Suspected upstream relay
  issue — worth filing with the repro (attach → kill `hc connect` →
  auto-reconnect → reattach). `pendingData` replay is capped at 512
  chunks with an overflow flag for the same reason.

## Not yet implemented (obvious next steps)

- Touch selection with drag handles (long-press word copy exists)
- Clipboard paste from the app UI (paste encode/safety already wired natively)
- Nerd Font symbols for private-use glyph cells
- Mouse reporting for TUI apps (`ghostty_mouse_*` API)
- Fling physics on scrollback (plain drag today)
- Multi-session/tabs
