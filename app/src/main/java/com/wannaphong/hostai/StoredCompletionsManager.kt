package com.wannaphong.hostai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages stored chat completions with disk persistence.
 * Singleton to share completions between the server and the UI.
 * Thread-safe for concurrent access.
 */
class StoredCompletionsManager internal constructor(private val filesDir: File) {

    private val completions = ConcurrentHashMap<String, StoredCompletion>()
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val completionsFile: File = File(filesDir, FILE_NAME)
    private val saveExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var pendingSave = false

    companion object {
        private const val TAG = "StoredCompletionsManager"
        private const val FILE_NAME = "stored_completions.json"
        private const val SAVE_DELAY_MS = 2000L
        private const val MAX_COMPLETIONS = 500

        @Volatile
        private var instance: StoredCompletionsManager? = null

        fun getInstance(context: Context): StoredCompletionsManager {
            return instance ?: synchronized(this) {
                instance ?: StoredCompletionsManager(context.applicationContext.filesDir).also { instance = it }
            }
        }
    }

    init {
        saveExecutor.execute {
            loadCompletionsFromDisk()
        }
    }

    private fun loadCompletionsFromDisk() {
        try {
            if (completionsFile.exists()) {
                val json = completionsFile.readText()
                if (json.isNotEmpty()) {
                    try {
                        val type = object : TypeToken<List<StoredCompletion>>() {}.type
                        val list: List<StoredCompletion> = gson.fromJson(json, type) ?: emptyList()
                        for (item in list) {
                            completions[item.id] = item
                        }
                        LogManager.i(TAG, "Loaded ${completions.size} stored completions from disk")
                    } catch (e: JsonSyntaxException) {
                        LogManager.e(TAG, "Malformed JSON in completions file, skipping load", e)
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to load stored completions from disk", e)
        }
    }

    private fun saveCompletionsToDisk() {
        try {
            val list = getAllCompletions()
            val json = gson.toJson(list)
            completionsFile.writeText(json)
            LogManager.d(TAG, "Saved ${list.size} completions to disk")
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to save completions to disk", e)
        }
    }

    private fun scheduleSaveToDisk() {
        synchronized(this) {
            if (!pendingSave) {
                pendingSave = true
                saveExecutor.schedule({
                    saveCompletionsToDisk()
                    synchronized(this) {
                        pendingSave = false
                    }
                }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun saveCompletionsToDiskImmediate() {
        synchronized(this) {
            pendingSave = false
        }
        saveCompletionsToDisk()
    }

    /**
     * Save or update a completion.
     */
    fun saveCompletion(completion: StoredCompletion) {
        completions[completion.id] = completion

        // Evict oldest if exceeding limit
        if (completions.size > MAX_COMPLETIONS) {
            val oldest = completions.values.minByOrNull { it.created }
            if (oldest != null) {
                completions.remove(oldest.id)
            }
        }

        scheduleSaveToDisk()
        LogManager.i(TAG, "Saved completion: ${completion.id}")
    }

    /**
     * Get all stored completions, sorted by created timestamp descending.
     */
    fun getAllCompletions(): List<StoredCompletion> {
        return completions.values.toList().sortedByDescending { it.created }
    }

    /**
     * Get a completion by its ID.
     */
    fun getCompletion(id: String): StoredCompletion? {
        return completions[id]
    }

    /**
     * Update metadata for a stored completion.
     */
    fun updateMetadata(id: String, metadata: Map<String, Any>): Boolean {
        val completion = completions[id] ?: return false
        completion.metadata = metadata
        scheduleSaveToDisk()
        LogManager.i(TAG, "Updated metadata for completion: $id")
        return true
    }

    /**
     * Delete a completion by ID.
     */
    fun deleteCompletion(id: String): Boolean {
        val removed = completions.remove(id)
        if (removed != null) {
            saveCompletionsToDiskImmediate()
            LogManager.i(TAG, "Deleted completion: $id")
            return true
        }
        return false
    }

    /**
     * Clear all stored completions.
     */
    fun clearAllCompletions(): Int {
        val count = completions.size
        completions.clear()
        saveCompletionsToDiskImmediate()
        LogManager.i(TAG, "Cleared $count stored completions")
        return count
    }

    /**
     * Shutdown executor service.
     */
    fun shutdown() {
        try {
            saveCompletionsToDiskImmediate()
            saveExecutor.shutdown()
            if (!saveExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error shutting down StoredCompletionsManager", e)
            saveExecutor.shutdownNow()
        }
    }
}
