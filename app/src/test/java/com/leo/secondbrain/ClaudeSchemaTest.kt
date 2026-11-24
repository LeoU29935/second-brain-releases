package com.leo.secondbrain

import com.leo.secondbrain.ai.claudeSchema
import com.leo.secondbrain.ai.organizerSchema
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSchemaTest {
    private fun item(): Map<*, *> {
        val root = claudeSchema(organizerSchema)
        val suggestions = (root["properties"] as Map<*, *>)["suggestions"] as Map<*, *>
        return (suggestions["items"] as Map<*, *>)["properties"] as Map<*, *>
    }

    @Test
    fun nullableFieldsBecomeAnyOf() {
        val startDate = item()["startDate"] as Map<*, *>
        assertEquals(listOf(mapOf("type" to "string"), mapOf("type" to "null")), startDate["anyOf"])
    }

    @Test
    fun nullEnumMemberMovesToNullBranch() {
        val timeOfDay = item()["timeOfDay"] as Map<*, *>
        val branches = timeOfDay["anyOf"] as List<*>
        val text = branches[0] as Map<*, *>
        assertEquals("string", text["type"])
        assertFalse((text["enum"] as List<*>).contains(null))
        assertEquals(mapOf("type" to "null"), branches[1])
    }

    @Test
    fun objectsStayClosedAndRequired() {
        val root = claudeSchema(organizerSchema)
        assertEquals(false, root["additionalProperties"])
        assertEquals(listOf("suggestions"), root["required"])
        val required = (((root["properties"] as Map<*, *>)["suggestions"] as Map<*, *>)["items"] as Map<*, *>)["required"] as List<*>
        assertTrue("timeOfDay" in required && "kind" in required)
    }

    @Test
    fun noTypeArraysRemain() {
        fun walk(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    assertFalse("type array left in $value", value["type"] is List<*>)
                    value.values.forEach(::walk)
                }
                is List<*> -> value.forEach(::walk)
            }
        }
        walk(claudeSchema(organizerSchema))
        walk(claudeSchema(JSONObject().put("type", JSONArray(listOf("integer", "null")))))
    }
}
