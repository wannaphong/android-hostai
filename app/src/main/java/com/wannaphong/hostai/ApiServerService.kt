package com.wannaphong.hostai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that runs the OpenAI-compatible API server.
 */
class ApiServerService : Service() {
    
    private val binder = LocalBinder()
    private var apiServer: OpenAIApiServer? = null
    private var model: LlamaModel? = null
    private var isRunning = false
    
    companion object {
        private const val TAG = "ApiServerService"
        const val CHANNEL_ID = "ApiServerChannel"
        const val NOTIFICATION_ID = 1
        const val DEFAULT_PORT = 8080
        
        const val ACTION_START = "com.wannaphong.hostai.ACTION_START"
        const val ACTION_STOP = "com.wannaphong.hostai.ACTION_STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_MODEL_PATH = "model_path"
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): ApiServerService = this@ApiServerService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: "mock-model"
                startServer(port, modelPath)
            }
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun startServer(port: Int = DEFAULT_PORT, modelPath: String = "mock-model"): Boolean {
        if (isRunning) {
            return true
        }
        
        return try {
            // Initialize model
            model = LlamaModel().apply {
                loadModel(modelPath)
            }
            
            // Start API server
            apiServer = OpenAIApiServer(port, model!!)
            apiServer?.start()
            isRunning = true
            
            // Start foreground service
            val notification = createNotification(port)
            startForeground(NOTIFICATION_ID, notification)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            false
        }
    }
    
    fun stopServer() {
        apiServer?.stop()
        model?.close()  // Explicitly close to free native resources
        apiServer = null
        model = null
        isRunning = false
    }
    
    fun isServerRunning(): Boolean = isRunning
    
    fun getServerPort(): Int = DEFAULT_PORT
    
    fun getLoadedModel(): LlamaModel? = model
    
    private fun createNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, "http://localhost:$port"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}
