# Concurrent Request Handling

## Overview

HostAI supports truly parallel inference requests by maintaining a **pool of
LiteRT Engine instances** – one per configured concurrent slot.  LiteRT's
native engine constraint ("only one active conversation per Engine instance") is
respected by giving each concurrent request its own dedicated Engine, so
multiple requests run simultaneously with no serialisation:

- Incoming requests are queued in FIFO order by the `requestSemaphore` in
  `OpenAIApiServer` (controlled by the *Max Concurrency* setting).
- Each request borrows one Engine from the pool in `LlamaModel`, creates a
  conversation on that Engine, runs inference, closes the conversation, and
  returns the Engine to the pool.
- With *Max Concurrency = N*, exactly N Engine instances are loaded at model
  load time, and N requests can execute fully in parallel.
- `close()` cancels in-flight streaming coroutines (whose `finally` blocks
  return engines to the pool) and then drains all N engines from the pool,
  guaranteeing that every Engine is idle before its native resources are freed.

## Implementation Details

### Engine Pool

```kotlin
// LlamaModel.kt
private val enginePool = LinkedBlockingQueue<Engine>()
@Volatile private var poolCapacity = 0
```

The pool is filled in `loadFromPath()` with `maxConcurrency` Engine instances:

```kotlin
val concurrency = settingsManager.getMaxConcurrency().coerceAtLeast(1)
repeat(concurrency) { index ->
    val eng = Engine(engineConfig)
    eng.initialize()
    enginePool.offer(eng)
}
poolCapacity = concurrency
isLoaded = true
```

Every `generate*()` method borrows one engine, uses it, and returns it in a
`finally` block:

```kotlin
val eng = enginePool.take()          // blocks only when all N slots are busy
var conversation: Conversation? = null
try {
    if (!isLoaded) return "Error"    // guard against concurrent close()
    conversation = createConversation(eng, config)
    // ... send message / stream tokens ...
} catch (...) { ... }
finally {
    conversation?.close()
    enginePool.offer(eng)            // always return engine to pool
}
```

### Memory Implications

Each Engine instance loads the model weights independently.  Setting *Max
Concurrency = N* therefore uses approximately N times the memory of a single
model instance.  Choose N according to available device RAM:

- Default (N = 1): same memory as before, no parallelism
- N = 2: twice the model memory, two truly simultaneous inferences
- Higher N: proportional memory increase; only beneficial if the device has
  sufficient RAM to hold multiple copies

### Safe engine-close via pool drain

```kotlin
// LlamaModel.close()
isLoaded = false               // prevent new requests from borrowing
scope.cancel()                 // signal in-flight streaming to stop
val count = poolCapacity
poolCapacity = 0
repeat(count) {
    val eng = enginePool.take() // wait for each engine to be returned
    eng.close()                 // safe: engine is idle
}
```

When `scope.cancel()` is called, every active streaming coroutine receives a
cancellation signal.  Its `finally` block closes the conversation and offers the
engine back to the pool, where the drain loop above collects it.

### Request Queue (Semaphore)

`OpenAIApiServer` keeps a `Semaphore` that limits the number of requests
permitted past the HTTP layer at once. This prevents excessive memory use when
many clients connect simultaneously:

```kotlin
// OpenAIApiServer.kt
private var requestSemaphore = Semaphore(maxConcurrency, true /* fair */)
```

The semaphore is initialised from the *Max Concurrency* value in Settings, which
is the same value used to size the engine pool.  Each request that acquires a
semaphore permit is guaranteed to find a free engine slot in the pool.

### Early conversation close on client disconnect

When a streaming client disconnects mid-response, the `onToken` callback throws
an `IOException`.  The `MessageCallback.onMessage` handler catches this,
immediately calls `conversation.close()` on the JNI callback thread, and then
resumes the coroutine continuation with the exception.

Closing the conversation from within `onMessage` sends a stop signal to the
native engine right away.  Without this early close, the engine would continue
generating tokens while the pool slot was still occupied, blocking any new
request that needed that slot.

The `finally` block still contains a `conversation.close()` call as a safety
net.  Calling `close()` on an already-closed `Conversation` is a no-op.

Additionally, subsequent `onMessage` callbacks check `resumed.get()` and return
immediately once the continuation has been resumed, avoiding redundant
`IOException` attempts.

### Methods Protected

All generation methods borrow an engine from the pool for the full conversation
lifetime:

- `generate()` – synchronous text generation
- `generateWithContents()` – synchronous multimodal generation
- `generateStream()` – streaming text generation
- `generateStreamWithContents()` – streaming multimodal generation

## Behaviour Under Load

With *Max Concurrency = N* (N engine instances in the pool):

```
Request 1 → acquire semaphore → borrow engine-1 → run inference → return engine-1 → release semaphore
Request 2 → acquire semaphore → borrow engine-2 → run inference IN PARALLEL with request 1 → …
Request N+1 → wait for semaphore (queue) → …
```

Requests up to N run fully in parallel.  Requests beyond N are queued at the
semaphore and begin executing as soon as a slot frees up.

## Usage Recommendations

### For API Clients

1. **Each request is independent** – there is no shared in-memory session state
   between calls. Conversation history must be sent in full with every request
   (standard OpenAI format).

2. **Parallel execution up to Max Concurrency** – requests beyond the configured
   limit are queued, not failed. Plan timeouts accordingly (the server allows up
   to 5 minutes per request).

3. **Use the `stream` parameter for long responses** – streaming lets the client
   start reading tokens while the server is still generating, reducing
   perceived latency.

### For Server Configuration

- **Max Concurrency** (Settings) controls both the engine pool size and the
  HTTP request queue depth.  Each additional concurrent slot loads one extra
  copy of the model weights into device RAM.
- Default (1) is memory-efficient but serialises all requests.
- Setting 2 or higher enables genuine parallelism at the cost of additional RAM.

## Troubleshooting

### Issue: `FAILED_PRECONDITION: A session already exists`

This error indicates that two conversations were created on the same Engine
instance simultaneously.  With the pool implementation this should not occur
because each Engine is borrowed exclusively for the duration of one
conversation.  If it reappears, verify that:

1. You are running the latest version of the app.
2. No other code path calls `Engine.createConversation()` without borrowing an
   engine from the pool first.

### Issue: Out-of-memory crash after increasing Max Concurrency

**Cause**: Each Engine loads a full copy of the model into device RAM.  With a
large model and high concurrency the device may run out of memory.

**Solution**: Lower *Max Concurrency* to a value the device can sustain, then
restart the server (a model reload is required for pool size to take effect).

### Issue: Second streaming request does not start until the first finishes fully

**Cause (fixed)**: When a streaming client disconnects mid-response, the native
engine continued generating tokens while the pool slot was still held.  The
slot was not released until the stream ended naturally, so any concurrently
queued request had to wait for that slot.

**Fix**: The `onMessage` callback now closes the conversation immediately on
client disconnect, sending a stop signal to the native engine and triggering the
`finally` block (which returns the engine to the pool) without waiting for
natural stream completion.

### Issue: Deadlock concerns

**Solution**: Deadlocks are not possible with this implementation because:
- Each request acquires at most one resource (an engine pool slot).
- No nested acquisition occurs.
- Pool slots are always released in `finally` blocks.
