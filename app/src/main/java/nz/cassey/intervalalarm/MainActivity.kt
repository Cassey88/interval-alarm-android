package nz.cassey.intervalalarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var fromBtn: Button
    private lateinit var toBtn: Button
    private lateinit var intervalSpin: Spinner
    private lateinit var soundSpin: Spinner
    private lateinit var startBtn: Button
    private lateinit var statusText: TextView
    private lateinit var listView: ListView

    private var fromH = 9; private var fromM = 0
    private var toH = 12; private var toM = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshList()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)

        fromBtn = findViewById(R.id.fromBtn)
        toBtn = findViewById(R.id.toBtn)
        intervalSpin = findViewById(R.id.intervalSpin)
        soundSpin = findViewById(R.id.soundSpin)
        startBtn = findViewById(R.id.startBtn)
        statusText = findViewById(R.id.statusText)
        listView = findViewById(R.id.listView)

        createChannels()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        intervalSpin.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Every 5 min", "Every 10 min", "Every 15 min", "Every 20 min", "Every 30 min", "Every 60 min")
        )
        intervalSpin.setSelection(2) // 15 min default

        soundSpin.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Alarm sound", "Ringtone", "Notification sound")
        )

        // restore saved settings
        fromH = prefs.getInt("fromH", 9); fromM = prefs.getInt("fromM", 0)
        toH = prefs.getInt("toH", 12); toM = prefs.getInt("toM", 0)
        intervalSpin.setSelection(prefs.getInt("intervalIdx", 2))
        soundSpin.setSelection(prefs.getInt("soundIdx", 0))
        updateTimeButtons()

        fromBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> fromH = h; fromM = m; updateTimeButtons() }, fromH, fromM, true).show()
        }
        toBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> toH = h; toM = m; updateTimeButtons() }, toH, toM, true).show()
        }
        startBtn.setOnClickListener {
            if (prefs.getBoolean("running", false)) stopAlarms() else startAlarms()
        }

        updateButtonState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun updateTimeButtons() {
        fromBtn.text = String.format(Locale.US, "From %02d:%02d", fromH, fromM)
        toBtn.text = String.format(Locale.US, "To %02d:%02d", toH, toM)
    }

    private fun updateButtonState() {
        val running = prefs.getBoolean("running", false)
        startBtn.text = if (running) "Stop alarms" else "Start alarms"
        fromBtn.isEnabled = !running
        toBtn.isEnabled = !running
        intervalSpin.isEnabled = !running
        soundSpin.isEnabled = !running
        statusText.text = if (running) "Running — alarms will fire even with the screen off" else ""
    }

    private fun startAlarms() {
        val am = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Allow \"Alarms & reminders\", then press Start again", Toast.LENGTH_LONG).show()
            return
        }

        val interval = intArrayOf(5, 10, 15, 20, 30, 60)[intervalSpin.selectedItemPosition]
        val start = fromH * 60 + fromM
        val end = toH * 60 + toM
        if (end < start) { statusText.text = "\"To\" must be after \"From\""; return }

        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) +
                now.get(Calendar.SECOND) / 60.0

        val slots = StringBuilder()
        var t = start
        var scheduled = 0
        while (t <= end) {
            val pending = t + 0.5 >= nowMin
            if (pending) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, t / 60)
                    set(Calendar.MINUTE, t % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent(t))
                scheduled++
            }
            if (slots.isNotEmpty()) slots.append(",")
            slots.append("$t:${if (pending) "pending" else "missed"}")
            t += interval
        }

        if (scheduled == 0) {
            statusText.text = "All times in this window have already passed."
            return
        }

        prefs.edit()
            .putString("slots", slots.toString())
            .putString("channel", arrayOf("alarm", "ringtone", "notification")[soundSpin.selectedItemPosition])
            .putInt("fromH", fromH).putInt("fromM", fromM)
            .putInt("toH", toH).putInt("toM", toM)
            .putInt("intervalIdx", intervalSpin.selectedItemPosition)
            .putInt("soundIdx", soundSpin.selectedItemPosition)
            .putBoolean("running", true)
            .apply()

        updateButtonState()
        refreshList()
        Toast.makeText(this, "$scheduled alarms set — you can close the app", Toast.LENGTH_LONG).show()
    }

    private fun stopAlarms() {
        val am = getSystemService(AlarmManager::class.java)
        val slots = prefs.getString("slots", "") ?: ""
        slots.split(",").filter { it.isNotEmpty() }.forEach {
            val minute = it.substringBefore(":").toInt()
            am.cancel(pendingIntent(minute))
        }
        prefs.edit().putBoolean("running", false).apply()
        updateButtonState()
        refreshList()
    }

    private fun pendingIntent(minuteOfDay: Int): PendingIntent {
        val intent = Intent(this, AlarmReceiver::class.java).putExtra("slot", minuteOfDay)
        return PendingIntent.getBroadcast(
            this, minuteOfDay, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun refreshList() {
        val slots = prefs.getString("slots", "") ?: ""
        if (slots.isEmpty()) {
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("No alarms set"))
            return
        }
        val running = prefs.getBoolean("running", false)
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        var done = 0
        val rows = slots.split(",").map { entry ->
            val parts = entry.split(":")
            val minute = parts[0].toInt()
            var state = parts[1]
            // safety net: pending slot more than 3 min past and never delivered
            if (state == "pending" && nowMin > minute + 3) state = "missed"
            if (state == "done") done++
            val label = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)
            when (state) {
                "done" -> "✓  $label   — rang"
                "missed" -> "–  $label   — missed"
                else -> if (running) "○  $label" else "○  $label   (not started)"
            }
        }
        val title = "Schedule   $done / ${slots.split(",").size} rang"
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf(title) + rows)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: String, sound: Uri?) {
            val c = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            c.enableVibration(true)
            c.vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            if (sound != null) {
                c.setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            nm.createNotificationChannel(c)
        }
        channel("alarm", "Alarm sound", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        channel("ringtone", "Ringtone sound", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        channel("notification", "Notification sound", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
    }
}
