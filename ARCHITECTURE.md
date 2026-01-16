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
│  │            LlamaModel (95+ lines)                         │  │
│  │  - JNI bindings to native code                            │  │
│  │  - System.loadLibrary("hostai")                           │  │
│  │  - generate() method → nativeGenerate()                   │  │
│  │  - generateStream() method                                │  │
│  │  - Native context management                              │  │
│  └───────────────────────────────────────────────────────────┘  │
│                               │                                   │
│                               │ JNI                               │
│                               ▼                                   │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │      Native Layer (C++ via JNI)                           │  │
│  │      app/src/main/cpp/llama_jni.cpp                       │  │
│  │  - JNI wrapper functions                                  │  │
│  │  - LlamaContext management                                │  │
│  │  - Interface ready for llama.cpp integration              │  │
│  │                                                           │  │
│  │  [Ready for: llama.cpp model loading & inference]        │  │
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
│ Language:     Kotlin + C++ (JNI)                                 │
│ Min SDK:      24 (Android 7.0)                                   │
│ Target SDK:   34 (Android 14)                                    │
│ Build:        Gradle 8.2, AGP 8.1.4                              │
│ Native:       NDK, CMake 3.22.1                                  │
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

1. llama.cpp Native Library - NOW IMPLEMENTED ✓
   ┌────────────────────────────────────────┐
   │ app/src/main/cpp/                      │
   │  ├── llama_jni.cpp        ✓            │
   │  ├── CMakeLists.txt       ✓            │
   │  ├── README.md            ✓            │
   │  └── [llama.cpp sources]  TODO        │
   └────────────────────────────────────────┘
   
2. JNI Bindings in LlamaModel.kt - NOW IMPLEMENTED ✓
   System.loadLibrary("hostai")
   external fun nativeInit(): Long
   external fun nativeLoadModel(contextPtr: Long, modelPath: String): Boolean
   external fun nativeGenerate(...): String
   
3. Model Storage - TODO
   - Internal storage for model files
   - Download manager for GGUF models
   - Model selection UI

4. Complete llama.cpp Integration - TODO
   - Add llama.cpp source files or library
   - Update CMakeLists.txt to build llama.cpp
   - Implement actual llama.cpp API calls in llama_jni.cpp

═══════════════════════════════════════════════════════════════════
