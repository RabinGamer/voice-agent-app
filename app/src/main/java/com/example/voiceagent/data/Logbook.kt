package com.example.voiceagent.data

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A small in-memory diagnostics log. Nothing is written to disk unless the user
 * taps Share diagnostics, and secrets are redacted before export.
 */
class Logbook {

    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(message: String) {
        val line = "${time.format(Date())} $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun dump(header: String): String = synchronized(lines) {
        buildString {
            appendLine(header)
            appendLine()
            appendLine("--- log ---")
            lines.forEach { appendLine(redact(it)) }
        }
    }

    private fun redact(value: String): String {
        var result = value
        REDACTIONS.forEach { pattern ->
            result = pattern.replace(result, "[redacted]")
        }
        return result
    }

    private companion object {
        const val MAX_LINES = 300
        val REDACTIONS = listOf(
            Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"),
            Regex("sk-[A-Za-z0-9_-]{10,}"),
            Regex("AIza[0-9A-Za-z_-]{20,}"),
        )
    }
}
