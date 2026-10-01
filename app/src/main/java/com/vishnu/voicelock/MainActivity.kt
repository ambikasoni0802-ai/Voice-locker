package com.vishnu.voicelock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var pin: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
        }.also { col.addView(it) }

        status = TextView(this).apply { textSize = 16f; text = "Steps 1 se 4 follow karo"; setPadding(0, 0, 0, 30) }
        pin = EditText(this).apply {
            hint = "Backup PIN (4-8 digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        col.addView(status)
        btn("1. Permissions do (mic + overlay)") { askPerms() }
        col.addView(pin)
        btn("2. Apni awaaz record karo (5 baar)") { enroll() }
        btn("3. Lock service chalu karo") { startLock(false) }
        btn("4. Abhi lock karo") { startLock(true) }
        btn("Service band karo") { stopService(Intent(this, LockService::class.java)) }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p.add("android.permission.POST_NOTIFICATIONS")
        requestPermissions(p.toTypedArray(), 1)
        if (!Settings.canDrawOverlays(this))
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun enroll() {
        val p = pin.text.toString()
        if (!hasMic()) { status.text = "Pehle step 1 karo"; return }
        if (p.length < 4) { status.text = "Pehle 4-8 digit ka PIN daalo"; return }
        Thread {
            val list = ArrayList<Array<FloatArray>>()
            var i = 1
            while (i <= 5) {
                ui.post { status.text = "Apna word bolo ($i/5) ... abhi!" }
                Thread.sleep(700)
                val f = Voice.record()?.let { Voice.features(it) }
                if (f == null) {
                    ui.post { status.text = "Awaaz saaf nahi aayi, dobara bolo" }
                    Thread.sleep(1200)
                } else { list.add(f); i++; Thread.sleep(600) }
            }
            Voice.save(this, list, p)
            ui.post { status.text = "Voice save ho gayi. Ab step 3 karo" }
        }.start()
    }

    private fun startLock(now: Boolean) {
        if (!Voice.enrolled(this)) { status.text = "Pehle step 2 (voice record) karo"; return }
        if (!hasMic() || !Settings.canDrawOverlays(this)) { status.text = "Pehle step 1 (permissions) karo"; return }
        startForegroundService(Intent(this, LockService::class.java).putExtra("lock", now))
        status.text = if (now) "Locked" else "Service chalu. Screen off hote hi lock lagega"
    }
}
