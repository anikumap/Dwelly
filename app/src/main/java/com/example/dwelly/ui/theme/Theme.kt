package com.example.dwelly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The only colour scheme. Dwelly is always black, blue and pink, whatever the phone's
 * theme or wallpaper, so dynamic colour is off.
 */
private val DwellyColorScheme = darkColorScheme(
    primary = Cobalt,
    onPrimary = Color.White,
    primaryContainer = CobaltDeep,
    onPrimaryContainer = Ice,
    inversePrimary = CobaltDeep,

    secondary = CobaltSoft,
    onSecondary = Black,
    secondaryContainer = MidnightHigh,
    onSecondaryContainer = Ice,

    tertiary = HotPink,
    onTertiary = Black,
    tertiaryContainer = PinkDeep,
    onTertiaryContainer = Ice,

    error = HotPink,
    onError = Black,
    errorContainer = PinkDeep,
    onErrorContainer = Ice,

    background = Black,
    onBackground = Ice,
    surface = Black,
    onSurface = Ice,
    // Kept distinct from the surfaceContainer colours: contentColorFor() matches surfaceVariant
    // first, so sharing a value would give cards the muted onSurfaceVariant text.
    surfaceVariant = MidnightHigh,
    onSurfaceVariant = Mist,
    surfaceTint = Cobalt,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Midnight,
    surfaceContainer = Midnight,
    surfaceContainerHigh = MidnightRaised,
    surfaceContainerHighest = MidnightRaised,

    outline = Outline,
    outlineVariant = Outline,
    scrim = Black,
)

/** Soft cards and sheets; buttons keep Material's pill shape. */
private val DwellyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun DwellyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DwellyColorScheme,
        typography = Typography,
        shapes = DwellyShapes,
        content = content,
    )
}
