package com.AK033.drowsinessdetection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Vibrator
import androidx.core.content.ContextCompat

class DrowsinessAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.AK033.drowsinessdetection.ALERT" -> {
                // Play alert sound
                val toneGenerator = ToneGenerator(
                    AudioManager.STREAM_ALARM,
                    ToneGenerator.MAX_VOLUME
                )
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)

                // Vibrate
                val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
                vibrator?.vibrate(1000)

                // You could also start the MainActivity here if needed
                // val launchIntent = Intent(context, MainActivity::class.java)
                // launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // context.startActivity(launchIntent)
            }
        }
    }
}