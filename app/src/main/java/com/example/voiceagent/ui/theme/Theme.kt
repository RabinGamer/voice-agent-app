package com.example.voiceagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4C662B),
    background = Color(0xFFFBFDF6),
    onBackground = Color(0xFF191D16),
    surface = Color(0xFFFBFDF6),
    onSurface = Color(0xFF191D16),
    surfaceContainerHighest = Color(0xFFE0E4D5),
    onSurfaceVariant = Color(0xFF44483D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD67D),
    onPrimary = Color(0xFF103800),
    secondary = Color(0xFFB3D18C),
    background = Color(0xFF12140E),
    onBackground = Color(0xFFE2E3DA),
    surface = Color(0xFF12140E),
    onSurface = Color(0xFFE2E3DA),
    surfaceContainerHighest = Color(0xFF44483D),
    onSurfaceVariant = Color(0xFFC4C8BA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun VoiceAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
