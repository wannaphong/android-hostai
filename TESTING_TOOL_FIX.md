# Testing the Tool Error Fix

This document explains how to test the fix for the tool error issue.

## Issue Description
When tools were provided in a chat completion request, the server returned a 500 Internal Server Error.

## Root Cause
The `ConversationConfig` constructor was being called with `tools` as a named parameter:
```kotlin
ConversationConfig(samplerConfig = samplerConfig, tools = config.tools)
```

However, the LiteRT-LM library expects `tools` as a positional parameter:
```kotlin
ConversationConfig(samplerConfig, config.tools)
```

## Fix Applied
Changed line 158 in `LlamaModel.kt` from named parameter to positional parameter syntax.

## Testing Instructions

### 1. Build and Install the App
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Start the HostAI App
- Launch the app on your Android device
- Load a function-calling compatible model (e.g., FunctionGemma)
- Start the API server (note the IP address and port)

### 3. Test with Python Client
Use the provided Python test script from the issue:

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://YOUR_DEVICE_IP:8080/v1",
    api_key="not-needed"
)

# Define tools
tools = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get the current weather for a city",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {
                        "type": "string",
                        "description": "The city name"
                    },
                    "unit": {
                        "type": "string",
                        "enum": ["celsius", "fahrenheit"]
                    }
                },
                "required": ["city"]
            }
        }
    }
]

# Send request with tools
response = client.chat.completions.create(
    model="llama-model",
    messages=[
        {"role": "user", "content": "What's the weather in Paris?"}
    ],
    tools=tools
)

# Check if model called a tool
print(f"Response: {response}")
if response.choices[0].message.content:
    print(f"Content: {response.choices[0].message.content}")
```

### 4. Expected Behavior

**Before the fix:**
- Client receives: `openai.InternalServerError: Error code: 500`
- Server logs show: Request received but no clear error message

**After the fix:**
- Client receives a successful response (status 200)
- Server logs show: "Created new conversation for session: default with 1 tools"
- The model processes the request with the provided tools
- Response contains the assistant's message (potentially with tool calls if the model supports it)

### 5. Verify in Logs
Check the Android logcat for these messages:
```
[DEBUG] [OpenAIApiServer] Tools provided in request (1 tools)
[INFO] [LlamaModel] Created new conversation for session: default with 1 tools
[INFO] [OpenAIApiServer] Chat completion completed successfully for session: default
```

## Additional Test Cases

### Test with Multiple Tools
```python
tools = [
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
    },
    {
        "type": "function",
        "function": {
            "name": "get_current_time",
            "description": "Get the current time in a specific timezone",
            "parameters": {
                "type": "object",
                "properties": {
                    "timezone": {"type": "string", "description": "The timezone"}
                }
            }
        }
    }
]
```

### Test without Tools (Regression Test)
Ensure that requests without tools still work:
```python
response = client.chat.completions.create(
    model="llama-model",
    messages=[
        {"role": "user", "content": "Hello, how are you?"}
    ]
)
print(f"Response: {response.choices[0].message.content}")
```

## Notes
- The ExampleToolSet class provides built-in implementations for `get_weather`, `sum`, and `get_current_time` functions
- The model must support function calling for tools to be effective
- Tools are session-specific and persist across requests in the same session
