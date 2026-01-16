#!/bin/bash
# Script to add llama.cpp integration to the project
# This should be run from the project root directory

set -e

echo "=== Adding llama.cpp to Android HostAI ==="
echo ""

# Check if we're in the right directory
if [ ! -f "app/build.gradle.kts" ]; then
    echo "Error: This script must be run from the project root directory"
    exit 1
fi

# Navigate to the cpp directory
cd app/src/main/cpp

echo "Step 1: Adding llama.cpp as a git submodule..."
if [ -d "llama.cpp" ]; then
    echo "llama.cpp directory already exists, skipping clone..."
else
    git submodule add https://github.com/ggerganov/llama.cpp.git
    git submodule update --init --recursive
fi

echo ""
echo "Step 2: Updating CMakeLists.txt to build llama.cpp..."

# Create updated CMakeLists.txt with llama.cpp integration
cat > CMakeLists.txt << 'EOF'
cmake_minimum_required(VERSION 3.22.1)

project("hostai")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add compiler flags for optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fvisibility=hidden")

# Find the log library
find_library(log-lib log)

# Configure llama.cpp
set(GGML_LTO OFF)
set(GGML_STATIC ON)

# Enable NEON optimizations for ARM
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NEON ON)
    message(STATUS "Enabling NEON optimizations for ${ANDROID_ABI}")
endif()

# Add llama.cpp source files
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

include_directories(${LLAMA_DIR})
include_directories(${LLAMA_DIR}/include)
include_directories(${LLAMA_DIR}/ggml/include)

# Add ggml sources
file(GLOB GGML_SOURCES 
    ${LLAMA_DIR}/ggml/src/*.c
    ${LLAMA_DIR}/ggml/src/*.cpp
)

# Add llama.cpp sources
set(LLAMA_SOURCES
    ${LLAMA_DIR}/src/llama.cpp
    ${LLAMA_DIR}/src/llama-vocab.cpp
    ${LLAMA_DIR}/src/llama-grammar.cpp
    ${LLAMA_DIR}/src/llama-sampling.cpp
)

# Create llama static library
add_library(llama STATIC ${GGML_SOURCES} ${LLAMA_SOURCES})

# Enable NEON for ARM architectures
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(llama PRIVATE GGML_USE_NEON)
endif()

# Create the shared library for JNI wrapper
add_library(hostai
        SHARED
        llama_jni.cpp)

# Link against Android log library and llama
target_link_libraries(hostai
        llama
        ${log-lib})
EOF

echo "CMakeLists.txt updated successfully!"
echo ""

cd ../../../../

echo "Step 3: Updating llama_jni.cpp to use actual llama.cpp API..."
echo "  (This requires manual code editing - see app/src/main/cpp/README.md)"
echo ""

echo "=== Integration steps completed! ==="
echo ""
echo "Next steps:"
echo "1. Review the generated CMakeLists.txt and adjust if needed"
echo "2. Edit app/src/main/cpp/llama_jni.cpp to uncomment and implement llama.cpp API calls"
echo "3. Build the project: ./gradlew assembleDebug"
echo "4. Add a GGUF model file to test with"
echo ""
echo "See app/src/main/cpp/README.md for detailed instructions."
