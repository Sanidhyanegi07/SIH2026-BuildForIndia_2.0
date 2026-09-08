package com.example.georescux.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.georescux.R
import com.example.georescux.ui.alerts.AlertsActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts a high-priority heads-up notification when a BLE mesh SOS alert arrives on this device.
 *
 * Without this, Device B silently saves the alert to history with no indication to the user.
 * The user would have to open the app and tap "Alerts" to discover that someone nearby needs help.
 *
 * Notification channel is created lazily on first use (safe to call repeatedly — Android ignores
 * duplicate createNotificationChannel calls once the channel exists).
 */
object SosAlertNotifier {

    private const val CHANNEL_ID = "georescux_sos_alerts"
    private const val CHANNEL_NAME = "SOS Alerts"
    private const val CHANNEL_DESC = "Alerts when a nearby device sends an SOS emergency"

    private val notifIdCounter = AtomicInteger(1000)

    fun notify(context: Context, originId: String, startedAtMs: Long) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        if (!hasNotificationPermission(appContext)) return

        // Tapping the notification opens the Alerts screen.
        val intent = Intent(appContext, AlertsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notifIdCounter.get(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val shortId = originId.takeLast(6).uppercase()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_logo)
            .setContentTitle("🚨 SOS Alert Received")
            .setContentText("A nearby device ($shortId) has sent an emergency SOS.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "A nearby device (ID: …$shortId) has sent an offline SOS emergency via Bluetooth mesh. " +
                            "Tap to view details in the Alerts screen."
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            // Vibrate + full-screen intent for maximum visibility
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        NotificationManagerCompat.from(appContext)
            .notify(notifIdCounter.getAndIncrement(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                enableLights(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
