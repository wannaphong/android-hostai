# HostAI - Android LLaMA API Server

An Android application that uses [kotlinllamacpp](https://github.com/ljcamargo/kotlinllamacpp) to host an OpenAI-compatible API server, allowing you to run LLM models on your phone as a web service.

## Features

- 🚀 OpenAI-compatible API endpoints
- 📱 Native Android app with Material Design UI
- 🔄 Foreground service for reliable server operation
- 🌐 Local network access via WiFi
- 🔌 Compatible with OpenAI client libraries
- ⚡ Optimized for ARM-based Android devices using kotlinllamacpp

## API Endpoints

The server implements the following OpenAI-compatible endpoints:

- `GET /v1/models` - List available models
- `POST /v1/chat/completions` - Chat completions (ChatGPT-style)
- `POST /v1/completions` - Text completions
- `GET /health` - Health check endpoint
- `GET /` - Web interface with API documentation

## Building

### Prerequisites

- Android Studio (2022.3 or later)
- Android SDK (API level 24+)
- JDK 8 or higher

Note: With the kotlinllamacpp library integration, you no longer need to manually build llama.cpp or configure NDK/CMake.

### Build Instructions

1. Clone the repository:
   ```bash
   git clone https://github.com/wannaphong/android-hostai.git
   cd android-hostai
   ```

2. Open the project in Android Studio

3. Build the project:
   ```bash
   ./gradlew assembleDebug
   ```

4. Install on device:
   ```bash
   ./gradlew installDebug
   ```

## Usage

1. Install and launch the app on your Android device
2. Select a GGUF model file from your device storage (optional, for testing you can start without a model)
3. Tap "Start Server" to begin the API server
4. The server will start on port 8080 by default
5. Use the displayed IP address to access the API from other devices on the same network

### Getting GGUF Models

You'll need a GGUF model file to use this app. You can:

- Download pre-converted GGUF models from [HuggingFace](https://huggingface.co/search/full-text?q=GGUF&type=model)
- Convert your own models following the [llama.cpp quantization guide](https://github.com/ggerganov/llama.cpp#prepare-and-quantize)

Quantized models (Q4, Q5, Q8) work particularly well on mobile devices.

### Example API Call

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-model",
    "messages": [{"role": "user", "content": "Hello!"}],
    "temperature": 0.7
  }'
```

### Using with OpenAI Python Client

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://<phone-ip>:8080/v1",
    api_key="not-needed"
)

response = client.chat.completions.create(
    model="llama-model",
    messages=[{"role": "user", "content": "Hello!"}]
)

print(response.choices[0].message.content)
```

## Architecture

- **MainActivity** - User interface for controlling the server and selecting models
- **ApiServerService** - Foreground service that runs the HTTP server
- **OpenAIApiServer** - NanoHTTPD-based web server with OpenAI-compatible endpoints
- **LlamaModel** - Model interface using kotlinllamacpp library for native llama.cpp integration

## Implementation

This app uses the [kotlinllamacpp](https://github.com/ljcamargo/kotlinllamacpp) library, which provides:

- Native llama.cpp bindings optimized for Android/ARM devices
- Automatic CPU feature detection (i8mm, dotprod) for hardware-accelerated inference
- Context management and efficient mobile inference
- Easy-to-use Kotlin API

The library handles all native code compilation and optimization, so you don't need to manually configure NDK, CMake, or build llama.cpp yourself.

## Requirements

- Android 7.0 (API level 24) or higher
- ARM64 or x86_64 processor (64-bit architectures)
- Permissions: INTERNET, FOREGROUND_SERVICE, ACCESS_NETWORK_STATE, READ_EXTERNAL_STORAGE

## License

Apache License 2.0 - See LICENSE file for details

## Acknowledgments

- [kotlinllamacpp](https://github.com/ljcamargo/kotlinllamacpp) - Kotlin bindings for llama.cpp
- [llama.cpp](https://github.com/ggerganov/llama.cpp) - LLaMA inference in C/C++
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) - Lightweight HTTP server

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.