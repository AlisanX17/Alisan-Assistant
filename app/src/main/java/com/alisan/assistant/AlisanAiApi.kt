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
    private const val API_URL = "https://ai.hunterdns.shop/search"

    suspend fun ask(
        message: String,
        history: List<Pair<String, String>> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList()
    ): AiReply = withContext(Dispatchers.IO) {
        val query = message.trim()
        require(query.isNotEmpty()) { "لطفاً یک سؤال یا عبارت برای جست‌وجو بنویس." }

        if (attachments.isNotEmpty()) {
            throw IllegalStateException(
                "نسخه فعلی Alisan AI جست‌وجوی وب را پشتیبانی می‌کند؛ تحلیل فایل و عکس هنوز در Worker فعال نشده."
            )
        }

        val historyArray = JSONArray().apply {
            history.takeLast(12).forEach { (role, content) ->
                if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                    put(JSONObject().put("role", role).put("content", content.take(1800)))
                }
            }
        }

        val payload = JSONObject()
            .put("message", query.take(300))
            .put("history", historyArray)
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = URL(API_URL).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 120000
            connection.doInput = true
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AlisanAI-Android")
            connection.setFixedLengthStreamingMode(body.size)

            connection.outputStream.use { output ->
                output.write(body)
                output.flush()
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                throw IllegalStateException(
                    "پاسخ Worker معتبر نیست (HTTP $status): " +
                        responseText.replace(Regex("\\s+"), " ").take(220)
                )
            }

            if (status !in 200..299) {
                val error = response.optString("error").ifBlank { "خطای HTTP $status" }
                val code = response.optString("code")
                throw IllegalStateException(
                    "$error (HTTP $status${if (code.isNotBlank()) ", $code" else ""})"
                )
            }

            val answer = response.optString("answer").trim()
            if (answer.isBlank()) throw IllegalStateException("پاسخ جست‌وجو خالی بود.")

            val sources = mutableListOf<AiSource>()
            val sourceArray = response.optJSONArray("sources")
            if (sourceArray != null) {
                for (i in 0 until sourceArray.length()) {
                    val item = sourceArray.optJSONObject(i) ?: continue
                    val link = item.optString("url")
                    if (link.startsWith("https://") || link.startsWith("http://")) {
                        sources += AiSource(item.optString("title").ifBlank { link }, link)
                    }
                }
            }

            AiReply(answer, sources)
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException(
                "خطای ارتباط با Alisan AI: ${e.javaClass.simpleName}: ${e.message ?: "اتصال برقرار نشد"}",
                e
            )
        } finally {
            connection.disconnect()
        }
    }
}
