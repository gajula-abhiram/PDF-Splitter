package com.abhiram.photoq

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore

class MonitorService : Service() {
    private val prefs by lazy { getSharedPreferences("photoq", MODE_PRIVATE) }
    private val app by lazy { application as PhotoQApp }
    private var observer: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "PhotoQ monitoring", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_START -> {
                val now = System.currentTimeMillis()
                prefs.edit().putBoolean("monitoring", true).putBoolean("paused", false).putLong("session_start", now).apply()
            }
        }
        if (!prefs.getBoolean("monitoring", false)) { stopSelf(); return START_NOT_STICKY }
        startForeground(1001, notification())
        register()
        return START_STICKY
    }

    private fun notification() = android.app.Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("PhotoQ monitoring Camera")
        .setContentText("New Camera photos will be queued for answering")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(android.app.Notification.Action.Builder(null, "STOP", PendingIntent.getService(this, 2, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        .build()

    private fun register() {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = discover()
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer!!)
        discover()
    }

    private fun discover() {
        if (prefs.getBoolean("paused", false)) return
        val since = prefs.getLong("session_start", System.currentTimeMillis())
        try { app.processor.queryCameraPhotos(since).forEach(app.processor::enqueueSingleAfterGrace) } catch (_: SecurityException) { }
    }

    private fun stopMonitoring() {
        prefs.edit().putBoolean("monitoring", false).putBoolean("paused", false).apply()
        observer?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Throwable) {} }
        observer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        observer?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Throwable) {} }
        observer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.abhiram.photoq.START"
        const val ACTION_STOP = "com.abhiram.photoq.STOP"
        private const val CHANNEL = "photoq_monitor"
    }
}
