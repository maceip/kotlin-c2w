# friscy

Run Linux containers on Android via RISC-V userland emulation.

## Architecture

```
Android UI (Kotlin) <-> JNI <-> libriscv (RISC-V 64) <-> Alpine Linux / Node.js / AI CLIs
```

**friscy** uses [libriscv](https://github.com/fwsGonzo/libriscv) to emulate a RISC-V 64-bit CPU in userland mode (no kernel boot). Docker container images are converted to RISC-V rootfs tarballs and run directly with Linux syscall emulation.

## Features

- **libriscv RISC-V 64 emulator** with threaded dispatch (~200M instr/s)
- **84 Linux syscalls** — VFS, network (real TCP/UDP), threads, futex, epoll, mmap
- **Dynamic ELF loading** — PIE binaries + ld-musl interpreter
- **Alpine Linux shell** — interactive BusyBox with tab completion, job control
- **Node.js / V8 support** — runs Node.js v24 with `--jitless` optimization
- **V8 startup snapshots** — 3.1x speedup via `vm.compileFunction()` pre-compilation
- **Snapshot save/restore** — persist and resume machine state instantly
- **Terminal emulation** — Termux-based xterm-256color with full ANSI support
- **Invader Zim themed UI** — dark mode, neon accents, predictive back animation

## Quick Start

1. Download APK from [Releases](https://github.com/maceip/kotlin-c2w/releases)
2. Install on Android 8.0+ (arm64 or x86_64)
3. Launch — Alpine Linux shell starts automatically

### Helper Bar (visible when keyboard is open)

| Button | Action |
|--------|--------|
| CTRL | Toggle sticky Ctrl mode |
| W/W | Jump word left/right |
| UP | Previous command |
| SNAP | Tap: save snapshot. Long-press: restore |

## Building from Source

### Prerequisites
- JDK 17
- Android SDK with NDK 27.2.12479018

### Build

```bash
git clone --recursive https://github.com/maceip/kotlin-c2w.git
cd kotlin-c2w/android-app-wamr
./gradlew assembleDebug
```

The APK bundles a 7.4MB Alpine rootfs in assets. Output: `app/build/outputs/apk/debug/app-debug.apk` (~21MB).

## Project Structure

```
kotlin-c2w/
├── android-app-wamr/          # Android app
│   └── app/src/main/
│       ├── cpp/               # Native runtime
│       │   ├── friscy_runtime.cpp    # JNI bridge + execution loop
│       │   └── friscy/               # Shared with friscy-standalone
│       │       ├── syscalls.hpp      # 84 Linux syscall handlers
│       │       ├── vfs.hpp           # In-memory virtual filesystem
│       │       ├── elf_loader.hpp    # ELF parser + dynamic linker
│       │       ├── network.hpp       # TCP/UDP socket emulation
│       │       └── android_io.hpp    # JNI I/O bridge
│       ├── kotlin/            # Kotlin UI
│       │   ├── MainActivity.kt       # Terminal + helper bar + snapshots
│       │   ├── VmService.kt          # Foreground service
│       │   ├── FriscyRuntime.kt      # JNI bindings
│       │   ├── SnapshotManager.kt    # Save/restore machine state
│       │   ├── BackSpineHandler.kt   # Predictive back animation
│       │   └── gauge/                # System stats gauge (Compose)
│       └── assets/
│           └── rootfs.tar     # Alpine Linux rootfs (7.4MB)
├── vendor/libriscv/           # libriscv git submodule
└── android-app/               # Legacy (deprecated)
```

## Performance

| Workload | Instructions | Time | Notes |
|----------|-------------|------|-------|
| Alpine shell boot | ~50M | <1s | BusyBox /bin/sh |
| Node.js --version | 247M | 3.6s | --jitless, Alpine musl riscv64 |
| Claude Code --version (cold) | 3.35B | 20s | 10.9MB ESM bundle |
| Claude Code --version (snapshot) | 661M | 6.5s | 3.1x speedup via vm.compileFunction() |

Interpreter throughput: ~200M instructions/sec (hard ceiling, threaded dispatch).

## Companion: friscy-standalone

The native host runtime lives at [friscy-standalone](https://github.com/maceip/friscy-standalone). It shares the same syscall/VFS/ELF code and additionally supports:
- Emscripten/WebAssembly build for browser execution
- Wizer pre-initialization for instant startup
- VFS tar export for state persistence
- rv2wasm AOT compiler (in progress)

## CI/CD

- Automated builds on push/PR
- Emulator smoke test (Kover code coverage)
- APK size validation

## License

Apache 2.0
