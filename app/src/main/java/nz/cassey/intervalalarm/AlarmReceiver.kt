package nz.cassey.intervalalarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getIntExtra("slot", -1)
        if (slot < 0) return

        // hold the CPU awake long enough to get the ring going
        val wl = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "intervalalarm:ring")
        wl.acquire(15000)

        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

        // tick this slot off
        val slots = prefs.getString("slots", "") ?: return
        val updated = slots.split(",").joinToString(",") { entry ->
            val minute = entry.substringBefore(":")
            if (minute.toIntOrNull() == slot) "$minute:done" else entry
        }
        prefs.edit().putString("slots", updated).apply()

        // ring: foreground service loops the sound and shows the full-screen SILENCE screen
        try {
            context.startForegroundService(
                Intent(context, AlarmRingService::class.java).putExtra("slot", slot)
            )
        } catch (e: Exception) {
            // MIUI blocked the service start — fall back to a loud alarm-sound notification
            val label = String.format(Locale.US, "%02d:%02d", slot / 60, slot % 60)
            val fullScreen = PendingIntent.getActivity(
                context, slot,
                Intent(context, AlarmActivity::class.java)
                    .putExtra("slot", slot)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(context, "fallback")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Interval alarm — $label")
                .setContentText("Tap to open")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(slot, n)
        }
    }
}
