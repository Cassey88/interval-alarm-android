package nz.cassey.intervalalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Alarms are cleared by a reboot, so re-arm the running session on boot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("running", false)) return

        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) return

        val slots = prefs.getString("slots", "") ?: return
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val show = PendingIntent.getActivity(
            context, 9000, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (entry in slots.split(",")) {
            val parts = entry.split(":")
            val minute = parts[0].toIntOrNull() ?: continue
            if (parts.getOrNull(1) != "pending" || minute <= nowMin) continue

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, minute / 60)
                set(Calendar.MINUTE, minute % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val pi = PendingIntent.getBroadcast(
                context, minute,
                Intent(context, AlarmReceiver::class.java).putExtra("slot", minute),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, show), pi)
        }

        try {
            context.startForegroundService(
                Intent(context, AlarmForegroundService::class.java)
                    .setAction(AlarmForegroundService.ACTION_START)
            )
        } catch (_: Exception) { }
    }
}
