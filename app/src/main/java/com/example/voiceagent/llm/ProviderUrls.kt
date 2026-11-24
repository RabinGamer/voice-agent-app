package com.example.voiceagent.llm

import com.example.voiceagent.data.ProviderConfig
import java.net.URI

object ProviderUrls {

    fun chat(provider: ProviderConfig): String = endpoint(provider.baseUrl, "chat/completions")

    fun models(provider: ProviderConfig): String = endpoint(provider.baseUrl, "models")

    private fun endpoint(base: String, suffix: String): String {
        var value = base.trim().trimEnd('/')
        if (value.endsWith("/$suffix")) return value
        if (suffix == "models" && value.endsWith("/chat/completions")) {
            value = value.removeSuffix("/chat/completions")
        }
        return "$value/$suffix"
    }

    /** Returns a user-facing error, or null when the URL is acceptable. */
    fun validate(baseUrl: String): String? {
        val raw = baseUrl.trim()
        if (raw.isEmpty()) return "Enter a base URL."
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return "That base URL is not valid."
        }
        val scheme = uri.scheme?.lowercase()
            ?: return "Include https:// or http:// in the base URL."
        if (scheme != "http" && scheme != "https") {
            return "Only http:// and https:// are supported."
        }
        val host = uri.host?.removeSurrounding("[", "]")
            ?: return "That base URL has no host."
        if (scheme == "http" && !isLocalHost(host)) {
            return "Plain http:// is allowed only for this phone or your local network. " +
                "Use https:// for internet providers."
        }
        return null
    }

    fun isLocalHost(host: String): Boolean {
        val value = host.trim().lowercase().removeSurrounding("[", "]")
        if (value == "localhost" || value == "::1" || value == "10.0.2.2") return true
        if (value.endsWith(".local")) return true

        val parts = value.split('.')
        if (parts.size != 4) return false
        val numbers = parts.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return numbers[0] == 10 ||
            (numbers[0] == 172 && numbers[1] in 16..31) ||
            (numbers[0] == 192 && numbers[1] == 168) ||
            numbers[0] == 127
    }

    fun hostLabel(baseUrl: String): String = try {
        URI(baseUrl.trim()).host ?: baseUrl
    } catch (_: Exception) {
        baseUrl
    }
}
