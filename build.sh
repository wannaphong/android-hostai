#!/bin/bash
# Build verification script for HostAI Android app
# This script requires network access to download Android SDK dependencies

set -e

echo "=== HostAI Build Verification ==="
echo ""

# Check Java version
echo "Checking Java version..."
java -version

echo ""
echo "Building Android application..."
./gradlew clean assembleDebug --no-daemon

echo ""
echo "Build artifacts location:"
ls -lh app/build/outputs/apk/debug/

echo ""
echo "=== Build completed successfully! ==="
echo ""
echo "To install on a connected device, run:"
echo "  ./gradlew installDebug"
