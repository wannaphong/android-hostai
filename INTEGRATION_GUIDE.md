# llama.cpp Integration Guide

This guide explains how to complete the llama.cpp integration for the Android HostAI app.

## Current Status

✅ **Completed:**
- JNI interface implemented (`app/src/main/cpp/llama_jni.cpp`)
- CMake build configuration created (`app/src/main/cpp/CMakeLists.txt`)
- NDK support configured in `app/build.gradle.kts`
- Kotlin/Java bindings ready in `LlamaModel.kt`
- ProGuard rules configured for native methods

⚠️ **Remaining:**
- Add llama.cpp source files or library
- Update CMakeLists.txt to build llama.cpp
- Implement actual llama.cpp API calls in JNI wrapper

## Quick Start (Automated)

Run the provided script to add llama.cpp as a submodule:

```bash
./add_llama_cpp.sh
```

This will:
1. Add llama.cpp as a git submodule
2. Update CMakeLists.txt with llama.cpp build configuration
3. Provide instructions for the remaining manual steps

## Manual Integration Steps

### Option 1: Using Git Submodule (Recommended)

1. **Add llama.cpp as a submodule:**

```bash
cd app/src/main/cpp
git submodule add https://github.com/ggerganov/llama.cpp.git
git submodule update --init --recursive
cd ../../..
```

2. **Update CMakeLists.txt** to build llama.cpp. Add these sections:

```cmake
# Configure llama.cpp
set(GGML_LTO OFF)
set(GGML_STATIC ON)

# Enable NEON optimizations for ARM
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NEON ON)
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

# Update the hostai library to link against llama
target_link_libraries(hostai
        llama
        ${log-lib})
```

### Option 2: Copy Source Files

If you prefer not to use submodules:

1. Download llama.cpp from https://github.com/ggerganov/llama.cpp
2. Copy the following directories to `app/src/main/cpp/llama.cpp/`:
   - `src/` (llama.cpp source files)
   - `ggml/src/` (GGML source files)
   - `include/` (header files)
   - `ggml/include/` (GGML headers)
3. Follow the CMakeLists.txt update from Option 1

### Option 3: Pre-built Library

For faster builds, you can use a pre-built llama.cpp library:

1. Build llama.cpp for Android using standalone NDK
2. Copy the built libraries to `app/src/main/jniLibs/<ABI>/libllama.so`
3. Update CMakeLists.txt to link against the pre-built library:

```cmake
find_library(llama-lib llama)
target_link_libraries(hostai ${llama-lib} ${log-lib})
```

## Implementing the Native Code

After adding llama.cpp, update `app/src/main/cpp/llama_jni.cpp`:

### 1. Include llama.cpp headers

At the top of the file, uncomment and add:

```cpp
#include "llama.h"
#include "common.h"
```

### 2. Update LlamaContext structure

Replace the placeholder structure with:

```cpp
struct LlamaContext {
    llama_model *model;
    llama_context *ctx;
    llama_sampler *sampler;
    std::string model_path;
    bool is_loaded;
    
    LlamaContext() : model(nullptr), ctx(nullptr), sampler(nullptr), is_loaded(false) {}
};
```

### 3. Implement nativeLoadModel

Replace the placeholder implementation:

```cpp
JNIEXPORT jboolean JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeLoadModel(
        JNIEnv *env, jobject thiz, jlong context_ptr, jstring model_path) {
    
    LlamaContext *llamaCtx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!llamaCtx) {
        LOGE("Invalid context pointer");
        return JNI_FALSE;
    }
    
    const char *path_str = env->GetStringUTFChars(model_path, nullptr);
    llamaCtx->model_path = std::string(path_str);
    env->ReleaseStringUTFChars(model_path, path_str);
    
    LOGI("Loading model from: %s", llamaCtx->model_path.c_str());
    
    // Initialize llama backend
    llama_backend_init();
    
    // Load model
    llama_model_params model_params = llama_model_default_params();
    llamaCtx->model = llama_load_model_from_file(llamaCtx->model_path.c_str(), model_params);
    
    if (!llamaCtx->model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }
    
    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 512;
    
    llamaCtx->ctx = llama_new_context_with_model(llamaCtx->model, ctx_params);
    
    if (!llamaCtx->ctx) {
        LOGE("Failed to create context");
        llama_free_model(llamaCtx->model);
        llamaCtx->model = nullptr;
        return JNI_FALSE;
    }
    
    // Create sampler
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llamaCtx->sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(llamaCtx->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(llamaCtx->sampler, llama_sampler_init_dist(0));
    
    llamaCtx->is_loaded = true;
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}
```

### 4. Implement nativeGenerate

Replace the placeholder with actual inference:

```cpp
JNIEXPORT jstring JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeGenerate(
        JNIEnv *env, jobject thiz, jlong context_ptr, 
        jstring prompt, jint max_tokens, jfloat temperature) {
    
    LlamaContext *llamaCtx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!llamaCtx || !llamaCtx->is_loaded) {
        LOGE("Context not initialized or model not loaded");
        return env->NewStringUTF("Error: Model not loaded");
    }
    
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_string(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);
    
    LOGI("Generating response for prompt: %s", prompt_string.c_str());
    
    // Tokenize the prompt
    std::vector<llama_token> tokens;
    tokens.resize(prompt_string.length() + 1);
    int n_tokens = llama_tokenize(
        llamaCtx->model,
        prompt_string.c_str(),
        prompt_string.length(),
        tokens.data(),
        tokens.size(),
        true,
        false
    );
    tokens.resize(n_tokens);
    
    // Evaluate the prompt
    if (llama_decode(llamaCtx->ctx, llama_batch_get_one(tokens.data(), tokens.size())) != 0) {
        LOGE("Failed to evaluate prompt");
        return env->NewStringUTF("Error: Failed to evaluate prompt");
    }
    
    // Generate tokens
    std::string result;
    for (int i = 0; i < max_tokens; i++) {
        llama_token new_token = llama_sampler_sample(llamaCtx->sampler, llamaCtx->ctx, -1);
        
        if (llama_token_is_eog(llamaCtx->model, new_token)) {
            break;
        }
        
        // Decode token to text
        char buf[256];
        int n = llama_token_to_piece(llamaCtx->model, new_token, buf, sizeof(buf), 0, false);
        if (n > 0) {
            result.append(buf, n);
        }
        
        // Feed the new token back for next prediction
        if (llama_decode(llamaCtx->ctx, llama_batch_get_one(&new_token, 1)) != 0) {
            LOGE("Failed to decode token");
            break;
        }
    }
    
    return env->NewStringUTF(result.c_str());
}
```

### 5. Implement cleanup functions

Update nativeUnload and nativeFree:

```cpp
JNIEXPORT void JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeUnload(JNIEnv *env, jobject thiz, jlong context_ptr) {
    LlamaContext *llamaCtx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!llamaCtx) {
        return;
    }
    
    LOGI("Unloading model");
    
    if (llamaCtx->sampler) {
        llama_sampler_free(llamaCtx->sampler);
        llamaCtx->sampler = nullptr;
    }
    
    if (llamaCtx->ctx) {
        llama_free(llamaCtx->ctx);
        llamaCtx->ctx = nullptr;
    }
    
    if (llamaCtx->model) {
        llama_free_model(llamaCtx->model);
        llamaCtx->model = nullptr;
    }
    
    llamaCtx->is_loaded = false;
}

JNIEXPORT void JNICALL
Java_com_wannaphong_hostai_LlamaModel_nativeFree(JNIEnv *env, jobject thiz, jlong context_ptr) {
    LlamaContext *llamaCtx = reinterpret_cast<LlamaContext *>(context_ptr);
    if (!llamaCtx) {
        return;
    }
    
    LOGI("Freeing LlamaContext");
    
    // Make sure everything is cleaned up
    if (llamaCtx->sampler) {
        llama_sampler_free(llamaCtx->sampler);
    }
    if (llamaCtx->ctx) {
        llama_free(llamaCtx->ctx);
    }
    if (llamaCtx->model) {
        llama_free_model(llamaCtx->model);
    }
    
    delete llamaCtx;
}
```

## Building the App

Once you've completed the integration:

```bash
./gradlew assembleDebug
```

The native library will be compiled for all configured ABIs and packaged into the APK.

## Adding a Model File

1. Download a GGUF format model from Hugging Face or another source
2. Copy it to your Android device:
   ```bash
   adb push model.gguf /sdcard/Download/
   ```
3. In the app, update the model path to point to the file location

## Troubleshooting

### Build Issues

**Problem:** CMake can't find llama.cpp files
- **Solution:** Make sure the submodule is properly initialized: `git submodule update --init --recursive`

**Problem:** Compiler errors about unknown symbols
- **Solution:** Check that all necessary llama.cpp source files are included in CMakeLists.txt

**Problem:** Linking errors
- **Solution:** Ensure llama library is listed before ${log-lib} in target_link_libraries

### Runtime Issues

**Problem:** "java.lang.UnsatisfiedLinkError: dlopen failed"
- **Solution:** Check that libhostai.so exists in the APK for your device's ABI
- **Verify:** `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libhostai.so`

**Problem:** Model loading fails
- **Solution:** Verify the model file path is accessible and the file is a valid GGUF format

**Problem:** Generation is too slow
- **Solution:** 
  - Reduce context size (n_ctx) in llama_context_params
  - Use a smaller/quantized model
  - Enable GPU acceleration (requires additional configuration)

## Testing

### Debug Native Code

View native logs:
```bash
adb logcat | grep LlamaJNI
```

### Test Model Loading

```bash
adb shell run-as com.wannaphong.hostai ls -l /data/data/com.wannaphong.hostai/files/
```

### Performance Testing

Monitor memory usage:
```bash
adb shell dumpsys meminfo com.wannaphong.hostai
```

## Advanced Features

### GPU Acceleration

To enable Vulkan/GPU acceleration (experimental):

1. Add Vulkan support to CMakeLists.txt
2. Set `GGML_VULKAN=ON` in CMake configuration
3. Add Vulkan dependencies to build.gradle.kts

### Streaming Generation

To implement true streaming (token-by-token):

1. Add a callback parameter to nativeGenerate
2. Use JNI to call back to Kotlin after each token
3. Update the Kotlin code to handle streaming callbacks

## Resources

- [llama.cpp Documentation](https://github.com/ggerganov/llama.cpp)
- [Android NDK Guide](https://developer.android.com/ndk/guides)
- [JNI Tips](https://developer.android.com/training/articles/perf-jni)
- [GGUF Model Format](https://github.com/ggerganov/ggml/blob/master/docs/gguf.md)

## Support

For issues specific to:
- **llama.cpp integration**: Check llama.cpp GitHub issues
- **Android build issues**: Check Android NDK documentation
- **This app**: Open an issue in this repository
