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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the OpenAI-compatible API server.
 */
class ApiServerService : Service() {
    
    private val binder = LocalBinder()
    private var apiServer: OpenAIApiServer? = null
    private var model: LlamaModel? = null
    @Volatile private var isRunning = false
    private var currentPort: Int = DEFAULT_PORT

    // Scope for background operations (model loading, server startup).
    // Cancelled in onDestroy() so any in-flight coroutines are cleaned up.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "ApiServerService"
        const val CHANNEL_ID = "ApiServerChannel"
        const val NOTIFICATION_ID = 1
        const val DEFAULT_PORT = 8080
        
        const val ACTION_START = "com.wannaphong.hostai.ACTION_START"
        const val ACTION_STOP = "com.wannaphong.hostai.ACTION_STOP"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.wannaphong.hostai.ACTION_STOP_FROM_NOTIFICATION"
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
            ACTION_STOP, ACTION_STOP_FROM_NOTIFICATION -> {
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
    
    /**
     * Initiates server startup and returns immediately.
     *
     * The foreground service is promoted synchronously (required within 5 s on
     * Android O+).  All heavy work – Jetty startup and model loading – runs on
     * a background [Dispatchers.IO] coroutine so the main thread is never
     * blocked.  Callers must NOT rely on the return value to determine whether
     * the HTTP server is accepting connections; use [isServerRunning] for that.
     *
     * @return `false` only if the foreground-service promotion fails (the only
     *         synchronous failure mode).  `true` means the service is alive and
     *         startup is in progress.
     */
    fun startServer(port: Int = DEFAULT_PORT, modelPath: String = "mock-model"): Boolean {
        if (isRunning) {
            LogManager.w(TAG, "Server already running")
            return true
        }
        
        currentPort = port
        LogManager.i(TAG, "Starting API server on port $port")
        
        // Start foreground service IMMEDIATELY to prevent Android from killing the service
        // This must be called within 5 seconds on Android O+ or the service will crash
        // We MUST call this before any potentially slow or error-prone operations
        try {
            val notification = createNotification(port)
            startForeground(NOTIFICATION_ID, notification)
            LogManager.i(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            // If we can't even start foreground, we should fail immediately
            Log.e(TAG, "Failed to start foreground service", e)
            LogManager.e(TAG, "Failed to start foreground service", e)
            return false
        }

        // Launch server startup and model loading in the background so that the
        // main thread is never blocked.  Crucially the HTTP server is started
        // BEFORE model loading begins so that the port is open right away and
        // clients are not greeted with "connection refused" while the model loads.
        serviceScope.launch {
            try {
                // Create the LlamaModel wrapper (lightweight – no I/O yet)
                LogManager.i(TAG, "Initializing model...")
                val llamaModel = LlamaModel(contentResolver, applicationContext)
                model = llamaModel

                // Start the HTTP server IMMEDIATELY – before model loading – so
                // browsers and API clients can reach the server without delay.
                LogManager.i(TAG, "Starting HTTP server...")
                val server = OpenAIApiServer(port, llamaModel, this@ApiServerService)
                apiServer = server
                server.start()
                isRunning = true
                LogManager.i(TAG, "API server started successfully")

                // Now load the model in the background.  Non-inference endpoints
                // (/health, /, /chat) respond immediately; inference endpoints
                // will return an appropriate error until the model is ready.
                LogManager.i(TAG, "Loading model: $modelPath")
                val modelLoaded = llamaModel.loadModel(modelPath)
                if (!modelLoaded) {
                    LogManager.e(TAG, "Failed to load model. Server running but model unavailable.")
                } else {
                    LogManager.i(TAG, "Model loaded successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                LogManager.e(TAG, "Failed to start server", e)
                // Clean up foreground service if server failed to start
                try {
                    stopForeground(true)
                } catch (ex: Exception) {
                    LogManager.w(TAG, "Error stopping foreground service: ${ex.message}")
                }
                isRunning = false
            }
        }

        // The foreground service is running.  The HTTP server and model loading
        // are in progress on the background coroutine.  Return true to indicate
        // the foreground service started successfully; poll isServerRunning() to
        // determine when the HTTP server is ready to accept connections.
        return true
    }
    
    fun stopServer() {
        LogManager.i(TAG, "Stopping API server")
        apiServer?.stop()
        model?.close()  // Explicitly close to free native resources
        apiServer = null
        model = null
        isRunning = false
        LogManager.i(TAG, "API server stopped")
    }
    
    fun isServerRunning(): Boolean = isRunning
    
    fun getServerPort(): Int = currentPort
    
    fun getLoadedModel(): LlamaModel? = model
    
    fun getApiServer(): OpenAIApiServer? = apiServer
    
    private fun createNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Create stop action for notification
        val stopIntent = Intent(this, ApiServerService::class.java).apply {
            action = ACTION_STOP_FROM_NOTIFICATION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, "http://localhost:$port"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop_action),
                stopPendingIntent
            )
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serviceScope.cancel()
    }
}
