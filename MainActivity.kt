package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
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
                    Manifest.permission.CALL_PHONE
                ), 1
            )
        }
        btn("2. Дозволити поверх інших вікон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("3. Увімкнути спеціальні можливості (Jarvis)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        root.addView(TextView(this).apply {
            text = "Ключ Claude API (для розмови, казок, розумних команд). Можна лишити порожнім."
            setPadding(0, 40, 0, 0)
        })
        val keyField = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        root.addView(keyField)
        val modelField = EditText(this).apply {
            hint = "модель"
            setText(prefs.getString("model", "claude-haiku-5-5"))
        }
        root.addView(modelField)
        btn("Зберегти ключ") {
            prefs.edit()
                .putString("key", keyField.text.toString().trim())
                .putString("model", modelField.text.toString().trim())
                .apply()
            Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show()
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
