# Function Calling Examples

This document demonstrates how to use function calling with the HostAI API.

## Example 1: Weather Query

**Request:**
```json
POST /v1/chat/completions
Content-Type: application/json

{
  "model": "functiongemma-270m-it",
  "messages": [
    {"role": "user", "content": "What's the weather like in San Francisco?"}
  ],
  "tools": [
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
              "description": "The city name, e.g., San Francisco"
            },
            "country": {
              "type": "string",
              "description": "Optional country code, e.g., US"
            },
            "unit": {
              "type": "string",
              "description": "Temperature unit (celsius or fahrenheit). Default: celsius",
              "enum": ["celsius", "fahrenheit"]
            }
          },
          "required": ["city"]
        }
      }
    }
  ]
}
```

**Expected Response:**
```json
{
  "id": "chatcmpl-1234567890",
  "object": "chat.completion",
  "created": 1705384800,
  "model": "functiongemma-270m-it",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "The weather in San Francisco is sunny with a temperature of 22°C."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 45,
    "completion_tokens": 18,
    "total_tokens": 63
  }
}
```

## Example 2: Mathematical Calculation

**Request:**
```json
POST /v1/chat/completions
Content-Type: application/json

{
  "model": "functiongemma-270m-it",
  "messages": [
    {"role": "user", "content": "What is the sum of 15, 27, 33, and 8?"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "sum",
        "description": "Calculate the sum of a list of numbers",
        "parameters": {
          "type": "object",
          "properties": {
            "numbers": {
              "type": "array",
              "items": {"type": "number"},
              "description": "The numbers to sum, can be floating point"
            }
          },
          "required": ["numbers"]
        }
      }
    }
  ]
}
```

**Expected Response:**
```json
{
  "id": "chatcmpl-1234567891",
  "object": "chat.completion",
  "created": 1705384801,
  "model": "functiongemma-270m-it",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "The sum of 15, 27, 33, and 8 is 83."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 38,
    "completion_tokens": 15,
    "total_tokens": 53
  }
}
```

## Example 3: Multiple Tools

**Request:**
```json
POST /v1/chat/completions
Content-Type: application/json

{
  "model": "functiongemma-270m-it",
  "messages": [
    {"role": "user", "content": "What time is it in Tokyo?"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_current_time",
        "description": "Get the current time in a specific timezone",
        "parameters": {
          "type": "object",
          "properties": {
            "timezone": {
              "type": "string",
              "description": "The timezone, e.g., America/New_York, Europe/London, Asia/Tokyo, or UTC"
            }
          }
        }
      }
    },
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
            }
          },
          "required": ["city"]
        }
      }
    }
  ]
}
```

**Expected Response:**
```json
{
  "id": "chatcmpl-1234567892",
  "object": "chat.completion",
  "created": 1705384802,
  "model": "functiongemma-270m-it",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "The current time in Tokyo (Asia/Tokyo timezone) is 14:30:00 on 2024-01-15."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 52,
    "completion_tokens": 22,
    "total_tokens": 74
  }
}
```

## Example 4: With Session ID

Tools work seamlessly with multi-session support:

**Request:**
```json
POST /v1/chat/completions
Content-Type: application/json

{
  "model": "functiongemma-270m-it",
  "conversation_id": "user123",
  "messages": [
    {"role": "user", "content": "What's the weather in Paris?"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Get the current weather for a city",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {"type": "string"}
          },
          "required": ["city"]
        }
      }
    }
  ]
}
```

The tools will remain available for all subsequent requests using `conversation_id: "user123"`.

## Notes

1. The model must support function calling (e.g., FunctionGemma)
2. Tools are defined once per session and remain available
3. The model decides when to call tools based on the query
4. Tool execution is automatic on the server side
5. Results are incorporated into the response naturally
