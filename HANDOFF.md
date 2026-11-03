# HANDOFF — herdm (qapquiz/herdm)

Read this before touching anything. Last updated: 2026-11-03, by the agent
that built the project in one session (5 commits, all verified end-to-end).

## What this is

Native Android terminal app, Kotlin + NDK, with **two transports**:

1. **herdr-core client** — attach to any terminal managed by
   [herdr-core](https://github.com/herdrdev/herdr-core) (`hc`) over
   `ssh host hc connect`: live terminal list with OSC 7501 agent-status
   dots, attach, type, resize, events.
2. **Mosh** — bundled real `mosh-client` binary; roaming UDP sessions.

Rendering is **one renderer for everything**
(`GhosttyTerminalView`): libghostty-vt (Ghostty's core, pinned commit)
parses VT bytes into terminal state; a JNI `nativeSnapshot` flattens dirty
rows into a direct ByteBuffer; Canvas paints them. Transports only move
bytes.

Repo: `github.com/qapquiz/herdm` (public). Local: `~/Projects/ghostty-android`
(folder name still says ghostty-android — safe to `mv` to `herdm`).
CI: `.github/workflows/build.yml` — green, uploads `HerdrM-debug-apk`
artifact on every push to main.

## Environment (this machine)

| Thing | Where/Value |
|---|---|
| Repo | `~/Projects/ghostty-android` |
| Gradle | `toolchain/gradle/bin/gradle` 8.14.2 (wrapper also committed) |
| Zig | `toolchain/zig/zig` 0.15.2 (ghostty pins the minor) |
| NDK | `~/Android/Sdk/ndk/27.1.12297006` |
| SDK | `~/Android/Sdk` (platform 36) — `local.properties` has sdk.dir |
| Emulator | AVD `sauron_p1`, x86_64 API 35, boot cmd used: `emulator -avd sauron_p1 -no-window -no-audio -no-boot-anim -port 5556` → adb device `emulator-5556`, host reachable as `10.0.2.2` |
| herdr-core | installed + running on this host (`hc` 0.1.15) — the emulator can hit it via `10.0.2.2` |
| mosh | host has mosh 1.4.0 (`mosh-server` works) |
| SSH test key | `/tmp/hc-test-key` (RSA PEM, **no passphrase**), pubkey in `~/.ssh/authorized_keys` — comment `ghostty-droid-test`. `/tmp` gets cleaned; re-create if missing |

## Build / test / verify

```sh
cd ~/Projects/ghostty-android
./toolchain/gradle/bin/gradle assembleDebug          # APK → app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
bash scripts/host-smoke.sh                           # host-side lib sanity
bash scripts/vendor.sh                               # rebuild vendored .a from source
```

Emulator e2e pattern used throughout (works well):

```sh
# seed app data (hosts.json + key) — see /tmp/hosts-all.json pattern:
adb shell run-as com.qapquiz.herdm cp /data/local/tmp/hosts.json files/hosts.json
adb shell am start -n com.qapquiz.herdm/.hc.HomeActivity
adb shell input tap 540 410        # row 1 = connect
adb shell input text '...' ; adb shell input keyevent 66
adb exec-out screencap -p > shot.png
```

Gotchas: `input text` **drops spaces** (use `%s` or multiple sends);
`adb shell` mangles nested quotes (write scripts, push, run);
`screencap` sometimes returns 0 bytes via exec-out — retry or use the
file+pull form. App data lives in `/data/user/0/com.qapquiz.herdm/files/`
(`run-as com.qapquiz.herdm ...`).

## Architecture (files that matter)

```
app/src/main/java/com/qapquiz/herdm/
  NativeTerminal.kt        JNI decls for the terminal core (main thread ONLY)
  TerminalSession.kt       local-PTY transport (parameterized program/env —
                           mosh screen reuses this class)
  GhosttyTerminalView.kt   renderer + input. TerminalLink interface = byte
                           sink any transport implements. Key ENCODING lives
                           here (nativeEncodeKey → link.send) so all
                           transports share DECCKM/Kitty handling.
  hc/                      herdr-core + mosh client (see below)
app/src/main/cpp/
  ghostty_jni.cpp          terminal core shim — read the buffer-layout
                           comment at the top before touching nativeSnapshot
  pty_jni.cpp              PTY fork/exec (also execs arbitrary binaries:
                           mosh-client runs through this path)
vendor/<abi>/libghostty-vt.a + vendor/include/   generated, gitignored
scripts/vendor.sh         rebuilds vendor/ (Zig; falls back to PATH zig)
scripts/host-smoke.c/.sh  host verification of the vendored lib
```

`hc/` package: `HcConnection` (SSH + framed JSON-RPC + supervisor
reconnect + rate guards), `Framed`/`JsonAssembler` (wire codec),
`HostStore` (hosts.json persistence), `HomeActivity` (hosts UI),
`TerminalsActivity` (live list), `HcTerminalActivity` (attach screen),
`MoshBootstrap`/`MoshTerminalActivity` (mosh), `AccessoryBar`,
`MoshTerminalSession.kt` is `HcTerminalSession.kt`.
Agent alerts (2026-11-03): `AgentWatch` (object, app-scoped watcher:
own `events.subscribe` channel, blocked/error → heads-up notifications,
reconciles via `terminal.list`) + `AgentWatchService` (specialUse FGS
behind the Home toggle; owns the connection when it created it; START_STICKY
re-reads the persisted host id from prefs `herdm`). Invariant: whoever
replaces `HcApp.connection` stops the previous one first. Home reuses a
live same-host connection without re-`start()` — never call
`HcConnection.start()` on an already-running connection (supervisor
would race). App ships NO icon assets — notifications must use platform
drawables (`android.R.drawable.*`).

## Threading model (do not break)

- **All** `NativeTerminal` calls on the main thread (no locks in native
  session). PTY/SSH reader threads only move bytes via `Handler.post`.
- Outbound frames go through one background writer thread (FIFO; an
  unhandled writer exception once **killed the whole process** — they are
  swallowed now, keep it that way).
- `nativeSnapshot` is called in `onDraw`; effects callbacks fire
  synchronously inside `vt_write` and must never re-enter the terminal.

## Wire protocol essentials (herdr-core 0.1.15, commit b0947378)

- `hc schema` is the authority (OpenRPC). Protocol generation 1.
- Bootstrap: line-mode `hello` (JSON-RPC **one line**, then `framed:true`)
  → after that everything is `[channel u32 LE][kind u8][len u32 LE][payload]`,
  channel 0 = JSON-RPC, attach/events channels = bytes.
- **Core writes ch-0 replies WITHOUT trailing newlines in framed mode**
  (`server.rs`) — hence `JsonAssembler` (brace-depth scan), not line
  splitting. Events channels ARE newline lines.
- Flow control: streams start with 256KB credit; send CREDIT (u32 LE)
  every 64KB consumed. Ignore and core repaints/restarts you.
- Repaints: `MAX_PENDING` overflow → core replaces backlog with a repaint.
- Terminal identity goes in `terminal.run` env (`TERM_PROGRAM=ghostty-droid`).

## Hard-won gotchas (each one cost real debugging time)

1. AGP **drops `.so.<number>` files** from APKs — mosh's versioned libs
   are renamed + their ELF `DT_NEEDED`/`SONAME` strings patched in place
   (see `scripts`/`stage_mosh.py` history in /tmp — the logic is documented
   in README "Mosh transport").
2. `useLegacyPackaging = true` is REQUIRED: without it
   `nativeLibraryDir` is empty and nothing can be exec'd (mosh-client).
3. sshj can't parse `openssh-key-v1` (modern ed25519) private keys —
   test key is RSA PEM for this reason. ed25519 support = parse the
   container ourselves or convert keys.
4. Unhandled exceptions on the writer/executor thread kill the process
   (OOM spiral observed). Everything on background threads is wrapped.
5. `InputType.TYPE_NULL` + `BaseInputConnection` is how the IME works;
   hardware keys route through the encoder in the view.
6. Home header text lives in `activity_home.xml` as an ATTRIBUTE
   (`android:text="ghostty-droid"` — now `herdm`); element-content sed
   patterns don't match it.
7. The intermittent "dim screen" in screenshots is just the home theme,
   not a dialog.

## Known open issues

1. **Core-side zero-byte flood** (intermittent): after a mid-stream
   transport loss + reconnect + reattach, core streamed ~2.5 MB/s of
   zero-payload DATA on events/attach channels (hc 0.1.15). Client
   survives it (rate guards + capped buffers) but the root cause looks
   upstream (relay/stitch). Worth filing: repro = attach → kill
   `hc connect` → auto-reconnect → reattach.
2. **Wrapped-row edge**: a continuation row immediately after a
   >viewport-width error line may not paint (stale history on fresh
   attach rendered fine later). Needs one clean repro to confirm
   client vs core.
3. **ed25519 openssh-key-v1 private keys** unsupported by sshj — users
   must convert (`ssh-keygen -p -m PKCS8`) or use RSA PEM.
4. **Secrets in hosts.json are plaintext** (app-private storage).
   Swap to EncryptedSharedPreferences before any real use.
5. Mosh: each connect = new server session (use tmux/herdr inside for
   persistence). aarch64 binaries bundled but only emulator (x86_64)
   verified on — arm64 untested on hardware.
6. Local demo `MainActivity` is `exported=false` — can't be launched via
   adb directly (intentional).

## Backlog (agreed next steps, in rough priority)

1. ~~**Agent-status notifications**~~ DONE (2026-11-03, user picked
   "in-app first, then FGS behind a toggle" — both shipped in one commit):
   heads-up on `blocked`/`error`, in-place update without re-buzz,
   cancel on clear/finish/remove, reconcile on reconnect; foreground
   service behind the Home toggle ("Alert when an agent needs you"),
   survives swipe-away and process kill. List: needs-you-first sort +
   `status.app` badge (no `Terminal.agent` field in core 0.1.15).
   Status recipe for testing in README "Agent alerts". Left as-is:
   tapping a notification deep-links into the terminal (works); no
   actions on the notification itself; POST_NOTIFICATIONS asked only
   when the toggle goes on.
2. Touch drag-handle selection (long-press word copy exists).
3. Paste from app UI (encode path already native — `nativeEncodePaste`).
4. Nerd Font symbols (PUA cells render as boxes today).
5. Scrollback fling physics (plain drag now).
6. EncryptedSharedPreferences (same as #4 above).
7. Mouse reporting (`ghostty_mouse_*`) for TUI apps.
8. Bumping the libghostty-vt pin: re-clone third_party/ghostty at new
   commit, `scripts/vendor.sh`, fix whatever the shim compile rejects
   (`clang++ -DGHOSTTY_STATIC -Ivendor/include -c` is the fast check).
   Update the prebuilt URL/sha in CI if you keep the prebuilt path.

## CI

`.github/workflows/build.yml` — on push/PR: JDK 17 + Gradle wrapper +
pinned prebuilt libghostty-vt tarball (sha256-pinned, same commit
recipe) → `assembleDebug` → `HerdrM-debug-apk` artifact. Dispatch input
`from_source=true` builds vendor libs from source via Zig instead.
Actions: checkout@v5, setup-java@v5 (bumped after node-20 deprecation
warnings). Repo is PUBLIC.
