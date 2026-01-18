# HostAI - Android LLM API Server

An Android application that uses [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) to host an OpenAI-compatible API server, allowing you to run LLM models on your phone as a web service.

## Features

- 🚀 OpenAI-compatible API endpoints
- 💬 Multi-session conversation support - maintain separate conversation contexts for multiple users
- 🔧 Function calling / Tool support - enable models to call predefined functions
- 📱 Native Android app with Material Design UI
- 🔄 Foreground service for reliable server operation
- 🌐 Local network access via WiFi
- 🔌 Compatible with OpenAI client libraries
- ⚡ Optimized for ARM-based Android devices using LiteRT with GPU acceleration

## API Endpoints

The server implements the following OpenAI-compatible endpoints:

- `GET /v1/models` - List available models
- `POST /v1/chat/completions` - Chat completions (ChatGPT-style) with multi-session support and function calling
- `POST /v1/completions` - Text completions with multi-session support
- `GET /v1/sessions` - List active conversation sessions
- `DELETE /v1/sessions/{sessionId}` - Clear a specific conversation session
- `DELETE /v1/sessions` - Clear all conversation sessions
- `GET /health` - Health check endpoint
- `GET /` - Web interface with API documentation
- `GET /chat` - Web-based chat UI (powered by [AI-QL/chat-ui](https://github.com/AI-QL/chat-ui))

## Building

### Prerequisites

- Android Studio (2022.3 or later)
- Android SDK (API level 26+)
- JDK 8 or higher

Note: With the LiteRT library integration, you no longer need to manually build llama.cpp or configure NDK/CMake.

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
2. Select a LiteRT model file (.litertlm) from your device storage (optional, for testing you can start without a model)
3. Tap "Start Server" to begin the API server
4. The server will start on port 8080 by default
5. Use the displayed IP address to access the API from other devices on the same network

### Using the Chat UI

The easiest way to interact with your model is through the built-in web chat interface:

1. After starting the server, open a web browser on any device on the same network
2. Navigate to `http://<phone-ip>:8080/chat`
3. The chat interface will automatically connect to your local API
4. Start chatting with your model!

The chat UI is powered by [AI-QL/chat-ui](https://github.com/AI-QL/chat-ui) and comes pre-configured to work with your local API endpoint. It supports:
- Real-time streaming responses
- Markdown rendering
- Chat history management
- Multimodal inputs (when using vision models)

### Getting LiteRT Models

You'll need a LiteRT model file to use this app. You can:

- Download pre-converted LiteRT models from [HuggingFace LiteRT Community](https://huggingface.co/litert-community)
- Popular LiteRT models include:
  - [Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT) (557 MB, 4-bit quantized)
  - [Phi-4-mini](https://huggingface.co/litert-community/Phi-4-mini-instruct) (3.7 GB, 8-bit quantized)
  - [Qwen2.5-1.5B](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct) (1.5 GB, 8-bit quantized)

LiteRT models (.litertlm) are optimized for mobile devices with GPU acceleration support.

### Multi-Session Support

HostAI supports multiple concurrent conversation sessions, allowing you to maintain separate conversation contexts. This is useful for:
- Supporting multiple users or clients simultaneously
- Maintaining different conversation threads
- Isolating different tasks or contexts

Specify a session using:
- `conversation_id` field in the request body (OpenAI Conversations API standard)
- `user` field (OpenAI standard)
- `session_id` field in the request body
- `X-Session-ID` HTTP header

Example with session:
```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-model",
    "conversation_id": "alice",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'
```

See [API_USAGE.md](API_USAGE.md) for more examples and session management endpoints.

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

### Function Calling / Tools

HostAI supports function calling, allowing models to call predefined functions. This requires a model with tool support (e.g., [FunctionGemma](https://huggingface.co/google/functiongemma-270m-it)).

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-model",
    "messages": [{"role": "user", "content": "What is the weather in Paris?"}],
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "get_weather",
          "description": "Get the current weather for a city",
          "parameters": {
            "type": "object",
            "properties": {
              "city": {"type": "string", "description": "The city name"}
            },
            "required": ["city"]
          }
        }
      }
    ]
  }'
```

Built-in example tools include:
- `get_weather` - Get weather for a city
- `sum` - Calculate sum of numbers
- `get_current_time` - Get current time in a timezone

See [API_USAGE.md](API_USAGE.md) for detailed function calling examples and more information.


## Architecture

- **MainActivity** - User interface for controlling the server and selecting models
- **ApiServerService** - Foreground service that runs the HTTP server
- **OpenAIApiServer** - Javalin-based web server with OpenAI-compatible endpoints and SSE streaming support
- **LlamaModel** - Model interface using LiteRT library for native LLM inference

## Implementation

This app uses the [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) library, which provides:

- Native LLM inference optimized for Android/ARM devices
- GPU acceleration support for faster inference on supported devices
- CPU fallback for universal device compatibility
- Efficient model loading and context management
- Easy-to-use Kotlin API with synchronous and asynchronous inference

The library handles all native code compilation and optimization, so you don't need to manually configure NDK, CMake, or build native code yourself.

## Requirements

- Android 8.0 (API level 26) or higher
- ARM64 or x86_64 processor (64-bit architectures)
- Permissions: INTERNET, FOREGROUND_SERVICE, ACCESS_NETWORK_STATE, READ_EXTERNAL_STORAGE

## License

Apache License 2.0 - See LICENSE file for details

## Acknowledgments

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) - Language model runtime for edge devices
- [LiteRT](https://github.com/google-ai-edge/LiteRT) - TensorFlow Lite runtime
- [Javalin](https://javalin.io/) - Simple and modern web framework for Java and Kotlin

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
