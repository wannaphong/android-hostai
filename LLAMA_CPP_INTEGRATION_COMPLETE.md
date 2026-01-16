# llama.cpp Integration Complete

## Summary

This PR successfully integrates llama.cpp into the Android HostAI application, replacing the placeholder implementation with actual model inference capabilities.

## Changes Made

### 1. Added llama.cpp as a Git Submodule
- Added llama.cpp repository as a submodule at `app/src/main/cpp/llama.cpp`
- This provides the actual inference engine for running LLM models on Android

### 2. Updated CMakeLists.txt
The build configuration now:
- Includes llama.cpp source directories
- Compiles GGML and llama.cpp sources into a static library
- Links the native library with llama.cpp
- Enables ARM NEON optimizations for ARM architectures (armeabi-v7a and arm64-v8a)

### 3. Implemented Native Inference in llama_jni.cpp
Replaced placeholder implementation with actual llama.cpp API calls:

#### `nativeInit()`
- Initializes llama backend with `llama_backend_init()`
- Creates and returns LlamaContext

#### `nativeLoadModel()`
- Loads GGUF model file using `llama_load_model_from_file()`
- Creates inference context with configurable parameters:
  - Context size: 2048 tokens
  - Batch size: 512
  - Threads: 4
- Initializes sampler chain with temperature control

#### `nativeGenerate()`
- Tokenizes input prompt
- Evaluates prompt through the model
- Generates tokens iteratively using the sampler
- Decodes tokens back to text
- Returns generated completion

#### `nativeUnload()` and `nativeFree()`
- Properly cleanup llama.cpp resources
- Free sampler, context, and model
- Prevent memory leaks

## Building the Project

To build the project with the llama.cpp integration:

```bash
# Ensure submodules are initialized
git submodule update --init --recursive

# Build the debug APK
./gradlew assembleDebug

# Or build release
./gradlew assembleRelease
```

## Testing the Model

Once built, to test the model:

1. Install the APK on an Android device
2. Copy a GGUF model file to the device using the app's file picker or adb:
   ```bash
   adb push your-model.gguf /sdcard/Download/
   ```
3. In the app:
   - Tap "Select Model" and choose your GGUF file
   - Tap "Start Server"
   - The server will start on port 8080

4. Test the API:
   ```bash
   curl http://<phone-ip>:8080/v1/chat/completions \
     -H "Content-Type: application/json" \
     -d '{
       "messages": [{"role": "user", "content": "Hello!"}],
       "temperature": 0.7,
       "max_tokens": 100
     }'
   ```

## What This Fixes

**Before**: The model returned a placeholder message saying "Native llama.cpp integration is active! This is a placeholder response..."

**After**: The model now performs actual inference using llama.cpp and generates real responses based on the loaded GGUF model.

## Performance Considerations

- **Context Size**: Set to 2048 tokens, can be adjusted in `llama_jni.cpp`
- **Batch Size**: 512 tokens for efficient processing
- **ARM NEON**: Automatically enabled for ARM devices for faster inference
- **Model Size**: Recommend using quantized models (Q4_0, Q8_0) for mobile devices
- **Memory**: Ensure sufficient RAM for your model size (typically 1-4GB for smaller models)

## Recommended Models

For Android devices, consider these model sizes:
- **Small (1-2GB RAM available)**: 1B-3B parameter models with Q4_0 quantization
- **Medium (3-4GB RAM available)**: 3B-7B parameter models with Q4_0 quantization
- **Large (6GB+ RAM available)**: 7B+ parameter models with Q4_0 or Q8_0 quantization

## Troubleshooting

### Model loading fails
- Verify the model file is a valid GGUF format
- Check that the file path is accessible to the app
- Ensure sufficient device memory

### Generation is slow
- Use a smaller or more heavily quantized model
- Reduce context size in `llama_jni.cpp`
- Ensure app has necessary CPU permissions

### App crashes
- Check logcat for native errors: `adb logcat | grep LlamaJNI`
- Verify model size is appropriate for device RAM
- Check for memory leaks with Android Profiler

## Architecture

The integration follows this architecture:

```
MainActivity (Kotlin/Java)
    ↓
LlamaModel (Kotlin) - JNI wrapper
    ↓
llama_jni.cpp (C++) - Native JNI layer
    ↓
llama.cpp - Actual inference engine
    ↓
GGUF Model File - Quantized model weights
```

## Technical Details

### Thread Safety
- Each LlamaContext is isolated
- Multiple models can be loaded (though memory intensive)
- Inference is sequential per context

### Memory Management
- Models are loaded on-demand
- Proper cleanup in `nativeUnload()` and `nativeFree()`
- KV cache is cleared before each generation

### Sampling
- Temperature-based sampling supported
- Sampler is recreated per generation to apply temperature
- Uses llama.cpp's sampler chain for flexible control

## Future Enhancements

Possible improvements for future PRs:
- GPU/Vulkan acceleration support
- Streaming token generation with callbacks
- Multiple model support
- Context caching for chat conversations
- LoRA adapter support
- Quantization at runtime

## References

- [llama.cpp GitHub](https://github.com/ggerganov/llama.cpp)
- [GGUF Format Documentation](https://github.com/ggerganov/ggml/blob/master/docs/gguf.md)
- [Android NDK Guide](https://developer.android.com/ndk/guides)
