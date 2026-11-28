package com.example.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object Ai {
    // Встав сюди свій ключ Gemini API або передавай його з BuildConfig / налаштувань
    private const val API_KEY = "YOUR_GEMINI_API_KEY"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$API_KEY"

    suspend fun askGemini(prompt: String): String = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) return@withContext "Питання порожнє."
        if (API_KEY == "YOUR_GEMINI_API_KEY" || API_KEY.isBlank()) {
            return@withContext "Не вказано API-ключ для Gemini."
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(ENDPOINT)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val rawResponse = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val errorText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                return@withContext "Помилка запиту до Gemini ($responseCode): ${errorText ?: "невідома помилка"}"
            }

            extractTextFromResponse(rawResponse)
        } catch (e: Exception) {
            "Помилка з'єднання з Gemini: ${e.localizedMessage ?: "невідома помилка"}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractTextFromResponse(jsonString: String): String {
        return try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates")
                ?: return "Не вдалося отримати відповідь від Gemini."
            if (candidates.length() == 0) return "Gemini не повернув варіантів відповіді."

            val content = candidates.getJSONObject(0).optJSONObject("content")
                ?: return "Порожній контент у відповіді."
            val parts = content.optJSONArray("parts")
                ?: return "Відповідь не містить тексту."

            val builder = StringBuilder()
            for (i in 0 until parts.length()) {
                val text = parts.getJSONObject(i).optString("text", "")
                if (text.isNotEmpty()) {
                    builder.append(text)
                }
            }

            val result = builder.toString().trim()
            if (result.isEmpty()) "Отримано порожню відповідь." else result
        } catch (e: Exception) {
            "Помилка читання відповіді Gemini: ${e.localizedMessage ?: "невідома помилка"}"
        }
    }
}
