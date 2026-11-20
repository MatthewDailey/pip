package dev.playground.pip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalPipColors = staticCompositionLocalOf { PipLight }

/** Pip's tokens, for colours Material's scheme has no slot for (the halo, the escalation levels). */
object Pip {
  val colors: PipColors
    @Composable @ReadOnlyComposable get() = LocalPipColors.current
}

private fun PipColors.scheme(dark: Boolean) = if (dark) {
  darkColorScheme(
    primary = pr, onPrimary = onPr, secondary = tx2, background = bg, onBackground = tx, surface = sf, onSurface = tx,
    surfaceVariant = sf2, onSurfaceVariant = tx2, outline = ln, outlineVariant = ln, error = dgr, errorContainer = dgrC,
    surfaceContainer = sf, surfaceContainerHigh = sf2, surfaceContainerLow = bg,
  )
} else {
  lightColorScheme(
    primary = pr, onPrimary = onPr, secondary = tx2, background = bg, onBackground = tx, surface = sf, onSurface = tx,
    surfaceVariant = sf2, onSurfaceVariant = tx2, outline = ln, outlineVariant = ln, error = dgr, errorContainer = dgrC,
    surfaceContainer = sf, surfaceContainerHigh = sf2, surfaceContainerLow = bg,
  )
}

@Composable
fun PipTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val colors = if (darkTheme) PipDark else PipLight
  CompositionLocalProvider(LocalPipColors provides colors) {
    MaterialTheme(colorScheme = colors.scheme(darkTheme), typography = Typography, content = content)
  }
}
