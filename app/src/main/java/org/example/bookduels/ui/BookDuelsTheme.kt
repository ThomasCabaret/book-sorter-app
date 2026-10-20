package org.example.bookduels.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Twilight palette: deep blue-charcoal surfaces with soft teal and amber accents. */
private val TwilightColorScheme = darkColorScheme(
    primary = Color(0xFFA7D8CE),
    onPrimary = Color(0xFF103733),
    primaryContainer = Color(0xFF244D49),
    onPrimaryContainer = Color(0xFFD2F4EC),
    secondary = Color(0xFFD8C5A2),
    onSecondary = Color(0xFF3A3020),
    secondaryContainer = Color(0xFF4B4030),
    onSecondaryContainer = Color(0xFFF4E3BF),
    tertiary = Color(0xFFCABBE7),
    onTertiary = Color(0xFF34294C),
    background = Color(0xFF14181E),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF1C222A),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF29313A),
    onSurfaceVariant = Color(0xFFC7CED8),
    outline = Color(0xFF89939F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
internal fun BookDuelsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TwilightColorScheme, content = content)
}
