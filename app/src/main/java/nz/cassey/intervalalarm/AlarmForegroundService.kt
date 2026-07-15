package nz.cassey.intervalalarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.Calendar
import java.util.Locale

class AlarmForegroundService : Service() {

    companion object {
        const val ACTION_START = "nz.cassey.intervalalarm.START"
        const val ACTION_RING = "nz.cassey.intervalalarm.RING"
        const val ACTION_SILENCE = "nz.cassey.intervalalarm.SILENCE"
        const val ACTION_STOP = "nz.cassey.intervalalarm.STOP"
        const val CHANNEL_RING = "ring"
        const val CHANNEL_STATUS = "status"
        const val NOTIF_ID = 1
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoSilence = Runnable { silence() }

    // watchdog: checks the schedule every 15s while the service lives,
    // so alarms fire even if an AlarmManager broadcast is swallowed
    private val watchdog = object : Runnable {
        override fun run() {
            checkDue()
            handler.postDelayed(this, 15000)
        }
    }

    private fun prefs() = getSharedPreferences("alarms", Context.MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_SILENCE -> { silence(); return START_STICKY }
            ACTION_RING -> {
                startForeground(NOTIF_ID, sentinelNotification())
                val slot = intent.getIntExtra("slot", -1)
                if (slot >= 0) ring(slot)
            }
            else -> { // ACTION_START or restart after being killed
                startForeground(NOTIF_ID, sentinelNotification())
                handler.removeCallbacks(watchdog)
                handler.post(watchdog)
            }
        }
        return START_STICKY
    }

    /* ---------- schedule helpers ---------- */

    private fun nowMin(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun checkDue() {
        if (!prefs().getBoolean("running", false)) { stopEverything(); return }
        val now = nowMin()
        val slots = prefs().getString("slots", "") ?: return
        var due = -1
        var anyPending = false
        for (entry in slots.split(",")) {
            val parts = entry.split(":")
            val minute = parts[0].toIntOrNull() ?: continue
            if (parts[1] == "pending") {
                anyPending = true
                if (due < 0 && now >= minute && now < minute + 3) due = minute
            }
        }
        if (due >= 0) ring(due)
        else if (!anyPending && player == null) {
            // all done — finish the session
            prefs().edit().putBoolean("running", false).apply()
            stopEverything()
        } else {
            notifyManagerUpdate()
        }
    }

    private fun markDone(slot: Int) {
        val slots = prefs().getString("slots", "") ?: return
        val updated = slots.split(",").joinToString(",") { entry ->
            val minute = entry.substringBefore(":")
            if (minute.toIntOrNull() == slot) "$minute:done" else entry
        }
        prefs().edit().putString("slots", updated).apply()
    }

    private fun nextPendingLabel(): String? {
        val slots = prefs().getString("slots", "") ?: return null
        val now = nowMin()
        for (entry in slots.split(",")) {
            val parts = entry.split(":")
            val minute = parts[0].toIntOrNull() ?: continue
            if (parts[1] == "pending" && minute + 3 > now)
                return String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)
        }
        return null
    }

    /* ---------- ringing ---------- */

    private fun ring(slot: Int) {
        markDone(slot)
        val label = String.format(Locale.US, "%02d:%02d", slot / 60, slot % 60)

        val fullScreen = PendingIntent.getActivity(
            this, slot,
            Intent(this, AlarmActivity::class.java)
                .putExtra("slot", slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val silencePi = PendingIntent.getService(
            this, 2,
            Intent(this, AlarmForegroundService::class.java).setAction(ACTION_SILENCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Interval alarm — $label")
            .setContentText("Tap Silence to stop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(Notification.Action.Builder(null, "Silence", silencePi).build())
            .setOngoing(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, n)

        val uri = when (prefs().getString("channel", "alarm")) {
            "ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            "notification" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@AlarmForegroundService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setWakeMode(this@AlarmForegroundService, PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { }

        vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))

        handler.removeCallbacks(autoSilence)
        handler.postDelayed(autoSilence, 60000)
    }

    private fun silence() {
        handler.removeCallbacks(autoSilence)
        try { player?.stop() } catch (_: Exception) { }
        player?.release(); player = null
        vibrator?.cancel()
        // back to the quiet sentinel notification (or finish if nothing left)
        checkDoneOrContinue()
    }

    private fun checkDoneOrContinue() {
        if (nextPendingLabel() == null) {
            prefs().edit().putBoolean("running", false).apply()
            stopEverything()
        } else {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, sentinelNotification())
        }
    }

    private fun notifyManagerUpdate() {
        if (player == null) {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, sentinelNotification())
        }
    }

    private fun sentinelNotification(): Notification {
        val next = nextPendingLabel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Interval alarms running")
            .setContentText(if (next != null) "Next alarm at $next" else "Finishing up…")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun stopEverything() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(autoSilence)
        try { player?.stop() } catch (_: Exception) { }
        player?.release(); player = null
        vibrator?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(autoSilence)
        try { player?.stop() } catch (_: Exception) { }
        player?.release(); player = null
        vibrator?.cancel()
        super.onDestroy()
    }
}
