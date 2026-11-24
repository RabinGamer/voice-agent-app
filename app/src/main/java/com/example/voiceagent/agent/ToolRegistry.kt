package com.example.voiceagent.agent

import com.example.voiceagent.engine.ToolSchemas
import com.example.voiceagent.llm.LlmToolSpec
import org.json.JSONArray
import org.json.JSONObject

object ToolRegistry {

    val specs: List<LlmToolSpec> by lazy { parse() }

    fun activityLabel(name: String, arguments: JSONObject): String = when (name) {
        ToolSchemas.CALL_CONTACT ->
            "Finding ${arguments.optString("contact_name").ifBlank { "contact" }}"

        ToolSchemas.TOGGLE_FLASHLIGHT ->
            if (arguments.optString("state").equals("off", ignoreCase = true)) {
                "Turning the flashlight off"
            } else {
                "Turning the flashlight on"
            }

        ToolSchemas.OPEN_APP ->
            "Opening ${arguments.optString("app_name").ifBlank { "app" }}"

        ToolSchemas.SET_TIMER -> "Setting a timer"
        ToolSchemas.SET_ALARM -> "Setting an alarm"
        else -> "Working"
    }

    private fun parse(): List<LlmToolSpec> {
        val array = JSONArray(ToolSchemas.JSON)
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val name = json.optString("name")
            if (name.isBlank()) return@mapNotNull null
            LlmToolSpec(
                name = name,
                description = json.optString("description"),
                parameters = json.optJSONObject("parameters") ?: EMPTY_PARAMETERS,
            )
        }
    }

    private val EMPTY_PARAMETERS = JSONObject(
        """{"type":"object","properties":{}}""",
    )
}
