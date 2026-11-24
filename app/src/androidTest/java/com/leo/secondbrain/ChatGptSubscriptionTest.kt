package com.leo.secondbrain

import android.net.Uri
import android.util.Base64
import android.content.ContextWrapper
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.AiUnavailable
import com.leo.secondbrain.ai.ChatGptHttp
import com.leo.secondbrain.ai.ChatGptSubscription
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatGptSubscriptionTest {
    @Test
    fun authorizationStoresVerifiedRegistrationRefreshesExpiresAndNeverFallsBack() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(target.cacheDir, "chatgpt-fixture").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = fixture
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val issued = "fixture-client"
        val tokenRequests = AtomicInteger()
        val inferences = AtomicInteger()
        val revocations = AtomicInteger()
        lateinit var authorization: Uri
        fun asUrl(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun signIdToken(): String {
            val header = JSONObject().put("kid", "fixture").put("alg", "RS256")
            val claims = JSONObject().put("sub", "fixture-subject").put("email", "fixture@example.invalid")
                .put("iss", "https://auth.openai.com").put("aud", issued)
                .put("exp", System.currentTimeMillis() / 1_000 + 3_600)
                .put("nonce", authorization.getQueryParameter("nonce"))
            val input = asUrl(header.toString().toByteArray()) + "." + asUrl(claims.toString().toByteArray())
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update(input.toByteArray())
                sign()
            }
            return "$input.${asUrl(signature)}"
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val value = when (request.url.encodedPath) {
                "/api/accounts/oauth/token" -> {
                    val form = request.body as FormBody
                    val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
                    assertEquals(issued, fields["client_id"])
                    when (tokenRequests.incrementAndGet()) {
                        1 -> {
                            assertEquals("authorization_code", fields["grant_type"])
                            assertEquals(authorization.getQueryParameter("redirect_uri"), fields["redirect_uri"])
                            val hash = MessageDigest.getInstance("SHA-256").digest(fields.getValue("code_verifier").toByteArray())
                            assertEquals(authorization.getQueryParameter("code_challenge"), asUrl(hash))
                            tokens("start", lifetime = 1).put("id_token", signIdToken()).toString()
                        }
                        2 -> {
                            assertEquals("refresh_token", fields["grant_type"])
                            assertEquals("refresh-start", fields["refresh_token"])
                            tokens("renewed").toString()
                        }
                        else -> {
                            assertEquals("refresh-renewed", fields["refresh_token"])
                            status = 400
                            """{"error":"invalid_grant"}"""
                        }
                    }
                }
                "/.well-known/jwks.json" -> {
                    val key = pair.public as RSAPublicKey
                    JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "fixture").put("kty", "RSA")
                        .put("n", asUrl(key.modulus.toByteArray())).put("e", asUrl(key.publicExponent.toByteArray())))).toString()
                }
                "/v1/models" -> {
                    assertEquals("Bearer access-renewed", request.header("Authorization"))
                    """{"models":[{"visibility":"list","slug":"fixture-model","display_name":"Fixture"}]}"""
                }
                "/v1/responses" -> {
                    assertEquals("Bearer access-renewed", request.header("Authorization"))
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val body = JSONObject(buffer.readUtf8())
                    assertEquals("fixture-model", body.getString("model"))
                    assertFalse(body.getBoolean("store"))
                    assertTrue(body.getBoolean("stream"))
                    assertEquals("Transcript fixture", body.getJSONArray("input").getJSONObject(0)
                        .getString("content").let(::JSONObject).getString("capture"))
                    if (inferences.incrementAndGet() == 1) {
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"{\\\"suggestions\\\":[]} \"}\n\n" +
                            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                    } else {
                        status = 401
                        """{"error":{"code":"subscription_sharing_invalid_user"}}"""
                    }
                }
                "/api/accounts/oauth/revoke" -> {
                    revocations.incrementAndGet()
                    ""
                }
                else -> error("Unexpected test request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(value.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val transport = ChatGptHttp(client)
        val session = ChatGptSubscription(context, transport)
        val callback = kotlinx.coroutines.CompletableDeferred<Uri>()
        val signingIn = async {
            session.signIn(addAccount = true) { url -> authorization = url; callback.complete(url) }
        }
        try {
            val url = withTimeout(5_000) { callback.await() }
            assertEquals("dynamic_agent_client", url.getQueryParameter("client_id"))
            assertEquals("S256", url.getQueryParameter("code_challenge_method"))
            val redirect = Uri.parse(url.getQueryParameter("redirect_uri"))
            Socket("127.0.0.1", redirect.port).use { socket ->
                val state = url.getQueryParameter("state")
                socket.getOutputStream().write(
                    "GET /auth/callback?code=fixture-code&client_id=$issued&state=$state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                        .toByteArray()
                )
            }
            withTimeout(10_000) { signingIn.await() }
            val reopened = ChatGptSubscription(context, transport)
            reopened.load()
            assertTrue(reopened.settings.value.usingSubscription)
            assertTrue(reopened.settings.value.active?.planAllowed == true)
            val input = JSONObject().put("capture", "Transcript fixture")
            assertEquals(0, reopened.organize("Organize", input, JSONObject()) { error("API-key fallback") }
                .getJSONArray("suggestions").length())
            val error = runCatching {
                reopened.organize("Organize", input, JSONObject()) { error("API-key fallback") }
            }.exceptionOrNull()
            assertTrue(error is AiUnavailable && error.message.orEmpty().contains("session expired"))
            assertFalse(reopened.settings.value.active?.planAllowed == true)
            assertTrue(reopened.signOut())
            assertTrue(reopened.settings.value.usingSubscription)
            assertNull(reopened.settings.value.active)
            assertEquals(3, tokenRequests.get())
            assertEquals(0, revocations.get())
        } finally {
            signingIn.cancel()
            ChatGptSubscription(context, transport).signOut()
            File(fixture, "chatgpt-account.credential").delete()
            fixture.delete()
        }
    }

    private fun tokens(key: String, lifetime: Int = 3_600) = JSONObject().put("access_token", "access-$key")
        .put("refresh_token", "refresh-$key").put("token_type", "Bearer").put("expires_in", lifetime)
        .put("scope", "openid offline_access resource.invoke chatgpt.tokens.use.direct")
}
