package com.example.voiceagent.engine

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ToolCall(
    val name: String,
    val arguments: JSONObject,
)

sealed interface ToolDecision {
    data class Call(val call: ToolCall) : ToolDecision

    data class NoCall(
        val confidence: Double,
        val hadSuppressedCall: Boolean,
    ) : ToolDecision
}

class CactusEngine {
    private val mutex = Mutex()

    // The engine is process-global and only gains a family of models when a
    // matching .cact archive is loaded, so remember what already succeeded.
    private var textLoaded = false
    private var initialized = false
    private var speechLoaded = false

    /**
     * Loads Needle 3, compiles the tool grammar, then loads Whistle. The engine
     * keeps both models: one engine serves text and speech.
     */
    suspend fun load(needle: File, whistle: File) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!textLoaded) {
                val code = CactusNative.nativeLoad(needle.absolutePath)
                check(code >= 0) { "Could not load Needle 3: ${lastError()}" }
                textLoaded = true
            }

            if (!initialized) {
                val code = CactusNative.nativeInit(ToolSchemas.SYSTEM_FACTS, ToolSchemas.JSON)
                check(code >= 0) { "Could not prepare the tool schemas: ${lastError()}" }
                initialized = true
            }

            if (!speechLoaded) {
                val code = CactusNative.nativeLoad(whistle.absolutePath)
                check(code >= 0) { "Could not load Whistle: ${lastError()}" }
                speechLoaded = true
            }

            val families = CactusNative.nativeModels()
            check(
                (families and FAMILY_TEXT) != 0 && (families and FAMILY_SPEECH) != 0,
            ) {
                "The engine loaded but reports families=$families " +
                    "(expected text and speech)."
            }
        }
    }

    /**
     * Runs one fixed prompt through the loaded models. It proves both archives
     * are usable (not merely present) and that the tool grammar decodes.
     */
    suspend fun selfTest(): Boolean = try {
        val decision = complete("turn on the flashlight")
        decision is ToolDecision.Call && decision.call.name == ToolSchemas.TOGGLE_FLASHLIGHT
    } catch (_: Throwable) {
        false
    }

    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val raw = CactusNative.nativeTranscribe(samples, LANGUAGE)
                ?: error("Transcription failed: ${lastError()}")
            val json = raw.toString(Charsets.UTF_8)
            if (json.isBlank()) return@withLock ""
            JSONObject(json).optString("text", "").trim()
        }
    }

    suspend fun complete(text: String): ToolDecision = withContext(Dispatchers.Default) {
        mutex.withLock {
            // Every utterance is independent, so previous turns must not leak in.
            CactusNative.nativeReset()
            val raw = CactusNative.nativeComplete(text.toByteArray(Charsets.UTF_8), MAX_NEW_TOKENS)
                ?: error("Tool call failed: ${lastError()}")
            val json = JSONObject(raw.toString(Charsets.UTF_8))

            val calls = json.optJSONArray("function_calls")
            if (calls == null || calls.length() == 0) {
                val suppressed = json.optJSONArray("suppressed_calls")
                return@withLock ToolDecision.NoCall(
                    confidence = json.optDouble("confidence", 0.0),
                    hadSuppressedCall = suppressed != null && suppressed.length() > 0,
                )
            }

            val call = calls.getJSONObject(0)
            ToolDecision.Call(
                ToolCall(
                    name = call.optString("name", ""),
                    arguments = call.optJSONObject("arguments") ?: JSONObject(),
                ),
            )
        }
    }

    private fun lastError(): String =
        CactusNative.nativeLastError().ifBlank { "unknown engine error" }

    private companion object {
        const val LANGUAGE = "en"
        const val MAX_NEW_TOKENS = 256
        const val FAMILY_TEXT = 1
        const val FAMILY_SPEECH = 2
    }
}
