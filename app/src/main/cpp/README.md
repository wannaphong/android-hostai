# Native Code - llama.cpp Integration

This directory contains the JNI (Java Native Interface) code for integrating llama.cpp with the Android app.

## Files

- **llama_jni.cpp**: JNI wrapper that bridges Kotlin/Java code with C++ llama.cpp implementation
- **CMakeLists.txt**: CMake build configuration for compiling the native library

## Current Status

The JNI interface is implemented and functional. The native library (`libhostai.so`) will be built for the following architectures:
- armeabi-v7a (32-bit ARM)
- arm64-v8a (64-bit ARM)
- x86 (32-bit x86 for emulators)
- x86_64 (64-bit x86 for emulators)

**Note**: This is currently a placeholder implementation. To complete the integration with actual llama.cpp inference:

## Completing the Integration

### Option 1: Add llama.cpp as a Git Submodule

```bash
cd app/src/main/cpp
git submodule add https://github.com/ggerganov/llama.cpp.git
```

Then update `CMakeLists.txt` to include llama.cpp source files.

### Option 2: Use Pre-built llama.cpp Library

1. Build llama.cpp for Android using the Android NDK
2. Copy the built libraries to `app/src/main/jniLibs/`
3. Update `CMakeLists.txt` to link against the pre-built library

### Option 3: Include llama.cpp Sources Directly

1. Copy the necessary llama.cpp source files to this directory:
   - `llama.cpp`
   - `llama.h`
   - `ggml.c`
   - `ggml.h`
   - Other required files

2. Update `CMakeLists.txt` to compile these files:

```cmake
# Add llama.cpp source files
set(LLAMA_SOURCES
    llama.cpp
    ggml.c
    ggml-alloc.c
    ggml-backend.c
    ggml-quants.c
    # Add other required sources
)

add_library(llama STATIC ${LLAMA_SOURCES})

# Enable NEON optimizations for ARM
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(llama PRIVATE GGML_USE_NEON)
endif()

# Link llama library with the JNI wrapper
target_link_libraries(hostai llama ${log-lib})
```

3. Update `llama_jni.cpp` to uncomment and implement the actual llama.cpp API calls:
   - Include `llama.h`
   - Use `llama_load_model_from_file()` in `nativeLoadModel()`
   - Use `llama_tokenize()`, `llama_decode()`, and `llama_sample()` in `nativeGenerate()`
   - Add proper cleanup in `nativeUnload()` and `nativeFree()`

## JNI Interface

The following native methods are exposed to Kotlin:

### nativeInit(): Long
Initializes a native context and returns a pointer to it.

### nativeLoadModel(contextPtr: Long, modelPath: String): Boolean
Loads a GGUF model file from the specified path.

### nativeGenerate(contextPtr: Long, prompt: String, maxTokens: Int, temperature: Float): String
Generates text based on the prompt using the loaded model.

### nativeIsLoaded(contextPtr: Long): Boolean
Checks if a model is currently loaded.

### nativeUnload(contextPtr: Long): Void
Unloads the current model and frees its memory.

### nativeFree(contextPtr: Long): Void
Frees the native context.

## Building

The native library is automatically built when you build the Android app:

```bash
./gradlew assembleDebug
```

The compiled `.so` files will be packaged into the APK under `lib/<arch>/libhostai.so`.

## Model Files

To use the app with actual models:

1. Download a GGUF format model (e.g., from Hugging Face)
2. Place it in the device storage
3. Update the model path in the app code or add model selection UI

Example model locations:
- Internal storage: `/data/data/com.wannaphong.hostai/files/models/`
- External storage: `/sdcard/Download/models/`

## Debugging

To see native logs:
```bash
adb logcat | grep LlamaJNI
```

## Resources

- [llama.cpp GitHub](https://github.com/ggerganov/llama.cpp)
- [Android NDK Documentation](https://developer.android.com/ndk)
- [JNI Best Practices](https://developer.android.com/training/articles/perf-jni)
