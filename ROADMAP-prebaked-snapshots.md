# Roadmap: Pre-Baked Snapshot Distribution

## Goal

Users see a shell prompt instantly on first launch. No cold boot ever.

## Architecture

```
CI Pipeline                          Device
┌─────────────────────┐              ┌─────────────────────┐
│ c2w converts Docker │              │ App downloads from   │
│ image to .wasm      │              │ OCI registry:        │
│                     │              │   - alpine.aot       │
│ Boot in emulator    │              │   - alpine.snap      │
│ Wait for HLT idle   │              │                     │
│ Save snapshot        │              │ Restore snapshot    │
│                     │              │ Inject entropy via  │
│ Publish to OCI      │              │ 9P → init wrapper   │
│ registry:           │              │ reseeds /dev/urandom│
│   - alpine.aot      │              │                     │
│   - alpine.snap     │              │ User sees shell     │
└─────────────────────┘              └─────────────────────┘
```

## Implementation Steps

### 1. Upstream patch: entropy reseeding via 9P (container2wasm)

**Repo:** https://github.com/container2wasm/container2wasm
**File:** `cmd/init/main.go`

The c2w init wrapper already has an explicit snapshot synchronization barrier.
In `doInit()`, the init process prints `"=========="` and blocks waiting for
the host to send `"=\n"`. The comment reads: `// Wizer snapshot can be created
by the host here`. After restore, init continues by reading the `info` file
from the 9P `wasi1` mount at `/mnt/wasi1/info`.

Currently `CONFIG_HW_RANDOM_VIRTIO` is disabled in all kernel configs (QEMU
and Bochs), and there is zero entropy handling anywhere in the init process.
After snapshot restore, the guest CSPRNG state is stale.

**Patch location:** Immediately after the synchronization barrier returns
(~line 141 for non-QEMU mode, ~line 175 for QEMU mode). This is the exact
moment the guest resumes from a snapshot.

```go
// After synchronization barrier, reseed entropy from host
entropyPath := filepath.Join("/mnt", packFSTag, "entropy")
if seed, err := os.ReadFile(entropyPath); err == nil && len(seed) > 0 {
    if f, err := os.OpenFile("/dev/urandom", os.O_WRONLY, 0); err == nil {
        f.Write(seed)
        f.Close()
    }
}
```

**Host side:** Before signaling the guest to resume, write `SecureRandom`
bytes (Android) or `crypto/rand` bytes (CI) to the `entropy` file on the
`wasi1` 9P shared directory.

This patch benefits the entire c2w ecosystem — every user of container2wasm
gets proper entropy on snapshot restore, not just this project.

### 2. Upstream patch: emulator update (container2wasm)

**Repo:** https://github.com/container2wasm/container2wasm
**Dirs:** `config/qemu`, `config/bochs`

Current upstream state:
- **QEMU:** Fork `ktock/qemu-wasm` @ `8604ed49`, compiled via Emscripten 3.1.50
- **Bochs:** Fork `ktock/Bochs` @ `a88d1f68`, compiled via Emscripten 3.1.40
- Both use Linux 6.1.0 kernels with minimal configs
- Bochs uses CD-ROM ISO boot + USB EHCI rootfs (no virtio-blk)
- QEMU uses virtio-blk + virtio-net + virtio-9p
- All kernel configs disable `CONFIG_HW_RANDOM_VIRTIO`

Contributing an updated emulator path upstream (potentially the Bochs/WAMR
AOT approach this project already uses) benefits both projects:

- c2w gets a faster, maintained emulator
- This project stops carrying a separate emulator stack
- Snapshot format is shared between CI (pre-bake) and device (restore)
- Enabling `CONFIG_HW_RANDOM_VIRTIO=y` in kernel configs is also an option
  for continuous entropy (complementary to the 9P seed approach)

### 3. CI pipeline: boot and snapshot in CI

Build pipeline produces two artifacts per container image:

1. `alpine.aot` — the wasm binary (AOT-compiled)
2. `alpine.snap` — pre-booted snapshot

Steps:
- c2w converts the Docker image to wasm (with patched init wrapper)
- CI loads the wasm in the emulator
- Emulator boots the guest OS
- Detect idle via HLT instruction count (CPU halted, waiting for interrupts,
  system has settled)
- Save snapshot
- Publish both artifacts to OCI registry (as two layers in the same image)

### 4. OCI registry distribution (container-registry module)

The `WasmArtifactProvider` (see `container-registry-pr-guidance.md`) pulls
both the wasm binary and the snapshot file:

```kotlin
data class VmArtifacts(
    val wasmFile: File,       // alpine.aot
    val snapshotFile: File?,  // alpine.snap (null if not pre-baked)
)

suspend fun ensure(imageRef: String): VmArtifacts
```

If a snapshot is available, the app skips cold boot entirely.

### 5. Device-side restore with entropy injection

On restore in `VmService`:

1. Write `SecureRandom` bytes to the 9P shared directory (entropy seed file)
2. Load wasm binary into WAMR
3. Restore from snapshot (not cold boot)
4. Emulator resumes → c2w init wrapper reads entropy seed → reseeds guest
5. User sees shell prompt

### 6. HLT-based idle detection in emulator

For cases where no pre-baked snapshot exists (custom images), the emulator
detects system idle via HLT instruction counting:

- After boot, count consecutive HLT cycles
- Once threshold reached (system has settled, not just inter-init idle),
  signal to host that snapshot is safe
- Host auto-saves checkpoint
- All subsequent launches restore from this checkpoint

This is the fallback path. Pre-baked snapshots from CI are the primary path.

## User Journey After Implementation

| Scenario | Experience |
|----------|-----------|
| First launch (pre-baked image) | Instant shell. Snapshot restored, entropy reseeded. |
| First launch (custom image, no snapshot) | Cold boot once. Auto-checkpoint on idle. |
| Subsequent launches | Always instant restore. |
| Image update (tag moves to new digest) | Registry detects staleness, pulls new snapshot, instant restore. |
| Swipe app away | Auto-save checkpoint, instant restore next time. |

## Dependencies

- [ ] Upstream patch to `container2wasm` init wrapper (entropy via 9P)
- [ ] Upstream patch to `container2wasm` emulator configs
- [ ] Container registry module landed as standalone Gradle module
- [ ] `WasmArtifactProvider` supports paired wasm+snapshot artifacts
- [ ] CI pipeline for pre-baking snapshots
- [ ] HLT idle detection in Bochs emulator (fallback auto-checkpoint)
- [ ] `VmService` wired to restore from snapshot + inject entropy via 9P
