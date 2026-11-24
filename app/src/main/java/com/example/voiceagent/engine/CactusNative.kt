package com.example.voiceagent.engine

/**
 * JNI entry points into the bundled Cactus 4 engine (libcactus.so, built from
 * the official android-arm64 libcactus.a).
 *
 * All calls are process-global and must be serialized by the caller.
 */
object CactusNative {
    init {
        System.loadLibrary("cactus")
    }

    external fun nativeLoad(path: String): Int

    external fun nativeModels(): Int

    external fun nativeInit(systemPrompt: String, toolsJson: String): Int

    external fun nativeComplete(input: ByteArray, maxNewTokens: Int): ByteArray?

    external fun nativeTranscribe(pcm: FloatArray, language: String?): ByteArray?

    external fun nativeReset()

    external fun nativeLastError(): String
}
