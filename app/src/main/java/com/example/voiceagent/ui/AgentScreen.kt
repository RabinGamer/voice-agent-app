package com.example.voiceagent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voiceagent.AgentUiState
import com.example.voiceagent.AgentViewModel
import com.example.voiceagent.ChatItem
import com.example.voiceagent.ChatRole
import com.example.voiceagent.Phase
import com.example.voiceagent.ProviderTestState
import com.example.voiceagent.contacts.ContactMatch
import com.example.voiceagent.data.ProviderConfig
import com.example.voiceagent.data.ProviderPresets
import com.example.voiceagent.llm.ProviderUrls

private val REQUIRED_PERMISSIONS = listOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CAMERA,
)

@Composable
fun AgentApp(viewModel: AgentViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-check permissions whenever we come back to the foreground, including
    // after the user changes them in Settings.
    var permissionTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionTick += 1 }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onAppStopped() }

    val missingPermissions = remember(permissionTick) {
        REQUIRED_PERMISSIONS.filterNot { isGranted(context, it) }
    }

    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        askedOnce = true
        permissionTick += 1
    }

    val openAppSettings = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        context.startActivity(intent)
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !state.ready -> SetupScreen(state = state, onRetry = viewModel::retry)

            showSettings -> SettingsScreen(
                state = state,
                onDone = { showSettings = false },
                onNameChange = viewModel::updateAssistantName,
                onSpeakReplies = viewModel::setSpeakReplies,
                onClearChat = viewModel::clearChat,
                onSetDefault = viewModel::setDefaultProvider,
                onSetFallback = viewModel::setFallbackProvider,
                onDeleteProvider = viewModel::deleteProvider,
                onSaveProvider = viewModel::saveProvider,
                onTestProvider = viewModel::testProvider,
                onFetchModels = viewModel::fetchModels,
                onClearProbe = viewModel::clearProviderProbe,
                onDiagnostics = viewModel::diagnostics,
            )

            else -> ChatScreen(
                state = state,
                missingPermissions = missingPermissions,
                askedOnce = askedOnce,
                onRequestPermissions = {
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                },
                onOpenAppSettings = openAppSettings,
                onOpenSettings = { showSettings = true },
                onStartListening = viewModel::startListening,
                onStopListening = viewModel::stopListening,
                onSend = viewModel::sendMessage,
                onDraftChange = viewModel::onDraftChange,
                onStopThinking = viewModel::stopThinking,
            )
        }
    }

    if (state.contactChoices.isNotEmpty()) {
        ContactChoiceDialog(
            spokenName = state.spokenName,
            choices = state.contactChoices,
            onChoose = viewModel::chooseContact,
            onDismiss = viewModel::dismissContactChoices,
        )
    }
}

/* ------------------------------------------------------------------ setup */

@Composable
private fun SetupScreen(state: AgentUiState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Voice Agent",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A voice assistant with a cloud brain and offline reflexes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        if (state.error == null) {
            LinearProgressIndicator(
                progress = { state.setupProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.setupLabel.ifBlank { "Preparing…" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${(state.setupProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "The first launch downloads about 50 MB, once.\n" +
                    "After that, calls, the flashlight, apps, timers and alarms\n" +
                    "keep working even without internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = "Setup failed",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

/* ------------------------------------------------------------------- chat */

@Composable
private fun ChatScreen(
    state: AgentUiState,
    missingPermissions: List<String>,
    askedOnce: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onSend: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onStopThinking: () -> Unit,
) {
    val listState = rememberLazyListState()
    val canTalk = state.phase == Phase.Ready &&
        Manifest.permission.RECORD_AUDIO !in missingPermissions

    LaunchedEffect(state.messages.size, state.toolActivity) {
        if (state.messages.isNotEmpty()) {
            // Wait for the new item to be laid out before scrolling.
            withFrameNanos { }
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        ChatTopBar(state = state, onOpenSettings = onOpenSettings)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (missingPermissions.isNotEmpty()) {
                item("permissions") {
                    PermissionsCard(
                        missingPermissions = missingPermissions,
                        askedOnce = askedOnce,
                        onRequest = onRequestPermissions,
                        onOpenSettings = onOpenAppSettings,
                    )
                }
            }

            if (needsProviderSetup(state)) {
                item("provider") { ProviderBanner(onOpenSettings = onOpenSettings) }
            }

            if (state.messages.isEmpty()) {
                item("empty") {
                    EmptyChat(assistantName = state.assistantName.ifBlank { "Assistant" })
                }
            }

            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }

            state.toolActivity?.let { activity ->
                item("activity") {
                    ToolActivityRow(activity)
                }
            }
        }

        ChatComposer(
            state = state,
            canTalk = canTalk,
            micGranted = Manifest.permission.RECORD_AUDIO !in missingPermissions,
            onSend = onSend,
            onDraftChange = onDraftChange,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            onStopThinking = onStopThinking,
            onRequestPermissions = onRequestPermissions,
        )
    }
}

@Composable
private fun ChatTopBar(state: AgentUiState, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.assistantName.ifBlank { "Assistant" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.providerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        TextButton(onClick = onOpenSettings) {
            Text("Settings")
        }
    }
}

@Composable
private fun EmptyChat(assistantName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Hi, I'm $assistantName.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ask me anything, or try one of these:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "Turn on the flashlight",
            "Set a 5 minute timer",
            "Open WhatsApp",
            "Call Mum",
        ).forEach { example ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Text(
                    text = "“$example”",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatItem) {
    when (message.role) {
        ChatRole.User -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(0.88f),
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        ChatRole.Assistant -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(0.92f),
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        ChatRole.Action -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "●",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ChatRole.Error -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ToolActivityRow(activity: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .width(56.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = activity,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatComposer(
    state: AgentUiState,
    canTalk: Boolean,
    micGranted: Boolean,
    onSend: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopThinking: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message or hold the mic") },
                maxLines = 4,
                enabled = !state.busy,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (state.draft.isNotBlank()) onSend(state.draft) },
                ),
            )
            Spacer(Modifier.width(8.dp))

            when {
                state.busy -> CircleButton(
                    label = "■",
                    background = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = onStopThinking,
                )

                state.draft.isNotBlank() -> CircleButton(
                    label = "↑",
                    background = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = { onSend(state.draft) },
                )

                else -> HoldMicButton(
                    enabled = canTalk,
                    listening = state.phase == Phase.Listening,
                    onStart = {
                        if (micGranted) onStartListening() else onRequestPermissions()
                    },
                    onStop = onStopListening,
                    onNeedPermission = onRequestPermissions,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = composerHint(state, canTalk),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CircleButton(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val click by rememberUpdatedState(onClick)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    waitForUpOrCancellation()
                    click()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

@Composable
private fun HoldMicButton(
    enabled: Boolean,
    listening: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNeedPermission: () -> Unit,
) {
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val needPermission by rememberUpdatedState(onNeedPermission)
    val canStart by rememberUpdatedState(enabled)

    val background = when {
        listening -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        listening -> MaterialTheme.colorScheme.onError
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!canStart) {
                        needPermission()
                        return@awaitEachGesture
                    }
                    start()
                    waitForUpOrCancellation()
                    stop()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (listening) "■" else "🎤",
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
        )
    }
}

/* ------------------------------------------------------------- permissions */

@Composable
private fun ProviderBanner(onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Add your AI key",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Offline commands work now. For general questions, add a " +
                    "provider and API key in Settings. The key stays on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    missingPermissions: List<String>,
    askedOnce: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "A few permissions first",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            missingPermissions.forEach { permission ->
                val (title, reason) = explanationFor(permission)
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(text = "•", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequest) {
                    Text(if (askedOnce) "Try again" else "Allow")
                }
                if (askedOnce) {
                    TextButton(onClick = onOpenSettings) {
                        Text("Open settings")
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- settings */

@Composable
private fun SettingsScreen(
    state: AgentUiState,
    onDone: () -> Unit,
    onNameChange: (String) -> Unit,
    onSpeakReplies: (Boolean) -> Unit,
    onClearChat: () -> Unit,
    onSetDefault: (String) -> Unit,
    onSetFallback: (String?) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSaveProvider: (ProviderConfig) -> Unit,
    onTestProvider: (ProviderConfig) -> Unit,
    onFetchModels: (ProviderConfig) -> Unit,
    onClearProbe: () -> Unit,
    onDiagnostics: () -> String,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProviderConfig?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Settings",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onDone) {
                Text("Done")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("assistant") {
                SettingsSection(title = "Assistant") {
                    OutlinedTextField(
                        value = state.assistantName,
                        onValueChange = onNameChange,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "Speak replies aloud",
                        subtitle = "Uses the voice installed on this phone.",
                        checked = state.speakReplies,
                        onChange = onSpeakReplies,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear conversation")
                    }
                }
            }

            item("providers") {
                SettingsSection(title = "AI providers") {
                    Text(
                        text = "One provider answers questions and controls tools. " +
                            "A fallback is tried if the first one fails.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    if (state.providers.isEmpty()) {
                        Text(
                            text = "No provider yet. Add one to get full answers. " +
                                "Offline commands still work.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    state.providers.forEach { provider ->
                        ProviderCard(
                            provider = provider,
                            isDefault = provider.id == state.defaultProviderId,
                            isFallback = provider.id == state.fallbackProviderId,
                            canBeFallback = provider.id != state.defaultProviderId,
                            onDefault = { onSetDefault(provider.id) },
                            onFallback = {
                                onSetFallback(
                                    if (provider.id == state.fallbackProviderId) null
                                    else provider.id,
                                )
                            },
                            onEdit = { editing = provider },
                            onDelete = { deleting = provider },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    OutlinedButton(
                        onClick = { adding = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add provider")
                    }
                }
            }

            item("diagnostics") {
                SettingsSection(title = "Diagnostics") {
                    Text(
                        text = "The report includes error details with API keys and " +
                            "tokens removed. It never leaves the phone unless you share it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Voice Agent diagnostics")
                                putExtra(Intent.EXTRA_TEXT, onDiagnostics())
                            }
                            context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Share diagnostics")
                    }
                }
            }

            item("version") {
                Text(
                    text = "Voice Agent 1.1 · Needle 3 + Whistle offline",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    editing?.let { draft ->
        ProviderEditorDialog(
            initial = draft,
            state = state,
            onDismiss = {
                editing = null
                onClearProbe()
            },
            onSave = { updated ->
                onSaveProvider(updated)
                editing = null
                onClearProbe()
            },
            onTest = onTestProvider,
            onFetchModels = onFetchModels,
        )
    }

    if (adding) {
        PresetPickerDialog(
            onPick = { presetId ->
                adding = false
                editing = ProviderPresets.create(presetId)
            },
            onDismiss = { adding = false },
        )
    }

    deleting?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${provider.name}?") },
            text = { Text("The saved API key for this provider is removed too.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteProvider(provider.id)
                    deleting = null
                    onClearProbe()
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the conversation?") },
            text = { Text("The chat history saved on this phone will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearChat()
                    confirmClear = false
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    isDefault: Boolean,
    isFallback: Boolean,
    canBeFallback: Boolean,
    onDefault: () -> Unit,
    onFallback: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = provider.name.ifBlank { "Provider" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = provider.model.ifBlank { "No model selected" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ProviderUrls.hostLabel(provider.baseUrl).ifBlank { "No address" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isDefault) {
                    Text(
                        text = "Default",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                } else if (isFallback) {
                    Text(
                        text = "Fallback",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = buildString {
                    append(if (provider.requiresKey) "Key needed" else "No key")
                    append(" · ")
                    append(if (provider.supportsTools) "tools on" else "tools off")
                    append(" · ")
                    append(if (provider.supportsVision) "vision on" else "vision off")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Row {
                if (!isDefault) {
                    TextButton(onClick = onDefault) { Text("Use") }
                }
                if (canBeFallback) {
                    TextButton(onClick = onFallback) {
                        Text(if (isFallback) "Unset fallback" else "Fallback")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/* ----------------------------------------------------------- provider edit */

@Composable
private fun ProviderEditorDialog(
    initial: ProviderConfig,
    state: AgentUiState,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    onTest: (ProviderConfig) -> Unit,
    onFetchModels: (ProviderConfig) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val problem = ProviderUrls.validate(draft.baseUrl)
    val models = if (state.modelsFor == draft.id) state.modelChoices else emptyList()
    val modelsLoading = state.modelsFor == draft.id && state.modelsLoading
    val modelsError = if (state.modelsFor == draft.id) state.modelsError else null
    val test = if (state.providerTestFor == draft.id) state.providerTest else ProviderTestState.Idle

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Provider settings",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Presets",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProviderPresets.all.forEach { preset ->
                            FilterChip(
                                selected = draft.presetId == preset.id,
                                onClick = {
                                    val fresh = ProviderPresets.create(preset.id)
                                    draft = fresh.copy(
                                        id = draft.id,
                                        apiKey = if (fresh.requiresKey) draft.apiKey else "",
                                        extraInstructions = draft.extraInstructions,
                                    )
                                },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                    if (draft.presetId.isNotBlank()) {
                        Text(
                            text = ProviderPresets.byId(draft.presetId).blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it.take(60)) },
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = it.trim()) },
                        label = { Text("Base URL") },
                        singleLine = true,
                        isError = problem != null && draft.baseUrl.isNotBlank(),
                        supportingText = {
                            Text(
                                if (draft.baseUrl.isBlank()) {
                                    "Example: https://api.openai.com/v1"
                                } else {
                                    problem ?: "Looks good."
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { draft = draft.copy(model = it.trim()) },
                        label = { Text("Model") },
                        singleLine = true,
                        placeholder = { Text("For example gemini-3.5-flash-lite") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onFetchModels(draft) },
                            enabled = !modelsLoading,
                        ) {
                            Text(if (modelsLoading) "Fetching…" else "Fetch models")
                        }
                    }
                    modelsError?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (models.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            models.forEach { model ->
                                TextButton(
                                    onClick = { draft = draft.copy(model = model) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = model,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Start,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { draft = draft.copy(apiKey = it.trim()) },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            Text(
                                "Stored encrypted on this phone. Leave empty for local " +
                                    "servers that don't need one.",
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))

                    SwitchRow(
                        title = "Needs an API key",
                        subtitle = "Turn off for servers on your own network.",
                        checked = draft.requiresKey,
                        onChange = { draft = draft.copy(requiresKey = it) },
                    )
                    SwitchRow(
                        title = "Supports tool calling",
                        subtitle = "Needed for calls, apps, timers and the flashlight.",
                        checked = draft.supportsTools,
                        onChange = { draft = draft.copy(supportsTools = it) },
                    )
                    SwitchRow(
                        title = "Supports images (vision)",
                        subtitle = "Used later for the camera.",
                        checked = draft.supportsVision,
                        onChange = { draft = draft.copy(supportsVision = it) },
                    )
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = draft.extraInstructions,
                        onValueChange = {
                            draft = draft.copy(extraInstructions = it.take(2000))
                        },
                        label = { Text("Extra instructions (optional)") },
                        minLines = 2,
                        maxLines = 5,
                        supportingText = { Text("Added to this provider's system prompt.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { onTest(draft) },
                        enabled = problem == null &&
                            draft.model.isNotBlank() &&
                            (!draft.requiresKey || draft.apiKey.isNotBlank()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (test is ProviderTestState.Running) {
                                "Testing…"
                            } else {
                                "Test connection"
                            },
                        )
                    }

                    if (test is ProviderTestState.Done) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (test.ok) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                            ),
                        ) {
                            Text(
                                text = test.report,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onSave(draft) },
                        enabled = draft.model.isNotBlank() && problem == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a provider") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ProviderPresets.all.forEach { preset ->
                    TextButton(
                        onClick = { onPick(preset.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = preset.blurb,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/* ----------------------------------------------------------------- dialog */

@Composable
private fun ContactChoiceDialog(
    spokenName: String,
    choices: List<ContactMatch>,
    onChoose: (ContactMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which $spokenName?") },
        text = {
            Column {
                choices.forEach { match ->
                    TextButton(
                        onClick = { onChoose(match) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                text = match.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = match.number,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/* ---------------------------------------------------------------- helpers */

private fun needsProviderSetup(state: AgentUiState): Boolean {
    val provider = state.providers.firstOrNull { it.id == state.defaultProviderId }
        ?: return true
    return provider.baseUrl.isBlank() ||
        provider.model.isBlank() ||
        (provider.requiresKey && provider.apiKey.isBlank())
}

private fun composerHint(state: AgentUiState, canTalk: Boolean): String = when (state.phase) {
    Phase.Listening -> "Listening… release to send"
    Phase.Transcribing -> "Transcribing…"
    Phase.Thinking -> "Working… tap ■ to stop"
    Phase.Ready -> if (canTalk) {
        "Hold the mic to talk, or type below"
    } else {
        "Type below, or allow the microphone first"
    }
    else -> ""
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun explanationFor(permission: String): Pair<String, String> = when (permission) {
    Manifest.permission.RECORD_AUDIO ->
        "Microphone" to "Turns your speech into text on this phone."

    Manifest.permission.READ_CONTACTS ->
        "Contacts" to "Finds the person whose name you say. Nothing leaves the phone."

    Manifest.permission.CAMERA ->
        "Camera" to "Android only lets apps switch the flashlight with camera access."

    else -> permission to ""
}
