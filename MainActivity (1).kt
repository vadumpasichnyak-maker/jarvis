package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        fun btn(text: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { this.text = text; setOnClickListener { onClick() } })
        }
        btn("1. Дозволи: мікрофон, сповіщення, контакти, дзвінки") {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.BLUETOOTH_CONNECT
                ), 1
            )
        }
        btn("2. Дозволити поверх інших вікон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("3. Увімкнути спеціальні можливості (Jarvis)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("4. Запустити Джарвіса") {
            startForegroundService(Intent(this, JarvisService::class.java))
        }
        btn("Зупинити") { stopService(Intent(this, JarvisService::class.java)) }

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
