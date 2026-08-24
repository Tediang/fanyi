package com.tediang.quicktranslate

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1677FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F2FF),
    onPrimaryContainer = Color(0xFF0A376B),
    secondary = Color(0xFF00A6A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2F8F6),
    onSecondaryContainer = Color(0xFF073B3B),
    tertiary = Color(0xFF0E9F6E),
    onTertiary = Color.White,
    background = Color(0xFFF6F9FC),
    onBackground = Color(0xFF172033),
    surface = Color.White,
    onSurface = Color(0xFF172033),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFDCE5EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CB8FF),
    onPrimary = Color(0xFF002F61),
    primaryContainer = Color(0xFF0D3E70),
    onPrimaryContainer = Color(0xFFD7E9FF),
    secondary = Color(0xFF62D9D3),
    onSecondary = Color(0xFF003736),
    secondaryContainer = Color(0xFF134E4D),
    onSecondaryContainer = Color(0xFFC7F7F3),
    tertiary = Color(0xFF5EE0AA),
    onTertiary = Color(0xFF003824),
    background = Color(0xFF0D1522),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF141E2D),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF202C3C),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF8B9AAF),
    outlineVariant = Color(0xFF334155),
)

@Composable
internal fun QuickTranslateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
