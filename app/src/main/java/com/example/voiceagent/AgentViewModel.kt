package com.example.voiceagent

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voiceagent.audio.AudioRecorder
import com.example.voiceagent.contacts.ContactFinder
import com.example.voiceagent.contacts.ContactMatch
import com.example.voiceagent.engine.CactusEngine
import com.example.voiceagent.engine.ToolDecision
import com.example.voiceagent.engine.ToolSchemas
import com.example.voiceagent.flashlight.FlashlightController
import com.example.voiceagent.models.DownloadProgress
import com.example.voiceagent.models.ModelDownloader
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase {
    Starting,
    Downloading,
    Loading,
    Ready,
    Listening,
    Transcribing,
    Thinking,
}

data class AgentUiState(
    val phase: Phase = Phase.Starting,
    val setupProgress: Float = 0f,
    val setupLabel: String = "Preparing…",
    val heard: String = "",
    val action: String = "",
    val status: String = "",
    val error: String? = null,
    val flashlightOn: Boolean = false,
    val contactChoices: List<ContactMatch> = emptyList(),
    val spokenName: String = "",
) {
    val busy: Boolean
        get() = phase == Phase.Transcribing || phase == Phase.Thinking
}

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = CactusEngine()
    private val recorder = AudioRecorder()
    private val contacts = ContactFinder(application)
    private val flashlight = FlashlightController(application)
    private val downloader = ModelDownloader(application.filesDir)

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var started = false
    private var torchOn = false

    init {
        start()
    }

    /** Downloads the models if needed, then loads and verifies the engine. */
    fun start() {
        if (started) return
        started = true
        _state.update {
            it.copy(
                phase = Phase.Downloading,
                setupProgress = 0f,
                setupLabel = "Checking models…",
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                val files = downloader.ensureModels(::onDownloadProgress)

                _state.update {
                    it.copy(
                        phase = Phase.Loading,
                        setupProgress = 1f,
                        setupLabel = "Loading models…",
                    )
                }
                engine.load(files.needle, files.whistle)

                if (files.downloaded) {
                    _state.update { it.copy(setupLabel = "Checking the models…") }
                    engine.selfTest()
                }

                _state.update {
                    it.copy(
                        phase = Phase.Ready,
                        setupProgress = 1f,
                        setupLabel = "",
                        status = "Ready",
                    )
                }
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        phase = Phase.Starting,
                        error = failure.message?.takeIf { message -> message.isNotBlank() }
                            ?: "Setup failed. Check the connection and try again.",
                    )
                }
            }
        }
    }

    fun retry() {
        started = false
        start()
    }

    /* ---------------------------------------------------------------- audio */

    fun startListening() {
        if (_state.value.phase != Phase.Ready) return

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            _state.update {
                it.copy(error = "Microphone access is needed. Allow it and try again.")
            }
            return
        }

        try {
            recorder.start()
        } catch (failure: Throwable) {
            _state.update { it.copy(error = failure.message ?: "Could not start recording.") }
            return
        }

        _state.update {
            it.copy(
                phase = Phase.Listening,
                heard = "",
                action = "",
                status = "Listening…",
                error = null,
            )
        }
    }

    fun stopListening() {
        if (_state.value.phase != Phase.Listening) return

        _state.update { it.copy(phase = Phase.Transcribing, status = "Transcribing…") }

        viewModelScope.launch {
            val recording = withContext(Dispatchers.Default) { recorder.stop() }
            if (recording == null) {
                _state.update {
                    it.copy(
                        phase = Phase.Ready,
                        status = "Too short — hold the button while you speak.",
                    )
                }
                return@launch
            }

            try {
                val heard = engine.transcribe(recording.samples)
                _state.update {
                    it.copy(
                        heard = heard,
                        status = if (heard.isBlank()) "I didn't catch that." else "Thinking…",
                        phase = if (heard.isBlank()) Phase.Ready else Phase.Thinking,
                    )
                }
                if (heard.isBlank()) return@launch

                handle(engine.complete(heard))
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        phase = Phase.Ready,
                        status = "",
                        error = failure.message ?: "Something went wrong.",
                    )
                }
            }
        }
    }

    /** Called when the app leaves the foreground mid-recording. */
    fun onAppStopped() {
        if (_state.value.phase != Phase.Listening) return
        viewModelScope.launch {
            withContext(Dispatchers.Default) { recorder.cancel() }
            _state.update { it.copy(phase = Phase.Ready, status = "Recording cancelled.") }
        }
    }

    /* --------------------------------------------------------------- actions */

    private suspend fun handle(decision: ToolDecision) {
        when (decision) {
            is ToolDecision.NoCall -> finish(SORRY)
            is ToolDecision.Call -> when (decision.call.name) {
                ToolSchemas.CALL_CONTACT ->
                    handleCallContact(decision.call.arguments.optString("contact_name"))

                ToolSchemas.TOGGLE_FLASHLIGHT ->
                    handleFlashlight(decision.call.arguments.optString("state"))

                else -> finish(SORRY)
            }
        }
    }

    private suspend fun handleCallContact(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            finish(SORRY)
            return
        }
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            finish("Allow contacts access so I can find someone.")
            return
        }

        val matches = contacts.find(name)
        when {
            matches.isEmpty() -> finish("No contact named \"$name\".")

            matches.size == 1 -> {
                val match = matches.first()
                if (dial(match)) {
                    finish("Dialing ${match.name}")
                } else {
                    finish("Couldn't open the dialer.")
                }
            }

            else -> _state.update {
                it.copy(
                    phase = Phase.Ready,
                    status = "Pick a number",
                    action = "",
                    contactChoices = matches,
                    spokenName = name,
                )
            }
        }
    }

    private fun handleFlashlight(rawState: String) {
        val on = when (rawState.trim().lowercase(Locale.ROOT)) {
            "on" -> true
            "off" -> false
            else -> null
        }
        if (on == null) {
            finish(SORRY)
            return
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            finish("Android needs camera access to switch the flashlight.")
            return
        }
        if (!flashlight.hasFlashlight()) {
            finish("This phone has no flashlight.")
            return
        }
        if (!flashlight.setTorch(on)) {
            finish("The flashlight couldn't be switched.")
            return
        }

        torchOn = on
        finish(if (on) "Flashlight on" else "Flashlight off", flashlightOn = on)
    }

    fun chooseContact(match: ContactMatch) {
        _state.update { it.copy(contactChoices = emptyList()) }
        if (dial(match)) {
            finish("Dialing ${match.name}")
        } else {
            finish("Couldn't open the dialer.")
        }
    }

    fun dismissContactChoices() {
        _state.update {
            it.copy(
                contactChoices = emptyList(),
                phase = Phase.Ready,
                status = "",
                action = "Cancelled",
            )
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /* --------------------------------------------------------------- helpers */

    private fun dial(match: ContactMatch): Boolean {
        val number = match.number.replace(NUMBER_NOISE, "")
        if (number.isEmpty()) return false

        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            getApplication<Application>().startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun finish(message: String, flashlightOn: Boolean = torchOn) {
        _state.update {
            it.copy(
                phase = Phase.Ready,
                status = "",
                action = message,
                flashlightOn = flashlightOn,
            )
        }
    }

    private fun onDownloadProgress(progress: DownloadProgress) {
        val fraction = if (progress.totalBytes > 0L) {
            (progress.doneBytes.toDouble() / progress.totalBytes).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val label = "${progress.label} · " +
            "${megabytes(progress.doneBytes)} / ${megabytes(progress.totalBytes)} MB"

        _state.update {
            it.copy(
                phase = Phase.Downloading,
                setupProgress = fraction,
                setupLabel = label,
            )
        }
    }

    private fun megabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SORRY = "Sorry, I can only call contacts or control the flashlight."
        val NUMBER_NOISE = Regex("[\\s()\\-.]")
    }
}
