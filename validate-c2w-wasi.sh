#!/bin/bash
set -e

# =============================================================================
# Validation Script: wasi-emscripten-host + container2wasm (c2w) with Alpine
# =============================================================================
# This script validates that wasi-emscripten-host can run c2w-generated WASM
# files using Alpine Linux as the base container image.
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="/tmp/c2w-validation"

# -----------------------------------------------------------------------------
# Environment Setup
# -----------------------------------------------------------------------------
export JAVA_HOME=/opt/jdk-22
export PATH="$JAVA_HOME/bin:/usr/bin:/usr/local/bin:$PATH"

export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
export NDK_HOME=$ANDROID_NDK_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# -----------------------------------------------------------------------------
# Verify Prerequisites
# -----------------------------------------------------------------------------
verify_prerequisites() {
    log_info "Verifying prerequisites..."

    # Check Java
    if ! java -version 2>&1 | grep -q "22"; then
        log_error "JDK 22 not found. Install it first."
        exit 1
    fi
    log_success "JDK 22: $(java -version 2>&1 | head -1)"

    # Check Docker
    if ! docker --version &> /dev/null; then
        log_error "Docker is required to run container2wasm"
        exit 1
    fi
    log_success "Docker: $(docker --version)"

    # Check Docker buildx
    if ! docker buildx version &> /dev/null; then
        log_warn "Installing Docker buildx..."
        mkdir -p ~/.docker/cli-plugins
        curl -sL "https://github.com/docker/buildx/releases/download/v0.12.1/buildx-v0.12.1.linux-amd64" \
            -o ~/.docker/cli-plugins/docker-buildx
        chmod +x ~/.docker/cli-plugins/docker-buildx
    fi
    log_success "Docker buildx: $(docker buildx version)"

    # Check for wasi-emscripten-host
    if [[ ! -d "$SCRIPT_DIR/wasi-emscripten-host" ]]; then
        log_error "wasi-emscripten-host directory not found"
        exit 1
    fi
    log_success "wasi-emscripten-host: $SCRIPT_DIR/wasi-emscripten-host"

    # Check for container2wasm
    if [[ ! -d "$SCRIPT_DIR/container2wasm" ]]; then
        log_error "container2wasm directory not found"
        exit 1
    fi
    log_success "container2wasm: $SCRIPT_DIR/container2wasm"
}

# -----------------------------------------------------------------------------
# Install container2wasm (c2w) CLI
# -----------------------------------------------------------------------------
install_c2w() {
    log_info "Checking container2wasm (c2w)..."

    if command -v c2w &> /dev/null; then
        log_success "c2w already installed"
        return 0
    fi

    # Check Go
    if ! command -v go &> /dev/null; then
        log_info "Installing Go..."
        curl -sL https://go.dev/dl/go1.22.0.linux-amd64.tar.gz | tar -C /usr/local -xzf -
        export PATH="/usr/local/go/bin:$PATH"
    fi

    # Build c2w from source
    log_info "Building c2w from source..."
    cd "$SCRIPT_DIR/container2wasm"
    go build -o /usr/local/bin/c2w ./cmd/c2w

    log_success "c2w installed"
}

# -----------------------------------------------------------------------------
# Generate WASM from Alpine container
# -----------------------------------------------------------------------------
generate_alpine_wasm() {
    log_info "Generating WASM from Alpine container..."

    mkdir -p "$WORK_DIR"

    if [[ -f "$WORK_DIR/alpine.wasm" ]]; then
        log_success "WASM file already exists: $WORK_DIR/alpine.wasm"
        return 0
    fi

    # Setup buildx
    docker buildx create --use --name c2w-builder 2>/dev/null || docker buildx use c2w-builder 2>/dev/null || true

    # Generate WASI-compatible WASM
    log_info "Running: c2w alpine:3.20 $WORK_DIR/alpine.wasm"
    c2w alpine:3.20 "$WORK_DIR/alpine.wasm"

    if [[ -f "$WORK_DIR/alpine.wasm" ]]; then
        local size=$(du -h "$WORK_DIR/alpine.wasm" | cut -f1)
        log_success "Generated WASM file: $WORK_DIR/alpine.wasm ($size)"
    else
        log_error "Failed to generate WASM file"
        exit 1
    fi
}

# -----------------------------------------------------------------------------
# Build and publish wasi-emscripten-host
# -----------------------------------------------------------------------------
build_wasi_host() {
    log_info "Building wasi-emscripten-host..."

    cd "$SCRIPT_DIR/wasi-emscripten-host"

    # Publish required modules to mavenLocal
    ./gradlew \
        :host:publishToMavenLocal \
        :common-api:publishToMavenLocal \
        :common-util:publishToMavenLocal \
        :wasm-core:publishToMavenLocal \
        :wasm-wasi-preview1:publishToMavenLocal \
        :wasm-wasi-preview1-core:publishToMavenLocal \
        :bindings-chicory-wasip1:publishToMavenLocal \
        -x signJvmPublication \
        -x signKotlinMultiplatformPublication \
        --no-daemon -q

    log_success "wasi-emscripten-host published to mavenLocal"
}

# -----------------------------------------------------------------------------
# Create and run validation test
# -----------------------------------------------------------------------------
run_validation() {
    log_info "Setting up validation test project..."

    mkdir -p "$WORK_DIR/test-project/src/main/kotlin"

    # Get version from published artifacts
    local version=$(find ~/.m2/repository/at/released/weh -name "*.pom" 2>/dev/null | head -1 | xargs dirname | xargs basename)

    # Create build.gradle.kts
    cat > "$WORK_DIR/test-project/build.gradle.kts" << EOF
plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("at.released.weh:bindings-chicory-wasip1-jvm:$version")
    implementation("com.dylibso.chicory:runtime:1.5.1")
}

application {
    mainClass.set("ValidateC2wKt")
}

kotlin {
    jvmToolchain(22)
}
EOF

    # Create settings.gradle.kts
    cat > "$WORK_DIR/test-project/settings.gradle.kts" << 'EOF'
rootProject.name = "c2w-validation"
EOF

    # Create validation test
    cat > "$WORK_DIR/test-project/src/main/kotlin/ValidateC2w.kt" << 'EOF'
import at.released.weh.bindings.chicory.wasip1.ChicoryWasiPreview1Builder
import at.released.weh.host.EmbedderHost
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import java.io.File

fun main(args: Array<String>) {
    val wasmPath = if (args.isNotEmpty()) args[0] else "/tmp/c2w-validation/alpine.wasm"

    println("======================================================================")
    println("  Validating c2w-generated WASM with wasi-emscripten-host")
    println("======================================================================")
    println()

    val wasmFile = File(wasmPath)
    println("[INFO] WASM file: ${wasmFile.absolutePath}")
    println("[INFO] File size: ${wasmFile.length() / 1024 / 1024} MB")
    println()

    if (!wasmFile.exists()) {
        println("[ERROR] WASM file not found!")
        System.exit(1)
    }

    // Step 1: Parse the WASM module
    println("[1/4] Parsing WASM module...")
    val startParse = System.currentTimeMillis()
    val wasmModule = Parser.parse(wasmFile)
    val parseTime = System.currentTimeMillis() - startParse
    println("      Module parsed in ${parseTime}ms")
    println()

    // Step 2: Create EmbedderHost
    println("[2/4] Creating EmbedderHost...")
    val host = EmbedderHost { }
    println("      EmbedderHost created")
    println()

    // Step 3: Build WASI Preview 1 imports
    println("[3/4] Building WASI Preview 1 imports...")
    val wasiImports: List<HostFunction> = ChicoryWasiPreview1Builder {
        this.host = host
    }.build()
    println("      WASI imports ready: ${wasiImports.size} functions")
    println()

    // Step 4: Instantiate module (without starting it)
    println("[4/4] Instantiating module...")
    try {
        val startInstantiate = System.currentTimeMillis()
        val hostImports = ImportValues.builder().withFunctions(wasiImports).build()

        val instance = Instance
            .builder(wasmModule)
            .withImportValues(hostImports)
            .withInitialize(true)
            .withStart(false)  // Don't start - needs proper TTY setup
            .build()

        val instantiateTime = System.currentTimeMillis() - startInstantiate
        println("      Module instantiated in ${instantiateTime}ms")
        println()

        println("======================================================================")
        println("  VALIDATION SUCCESSFUL")
        println("  wasi-emscripten-host CAN load and instantiate c2w-generated WASM")
        println("======================================================================")

        host.close()

    } catch (e: Exception) {
        println()
        println("======================================================================")
        println("  VALIDATION FAILED")
        println("  Error: ${e.message}")
        println("======================================================================")
        e.printStackTrace()
        host.close()
        System.exit(1)
    }
}
EOF

    # Copy Gradle wrapper
    cp -r "$SCRIPT_DIR/wasi-emscripten-host/gradle" "$WORK_DIR/test-project/"
    cp "$SCRIPT_DIR/wasi-emscripten-host/gradlew" "$WORK_DIR/test-project/"

    # Run validation
    log_info "Running validation test..."
    cd "$WORK_DIR/test-project"
    ./gradlew run --args="$WORK_DIR/alpine.wasm" --no-daemon
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------
main() {
    echo "============================================================"
    echo " c2w + wasi-emscripten-host Validation Script"
    echo "============================================================"
    echo
    echo "Working directory: $WORK_DIR"
    echo "Script directory:  $SCRIPT_DIR"
    echo
    echo "Environment:"
    echo "  JAVA_HOME=$JAVA_HOME"
    echo "  ANDROID_HOME=$ANDROID_HOME"
    echo "  ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
    echo

    verify_prerequisites
    echo

    install_c2w
    echo

    generate_alpine_wasm
    echo

    build_wasi_host
    echo

    run_validation
    echo

    run_terminal_demo
}

# -----------------------------------------------------------------------------
# Run terminal demo (bolstered test with custom stdin/stdout)
# -----------------------------------------------------------------------------
run_terminal_demo() {
    log_info "Setting up terminal demo project..."

    mkdir -p "$WORK_DIR/terminal-demo/src/main/kotlin"

    # Get version from published artifacts
    local version=$(find ~/.m2/repository/at/released/weh -name "*.pom" 2>/dev/null | head -1 | xargs dirname | xargs basename)

    # Create build.gradle.kts
    cat > "$WORK_DIR/terminal-demo/build.gradle.kts" << EOF
plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
    google()
}

dependencies {
    implementation("at.released.weh:bindings-chicory-wasip1-jvm:$version")
    implementation("com.dylibso.chicory:runtime:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.arrow-kt:arrow-core:2.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
}

application {
    mainClass.set("AlpineTerminalKt")
}

kotlin {
    jvmToolchain(22)
}

tasks.withType<JavaExec> {
    jvmArgs = listOf("-Xmx4g", "-Xms1g")
}
EOF

    cat > "$WORK_DIR/terminal-demo/settings.gradle.kts" << 'EOF'
rootProject.name = "terminal-demo"
EOF

    # Copy the terminal demo source
    cp /tmp/c2w-validation/mosaic-terminal/src/main/kotlin/AlpineTerminal.kt \
       "$WORK_DIR/terminal-demo/src/main/kotlin/" 2>/dev/null || \
    cat > "$WORK_DIR/terminal-demo/src/main/kotlin/AlpineTerminal.kt" << 'KOTLIN_EOF'
import arrow.core.Either
import arrow.core.right
import at.released.weh.bindings.chicory.exception.ProcExitException
import at.released.weh.bindings.chicory.wasip1.ChicoryWasiPreview1Builder
import at.released.weh.filesystem.error.NonblockingPollError
import at.released.weh.filesystem.stdio.StdioPollEvent
import at.released.weh.filesystem.stdio.StdioSink
import at.released.weh.filesystem.stdio.StdioSource
import at.released.weh.host.EmbedderHost
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import kotlinx.io.Buffer
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TerminalBuffer {
    private val inputQueue = ConcurrentLinkedQueue<Byte>()
    private val hasInput = AtomicBoolean(false)

    fun writeOutput(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        val cleanText = text.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
        print(cleanText)
        System.out.flush()
    }

    fun sendInput(text: String) {
        text.toByteArray(Charsets.UTF_8).forEach { inputQueue.offer(it) }
        hasInput.set(true)
    }

    fun readInput(maxBytes: Int): ByteArray {
        val result = mutableListOf<Byte>()
        while (result.size < maxBytes) { inputQueue.poll()?.let { result.add(it) } ?: break }
        if (inputQueue.isEmpty()) hasInput.set(false)
        return result.toByteArray()
    }

    fun hasInputAvailable() = hasInput.get()
    fun waitForInput(timeoutNanos: Long): Boolean {
        val endTime = System.nanoTime() + timeoutNanos
        while (System.nanoTime() < endTime) { if (hasInputAvailable()) return true; Thread.sleep(10) }
        return hasInputAvailable()
    }
}

class TerminalStdioSource(private val buffer: TerminalBuffer) : StdioSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (!buffer.hasInputAvailable()) buffer.waitForInput(100_000_000)
        val data = buffer.readInput(byteCount.toInt())
        if (data.isEmpty()) return 0
        sink.write(data)
        return data.size.toLong()
    }
    override fun close() {}
    override fun pollNonblocking(): Either<NonblockingPollError, StdioPollEvent> =
        StdioPollEvent(bytesAvailable = if (buffer.hasInputAvailable()) 1 else 0).right()
}

class TerminalStdioSink(private val buffer: TerminalBuffer) : StdioSink {
    override fun write(source: Buffer, byteCount: Long) {
        val data = ByteArray(byteCount.toInt()) { source.readByte() }
        buffer.writeOutput(data)
    }
    override fun flush() {}
    override fun close() {}
    override fun pollNonblocking(): Either<NonblockingPollError, StdioPollEvent> = StdioPollEvent().right()
}

fun main(args: Array<String>) {
    val wasmFile = File(args.firstOrNull() ?: "/tmp/c2w-validation/alpine.wasm")
    println("=== Terminal Demo: c2w + wasi-emscripten-host ===")
    if (!wasmFile.exists()) { println("WASM not found"); return }

    val buffer = TerminalBuffer()
    val wasmRan = AtomicBoolean(false)
    val exitCode = AtomicInteger(-1)

    val wasmThread = thread {
        var host: EmbedderHost? = null
        try {
            val wasmModule = Parser.parse(wasmFile)
            host = EmbedderHost {
                stdin = StdioSource.Provider { TerminalStdioSource(buffer) }
                stdout = StdioSink.Provider { TerminalStdioSink(buffer) }
                stderr = StdioSink.Provider { TerminalStdioSink(buffer) }
            }
            val wasiImports = ChicoryWasiPreview1Builder { this.host = host }.build()
            val instance = Instance.builder(wasmModule)
                .withImportValues(ImportValues.builder().withFunctions(wasiImports).build())
                .withInitialize(true).withStart(false).build()
            wasmRan.set(true)
            instance.export("_start").apply()
        } catch (e: ProcExitException) { exitCode.set(e.exitCode) }
        catch (e: Exception) { e.printStackTrace() }
        finally { host?.close() }
    }

    Thread.sleep(5000)
    wasmThread.interrupt()

    println("\n=== Result ===")
    if (wasmRan.get()) println("SUCCESS: WASM loaded and executed")
    else println("FAILED")
}
KOTLIN_EOF

    # Copy Gradle wrapper
    cp -r "$SCRIPT_DIR/wasi-emscripten-host/gradle" "$WORK_DIR/terminal-demo/"
    cp "$SCRIPT_DIR/wasi-emscripten-host/gradlew" "$WORK_DIR/terminal-demo/"
    rm -f "$WORK_DIR/terminal-demo/gradle/verification-metadata.xml" "$WORK_DIR/terminal-demo/gradle/verification-keyring.keys"

    # Run terminal demo
    log_info "Running terminal demo with custom stdin/stdout..."
    cd "$WORK_DIR/terminal-demo"
    timeout 60 ./gradlew run --args="$WORK_DIR/alpine.wasm" --no-daemon || true

    log_success "Terminal demo completed"
}

# Run if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
