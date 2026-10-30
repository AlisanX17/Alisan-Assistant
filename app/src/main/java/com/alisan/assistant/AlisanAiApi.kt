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

        val payload = JSONObject()
            .put("message", message.take(8000))
            .put("history", JSONArray().apply {
                history.forEach { (role, content) ->
                    if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                        put(
                            JSONObject()
                                .put("role", role)
                                .put("content", content.take(1800))
                        )
                    }
                }
            })
            .put("attachments", JSONArray().apply {
                attachments.forEach { file ->
                    put(
                        JSONObject()
                            .put("name", file.name)
                            .put("mimeType", file.mimeType)
                            .put("data", file.base64)
                    )
                }
            })

        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(API_URL).openConnection() as HttpURLConnection)

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.doInput = true
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 AlisanAI")

            connection.setFixedLengthStreamingMode(body.size)

            connection.outputStream.use { output ->
                output.write(body)
                output.flush()
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            val response = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                throw IllegalStateException(
                    "پاسخ نامعتبر از سرور (HTTP $status): " +
                        responseText.take(300)
                )
            }

            if (status !in 200..299) {
                val error = response.optString("error")
                    .ifBlank { "خطای HTTP $status" }
                val code = response.optString("code")
                throw IllegalStateException(
                    "$error (HTTP $status${if (code.isNotBlank()) ", $code" else ""})"
                )
            }

            val answer = response.optString("answer").trim()

            if (answer.isBlank()) {
                throw IllegalStateException("پاسخ هوش مصنوعی خالی بود.")
            }

            val sources = mutableListOf<AiSource>()
            val array = response.optJSONArray("sources")

            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val link = item.optString("url")

                    if (link.startsWith("https://") || link.startsWith("http://")) {
                        sources.add(
                            AiSource(
                                item.optString("title").ifBlank { link },
                                link
                            )
                        )
                    }
                }
            }

            AiReply(answer, sources)

        } catch (e: Exception) {
            if (e is IllegalStateException) throw e

            throw IllegalStateException(
                "خطای ارتباط با سرور: ${e.javaClass.simpleName}: " +
                    "${e.message ?: "اتصال برقرار نشد"}",
                e
            )
        } finally {
            connection.disconnect()
        }
    }
}
