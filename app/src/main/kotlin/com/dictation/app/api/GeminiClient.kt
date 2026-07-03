package com.dictation.app.api

import com.dictation.app.data.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Клиент Gemini API. Поддерживает несколько ключей и автоматическую ротацию
 * при ошибках 429 / 503 / RESOURCE_EXHAUSTED.
 */
class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val message: String, val retryable: Boolean) : Result()
    }

    /**
     * Отправляет аудиофайл в Gemini и возвращает распознанный/нормализованный текст.
     * @param mime MIME-тип аудио (например "audio/ogg;codecs=opus")
     * @param audioBytes содержимое файла
     */
    fun transcribe(mime: String, audioBytes: ByteArray): Result {
        val keys = Settings.get().getKeys()
        if (keys.isEmpty()) {
            return Result.Err("Добавь хотя бы один API ключ в настройках", retryable = false)
        }

        var lastErr: Result.Err? = null
        for ((idx, key) in keys.withIndex()) {
            val r = trySend(key, mime, audioBytes)
            when (r) {
                is Result.Ok -> return r
                is Result.Err -> {
                    lastErr = r
                    if (!r.retryable) {
                        // фатальная ошибка — нет смысла пробовать другие ключи
                        return r
                    }
                    // иначе пробуем следующий ключ
                }
            }
        }
        return lastErr ?: Result.Err("Не удалось подключиться ни к одному ключу", retryable = true)
    }

    private fun trySend(key: String, mime: String, audioBytes: ByteArray): Result {
        val url = "$API_BASE/$MODEL:generateContent?key=$key"

        val prompt = buildPrompt()

        val inlineData = JSONObject().apply {
            put("mime_type", mime)
            // base64 без переносов строк
            put("data", android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP))
        }
        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            put(JSONObject().put("inline_data", inlineData))
        }
        val contents = JSONArray().apply {
            put(JSONObject().put("role", "user").put("parts", parts))
        }
        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 4096)
            })

        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val text = extractText(raw)
                    if (text.isBlank()) {
                        Result.Err("Пустой ответ от модели", retryable = true)
                    } else {
                        Result.Ok(text.trim())
                    }
                } else {
                    val msg = extractError(raw) ?: "HTTP ${resp.code}"
                    val retryable = resp.code == 429 || resp.code == 503 || resp.code == 500
                    Result.Err(msg, retryable)
                }
            }
        } catch (e: IOException) {
            Result.Err("Нет связи: ${e.message ?: "неизвестно"}", retryable = true)
        } catch (e: Exception) {
            Result.Err("Ошибка: ${e.message ?: e.javaClass.simpleName}", retryable = false)
        }
    }

    private fun buildPrompt(): String {
        val s = Settings.get()
        val langHint = when (s.getLang()) {
            Settings.Lang.RU -> "Язык оригинала: русский."
            Settings.Lang.EN -> "Language of original: English."
            Settings.Lang.AUTO -> ""
        }
        val prompt = s.getPrompt().trim()
        return buildString {
            append(prompt)
            if (langHint.isNotBlank()) {
                if (this.isNotBlank() && !this.endsWith("\n")) append('\n')
                append(langHint)
            }
        }
    }

    private fun extractText(raw: String): String {
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("candidates") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val content = c.optJSONObject("content") ?: continue
                val parts = content.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    val t = parts.getJSONObject(j).optString("text", "")
                    if (t.isNotBlank()) sb.append(t)
                }
            }
            sb.toString()
        } catch (e: Exception) { "" }
    }

    private fun extractError(raw: String): String? {
        return try {
            val obj = JSONObject(raw)
            val err = obj.optJSONObject("error") ?: return null
            val msg = err.optString("message")
            if (msg.isBlank()) null else msg
        } catch (e: Exception) { null }
    }

    companion object {
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        // Актуальная модель на июнь 2026 (Flash 2.0 отключён 1 июня 2026)
        private const val MODEL = "gemini-3.1-flash-lite"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}