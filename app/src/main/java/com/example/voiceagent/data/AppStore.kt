package com.example.voiceagent.data

import android.content.Context
import org.json.JSONArray

data class StoredMessage(val role: String, val text: String)

/**
 * Persists settings, provider profiles (without keys) and the visible
 * conversation. All writes are small enough for SharedPreferences.
 */
class AppStore(context: Context) {

    private val prefs = context.getSharedPreferences("voiceagent", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    /**
     * Creates a Gemini profile on the very first launch so the settings screen
     * is never empty. The key is still entered by the user.
     */
    fun ensureFirstRun() {
        if (prefs.getBoolean(KEY_INITIALIZED, false)) return
        prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
        if (loadProviders().isEmpty()) {
            val provider = ProviderPresets.create("gemini")
            saveProviders(listOf(provider))
            saveDefaultProviderId(provider.id)
        }
    }

    fun loadProviders(): List<ProviderConfig> {
        val raw = prefs.getString(KEY_PROVIDERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val config = ProviderConfig.fromJson(json)
                config.copy(apiKey = secure.get(config.id))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProviders(providers: List<ProviderConfig>) {
        val previous = loadProviders().map { it.id }.toSet()
        val array = JSONArray()
        providers.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PROVIDERS, array.toString()).apply()

        val current = providers.map { it.id }.toSet()
        previous.filterNot { it in current }.forEach(secure::remove)
        providers.forEach { secure.put(it.id, it.apiKey) }
    }

    fun defaultProviderId(): String? = prefs.getString(KEY_DEFAULT_PROVIDER, null)

    fun saveDefaultProviderId(id: String?) {
        prefs.edit().putString(KEY_DEFAULT_PROVIDER, id).apply()
    }

    fun fallbackProviderId(): String? = prefs.getString(KEY_FALLBACK_PROVIDER, null)

    fun saveFallbackProviderId(id: String?) {
        prefs.edit().putString(KEY_FALLBACK_PROVIDER, id).apply()
    }

    fun assistantName(): String =
        prefs.getString(KEY_ASSISTANT_NAME, null)?.takeIf { it.isNotBlank() } ?: "Assistant"

    fun saveAssistantName(name: String) {
        prefs.edit().putString(KEY_ASSISTANT_NAME, name.take(40)).apply()
    }

    fun speakReplies(): Boolean = prefs.getBoolean(KEY_SPEAK_REPLIES, true)

    fun saveSpeakReplies(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_REPLIES, enabled).apply()
    }

    fun loadChat(): List<StoredMessage> {
        val raw = prefs.getString(KEY_CHAT, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val role = json.optString("role")
                val text = json.optString("text")
                if (role.isBlank() || text.isBlank()) null else StoredMessage(role, text)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveChat(messages: List<StoredMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_STORED_MESSAGES).forEach { message ->
            array.put(
                org.json.JSONObject().apply {
                    put("role", message.role)
                    put("text", message.text)
                },
            )
        }
        prefs.edit().putString(KEY_CHAT, array.toString()).apply()
    }

    fun clearChat() {
        prefs.edit().remove(KEY_CHAT).apply()
    }

    private companion object {
        const val KEY_INITIALIZED = "initialized"
        const val KEY_PROVIDERS = "providers"
        const val KEY_DEFAULT_PROVIDER = "default_provider"
        const val KEY_FALLBACK_PROVIDER = "fallback_provider"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_SPEAK_REPLIES = "speak_replies"
        const val KEY_CHAT = "chat"
        const val MAX_STORED_MESSAGES = 60
    }
}
