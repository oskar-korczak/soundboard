package com.soundboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class SoundService : Service() {

    companion object {
        private const val TAG = "Soundboard"
        private const val CHANNEL_ID = "soundboard_channel"
        private const val NOTIFICATION_ID = 1
    }

    private val binder = LocalBinder()
    private var soundServer: SoundServer? = null
    private var soundPlayer: SoundPlayer? = null

    inner class LocalBinder : Binder() {
        fun getService(): SoundService = this@SoundService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        RecentSoundsManager.init(applicationContext)
        soundPlayer = SoundPlayer(applicationContext)
        soundServer = SoundServer(8080, soundPlayer!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        soundPlayer?.release()
    }

    fun startServer() {
        try {
            Log.d(TAG, "Starting HTTP server on port 8080")
            soundServer?.start()
            Log.d(TAG, "HTTP server started, isAlive=${soundServer?.isAlive}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }
    }

    fun stopServer() {
        soundServer?.stop()
    }

    fun isRunning(): Boolean {
        return soundServer?.isAlive == true
    }

    fun playTestSound() {
        playSound("mgs-alert.mp3")
    }

    fun playSound(filename: String) {
        val url = "https://www.myinstants.com/media/sounds/$filename"
        soundPlayer?.play(url)
        RecentSoundsManager.addSound(filename)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Soundboard Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the soundboard server running"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Soundboard Server")
            .setContentText("Server is running on port 8080")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

}
