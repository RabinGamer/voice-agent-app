package com.example.voiceagent.models

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ModelSpec(
    val displayName: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class ModelFiles(
    val needle: File,
    val whistle: File,
    val downloaded: Boolean,
)

data class DownloadProgress(
    val label: String,
    val doneBytes: Long,
    val totalBytes: Long,
)

object Models {
    // Pinned revisions keep the download reproducible.
    val NEEDLE = ModelSpec(
        displayName = "Needle 3",
        fileName = "needle3.cact",
        url = "https://huggingface.co/Cactus-Compute/needle3/resolve/" +
            "c95d25b4efa1e9601e785d92438e4ade0dbddbf8/needle3.cact",
        sha256 = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38",
        sizeBytes = 35_335_380L,
    )

    val WHISTLE = ModelSpec(
        displayName = "Whistle",
        fileName = "whistle.cact",
        url = "https://huggingface.co/Cactus-Compute/whistle/resolve/" +
            "88cb00eeb8928ddbcd410b491584f938ba030b07/whistle.cact",
        sha256 = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb",
        sizeBytes = 16_919_407L,
    )
}

class ModelDownloader(private val rootDir: File) {

    suspend fun ensureModels(onProgress: (DownloadProgress) -> Unit): ModelFiles =
        withContext(Dispatchers.IO) {
            val directory = File(rootDir, "models").apply { mkdirs() }
            if (!directory.isDirectory) {
                throw IOException("Could not create the model directory")
            }

            val specs = listOf(Models.NEEDLE, Models.WHISTLE)
            val total = specs.sumOf { it.sizeBytes }
            var completed = 0L
            var downloaded = false

            for (spec in specs) {
                val target = File(directory, spec.fileName)
                if (isComplete(target, spec)) {
                    completed += spec.sizeBytes
                    onProgress(DownloadProgress(spec.displayName, completed, total))
                    continue
                }

                val part = File(directory, spec.fileName + ".part")
                if (part.exists()) part.delete()
                if (directory.usableSpace < spec.sizeBytes + MIN_FREE_BYTES) {
                    throw IOException(
                        "Not enough free storage for ${spec.displayName}. " +
                            "Free up about ${spec.sizeBytes / (1024 * 1024) + 32} MB and retry.",
                    )
                }

                download(spec, part, completed, total, onProgress)
                downloaded = true

                if (part.length() != spec.sizeBytes) {
                    val length = part.length()
                    part.delete()
                    throw IOException(
                        "${spec.displayName} download is $length bytes, " +
                            "expected ${spec.sizeBytes}",
                    )
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                File(directory, spec.fileName + ".ok").writeText(spec.sha256)
                completed += spec.sizeBytes
                onProgress(DownloadProgress(spec.displayName, completed, total))
            }

            ModelFiles(
                needle = File(directory, Models.NEEDLE.fileName),
                whistle = File(directory, Models.WHISTLE.fileName),
                downloaded = downloaded,
            )
        }

    private fun isComplete(file: File, spec: ModelSpec): Boolean {
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        val marker = File(file.parentFile, spec.fileName + ".ok")
        return marker.isFile && marker.readText().trim().equals(spec.sha256, ignoreCase = true)
    }

    private fun download(
        spec: ModelSpec,
        part: File,
        completedBytes: Long,
        totalBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val connection = URI(spec.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "VoiceAgent/1.0 (Android)")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("${spec.displayName}: server returned HTTP $code")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastReported = -1L

            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw IOException("Download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        received += read
                        if (received - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = received
                            onProgress(
                                DownloadProgress(
                                    spec.displayName,
                                    completedBytes + received,
                                    totalBytes,
                                ),
                            )
                        }
                    }
                }
            }

            val actual = digest.digest().toHex()
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                throw IOException(
                    "${spec.displayName} failed its checksum. " +
                        "Delete the app's data and try again.",
                )
            }
        } catch (failure: Throwable) {
            part.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
        }
    }

    private companion object {
        const val PROGRESS_STEP_BYTES = 256L * 1024L
        const val MIN_FREE_BYTES = 16L * 1024L * 1024L
    }
}
