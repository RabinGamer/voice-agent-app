package com.example.voiceagent.llm

import com.example.voiceagent.data.ProviderConfig
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A deliberately small client for the OpenAI Chat Completions shape. Nearly
 * every hosted and local provider speaks this format, so one adapter reaches
 * Gemini's compatibility endpoint, OpenRouter, Ollama, llama.cpp and the rest.
 */
class OpenAiCompatibleClient {

    suspend fun complete(provider: ProviderConfig, request: LlmRequest): LlmResponse =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", request.model)
                put("messages", JSONArray().apply {
                    request.messages.forEach { put(messageJson(it)) }
                })
                put("max_tokens", request.maxTokens)
                put("temperature", request.temperature)
                put("stream", false)
                if (request.tools.isNotEmpty()) {
                    put("tools", JSONArray().apply {
                        request.tools.forEach { tool ->
                            put(JSONObject().apply {
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                })
                            })
                        }
                    })
                }
            }

            val raw = post(provider, ProviderUrls.chat(provider), body.toString())
            parseResponse(raw, provider)
        }

    suspend fun listModels(provider: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val raw = get(provider, ProviderUrls.models(provider))
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw LlmException("${provider.name} did not return valid JSON.")
        }
        root.optJSONObject("error")?.let {
            throw LlmException("${provider.name}: ${it.optString("message", "request failed")}")
        }
        val array = root.optJSONArray("data") ?: root.optJSONArray("models")
            ?: throw LlmException("${provider.name} did not return a model list.")

        val ids = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { item.optString("name") }
            if (id.isNotBlank()) ids += id.removePrefix("models/")
        }
        ids.sorted()
    }

    private fun messageJson(message: LlmMessage): JSONObject = JSONObject().apply {
        put("role", message.role)

        when {
            message.images.isNotEmpty() -> put("content", JSONArray().apply {
                val text = message.content.ifBlank { "Look at the attached image." }
                put(JSONObject().put("type", "text").put("text", text))
                message.images.forEach { base64 ->
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", "data:image/png;base64,$base64"))
                    })
                }
            })

            message.toolCalls.isNotEmpty() && message.content.isBlank() ->
                put("content", JSONObject.NULL)

            else -> put("content", message.content)
        }

        if (message.toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                message.toolCalls.forEach { call ->
                    put(JSONObject().apply {
                        put("id", call.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", call.name)
                            put("arguments", call.arguments)
                        })
                        // Gemini's compatibility layer puts an opaque thought
                        // signature here and requires it back verbatim.
                        call.extra?.let { put("extra_content", it) }
                    })
                }
            })
            message.assistantExtra?.let { put("extra_content", it) }
        }

        message.toolCallId?.let { put("tool_call_id", it) }
    }

    private fun post(provider: ProviderConfig, url: String, body: String): String {
        val connection = open(provider, url)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { output -> output.write(bytes) }
            read(connection, provider)
        } catch (failure: LlmException) {
            throw failure
        } catch (failure: SocketTimeoutException) {
            throw LlmException(
                "${provider.name} took too long to answer (${provider.timeoutSeconds}s).",
                failure.toString(),
            )
        } catch (failure: UnknownHostException) {
            throw LlmException(
                "Couldn't reach ${provider.name}. Check the base URL and your connection.",
                failure.toString(),
            )
        } catch (failure: SSLException) {
            throw LlmException("The secure connection to ${provider.name} failed.", failure.toString())
        } catch (failure: Exception) {
            throw LlmException(
                "Couldn't reach ${provider.name}: ${failure.message ?: "network error"}",
                failure.toString(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun get(provider: ProviderConfig, url: String): String {
        val connection = open(provider, url)
        return try {
            connection.requestMethod = "GET"
            read(connection, provider)
        } catch (failure: LlmException) {
            throw failure
        } catch (failure: Exception) {
            throw LlmException(
                "Couldn't reach ${provider.name}: ${failure.message ?: "network error"}",
                failure.toString(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(provider: ProviderConfig, url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = provider.timeoutSeconds.coerceIn(10, 600) * 1000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "VoiceAgent/1.1 (Android)")

        if (provider.apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
        }
        provider.extraHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                connection.setRequestProperty(key.trim(), value.trim())
            }
        }
        return connection
    }

    private fun read(connection: HttpURLConnection, provider: ProviderConfig): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()

        if (code !in 200..299) throw httpError(code, body, provider)
        if (body.isBlank()) throw LlmException("${provider.name} returned an empty response.")
        return body
    }

    private fun httpError(code: Int, body: String, provider: ProviderConfig): LlmException {
        val detail = try {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
                ?: body.take(300)
        } catch (_: Exception) {
            body.take(300)
        }

        val message = when (code) {
            401, 403 -> "${provider.name} rejected the API key."
            404 -> "${provider.name}: the endpoint or model was not found. " +
                "Check the base URL and the model name."
            429 -> "${provider.name} is rate-limiting or out of quota. Try again shortly."
            in 500..599 -> "${provider.name} had a server error (HTTP $code)."
            else -> "${provider.name} returned HTTP $code."
        }
        return LlmException(message, "HTTP $code: $detail")
    }

    private fun parseResponse(raw: String, provider: ProviderConfig): LlmResponse {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw LlmException("${provider.name} returned something that isn't JSON.", raw.take(300))
        }

        root.optJSONObject("error")?.let {
            throw LlmException("${provider.name}: ${it.optString("message", "request failed")}")
        }

        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw LlmException("${provider.name} returned no answer.")
        }
        val choice = choices.optJSONObject(0)
            ?: throw LlmException("${provider.name} returned no answer.")
        val message = choice.optJSONObject("message") ?: JSONObject()

        val text = contentText(message.opt("content"))
        val calls = ArrayList<LlmToolCall>()

        message.optJSONArray("tool_calls")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val function = item.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isBlank()) continue
                val arguments = when (val value = function.opt("arguments")) {
                    is String -> value
                    is JSONObject -> value.toString()
                    else -> "{}"
                }
                calls += LlmToolCall(
                    id = item.optString("id").ifBlank { "call_$index" },
                    name = name,
                    arguments = arguments,
                    extra = item.optJSONObject("extra_content"),
                )
            }
        }

        if (text.isBlank() && calls.isEmpty()) {
            throw LlmException(
                "${provider.name} returned an empty answer. " +
                    "If the model has a thinking mode, switch it off.",
            )
        }

        val usage = root.optJSONObject("usage")?.let {
            LlmUsage(
                promptTokens = it.optInt("prompt_tokens"),
                completionTokens = it.optInt("completion_tokens"),
                totalTokens = it.optInt("total_tokens"),
            )
        }

        return LlmResponse(
            text = text,
            toolCalls = calls,
            finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() },
            usage = usage,
            assistantExtra = message.optJSONObject("extra_content"),
        )
    }

    private fun contentText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONObject -> value.optString("text")
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index) ?: continue
                val text = part.optString("text")
                if (text.isBlank()) continue
                if (isNotEmpty()) append('\n')
                append(text)
            }
        }
        else -> value.toString()
    }
}
