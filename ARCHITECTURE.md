# Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         HostAI Android App                       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        User Interface Layer                      │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              MainActivity (218 lines)                      │  │
│  │  - Server control (Start/Stop)                            │  │
│  │  - Status display                                         │  │
│  │  - IP address detection                                   │  │
│  │  - URL copy & test                                        │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Service Binding
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Service Layer                             │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │          ApiServerService (140 lines)                     │  │
│  │  - Foreground Service                                     │  │
│  │  - Lifecycle management                                   │  │
│  │  - Notification handling                                  │  │
│  │  - Server instance management                             │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Manages
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       HTTP Server Layer                          │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │         OpenAIApiServer (259 lines)                       │  │
│  │  Based on NanoHTTPD                                       │  │
│  │                                                           │  │
│  │  Endpoints:                                               │  │
│  │  ┌─────────────────────────────────────────────────────┐ │  │
│  │  │ GET  /                 → Web UI                     │ │  │
│  │  │ GET  /health           → Health check               │ │  │
│  │  │ GET  /v1/models        → List models                │ │  │
│  │  │ POST /v1/completions   → Text completion            │ │  │
│  │  │ POST /v1/chat/completions → Chat completion         │ │  │
│  │  └─────────────────────────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Uses
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Model Layer                              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │            LlamaModel (47 lines)                          │  │
│  │  - Mock implementation                                    │  │
│  │  - generate() method                                      │  │
│  │  - generateStream() method                                │  │
│  │                                                           │  │
│  │  [Future: JNI bindings to llama.cpp native library]      │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                         Client Layer                             │
│                                                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │   Python    │  │  JavaScript  │  │     cURL     │           │
│  │   OpenAI    │  │    fetch     │  │   requests   │           │
│  │   Client    │  │              │  │              │           │
│  └─────────────┘  └──────────────┘  └──────────────┘           │
│                                                                   │
│  All communicate via HTTP to: http://<phone-ip>:8080             │
└─────────────────────────────────────────────────────────────────┘

═══════════════════════════════════════════════════════════════════

Data Flow Example (Chat Completion):

1. Client sends POST request to /v1/chat/completions
   {
     "model": "llama-mock-model",
     "messages": [{"role": "user", "content": "Hello"}]
   }
   
2. OpenAIApiServer.handleChatCompletions() receives request
   ↓
3. Parses JSON, extracts messages and parameters
   ↓
4. Calls LlamaModel.generate() with prompt
   ↓
5. LlamaModel returns generated text (mock/JNI)
   ↓
6. OpenAIApiServer formats response in OpenAI format
   {
     "id": "chatcmpl-xxx",
     "object": "chat.completion",
     "choices": [{
       "message": {"role": "assistant", "content": "..."}
     }]
   }
   ↓
7. Returns JSON response to client

═══════════════════════════════════════════════════════════════════

Technology Stack:

┌─────────────────────────────────────────────────────────────────┐
│ Language:     Kotlin                                             │
│ Min SDK:      24 (Android 7.0)                                   │
│ Target SDK:   34 (Android 14)                                    │
│ Build:        Gradle 8.2, AGP 8.1.4                              │
│ UI:           Material Design 3, View Binding                    │
│ Server:       NanoHTTPD 2.3.1                                    │
│ JSON:         Gson 2.10.1                                        │
│ Async:        Kotlin Coroutines 1.7.3                            │
└─────────────────────────────────────────────────────────────────┘

═══════════════════════════════════════════════════════════════════

Security Considerations:

✓ Runs on local network (no public exposure by default)
✓ Proper Android permissions declared
✓ No hardcoded credentials
✓ Error handling without sensitive info leakage
✓ Input validation on API endpoints

⚠ No authentication (suitable for local use only)
⚠ HTTP only (no HTTPS) - consider adding for production
⚠ No rate limiting - consider adding for production

═══════════════════════════════════════════════════════════════════

Future Integration Points:

1. llama.cpp Native Library
   ┌────────────────────────────────────────┐
   │ app/src/main/cpp/                      │
   │  ├── llama.cpp/                        │
   │  ├── jni_wrapper.cpp                   │
   │  └── CMakeLists.txt                    │
   └────────────────────────────────────────┘
   
2. JNI Bindings in LlamaModel.kt
   external fun loadModelNative(path: String): Long
   external fun generateNative(context: Long, prompt: String): String
   
3. Model Storage
   - Internal storage for model files
   - Download manager for GGUF models
   - Model selection UI

═══════════════════════════════════════════════════════════════════
