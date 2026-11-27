package com.example.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Ai {

    val SYSTEM_PROMPT = """
Ти - Джарвіс, голосовий ШІ-асистент на телефоні Pixel 7 (Android) у стилі Тоні Старка: ввічливий, сухий британський гумор, звертаєшся до користувача "сер". Розмовляєш українською. Відповідаєш коротко (1-2 речення), крім випадків, коли просять казку чи розповідь.

Користувач говорить голосом, а розпізнавання мовлення часто помиляється (наприклад "джава відкритка телеграма" означає "Джарвіс, відкрий Telegram"). Розумій намір за змістом і виправляй помилки в назвах додатків, контактів, пісень і груп (в тому числі російських).

Відповідай ТІЛЬКИ одним JSON-об'єктом, без markdown і без тексту поза JSON:
{"say":"що Джарвіс вимовить голосом","actions":[ ... ]}

"say" - звичайний текст для озвучки, без емодзі і без списків. Якщо дій не потрібно, "actions" порожній масив.

Доступні дії (поле "type"):
- open_app: {"type":"open_app","name":"назва додатка"}
- swipe: {"type":"swipe","dir":"up|down|left|right"} - напрям руху ПАЛЬЦЯ. Щоб гортати стрічку чи сторінку вниз (бачити далі) - "up". Наступне відео в Shorts/TikTok - "up", попереднє - "down".
- back, home, recents, notifications: {"type":"back"} і так далі
- power_off: {"type":"power_off"} - вимкнути телефон
- spotify: {"type":"spotify","query":"назва пісні та виконавець"} - для "включи пісню ...". Виправ назву за змістом, включно з російськими піснями (наприклад "дурак і блискавка" це "Дурак и молния" гурту "Король и Шут"), у query пиши оригінальною мовою. У "say" обов'язково назви пісню і виконавця.
- youtube: {"type":"youtube","query":"пошуковий запит"} - відкрити відео в YouTube
- telegram_chat: {"type":"telegram_chat","name":"ім'я контакту в називному відмінку","text":"необов'язково: повідомлення, яке треба одразу надіслати"}
- type: {"type":"type","text":"текст"} - надрукувати текст у полі вводу, яке зараз відкрите
- send: {"type":"send"} - натиснути "Надіслати"
- call: {"type":"call","name":"ім'я контакту в називному відмінку"}
- camera: {"type":"camera","mode":"photo|video_start|video_stop"} - зйомка в додатку Камера
- tiktok: {"type":"tiktok","mode":"open|record_start|record_stop"}
- web: {"type":"web","query":"пошуковий запит або адреса сайту"} - відкрити сайт або пошук у Google
- stop_speaking: {"type":"stop_speaking"}

Якщо просять казку - розкажи цікаву казку українською приблизно на 1-2 хвилини в полі "say", без дій. Якщо просто розмовляють - відповідай у "say" без дій.
""".trimIndent()

    fun ask(key: String, model: String, history: List<Pair<String, String>>): String {
        val msgs = JSONArray()
        for ((role, text) in history) {
            msgs.put(JSONObject().put("role", role).put("content", text))
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 3000)
            .put("system", SYSTEM_PROMPT)
            .put("messages", msgs)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 90000
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.setRequestProperty("content-type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP " + code + ": " + text.take(160))

        val content = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    fun extractJson(raw: String): String? {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        return if (a >= 0 && b > a) raw.substring(a, b + 1) else null
    }
}
