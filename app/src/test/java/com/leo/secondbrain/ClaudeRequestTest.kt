package com.leo.secondbrain

import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.leo.secondbrain.ai.AiUnavailable
import com.leo.secondbrain.ai.ClaudeModels
import com.leo.secondbrain.ai.claudeOrganize
import com.leo.secondbrain.ai.organizerSchema
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Sends real SDK requests to a local server, so the wire format and response handling are checked without a key. */
class ClaudeRequestTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var reply = ""
    private var body = JSONObject()
    private var headers = mapOf<String, String>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            body = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            headers = exchange.requestHeaders.mapValues { it.value.joinToString(",") }.mapKeys { it.key.lowercase() }
            val bytes = reply.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client() = AnthropicOkHttpClientAsync.builder().apiKey("sk-ant-test")
        .baseUrl("http://127.0.0.1:${server.address.port}").maxRetries(0).build()

    private fun message(text: String, stop: String = "end_turn") = JSONObject()
        .put("id", "msg_test").put("type", "message").put("role", "assistant").put("model", "claude-opus-5-5")
        .put("content", listOf(JSONObject().put("type", "text").put("text", text)))
        .put("stop_reason", stop).put("stop_sequence", JSONObject.NULL)
        .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5)).toString()

    private fun organize(model: String = "claude-opus-5-5") = runBlocking {
        claudeOrganize(client(), ClaudeModels.find(model), "Organize.", JSONObject().put("capture", "Call Dad tomorrow"), organizerSchema)
    }

    @Test
    fun sendsStructuredRequestAndParsesResult() {
        reply = message("""{"suggestions":[{"kind":"TASK","title":"Call Dad"}]}""")
        val result = organize()
        assertEquals("Call Dad", result.getJSONArray("suggestions").getJSONObject(0).getString("title"))
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("Organize.", body.getString("system"))
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
        val format = body.getJSONObject("output_config").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getJSONObject("schema").getJSONObject("properties").has("suggestions"))
        assertEquals("default", body.getString("fallbacks"))
        assertTrue(headers["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))
        assertEquals("Bearer sk-ant-test", headers["authorization"])
    }

    @Test
    fun haikuSkipsServerFallback() {
        reply = message("""{"suggestions":[]}""")
        organize("claude-haiku-5-5")
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertFalse(body.has("fallbacks"))
        assertFalse(headers["anthropic-beta"].orEmpty().contains("server-side-fallback"))
    }

    @Test
    fun refusalBecomesReadableError() {
        reply = message("", stop = "refusal")
        try {
            organize()
            fail("Expected a refusal error")
        } catch (error: AiUnavailable) {
            assertTrue(error.message!!.contains("declined"))
        }
    }

    @Test
    fun rejectedKeyBecomesReadableError() {
        status = 401
        reply = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        try {
            organize()
            fail("Expected an authentication error")
        } catch (error: AiUnavailable) {
            assertTrue(error.message!!.contains("rejected this API key"))
        }
    }
}
