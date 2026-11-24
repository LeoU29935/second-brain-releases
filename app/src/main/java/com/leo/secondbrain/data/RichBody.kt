package com.leo.secondbrain.data

import org.json.JSONArray
import org.json.JSONObject

internal enum class BodyFormat { BOLD, ITALIC, HEADING, SMALL, LARGE, ACCENT, WARM, COOL }

internal data class BodySpan(val start: Int, val end: Int, val format: BodyFormat)

internal data class RichBody(val text: String, val spans: List<BodySpan> = emptyList()) {
    fun encode(): String {
        if (spans.isEmpty()) return text
        return PREFIX + JSONObject().put("text", text).put("spans", JSONArray(spans.map {
            JSONArray(listOf(it.start, it.end, it.format.name))
        })).toString()
    }

    fun replace(start: Int, end: Int, replacement: String): RichBody {
        if (text.substring(start, end) == replacement) return this
        val delta = replacement.length - (end - start)
        val updated = spans.mapNotNull { span ->
            val from = when {
                span.start >= end && span.start > start -> span.start + delta
                span.start > start -> start
                else -> span.start
            }
            val through = when {
                span.end >= end && span.end > start -> span.end + delta
                span.end > start -> start + replacement.length
                else -> span.end
            }
            span.copy(start = from, end = through).takeIf { from < through }
        }
        return RichBody(text.replaceRange(start, end, replacement), updated)
    }

    fun withText(updated: String): RichBody {
        if (text == updated) return this
        var start = 0
        val limit = minOf(text.length, updated.length)
        while (start < limit && text[start] == updated[start]) start++
        var before = text.length
        var after = updated.length
        while (before > start && after > start && text[before - 1] == updated[after - 1]) {
            before--
            after--
        }
        return replace(start, before, updated.substring(start, after))
    }

    fun format(start: Int, end: Int, format: BodyFormat?, family: Set<BodyFormat>): RichBody {
        if (start == end) return this
        val remove = spans.any { it.format == format && it.start <= start && it.end >= end } &&
            format in setOf(BodyFormat.BOLD, BodyFormat.ITALIC)
        val retained = spans.flatMap { span ->
            if (span.format !in family || span.end <= start || span.start >= end) listOf(span)
            else listOfNotNull(span.copy(end = start).takeIf { span.start < start },
                span.copy(start = end).takeIf { span.end > end })
        }
        return copy(spans = if (format == null || remove) retained else retained + BodySpan(start, end, format))
    }

    companion object {
        private const val PREFIX = "\u001eSB-RICH-1\n"

        fun read(encoded: String): RichBody {
            if (!encoded.startsWith(PREFIX)) return RichBody(encoded)
            return runCatching {
                val data = JSONObject(encoded.removePrefix(PREFIX))
                val text = data.getString("text")
                val array = data.getJSONArray("spans")
                RichBody(text, (0 until array.length()).mapNotNull { index ->
                    val span = array.getJSONArray(index)
                    val start = span.getInt(0)
                    val end = span.getInt(1)
                    val format = BodyFormat.entries.firstOrNull { it.name == span.getString(2) }
                    format?.takeIf { start in 0 until end && end <= text.length }?.let { BodySpan(start, end, it) }
                })
            }.getOrElse { RichBody(encoded) }
        }
    }
}

internal fun String.plainBody(): String = RichBody.read(this).text
