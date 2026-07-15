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
    private lateinit var fromBtn: android.view.View
    private lateinit var toBtn: android.view.View
    private lateinit var fromVal: TextView
    private lateinit var toVal: TextView
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
        fromVal = findViewById(R.id.fromVal)
        toVal = findViewById(R.id.toVal)
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
        fromVal.text = String.format(Locale.US, "%02d:%02d", fromH, fromM)
        toVal.text = String.format(Locale.US, "%02d:%02d", toH, toM)
    }

    private fun updateButtonState() {
        val running = prefs.getBoolean("running", false)
        startBtn.text = if (running) "Stop alarms" else "Start alarms"
        startBtn.setBackgroundResource(if (running) R.drawable.btn_danger else R.drawable.btn_primary)
        fromBtn.isEnabled = !running
        toBtn.isEnabled = !running
        fromBtn.alpha = if (running) 0.45f else 1f
        toBtn.alpha = if (running) 0.45f else 1f
        intervalSpin.isEnabled = !running
        soundSpin.isEnabled = !running
        statusText.text = if (running) "Running — alarms fire even with the screen off" else ""
    }

    private data class Row(val mark: String, val time: String, val note: String, val color: Int, val dim: Boolean)

    private inner class SlotAdapter(val rows: List<Row>) :
        ArrayAdapter<Row>(this, R.layout.row_slot, rows) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val v = convertView ?: layoutInflater.inflate(R.layout.row_slot, parent, false)
            val r = rows[position]
            val mark = v.findViewById<TextView>(R.id.rowMark)
            val time = v.findViewById<TextView>(R.id.rowTime)
            val note = v.findViewById<TextView>(R.id.rowNote)
            mark.text = r.mark; mark.setTextColor(r.color)
            time.text = r.time
            time.setTextColor(if (r.dim) getColor(R.color.ms_text_secondary) else getColor(R.color.ms_text))
            time.paintFlags = if (r.dim)
                time.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            else
                time.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            note.text = r.note; note.setTextColor(r.color)
            return v
        }
    }

    private fun refreshList() {
        val slots = prefs.getString("slots", "") ?: ""
        val titleView = findViewById<TextView>(R.id.scheduleTitle)
        if (slots.isEmpty()) {
            titleView.text = "Schedule"
            listView.adapter = SlotAdapter(listOf(Row("", "No alarms set", "", getColor(R.color.ms_text_secondary), false)))
            return
        }
        val running = prefs.getBoolean("running", false)
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        var done = 0
        var nextMarked = false
        val rows = slots.split(",").map { entry ->
            val parts = entry.split(":")
            val minute = parts[0].toInt()
            var state = parts[1]
            if (state == "pending" && nowMin > minute + 3) state = "missed"
            if (state == "done") done++
            val label = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)
            when (state) {
                "done" -> Row("✓", label, "rang", getColor(R.color.ms_green), false)
                "missed" -> Row("–", label, "missed", getColor(R.color.ms_text_secondary), true)
                else -> {
                    val isNext = running && !nextMarked
                    if (isNext) nextMarked = true
                    if (isNext) Row("●", label, "next", getColor(R.color.ms_blue), false)
                    else Row("○", label, if (running) "" else "not started", getColor(R.color.ms_border), false)
                }
            }
        }
        titleView.text = "Schedule   —   $done / ${rows.size} rang"
        listView.adapter = SlotAdapter(rows)
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

        startForegroundService(
            Intent(this, AlarmForegroundService::class.java)
                .setAction(AlarmForegroundService.ACTION_START)
        )
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
        try {
            startService(
                Intent(this, AlarmForegroundService::class.java)
                    .setAction(AlarmForegroundService.ACTION_STOP)
            )
        } catch (_: Exception) { }
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


    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val c = NotificationChannel(
            AlarmForegroundService.CHANNEL_RING, "Ringing alarm",
            NotificationManager.IMPORTANCE_HIGH
        )
        c.setSound(null, null)   // the service plays the looping sound itself
        c.enableVibration(false)
        nm.createNotificationChannel(c)

        // fallback channel: rings the system alarm sound if MIUI blocks the service
        val f = NotificationChannel("fallback", "Alarm (fallback)", NotificationManager.IMPORTANCE_HIGH)
        f.enableVibration(true)
        f.vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
        f.setSound(
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        nm.createNotificationChannel(f)

        // quiet persistent channel for the "alarms running" sentinel
        val s = NotificationChannel(
            AlarmForegroundService.CHANNEL_STATUS, "Alarms running",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(s)
    }
}
