package com.leafbt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Foreground service kept alive while a dongle session is active, so Android
 * doesn't kill the process or block the Bluetooth link when the app is in the
 * background (driving with the screen off / another app on top).
 */
class LeafService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Leaf Battery Test", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Leaf Battery Test")
            .setContentText("Connected to the car")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // e.g. missing BLUETOOTH_CONNECT for the connectedDevice type (API 34),
            // or a background-start restriction: don't crash, just don't stay foreground
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "leafbt"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LeafService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LeafService::class.java))
        }
    }
}
