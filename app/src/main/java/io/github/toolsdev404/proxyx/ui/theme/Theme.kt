package io.github.toolsdev404.proxyx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.toolsdev404.proxyx.ThemeMode

private val DarkColors = darkColorScheme(
    primary = AccentGreen,
    onPrimary = Color(0xFF03110A),
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed
)

private val LightColors = lightColorScheme(
    primary = AccentGreen,
    onPrimary = Color(0xFF03110A),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF0B0F14),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B0F14),
    surfaceVariant = Color(0xFFE8ECF1),
    onSurfaceVariant = Color(0xFF566072),
    error = ErrorRed
)

@Composable
fun ProxyXTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}