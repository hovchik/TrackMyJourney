package com.trackjourney.data.webhook

import android.util.Log
import com.google.gson.Gson
import com.trackjourney.data.model.WebhookLocationPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class WebhookSender {

    private val gson = Gson()

    companion object {
        private const val TAG = "WebhookSender"
        private const val PATH = "/api/hook"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    suspend fun send(baseUrl: String, key: String, payload: WebhookLocationPayload): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
                val endpoint = baseUrl.trimEnd('/') + PATH + "?key=$encodedKey"
                val json = gson.toJson(payload)

                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(json)
                    writer.flush()
                }

                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode in 200..299) {
                    Log.d(TAG, "Webhook sent successfully ($responseCode)")
                    true
                } else {
                    Log.w(TAG, "Webhook failed with HTTP $responseCode")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Webhook send error: ${e.message}")
                false
            }
        }
}
