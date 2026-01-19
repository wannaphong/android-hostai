# Concurrent Request Handling

## Overview

HostAI now supports handling multiple concurrent requests efficiently and safely. The implementation ensures that:

1. **Multiple requests to different sessions run in parallel** - No blocking between different users/contexts
2. **Multiple requests to the same session are serialized** - Prevents race conditions and ensures conversation consistency
3. **Thread-safe access to LiteRT Conversation objects** - Uses per-session locks to coordinate access

## Implementation Details

### Per-Session Locking

The implementation uses `ReentrantLock` for each session, stored in a `ConcurrentHashMap`:

```kotlin
private val sessionLocks = ConcurrentHashMap<String, ReentrantLock>()
```

When a request comes in for a specific session, the code:
1. Gets or creates a lock for that session
2. Acquires the lock using `withLock { }`
3. Performs the generation operation
4. Releases the lock automatically

### Methods Protected

All generation methods are protected with per-session locks:
- `generate()` - Synchronous text generation
- `generateWithContents()` - Synchronous multimodal generation
- `generateStream()` - Asynchronous streaming text generation
- `generateStreamWithContents()` - Asynchronous streaming multimodal generation

### Example Scenarios

#### Scenario 1: Different Sessions (Parallel Execution)
```
Request 1 (session: alice) → Lock alice → Generate → Release
Request 2 (session: bob)   → Lock bob   → Generate → Release
```
Both requests run **concurrently** because they use different locks.

#### Scenario 2: Same Session (Sequential Execution)
```
Request 1 (session: alice) → Lock alice → Generate (5s) → Release
Request 2 (session: alice) → Wait for lock → Lock alice → Generate (5s) → Release
```
Request 2 **waits** for Request 1 to complete because they share the same lock.

## Benefits

### 1. Correctness
- Prevents race conditions when multiple clients use the same session
- Maintains conversation context integrity
- Ensures deterministic behavior

### 2. Performance
- Requests to different sessions execute in parallel
- No global lock that would serialize all requests
- Efficient use of multiple CPU cores

### 3. Resource Management
- Locks are automatically cleaned up when sessions are cleared
- No memory leaks from orphaned locks
- Uses lazy lock creation (only when needed)

## Usage Recommendations

### For API Clients

1. **Use different session IDs for different users/contexts**
   ```bash
   # User Alice
   curl -H "X-Session-ID: alice" ...
   
   # User Bob (runs in parallel)
   curl -H "X-Session-ID: bob" ...
   ```

2. **Reuse session IDs for conversation continuity**
   ```bash
   # First message from Alice
   curl -H "X-Session-ID: alice" ... "Hello"
   
   # Follow-up from Alice (maintains context)
   curl -H "X-Session-ID: alice" ... "What's my name?"
   ```

3. **Be aware of sequential processing within sessions**
   - Multiple concurrent requests to the same session will queue
   - Consider using unique session IDs if requests are independent

### For Server Configuration

- The Javalin server already handles concurrent HTTP requests efficiently
- No configuration changes needed for the web server
- The LlamaModel handles per-session synchronization automatically

## Testing Concurrent Requests

### Using curl (Bash)

```bash
# Start two requests to different sessions in parallel
curl -H "X-Session-ID: session1" http://localhost:8080/v1/chat/completions \
  -d '{"model":"llama-model","messages":[{"role":"user","content":"Hello"}]}' &

curl -H "X-Session-ID: session2" http://localhost:8080/v1/chat/completions \
  -d '{"model":"llama-model","messages":[{"role":"user","content":"Hi"}]}' &

wait
```

### Using Python

```python
import concurrent.futures
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="not-needed")

def make_request(session_id, message):
    response = client.chat.completions.create(
        model="llama-model",
        extra_headers={"X-Session-ID": session_id},
        messages=[{"role": "user", "content": message}]
    )
    return f"{session_id}: {response.choices[0].message.content}"

# Test concurrent requests to different sessions
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
    futures = [
        executor.submit(make_request, f"session{i}", f"Hello {i}")
        for i in range(5)
    ]
    
    for future in concurrent.futures.as_completed(futures):
        print(future.result())
```

## Performance Characteristics

- **Throughput**: Multiple sessions can generate responses simultaneously
- **Latency**: Single-session latency unchanged; multi-session requests don't block each other
- **Resource Usage**: Lock overhead is minimal (microseconds for lock acquisition)
- **Scalability**: Scales with the number of concurrent sessions up to hardware limits

## Troubleshooting

### Issue: Requests seem to queue even with different session IDs

**Solution**: Verify that session IDs are actually different. The session ID can come from:
- `conversation_id` field (highest priority)
- `user` field
- `session_id` field
- `X-Session-ID` header
- "default" (lowest priority)

### Issue: Deadlock concerns

**Solution**: Deadlocks are not possible with this implementation because:
- Each request acquires at most one lock
- No nested lock acquisition
- All locks are automatically released via `withLock`

## Future Enhancements

Potential improvements for future versions:

1. **Lock timeout**: Add timeout to prevent indefinite waiting
2. **Request queue limits**: Limit concurrent requests per session
3. **Metrics**: Track lock contention and waiting times
4. **Priority scheduling**: Allow high-priority requests to jump the queue
