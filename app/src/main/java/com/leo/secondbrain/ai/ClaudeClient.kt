package com.leo.secondbrain.ai

import android.content.Context
import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.ServiceUnavailableException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ClaudeModel(val id: String, val label: String, val serverFallback: Boolean)

internal object ClaudeModels {
    val choices = listOf(
        ClaudeModel("claude-opus-5-5", "Opus 5.5", serverFallback = true),
        ClaudeModel("claude-sonnet-5-5", "Sonnet 5.5", serverFallback = true),
        ClaudeModel("claude-haiku-5-5", "Haiku 5.5", serverFallback = false)
    )
    val default = choices.first()
    fun find(id: String?) = choices.firstOrNull { it.id == id } ?: default
}

internal data class ClaudeSettings(
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val model: ClaudeModel = ClaudeModels.default,
    val busy: Boolean = false,
    val message: String? = null
) {
    val active get() = configured && enabled
}

/** Organizes captures with the user's own Anthropic API key. The key is encrypted with Android Keystore. */
internal class ClaudeClient(context: Context) {
    private val key = ProtectedValueStore(context, "claude.credential", "com.leo.secondbrain.claude.v1")
    private val preferences = context.getSharedPreferences("claude", Context.MODE_PRIVATE)
    private val visible = MutableStateFlow(readSettings())
    val settings = visible.asStateFlow()
    private var client: Pair<String, AnthropicClientAsync>? = null

    val active get() = visible.value.active

    fun saveKey(value: String) {
        val trimmed = value.trim()
        require(trimmed.matches(Regex("sk-ant-[A-Za-z0-9_-]{20,480}")))
        key.save(trimmed)
        preferences.edit().putBoolean("enabled", true).apply()
        refresh()
    }

    fun removeKey() {
        key.remove()
        synchronized(this) { client = null }
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("enabled", enabled).apply()
        refresh()
    }

    fun selectModel(id: String) {
        preferences.edit().putString("model", ClaudeModels.find(id).id).apply()
        refresh()
    }

    fun report(message: String?, busy: Boolean = false) {
        visible.value = visible.value.copy(message = message, busy = busy)
    }

    suspend fun organize(instructions: String, input: JSONObject, schema: JSONObject): JSONObject {
        val anthropic = withContext(Dispatchers.IO) { client() }
        return claudeOrganize(anthropic, visible.value.model, instructions, input, schema)
    }

    @Synchronized private fun client(): AnthropicClientAsync {
        val secret = runCatching { key.read() }.getOrElse {
            throw AiUnavailable("The stored Claude key could not be read. Replace it in AI settings.")
        } ?: throw AiUnavailable("Your capture is saved. Add a Claude API key in AI settings.")
        client?.takeIf { it.first == secret }?.let { return it.second }
        client?.second?.close()
        return AnthropicOkHttpClientAsync.builder().apiKey(secret)
            .timeout(Duration.ofSeconds(180)).maxRetries(2).build()
            .also { client = secret to it }
    }

    private fun refresh() {
        visible.value = readSettings().copy(busy = visible.value.busy, message = visible.value.message)
    }

    private fun readSettings() = ClaudeSettings(
        configured = key.exists(),
        enabled = preferences.getBoolean("enabled", false),
        model = ClaudeModels.find(preferences.getString("model", null))
    )
}

internal suspend fun claudeOrganize(
    anthropic: AnthropicClientAsync, model: ClaudeModel, instructions: String, input: JSONObject, schema: JSONObject
): JSONObject {
    val params = MessageCreateParams.builder()
        .model(model.id)
        .maxTokens(16_000L)
        .system(instructions)
        .addUserMessage(input.toString())
        .outputConfig(BetaOutputConfig.builder()
            .effort(BetaOutputConfig.Effort.MEDIUM)
            .format(BetaJsonOutputFormat.builder()
                .schema(BetaJsonOutputFormat.Schema.builder()
                    .putAllAdditionalProperties(claudeSchema(schema).mapValues { JsonValue.from(it.value) })
                    .build())
                .build())
            .build())
        .apply {
            // Opus and Sonnet can hand a declined request to another model server-side. Haiku has no fallback.
            if (model.serverFallback) addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacksDefault()
        }
        .build()
    val message = try {
        anthropic.beta().messages().create(params).await()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw claudeError(error)
    }
    when (message.stopReason().orElse(null)) {
        BetaStopReason.REFUSAL -> throw AiUnavailable("Claude declined to organize this capture. Your transcript is saved.")
        BetaStopReason.MAX_TOKENS -> throw AiUnavailable("Claude returned an incomplete result. Retry this capture.", true)
        else -> Unit
    }
    val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    if (text.isBlank()) throw AiUnavailable("Claude returned no usable suggestions.")
    return try {
        JSONObject(text)
    } catch (_: Exception) {
        throw AiUnavailable("Claude returned an unreadable response. Retry this capture.", true)
    }
}

private fun claudeError(error: Exception): AiUnavailable = when (error) {
    is UnauthorizedException -> AiUnavailable("Claude rejected this API key. Check it in AI settings.")
    is PermissionDeniedException -> AiUnavailable("This Claude API key isn’t allowed to use the selected model.")
    is NotFoundException -> AiUnavailable("The selected Claude model isn’t available to this key. Choose another in AI settings.")
    is RateLimitException -> AiUnavailable("Claude rate limit reached. Retry this capture later.", true)
    is OverloadedException, is InternalServerException, is ServiceUnavailableException ->
        AiUnavailable("Claude is temporarily unavailable. Retry this capture later.", true)
    is BadRequestException -> AiUnavailable(
        if (error.message.orEmpty().contains("credit balance", ignoreCase = true))
            "Your Anthropic credit balance is too low. Add credits in the Claude Console, then retry."
        else "Claude couldn’t process this request. Your transcript is saved."
    )
    is AnthropicServiceException -> AiUnavailable("Claude couldn’t process this request (HTTP ${error.statusCode()}).", error.statusCode() >= 500)
    is AnthropicIoException -> AiUnavailable("Claude could not be reached. Check your connection and retry.", true)
    is AiUnavailable -> error
    else -> AiUnavailable("Claude returned an unreadable response. Retry this capture.", true)
}

/**
 * Converts the organizer schema to plain maps for Claude structured outputs. Nullable fields written as
 * `"type": [t, "null"]` become `anyOf` alternatives, and a `null` enum member becomes its own `null` branch.
 */
internal fun claudeSchema(schema: JSONObject): Map<String, Any?> = convertSchema(schema)

private fun convertSchema(node: JSONObject): Map<String, Any?> {
    val type = node.opt("type")
    if (type is JSONArray && (0 until type.length()).any { type.opt(it) == "null" }) {
        val others = (0 until type.length()).map { type.getString(it) }.filterNot { it == "null" }
        val base = JSONObject(node.toString()).apply {
            if (others.size == 1) put("type", others.single()) else put("type", JSONArray(others))
            optJSONArray("enum")?.let { values ->
                put("enum", JSONArray((0 until values.length()).map { values.get(it) }.filterNot { it == JSONObject.NULL }))
            }
        }
        return mapOf("anyOf" to listOf(convertSchema(base), mapOf("type" to "null")))
    }
    return node.keys().asSequence().associateWith { key -> plain(node.get(key), key) }
}

private fun plain(value: Any?, key: String): Any? = when {
    value == JSONObject.NULL -> null
    value is JSONObject && key == "properties" -> value.keys().asSequence().associateWith { convertSchema(value.getJSONObject(it)) }
    value is JSONObject && key == "items" -> convertSchema(value)
    value is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it), it) }
    value is JSONArray -> (0 until value.length()).map { plain(value.get(it), "") }
    else -> value
}
