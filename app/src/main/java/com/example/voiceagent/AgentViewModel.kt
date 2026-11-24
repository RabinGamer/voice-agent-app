package com.example.voiceagent

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voiceagent.agent.SystemPrompt
import com.example.voiceagent.agent.ToolExecutor
import com.example.voiceagent.agent.ToolOutcome
import com.example.voiceagent.agent.ToolRegistry
import com.example.voiceagent.audio.AudioRecorder
import com.example.voiceagent.contacts.ContactFinder
import com.example.voiceagent.contacts.ContactMatch
import com.example.voiceagent.data.AppStore
import com.example.voiceagent.data.Logbook
import com.example.voiceagent.data.ProviderConfig
import com.example.voiceagent.data.StoredMessage
import com.example.voiceagent.engine.CactusEngine
import com.example.voiceagent.engine.ToolDecision
import com.example.voiceagent.flashlight.FlashlightController
import com.example.voiceagent.llm.LlmException
import com.example.voiceagent.llm.LlmMessage
import com.example.voiceagent.llm.LlmRequest
import com.example.voiceagent.llm.LlmToolCall
import com.example.voiceagent.llm.LlmToolSpec
import com.example.voiceagent.llm.OpenAiCompatibleClient
import com.example.voiceagent.llm.ProviderUrls
import com.example.voiceagent.models.DownloadProgress
import com.example.voiceagent.models.ModelDownloader
import com.example.voiceagent.speech.TtsSpeaker
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Phase {
    Starting,
    Downloading,
    Loading,
    Ready,
    Listening,
    Transcribing,
    Thinking,
}

enum class ChatRole { User, Assistant, Action, Error }

data class ChatItem(
    val id: Long,
    val role: ChatRole,
    val text: String,
)

sealed interface ProviderTestState {
    data object Idle : ProviderTestState
    data object Running : ProviderTestState
    data class Done(val report: String, val ok: Boolean) : ProviderTestState
}

data class AgentUiState(
    val phase: Phase = Phase.Starting,
    val setupProgress: Float = 0f,
    val setupLabel: String = "Preparing…",
    val error: String? = null,
    val messages: List<ChatItem> = emptyList(),
    val draft: String = "",
    val assistantName: String = "Assistant",
    val speakReplies: Boolean = true,
    val providers: List<ProviderConfig> = emptyList(),
    val defaultProviderId: String? = null,
    val fallbackProviderId: String? = null,
    val providerLabel: String = "Offline reflexes",
    val toolActivity: String? = null,
    val contactChoices: List<ContactMatch> = emptyList(),
    val spokenName: String = "",
    val providerTest: ProviderTestState = ProviderTestState.Idle,
    val providerTestFor: String? = null,
    val modelChoices: List<String> = emptyList(),
    val modelsFor: String? = null,
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
) {
    val busy: Boolean
        get() = phase == Phase.Transcribing || phase == Phase.Thinking

    val ready: Boolean
        get() = phase == Phase.Ready ||
            phase == Phase.Listening ||
            phase == Phase.Transcribing ||
            phase == Phase.Thinking
}

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = CactusEngine()
    private val recorder = AudioRecorder()
    private val contacts = ContactFinder(application)
    private val flashlight = FlashlightController(application)
    private val downloader = ModelDownloader(application.filesDir)
    private val store = AppStore(application)
    private val tts = TtsSpeaker(application)
    private val log = Logbook()
    private val tools = ToolExecutor(application, contacts, flashlight)
    private val client = OpenAiCompatibleClient()

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var started = false
    private var requestJob: Job? = null
    private var testJob: Job? = null
    private var modelsJob: Job? = null
    private val history = mutableListOf<LlmMessage>()
    private val messageIds = AtomicLong(1)

    init {
        store.ensureFirstRun()

        val providers = store.loadProviders()
        val defaultId = store.defaultProviderId()
            ?.takeIf { id -> providers.any { it.id == id } }
            ?: providers.firstOrNull()?.id
        val fallbackId = store.fallbackProviderId()
            ?.takeIf { id -> providers.any { provider -> provider.id == id && id != defaultId } }

        val stored = store.loadChat()
        val restored = stored.map { message ->
            ChatItem(
                id = nextId(),
                role = if (message.role == "user") ChatRole.User else ChatRole.Assistant,
                text = message.text,
            )
        }
        history += stored.map { message ->
            LlmMessage(role = message.role, content = message.text)
        }

        _state.value = _state.value.copy(
            messages = restored,
            assistantName = store.assistantName(),
            speakReplies = store.speakReplies(),
            providers = providers,
            defaultProviderId = defaultId,
            fallbackProviderId = fallbackId,
            providerLabel = labelFor(providers, defaultId),
        )

        log.add("started; providers=${providers.size}; default=${defaultId?.take(8)}")
        start()
    }

    /** Downloads the offline models if needed, then loads and verifies them. */
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
                    )
                }
                log.add("offline models ready")
            } catch (failure: Throwable) {
                log.add("setup failed: ${failure.message}")
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
            addAction("Microphone access is needed. Allow it and try again.")
            return
        }

        tts.stop()
        try {
            recorder.start()
        } catch (failure: Throwable) {
            addError(failure.message ?: "Could not start recording.")
            return
        }

        _state.update { it.copy(phase = Phase.Listening) }
    }

    fun stopListening() {
        if (_state.value.phase != Phase.Listening) return

        _state.update { it.copy(phase = Phase.Transcribing) }

        viewModelScope.launch {
            val recording = withContext(Dispatchers.Default) { recorder.stop() }
            if (recording == null) {
                _state.update { it.copy(phase = Phase.Ready) }
                addAction("Too short — hold the button while you speak.")
                return@launch
            }

            try {
                val heard = engine.transcribe(recording.samples).trim()
                if (heard.isBlank()) {
                    _state.update { it.copy(phase = Phase.Ready) }
                    addAction("I didn't catch that.")
                    return@launch
                }
                submit(heard)
            } catch (failure: Throwable) {
                _state.update { it.copy(phase = Phase.Ready) }
                addError(failure.message ?: "Transcription failed.")
            }
        }
    }

    fun onAppStopped() {
        tts.stop()
        if (_state.value.phase == Phase.Listening) {
            viewModelScope.launch {
                withContext(Dispatchers.Default) { recorder.cancel() }
                _state.update { it.copy(phase = Phase.Ready) }
            }
        }
    }

    /* --------------------------------------------------------------- turns */

    fun sendMessage(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _state.value.phase != Phase.Ready) return
        submit(text)
    }

    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value.take(1000)) }
    }

    private fun submit(text: String) {
        tts.stop()
        addChat(ChatRole.User, text)
        history += LlmMessage(role = "user", content = text)
        store.saveChat(chatForStore())

        _state.update {
            it.copy(phase = Phase.Thinking, toolActivity = null, draft = "", error = null)
        }

        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            try {
                answer(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                log.add("turn failed: ${failure.message}")
                addError(failure.message ?: "Something went wrong.")
            } finally {
                dropIncompleteToolTail()
                if (_state.value.phase == Phase.Thinking) {
                    _state.update { it.copy(phase = Phase.Ready, toolActivity = null) }
                }
            }
        }
    }

    fun stopThinking() {
        requestJob?.cancel()
        requestJob = null
        tts.stop()
        dropIncompleteToolTail()
        _state.update { it.copy(phase = Phase.Ready, toolActivity = null) }
    }

    private suspend fun answer(text: String) {
        val current = _state.value
        val primary = current.providers.firstOrNull { it.id == current.defaultProviderId }
            ?.takeIf(::usable)

        if (primary != null) {
            val first = tryProvider(primary)
            if (first is ProviderResult.Done) return
            if (first is ProviderResult.Paused) return

            val failure = (first as ProviderResult.Failed).error
            log.add("provider ${primary.name} failed: ${failure.userMessage} / ${failure.detail}")

            val fallback = current.providers.firstOrNull { it.id == current.fallbackProviderId }
                ?.takeIf { it.id != primary.id && usable(it) }
            if (fallback != null) {
                val second = tryProvider(fallback)
                if (second is ProviderResult.Done) return
                if (second is ProviderResult.Paused) return
            }

            if (runOffline(text)) {
                addAction("Offline fallback: ${failure.userMessage}")
            } else {
                addError(failure.userMessage + " " + OFFLINE_HINT)
            }
            return
        }

        if (!runOffline(text)) {
            addAssistant(NO_PROVIDER)
        }
    }

    private suspend fun tryProvider(provider: ProviderConfig): ProviderResult {
        var steps = 0
        while (steps < MAX_STEPS) {
            steps++
            val request = LlmRequest(
                model = provider.model,
                messages = buildMessages(provider),
                tools = if (provider.supportsTools) ToolRegistry.specs else emptyList(),
                maxTokens = provider.maxTokens,
            )

            val startedAt = SystemClock.elapsedRealtime()
            val response = try {
                client.complete(provider, request)
            } catch (failure: LlmException) {
                return ProviderResult.Failed(failure)
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            log.add(
                "${provider.name}/${provider.model}: ${elapsed}ms, " +
                    "chars=${response.text.length}, calls=${response.toolCalls.size}, " +
                    "finish=${response.finishReason}",
            )

            if (response.toolCalls.isEmpty()) {
                val text = response.text.trim()
                addAssistant(text)
                history += LlmMessage(role = "assistant", content = text)
                store.saveChat(chatForStore())
                return ProviderResult.Done
            }

            history += LlmMessage(
                role = "assistant",
                content = response.text,
                toolCalls = response.toolCalls,
                assistantExtra = response.assistantExtra,
            )

            for (call in response.toolCalls) {
                val arguments = parseArguments(call.arguments)
                _state.update {
                    it.copy(toolActivity = ToolRegistry.activityLabel(call.name, arguments))
                }
                log.add("tool ${call.name}")

                when (val outcome = tools.execute(call.name, arguments)) {
                    is ToolOutcome.Done -> {
                        addAction(outcome.display)
                        history += toolResult(call, outcome.modelText)
                    }

                    is ToolOutcome.Failed -> {
                        addAction(outcome.display)
                        history += toolResult(call, outcome.modelText)
                    }

                    is ToolOutcome.NeedsChoice -> {
                        history += toolResult(call, "Waiting for the user to choose a contact.")
                        _state.update {
                            it.copy(
                                phase = Phase.Ready,
                                toolActivity = null,
                                contactChoices = outcome.choices,
                                spokenName = outcome.utterance,
                            )
                        }
                        return ProviderResult.Paused
                    }
                }
            }
            _state.update { it.copy(toolActivity = null) }
        }

        addAssistant("I stopped after several steps to avoid looping. Please ask again if I didn't finish.")
        history += LlmMessage(
            role = "assistant",
            content = "Stopped after several steps.",
        )
        return ProviderResult.Done
    }

    private suspend fun runOffline(text: String): Boolean = try {
        when (val decision = engine.complete(text)) {
            is ToolDecision.NoCall -> false

            is ToolDecision.Call -> {
                _state.update {
                    it.copy(
                        toolActivity = ToolRegistry.activityLabel(
                            decision.call.name,
                            decision.call.arguments,
                        ),
                    )
                }
                val outcome = tools.execute(decision.call.name, decision.call.arguments)
                _state.update { it.copy(toolActivity = null) }

                when (outcome) {
                    is ToolOutcome.Done -> {
                        addAction(outcome.display)
                        speak(outcome.display)
                    }

                    is ToolOutcome.Failed -> addAssistant(outcome.modelText)

                    is ToolOutcome.NeedsChoice -> _state.update {
                        it.copy(
                            phase = Phase.Ready,
                            contactChoices = outcome.choices,
                            spokenName = outcome.utterance,
                        )
                    }
                }
                true
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        log.add("offline engine failed: ${failure.message}")
        false
    }

    private fun buildMessages(provider: ProviderConfig): List<LlmMessage> {
        dropIncompleteToolTail()
        val system = LlmMessage(
            role = "system",
            content = SystemPrompt.build(
                _state.value.assistantName.ifBlank { "Assistant" },
                provider.extraInstructions,
            ),
        )
        val tail = history.takeLast(MAX_HISTORY)
        val firstUser = tail.indexOfFirst { it.role == "user" }
        val trimmed = if (firstUser > 0) tail.drop(firstUser) else tail
        return listOf(system) + trimmed
    }

    private fun parseArguments(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) {
        JSONObject()
    }

    private fun toolResult(call: LlmToolCall, text: String): LlmMessage =
        LlmMessage(role = "tool", content = text, toolCallId = call.id)

    /**
     * Cancelling or failing a turn can leave an assistant tool call without its
     * tool result. Sending that back would be rejected, so trim the tail.
     */
    private fun dropIncompleteToolTail() {
        while (true) {
            val last = history.lastOrNull() ?: return
            val incomplete = last.role == "tool" ||
                (last.role == "assistant" && last.toolCalls.isNotEmpty())
            if (!incomplete) return
            history.removeAt(history.lastIndex)
        }
    }

    /* ------------------------------------------------------------- contacts */

    fun chooseContact(match: ContactMatch) {
        _state.update { it.copy(contactChoices = emptyList()) }
        when (val outcome = tools.dialContact(match)) {
            is ToolOutcome.Done -> {
                addAction(outcome.display)
                speak(outcome.display)
            }

            is ToolOutcome.Failed -> addAssistant(outcome.modelText)
            is ToolOutcome.NeedsChoice -> Unit
        }
        _state.update { it.copy(phase = Phase.Ready) }
    }

    fun dismissContactChoices() {
        _state.update {
            it.copy(
                contactChoices = emptyList(),
                phase = Phase.Ready,
                toolActivity = null,
            )
        }
        addAction("Cancelled")
    }

    /* -------------------------------------------------------------- settings */

    fun updateAssistantName(name: String) {
        val value = name.take(40)
        _state.update { it.copy(assistantName = value) }
        store.saveAssistantName(value)
    }

    fun setSpeakReplies(enabled: Boolean) {
        _state.update { it.copy(speakReplies = enabled) }
        store.saveSpeakReplies(enabled)
        if (!enabled) tts.stop()
    }

    fun saveProvider(config: ProviderConfig) {
        val current = _state.value.providers
        val updated = if (current.any { it.id == config.id }) {
            current.map { if (it.id == config.id) config else it }
        } else {
            current + config
        }
        store.saveProviders(updated)

        val defaultId = _state.value.defaultProviderId
            ?.takeIf { id -> updated.any { it.id == id } }
            ?: updated.firstOrNull()?.id
        store.saveDefaultProviderId(defaultId)

        val fallbackId = _state.value.fallbackProviderId
            ?.takeIf { id -> id != defaultId && updated.any { it.id == id } }
        store.saveFallbackProviderId(fallbackId)

        _state.update {
            it.copy(
                providers = updated,
                defaultProviderId = defaultId,
                fallbackProviderId = fallbackId,
                providerLabel = labelFor(updated, defaultId),
            )
        }
        log.add("provider saved: ${config.name}; key=${config.apiKey.isNotBlank()}")
    }

    fun deleteProvider(id: String) {
        val updated = _state.value.providers.filterNot { it.id == id }
        store.saveProviders(updated)

        val defaultId = _state.value.defaultProviderId
            ?.takeIf { it != id && updated.any { provider -> provider.id == it } }
            ?: updated.firstOrNull()?.id
        store.saveDefaultProviderId(defaultId)

        val fallbackId = _state.value.fallbackProviderId
            ?.takeIf { it != id && updated.any { provider -> provider.id == it } }
        store.saveFallbackProviderId(fallbackId)

        _state.update {
            it.copy(
                providers = updated,
                defaultProviderId = defaultId,
                fallbackProviderId = fallbackId,
                providerLabel = labelFor(updated, defaultId),
            )
        }
        log.add("provider deleted")
    }

    fun setDefaultProvider(id: String) {
        if (_state.value.providers.none { it.id == id }) return
        store.saveDefaultProviderId(id)

        val fallbackId = _state.value.fallbackProviderId?.takeIf { it != id }
        store.saveFallbackProviderId(fallbackId)

        _state.update {
            it.copy(
                defaultProviderId = id,
                fallbackProviderId = fallbackId,
                providerLabel = labelFor(it.providers, id),
            )
        }
    }

    fun setFallbackProvider(id: String?) {
        val value = id?.takeIf { candidate ->
            candidate != _state.value.defaultProviderId &&
                _state.value.providers.any { it.id == candidate }
        }
        store.saveFallbackProviderId(value)
        _state.update { it.copy(fallbackProviderId = value) }
    }

    fun clearChat() {
        history.clear()
        store.clearChat()
        _state.update { it.copy(messages = emptyList()) }
    }

    /* ----------------------------------------------------------- provider test */

    fun testProvider(config: ProviderConfig) {
        val problem = validateForNetwork(config)
        if (problem != null) {
            _state.update {
                it.copy(
                    providerTestFor = config.id,
                    providerTest = ProviderTestState.Done(problem, false),
                )
            }
            return
        }

        testJob?.cancel()
        _state.update {
            it.copy(
                providerTestFor = config.id,
                providerTest = ProviderTestState.Running,
                modelsFor = null,
                modelChoices = emptyList(),
                modelsError = null,
            )
        }

        testJob = viewModelScope.launch {
            val report = StringBuilder()
            var ok = false

            try {
                val startedAt = SystemClock.elapsedRealtime()
                val response = client.complete(
                    config,
                    LlmRequest(
                        model = config.model,
                        messages = listOf(
                            LlmMessage(role = "user", content = "Reply with exactly: OK"),
                        ),
                        tools = emptyList(),
                        maxTokens = 16,
                        temperature = 0.0,
                    ),
                )
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                report.appendLine("Text: OK (${elapsed} ms)")
                ok = response.text.isNotBlank()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LlmException) {
                report.appendLine("Text: failed — ${failure.userMessage}")
            } catch (failure: Throwable) {
                report.appendLine("Text: failed — ${failure.message ?: "unknown error"}")
            }

            if (ok && config.supportsTools) {
                try {
                    val ping = LlmToolSpec(
                        name = "ping",
                        description = "Test tool. Call it when the user asks.",
                        parameters = JSONObject(
                            """{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"]}""",
                        ),
                    )
                    val response = client.complete(
                        config,
                        LlmRequest(
                            model = config.model,
                            messages = listOf(
                                LlmMessage(
                                    role = "user",
                                    content = "Call the ping tool with value 1. Do not answer with text.",
                                ),
                            ),
                            tools = listOf(ping),
                            maxTokens = 64,
                            temperature = 0.0,
                        ),
                    )
                    report.appendLine(
                        if (response.toolCalls.isNotEmpty()) {
                            "Tools: OK"
                        } else {
                            "Tools: not detected (the model answered in text)"
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: LlmException) {
                    report.appendLine("Tools: failed — ${failure.userMessage}")
                }
            } else if (ok) {
                report.appendLine("Tools: disabled for this profile")
            }

            if (ok && config.supportsVision) {
                try {
                    val response = client.complete(
                        config,
                        LlmRequest(
                            model = config.model,
                            messages = listOf(
                                LlmMessage(
                                    role = "user",
                                    content = "What is the main color of this image? Reply with one word.",
                                    images = listOf(redPng()),
                                ),
                            ),
                            tools = emptyList(),
                            maxTokens = 16,
                            temperature = 0.0,
                        ),
                    )
                    val sawRed = response.text.contains("red", ignoreCase = true)
                    report.appendLine(
                        if (sawRed) {
                            "Vision: OK"
                        } else {
                            "Vision: unsure — replied \"${response.text.take(40)}\""
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: LlmException) {
                    report.appendLine("Vision: failed — ${failure.userMessage}")
                }
            } else if (ok) {
                report.appendLine("Vision: disabled for this profile")
            }

            log.add("test ${config.name}: ok=$ok")
            _state.update {
                it.copy(
                    providerTest = ProviderTestState.Done(report.toString().trim(), ok),
                )
            }
        }
    }

    fun fetchModels(config: ProviderConfig) {
        // A missing model is the normal case here: fetching is how you pick one.
        val problem = when {
            config.baseUrl.isBlank() -> "Add a base URL first."
            config.requiresKey && config.apiKey.isBlank() -> "Add an API key first."
            else -> ProviderUrls.validate(config.baseUrl)
        }
        if (problem != null) {
            _state.update {
                it.copy(
                    modelsFor = config.id,
                    modelsLoading = false,
                    modelsError = problem,
                    modelChoices = emptyList(),
                )
            }
            return
        }

        modelsJob?.cancel()
        _state.update {
            it.copy(
                modelsFor = config.id,
                modelsLoading = true,
                modelsError = null,
                modelChoices = emptyList(),
            )
        }
        modelsJob = viewModelScope.launch {
            try {
                val models = client.listModels(config)
                _state.update { it.copy(modelsLoading = false, modelChoices = models) }
                if (models.isEmpty()) {
                    _state.update { it.copy(modelsError = "The provider listed no models.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LlmException) {
                _state.update {
                    it.copy(modelsLoading = false, modelsError = failure.userMessage)
                }
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        modelsLoading = false,
                        modelsError = failure.message ?: "Could not fetch models.",
                    )
                }
            }
        }
    }

    fun clearProviderProbe() {
        testJob?.cancel()
        modelsJob?.cancel()
        testJob = null
        modelsJob = null
        _state.update {
            it.copy(
                providerTest = ProviderTestState.Idle,
                providerTestFor = null,
                modelChoices = emptyList(),
                modelsFor = null,
                modelsLoading = false,
                modelsError = null,
            )
        }
    }

    /* ----------------------------------------------------------- diagnostics */

    fun diagnostics(): String {
        val app = getApplication<Application>()
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val header = buildString {
            appendLine("Voice Agent $version diagnostics")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Phase: ${_state.value.phase}")
            appendLine("Providers:")
            _state.value.providers.forEach { provider ->
                appendLine(
                    "  - ${provider.name} | model=${provider.model} | " +
                        "host=${ProviderUrls.hostLabel(provider.baseUrl)} | " +
                        "key=${if (provider.apiKey.isBlank()) "none" else "set"} | " +
                        "tools=${provider.supportsTools} | vision=${provider.supportsVision}",
                )
            }
            appendLine("Default: ${_state.value.defaultProviderId?.take(8) ?: "none"}")
            appendLine("Fallback: ${_state.value.fallbackProviderId?.take(8) ?: "none"}")
        }
        return log.dump(header)
    }

    /* --------------------------------------------------------------- helpers */

    private fun usable(provider: ProviderConfig): Boolean =
        provider.baseUrl.isNotBlank() &&
            provider.model.isNotBlank() &&
            (!provider.requiresKey || provider.apiKey.isNotBlank())

    private fun validateForNetwork(config: ProviderConfig): String? {
        if (config.baseUrl.isBlank()) return "Add a base URL first."
        if (config.model.isBlank()) return "Add a model name first."
        if (config.requiresKey && config.apiKey.isBlank()) return "Add an API key first."
        return ProviderUrls.validate(config.baseUrl)
    }

    private fun labelFor(providers: List<ProviderConfig>, defaultId: String?): String {
        val provider = providers.firstOrNull { it.id == defaultId } ?: return "Offline reflexes"
        return if (usable(provider)) {
            "${provider.name} · ${provider.model}"
        } else {
            "${provider.name} · add API key"
        }
    }

    private fun addChat(role: ChatRole, text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        _state.update {
            it.copy(messages = it.messages + ChatItem(nextId(), role, value))
        }
        if (role == ChatRole.User || role == ChatRole.Assistant) {
            store.saveChat(chatForStore())
        }
    }

    private fun addAssistant(text: String, speak: Boolean = true) {
        if (text.isBlank()) return
        addChat(ChatRole.Assistant, text)
        if (speak) speak(text)
    }

    private fun addAction(text: String) {
        addChat(ChatRole.Action, text)
    }

    private fun addError(text: String) {
        addChat(ChatRole.Error, text)
    }

    private fun speak(text: String) {
        if (_state.value.speakReplies) tts.speak(text)
    }

    private fun chatForStore(): List<StoredMessage> = _state.value.messages
        .filter { it.role == ChatRole.User || it.role == ChatRole.Assistant }
        .map {
            StoredMessage(
                role = if (it.role == ChatRole.User) "user" else "assistant",
                text = it.text,
            )
        }
        .takeLast(60)

    private fun redPng(): String {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun nextId(): Long = messageIds.getAndIncrement()

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

    override fun onCleared() {
        requestJob?.cancel()
        testJob?.cancel()
        modelsJob?.cancel()
        tts.shutdown()
        super.onCleared()
    }

    private sealed interface ProviderResult {
        data object Done : ProviderResult
        data object Paused : ProviderResult
        data class Failed(val error: LlmException) : ProviderResult
    }

    private companion object {
        const val MAX_STEPS = 6
        const val MAX_HISTORY = 30
        const val OFFLINE_HINT =
            "I can still try simple offline commands. Add or fix a provider in Settings for full answers."
        const val NO_PROVIDER =
            "I can only handle calls, the flashlight, apps, timers, and alarms offline. " +
                "Add a provider in Settings for full answers."
    }
}
