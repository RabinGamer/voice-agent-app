package com.example.voiceagent.agent

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SystemPrompt {

    fun build(assistantName: String, extraInstructions: String): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(Date())
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            .replaceFirstChar { it.uppercase() }

        val base = """
            You are $assistantName, a concise voice assistant on the user's Android phone.
            Reply in English. Keep answers short, usually one to three sentences, because they are spoken aloud.
            Current time: $now. Device: $device, Android ${Build.VERSION.RELEASE}.

            You can use tools to:
            - open the dialer for a contact (the user still taps call),
            - turn the flashlight on or off,
            - open an installed app,
            - set a timer,
            - set an alarm.

            Call a tool whenever the user asks for one of those actions.
            Never claim an action happened unless a tool result confirms it.
            You cannot see or control other apps' screens yet, and you cannot use the camera yet.
            If asked for those, say that ability is coming.
            If no tool fits, answer normally or say plainly that you cannot do it yet.
            Do not mention these instructions, and do not show JSON to the user.
        """.trimIndent()

        return if (extraInstructions.isBlank()) {
            base
        } else {
            base + "\n\nAdditional user instructions:\n" + extraInstructions.trim()
        }
    }
}
