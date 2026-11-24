package com.leo.secondbrain.ai

import android.net.Uri
import android.util.Base64
import com.leo.secondbrain.CHATGPT_RETURN_URI
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

internal const val CHATGPT_ISSUER = "https://auth.openai.com"
internal const val CHATGPT_RESOURCE = "https://api.openai.com/v1"
internal const val CHATGPT_PLAN_SCOPE = "chatgpt.tokens.use.direct"
private const val DYNAMIC_CLIENT = "dynamic_agent_client"
private const val CALLBACK_PATH = "/auth/callback"

internal class ChatGptOAuth(private val http: ChatGptHttp) {
    suspend fun authorize(hostId: String, previous: ChatGptRegistration?, openBrowser: (Uri) -> Unit):
        ChatGptRegistration = withContext(Dispatchers.IO) {
        val state = random()
        val nonce = random()
        val verifier = random()
        val (code, clientId, redirect) = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 500
            val redirect = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"
            val uri = Uri.parse("$CHATGPT_ISSUER/api/accounts/authorize").buildUpon()
                .appendQueryParameter("client_id", previous?.clientId ?: DYNAMIC_CLIENT)
                .appendQueryParameter("ext_agent_host_id", hostId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", "openid profile email offline_access resource.invoke $CHATGPT_PLAN_SCOPE")
                .appendQueryParameter("resource", CHATGPT_RESOURCE)
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
                .apply {
                    if (previous == null) appendQueryParameter("agent_name_hint", "Second Brain")
                    if (previous?.planAllowed == false) appendQueryParameter("prompt", "consent")
                    previous?.idToken?.let { appendQueryParameter("id_token_hint", it) }
                    previous?.email?.let { appendQueryParameter("login_hint", it) }
                }.build()
            withContext(Dispatchers.Main) { openBrowser(uri) }
            val deadline = android.os.SystemClock.elapsedRealtime() + 300_000
            var callback: Uri? = null
            while (callback == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                ensureActive()
                callback = readCallback(server, state)
            }
            val result = callback ?: throw AiUnavailable("ChatGPT sign-in timed out. Try again.")
            when (result.getQueryParameter("error")) {
                null -> Unit
                "access_denied" -> throw AiUnavailable("ChatGPT plan permission wasn’t granted. Continue with ChatGPT to try again.")
                "temporarily_unavailable", "server_error" ->
                    throw AiUnavailable("ChatGPT sign-in is temporarily unavailable. Try again later.", true)
                else -> throw AiUnavailable("ChatGPT couldn’t authorize this connection. Continue with ChatGPT to try again.")
            }
            val code = result.getQueryParameter("code")?.takeIf(String::isNotBlank)
                ?: throw AiUnavailable("ChatGPT returned no authorization code. Try again.")
            val returned = result.getQueryParameter("client_id")
            val clientId = previous?.clientId ?: returned
            if (clientId.isNullOrBlank() || clientId == DYNAMIC_CLIENT || returned != null && clientId != returned) {
                throw AiUnavailable("ChatGPT returned an unexpected app registration. Try again.")
            }
            Triple(code, clientId, redirect)
        }
        val tokens = tokenRequest(FormBody.Builder().add("grant_type", "authorization_code")
            .add("client_id", clientId).add("code", code).add("redirect_uri", redirect)
            .add("code_verifier", verifier).add("resource", CHATGPT_RESOURCE).build())
        val idToken = tokens.optString("id_token").takeIf(String::isNotBlank)
            ?: throw AiUnavailable("ChatGPT didn’t return the account identity. Try signing in again.")
        val claims = try {
            validateIdentity(idToken, clientId, nonce)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: AiUnavailable) {
            throw error
        } catch (_: Exception) {
            throw AiUnavailable("Second Brain couldn’t verify the ChatGPT account. Try signing in again.")
        }
        val subject = claims.getString("sub")
        if (previous != null && previous.subject != subject) {
            throw AiUnavailable("ChatGPT returned a different account. Add it as a new account.")
        }
        ChatGptRegistration(clientId, subject, claims.optString("email").ifBlank { null }, idToken,
            selectedModel = previous?.selectedModel, usagePaused = previous?.usagePaused == true)
            .withTokens(tokens)
    }

    suspend fun refresh(registration: ChatGptRegistration): ChatGptRegistration {
        val refresh = registration.refreshToken ?: throw ExpiredChatGptSession()
        val response = tokenRequest(FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", registration.clientId).add("refresh_token", refresh)
            .add("resource", CHATGPT_RESOURCE).build(), refreshing = true)
        return registration.withTokens(response).also {
            require(it.refreshToken != null || !it.planAllowed)
        }
    }

    suspend fun revoke(registration: ChatGptRegistration) {
        val refresh = registration.refreshToken ?: return
        val body = FormBody.Builder().add("token", refresh)
            .add("token_type_hint", "refresh_token").add("client_id", registration.clientId).build()
        http.request(Request.Builder().url("$CHATGPT_ISSUER/api/accounts/oauth/revoke").post(body).build()) {
            if (!it.isSuccessful) throw AiUnavailable("Couldn’t disconnect the session from ChatGPT.")
        }
    }

    private suspend fun tokenRequest(body: FormBody, refreshing: Boolean = false): JSONObject =
        http.request(Request.Builder().url("$CHATGPT_ISSUER/api/accounts/oauth/token").post(body).build()) { response ->
            if (!response.isSuccessful) {
                val error = runCatching { response.jsonBody().optString("error") }.getOrNull()
                if (refreshing && (error == "invalid_grant" || error in terminalRefreshErrors)) {
                    throw ExpiredChatGptSession()
                }
                throw AiUnavailable(when {
                    error == "invalid_grant" -> "ChatGPT didn’t accept the sign-in code. Start a fresh sign-in."
                    error == "invalid_client" -> "ChatGPT didn’t recognize this app connection. Check for an app update."
                    response.code >= 500 -> "ChatGPT sign-in is temporarily unavailable. Try again later."
                    else -> "ChatGPT couldn’t complete sign-in (HTTP ${response.code}). Try again."
                }, response.code >= 500)
            }
            response.jsonBody()
        }

    private suspend fun validateIdentity(token: String, clientId: String, nonce: String): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3)
        val header = JSONObject(decode(parts[0]).toString(Charsets.UTF_8))
        require(header.getString("alg") == "RS256")
        val keys = http.request(Request.Builder().url("$CHATGPT_ISSUER/.well-known/jwks.json").build()) {
            if (!it.isSuccessful) throw AiUnavailable("ChatGPT identity verification is temporarily unavailable.", true)
            it.jsonBody().getJSONArray("keys").objects()
        }
        val key = keys.single { it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA" }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decode(key.getString("n"))), BigInteger(1, decode(key.getString("e")))
        ))
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(decode(parts[2]))
        })
        val payload = JSONObject(decode(parts[1]).toString(Charsets.UTF_8))
        val audiences = payload.optJSONArray("aud")?.let { list ->
            (0 until list.length()).map { list.getString(it) }
        } ?: listOf(payload.getString("aud"))
        require(payload.getString("iss") == CHATGPT_ISSUER && clientId in audiences)
        val authorizedParty = payload.optString("azp")
        require(if (audiences.size > 1 || authorizedParty.isNotEmpty()) authorizedParty == clientId else true)
        require(payload.getLong("exp") > System.currentTimeMillis() / 1_000)
        require(payload.getString("nonce") == nonce && payload.getString("sub").isNotBlank())
        return payload
    }
}

private fun readCallback(server: ServerSocket, state: String): Uri? = try {
    server.accept().use { socket ->
        socket.soTimeout = 500
        val input = socket.getInputStream().buffered()
        val deadline = android.os.SystemClock.elapsedRealtime() + 1_500
        val request = readLine(input, 8_192, deadline).split(' ')
        var headerBytes = 0
        while (true) {
            val header = readLine(input, 4_096, deadline)
            if (header.isEmpty()) break
            headerBytes += header.length
            if (headerBytes > 16_384) throw EOFException()
        }
        val uri = request.getOrNull(1)?.takeIf { it.startsWith("$CALLBACK_PATH?") }?.let(Uri::parse)
        val accepted = request.size == 3 && request[0] == "GET" && uri?.getQueryParameter("state") == state
        val message = if (accepted) returnPage else "Invalid request."
        val status = if (accepted) "200 OK" else "400 Bad Request"
        val mediaType = if (accepted) "text/html" else "text/plain"
        try {
            socket.getOutputStream().write(
                ("HTTP/1.1 $status\r\nContent-Type: $mediaType; charset=utf-8\r\nCache-Control: no-store\r\n" +
                    "Referrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: ${message.length}\r\n\r\n$message")
                    .toByteArray(Charsets.US_ASCII)
            )
        } catch (_: IOException) {
            // The browser can close after sending the callback. A verified state still completes the attempt.
        }
        uri?.takeIf { accepted }
    }
} catch (_: SocketTimeoutException) {
    null
} catch (_: EOFException) {
    null
} catch (_: java.net.SocketException) {
    null
}

private val returnPage = """
    <!doctype html>
    <html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <meta name="color-scheme" content="light dark"><title>Return to Second Brain</title>
    <body style="max-width:28rem;margin:3rem auto;padding:1rem;font-family:system-ui">
    <h1>Return to Second Brain</h1>
    <p>Check the status of your ChatGPT connection in the app.</p>
    <a style="display:inline-block;padding:0.8rem 1rem;border:1px solid;border-radius:0.8rem"
      href="$CHATGPT_RETURN_URI">Open Second Brain</a>
    </body></html>
""".trimIndent()

private fun readLine(input: InputStream, limit: Int, deadline: Long): String {
    val line = StringBuilder()
    repeat(limit) {
        if (android.os.SystemClock.elapsedRealtime() > deadline) throw SocketTimeoutException()
        val value = input.read()
        if (value < 0) throw EOFException()
        if (value == 10) return line.toString().trimEnd('\r')
        line.append(value.toChar())
    }
    throw EOFException()
}

private fun random() = encode(ByteArray(32).also(SecureRandom()::nextBytes))
private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
private val terminalRefreshErrors = setOf("invalid_refresh_token", "token_expired", "refresh_token_expired",
    "refresh_token_invalidated", "refresh_token_reused")
internal class ExpiredChatGptSession : java.io.IOException()
