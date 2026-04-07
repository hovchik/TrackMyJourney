package com.trackmyjourney.data.webhook

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import com.trackmyjourney.data.model.WebhookLocationPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

class WebhookSender(private val context: Context) {

    private val gson = Gson()
    private val pendingQueue = ConcurrentLinkedQueue<PendingPayload>()
    private val flushMutex = Mutex()
    private var consecutiveFailures = 0

    companion object {
        private const val TAG = "WebhookSender"
        private const val PATH = "/api/hook"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_RETRIES = 3
        private const val MAX_QUEUE_SIZE = 50
        private val RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000)
    }

    /** Queued payload waiting to be re-sent. Stores pre-serialised JSON to avoid
     *  holding on to large object graphs. */
    private data class PendingPayload(
        val baseUrl: String,
        val key: String,
        val json: String
    )

    // ── public API ──────────────────────────────────────────

    /**
     * Sends [payload] to the webhook endpoint.
     *
     * - If the device is offline the payload is queued and sent when
     *   connectivity returns (on the next [send] call).
     * - Retries transient failures up to [MAX_RETRIES] times with
     *   exponential back-off (2 s → 4 s → 8 s).
     * - Permanently failing payloads (4xx) are dropped immediately.
     * - Successfully recovered payloads are flushed from the queue
     *   before the current payload is attempted.
     *
     * @return `true` if the payload was delivered, `false` if it was
     *         queued or permanently failed.
     */
    suspend fun send(
        baseUrl: String,
        key: String,
        payload: WebhookLocationPayload
    ): Boolean = withContext(Dispatchers.IO) {
        // Try to drain anything that accumulated while we were offline.
        flushPending(baseUrl, key)

        val json = gson.toJson(payload)

        if (!isNetworkAvailable()) {
            enqueue(baseUrl, key, json)
            Log.w(TAG, "Offline — queued payload (${pendingQueue.size} pending)")
            return@withContext false
        }

        val success = sendWithRetry(baseUrl, key, json)
        if (success) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures++
            enqueue(baseUrl, key, json)
            Log.w(TAG, "Send failed after retries — queued (${pendingQueue.size} pending, $consecutiveFailures consecutive failures)")
        }
        success
    }

    /** Number of payloads waiting to be re-sent. */
    val pendingCount: Int get() = pendingQueue.size

    // ── retry logic ─────────────────────────────────────────

    private suspend fun sendWithRetry(baseUrl: String, key: String, json: String): Boolean {
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                val delayMs = RETRY_DELAYS_MS.getOrElse(attempt - 1) { RETRY_DELAYS_MS.last() }
                Log.d(TAG, "Retry $attempt/$MAX_RETRIES in ${delayMs}ms")
                delay(delayMs)
            }
            val result = doSend(baseUrl, key, json)
            when (result) {
                SendResult.SUCCESS -> return true
                SendResult.PERMANENT_FAILURE -> return false   // 4xx — don't retry
                SendResult.TRANSIENT_FAILURE -> continue       // 5xx / network — retry
            }
        }
        return false
    }

    // ── single HTTP attempt ─────────────────────────────────

    private enum class SendResult { SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE }

    private fun doSend(baseUrl: String, key: String, json: String): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
            val endpoint = baseUrl.trimEnd('/') + PATH + "?key=$encodedKey"

            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            connection.outputStream.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = connection.responseCode

            // Always drain the stream so the connection can return to the pool.
            if (code in 200..299) {
                connection.inputStream.use { it.readBytes() }
                Log.d(TAG, "Webhook OK ($code)")
                SendResult.SUCCESS
            } else {
                val body = connection.errorStream?.use { it.readBytes()?.decodeToString() }
                Log.w(TAG, "Webhook HTTP $code: $body")
                if (code in 400..499) SendResult.PERMANENT_FAILURE else SendResult.TRANSIENT_FAILURE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Webhook error: ${e.message}", e)
            SendResult.TRANSIENT_FAILURE
        } finally {
            connection?.disconnect()
        }
    }

    // ── offline queue ───────────────────────────────────────

    private fun enqueue(baseUrl: String, key: String, json: String) {
        if (pendingQueue.size >= MAX_QUEUE_SIZE) {
            pendingQueue.poll()   // drop the oldest to make room
            Log.w(TAG, "Queue full — dropped oldest payload")
        }
        pendingQueue.add(PendingPayload(baseUrl, key, json))
    }

    /**
     * Drains the pending queue by re-sending each item. Stops at the
     * first failure so we don't burn battery on a dead connection.
     * The [Mutex] ensures only one flush runs at a time.
     */
    private suspend fun flushPending(baseUrl: String, key: String) {
        if (pendingQueue.isEmpty() || !isNetworkAvailable()) return

        flushMutex.withLock {
            var flushed = 0
            while (pendingQueue.isNotEmpty()) {
                val pending = pendingQueue.peek() ?: break
                val result = doSend(pending.baseUrl, pending.key, pending.json)
                if (result == SendResult.SUCCESS) {
                    pendingQueue.poll()
                    flushed++
                } else if (result == SendResult.PERMANENT_FAILURE) {
                    pendingQueue.poll()   // discard bad payload
                    flushed++
                } else {
                    break   // transient failure — stop flushing
                }
            }
            if (flushed > 0) {
                Log.i(TAG, "Flushed $flushed queued payloads (${pendingQueue.size} remaining)")
            }
        }
    }

    // ── connectivity ────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true   // assume available if we can't check
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
