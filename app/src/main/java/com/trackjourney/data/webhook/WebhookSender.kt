package com.trackjourney.data.webhook

import android.util.Log
import com.google.gson.Gson
import com.trackjourney.data.model.WebhookLocationPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            var connection: HttpURLConnection? = null
            try {
                val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
                val endpoint = baseUrl.trimEnd('/') + PATH + "?key=$encodedKey"
                val json = gson.toJson(payload)

                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true

                connection.outputStream.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val responseCode = connection.responseCode

                // Drain the response / error stream so the connection can be
                // returned to the pool instead of being leaked.
                if (responseCode in 200..299) {
                    connection.inputStream.use { it.readBytes() }
                    Log.d(TAG, "Webhook sent successfully ($responseCode)")
                    true
                } else {
                    val errorBody = connection.errorStream?.use { it.readBytes()?.decodeToString() }
                    Log.w(TAG, "Webhook failed with HTTP $responseCode: $errorBody")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Webhook send error: ${e.message}", e)
                false
            } finally {
                connection?.disconnect()
            }
        }
}
