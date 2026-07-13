package nz.cassey.intervalalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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

        // ring: foreground service loops the sound and shows the full-screen SILENCE screen
        context.startForegroundService(
            Intent(context, AlarmRingService::class.java).putExtra("slot", slot)
        )
    }
}
