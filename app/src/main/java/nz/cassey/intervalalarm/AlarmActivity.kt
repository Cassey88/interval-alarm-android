package nz.cassey.intervalalarm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.util.Locale

class AlarmActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        val slot = intent.getIntExtra("slot", -1)
        findViewById<TextView>(R.id.alarmTime).text =
            if (slot >= 0) String.format(Locale.US, "%02d:%02d", slot / 60, slot % 60) else "--:--"

        findViewById<Button>(R.id.silenceBtn).setOnClickListener {
            startService(
                Intent(this, AlarmForegroundService::class.java)
                    .setAction(AlarmForegroundService.ACTION_SILENCE)
            )
            finish()
        }

        // close by itself shortly after the sound's 60s auto-silence
        handler.postDelayed(autoClose, 65000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoClose)
        super.onDestroy()
    }
}
