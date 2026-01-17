package com.wannaphong.hostai

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Data class representing a stored model
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
     * Remove a model by ID
     */
    fun removeModel(modelId: String): Boolean {
        synchronized(lock) {
            try {
                val models = getModels().toMutableList()
                val model = models.find { it.id == modelId } ?: return false
                
                // Delete file
                val file = File(model.path)
                if (file.exists()) {
                    val deleted = file.delete()
                    if (!deleted) {
                        LogManager.e(TAG, "Failed to delete model file: ${model.path}")
                        return false
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
     * Get the selected model (validates file exists)
     */
    fun getSelectedModel(): StoredModel? {
        synchronized(lock) {
            val selectedId = getSelectedModelId() ?: return null
            val model = getModels().find { it.id == selectedId } ?: return null
            
            // Validate that the model file still exists
            val file = File(model.path)
            if (!file.exists()) {
                LogManager.w(TAG, "Selected model file not found, clearing selection: ${model.path}")
                setSelectedModelId(null)
                return null
            }
            
            return model
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
                    
                    // Verify file exists
                    val file = File(model.path)
                    if (!file.exists()) {
                        LogManager.e(TAG, "Model file not found: ${model.path}")
                        return false
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
     * Validate that model files exist
     */
    fun validateModels(): List<String> {
        val models = getModels()
        val invalidModels = mutableListOf<String>()
        
        models.forEach { model ->
            val file = File(model.path)
            if (!file.exists()) {
                invalidModels.add(model.id)
                LogManager.w(TAG, "Model file not found: ${model.name}")
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
