package com.tediang.quicktranslate

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF171717),
    onPrimary = Color.White,
    secondary = Color(0xFF404040),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8ECF0),
    onSecondaryContainer = Color(0xFF171717),
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF171717),
    surface = Color.White,
    onSurface = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF475569),
    outlineVariant = Color(0xFFE5E5E5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF171717),
    secondary = Color(0xFFD4D4D4),
    onSecondary = Color(0xFF171717),
    secondaryContainer = Color(0xFF30343A),
    onSecondaryContainer = Color(0xFFF5F5F5),
    background = Color(0xFF111111),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1B1B1B),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFF404040),
)

@Composable
internal fun QuickTranslateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
