package com.example.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class JarvisService : Service(), TextToSpeech.OnInitListener {

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var resetRequested = false
    @Volatile private var useHeadsetMic = true
    @Volatile private var torchOn = false
    private var audioThread: Thread? = null
    private val effects = mutableListOf<AudioEffect>()
    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val resume = Runnable { setPaused(false) }
    private val wake = Regex("(дж[аеи]\\p{L}*|[дчжшхг]арв\\p{L}*|ярв\\p{L}*|дервіс|jarvis)")

    // Що ти говориш -> частини назв додатка (англ. та укр.)
    private val aliases: Map<String, List<String>> = mapOf(
        "тг" to listOf("telegram"), "телегр" to listOf("telegram"), "телега" to listOf("telegram"),
        "тт" to listOf("tiktok"), "тікт" to listOf("tiktok"), "тік" to listOf("tiktok"),
        "ютуб" to listOf("youtube", "ютуб"), "ютюб" to listOf("youtube"), "юту" to listOf("youtube"),
        "інстаграм" to listOf("instagram"), "інста" to listOf("instagram"),
        "ватсап" to listOf("whatsapp"), "вотсап" to listOf("whatsapp"), "вацап" to listOf("whatsapp"),
        "вайбер" to listOf("viber"), "хром" to listOf("chrome"), "кром" to listOf("chrome"),
        "хроми" to listOf("chrome"), "хрому" to listOf("chrome"), "гром" to listOf("chrome"),
        "спотіфай" to listOf("spotify"), "спотифай" to listOf("spotify"), "спотік" to listOf("spotify"),
        "нетфлікс" to listOf("netflix"), "дискорд" to listOf("discord"),
        "гугл" to listOf("google"), "пошта" to listOf("gmail", "пошта"), "джимейл" to listOf("gmail"),
        "монобанк" to listOf("mono"), "моно" to listOf("mono"),
        "снепчат" to listOf("snapchat"), "фейсбук" to listOf("facebook"),
        "камер" to listOf("camera", "камера"), "налаштув" to listOf("settings", "налаштування"),
        "карти" to listOf("maps", "карти"), "телефон" to listOf("phone", "телефон"),
        "фото" to listOf("photos", "фото"),
        "дуолінго" to listOf("duolingo"), "тему" to listOf("temu"), "бамп" to listOf("bump")
    )

    private fun normalize(s: String): String = s.lowercase()
        .replace("те ге", "тг").replace("те те", "тт")
        .replace("тік ток", "тікток").replace("тік-ток", "тікток")
        .replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Відстань між словами (скільки букв відрізняється) - щоб прощати помилки розпізнавання. */
    private fun lev(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + c)
            }
        }
        return dp[a.length][b.length]
    }

    private fun findApp(cmd: String): android.content.pm.ResolveInfo? {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val labels = apps.map { it to it.loadLabel(pm).toString().lowercase() }
        val full = normalize(cmd)
        val words = full.split(" ").filter { it.isNotBlank() }
        // 1) за списком скорочень та назв (з допуском на помилки розпізнавання)
        for (w in words) {
            for ((key, cands) in aliases) {
                val limit = if (key.length >= 7) 2 else if (key.length >= 4) 1 else 0
                val hit = w == key || (key.length >= 4 && w.startsWith(key)) || (limit > 0 && lev(w, key) <= limit)
                if (hit) for (c in cands) {
                    labels.firstOrNull { it.second.contains(c) }?.let { return it.first }
                }
            }
        }
        // 2) назва додатка зустрічається в команді
        labels.firstOrNull { it.second.length >= 3 && full.contains(it.second) }?.let { return it.first }
        // 3) слово з команди - початок назви додатка
        for (w in words) {
            if (w.length >= 4) labels.firstOrNull { it.second.startsWith(w) }?.let { return it.first }
        }
        return null
    }

    private fun launchApp(spoken: String): Boolean {
        val hit = findApp(spoken) ?: return false
        val launch = packageManager.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return true
    }

    // ---------- Життєвий цикл ----------

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, JarvisService::class.java).setAction("STOP_SPEAK"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            "Стоп", stopPi
        ).build()
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Джарвіс слухає")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(stopAction)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        useHeadsetMic = getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("headset_mic", true)
        try {
            val cm = getSystemService(CameraManager::class.java)
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, handler)
        } catch (e: Exception) {
        }
        tts = TextToSpeech(this, this)
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SPEAK") {
            tts.stop()
            handler.post { handler.removeCallbacks(resume); setPaused(false) }
        }
        return START_STICKY
    }

    private fun toast(msg: String) {
        handler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Розпаковка моделі йде у фоні (Vosk), а створення розпізнавача - в окремому потоці. */
    private fun loadModel() {
        toast("Завантажую мовну модель (перший раз до хвилини)...")
        StorageService.unpack(
            this, "model-uk", "model",
            StorageService.Callback<Model> { model ->
                modelExecutor.execute {
                    try {
                        val rec = Recognizer(model, 16000.0f)
                        startAudio(rec)
                        toast("Джарвіс слухає")
                    } catch (e: Throwable) {
                        android.util.Log.e("JarvisService", "Recognizer init failed", e)
                        toast("Помилка розпізнавача: " + (e.message ?: ""))
                    }
                }
            },
            StorageService.Callback<java.io.IOException> { e ->
                toast("Помилка моделі: " + (e.message ?: ""))
            }
        )
    }

    // ---------- Запис звуку (мікрофон телефону або навушників, з придушенням відлуння) ----------

    private fun onVoskResult(json: String) {
        val text = try { JSONObject(json).optString("text") } catch (e: Exception) { "" }
        if (text.isNotBlank()) handle(text)
    }

    private fun setPaused(p: Boolean) {
        paused = p
        if (!p) resetRequested = true
    }

    private fun startAudio(rec: Recognizer) {
        if (running) return
        running = true
        val t = Thread { audioLoop(rec) }
        t.name = "jarvis-audio"
        audioThread = t
        t.start()
    }

    private fun pickInputDevice(am: AudioManager): AudioDeviceInfo? {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val order = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET
        )
        for (t in order) {
            val d = inputs.firstOrNull { it.type == t }
            if (d != null) return d
        }
        return null
    }

    private fun isBluetoothType(t: Int): Boolean =
        t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun applyCommunicationDevice(am: AudioManager, wantBt: Boolean) {
        try {
            if (wantBt) {
                val d = am.availableCommunicationDevices.firstOrNull { isBluetoothType(it.type) }
                if (d != null) am.setCommunicationDevice(d)
            } else {
                am.clearCommunicationDevice()
            }
        } catch (e: Exception) {
        }
    }

    private fun attachEffects(sid: Int) {
        for (e in effects) {
            try { e.release() } catch (x: Exception) { }
        }
        effects.clear()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                val e = AcousticEchoCanceler.create(sid)
                if (e != null) { e.setEnabled(true); effects.add(e) }
            }
        } catch (x: Exception) { }
        try {
            if (NoiseSuppressor.isAvailable()) {
                val e = NoiseSuppressor.create(sid)
                if (e != null) { e.setEnabled(true); effects.add(e) }
            }
        } catch (x: Exception) { }
        try {
            if (AutomaticGainControl.isAvailable()) {
                val e = AutomaticGainControl.create(sid)
                if (e != null) { e.setEnabled(true); effects.add(e) }
            }
        } catch (x: Exception) { }
    }

    private fun createRecorder(rate: Int, dev: AudioDeviceInfo?): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = AudioRecord.Builder()
                // VOICE_COMMUNICATION вмикає придушення відлуння (щоб не чути відео з динаміка)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, rate * 2))
                .build()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                null
            } else {
                if (dev != null) ar.setPreferredDevice(dev)
                attachEffects(ar.audioSessionId)
                ar
            }
        } catch (e: Throwable) {
            android.util.Log.e("JarvisService", "AudioRecord failed", e)
            null
        }
    }

    private fun audioLoop(rec: Recognizer) {
        val rate = 16000
        val am = getSystemService(AudioManager::class.java)
        val buf = ShortArray(3200)
        var ar: AudioRecord? = null
        var currentKey = -2
        try {
            while (running) {
                val dev = if (useHeadsetMic) pickInputDevice(am) else null
                val key = dev?.id ?: -1
                if (ar == null || key != currentKey) {
                    try { ar?.stop() } catch (e: Exception) { }
                    ar?.release()
                    ar = null
                    applyCommunicationDevice(am, dev != null && isBluetoothType(dev.type))
                    val created = createRecorder(rate, dev)
                    if (created == null) { Thread.sleep(1500); continue }
                    created.startRecording()
                    ar = created
                    currentKey = key
                    if (dev != null) toast("Мікрофон: " + dev.productName)
                }
                val r = ar ?: continue
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(40); continue }
                if (paused) continue
                if (resetRequested) { rec.reset(); resetRequested = false }
                if (rec.acceptWaveForm(buf, n)) {
                    val json = rec.result
                    handler.post { onVoskResult(json) }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("JarvisService", "Audio loop crashed", e)
            toast("Помилка запису звуку: " + (e.message ?: ""))
        } finally {
            try { ar?.stop() } catch (e: Exception) { }
            ar?.release()
            for (e in effects) {
                try { e.release() } catch (x: Exception) { }
            }
            effects.clear()
            applyCommunicationDevice(am, false)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { toast("Синтез мовлення не запустився"); return }
        tts.language = Locale("uk", "UA")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { handler.removeCallbacks(resume); setPaused(false) }
            }
            override fun onError(utteranceId: String?) {
                handler.post { handler.removeCallbacks(resume); setPaused(false) }
            }
        })
        say("Системи в нормі, сер. Я весь у вашому розпорядженні.")
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        tts.shutdown()
        bg.shutdownNow()
        modelExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------- Озвучка ----------

    private fun say(text: String) {
        handler.post {
            toast("Джарвіс: " + text.take(120))
            setPaused(true)
            handler.removeCallbacks(resume)
            handler.postDelayed(resume, 5000L + text.length * 90L)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
    }

    private fun stopSpeaking() {
        handler.post {
            tts.stop()
            handler.removeCallbacks(resume)
            setPaused(false)
        }
    }

    // ---------- Обробка команд ----------

    private fun handle(text: String) {
        val t = text.lowercase()
        toast("Почув: $text")
        val m = wake.find(t)
        val tTrigger = "дай"
        val dateTrigger = "дає"

        val cleanT = t.replace(",", "").replace(":", "").trim().lowercase()

        if (cleanT.contains(tTrigger) || cleanT.contains(dateTrigger)) {
        val delimiter = if (cleanT.contains(tTrigger)) "дай" else "дає"
        val question = text.trim()
        .substringAfter(delimiter, "")
        .trim()
        .removePrefix("відповідь на питання")
        .removePrefix("відповідь")
        .trim()

    if (question.isNotEmpty()) {
        say("Думаю...")
        bg.execute {
            val answer = kotlinx.coroutines.runBlocking { Ai.askGemini(question) }
            say(answer)
        }
    }
    return
}

        }
        val cmd = t.substring(m.range.last + 1).trim(' ', ',', '.', '!')
        if (cmd.isEmpty()) { say("Слухаю, сер."); return }
        process(cmd)
    }

    private fun after(cmd: String, markers: List<String>): String {
        var end = -1
        for (m in markers) {
            val i = cmd.lastIndexOf(m)
            if (i >= 0) end = maxOf(end, i + m.length)
        }
        return if (end < 0) cmd else cmd.substring(end).trim()
    }

    private fun act(type: String, key: String = "", value: String = ""): JSONObject {
        val o = JSONObject().put("type", type)
        if (key.isNotEmpty()) o.put(key, value)
        return o
    }

    private fun process(cmd0: String) {
        val cmd = normalize(cmd0)
        if (localSimple(cmd)) return
        bg.execute { localFallbackInner(cmd) }
    }

    /** Швидкі команди (виконуються одразу). Повертає true, якщо виконано. */
    private fun localSimple(cmd: String): Boolean {
        val a = JarvisAccessibility.instance
        fun need(block: (JarvisAccessibility) -> Unit): Boolean {
            if (a == null) say("Спершу увімкніть мені спеціальні можливості, сер. Я ж не чарівник.")
            else block(a)
            return true
        }
        val short = cmd.length < 28
        val hasDir = cmd.contains("вниз") || cmd.contains("вгору") || cmd.contains("вверх") ||
            cmd.contains("ліво") || cmd.contains("право") || cmd.contains("донизу") || cmd.contains("нагору")
        val bareDir = hasDir && cmd.split(" ").size <= 3
        return when {
            cmd == "стоп" || cmd.contains("замовкни") || cmd == "досить" -> { stopSpeaking(); true }
            cmd.contains("вимкнись") -> { say("Відходжу в тінь, сер."); handler.postDelayed({ stopSelf() }, 2500); true }
            short && cmd.contains("назад") -> need { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); say("Крок назад, сер.") }
            short && (cmd.contains("додому") || cmd.contains("головн")) -> need { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); say("Вдома, сер.") }
            short && (cmd.contains("нещодавн") || cmd.contains("останні")) -> need { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); say("Ось що ви відкривали.") }
            short && cmd.contains("сповіщен") -> need { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); say("Ваші сповіщення, сер.") }
            short && (cmd.contains("свайп") || cmd.contains("прокрут") || cmd.contains("скрол") || cmd.contains("листай") ||
                cmd.contains("гортай") || cmd.contains("наступн") || cmd.contains("попередн") || bareDir) -> need {
                val finger = when {
                    cmd.contains("наступн") -> "up"
                    cmd.contains("попередн") -> "down"
                    cmd.contains("вниз") || cmd.contains("донизу") -> "down"
                    cmd.contains("вгору") || cmd.contains("вверх") || cmd.contains("нагору") -> "up"
                    cmd.contains("ліво") -> "left"
                    cmd.contains("право") -> "right"
                    else -> "down"
                }
                // "свайпни" = палець рухається в цей бік; просто "вниз"/"листай" = сторінка їде в цей бік
                val isScroll = !cmd.contains("свайп") && !cmd.contains("наступн") && !cmd.contains("попередн")
                val dir = if (isScroll) when (finger) {
                    "down" -> "up"; "up" -> "down"; "left" -> "right"; else -> "left"
                } else finger
                it.swipe(dir)
            }
            else -> false
        }
    }

    // ---------- Решта команд ----------

    private val prepositions = listOf("з", "із", "зі", "до", "в", "у", "на", "для", "по")

    private fun localFallbackInner(cmd: String) {
        try {
            val words = cmd.split(" ").filter { it.isNotBlank() }
            val tgIdx = words.indexOfFirst {
                it == "тг" || it.startsWith("телегр") || it.startsWith("телега") || it == "чат" || it == "чату"
            }
            val torchWord = words.any { it.startsWith("ліхтар") || it.startsWith("фонар") || lev(it, "ліхтарик") <= 2 }
            val btWord = words.any { it.startsWith("блю") || it.startsWith("bluetooth") || lev(it, "блютуз") <= 2 } ||
                cmd.contains("синій зуб") || cmd.contains("синього зуба")
            val offWord = cmd.contains("вимкн") || cmd.contains("виключ") || cmd.contains("погас") ||
                cmd.contains("згас") || cmd.contains("загас")
            val onWord = cmd.contains("увімкн") || cmd.contains("ввімкн") || cmd.contains("включ") || cmd.contains("запал")
            val desired: Boolean? = if (offWord) false else if (onWord) true else null
            when {
                torchWord -> setTorch(desired)
                btWord -> {
                    val acc = JarvisAccessibility.instance
                    if (acc == null) say("Потрібні спеціальні можливості, сер.")
                    else {
                        val r = acc.setBluetooth(desired)
                        say(when (r) { "on" -> "Блютуз увімкнено, сер."; "off" -> "Блютуз вимкнено, сер."; else -> "Не знайшов блютуз у швидких налаштуваннях, сер." })
                    }
                }
                cmd.contains("мікрофон") && cmd.contains("навушн") -> {
                    useHeadsetMic = true
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("headset_mic", true).apply()
                    say("Слухатиму через навушники, сер.")
                }
                cmd.contains("мікрофон") && (cmd.contains("телефон") || cmd.contains("динамік")) -> {
                    useHeadsetMic = false
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("headset_mic", false).apply()
                    say("Слухатиму через мікрофон телефону, сер.")
                }
                cmd.contains("подзвони") || cmd.contains("зателефону") || cmd.contains("набери") -> {
                    val name = after(cmd, listOf("контакту", "контакт", "подзвони", "зателефонуй", "набери"))
                    say("Дзвоню, сер.")
                    runAction(act("call", "name", name))
                }
                cmd.contains("зупини запис") || cmd.contains("стоп запис") || cmd.contains("закінчи запис") -> {
                    say("Зупиняю запис.")
                    runAction(if (cmd.contains("тік")) act("tiktok", "mode", "record_stop") else act("camera", "mode", "video_stop"))
                }
                (cmd.contains("зніми") || cmd.contains("запиши") || cmd.contains("почни запис")) && cmd.contains("відео") -> {
                    say("Знімаю, сер.")
                    runAction(if (cmd.contains("тік")) act("tiktok", "mode", "record_start") else act("camera", "mode", "video_start"))
                }
                cmd.contains("сфот") || cmd.contains("зроби фото") || cmd.contains("зробити фото") -> {
                    say("Усміхніться, сер.")
                    runAction(act("camera", "mode", "photo"))
                }
                cmd.contains("відео") && (cmd.contains("відкр") || cmd.contains("знайди") || cmd.contains("включи") ||
                    cmd.contains("увімкни") || cmd.contains("покажи") || cmd.contains("пошукай")) -> {
                    val q = after(cmd, listOf("відео"))
                    say("Шукаю відео, сер.")
                    runAction(act("youtube", "query", q))
                }
                cmd.startsWith("включи") || cmd.startsWith("увімкни") || cmd.startsWith("грай") || cmd.startsWith("постав") -> {
                    val rest = after(cmd, listOf("включи", "увімкни", "грай", "постав", "пісню", "пісня", "трек")).trim()
                    if (rest.isNotEmpty() && findApp(rest) != null && rest.split(" ").size <= 2) {
                        if (launchApp(rest)) say("Звісно, сер.")
                    } else {
                        say("Включаю: $rest")
                        runAction(act("spotify", "query", rest))
                    }
                }
                tgIdx >= 0 -> {
                    val name = words.drop(tgIdx + 1).filter { it !in prepositions }.joinToString(" ")
                    if (name.isBlank()) {
                        if (launchApp("telegram")) say("Звісно, сер.")
                    } else {
                        say("Відкриваю чат.")
                        runAction(act("telegram_chat", "name", name))
                    }
                }
                cmd.startsWith("напиши") -> {
                    val txt = after(cmd, listOf("напиши"))
                    val ok = JarvisAccessibility.instance?.typeText(txt) ?: false
                    say(if (ok) "Написав." else "Не бачу поля вводу, сер. Спершу натисніть на нього.")
                }
                cmd.startsWith("відправ") || cmd.startsWith("надішли") -> {
                    runAction(act("send"))
                    say("Надіслано.")
                }
                cmd.contains("сайт") || cmd.contains("загугли") || cmd.startsWith("знайди") || cmd.contains("в гуглі") -> {
                    val q = after(cmd, listOf("сайт", "загугли", "знайди", "в гуглі"))
                    say("Одну секунду, сер.")
                    runAction(act("web", "query", q))
                }
                else -> {
                    if (launchApp(cmd)) say(listOf("Звісно, сер.", "Вже відкриваю.", "Як завжди, до ваших послуг.").random())
                    else say("Не розумію, сер. Я почув: $cmd")
                }
            }
        } catch (e: Exception) {
            toast("Помилка: " + (e.message ?: ""))
        }
    }

    // ---------- Виконання дій ----------

    private fun stem(name: String): String {
        val n = name.lowercase().trim()
        return when {
            n.length >= 5 -> n.dropLast(2)
            n.length == 4 -> n.dropLast(1)
            else -> n
        }
    }

    private fun setTorch(desired: Boolean?) {
        try {
            val cm = getSystemService(CameraManager::class.java)
            var id: String? = null
            for (cid in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(cid)
                val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                if (flash && facing == CameraCharacteristics.LENS_FACING_BACK) { id = cid; break }
            }
            if (id == null) { say("Ліхтарика не знайдено, сер."); return }
            val on = desired ?: !torchOn
            cm.setTorchMode(id, on)
            torchOn = on
            say(if (on) "Хай буде світло, сер." else "Світло вимкнено, сер.")
        } catch (e: Exception) {
            toast("Ліхтарик: " + (e.message ?: ""))
        }
    }

    private fun runAction(a: JSONObject) {
        try {
            val type = a.optString("type")
            when (type) {
                "spotify" -> playSpotify(a.optString("query"))
                "youtube" -> openYoutube(a.optString("query"))
                "telegram_chat" -> telegramChat(a.optString("name"))
                "send" -> JarvisAccessibility.instance?.pressSend()
                "call" -> callContact(a.optString("name"))
                "camera" -> camera(a.optString("mode"))
                "tiktok" -> tiktok(a.optString("mode"))
                "web" -> openWeb(a.optString("query"))
                else -> toast("Невідома дія: $type")
            }
        } catch (e: Exception) {
            toast("Помилка дії: " + (e.message ?: ""))
        }
    }

    private fun newTask(i: Intent): Intent {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
    }

    private fun playSpotify(query: String) {
        val acc = JarvisAccessibility.instance
        val uri = "spotify:search:" + Uri.encode(query)
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            i.setPackage("com.spotify.music")
            startActivity(newTask(i))
        } catch (e: Exception) {
            toast("Spotify не знайдено")
            return
        }
        if (acc == null) return
        Thread.sleep(5000)
        // верхній результат пошуку
        acc.tapFraction(0.5f, 0.30f)
        Thread.sleep(2500)
        // якщо відкрилась сторінка альбому/виконавця - натискаємо "Відтворити"
        acc.clickAnyText(listOf("Відтворити", "Play"), true)
    }

    private fun openYoutube(query: String) {
        val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.setPackage("com.google.android.youtube")
        try {
            startActivity(newTask(i))
        } catch (e: Exception) {
            i.setPackage(null)
            startActivity(newTask(i))
        }
        Thread.sleep(5000)
        JarvisAccessibility.instance?.tapFraction(0.5f, 0.22f)
    }

    private fun openWeb(query: String) {
        val q = query.trim()
        val isUrl = Regex("^(https?://)?[\\p{L}0-9.-]+\\.[a-z]{2,}(/.*)?$").matches(q)
        val url = if (isUrl) {
            if (q.startsWith("http")) q else "https://$q"
        } else {
            "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        }
        startActivity(newTask(Intent(Intent.ACTION_VIEW, Uri.parse(url))))
    }

    private fun telegramChat(name: String) {
        val acc = JarvisAccessibility.instance
        if (acc == null) { say("Потрібні спеціальні можливості, сер."); return }
        if (!launchApp("telegram")) { toast("Telegram не знайдено"); return }
        Thread.sleep(2800)
        // повертаємось на список чатів, якщо Telegram відкрився всередині іншого чату
        if (!acc.clickAnyText(listOf("Пошук", "Search", "Поиск"), true)) {
            acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(800)
            if (!acc.clickAnyText(listOf("Пошук", "Search", "Поиск"), true)) acc.tapFraction(0.86f, 0.075f)
        }
        Thread.sleep(1200)
        val s = stem(name)
        acc.typeText(s)
        Thread.sleep(2300)
        val node = acc.findNodes(s).firstOrNull { !it.isEditable }
        if (node == null || !acc.clickNode(node)) acc.tapFraction(0.5f, 0.22f)
    }

    private fun callContact(name: String) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            say("Дайте мені доступ до контактів і дзвінків, сер. Кнопка 1 в додатку.")
            return
        }
        val q = stem(name)
        var foundNumber = ""
        val c = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ), null, null, null
        )
        c?.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                if (n.lowercase().contains(q)) {
                    foundNumber = num
                    break
                }
            }
        }
        if (foundNumber.isEmpty()) {
            say("Контакт «$name» не знайдено, сер.")
            return
        }
        startActivity(newTask(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(foundNumber)))))
    }

    private fun camera(mode: String) {
        val acc = JarvisAccessibility.instance
        when (mode) {
            "photo" -> {
                startActivity(newTask(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
                Thread.sleep(2200)
                acc?.clickShutter()
            }
            "video_start" -> {
                startActivity(newTask(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)))
                Thread.sleep(2200)
                acc?.clickShutter()
            }
            "video_stop" -> acc?.clickShutter()
        }
    }

    private fun tiktok(mode: String) {
        val acc = JarvisAccessibility.instance
        when (mode) {
            "record_start" -> {
                if (!launchApp("tiktok")) { toast("TikTok не знайдено"); return }
                Thread.sleep(3500)
                acc?.tapFraction(0.5f, 0.95f)
                Thread.sleep(2800)
                acc?.tapFraction(0.5f, 0.87f)
            }
            "record_stop" -> acc?.tapFraction(0.5f, 0.87f)
        }
    }
}
