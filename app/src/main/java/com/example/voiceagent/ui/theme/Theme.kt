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
    primaryContainer = Color(0xFFA5F2A0),
    onPrimaryContainer = Color(0xFF002204),
    secondary = Color(0xFF4C662B),
    secondaryContainer = Color(0xFFCDEBA5),
    onSecondaryContainer = Color(0xFF0F2000),
    tertiary = Color(0xFF38656A),
    background = Color(0xFFFBFDF6),
    onBackground = Color(0xFF191D16),
    surface = Color(0xFFFBFDF6),
    onSurface = Color(0xFF191D16),
    surfaceContainerHigh = Color(0xFFE6EADB),
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
    primaryContainer = Color(0xFF235000),
    onPrimaryContainer = Color(0xFFA5F2A0),
    secondary = Color(0xFFB3D18C),
    secondaryContainer = Color(0xFF354E16),
    onSecondaryContainer = Color(0xFFCDEBA5),
    tertiary = Color(0xFFA0CFD4),
    background = Color(0xFF12140E),
    onBackground = Color(0xFFE2E3DA),
    surface = Color(0xFF12140E),
    onSurface = Color(0xFFE2E3DA),
    surfaceContainerHigh = Color(0xFF2A2E25),
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
