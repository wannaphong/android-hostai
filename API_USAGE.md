# API Usage Examples

This document provides examples of how to use the HostAI OpenAI-compatible API server.

## Base URL

Replace `<phone-ip>` with your Android device's IP address (shown in the app):

```
http://<phone-ip>:8080
```

## Multi-Session Support

HostAI now supports multiple concurrent conversation sessions, allowing you to maintain separate conversation contexts. This is useful for:
- Supporting multiple users or clients
- Maintaining different conversation threads
- Isolating different tasks or contexts

### How to Use Sessions

You can specify a session ID in multiple ways (in order of priority):
1. `conversation_id` field in request body (OpenAI Conversations API standard)
2. `user` field in request body (OpenAI standard)
3. `session_id` field in request body
4. `X-Session-ID` HTTP header

If no session ID is provided, requests will use the "default" session.

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


#### Chat Completions with Multi-Session

Maintain separate conversation contexts using session IDs:

**Method 1: Using conversation_id field (OpenAI Conversations API standard)**
```bash
# User 1's conversation
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "conversation_id": "user1",
    "messages": [
      {"role": "user", "content": "My name is Alice"}
    ]
  }'

# User 2's conversation (separate context)
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "conversation_id": "user2",
    "messages": [
      {"role": "user", "content": "My name is Bob"}
    ]
  }'
```

**Method 2: Using user field (OpenAI standard)**
```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "user": "alice@example.com",
    "messages": [
      {"role": "user", "content": "What did I tell you my name was?"}
    ]
  }'
```

**Method 3: Using X-Session-ID header**
```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Session-ID: project-alpha" \
  -d '{
    "model": "llama-mock-model",
    "messages": [
      {"role": "user", "content": "Hello"}
    ]
  }'
```


### 3. Stored Chat Completions

HostAI supports storing chat completions for later retrieval by setting the `store` parameter to `true`. This allows you to persist completions and their metadata.

#### Store a Chat Completion

Set `store=true` when creating a chat completion to store it:

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "messages": [
      {"role": "user", "content": "What is the capital of France?"}
    ],
    "store": true,
    "metadata": {
      "user_id": "12345",
      "session": "chat-session-1"
    }
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
        "content": "The capital of France is Paris."
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

**Note:** Save the `id` field (e.g., `chatcmpl-1705384800123`) to retrieve or update the completion later.

#### Get a Stored Completion

Retrieve a stored chat completion by its ID:

```bash
curl http://<phone-ip>:8080/v1/chat/completions/chatcmpl-1705384800123
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
        "content": "The capital of France is Paris."
      },
      "finish_reason": "stop"
    }
  ],
  "metadata": {
    "user_id": "12345",
    "session": "chat-session-1"
  }
}
```

#### Get Messages from a Stored Completion

Get all messages (including the assistant's response) from a stored completion:

```bash
curl http://<phone-ip>:8080/v1/chat/completions/chatcmpl-1705384800123/messages
```

**Response:**
```json
{
  "object": "list",
  "data": [
    {
      "id": "chatcmpl-1705384800123-msg-0",
      "object": "chat.completion.message",
      "created": 1705384800,
      "role": "user",
      "content": "What is the capital of France?"
    },
    {
      "id": "chatcmpl-1705384800123-msg-1",
      "object": "chat.completion.message",
      "created": 1705384800,
      "role": "assistant",
      "content": "The capital of France is Paris."
    }
  ]
}
```

#### Update Completion Metadata

Update the metadata for a stored completion:

```bash
curl -X POST http://<phone-ip>:8080/v1/chat/completions/chatcmpl-1705384800123 \
  -H "Content-Type: application/json" \
  -d '{
    "metadata": {
      "user_id": "12345",
      "session": "chat-session-1",
      "rating": "5",
      "reviewed": true
    }
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
        "content": "The capital of France is Paris."
      },
      "finish_reason": "stop"
    }
  ],
  "metadata": {
    "user_id": "12345",
    "session": "chat-session-1",
    "rating": "5",
    "reviewed": true
  }
}
```


### 4. Text Completions

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


### 5. Session Management

Manage conversation sessions.

#### List Active Sessions

Get a list of all active conversation sessions:

```bash
curl http://<phone-ip>:8080/v1/sessions
```

**Response:**
```json
{
  "object": "list",
  "data": [
    {
      "id": "default",
      "object": "session"
    },
    {
      "id": "user1",
      "object": "session"
    },
    {
      "id": "alice@example.com",
      "object": "session"
    }
  ],
  "count": 3
}
```

#### Delete a Specific Session

Clear a specific conversation session and its context:

```bash
curl -X DELETE http://<phone-ip>:8080/v1/sessions/user1
```

**Response:**
```json
{
  "deleted": true,
  "id": "user1",
  "object": "session"
}
```

#### Clear All Sessions

Clear all conversation sessions:

```bash
curl -X DELETE http://<phone-ip>:8080/v1/sessions
```

**Response:**
```json
{
  "deleted": true,
  "count": 3,
  "object": "sessions"
}
```


### 6. Health Check

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

# Multi-session support: Use 'user' parameter to maintain separate conversations
response1 = client.chat.completions.create(
    model="llama-mock-model",
    user="alice",  # Conversation for Alice
    messages=[
        {"role": "user", "content": "My favorite color is blue"}
    ]
)

response2 = client.chat.completions.create(
    model="llama-mock-model",
    user="bob",  # Separate conversation for Bob
    messages=[
        {"role": "user", "content": "My favorite color is red"}
    ]
)

# Continue Alice's conversation
response3 = client.chat.completions.create(
    model="llama-mock-model",
    user="alice",  # Same user, continues conversation context
    messages=[
        {"role": "user", "content": "What's my favorite color?"}
    ]
)
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

These parameters are extracted from the API request and prepared for the generation engine:

- `model` (string): Model identifier (e.g., "llama-mock-model")
- `temperature` (float, 0-2): Controls randomness. Lower = more deterministic. Default: 0.7
- `max_tokens` (integer): Maximum tokens to generate. Default: 100
- `top_p` (float, 0-1): Nucleus sampling parameter. Default: 0.95
- `top_k` (integer): Top-K sampling parameter. Default: 40
- `stream` (boolean): Whether to stream the response using Server-Sent Events (SSE). Default: false

**Note:** The HostAI API accepts all parameters listed below and prepares them for the underlying inference engine. The actual parameter support depends on the kotlinllamacpp library implementation. Currently, prompt and streaming are fully supported, with additional parameters prepared for future compatibility.

### Advanced Sampling Parameters

These parameters provide fine-grained control over text generation quality:

- `min_p` (float, 0-1): Minimum probability for a token to be considered. Default: 0.05
- `tfs_z` (float): Tail-free sampling parameter. Default: 1.00
- `typical_p` (float, 0-1): Locally typical sampling parameter. Default: 1.00
- `seed` (integer): Random seed for reproducible generation. Default: -1 (random)

### Penalty Parameters

Control repetition and token selection:

- `penalty_repeat` or `repetition_penalty` (float): Penalize repeated tokens. Default: 1.00
- `penalty_freq` or `frequency_penalty` (float): Penalize tokens based on frequency. Default: 0.00
- `penalty_present` or `presence_penalty` (float): Penalize tokens based on presence. Default: 0.00
- `penalty_last_n` (integer): Number of tokens to consider for repetition penalty. Default: 64
- `penalize_nl` (boolean): Whether to penalize newline tokens. Default: false

### Mirostat Sampling

Advanced coherence control:

- `mirostat` (float): Mirostat sampling mode (0=disabled, 1=Mirostat, 2=Mirostat 2.0). Default: 0.00
- `mirostat_tau` (float): Target entropy for Mirostat. Default: 5.00
- `mirostat_eta` (float): Learning rate for Mirostat. Default: 0.10

### Stop Conditions

- `stop` (string or array of strings): Stop sequences. Generation stops when any of these strings are encountered
- `ignore_eos` (boolean): Whether to ignore end-of-sequence token. Default: false

### Grammar and Constraints

- `grammar` (string): BNF grammar to constrain output format
- `logit_bias` (object): Bias specific tokens. Keys are token IDs, values are bias values (-100 to 100)
- `n_probs` (integer): Number of most likely tokens to return with probabilities. Default: 0

### Example with Advanced Parameters

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama-mock-model",
    "messages": [
      {"role": "user", "content": "Write a creative story"}
    ],
    "temperature": 0.8,
    "max_tokens": 200,
    "top_p": 0.9,
    "top_k": 50,
    "repetition_penalty": 1.1,
    "frequency_penalty": 0.1,
    "presence_penalty": 0.1,
    "stop": ["\n\n", "THE END"],
    "seed": 42
  }'
```

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
