package nz.cassey.intervalalarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getIntExtra("slot", -1)
        if (slot < 0) return

        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

        // tick this slot off
        val slots = prefs.getString("slots", "") ?: return
        val updated = slots.split(",").joinToString(",") { entry ->
            val minute = entry.substringBefore(":")
            if (minute.toIntOrNull() == slot) "$minute:done" else entry
        }
        prefs.edit().putString("slots", updated).apply()

        // ring: high-importance notification on the channel matching the chosen sound
        val channel = prefs.getString("channel", "alarm") ?: "alarm"
        val label = String.format(Locale.US, "%02d:%02d", slot / 60, slot % 60)

        val tap = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Interval alarm — $label")
            .setContentText("Tap to open the schedule")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(slot, notification)
    }
}
