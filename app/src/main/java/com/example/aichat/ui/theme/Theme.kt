
package com.example.aichat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD97706),
    onPrimary = Color(0xFF1C1917),
    background = Color(0xFF0C0C0C),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFE4E2DF),
    onSurface = Color(0xFFE4E2DF),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF2C2C2C),
    secondary = Color(0xFF6B6B6B),
    tertiary = Color(0xFF4EC9B0)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC2410C),
    onPrimary = Color.White,
    background = Color(0xFFF7F6F3),
    surface = Color(0xFFFCFBF9),
    onBackground = Color(0xFF1A1816),
    onSurface = Color(0xFF1A1816),
    surfaceVariant = Color(0xFFEDEBE7),
    onSurfaceVariant = Color(0xFF5C574F),
    outline = Color(0xFFD8D4CE),
    secondary = Color(0xFF78716C),
    tertiary = Color(0xFF0F766E)
)

private val HighContrastDark = darkColorScheme(
    primary = Color(0xFFFFB000),
    onPrimary = Color(0xFF000000),
    background = Color(0xFF000000),
    surface = Color(0xFF0A0A0A),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF666666),
    secondary = Color(0xFFAAAAAA),
    tertiary = Color(0xFF00FFCC)
)

@Composable
fun AIChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val scheme = when (themeMode) {
        "dark" -> DarkColorScheme
        "light" -> LightColorScheme
        "high" -> HighContrastDark
        else -> if (darkTheme) DarkColorScheme else LightColorScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
