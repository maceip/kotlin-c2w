# friscy Android Client — Implementation Phases

Every subtask listed below is a gate. A subtask is **done** only when `smoke-test.yml` passes on the branch/commit that implements it:

```bash
gh workflow run smoke-test.yml --repo maceip/kotlin-c2w -f ref=<branch>
```

The smoke test builds the APK, verifies expected artifacts inside it, installs it on an Android emulator, launches `MainActivity`, and asserts no crash for 15 seconds. Phase-specific verification steps are added to `smoke-test.yml` as the project evolves — each subtask description below specifies exactly what the smoke test must check.

---

## Phase 1: libriscv NDK Build + Static Hello World
**Status: DONE**

Prove libriscv compiles with Android NDK and executes a RISC-V ELF via JNI.

### Components

| Component | File | Role |
|-----------|------|------|
| `friscy_runtime.cpp` | `app/src/main/cpp/friscy_runtime.cpp` | JNI bridge: creates `Machine<RISCV64>`, installs Linux syscalls, runs ELF, routes stdout to Java |
| `CMakeLists.txt` | `app/src/main/cpp/CMakeLists.txt` | Builds libriscv + JNI bridge into `libfriscy_android.so` |
| `FriscyRuntime.kt` | `app/src/main/kotlin/.../FriscyRuntime.kt` | Kotlin JNI wrapper: `nativeInit`, `nativeLoadAndRun`, `nativeGetVersion` |
| `VmService.kt` | `app/src/main/kotlin/.../VmService.kt` | Foreground service: loads `hello.elf` from assets, calls `FriscyRuntime.loadAndRun()` |
| `hello.elf` | `app/src/main/assets/hello.elf` | Static RISC-V 64 binary, prints "Hello from friscy on Android!" |
| libriscv | `vendor/libriscv` (git submodule) | RISC-V 64 emulator library (fwsGonzo/libriscv) |

### Subtasks

#### 1.1 libriscv submodule + CMake integration
Add `vendor/libriscv` submodule. Rewrite `CMakeLists.txt` to build libriscv with `RISCV_64I=ON`, `RISCV_THREADED=ON`, C++20, `-fexceptions`. Output: `libfriscy_android.so`.

**Smoke gate**: APK builds. `libfriscy_android.so` present for arm64-v8a.

#### 1.2 JNI bridge + Kotlin wrapper
Create `friscy_runtime.cpp` with `nativeInit`, `nativeLoadAndRun`, `nativeGetVersion`. Create `FriscyRuntime.kt`. Wire `VmService.kt` to load `hello.elf` and call `FriscyRuntime.loadAndRun()`. Update `MainActivity.kt` to reference `FriscyRuntime`.

**Smoke gate**: APK builds, app launches without crash, `hello.elf` asset present in APK.

#### 1.3 CI workflows updated
Remove alpine.wasm/aot downloads from all workflows. Update `expected-native-libs` to `libfriscy_android.so`. Update `min-apk-size` (no longer 80MB). Add libriscv submodule verification step.

**Smoke gate**: `smoke-test.yml` passes end-to-end on CI (build + emulator launch + no crash).

---

## Phase 2: VFS + rootfs.tar + Interactive Shell
**Status: DONE**

Load a full Alpine riscv64 rootfs, install all friscy syscalls, get an interactive busybox shell.

### Components

| Component | File | Role |
|-----------|------|------|
| `vfs.hpp` | `app/src/main/cpp/friscy/vfs.hpp` | In-memory virtual filesystem, loaded from tar |
| `elf_loader.hpp` | `app/src/main/cpp/friscy/elf_loader.hpp` | Dynamic ELF loader (resolves `ld-musl-riscv64`) from VFS |
| `syscalls.hpp` | `app/src/main/cpp/friscy/syscalls.hpp` | Linux syscall handlers adapted for Android (stdin/stdout/ioctl) |
| `friscy_runtime.cpp` | `app/src/main/cpp/friscy_runtime.cpp` | Expanded JNI: `nativeLoadRootfs`, `nativeStart`, `nativeSendInput`, `nativeStop`, stdin buffer + condvar |
| `FriscyRuntime.kt` | `app/src/main/kotlin/.../FriscyRuntime.kt` | Full API: `loadRootfs`, `start`, `sendInput`, `stop`, `isRunning` |
| `VmService.kt` | `app/src/main/kotlin/.../VmService.kt` | Loads `rootfs.tar` from assets, calls `FriscyRuntime.loadRootfs()` + `start("/bin/sh")` |
| `rootfs.tar` | `app/src/main/assets/rootfs.tar` | Alpine riscv64 minimal rootfs (~5MB) |

### Subtasks

#### 2.1 Copy friscy runtime sources into `app/src/main/cpp/friscy/`
Port `vfs.hpp`, `elf_loader.hpp`, `syscalls.hpp` from `friscy-standalone`. Replace `#ifdef __EMSCRIPTEN__` stdin/stdout blocks with Android JNI equivalents: `android_io::try_read_stdin()` (mutex-guarded vector), `android_io::has_stdin_data()`, JNI callback for `TIOCGWINSZ`.

**Smoke gate**: APK builds with the new sources compiled in. App launches without crash.

#### 2.2 Expand `friscy_runtime.cpp` to full interactive runtime
Implement `nativeLoadRootfs(byte[] tarBytes)` — parses tar into `vfs::VirtualFS`. Implement `nativeStart(String entry, OutputCallback)` — uses `elf_loader.hpp` to load the entry binary + dynamic linker from VFS, creates `Machine<RISCV64>` with 512MB memory, installs all syscalls, starts execution thread with stdin condvar loop. Implement `nativeSendInput(String)` — pushes to stdin buffer, notifies condvar. Implement `nativeStop()`, `nativeIsRunning()`.

**Smoke gate**: APK builds. App launches without crash.

#### 2.3 Update `FriscyRuntime.kt` + `VmService.kt` for interactive mode
`FriscyRuntime.kt` gets full API: `loadRootfs`, `start`, `sendInput`, `stop`, `isRunning`. `VmService.kt` loads `rootfs.tar` from assets, boots `/bin/sh`. Remove Phase 1's `loadAndRun` one-shot path.

**Smoke gate**: APK builds. App launches, service starts, no crash for 15s.

#### 2.4 Build Alpine riscv64 `rootfs.tar` and bundle in assets
Use `friscy-standalone/tools/container_to_riscv.sh alpine:latest` (or Docker buildx + export) to produce a minimal Alpine riscv64 rootfs tarball. Place in `app/src/main/assets/rootfs.tar`. Remove `hello.elf`.

**Smoke gate**: APK builds with `rootfs.tar` bundled. App launches, terminal shows shell prompt within 5 seconds (verified via logcat string match for `/ #` or `$`). No crash for 30s.

#### 2.5 Wire `MainActivity.kt` input to `FriscyRuntime.sendInput`
Re-enable helper bar buttons (CTRL, arrow keys, word nav) to call `FriscyRuntime.sendInput()`. Re-enable inline command input (Enter key sends text + newline to runtime). Remove `!save`/`!restore`/`!info` commands permanently.

**Smoke gate**: APK builds. App launches. ADB `input text "echo hello"` + `input keyevent KEYCODE_ENTER` produces "hello" in logcat output. No crash for 30s.

---

## Phase 3: Image Download Manager + Picker UI
**Status: DONE**

Download rootfs images on-demand instead of bundling in APK. Add image selection UI.

### Components

| Component | File | Role |
|-----------|------|------|
| `ImageManager.kt` | `app/src/main/kotlin/.../ImageManager.kt` | Download, cache, validate rootfs tarballs in `filesDir/images/` |
| `ImagePickerActivity.kt` | `app/src/main/kotlin/.../ImagePickerActivity.kt` | Three cards: Claude Code, Gemini CLI, Codex. Download progress. Launch button. |
| `image_registry.json` | `app/src/main/assets/image_registry.json` | Metadata: image name, description, download URL, size, sha256 |
| `VmService.kt` | `app/src/main/kotlin/.../VmService.kt` | Accepts image path + entry binary as start parameters |

### Subtasks

#### 3.1 Create `ImageManager.kt`
Downloads tarballs via HTTPS to `filesDir/images/{name}.tar.gz`. Validates sha256 after download. Provides `listCached()`, `download(url, progress)`, `delete(name)`, `getPath(name)`. Handles partial downloads (resume or delete + retry).

**Smoke gate**: APK builds. Unit test for `ImageManager` passes (mock download, cache lookup, delete). App launches without crash.

#### 3.2 Create `ImagePickerActivity.kt`
New launcher activity with three cards: Claude Code, Gemini CLI, Codex. Each card shows name, description, size, download status. Tap downloads (if not cached) then launches `MainActivity` with an intent extra specifying the image path. Fallback: if a bundled `rootfs.tar` exists in assets, show it as "Alpine (offline)".

**Smoke gate**: APK builds. `ImagePickerActivity` is the new launcher (declared in `AndroidManifest.xml`). App opens to picker screen without crash. Tapping a card that has no download shows download progress UI (can fail gracefully if no network on emulator).

#### 3.3 Update `VmService.kt` to accept image path parameter
`VmService` reads image path from intent extra instead of hardcoded asset name. `FriscyRuntime.loadRootfs()` accepts a file path (not just asset bytes). If no path provided, falls back to bundled `rootfs.tar` asset.

**Smoke gate**: APK builds. Launching with bundled Alpine rootfs still works (no regression from 2.4). App launches, terminal shows shell prompt, no crash for 15s.

#### 3.4 Bundle `image_registry.json` with download URLs
Create `assets/image_registry.json` listing available images with their GitHub Release download URLs, sizes, and checksums. `ImagePickerActivity` reads this to populate cards. URLs point to `https://github.com/maceip/kotlin-c2w/releases/download/images-v1/`.

**Smoke gate**: APK builds with `image_registry.json` bundled. App opens picker, shows three cards with correct metadata. No crash.

---

## Phase 4: Cloud Build Pipeline for Quickstart Images
**Status: DONE**

GitHub Actions builds riscv64 Docker images, extracts rootfs tarballs, uploads as release assets.

### Components

| Component | File | Role |
|-----------|------|------|
| `Dockerfile` (alpine-base) | `images/alpine-base/Dockerfile` | Alpine + bash + curl |
| `Dockerfile` (gemini-cli) | `images/gemini-cli/Dockerfile` | Alpine/Debian + Node.js + `@google/gemini-cli` |
| `Dockerfile` (codex) | `images/codex/Dockerfile` | Alpine/Debian + Node.js + `@openai/codex` |
| `Dockerfile` (claude-code) | `images/claude-code/Dockerfile` | Alpine/Debian + Node.js + `@anthropic-ai/claude-code` |
| `build-images.yml` | `.github/workflows/build-images.yml` | Matrix build: QEMU + buildx riscv64, export rootfs, upload to release |

### Subtasks

#### 4.1 Create `images/alpine-base/Dockerfile` + build workflow
Write a Dockerfile for Alpine riscv64 with bash and curl. Create `.github/workflows/build-images.yml` that uses QEMU + `docker buildx build --platform linux/riscv64`, then `docker create` + `docker export` to produce `rootfs.tar.gz`. Upload as GitHub Release asset under tag `images-v1`.

**Smoke gate**: `build-images.yml` workflow runs on GitHub Actions without failure. `alpine-base.tar.gz` artifact is uploaded to the release. File size < 15MB.

#### 4.2 Create Gemini CLI Dockerfile
`images/gemini-cli/Dockerfile`: base image with Node.js + `npm install -g @google/gemini-cli`. Handle riscv64 Node.js availability (Alpine `apk add nodejs` or Debian `apt install nodejs`).

**Smoke gate**: `build-images.yml` matrix includes `gemini-cli`. Workflow passes. `gemini-cli.tar.gz` uploaded to release.

#### 4.3 Create Codex Dockerfile
`images/codex/Dockerfile`: base image with Node.js + `npm install -g @openai/codex`.

**Smoke gate**: `build-images.yml` matrix includes `codex`. Workflow passes. `codex-cli.tar.gz` uploaded to release.

#### 4.4 Create Claude Code Dockerfile
`images/claude-code/Dockerfile`: base image with Node.js + `npm install -g @anthropic-ai/claude-code`.

**Smoke gate**: `build-images.yml` matrix includes `claude-code`. Workflow passes. `claude-code.tar.gz` uploaded to release.

#### 4.5 Update `image_registry.json` with real URLs
Point download URLs in `image_registry.json` to the actual release assets from 4.1–4.4. Update sha256 checksums.

**Smoke gate**: Full `smoke-test.yml` passes. App opens, picker shows images with correct sizes. Downloading alpine-base image on emulator with network succeeds (or gracefully shows error if no network). No crash.

---

## Phase 5: Termux Terminal Integration
**Status: DONE**

Wire the vendored Termux `TerminalEmulator` to friscy for proper VT100/xterm rendering.

### Components

| Component | File | Role |
|-----------|------|------|
| `FriscyTerminalBridge` | `app/src/main/kotlin/.../terminal/FriscyTerminalBridge.kt` | Extends `TerminalOutput`. Routes libriscv output → `TerminalEmulator.append()`. Routes `TerminalView.onKey()` → `FriscyRuntime.sendInput()`. |
| `TerminalActivity.kt` | `app/src/main/kotlin/.../TerminalActivity.kt` | Replaces current `MainActivity` terminal section. Uses vendored `TerminalView` instead of `EditText`. |
| `sys_ioctl` update | `app/src/main/cpp/friscy/syscalls.hpp` | `TIOCGWINSZ` returns actual `TerminalView` dimensions via JNI callback. `TCGETS` succeeds for fd 0 (makes `isatty()` return true). |

### Subtasks

#### 5.1 Create `FriscyTerminalBridge` extending `TerminalOutput`
Implement `write(byte[], int, int)` to call `FriscyRuntime.sendInput()` (user keystroke → guest stdin). Implement `BridgeListener` interface for UI callbacks (bell, title change, colors). Provide `feedOutput(byte[])` method that calls `TerminalEmulator.append()` for guest stdout.

**Smoke gate**: APK builds with new bridge class. Unit test verifies bridge construction and method signatures. App launches without crash.

#### 5.2 Replace `EditText` terminal with `TerminalView`
Swap the `EditText`-based terminal in `MainActivity` (or new `TerminalActivity`) with the vendored `TerminalView` from `com.termux.view`. Wire output: `FriscyRuntime` stdout callback → `FriscyTerminalBridge.feedOutput()` → `TerminalEmulator.append()`. Wire input: `TerminalView.onKey()` → `FriscyTerminalBridge.write()` → `FriscyRuntime.sendInput()`. Remove `AnsiTerminalParser` (Termux handles ANSI natively).

**Smoke gate**: APK builds. App launches, terminal renders. Shell prompt visible. No crash for 15s.

#### 5.3 Fix `sys_ioctl` for terminal dimensions and `isatty`
Update `TIOCGWINSZ` handler to query actual `TerminalView` rows/cols via JNI callback. Add `TCGETS` handler that succeeds for fd 0/1/2 so guest `isatty()` returns true. This enables interactive shell features (line editing, job control, colored prompts).

**Smoke gate**: APK builds. App launches. Shell prompt shows `/ #` (not raw `$PS1` escape sequences). Tab completion produces output (verified via logcat). No crash for 15s.

#### 5.4 Wire helper bar to `TerminalView`
CTRL button toggles modifier state on `TerminalView`. Arrow keys, word navigation, TAB, ESC send correct escape sequences through the bridge.

**Smoke gate**: APK builds. App launches. ADB `input text "ls"` + enter shows file listing. No crash for 15s.

---

## Phase 6: Network Support + AI Tool Launch
**Status: DONE**

Enable real network from inside the emulated environment so AI CLI tools can make API calls.

### Components

| Component | File | Role |
|-----------|------|------|
| `network.hpp` | `app/src/main/cpp/friscy/network.hpp` | Socket syscall handlers: `socket`, `connect`, `bind`, `listen`, `accept`, `send`, `recv`, `setsockopt`, etc. Uses real Android POSIX sockets. |
| `AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` | Already has `INTERNET` permission |

### Subtasks

#### 6.1 Install network syscall handlers
Wire `network.hpp` socket syscall handlers into `friscy_runtime.cpp`. The `#ifndef __EMSCRIPTEN__` code paths use POSIX sockets directly — Android supports these natively. Syscalls: `socket`, `connect`, `sendto`, `recvfrom`, `bind`, `listen`, `accept4`, `getsockopt`, `setsockopt`, `shutdown`, `getpeername`, `getsockname`.

**Smoke gate**: APK builds. App launches. Inside shell, `wget http://example.com -O /dev/null` succeeds (or shows connection attempt in logcat if emulator has no network). No crash for 15s.

#### 6.2 Verify DNS resolution works
Guest calls `connect()` to DNS server (8.8.8.8:53 or system DNS). The real Android socket connects. DNS packets flow through. Hostname resolution works inside the guest without any special handling.

**Smoke gate**: APK builds. App launches. `ping -c 1 example.com` (if ping is available) or `wget https://example.com` resolves hostname. Verified via logcat output. No crash.

#### 6.3 Verify TLS works end-to-end
Guest Node.js bundles its own OpenSSL. TLS handshake happens entirely in guest userland over our socket syscalls. Test with `node -e "require('https').get('https://example.com', r => { console.log(r.statusCode); process.exit(); })"` inside the Gemini CLI or Claude Code image.

**Smoke gate**: APK builds with a Node.js-containing rootfs. App launches. HTTPS request from guest succeeds (verified via logcat). No crash.

#### 6.4 End-to-end AI CLI test
Launch Claude Code (or Gemini CLI) image. Verify the CLI starts, shows its prompt, and can reach its API endpoint. API key not required for this gate — just verify the tool boots and attempts an API call (which will fail with auth error, confirming network path works).

**Smoke gate**: APK builds with AI CLI rootfs. App launches. CLI tool starts inside terminal. Logcat shows outbound HTTPS connection attempt to the AI provider's API. No crash for 30s.

---

## Phase 7: Snapshot + Block Storage
**Status: DONE**

Save and restore emulator state. Sync snapshots to hosted server or companion app.

### Components

| Component | File | Role |
|-----------|------|------|
| `friscy_runtime.cpp` (snapshot) | `app/src/main/cpp/friscy_runtime.cpp` | `nativeSaveSnapshot()` — serializes `Machine` state + VFS dirty pages. `nativeRestoreSnapshot()` — deserializes and resumes. |
| `FriscyRuntime.kt` (snapshot) | `app/src/main/kotlin/.../FriscyRuntime.kt` | `saveSnapshot(path)`, `restoreSnapshot(path)`, `hasSnapshot(path)` |
| `SnapshotManager.kt` | `app/src/main/kotlin/.../SnapshotManager.kt` | Local snapshot storage in `filesDir/snapshots/`. Upload/download to remote backends. |
| `CompanionClient.kt` | `app/src/main/kotlin/.../CompanionClient.kt` | LAN discovery (mDNS) + HTTP client for companion app on user's PC |

### Subtasks

#### 7.1 Implement local snapshot save/restore
`nativeSaveSnapshot()` calls `machine.serialize()` for CPU/memory state, plus dumps VFS dirty page list. Writes to a file. `nativeRestoreSnapshot()` reads file, calls `machine.deserialize()`, applies VFS state, resumes `machine.simulate()`.

**Smoke gate**: APK builds. App launches, shell boots. ADB triggers save (via a debug command or intent). App killed and relaunched. Restore loads snapshot. Shell is back at same state (verified via logcat showing restore success message). No crash.

#### 7.2 Create `SnapshotManager.kt` for local management
List, delete, rename snapshots in `filesDir/snapshots/`. Show snapshot size and timestamp. Wire to UI (button in terminal toolbar or helper bar).

**Smoke gate**: APK builds. Unit test for `SnapshotManager` passes (create, list, delete). App launches without crash.

#### 7.3 Upload snapshots to hosted server
`SnapshotManager` gets `upload(snapshotPath, serverUrl)` method. HTTPS PUT with multipart upload. Server returns a snapshot ID. `download(snapshotId, serverUrl)` retrieves it. Server implementation is out of scope — just the client.

**Smoke gate**: APK builds. Unit test for upload/download client logic passes (mock HTTP). App launches without crash.

#### 7.4 Companion app LAN sync
`CompanionClient.kt` discovers companion app via mDNS (`_friscy._tcp`). Falls back to manual IP entry. HTTP GET/PUT for snapshot transfer over LAN. Companion app (separate repo, runs on PC) serves snapshots from a local directory.

**Smoke gate**: APK builds. Unit test for mDNS discovery + HTTP client passes (mock). App launches without crash. Manual test: companion app running on LAN, Android app discovers it (verified via logcat).

---

## Verification Protocol

Every subtask PR follows this process:

1. **Branch**: Create branch `phase-{N}/subtask-{N.M}-{short-name}`
2. **Implement**: Write code, ensure local `./gradlew :app:assembleDebug` passes
3. **Update smoke-test.yml**: Add any new verification steps specific to this subtask
4. **Push**: Push branch to origin
5. **Trigger smoke test**:
   ```bash
   gh workflow run smoke-test.yml --repo maceip/kotlin-c2w -f ref=phase-{N}/subtask-{N.M}-{short-name}
   ```
6. **Wait for green**: Both `build` and `smoke` jobs must pass
7. **Merge**: PR into master only after smoke test passes

A subtask is **not done** until its smoke test gate passes on GitHub Actions. No exceptions.

### What the smoke test verifies at each layer

| Layer | Check | How |
|-------|-------|-----|
| **Build** | APK compiles | `./gradlew :app:assembleDebug` exit code 0 |
| **Build** | Expected native lib present | `unzip -l` checks for `libfriscy_android.so` |
| **Build** | Expected assets present | `test -f` checks in workflow |
| **Build** | APK size reasonable | `min-apk-size` threshold |
| **Runtime** | App launches | ADB install + `am start` |
| **Runtime** | No crash | `logcat` scanned for `AndroidRuntime:E` fatal exceptions |
| **Runtime** | Activity alive after N seconds | `dumpsys activity` confirms activity is running |
| **Runtime** | Expected output (phase-specific) | `logcat` grep for expected strings (e.g., shell prompt, "Hello from friscy") |
