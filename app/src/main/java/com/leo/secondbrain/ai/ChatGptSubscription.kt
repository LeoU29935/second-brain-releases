package com.leo.secondbrain.ai

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

internal data class ChatGptAccount(val id: String, val label: String, val planAllowed: Boolean)
internal data class ChatGptSettings(
    val usingSubscription: Boolean = false,
    val active: ChatGptAccount? = null,
    val accounts: List<ChatGptAccount> = emptyList(),
    val notice: Boolean = false,
    val selectedModel: String? = null,
    val modelChoices: List<ChatGptModel> = emptyList()
)

internal data class ChatGptModel(val slug: String, val label: String)

internal data class ChatGptRegistration(
    val clientId: String, val subject: String, val email: String?, val idToken: String,
    val accessToken: String? = null, val refreshToken: String? = null, val expiresAt: Long = 0,
    val scopes: String = "", val selectedModel: String? = null, val usagePaused: Boolean = false
) {
    val planAllowed get() = CHATGPT_PLAN_SCOPE in scopes.split(' ') && "resource.invoke" in scopes.split(' ')
    fun withTokens(tokens: JSONObject): ChatGptRegistration {
        val lifetime = tokens.getLong("expires_in").also { require(it in 1..86_400) }
        require(tokens.getString("token_type").equals("bearer", ignoreCase = true))
        val access = tokens.getString("access_token").also { require(it.isNotBlank()) }
        return copy(accessToken = access, refreshToken = tokens.optional("refresh_token"),
            expiresAt = System.currentTimeMillis() + lifetime * 1_000,
            scopes = tokens.optString("scope", scopes))
    }

    fun json() = JSONObject().put("client", clientId).put("subject", subject).put("email", email)
        .put("id", idToken).put("access", accessToken).put("refresh", refreshToken)
        .put("expires", expiresAt).put("scopes", scopes).put("model", selectedModel).put("usagePaused", usagePaused)

    companion object {
        fun read(json: JSONObject) = ChatGptRegistration(
            json.getString("client"), json.getString("subject"), json.optional("email"), json.getString("id"),
            json.optional("access"), json.optional("refresh"), json.getLong("expires"), json.getString("scopes"),
            json.optional("model"), json.optBoolean("usagePaused")
        )
    }
}

private fun JSONObject.optional(key: String): String? = optString(key, "").takeIf(String::isNotBlank)

private data class ChatGptRegistrations(
    val hostId: String = "urn:uuid:${UUID.randomUUID()}",
    val subscription: Boolean = false,
    val active: String? = null,
    val accounts: List<ChatGptRegistration> = emptyList(),
    val seenNotice: Boolean = false
) {
    val current get() = accounts.firstOrNull { it.clientId == active }
    fun save(account: ChatGptRegistration) = copy(accounts = accounts.filterNot { it.clientId == account.clientId } + account)
    fun json() = JSONObject().put("host", hostId).put("subscription", subscription).put("active", active)
        .put("notice", seenNotice).put("accounts", JSONArray(accounts.map(ChatGptRegistration::json)))
}

internal class ChatGptSubscription(context: Context, private val http: ChatGptHttp = ChatGptHttp()) {
    private val storage = ProtectedValueStore(context, "chatgpt-account.credential", "com.leo.secondbrain.chatgpt.v1")
    private val lock = Mutex()
    private val oauth = ChatGptOAuth(http)
    private val visible = MutableStateFlow(ChatGptSettings())
    val settings = visible.asStateFlow()

    suspend fun load() = access { state -> publish(state) }

    suspend fun signIn(addAccount: Boolean, openBrowser: (Uri) -> Unit) = access { state ->
        val enabled = state.copy(subscription = true)
        save(enabled)
        val account = oauth.authorize(state.hostId, state.current.takeUnless { addAccount }, openBrowser)
        save(enabled.save(account).copy(active = account.clientId))
    }

    suspend fun useApiKey() = access { state -> save(state.copy(subscription = false)) }
    suspend fun dismissNotice() = access { state -> save(state.copy(seenNotice = true)) }

    suspend fun selectAccount(id: String) = access { state ->
        require(state.accounts.any { it.clientId == id })
        save(state.copy(subscription = true, active = id))
    }

    suspend fun signOut(): Boolean = access { state ->
        val account = state.current ?: return@access true
        save(state.copy(active = null, accounts = state.accounts - account))
        try {
            oauth.revoke(account)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    suspend fun refreshModels() = access { state ->
        val result = withSession(state) { it -> models(requireNotNull(it.accessToken)) }
        visible.value = visible.value.copy(modelChoices = result)
    }

    suspend fun selectModel(slug: String) = access { state ->
        val account = requireNotNull(state.current)
        require(visible.value.modelChoices.any { it.slug == slug })
        save(state.save(account.copy(selectedModel = slug)))
    }

    suspend fun retryAfterUsageLimit() = access { state ->
        state.current?.takeIf { state.subscription && it.usagePaused }?.let {
            save(state.save(it.copy(usagePaused = false)))
        }
    }

    suspend fun organize(instructions: String, input: JSONObject, schema: JSONObject,
        apiKeyOrganization: suspend () -> JSONObject): JSONObject = access { state ->
        if (!state.subscription) return@access apiKeyOrganization()
        if (state.current?.usagePaused == true) throw ChatGptUsageLimit()
        withSession(state) { account ->
            val token = requireNotNull(account.accessToken)
            val choices = visible.value.modelChoices.ifEmpty { models(token) }
            visible.value = visible.value.copy(modelChoices = choices)
            val model = choices.firstOrNull { it.slug == account.selectedModel } ?: choices.firstOrNull()
                ?: throw AiUnavailable("This ChatGPT account has no available model for organizing captures.")
            val format = JSONObject().put("type", "json_schema").put("strict", true)
                .put("name", "brain_dump_proposals").put("schema", schema)
            val body = JSONObject().put("model", model.slug).put("store", false).put("stream", true)
                .put("instructions", instructions)
                .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", input.toString())))
                .put("text", JSONObject().put("format", format))
            val request = bearer("$CHATGPT_RESOURCE/responses", token)
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            try {
                http.request(request) { response ->
                    response.checkInferenceStatus()
                    readChatGptStream(requireNotNull(response.body).source())
                }
            } catch (error: ChatGptUsageLimit) {
                save(state.save(account.copy(usagePaused = true)))
                throw error
            }
        }
    }

    private suspend fun models(token: String): List<ChatGptModel> = http.request(
        bearer("$CHATGPT_RESOURCE/models", token).build()
    ) { response ->
        response.checkInferenceStatus()
        response.jsonBody().optJSONArray("models").objects().mapNotNull {
            val slug = it.optString("slug")
            if (it.optString("visibility") != "list" || slug.isBlank()) null
            else ChatGptModel(slug, it.optString("display_name").ifBlank { slug })
        }.distinctBy(ChatGptModel::slug)
    }

    private suspend fun <T> withSession(
        state: ChatGptRegistrations, operation: suspend (ChatGptRegistration) -> T
    ): T {
        val saved = state.current ?: throw AiUnavailable("Sign in with ChatGPT in AI settings to organize this capture.")
        if (!saved.planAllowed) throw AiUnavailable("ChatGPT plan usage isn’t enabled. Reconnect in AI settings.")
        var account = saved
        try {
            val refreshed = account.accessToken == null || account.expiresAt <= System.currentTimeMillis() + 60_000
            if (refreshed) account = renew(state, account)
            return try {
                operation(account)
            } catch (error: ExpiredChatGptSession) {
                if (refreshed) throw error
                account = renew(state, account)
                operation(account)
            }
        } catch (_: ExpiredChatGptSession) {
            save(state.save(account.copy(accessToken = null, refreshToken = null, expiresAt = 0)))
            throw AiUnavailable("Your ChatGPT session expired. Reconnect in AI settings, then retry.")
        }
    }

    private suspend fun renew(state: ChatGptRegistrations, account: ChatGptRegistration): ChatGptRegistration {
        val renewed = oauth.refresh(account)
        save(state.save(renewed))
        if (!renewed.planAllowed) throw AiUnavailable("ChatGPT plan usage is no longer enabled. Reconnect in AI settings.")
        return renewed
    }

    private fun publish(state: ChatGptRegistrations) {
        fun ChatGptRegistration.details() = ChatGptAccount(clientId, email ?: "ChatGPT account", planAllowed && accessToken != null)
        val old = visible.value
        val active = state.current?.details()
        visible.value = ChatGptSettings(state.subscription, active, state.accounts.map { it.details() },
            state.subscription && !state.seenNotice && active?.planAllowed == true, state.current?.selectedModel,
            old.modelChoices.takeIf { old.active?.id == active?.id && active?.planAllowed == true }.orEmpty())
    }

    private fun save(state: ChatGptRegistrations) {
        storage.save(state.json().toString())
        publish(state)
    }

    private suspend fun <T> access(action: suspend (ChatGptRegistrations) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val state = try {
                storage.read()?.let { value ->
                    val json = JSONObject(value)
                    ChatGptRegistrations(json.getString("host"), json.getBoolean("subscription"), json.optional("active"),
                        json.optJSONArray("accounts").objects().map(ChatGptRegistration::read), json.optBoolean("notice"))
                } ?: ChatGptRegistrations().also { if (storage.exists()) throw IOException() }
            } catch (_: Exception) {
                throw AiUnavailable("ChatGPT settings could not be read. Your captures are still saved.")
            }
            action(state)
        }
    }
}

private fun bearer(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")
