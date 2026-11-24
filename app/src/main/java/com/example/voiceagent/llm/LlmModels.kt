package com.example.voiceagent.llm

import org.json.JSONObject

data class LlmMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val images: List<String> = emptyList(),
    val assistantExtra: JSONObject? = null,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val extra: JSONObject? = null,
)

data class LlmToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolSpec> = emptyList(),
    val maxTokens: Int = 1024,
    val temperature: Double = 0.2,
)

data class LlmUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

data class LlmResponse(
    val text: String,
    val toolCalls: List<LlmToolCall>,
    val finishReason: String?,
    val usage: LlmUsage?,
    val assistantExtra: JSONObject? = null,
)

class LlmException(
    val userMessage: String,
    val detail: String = "",
) : Exception(detail.ifBlank { userMessage })
