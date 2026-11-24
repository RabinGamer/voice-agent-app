package com.example.voiceagent.data

import java.util.UUID
import org.json.JSONObject

/**
 * One OpenAI-compatible endpoint. The API key is kept out of [toJson] and lives
 * in [SecureStore] instead.
 */
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val presetId: String = "custom",
    val extraHeaders: Map<String, String> = emptyMap(),
    val maxTokens: Int = 1024,
    val timeoutSeconds: Int = 120,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val requiresKey: Boolean = true,
    val allowSensitive: Boolean = false,
    val extraInstructions: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("model", model)
        put("presetId", presetId)
        put("maxTokens", maxTokens)
        put("timeoutSeconds", timeoutSeconds)
        put("supportsTools", supportsTools)
        put("supportsVision", supportsVision)
        put("requiresKey", requiresKey)
        put("allowSensitive", allowSensitive)
        put("extraInstructions", extraInstructions)
        put(
            "extraHeaders",
            JSONObject().apply {
                extraHeaders.forEach { (key, value) -> put(key, value) }
            },
        )
    }

    companion object {
        fun fromJson(json: JSONObject): ProviderConfig = ProviderConfig(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"),
            baseUrl = json.optString("baseUrl"),
            model = json.optString("model"),
            presetId = json.optString("presetId", "custom"),
            extraHeaders = json.optJSONObject("extraHeaders")?.let { headers ->
                buildMap<String, String> {
                    headers.keys().forEach { key ->
                        put(key, headers.optString(key))
                    }
                }
            } ?: emptyMap(),
            maxTokens = json.optInt("maxTokens", 1024).coerceIn(64, 8192),
            timeoutSeconds = json.optInt("timeoutSeconds", 120).coerceIn(10, 600),
            supportsTools = json.optBoolean("supportsTools", true),
            supportsVision = json.optBoolean("supportsVision", false),
            requiresKey = json.optBoolean("requiresKey", true),
            allowSensitive = json.optBoolean("allowSensitive", false),
            extraInstructions = json.optString("extraInstructions"),
        )
    }
}

object ProviderPresets {

    data class Preset(
        val id: String,
        val name: String,
        val baseUrl: String,
        val model: String,
        val requiresKey: Boolean,
        val supportsVision: Boolean,
        val blurb: String,
    )

    val gemini = Preset(
        id = "gemini",
        name = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        model = "gemini-3.5-flash-lite",
        requiresKey = true,
        supportsVision = true,
        blurb = "Free tier. Gemini Flash-Lite is fast and good at tool calls.",
    )

    val all = listOf(
        gemini,
        Preset(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openrouter/auto",
            requiresKey = true,
            supportsVision = true,
            blurb = "One key for many models, including free ones.",
        ),
        Preset(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "",
            requiresKey = true,
            supportsVision = false,
            blurb = "Very fast hosted models. Use Fetch models to pick one.",
        ),
        Preset(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            requiresKey = true,
            supportsVision = false,
            blurb = "Low-cost chat models.",
        ),
        Preset(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "",
            requiresKey = true,
            supportsVision = true,
            blurb = "The official OpenAI endpoint.",
        ),
        Preset(
            id = "ollama",
            name = "Ollama (LAN)",
            baseUrl = "http://192.168.1.100:11434/v1",
            model = "",
            requiresKey = false,
            supportsVision = true,
            blurb = "Ollama on a PC or another device on your Wi-Fi.",
        ),
        Preset(
            id = "lmstudio",
            name = "LM Studio (LAN)",
            baseUrl = "http://192.168.1.100:1234/v1",
            model = "",
            requiresKey = false,
            supportsVision = true,
            blurb = "LM Studio's local OpenAI-compatible server.",
        ),
        Preset(
            id = "llamacpp",
            name = "llama.cpp (this phone)",
            baseUrl = "http://127.0.0.1:8080/v1",
            model = "",
            requiresKey = false,
            supportsVision = false,
            blurb = "Run llama-server in Termux. Private and fully offline.",
        ),
        Preset(
            id = "custom",
            name = "Custom endpoint",
            baseUrl = "",
            model = "",
            requiresKey = false,
            supportsVision = false,
            blurb = "Any OpenAI-compatible API, including a self-hosted gateway.",
        ),
    )

    fun byId(id: String): Preset = all.firstOrNull { it.id == id } ?: all.last()

    fun create(id: String = "gemini"): ProviderConfig {
        val preset = byId(id)
        return ProviderConfig(
            name = preset.name,
            baseUrl = preset.baseUrl,
            model = preset.model,
            presetId = preset.id,
            requiresKey = preset.requiresKey,
            supportsVision = preset.supportsVision,
        )
    }
}
