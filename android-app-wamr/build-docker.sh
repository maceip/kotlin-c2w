#!/bin/bash
# Build WAMR-based c2w Android app using Docker
# This provides full SDK/NDK permissions for native compilation

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Building WAMR c2w Android App ==="
echo "This uses Docker for full SDK/NDK access"
echo ""

# Ensure WAMR is available
if [ ! -d "../wamr-integration/wamr" ]; then
    echo "ERROR: WAMR not found at ../wamr-integration/wamr"
    echo "Please run: git clone https://github.com/bytecodealliance/wasm-micro-runtime.git ../wamr-integration/wamr"
    exit 1
fi

# Ensure alpine.wasm is available
if [ ! -f "app/src/main/assets/alpine.wasm" ]; then
    echo "WARNING: alpine.wasm not found in assets"
    echo "Copy it from the original app or generate with c2w"
fi

# Build Docker image
echo "Building Docker image..."
docker build -t c2w-wamr-builder .

# Run build
echo "Running build..."
docker run --rm \
    -v "$SCRIPT_DIR/../wamr-integration:/app/wamr-integration:ro" \
    -v "$SCRIPT_DIR/build-output:/app/app/build/outputs" \
    c2w-wamr-builder

echo ""
echo "=== Build Complete ==="
echo "APK location: build-output/apk/debug/app-debug.apk"
