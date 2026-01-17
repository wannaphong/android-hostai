package com.wannaphong.hostai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.wannaphong.hostai.databinding.ActivityStoredCompletionsBinding
import com.wannaphong.hostai.databinding.ItemStoredCompletionBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Activity to view and manage stored chat completions.
 */
class StoredCompletionsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityStoredCompletionsBinding
    private var apiServerService: ApiServerService? = null
    private var isBound = false
    private lateinit var adapter: CompletionsAdapter
    private val gson = GsonBuilder().setPrettyPrinting().create()
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ApiServerService.LocalBinder
            apiServerService = binder.getService()
            isBound = true
            loadCompletions()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            apiServerService = null
            isBound = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoredCompletionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        bindToService()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
    
    private fun setupUI() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Stored Completions"
        
        // Setup RecyclerView
        adapter = CompletionsAdapter(emptyList()) { completion, action ->
            when (action) {
                CompletionAction.VIEW -> showCompletionDetails(completion)
                CompletionAction.EXPORT -> exportCompletion(completion)
                CompletionAction.DELETE -> deleteCompletion(completion)
            }
        }
        
        binding.completionsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.completionsRecyclerView.adapter = adapter
        binding.completionsRecyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        
        // Setup buttons
        binding.refreshButton.setOnClickListener {
            loadCompletions()
        }
        
        binding.exportAllButton.setOnClickListener {
            exportAllCompletions()
        }
        
        binding.clearAllButton.setOnClickListener {
            showClearAllConfirmation()
        }
    }
    
    private fun bindToService() {
        val intent = Intent(this, ApiServerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun loadCompletions() {
        val apiServer = apiServerService?.getApiServer()
        if (apiServer == null) {
            binding.emptyStateText.visibility = View.VISIBLE
            binding.emptyStateText.text = "Server is not running"
            binding.completionsRecyclerView.visibility = View.GONE
            binding.exportAllButton.isEnabled = false
            binding.clearAllButton.isEnabled = false
            return
        }
        
        val completions = apiServer.getStoredCompletions()
        
        if (completions.isEmpty()) {
            binding.emptyStateText.visibility = View.VISIBLE
            binding.emptyStateText.text = "No stored completions.\n\nCreate completions with store=true parameter."
            binding.completionsRecyclerView.visibility = View.GONE
            binding.exportAllButton.isEnabled = false
            binding.clearAllButton.isEnabled = false
        } else {
            binding.emptyStateText.visibility = View.GONE
            binding.completionsRecyclerView.visibility = View.VISIBLE
            binding.exportAllButton.isEnabled = true
            binding.clearAllButton.isEnabled = true
            adapter.updateCompletions(completions)
        }
        
        binding.countText.text = "${completions.size} completion(s)"
    }
    
    private fun showCompletionDetails(completion: StoredCompletion) {
        val messages = getAllMessages(completion)
        val messageText = messages.joinToString("\n\n") { msg ->
            "${msg["role"]?.uppercase()}: ${msg["content"]}"
        }
        
        val metadataText = if (completion.metadata != null) {
            "\n\nMetadata:\n${gson.toJson(completion.metadata)}"
        } else {
            ""
        }
        
        AlertDialog.Builder(this)
            .setTitle("Completion: ${completion.id}")
            .setMessage("Created: ${formatTimestamp(completion.created)}\nModel: ${completion.model}\n\n$messageText$metadataText")
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard(completion.id, gson.toJson(completion))
            }
            .show()
    }
    
    private fun getAllMessages(completion: StoredCompletion): List<Map<String, String>> {
        val allMessages = completion.messages.toMutableList()
        allMessages.add(mapOf(
            "role" to "assistant",
            "content" to completion.responseContent
        ))
        return allMessages
    }
    
    private fun exportCompletion(completion: StoredCompletion) {
        try {
            val fileName = "completion_${completion.id}.json"
            val file = File(getExternalFilesDir(null), fileName)
            FileOutputStream(file).use { output ->
                output.write(gson.toJson(completion).toByteArray())
            }
            Toast.makeText(this, "Exported to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun exportAllCompletions() {
        val apiServer = apiServerService?.getApiServer() ?: return
        val completions = apiServer.getStoredCompletions()
        
        if (completions.isEmpty()) {
            Toast.makeText(this, "No completions to export", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "completions_$timestamp.json"
            val file = File(getExternalFilesDir(null), fileName)
            FileOutputStream(file).use { output ->
                output.write(gson.toJson(completions).toByteArray())
            }
            Toast.makeText(this, "Exported ${completions.size} completion(s) to:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun deleteCompletion(completion: StoredCompletion) {
        AlertDialog.Builder(this)
            .setTitle("Delete Completion")
            .setMessage("Delete completion ${completion.id}?")
            .setPositiveButton("Delete") { _, _ ->
                val apiServer = apiServerService?.getApiServer()
                if (apiServer?.deleteStoredCompletion(completion.id) == true) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    loadCompletions()
                } else {
                    Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showClearAllConfirmation() {
        val apiServer = apiServerService?.getApiServer() ?: return
        val count = apiServer.getStoredCompletions().size
        
        AlertDialog.Builder(this)
            .setTitle("Clear All Completions")
            .setMessage("Delete all $count stored completion(s)?")
            .setPositiveButton("Clear All") { _, _ ->
                val cleared = apiServer.clearAllStoredCompletions()
                Toast.makeText(this, "Cleared $cleared completion(s)", Toast.LENGTH_SHORT).show()
                loadCompletions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp * 1000) // Convert from seconds to milliseconds
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

enum class CompletionAction {
    VIEW, EXPORT, DELETE
}

class CompletionsAdapter(
    private var completions: List<StoredCompletion>,
    private val onAction: (StoredCompletion, CompletionAction) -> Unit
) : RecyclerView.Adapter<CompletionsAdapter.ViewHolder>() {
    
    class ViewHolder(val binding: ItemStoredCompletionBinding) : RecyclerView.ViewHolder(binding.root)
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStoredCompletionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val completion = completions[position]
        val date = Date(completion.created * 1000)
        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        
        holder.binding.apply {
            completionIdText.text = completion.id
            completionTimeText.text = dateFormat.format(date)
            completionModelText.text = "Model: ${completion.model}"
            
            val messageCount = completion.messages.size + 1 // +1 for assistant response
            completionMessagesText.text = "$messageCount message(s)"
            
            if (completion.metadata != null) {
                completionMetadataText.visibility = View.VISIBLE
                completionMetadataText.text = "Has metadata"
            } else {
                completionMetadataText.visibility = View.GONE
            }
            
            root.setOnClickListener {
                onAction(completion, CompletionAction.VIEW)
            }
            
            exportButton.setOnClickListener {
                onAction(completion, CompletionAction.EXPORT)
            }
            
            deleteButton.setOnClickListener {
                onAction(completion, CompletionAction.DELETE)
            }
        }
    }
    
    override fun getItemCount() = completions.size
    
    fun updateCompletions(newCompletions: List<StoredCompletion>) {
        completions = newCompletions
        notifyDataSetChanged()
    }
}
