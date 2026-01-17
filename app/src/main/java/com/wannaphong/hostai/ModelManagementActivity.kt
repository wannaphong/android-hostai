package com.wannaphong.hostai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wannaphong.hostai.databinding.ActivityModelManagementBinding
import com.wannaphong.hostai.databinding.ItemModelBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Activity for managing LiteRT models
 */
class ModelManagementActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityModelManagementBinding
    private lateinit var modelManager: ModelManager
    private lateinit var adapter: ModelsAdapter
    
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        modelManager = ModelManager(this)
        
        setupUI()
        loadModels()
    }
    
    private fun setupUI() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.model_management)
        
        // Setup RecyclerView
        adapter = ModelsAdapter(emptyList(), modelManager.getSelectedModel()?.id) { model, action ->
            when (action) {
                ModelAction.SELECT -> selectModel(model)
                ModelAction.VIEW -> showModelDetails(model)
                ModelAction.DELETE -> confirmDeleteModel(model)
            }
        }
        
        binding.modelsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.modelsRecyclerView.adapter = adapter
        
        // Setup buttons
        binding.addModelButton.setOnClickListener {
            selectModelFile()
        }
        
        binding.refreshButton.setOnClickListener {
            loadModels()
        }
    }
    
    private fun loadModels() {
        // Validate models first
        modelManager.validateModels()
        
        val models = modelManager.getModels()
        val selectedModelId = modelManager.getSelectedModel()?.id
        
        if (models.isEmpty()) {
            binding.emptyStateText.visibility = View.VISIBLE
            binding.modelsRecyclerView.visibility = View.GONE
            binding.modelCountText.text = "0 model(s)"
            binding.totalSizeText.text = "Total size: 0 MB"
        } else {
            binding.emptyStateText.visibility = View.GONE
            binding.modelsRecyclerView.visibility = View.VISIBLE
            binding.modelCountText.text = "${models.size} model(s)"
            
            val totalSizeMB = modelManager.getTotalModelSize() / 1024 / 1024
            binding.totalSizeText.text = "Total size: $totalSizeMB MB"
            
            adapter.updateModels(models, selectedModelId)
        }
    }
    
    private fun selectModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
        }
        filePickerLauncher.launch(intent)
    }
    
    private fun handleSelectedFile(uri: Uri) {
        try {
            LogManager.i("ModelManagement", "User selected a file")
            
            // Get file name and size
            var fileName: String? = null
            var fileSize: Long = 0
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                fileName = cursor.getString(nameIndex)
                fileSize = cursor.getLong(sizeIndex)
            }
            
            LogManager.i("ModelManagement", "Selected file: $fileName (${fileSize / 1024 / 1024} MB)")
            
            // Validate file name and extension
            val validFileName = fileName
            if (validFileName == null || !validFileName.endsWith(".litertlm", ignoreCase = true)) {
                LogManager.w("ModelManagement", "Invalid file type selected: $fileName")
                Toast.makeText(this, "Please select a LiteRT model file (.litertlm)", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Check file size (limit to 10GB to avoid OOM)
            val maxFileSize = 10L * 1024 * 1024 * 1024 // 10GB
            if (fileSize > maxFileSize) {
                LogManager.w("ModelManagement", "File too large: ${fileSize / 1024 / 1024 / 1024} GB")
                Toast.makeText(this, "File too large. Maximum size is 10GB", Toast.LENGTH_LONG).show()
                return
            }
            
            // Show progress
            Toast.makeText(this, "Adding model...", Toast.LENGTH_SHORT).show()
            
            // Copy file to temporary location first
            LogManager.i("ModelManagement", "Copying file to temporary storage...")
            val tempFile = File.createTempFile("model_temp", ".litertlm", cacheDir)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Add model using ModelManager
            val model = modelManager.addModel(tempFile.absolutePath, validFileName)
            
            // Delete temp file
            tempFile.delete()
            
            if (model != null) {
                LogManager.i("ModelManagement", "Model added successfully: ${model.name}")
                Toast.makeText(this, getString(R.string.model_added), Toast.LENGTH_SHORT).show()
                loadModels()
            } else {
                LogManager.e("ModelManagement", "Failed to add model")
                Toast.makeText(this, "Failed to add model", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            LogManager.e("ModelManagement", "Failed to load model file", e)
            Toast.makeText(this, "Failed to load model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun selectModel(model: StoredModel) {
        if (modelManager.setSelectedModelId(model.id)) {
            Toast.makeText(this, getString(R.string.model_selected_success), Toast.LENGTH_SHORT).show()
            loadModels()
            
            // Return result to MainActivity
            setResult(Activity.RESULT_OK)
        } else {
            Toast.makeText(this, "Failed to select model", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showModelDetails(model: StoredModel) {
        val sizeMB = model.sizeBytes / 1024 / 1024
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val date = dateFormat.format(Date(model.addedTimestamp))
        
        val message = """
            Name: ${model.name}
            
            Size: $sizeMB MB
            
            Added: $date
            
            Path: ${model.path}
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.model_info))
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun confirmDeleteModel(model: StoredModel) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete_model))
            .setMessage("Delete \"${model.name}\"?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                deleteModel(model)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun deleteModel(model: StoredModel) {
        if (modelManager.removeModel(model.id)) {
            Toast.makeText(this, getString(R.string.model_removed), Toast.LENGTH_SHORT).show()
            loadModels()
        } else {
            Toast.makeText(this, "Failed to remove model", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

enum class ModelAction {
    SELECT, VIEW, DELETE
}

class ModelsAdapter(
    private var models: List<StoredModel>,
    private var selectedModelId: String?,
    private val onAction: (StoredModel, ModelAction) -> Unit
) : RecyclerView.Adapter<ModelsAdapter.ViewHolder>() {
    
    class ViewHolder(val binding: ItemModelBinding) : RecyclerView.ViewHolder(binding.root)
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]
        val sizeMB = model.sizeBytes / 1024 / 1024
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val date = dateFormat.format(Date(model.addedTimestamp))
        
        holder.binding.apply {
            modelNameText.text = model.name
            modelSizeText.text = "Size: $sizeMB MB"
            modelDateText.text = "Added: $date"
            
            // Set radio button state
            modelRadioButton.isChecked = model.id == selectedModelId
            
            // Handle radio button click
            modelRadioButton.setOnClickListener {
                onAction(model, ModelAction.SELECT)
            }
            
            // Handle card click to select
            root.setOnClickListener {
                onAction(model, ModelAction.SELECT)
            }
            
            viewModelButton.setOnClickListener {
                onAction(model, ModelAction.VIEW)
            }
            
            deleteModelButton.setOnClickListener {
                onAction(model, ModelAction.DELETE)
            }
        }
    }
    
    override fun getItemCount() = models.size
    
    fun updateModels(newModels: List<StoredModel>, newSelectedModelId: String?) {
        models = newModels
        selectedModelId = newSelectedModelId
        notifyDataSetChanged()
    }
}
