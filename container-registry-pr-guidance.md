# Container Registry PR Guidance

## Current state

Branch `claude/lazy-container-registry-OFP1Q` (commit `b39df53`) has a working
OCI registry client with eStargz lazy pulling. However, it cannot be merged
as-is because:

- It deletes 16 unrelated files (gauge UI, IME helpers, ANSI parser, tests,
  VmService, notification icon, drawables)
- It rewrites MainActivity from scratch
- It removes Compose, downgrades compileSdk and AndroidX versions
- It strips test infrastructure and build features

All of this happened because the registry code was added directly into the app
module and the build was forced to compile by removing conflicts.

## What needs to happen

### 1. Rebase onto current master

Current HEAD is `c61d350` on `master` (tag `v2.5.0`). The branch must be
rebased onto this. The recent commits added a foreground `VmService` that owns
the VM lifecycle, a notification icon, and pause/resume support for
`SystemStatsProvider`. None of that should be lost.

### 2. Create a standalone Gradle module

The registry code is pure JVM — it uses OkHttp and kotlinx-serialization with
zero Android imports. It should live in its own module:

```
container-registry/
├── build.gradle.kts
└── src/
    ├── main/kotlin/com/example/c2wdemo/registry/
    │   ├── ContainerPuller.kt
    │   ├── EstargzParser.kt
    │   ├── ImageReference.kt
    │   ├── LazyLayerReader.kt
    │   ├── OciModels.kt
    │   ├── RegistryClient.kt
    │   ├── TarReader.kt
    │   └── WasmArtifactProvider.kt   ← new (see section 3)
    └── test/kotlin/com/example/c2wdemo/registry/
        └── ...                        ← JVM unit tests (no Android needed)
```

`build.gradle.kts` for the module:

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

Add to `settings.gradle.kts`:

```kotlin
include(":container-registry")
```

### 3. Add a WasmArtifactProvider on top of the raw OCI client

The current API stops at `PullResult.readFile(path)` — the consuming app has to
know OCI layer internals, know which file path holds the wasm binary, manage
caching, and handle staleness. That's registry-side knowledge leaking into the
app.

The module should provide a higher-level artifact provider that owns storage:

```kotlin
class WasmArtifactProvider(
    private val cacheDir: File,
    private val onProgress: ((String) -> Unit)? = null,
) {
    /**
     * Ensure the wasm artifact for the given image ref is available locally.
     *
     * - If a valid cached copy exists (digest matches remote), returns it
     *   immediately.
     * - Otherwise, pulls the image, extracts the wasm/aot binary from the
     *   correct layer, writes it to disk, and returns the local file.
     *
     * The returned File is a flat binary ready to pass to
     * WamrRuntime.loadModule(file.readBytes()).
     */
    suspend fun ensure(imageRef: String): File { ... }

    /**
     * Check whether a cached artifact exists for this ref without hitting
     * the network.
     */
    fun isCached(imageRef: String): Boolean { ... }

    /**
     * Delete cached artifacts.
     */
    fun evict(imageRef: String) { ... }
    fun evictAll() { ... }

    fun shutdown() { ... }
}
```

Key responsibilities that belong here (not in the app):

| Concern | Owner |
|---------|-------|
| Knowing the wasm binary path inside the OCI image layers | Registry module |
| Content-addressable caching (digest-keyed, survives app restarts) | Registry module |
| Staleness check (has the tag moved to a new digest?) | Registry module |
| Extracting the artifact from tar/gzip layers to a flat file | Registry module |
| Cleaning up intermediate OCI layer data after extraction | Registry module |

What the app provides:

| Concern | Owner |
|---------|-------|
| Cache directory (e.g. `context.filesDir` or `context.cacheDir`) | App |
| Image reference string | App |
| Progress callback for UI | App |

The integration in the app becomes trivial:

```kotlin
// In VmService.kt
val provider = WasmArtifactProvider(cacheDir = filesDir, onProgress = ::deliverOutput)
val wasmFile = provider.ensure("ghcr.io/maceip/alpine:latest")
val wasmBytes = wasmFile.readBytes()
WamrRuntime.loadModule(wasmBytes)
```

### 4. Existing public API to keep

The lower-level OCI client API (`ContainerPuller`, `PullResult`,
`LazyLayerReader`, etc.) is still useful for advanced use cases and should
remain public. `WasmArtifactProvider` is a convenience layer on top — not a
replacement.

Summary of the module's public surface:

| Class | Purpose |
|-------|---------|
| `WasmArtifactProvider` | High-level: give ref, get local File |
| `ContainerPuller` | Mid-level: pull image, get PullResult |
| `PullResult` | Lazy file access into pulled image |
| `RegistryClient` | Low-level: OCI HTTP operations |
| `ImageReference` | Parse image ref strings |
| `CacheStats` | Cache diagnostics |
| OCI model types | Manifest, config, descriptor data classes |

### 5. What the PR should touch

**New files (all in `container-registry/`):**
- `build.gradle.kts`
- All source files under `src/main/kotlin/`
- JVM unit tests under `src/test/kotlin/`

**Modified files in existing modules:**
- `settings.gradle.kts` — add `include(":container-registry")`

**Nothing else.** Do not modify:
- `android-app-wamr/` (no app module changes)
- `.github/workflows/android.yml` (no CI changes)
- Any existing source files, drawables, layouts, or tests

The app module can add `implementation(project(":container-registry"))` in a
follow-up PR once the module is merged and reviewed independently.

### 6. Tests

Since this is pure JVM, tests should run with plain JUnit + MockWebServer (OkHttp
ships one). No Robolectric or Android instrumentation needed. Cover at least:

- `ImageReference.parse()` with various ref formats
- `RegistryClient` against MockWebServer (token auth, manifest fetch, range requests)
- `EstargzParser` with a known eStargz blob
- `WasmArtifactProvider.ensure()` end-to-end against MockWebServer
- `WasmArtifactProvider.isCached()` / `evict()` with temp directories
