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
- `close()` also acquires `inferenceMutex` before freeing the native engine,
  guaranteeing that no in-flight inference is running when the engine is
  destroyed.

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
`generate*()` method, and is also acquired by `close()` before the engine is
destroyed:

```kotlin
// inferenceMutex is imported as mutexWithLock (see LlamaModel.kt imports)
inferenceMutex.mutexWithLock {
    if (!isLoaded) return@mutexWithLock "Error"
    val conversation = createConversation(config) ?: return@mutexWithLock "Error"
    try {
        // ... send message / stream tokens ...
    } finally {
        conversation.close()   // lock released only after close()
    }
}
```

### Why ReentrantReadWriteLock is NOT used for streaming

An earlier implementation wrapped streaming coroutines with
`engineLifecycleLock.read {}` (a `ReentrantReadWriteLock`) to prevent
`close()` from freeing the engine while `sendMessageAsync` callbacks were still
firing.  This caused a subtle but critical bug under concurrent load:

`ReentrantReadWriteLock.ReadLock` has **thread affinity** – `unlock()` must be
called from the same thread that called `lock()`.  Kotlin coroutines on
`Dispatchers.IO` can resume on a **different** thread after each suspension
point (`inferenceMutex.mutexWithLock` and `suspendCancellableCoroutine` are
both suspension points).  When a second concurrent streaming request had to
wait for `inferenceMutex`, it suspended and resumed on a different IO thread,
causing `IllegalMonitorStateException` when the `read {}` block tried to
unlock – silently failing every concurrent streaming request.

`inferenceMutex` is a Kotlin coroutine `Mutex`, which is fully
**coroutine-friendly**: its ownership is logical (coroutine-bound), not
thread-bound, so thread switches between suspension points are safe.

### Safe engine-close via inferenceMutex

```kotlin
// LlamaModel.close()
isLoaded = false          // prevent new requests from entering inferenceMutex
runBlocking {
    inferenceMutex.mutexWithLock {
        // At this point no inference is running.
        scope.cancel()    // cancel any pending streaming jobs
        engine?.close()   // safe: engine is not in use
        engine = null
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

### Early conversation close on client disconnect

When a streaming client disconnects mid-response, the `onToken` callback throws
an `IOException`.  The `MessageCallback.onMessage` handler catches this,
immediately calls `conversation.close()` on the JNI callback thread, and then
resumes the coroutine continuation with the exception.

Closing the conversation from within `onMessage` sends a stop signal to the
native engine right away.  Without this early close, the engine would continue
generating tokens while `inferenceMutex` was still held (because the finally
block that calls `close()` can only run once the coroutine is scheduled and
dispatched, which may lag behind the JNI callbacks by many tokens).

The `finally` block still contains a `conversation.close()` call as a safety
net.  Calling `close()` on an already-closed `Conversation` is a no-op.

Additionally, subsequent `onMessage` callbacks check `resumed.get()` and return
immediately once the continuation has been resumed, avoiding redundant
`IOException` attempts.

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

This was the bug fixed by the `inferenceMutex` change. If it reappears, verify
that:

1. You are running the latest version of the app.
2. No other code path calls `Engine.createConversation()` outside the
   `inferenceMutex` lock.

### Issue: Second streaming request does not start until the first finishes fully

**Cause (fixed)**: When a streaming client disconnects mid-response, the native
engine continued generating tokens while `inferenceMutex` was still held.  The
lock was not released until the stream ended naturally, so any concurrently
queued request had to wait.

**Fix**: The `onMessage` callback now closes the conversation immediately on
client disconnect, sending a stop signal to the native engine without waiting
for the coroutine finally block to run.

**Cause**: The LiteRT engine processes one request at a time, so response time
scales linearly with queue depth.

**Solution**: Lower *Max Concurrency* in Settings to shed load early (returning
HTTP 503) rather than letting requests pile up and time out.

### Issue: Deadlock concerns

**Solution**: Deadlocks are not possible with this implementation because:
- Each request acquires at most one lock (`inferenceMutex`).
- No nested lock acquisition occurs.
- The lock is always released in a `finally` block.
