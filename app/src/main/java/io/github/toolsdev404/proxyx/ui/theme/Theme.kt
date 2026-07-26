package io.github.toolsdev404.proxyx.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.toolsdev404.proxyx.ThemeMode

// Bright green reads well on the dark surfaces.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF22C55E),
    onPrimary = Color(0xFF03110A),
    primaryContainer = Color(0xFF14351F),
    onPrimaryContainer = Color(0xFF86EFAC),
    secondary = Color(0xFF22C55E),
    onSecondary = Color(0xFF03110A),
    secondaryContainer = Color(0xFF14351F),
    onSecondaryContainer = Color(0xFF86EFAC),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF222A34),
    onSurfaceVariant = Color(0xFF9BA7B4),
    outline = Color(0xFF3A434E),
    outlineVariant = Color(0xFF2A323C),
    error = Color(0xFFEF4444),
    onError = Color(0xFF1A0000)
)

// A deeper green so accents stay legible on white.
private val LightColors = lightColorScheme(
    primary = Color(0xFF15803D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF063A1E),
    secondary = Color(0xFF15803D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCFCE7),
    onSecondaryContainer = Color(0xFF063A1E),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF0B0F14),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B0F14),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFC2CAD3),
    outlineVariant = Color(0xFFD8DEE5),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF)
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

    // Keep the status-bar / nav-bar icons legible for the app's own theme
    // (not the system theme), since we draw edge-to-edge.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}