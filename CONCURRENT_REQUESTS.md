# Concurrent Request Handling

## Overview

HostAI accepts multiple concurrent HTTP requests, but the underlying LiteRT
inference engine supports **only one active conversation (session) at a time**.
Because of this hardware/SDK constraint, all inference operations are serialised
through a single `inferenceMutex`:

- Incoming requests are queued in FIFO order by the `requestSemaphore` in
  `OpenAIApiServer` (controlled by the *Max Concurrency* setting).
- Each request then acquires `inferenceMutex` in `LlamaModel` before creating a
  conversation, runs inference, and closes the conversation – all while holding
  the lock.
- Only after the conversation is fully closed is the lock released and the next
  queued request allowed to proceed.

This guarantees that `Engine.createConversation()` is never called while another
session is still open, eliminating the
`FAILED_PRECONDITION: A session already exists` error that previously occurred
when concurrency > 1.

## Implementation Details

### Global Inference Mutex

```kotlin
// LlamaModel.kt
private val inferenceMutex = Mutex()
```

The mutex is held for the **complete** conversation lifetime in every
`generate*()` method:

```kotlin
// inferenceMutex is imported as mutexWithLock (see LlamaModel.kt imports)
inferenceMutex.mutexWithLock {
    val conversation = createConversation(config) ?: return@mutexWithLock "Error"
    try {
        // ... send message / stream tokens ...
    } finally {
        conversation.close()   // lock released only after close()
    }
}
```

### Request Queue (Semaphore)

`OpenAIApiServer` keeps a `Semaphore` that limits the number of requests
permitted past the HTTP layer at once. This prevents excessive memory use when
many clients connect simultaneously:

```kotlin
// OpenAIApiServer.kt
private var requestSemaphore = Semaphore(maxConcurrency, true /* fair */)
```

The semaphore is initialised from the *Max Concurrency* value in Settings. With
the inference mutex in place this value mainly controls how many requests are
held in memory while waiting for the engine – it no longer causes
concurrent-session errors regardless of what value is chosen.

### Methods Protected

All generation methods hold `inferenceMutex` for the full conversation lifetime:

- `generate()` – synchronous text generation
- `generateWithContents()` – synchronous multimodal generation
- `generateStream()` – streaming text generation
- `generateStreamWithContents()` – streaming multimodal generation

## Behaviour Under Load

```
Request 1 → acquire semaphore → acquire inferenceMutex → run inference → close conversation → release mutex → release semaphore
Request 2 → acquire semaphore → wait for inferenceMutex → acquire inferenceMutex → run inference → …
Request 3 → wait for semaphore → …
```

Requests are processed in the order they arrive (FIFO), so clients see
predictable queuing rather than random failures.

## Usage Recommendations

### For API Clients

1. **Each request is independent** – there is no shared in-memory session state
   between calls. Conversation history must be sent in full with every request
   (standard OpenAI format).

2. **Expect queuing under concurrent load** – because inference is serialised,
   the second request will wait for the first to finish. Plan timeouts
   accordingly (the server allows up to 5 minutes per request).

3. **Use the `stream` parameter for long responses** – streaming lets the client
   start reading tokens while the server is still generating, reducing
   perceived latency.

### For Server Configuration

- **Max Concurrency** (Settings) controls the size of the in-memory request
  queue. Values > 1 are valid; they queue requests without causing errors.
- The default value is suitable for most use cases.

## Troubleshooting

### Issue: `FAILED_PRECONDITION: A session already exists`

This was the bug fixed by this change. If it reappears, verify that:

1. You are running the latest version of the app.
2. No other code path calls `Engine.createConversation()` outside the
   `inferenceMutex` lock.

### Issue: Requests time out under high concurrency

**Cause**: The LiteRT engine processes one request at a time, so response time
scales linearly with queue depth.

**Solution**: Lower *Max Concurrency* in Settings to shed load early (returning
HTTP 503) rather than letting requests pile up and time out.

### Issue: Deadlock concerns

**Solution**: Deadlocks are not possible with this implementation because:
- Each request acquires at most one lock (`inferenceMutex`).
- No nested lock acquisition occurs.
- The lock is always released in a `finally` block.
