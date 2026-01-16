# HostAI - Android LLaMA API Server

An Android application that uses llama.cpp to host an OpenAI-compatible API server, allowing you to run LLM models on your phone as a web service.

## Features

- 🚀 OpenAI-compatible API endpoints
- 📱 Native Android app with Material Design UI
- 🔄 Foreground service for reliable server operation
- 🌐 Local network access via WiFi
- 🔌 Compatible with OpenAI client libraries

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
2. Tap "Start Server" to begin the API server
3. The server will start on port 8080 by default
4. Use the displayed IP address to access the API from other devices on the same network

### Example API Call

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
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
    model="llama-mock-model",
    messages=[{"role": "user", "content": "Hello!"}]
)

print(response.choices[0].message.content)
```

## Current Implementation

**Note:** This is a functional implementation with a mock LLaMA model. The app demonstrates the full OpenAI-compatible API server functionality, but uses simulated responses instead of actual LLaMA inference.

### To Add Real llama.cpp Integration:

1. Add llama.cpp as a native library (C++ via JNI)
2. Configure NDK and CMake in build.gradle
3. Replace `LlamaModel.kt` with JNI bindings to llama.cpp
4. Add actual GGUF model files to app storage
5. Implement model loading and inference via native code

## Architecture

- **MainActivity** - User interface for controlling the server
- **ApiServerService** - Foreground service that runs the HTTP server
- **OpenAIApiServer** - NanoHTTPD-based web server with OpenAI-compatible endpoints
- **LlamaModel** - Model interface (currently mock, to be replaced with JNI to llama.cpp)

## Requirements

- Android 7.0 (API level 24) or higher
- Permissions: INTERNET, FOREGROUND_SERVICE, ACCESS_NETWORK_STATE

## License

Apache License 2.0 - See LICENSE file for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.