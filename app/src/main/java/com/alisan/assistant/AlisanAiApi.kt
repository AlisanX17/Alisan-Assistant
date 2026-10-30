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

    suspend fun ask(message: String, attachments: List<SelectedAttachment> = emptyList()): AiReply =
        withContext(Dispatchers.IO) {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 120000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")

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
                    .put("message", message)
                    .put("attachments", attachmentArray)

                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                val response = JSONObject(responseText)

                if (status !in 200..299) {
                    throw IllegalStateException(
                        response.optString("error").ifBlank { "HTTP $status" }
                    )
                }

                val answer = response.optString("answer").ifBlank {
                    throw IllegalStateException("Empty response from Alisan AI")
                }

                val sources = mutableListOf<AiSource>()
                val array = response.optJSONArray("sources")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val link = item.optString("url")
                        if (link.startsWith("https://") || link.startsWith("http://")) {
                            sources.add(AiSource(item.optString("title", link), link))
                        }
                    }
                }

                AiReply(answer, sources)
            } finally {
                connection.disconnect()
            }
        }
}
