package nz.cassey.intervalalarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.Locale

class AlarmRingService : Service() {

    companion object {
        const val ACTION_STOP = "nz.cassey.intervalalarm.STOP"
        const val CHANNEL_RING = "ring"
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopRinging() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }

        val slot = intent?.getIntExtra("slot", -1) ?: -1
        val label = if (slot >= 0)
            String.format(Locale.US, "%02d:%02d", slot / 60, slot % 60) else ""

        // full-screen intent: opens the big SILENCE screen, even over the lock screen
        val fullScreen = PendingIntent.getActivity(
            this, slot,
            Intent(this, AlarmActivity::class.java)
                .putExtra("slot", slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val silence = PendingIntent.getService(
            this, 1,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Interval alarm — $label")
            .setContentText("Tap Silence to stop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(Notification.Action.Builder(null, "Silence", silence).build())
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        // looping alarm sound (through the alarm audio stream — respects alarm volume, plays in DND)
        val prefs = getSharedPreferences("alarms", MODE_PRIVATE)
        val uri = when (prefs.getString("channel", "alarm")) {
            "ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            "notification" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@AlarmRingService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { }

        vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))

        // never ring forever: auto-silence after 60 seconds
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 60000)

        return START_NOT_STICKY
    }

    private fun stopRinging() {
        handler.removeCallbacks(autoStop)
        try { player?.stop() } catch (_: Exception) { }
        player?.release()
        player = null
        vibrator?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        try { player?.stop() } catch (_: Exception) { }
        player?.release()
        player = null
        vibrator?.cancel()
        super.onDestroy()
    }
}
