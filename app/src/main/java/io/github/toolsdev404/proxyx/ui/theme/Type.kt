package io.github.toolsdev404.proxyx.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.toolsdev404.proxyx.R

private val SpaceGrotesk = FontFamily(Font(R.font.space_grotesk))

private val base = Typography()

val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = SpaceGrotesk),
    displayMedium = base.displayMedium.copy(fontFamily = SpaceGrotesk),
    displaySmall = base.displaySmall.copy(fontFamily = SpaceGrotesk),
    headlineLarge = base.headlineLarge.copy(fontFamily = SpaceGrotesk),
    headlineMedium = base.headlineMedium.copy(fontFamily = SpaceGrotesk),
    headlineSmall = base.headlineSmall.copy(fontFamily = SpaceGrotesk),
    titleLarge = base.titleLarge.copy(fontFamily = SpaceGrotesk),
    titleMedium = base.titleMedium.copy(fontFamily = SpaceGrotesk),
    titleSmall = base.titleSmall.copy(fontFamily = SpaceGrotesk),
    bodyLarge = base.bodyLarge.copy(fontFamily = SpaceGrotesk),
    bodyMedium = base.bodyMedium.copy(fontFamily = SpaceGrotesk),
    bodySmall = base.bodySmall.copy(fontFamily = SpaceGrotesk),
    labelLarge = base.labelLarge.copy(fontFamily = SpaceGrotesk),
    labelMedium = base.labelMedium.copy(fontFamily = SpaceGrotesk),
    labelSmall = base.labelSmall.copy(fontFamily = SpaceGrotesk)
)