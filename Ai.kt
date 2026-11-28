package com.example.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object Ai {
    private const val API_KEY = "AQ.Ab8RN6InNpacG8AGahe0uVToEy9sJy0hXMalgEX2eZUVcpFk1g"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    suspend fun askGemini(prompt: String): String = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) {
            return@withContext "Питання порожнє."
        }
        if (API_KEY.isBlank() || API_KEY == "YOUR_GEMINI_API_KEY") {
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
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val rawText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                return@withContext "Помилка запиту ($responseCode): $rawText"
            }

            extractText(rawText)
        } catch (e: Exception) {
            "Помилка з'єднання: ${e.localizedMessage ?: "невідома помилка"}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractText(rawJson: String): String {
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return "Порожня відповідь від Gemini."
            if (candidates.length() == 0) return "Немає варіантів відповіді."

            val content = candidates.getJSONObject(0).optJSONObject("content")
                ?: return "Некоректний формат відповіді."
            val parts = content.optJSONArray("parts") ?: return "Немає тексту у відповіді."

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val partText = parts.getJSONObject(i).optString("text", "")
                if (partText.isNotEmpty()) {
                    sb.append(partText)
                }
            }

            val result = sb.toString().trim()
            if (result.isEmpty()) "Порожня відповідь." else result
        } catch (e: Exception) {
            "Помилка парсингу відповіді: ${e.localizedMessage ?: "невідома помилка"}"
        }
    }
}
