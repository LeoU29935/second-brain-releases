package com.leo.secondbrain.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object AiModels {
    const val transcription = "gpt-transcribe"
    const val organizer = "gpt-5.4-mini"
    const val realtime = "gpt-realtime-2.1"
}

internal open class AiUnavailable(message: String, val retryLater: Boolean = false) : IOException(message)

internal class OpenAiClient(private val credentials: CredentialStore) {
    internal val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    suspend fun organize(instructions: String, input: JSONObject, schema: JSONObject): JSONObject {
        val format = JSONObject().put("type", "json_schema").put("strict", true)
            .put("name", "brain_dump_proposals").put("schema", schema)
        val body = JSONObject().put("model", AiModels.organizer).put("store", false)
            .put("max_output_tokens", 10_000).put("instructions", instructions)
            .put("input", input.toString()).put("text", JSONObject().put("format", format))
        val response = post("responses", body)
        if (response.optString("status") != "completed") {
            throw AiUnavailable("OpenAI returned an incomplete result. Try again.")
        }
        val messages = response.optJSONArray("output").objects().flatMap {
            it.optJSONArray("content").objects()
        }
        if (messages.any { it.optString("type") == "refusal" }) {
            throw AiUnavailable("OpenAI couldn’t organize this capture. You can still create drafts manually.")
        }
        val result = messages.filter { it.optString("type") == "output_text" }
            .joinToString("") { it.getString("text") }
        if (result.isBlank()) throw AiUnavailable("OpenAI returned no usable suggestions.")
        return JSONObject(result)
    }

    suspend fun realtimeToken(): String = post("realtime/client_secrets",
        JSONObject().put("session", JSONObject().put("type", "realtime").put("model", AiModels.realtime))
    ).getString("value")

    private suspend fun post(path: String, value: JSONObject) =
        post(path, value.toString().toRequestBody("application/json".toMediaType()))

    private suspend fun post(path: String, body: RequestBody): JSONObject {
        val key = withContext(Dispatchers.IO) {
            runCatching { credentials.read() }.getOrElse {
                throw AiUnavailable("The stored key could not be read. Replace it in AI settings.", true)
            }
        } ?: throw AiUnavailable("Your capture is saved. AI organization isn’t configured in AI settings.")
        val request = Request.Builder().url("https://api.openai.com/v1/$path")
            .header("Authorization", "Bearer $key").post(body).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val message = if (e is SocketTimeoutException) "OpenAI timed out. Try again."
                        else "OpenAI could not be reached. Check your connection and try again."
                    continuation.resumeWithException(AiUnavailable(message, true))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val json = response.use {
                            if (!it.isSuccessful) throw statusError(it.code)
                            val source = it.body?.source() ?: throw AiUnavailable("OpenAI returned an empty response.")
                            if (source.request(2_000_001)) throw AiUnavailable("OpenAI returned an oversized response.")
                            JSONObject(source.readUtf8())
                        }
                        continuation.resume(json)
                    } catch (error: AiUnavailable) {
                        continuation.resumeWithException(error)
                    } catch (_: Exception) {
                        continuation.resumeWithException(AiUnavailable("OpenAI returned an unreadable response."))
                    }
                }
            })
        }
    }

    private fun statusError(code: Int) = AiUnavailable(when (code) {
        401, 403 -> "OpenAI rejected this key or project permissions. Check AI settings."
        429 -> "OpenAI rate limit or billing limit reached. Try later or check your project."
        in 500..599 -> "OpenAI is temporarily unavailable. Try again later."
        else -> "OpenAI could not process this request (HTTP $code)."
    }, retryLater = code == 401 || code == 403 || code == 429 || code >= 500)
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
