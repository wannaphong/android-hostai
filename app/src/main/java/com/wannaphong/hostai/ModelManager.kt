package com.wannaphong.hostai

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Data class representing a stored model.
 * [path] may be either an absolute file-system path or a content:// URI string.
 */
data class StoredModel(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val addedTimestamp: Long,
    val isSelected: Boolean = false
)

/**
 * Manager class for handling model storage and management.
 * Stores model metadata in SharedPreferences and manages model files in internal storage.
 * Thread-safe for concurrent access.
 */
class ModelManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()  // Synchronization lock for thread safety
    
    companion object {
        private const val TAG = "ModelManager"
        private const val PREFS_NAME = "model_prefs"
        private const val KEY_MODELS = "models"
        private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        private const val MODELS_DIR = "models"
    }
    
    /**
     * Get the directory where models are stored
     */
    private fun getModelsDirectory(): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Get all stored models
     */
    fun getModels(): List<StoredModel> {
        synchronized(lock) {
            return try {
                val json = prefs.getString(KEY_MODELS, null) ?: return emptyList()
                val type = object : TypeToken<List<StoredModel>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to parse models from preferences", e)
                emptyList()
            }
        }
    }
    
    /**
     * Save models list to preferences
     */
    private fun saveModels(models: List<StoredModel>) {
        synchronized(lock) {
            val json = gson.toJson(models)
            prefs.edit().putString(KEY_MODELS, json).apply()
        }
    }
    
    /**
     * Returns true if [path] is a content:// URI rather than a plain file path.
     */
    private fun isContentUri(path: String): Boolean = path.startsWith("content://")

    /**
     * Add a new model by copying from source path
     * @param sourcePath Source file path
     * @param fileName File name
     * @return StoredModel if successful, null otherwise
     */
    fun addModel(sourcePath: String, fileName: String): StoredModel? {
        synchronized(lock) {
            var destFile: File? = null
            try {
                val sourceFile = File(sourcePath)
                if (!sourceFile.exists()) {
                    LogManager.e(TAG, "Source file does not exist: $sourcePath")
                    return null
                }
                
                // Generate unique ID
                val id = generateModelId()
                
                // Copy file to models directory
                val tempDestFile = File(getModelsDirectory(), fileName)
                
                // If file already exists with same name, generate unique name
                destFile = if (tempDestFile.exists()) {
                    val nameWithoutExt = fileName.substringBeforeLast(".")
                    val ext = fileName.substringAfterLast(".")
                    File(getModelsDirectory(), "${nameWithoutExt}_${System.currentTimeMillis()}.$ext")
                } else {
                    tempDestFile
                }
                
                sourceFile.copyTo(destFile, overwrite = true)
                
                // Verify the copy was successful
                if (!destFile.exists() || destFile.length() != sourceFile.length()) {
                    LogManager.e(TAG, "File copy verification failed")
                    destFile.delete()
                    return null
                }
                
                // Create model entry
                val model = StoredModel(
                    id = id,
                    name = destFile.name,
                    path = destFile.absolutePath,
                    sizeBytes = destFile.length(),
                    addedTimestamp = System.currentTimeMillis(),
                    isSelected = false
                )
                
                // Add to list
                val models = getModels().toMutableList()
                models.add(model)
                saveModels(models)
                
                LogManager.i(TAG, "Added model: ${model.name} (${model.sizeBytes / 1024 / 1024} MB)")
                return model
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to add model", e)
                // Cleanup on failure
                destFile?.let { 
                    if (it.exists()) {
                        val deleted = it.delete()
                        if (!deleted) {
                            LogManager.w(TAG, "Failed to cleanup partial model file: ${it.absolutePath}")
                        }
                    }
                }
                return null
            }
        }
    }

    /**
     * Add a model that lives at a content:// URI without copying the file.
     * The caller must have already called [ContentResolver.takePersistableUriPermission]
     * for [uriString] before invoking this.
     * @param uriString  The content URI string (e.g. "content://…")
     * @param fileName   Display name shown in the UI
     * @param fileSize   File size in bytes (pass 0 if unknown)
     * @return StoredModel on success, null on failure
     */
    fun addModelFromUri(uriString: String, fileName: String, fileSize: Long): StoredModel? {
        synchronized(lock) {
            return try {
                val id = generateModelId()
                val model = StoredModel(
                    id = id,
                    name = fileName,
                    path = uriString,
                    sizeBytes = fileSize,
                    addedTimestamp = System.currentTimeMillis(),
                    isSelected = false
                )
                val models = getModels().toMutableList()
                models.add(model)
                saveModels(models)
                LogManager.i(TAG, "Added model from URI: $fileName (${fileSize / 1024 / 1024} MB)")
                model
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to add model from URI", e)
                null
            }
        }
    }

    /**
     * Remove a model by ID
     */
    fun removeModel(modelId: String): Boolean {
        synchronized(lock) {
            try {
                val models = getModels().toMutableList()
                val model = models.find { it.id == modelId } ?: return false
                
                // Only delete the underlying file for file-path models (not content URIs)
                if (!isContentUri(model.path)) {
                    val file = File(model.path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        if (!deleted) {
                            LogManager.e(TAG, "Failed to delete model file: ${model.path}")
                            return false
                        }
                    }
                } else {
                    // Clean up any cached copy in internal model_cache
                    try {
                        val cacheDir = File(context.filesDir, "model_cache")
                        val cachedFile = File(cacheDir, model.name)
                        if (cachedFile.exists()) {
                            val deleted = cachedFile.delete()
                            LogManager.i(TAG, "Deleted cached model file: ${cachedFile.absolutePath}, success=$deleted")
                        }
                    } catch (e: Exception) {
                        LogManager.w(TAG, "Failed to clean up cached model copy: ${e.message}")
                    }
                }
                
                // Remove from list
                models.removeIf { it.id == modelId }
                saveModels(models)
                
                // Clear selection if this was the selected model
                if (getSelectedModelId() == modelId) {
                    setSelectedModelId(null)
                }
                
                LogManager.i(TAG, "Removed model: ${model.name}")
                return true
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to remove model", e)
                return false
            }
        }
    }
    
    /**
     * Get the selected model (validates accessibility).
     * For file-path models the file is checked for existence.
     * For content-URI models the URI is queried via ContentResolver.
     */
    fun getSelectedModel(): StoredModel? {
        synchronized(lock) {
            val selectedId = getSelectedModelId() ?: return null
            val model = getModels().find { it.id == selectedId } ?: return null
            
            return if (isContentUri(model.path)) {
                // Validate that the URI is still accessible.
                // A non-null cursor from query() is sufficient to confirm the URI is valid;
                // moveToFirst() may return false for a valid URI if the cursor has no rows.
                try {
                    val uri = Uri.parse(model.path)
                    val accessible = context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.SIZE), null, null, null
                    )?.use { true } ?: false
                    if (!accessible) {
                        LogManager.w(TAG, "Content URI no longer accessible, clearing selection: ${model.path}")
                        setSelectedModelId(null)
                        null
                    } else {
                        model
                    }
                } catch (e: Exception) {
                    LogManager.w(TAG, "Failed to validate content URI, clearing selection: ${e.message}")
                    setSelectedModelId(null)
                    null
                }
            } else {
                // Validate that the model file still exists
                val file = File(model.path)
                if (!file.exists()) {
                    LogManager.w(TAG, "Selected model file not found, clearing selection: ${model.path}")
                    setSelectedModelId(null)
                    null
                } else {
                    model
                }
            }
        }
    }
    
    /**
     * Get selected model ID from preferences
     */
    private fun getSelectedModelId(): String? {
        return prefs.getString(KEY_SELECTED_MODEL_ID, null)
    }
    
    /**
     * Set the selected model
     */
    fun setSelectedModelId(modelId: String?): Boolean {
        synchronized(lock) {
            try {
                if (modelId != null) {
                    // Verify model exists
                    val model = getModels().find { it.id == modelId }
                    if (model == null) {
                        LogManager.e(TAG, "Model not found: $modelId")
                        return false
                    }
                    
                    // For file-path models, verify the file exists
                    if (!isContentUri(model.path)) {
                        val file = File(model.path)
                        if (!file.exists()) {
                            LogManager.e(TAG, "Model file not found: ${model.path}")
                            return false
                        }
                    }
                    
                    LogManager.i(TAG, "Selected model: ${model.name}")
                } else {
                    LogManager.i(TAG, "Cleared model selection")
                }
                
                prefs.edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply()
                return true
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to set selected model", e)
                return false
            }
        }
    }
    
    /**
     * Get model by ID
     */
    fun getModelById(modelId: String): StoredModel? {
        return getModels().find { it.id == modelId }
    }
    
    /**
     * Generate a unique model ID
     */
    private fun generateModelId(): String {
        return "model_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
    
    /**
     * Get total size of all models
     */
    fun getTotalModelSize(): Long {
        return getModels().sumOf { it.sizeBytes }
    }
    
    /**
     * Validate that model files / URIs are accessible.
     * Removes any that are no longer accessible from the stored list.
     */
    fun validateModels(): List<String> {
        val models = getModels()
        val invalidModels = mutableListOf<String>()
        
        models.forEach { model ->
            if (isContentUri(model.path)) {
                try {
                    val uri = Uri.parse(model.path)
                    // A non-null cursor indicates the URI is accessible
                    val accessible = context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.SIZE), null, null, null
                    )?.use { true } ?: false
                    if (!accessible) {
                        invalidModels.add(model.id)
                        LogManager.w(TAG, "Content URI no longer accessible: ${model.name}")
                    }
                } catch (e: Exception) {
                    invalidModels.add(model.id)
                    LogManager.w(TAG, "Failed to validate content URI for: ${model.name}")
                }
            } else {
                val file = File(model.path)
                if (!file.exists()) {
                    invalidModels.add(model.id)
                    LogManager.w(TAG, "Model file not found: ${model.name}")
                }
            }
        }
        
        if (invalidModels.isNotEmpty()) {
            // Remove invalid models
            val validModels = models.filter { it.id !in invalidModels }
            saveModels(validModels)
        }
        
        return invalidModels
    }
}
