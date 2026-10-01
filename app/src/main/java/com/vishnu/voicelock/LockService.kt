package com.vishnu.voicelock

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

class LockService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var fails = 0
    private var busy = false

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_OFF) show() }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("vl", "Voice Lock", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "vl").setContentTitle("Voice Lock active")
            .setSmallIcon(android.R.drawable.ic_lock_lock).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rcv, f)
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.getBooleanExtra("lock", false) == true) show()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(rcv); hide(); super.onDestroy()
    }

    private fun hide() {
        root?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        root = null; busy = false; fails = 0
    }

    private fun show() {
        if (root != null || !Settings.canDrawOverlays(this)) return
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#101010"))
            setPadding(60, 60, 60, 60)
        }
        val title = TextView(this).apply {
            text = "LOCKED"; textSize = 30f; setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        }
        val st = TextView(this).apply {
            text = "Tap the button, then say your word"; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER; setPadding(0, 30, 0, 60)
        }
        val btn = Button(this).apply {
            text = "SPEAK TO UNLOCK"; textSize = 18f; setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#E53935")); cornerRadius = 120f }
            setPadding(90, 45, 90, 45)
        }
        val pinLink = TextView(this).apply {
            text = "Use PIN"; setTextColor(Color.GRAY); setPadding(0, 80, 0, 20); gravity = Gravity.CENTER
        }
        val pin = EditText(this).apply {
            hint = "PIN"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            visibility = View.GONE; gravity = Gravity.CENTER
        }
        val pinOk = Button(this).apply { text = "OK"; visibility = View.GONE }

        pinLink.setOnClickListener { pin.visibility = View.VISIBLE; pinOk.visibility = View.VISIBLE }
        pinOk.setOnClickListener {
            if (Voice.pinOk(this, pin.text.toString())) hide() else st.text = "Wrong PIN"
        }
        btn.setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true; btn.isEnabled = false; st.text = "Listening... say your word now"
            Thread {
                val x = Voice.record()
                val ok = x != null && Voice.verify(this, x)
                ui.post {
                    if (ok) { hide(); return@post }
                    fails++
                    busy = false
                    if (fails >= 3) {
                        st.text = "Too many tries. Wait 30 seconds"
                        ui.postDelayed({ btn.isEnabled = true; st.text = "Try again" }, 30000)
                    } else {
                        st.text = "Voice did not match"; btn.isEnabled = true
                    }
                }
            }.start()
        }

        col.addView(title); col.addView(st); col.addView(btn)
        col.addView(pinLink); col.addView(pin); col.addView(pinOk)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        getSystemService(WindowManager::class.java).addView(col, lp)
        root = col
    }
}
