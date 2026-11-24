package com.example.voiceagent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voiceagent.AgentUiState
import com.example.voiceagent.AgentViewModel
import com.example.voiceagent.Phase
import com.example.voiceagent.contacts.ContactMatch

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

    val openSettings = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        context.startActivity(intent)
    }

    val inMainScreen = when (state.phase) {
        Phase.Ready, Phase.Listening, Phase.Transcribing, Phase.Thinking -> true
        else -> false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (inMainScreen) {
            MainScreen(
                state = state,
                missingPermissions = missingPermissions,
                askedOnce = askedOnce,
                onRequestPermissions = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
                onOpenSettings = openSettings,
                onStartListening = viewModel::startListening,
                onStopListening = viewModel::stopListening,
                onDismissError = viewModel::dismissError,
            )
        } else {
            SetupScreen(state = state, onRetry = viewModel::retry)
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
            text = "Calls and flashlight, entirely on this phone.",
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
                    "After that, everything works offline.",
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

/* ------------------------------------------------------------------- main */

@Composable
private fun MainScreen(
    state: AgentUiState,
    missingPermissions: List<String>,
    askedOnce: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onDismissError: () -> Unit,
) {
    val canTalk = state.phase == Phase.Ready &&
        Manifest.permission.RECORD_AUDIO !in missingPermissions

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Voice Agent",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (missingPermissions.isNotEmpty()) {
                PermissionsCard(
                    missingPermissions = missingPermissions,
                    askedOnce = askedOnce,
                    onRequest = onRequestPermissions,
                    onOpenSettings = onOpenSettings,
                )
                Spacer(Modifier.height(16.dp))
            }

            ResultCard(
                label = "I heard",
                text = state.heard.ifBlank { "Nothing yet" },
                dimmed = state.heard.isBlank(),
            )
            Spacer(Modifier.height(12.dp))
            ResultCard(
                label = "Action",
                text = state.action.ifBlank { "No action yet" },
                dimmed = state.action.isBlank(),
            )

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = 12.dp,
                            top = 4.dp,
                            bottom = 4.dp,
                            end = 4.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = onDismissError) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        HoldToTalkButton(
            enabled = canTalk,
            listening = state.phase == Phase.Listening,
            onStart = onStartListening,
            onStop = onStopListening,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = buttonHint(state, canTalk),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Needle 3 + Whistle · runs offline",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(label: String, text: String, dimmed: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
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

@Composable
private fun HoldToTalkButton(
    enabled: Boolean,
    listening: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)

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
            .size(220.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    start()
                    waitForUpOrCancellation()
                    stop()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (listening) "Release" else "Hold to talk",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
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

private fun statusLine(state: AgentUiState): String = when (state.phase) {
    Phase.Listening -> "Listening…"
    Phase.Transcribing -> "Transcribing…"
    Phase.Thinking -> "Thinking…"
    else -> state.status.ifBlank { "Ready" }
}

private fun buttonHint(state: AgentUiState, canTalk: Boolean): String = when (state.phase) {
    Phase.Listening -> "Release when you are done"
    Phase.Transcribing, Phase.Thinking -> "Working…"
    Phase.Ready -> if (canTalk) {
        "Hold, speak, then release"
    } else {
        "Allow the microphone above to start"
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
