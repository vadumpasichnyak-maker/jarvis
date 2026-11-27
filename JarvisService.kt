package com.example.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class JarvisService : Service(), TextToSpeech.OnInitListener {

    private var speech: SpeechService? = null
    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val resume = Runnable { resumeListening() }
    private val wake = Regex("(дж[аеи]\\p{L}*|жарв\\p{L}*|чарв\\p{L}*|ярвіс|jarvis)")
    private var conversationUntil = 0L
    private var aiSpoke = false
    private val history = mutableListOf<Pair<String, String>>()

    // Що ти говориш -> частини назв додатка (англ. та укр.)
    private val aliases: Map<String, List<String>> = mapOf(
        "тг" to listOf("telegram"), "телегр" to listOf("telegram"), "телега" to listOf("telegram"),
        "тт" to listOf("tiktok"), "тікт" to listOf("tiktok"), "тік" to listOf("tiktok"),
        "ютуб" to listOf("youtube", "ютуб"), "ютюб" to listOf("youtube"), "юту" to listOf("youtube"),
        "інстаграм" to listOf("instagram"), "інста" to listOf("instagram"),
        "ватсап" to listOf("whatsapp"), "вотсап" to listOf("whatsapp"), "вацап" to listOf("whatsapp"),
        "вайбер" to listOf("viber"), "хром" to listOf("chrome"), "кром" to listOf("chrome"),
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

    private fun findApp(cmd: String): android.content.pm.ResolveInfo? {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val labels = apps.map { it to it.loadLabel(pm).toString().lowercase() }
        val words = normalize(cmd).split(" ").filter { it.isNotBlank() }
        // 1) за списком скорочень та назв
        for (w in words) {
            for ((key, cands) in aliases) {
                val hit = w == key || (key.length >= 4 && w.startsWith(key))
                if (hit) for (c in cands) {
                    labels.firstOrNull { it.second.contains(c) }?.let { return it.first }
                }
            }
        }
        // 2) назва додатка зустрічається в команді
        val full = normalize(cmd)
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
        tts = TextToSpeech(this, this)
        toast("Завантажую мовну модель (перший раз довго)...")
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SPEAK") {
            tts.stop()
            handler.post { handler.removeCallbacks(resume); resumeListening() }
        }
        return START_STICKY
    }

    private fun toast(msg: String) {
        handler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadModel() {
        StorageService.unpack(
            this, "model-uk", "model",
            StorageService.Callback<Model> { model ->
                val rec = Recognizer(model, 16000.0f)
                speech = SpeechService(rec, 16000.0f)
                speech?.startListening(listener)
                toast("Джарвіс слухає")
            },
            StorageService.Callback<java.io.IOException> { e ->
                toast("Помилка моделі: ${e.message}")
            }
        )
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) {
            val text = try { JSONObject(hypothesis ?: "{}").optString("text") } catch (e: Exception) { "" }
            if (text.isNotBlank()) handle(text)
        }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) { toast("Помилка: ${exception?.message}") }
        override fun onTimeout() {}
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { toast("Синтез мовлення не запустився"); return }
        tts.language = Locale("uk", "UA")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "last") {
                    handler.post { handler.removeCallbacks(resume); resumeListening() }
                }
            }
            override fun onError(utteranceId: String?) {
                handler.post { handler.removeCallbacks(resume); resumeListening() }
            }
        })
        say("Системи в нормі, сер. Я весь у вашому розпорядженні.")
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speech?.stop()
        speech?.shutdown()
        tts.shutdown()
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun resumeListening() {
        speech?.setPause(false)
        if (aiSpoke) conversationUntil = SystemClock.elapsedRealtime() + 12000L
    }

    // ---------- Озвучка ----------

    private fun chunk(text: String): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?…])\\s+"))) {
            if (cur.length + sentence.length > 3000) {
                out.add(cur.toString())
                cur = StringBuilder()
            }
            cur.append(sentence).append(' ')
        }
        if (cur.isNotBlank()) out.add(cur.toString())
        if (out.isEmpty()) out.add(text)
        return out
    }

    private fun say(text: String, ai: Boolean = false) {
        handler.post {
            toast("Джарвіс: " + text.take(120))
            aiSpoke = ai
            speech?.setPause(true)
            handler.removeCallbacks(resume)
            handler.postDelayed(resume, 7000L + text.length * 90L)
            val parts = chunk(text)
            for ((i, c) in parts.withIndex()) {
                val id = if (i == parts.size - 1) "last" else "part$i"
                tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    private fun stopSpeaking() {
        handler.post {
            tts.stop()
            handler.removeCallbacks(resume)
            resumeListening()
        }
    }

    // ---------- Обробка команд ----------

    private fun handle(text: String) {
        val t = text.lowercase()
        toast("Почув: $text")
        val m = wake.find(t)
        val now = SystemClock.elapsedRealtime()
        var cmd = ""
        if (m != null) {
            cmd = t.substring(m.range.last + 1).trim(' ', ',', '.', '!')
            if (cmd.isEmpty()) { say("Слухаю, сер.", true); return }
        } else if (now < conversationUntil) {
            cmd = t
        } else {
            // без слова "Джарвіс" приймаємо лише "відкрий <додаток>"
            if ((t.contains("відкр") || t.contains("запуст")) && findApp(t) != null) {
                bg.execute { if (launchApp(t)) say("Звісно, сер.") }
            }
            return
        }
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
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val key = prefs.getString("key", "") ?: ""
        val model = prefs.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5"
        if (key.isNotBlank()) askAi(cmd, key, model) else localFallback(cmd)
    }

    /** Швидкі команди без ШІ. Повертає true, якщо виконано. */
    private fun localSimple(cmd: String): Boolean {
        val a = JarvisAccessibility.instance
        fun need(block: (JarvisAccessibility) -> Unit): Boolean {
            if (a == null) say("Спершу увімкніть мені спеціальні можливості, сер. Я ж не чарівник.")
            else block(a)
            return true
        }
        val short = cmd.length < 28
        return when {
            cmd == "стоп" || cmd.contains("замовкни") || cmd == "досить" -> { stopSpeaking(); true }
            cmd.contains("вимкнись") -> { say("Відходжу в тінь, сер."); handler.postDelayed({ stopSelf() }, 2500); true }
            short && cmd.contains("назад") -> need { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); say("Крок назад, сер.") }
            short && (cmd.contains("додому") || cmd.contains("головн")) -> need { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); say("Вдома, сер.") }
            short && (cmd.contains("нещодавн") || cmd.contains("останні")) -> need { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); say("Ось що ви відкривали.") }
            short && cmd.contains("сповіщен") -> need { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); say("Ваші сповіщення, сер.") }
            short && (cmd.contains("свайп") || cmd.contains("прокрут") || cmd.contains("скрол") || cmd.contains("листай") || cmd.contains("гортай") || cmd.contains("наступн") || cmd.contains("попередн")) -> need {
                val finger = when {
                    cmd.contains("наступн") -> "up"
                    cmd.contains("попередн") -> "down"
                    cmd.contains("вниз") -> "down"
                    cmd.contains("вгору") || cmd.contains("вверх") -> "up"
                    cmd.contains("ліво") -> "left"
                    cmd.contains("право") -> "right"
                    else -> "down"
                }
                // "свайпни" = палець рухається в цей бік; "листай/прокрути" = вміст їде в цей бік
                val isScroll = !cmd.contains("свайп") && !cmd.contains("наступн") && !cmd.contains("попередн")
                val dir = if (isScroll) when (finger) {
                    "down" -> "up"; "up" -> "down"; "left" -> "right"; else -> "left"
                } else finger
                it.swipe(dir)
                say(listOf("Зроблено, сер.", "Як побажаєте.", "Легко.").random())
            }
            else -> false
        }
    }

    // ---------- ШІ ----------

    private fun askAi(cmd: String, key: String, model: String) {
        bg.execute {
            try {
                history.add(Pair("user", cmd))
                while (history.size > 8) history.removeAt(0)
                while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
                val raw = Ai.ask(key, model, history)
                history.add(Pair("assistant", raw))
                val json = Ai.extractJson(raw)
                if (json == null) {
                    say(raw, true)
                    return@execute
                }
                val obj = JSONObject(json)
                val sayText = obj.optString("say")
                val actions = obj.optJSONArray("actions") ?: JSONArray()
                if (sayText.isNotBlank()) say(sayText, actions.length() == 0)
                for (i in 0 until actions.length()) {
                    runAction(actions.getJSONObject(i))
                }
            } catch (e: Exception) {
                toast("ШІ: " + (e.message ?: "помилка"))
                history.clear()
                localFallbackInner(cmd)
            }
        }
    }

    // ---------- Локальний розбір (без ШІ) ----------

    private fun localFallback(cmd: String) {
        bg.execute { localFallbackInner(cmd) }
    }

    private fun localFallbackInner(cmd: String) {
        try {
            when {
                cmd.contains("подзвони") || cmd.contains("зателефону") || cmd.contains("набери") -> {
                    val name = after(cmd, listOf("контакту", "контакт", "подзвони", "зателефонуй", "набери"))
                    say("Дзвоню, сер.")
                    runAction(act("call", "name", name))
                }
                cmd.contains("вимкн") && cmd.contains("телефон") -> {
                    say("Вимикаю, сер. Не сумуйте без мене.")
                    runAction(act("power_off"))
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
                cmd.startsWith("включи") || cmd.startsWith("увімкни") || cmd.startsWith("грай") || cmd.startsWith("постав") -> {
                    val rest = after(cmd, listOf("включи", "увімкни", "грай", "постав", "пісню", "пісня", "трек")).trim()
                    if (rest.isNotEmpty() && findApp(rest) != null && rest.split(" ").size <= 2) {
                        if (launchApp(rest)) say("Звісно, сер.")
                    } else {
                        say("Включаю: $rest")
                        runAction(act("spotify", "query", rest))
                    }
                }
                cmd.contains("відео") && (cmd.contains("відкр") || cmd.contains("знайди") || cmd.contains("включи")) -> {
                    val q = after(cmd, listOf("відео"))
                    say("Шукаю відео, сер.")
                    runAction(act("youtube", "query", q))
                }
                cmd.contains("чат") && !cmd.contains("напиши") -> {
                    val name = after(cmd, listOf("чат з", "чат із", "чат"))
                    say("Відкриваю чат.")
                    runAction(act("telegram_chat", "name", name))
                }
                cmd.startsWith("напиши") -> {
                    val txt = after(cmd, listOf("напиши"))
                    runAction(act("type", "text", txt))
                    say("Написав.")
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
                    else say("Не розумію, сер. Для розмов мені потрібен ключ ШІ в налаштуваннях. Я почув: $cmd")
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
            n.length >= 6 -> n.dropLast(2)
            n.length >= 4 -> n.dropLast(1)
            else -> n
        }
    }

    private fun runAction(a: JSONObject) {
        try {
            val type = a.optString("type")
            val acc = JarvisAccessibility.instance
            when (type) {
                "open_app" -> { if (!launchApp(a.optString("name"))) toast("Не знайшов додаток: " + a.optString("name")) }
                "swipe" -> acc?.swipe(a.optString("dir", "up"))
                "back" -> acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "power_off" -> acc?.powerOff()
                "spotify" -> playSpotify(a.optString("query"))
                "youtube" -> openYoutube(a.optString("query"))
                "telegram_chat" -> telegramChat(a.optString("name"), a.optString("text"))
                "type" -> acc?.typeText(a.optString("text"))
                "send" -> acc?.pressSend()
                "call" -> callContact(a.optString("name"))
                "camera" -> camera(a.optString("mode"))
                "tiktok" -> tiktok(a.optString("mode"))
                "web" -> openWeb(a.optString("query"))
                "stop_speaking" -> stopSpeaking()
                "wait" -> Thread.sleep(a.optLong("ms", 1000L))
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
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        i.setPackage("com.spotify.music")
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        i.putExtra(SearchManager.QUERY, query)
        try {
            startActivity(newTask(i))
        } catch (e: Exception) {
            try {
                startActivity(newTask(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query)))))
            } catch (e2: Exception) {
                toast("Spotify не знайдено")
            }
        }
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
        Thread.sleep(4500)
        JarvisAccessibility.instance?.tapFraction(0.5f, 0.2f)
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

    private fun telegramChat(name: String, text: String) {
        val acc = JarvisAccessibility.instance
        if (acc == null) { say("Потрібні спеціальні можливості, сер."); return }
        if (!launchApp("telegram")) { toast("Telegram не знайдено"); return }
        Thread.sleep(2800)
        acc.clickAnyText(listOf("Пошук", "Search"), true)
        Thread.sleep(1200)
        val s = stem(name)
        acc.typeText(s)
        Thread.sleep(2200)
        val node = acc.findNodes(s).firstOrNull { !it.isEditable }
        if (node == null || !acc.clickNode(node)) acc.tapFraction(0.5f, 0.22f)
        if (text.isNotBlank()) {
            Thread.sleep(2000)
            acc.typeText(text)
            Thread.sleep(700)
            acc.pressSend()
        }
    }

    private fun callContact(name: String) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            say("Дайте мені доступ до контактів і дзвінків, сер. Кнопка 1 в додатку.")
            return
        }
        val q = name.lowercase().trim()
        val q2 = if (q.length >= 4) q.dropLast(1) else q
        var foundName = ""
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
                val ln = n.lowercase()
                if (ln.contains(q) || ln.contains(q2)) {
                    foundName = n
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
            "open" -> { if (!launchApp("tiktok")) toast("TikTok не знайдено") }
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
