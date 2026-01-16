# Contributing to HostAI

Thank you for your interest in contributing to HostAI! This document provides guidelines and instructions for developers.

## Development Setup

### Prerequisites

1. **Android Studio** (Arctic Fox 2020.3.1 or later)
   - Download from: https://developer.android.com/studio

2. **Android SDK**
   - API Level 24 (Android 7.0) minimum
   - API Level 34 (Android 14) target

3. **JDK 8 or higher**
   - OpenJDK recommended

4. **Git**

### Setting Up the Development Environment

1. Clone the repository:
   ```bash
   git clone https://github.com/wannaphong/android-hostai.git
   cd android-hostai
   ```

2. Open the project in Android Studio:
   - File → Open → Select the `android-hostai` directory

3. Let Android Studio sync the Gradle files

4. Connect an Android device or start an emulator

5. Build and run:
   - Click the "Run" button or press Shift+F10

### Building from Command Line

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing configuration)
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

## Project Structure

```
android-hostai/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/wannaphong/hostai/
│   │       │   ├── MainActivity.kt           # Main UI activity
│   │       │   ├── ApiServerService.kt       # Background service
│   │       │   ├── OpenAIApiServer.kt        # HTTP server implementation
│   │       │   └── LlamaModel.kt             # Model interface
│   │       ├── res/                          # Resources (layouts, strings, etc.)
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

## Key Components

### MainActivity
The main user interface that allows users to:
- Start/stop the API server
- View server status and URL
- Copy the server URL to clipboard
- Test the server connectivity

### ApiServerService
A foreground service that:
- Manages the HTTP server lifecycle
- Shows a persistent notification when running
- Handles server start/stop operations

### OpenAIApiServer
HTTP server implementation using NanoHTTPD that provides:
- `/v1/models` - List available models
- `/v1/chat/completions` - Chat completion endpoint
- `/v1/completions` - Text completion endpoint
- `/health` - Health check endpoint

### LlamaModel
Mock implementation of the model interface. This is where llama.cpp integration would be added.

## Adding Real llama.cpp Integration

The current implementation uses a mock model. To integrate real llama.cpp:

1. **Add llama.cpp as a native library:**
   - Add llama.cpp source code to `app/src/main/cpp/`
   - Create JNI wrapper functions
   - Configure CMake in `app/build.gradle.kts`

2. **Update LlamaModel.kt:**
   - Add native method declarations
   - Load the native library
   - Replace mock implementations with JNI calls

3. **Add model loading:**
   - Implement model file selection/download
   - Add model storage management
   - Handle GGUF format models

Example CMake configuration:
```kotlin
android {
    ...
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "25.1.8937393"
}
```

## Testing

### Manual Testing
1. Install the app on a device
2. Start the server
3. Use curl or a REST client to test endpoints:
   ```bash
   curl http://<device-ip>:8080/v1/models
   curl http://<device-ip>:8080/v1/chat/completions \
     -H "Content-Type: application/json" \
     -d '{"model":"llama-mock-model","messages":[{"role":"user","content":"Hello"}]}'
   ```

### Unit Tests
Run unit tests with:
```bash
./gradlew test
```

### Integration Tests
Run instrumented tests on a device:
```bash
./gradlew connectedAndroidTest
```

## Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Add comments for complex logic
- Keep functions small and focused

## Submitting Changes

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Commit your changes (`git commit -m 'Add amazing feature'`)
5. Push to the branch (`git push origin feature/amazing-feature`)
6. Open a Pull Request

## Pull Request Guidelines

- Describe your changes clearly
- Include screenshots for UI changes
- Update documentation if needed
- Ensure the code builds successfully
- Test on at least one physical device

## Future Enhancements

Potential areas for contribution:

1. **Real llama.cpp Integration**
   - Native library compilation
   - JNI bindings
   - Model loading and inference

2. **UI Improvements**
   - Model selection UI
   - Request/response logging
   - Performance metrics

3. **Features**
   - Model download manager
   - Custom port configuration
   - Authentication/API keys
   - Request rate limiting
   - Streaming responses (SSE)

4. **Performance**
   - Memory optimization
   - Background processing
   - Battery optimization

5. **Security**
   - HTTPS support
   - Access controls
   - Input validation

## Questions?

If you have questions, please:
- Open an issue on GitHub
- Check existing issues and discussions
- Review the README.md for basic usage

Thank you for contributing to HostAI!
