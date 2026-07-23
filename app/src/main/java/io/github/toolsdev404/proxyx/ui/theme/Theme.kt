package io.github.toolsdev404.proxyx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ProxyXDarkColors = darkColorScheme(
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

@Composable
fun ProxyXTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ProxyXDarkColors,
        typography = Typography,
        content = content
    )
}