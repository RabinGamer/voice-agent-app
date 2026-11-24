package com.example.voiceagent.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Local text-to-speech. No network, no download; it uses the voice already
 * installed on the phone.
 */
class TtsSpeaker(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = engine?.setLanguage(Locale.US)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) {
                    engine?.setLanguage(Locale.getDefault())
                    ready = true
                }
            }
        }
    }

    fun speak(text: String) {
        val message = clean(text)
        if (!ready || message.isBlank()) return
        engine?.stop()
        engine?.speak(message.take(MAX_CHARS), TextToSpeech.QUEUE_FLUSH, null, "assistant")
    }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    /** Removes markdown punctuation that would be read out literally. */
    private fun clean(text: String): String = text
        .replace(Regex("[*_`#>]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val MAX_CHARS = 700
    }
}
