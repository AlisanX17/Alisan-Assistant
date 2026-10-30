package com.alisan.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

 data class AiSource(val title: String, val url: String)
 data class AiReply(val answer: String, val sources: List<AiSource>)

object AlisanAiApi {
    private const val API_URL = "https://ai.hunterdns.shop/chat"

    suspend fun ask(
        message: String,
        history: List<Pair<String, String>> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList()
    ): AiReply = withContext(Dispatchers.IO) {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 120000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AlisanAI-Android")

            val historyArray = JSONArray()
            history.forEach { (role, content) ->
                if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                    historyArray.put(JSONObject().put("role", role).put("content", content.take(1800)))
                }
            }

            val attachmentArray = JSONArray()
            attachments.forEach { file ->
                attachmentArray.put(
                    JSONObject()
                        .put("name", file.name)
                        .put("mimeType", file.mimeType)
                        .put("data", file.base64)
                )
            }

            val payload = JSONObject()
                .put("message", message.take(8000))
                .put("history", historyArray)
                .put("attachments", attachmentArray)

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                val excerpt = responseText.replace(Regex("\\s+"), " ").take(220)
                throw IllegalStateException("سرور پاسخ JSON معتبر نداد (HTTP $status): ${excerpt.ifBlank { "پاسخ خالی" }}")
            }

            if (status !in 200..299) {
                val error = response.optString("error").ifBlank { "HTTP $status" }
                val code = response.optString("code").takeIf { it.isNotBlank() }
                throw IllegalStateException(if (code != null) "$error (کد: $code، HTTP $status)" else "$error (HTTP $status)")
            }

            val answer = response.optString("answer").trim()
            if (answer.isBlank()) throw IllegalStateException("پاسخ هوش مصنوعی خالی بود.")

            val sources = mutableListOf<AiSource>()
            val array = response.optJSONArray("sources")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val link = item.optString("url")
                    if (link.startsWith("https://") || link.startsWith("http://")) {
                        sources.add(AiSource(item.optString("title").ifBlank { link }, link))
                    }
                }
            }
            AiReply(answer, sources)
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException("خطای شبکه: ${e.javaClass.simpleName}: ${e.message ?: "اتصال برقرار نشد"}", e)
        } finally {
            connection.disconnect()
        }
    }
}
