package com.AK033.drowsinessdetection

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import timber.log.Timber

class DrowsinessDetectionApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize logging (no need for BuildConfig check)
        Timber.plant(Timber.DebugTree())

        // Create notification channel
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "drowsiness_channel",
                "Drowsiness Detection",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for drowsiness detection alerts"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}