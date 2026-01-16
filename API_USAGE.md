# API Usage Examples

This document provides examples of how to use the HostAI OpenAI-compatible API server.

## Base URL

Replace `<phone-ip>` with your Android device's IP address (shown in the app):

```
http://<phone-ip>:8080
```

## Available Endpoints

### 1. List Models

Get a list of available models.

```bash
curl http://<phone-ip>:8080/v1/models
```

**Response:**
```json
{
  "object": "list",
  "data": [
    {
      "id": "llama-mock-model",
      "object": "model",
      "created": 1705384800,
      "owned_by": "hostai"
    }
  ]
}
```

### 2. Chat Completions

Chat with the model using the ChatGPT-style API.

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "What is the capital of France?"}
    ],
    "temperature": 0.7,
    "max_tokens": 100
  }'
```

**Response:**
```json
{
  "id": "chatcmpl-1705384800123",
  "object": "chat.completion",
  "created": 1705384800,
  "model": "llama-mock-model",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "This is a mock response from the LLaMA model..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 20,
    "completion_tokens": 15,
    "total_tokens": 35
  }
}
```

#### Chat Completions with Streaming

Enable streaming to receive tokens as they are generated:

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "messages": [
      {"role": "user", "content": "Tell me a story"}
    ],
    "stream": true,
    "temperature": 0.7,
    "max_tokens": 100
  }'
```

**Response (Server-Sent Events):**
```
data: {"id":"chatcmpl-1705384800123","object":"chat.completion.chunk","created":1705384800,"model":"llama-mock-model","choices":[{"index":0,"delta":{"content":"Once"},"finish_reason":null}]}

data: {"id":"chatcmpl-1705384800123","object":"chat.completion.chunk","created":1705384800,"model":"llama-mock-model","choices":[{"index":0,"delta":{"content":" upon"},"finish_reason":null}]}

data: {"id":"chatcmpl-1705384800123","object":"chat.completion.chunk","created":1705384800,"model":"llama-mock-model","choices":[{"index":0,"delta":{"content":" a"},"finish_reason":null}]}

data: {"id":"chatcmpl-1705384800123","object":"chat.completion.chunk","created":1705384800,"model":"llama-mock-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```


### 3. Text Completions

Generate text completions.

```bash
curl http://<phone-ip>:8080/v1/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "prompt": "Once upon a time",
    "temperature": 0.7,
    "max_tokens": 100
  }'
```

**Response:**
```json
{
  "id": "cmpl-1705384800123",
  "object": "text_completion",
  "created": 1705384800,
  "model": "llama-mock-model",
  "choices": [
    {
      "text": "This is a mock response from the LLaMA model...",
      "index": 0,
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 4,
    "completion_tokens": 15,
    "total_tokens": 19
  }
}
```

#### Text Completions with Streaming

Enable streaming to receive tokens as they are generated:

```bash
curl http://<phone-ip>:8080/v1/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "prompt": "Once upon a time",
    "stream": true,
    "temperature": 0.7,
    "max_tokens": 100
  }'
```

**Response (Server-Sent Events):**
```
data: {"id":"cmpl-1705384800123","object":"text_completion","created":1705384800,"model":"llama-mock-model","choices":[{"text":"there","index":0,"finish_reason":null}]}

data: {"id":"cmpl-1705384800123","object":"text_completion","created":1705384800,"model":"llama-mock-model","choices":[{"text":" was","index":0,"finish_reason":null}]}

data: {"id":"cmpl-1705384800123","object":"text_completion","created":1705384800,"model":"llama-mock-model","choices":[{"text":" a","index":0,"finish_reason":null}]}

data: {"id":"cmpl-1705384800123","object":"text_completion","created":1705384800,"model":"llama-mock-model","choices":[{"text":"","index":0,"finish_reason":"stop"}]}

data: [DONE]
```


### 4. Health Check

Check if the server is running.

```bash
curl http://<phone-ip>:8080/health
```

**Response:**
```json
{
  "status": "ok",
  "model_loaded": true
}
```

## Using with Programming Languages

### Python (OpenAI Library)

```python
from openai import OpenAI

# Initialize client with your phone's IP
client = OpenAI(
    base_url="http://<phone-ip>:8080/v1",
    api_key="not-needed"  # API key not required for local server
)

# Chat completion
response = client.chat.completions.create(
    model="llama-mock-model",
    messages=[
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Hello!"}
    ],
    temperature=0.7,
    max_tokens=100
)

print(response.choices[0].message.content)

# Chat completion with streaming
stream = client.chat.completions.create(
    model="llama-mock-model",
    messages=[
        {"role": "user", "content": "Tell me a story"}
    ],
    stream=True,
    temperature=0.7,
    max_tokens=100
)

for chunk in stream:
    if chunk.choices[0].delta.content is not None:
        print(chunk.choices[0].delta.content, end='')
```

### JavaScript/Node.js

```javascript
const fetch = require('node-fetch');

const phoneIp = '<phone-ip>';
const baseUrl = `http://${phoneIp}:8080`;

async function chatCompletion(message) {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: 'llama-mock-model',
      messages: [
        { role: 'user', content: message }
      ],
      temperature: 0.7,
      max_tokens: 100
    })
  });
  
  const data = await response.json();
  return data.choices[0].message.content;
}

// Streaming chat completion
async function chatCompletionStream(message) {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: 'llama-mock-model',
      messages: [
        { role: 'user', content: message }
      ],
      stream: true,
      temperature: 0.7,
      max_tokens: 100
    })
  });

  const reader = response.body;
  reader.on('data', (chunk) => {
    const lines = chunk.toString().split('\n');
    for (const line of lines) {
      if (line.startsWith('data: ') && !line.includes('[DONE]')) {
        const data = JSON.parse(line.substring(6));
        if (data.choices[0].delta.content) {
          process.stdout.write(data.choices[0].delta.content);
        }
      }
    }
  });
}

// Usage
chatCompletion('Hello!')
  .then(response => console.log(response))
  .catch(error => console.error(error));

// Streaming usage
chatCompletionStream('Tell me a story');
```

### Python (requests library)

```python
import requests
import json

phone_ip = '<phone-ip>'
base_url = f'http://{phone_ip}:8080'

def chat_completion(message):
    response = requests.post(
        f'{base_url}/v1/chat/completions',
        headers={'Content-Type': 'application/json'},
        json={
            'model': 'llama-mock-model',
            'messages': [
                {'role': 'user', 'content': message}
            ],
            'temperature': 0.7,
            'max_tokens': 100
        }
    )
    
    data = response.json()
    return data['choices'][0]['message']['content']

# Streaming chat completion
def chat_completion_stream(message):
    response = requests.post(
        f'{base_url}/v1/chat/completions',
        headers={'Content-Type': 'application/json'},
        json={
            'model': 'llama-mock-model',
            'messages': [
                {'role': 'user', 'content': message}
            ],
            'stream': True,
            'temperature': 0.7,
            'max_tokens': 100
        },
        stream=True
    )
    
    for line in response.iter_lines():
        if line:
            line = line.decode('utf-8')
            if line.startswith('data: ') and '[DONE]' not in line:
                data = json.loads(line[6:])
                if 'choices' in data and data['choices']:
                    delta = data['choices'][0].get('delta', {})
                    if 'content' in delta:
                        print(delta['content'], end='', flush=True)

# Usage
print(chat_completion('Hello!'))

# Streaming usage
print("\nStreaming response:")
chat_completion_stream('Tell me a story')
```

### Go

```go
package main

import (
    "bytes"
    "encoding/json"
    "fmt"
    "io/ioutil"
    "net/http"
)

type ChatRequest struct {
    Model       string    `json:"model"`
    Messages    []Message `json:"messages"`
    Temperature float64   `json:"temperature"`
    MaxTokens   int       `json:"max_tokens"`
}

type Message struct {
    Role    string `json:"role"`
    Content string `json:"content"`
}

type ChatResponse struct {
    Choices []struct {
        Message Message `json:"message"`
    } `json:"choices"`
}

func chatCompletion(phoneIP, message string) (string, error) {
    url := fmt.Sprintf("http://%s:8080/v1/chat/completions", phoneIP)
    
    request := ChatRequest{
        Model: "llama-mock-model",
        Messages: []Message{
            {Role: "user", Content: message},
        },
        Temperature: 0.7,
        MaxTokens:   100,
    }
    
    jsonData, _ := json.Marshal(request)
    
    resp, err := http.Post(url, "application/json", bytes.NewBuffer(jsonData))
    if err != nil {
        return "", err
    }
    defer resp.Body.Close()
    
    body, _ := ioutil.ReadAll(resp.Body)
    
    var response ChatResponse
    json.Unmarshal(body, &response)
    
    return response.Choices[0].Message.Content, nil
}

func main() {
    result, err := chatCompletion("<phone-ip>", "Hello!")
    if err != nil {
        fmt.Println("Error:", err)
        return
    }
    fmt.Println(result)
}
```

## Parameters

### Common Parameters

- `model` (string): Model identifier (e.g., "llama-mock-model")
- `temperature` (float, 0-2): Controls randomness. Lower = more deterministic
- `max_tokens` (integer): Maximum tokens to generate
- `top_p` (float, 0-1): Nucleus sampling parameter
- `stream` (boolean): Whether to stream the response using Server-Sent Events (SSE). Default: false

### Chat Completions Specific

- `messages` (array): Array of message objects with `role` and `content`
  - Roles: "system", "user", "assistant"

### Text Completions Specific

- `prompt` (string): The prompt to complete

## Error Handling

If an error occurs, the API returns a JSON response with error details:

```json
{
  "error": {
    "message": "Error message here"
  }
}
```

## Tips

1. **Finding Your Phone's IP Address:**
   - The IP is displayed in the HostAI app when the server is running
   - Or check your WiFi settings

2. **Same Network Requirement:**
   - Your phone and client device must be on the same WiFi network

3. **Firewall:**
   - Ensure no firewall is blocking port 8080

4. **Testing:**
   - Use the built-in "Test Server" button in the app to verify connectivity

## Browser Access

You can also open `http://<phone-ip>:8080` in a web browser to see a simple web interface with endpoint documentation.
