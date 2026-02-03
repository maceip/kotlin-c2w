# kotlin-c2w

Run container2wasm (c2w) containers on Android using WAMR native runtime.

![Android Demo](android-demo-screenshot.png)

## Features

- **WAMR Native Runtime** - 10-15x faster than Java WASM interpreters
- **AOT Compilation** - Pre-compiled ARM64 native code for maximum performance
- **Checkpoint/Restore** - Save VM state and restore instantly (skip boot time)
- **c2w WASM support** - Boots Alpine Linux in Bochs x86 emulator compiled to WASM

## Architecture

```
UI (Kotlin) ←→ JNI ←→ WAMR (C/C++) ←→ Bochs WASM ←→ Alpine Linux
```

## Performance

| Runtime | Speed vs Native | Notes |
|---------|-----------------|-------|
| Chicory (Java) | ~5% | Pure Java interpreter |
| WAMR Fast Interp | ~75% | Native interpreter |
| WAMR AOT | ~90% | Pre-compiled ARM64 |

## Quick Start

1. Download APK from [Releases](https://github.com/maceip/kotlin-c2w/releases)
2. Install on ARM64 Android device (Android 8.0+)
3. Launch - Alpine Linux boots automatically
4. Use `!save` after boot to create checkpoint for instant future launches

## Special Commands

| Command | Description |
|---------|-------------|
| `!save` | Save checkpoint (snapshot VM state) |
| `!restore` | Delete checkpoint, reboot fresh |
| `!info` | Show checkpoint info |

## Building from Source

### Prerequisites
- JDK 17
- Android SDK with NDK 27.2.12479018
- ~200MB disk space for AOT binary

### Build Steps

```bash
# Clone with submodules (includes WAMR)
git clone --recursive https://github.com/maceip/kotlin-c2w.git
cd kotlin-c2w

# Download pre-built assets (or build your own with c2w)
mkdir -p android-app-wamr/app/src/main/assets
curl -L -o android-app-wamr/app/src/main/assets/alpine.aot \
  https://github.com/maceip/kotlin-c2w/releases/download/assets-v1/alpine.aot
curl -L -o android-app-wamr/app/src/main/assets/alpine.wasm \
  https://github.com/maceip/kotlin-c2w/releases/download/assets-v1/alpine.wasm

# Build APK
cd android-app-wamr
./gradlew assembleDebug
```

### Generate Your Own WASM

```bash
# Install container2wasm
go install github.com/aspect-build/container2wasm/cmd/c2w@latest

# Convert container to WASM
c2w alpine:latest alpine.wasm

# Compile to AOT (optional, requires wamrc)
wamrc --target=aarch64 --size-level=3 -o alpine.aot alpine.wasm
```

## Project Structure

```
kotlin-c2w/
├── android-app-wamr/     # WAMR-based Android app (recommended)
│   └── app/src/main/
│       ├── cpp/          # Native WAMR runtime (c2w_runtime.cpp)
│       └── kotlin/       # Kotlin UI and JNI wrapper
├── android-app/          # Legacy Chicory-based app (deprecated)
├── wamr-integration/     # WAMR submodule
└── container2wasm/       # c2w reference
```

## Screenshot

![Android Demo](android-demo-screenshot.png)

## CI/CD

- Automated builds on push/PR
- Emulator smoke test verifies app launches without crashing
- APK size validation ensures assets are bundled
- Release workflow with automatic artifact upload

## License

Apache 2.0
