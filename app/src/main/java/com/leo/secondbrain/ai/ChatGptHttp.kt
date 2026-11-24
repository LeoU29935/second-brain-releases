package com.leo.secondbrain.ai

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject

internal class ChatGptHttp(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(100, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS).build()) {
    suspend fun <T> request(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(connectionError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(response.use(read))
                } catch (error: AiUnavailable) {
                    continuation.resumeWithException(error)
                } catch (error: ExpiredChatGptSession) {
                    continuation.resumeWithException(error)
                } catch (error: IOException) {
                    continuation.resumeWithException(connectionError(error))
                } catch (_: Exception) {
                    continuation.resumeWithException(AiUnavailable("ChatGPT returned an unreadable response."))
                }
            }
        })
    }
}

private fun connectionError(error: IOException) = AiUnavailable(
    if (error is SocketTimeoutException) "ChatGPT timed out. Retry when your connection is available."
    else "ChatGPT could not be reached. Check your connection and retry.", true
)

internal fun Response.jsonBody(): JSONObject {
    val source = body?.source() ?: throw AiUnavailable("ChatGPT returned an empty response.")
    if (source.request(2_000_001)) throw AiUnavailable("ChatGPT returned an oversized response.")
    return JSONObject(source.readUtf8())
}

internal fun Response.checkInferenceStatus() {
    if (isSuccessful) return
    val code = runCatching { jsonBody().optJSONObject("error")?.optString("code") }.getOrNull()
    throw inferenceError(code, this.code)
}

internal fun readChatGptStream(source: BufferedSource): JSONObject {
    val event = StringBuilder()
    val output = StringBuilder()
    var length = 0
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(2_000_000)
        length += line.length + 1
        if (length > 2_000_000) throw AiUnavailable("ChatGPT returned an oversized response.")
        if (line.startsWith("data:")) {
            if (event.isNotEmpty()) event.append('\n')
            event.append(line.removePrefix("data:").removePrefix(" "))
        } else if (line.isEmpty() && event.isNotEmpty()) {
            val data = event.toString()
            event.clear()
            if (data == "[DONE]") break
            val message = JSONObject(data)
            val response = message.optJSONObject("response")
            when (message.optString("type")) {
                "response.output_text.delta" -> output.append(message.getString("delta"))
                "response.refusal.delta", "response.refusal.done" ->
                    throw AiUnavailable("ChatGPT couldn’t organize this capture. Your transcript is saved.")
                "response.failed", "error" ->
                    throw inferenceError(response?.optJSONObject("error")?.optString("code") ?: message.optString("code"))
                "response.incomplete" -> throw AiUnavailable("ChatGPT returned an incomplete result. Retry this capture.")
                "response.completed" -> {
                    val finished = requireNotNull(response)
                    if (finished.optString("status") != "completed") break
                    val content = finished.optJSONArray("output").objects().flatMap { it.optJSONArray("content").objects() }
                    if (content.any { it.optString("type") == "refusal" }) {
                        throw AiUnavailable("ChatGPT couldn’t organize this capture. Your transcript is saved.")
                    }
                    val text = content.filter { it.optString("type") == "output_text" }.joinToString("") { it.getString("text") }
                    return JSONObject(text.ifBlank { output.toString() })
                }
            }
        }
    }
    throw AiUnavailable("ChatGPT did not finish the request. Retry this capture.", true)
}

private fun inferenceError(code: String?, status: Int = 0): IOException = when {
    code == "subscription_sharing_usage_limit_exceeded" || status == 429 ->
        ChatGptUsageLimit()
    code == "subscription_sharing_user_not_eligible" ->
        AiUnavailable("ChatGPT plan usage is unavailable for this account or workspace. Choose an eligible account in AI settings.")
    code == "subscription_sharing_usage_unavailable" || code == "subscription_sharing_user_unavailable" || status >= 500 ->
        AiUnavailable("ChatGPT usage is temporarily unavailable. Retry this capture later.", true)
    code == "subscription_sharing_invalid_user" || status == 401 -> ExpiredChatGptSession()
    code == "subscription_sharing_unsupported_capability" ->
        AiUnavailable("This ChatGPT model couldn’t organize the capture. Choose another model in AI settings and retry.")
    status == 403 -> AiUnavailable("ChatGPT plan permission was rejected. Check the connection in AI settings.")
    else -> AiUnavailable("ChatGPT couldn’t organize the capture. Your transcript is saved.")
}

internal class ChatGptUsageLimit : AiUnavailable(
    "ChatGPT usage limit reached. Check Manage usage in AI settings, then retry when usage is available."
)
