package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.data.AiThemePalette
import com.example.data.ThemeMode

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.DARK }

private val ObsidianDarkScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = Color(0xFF063A38),
    onPrimaryContainer = Color(0xFF8FFFEF),
    secondary = CyberAzure,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF102A54),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = AmberGold,
    onTertiary = ObsidianVoid,
    background = ObsidianVoid,
    onBackground = MistWhite,
    surface = ObsidianSurface,
    onSurface = MistWhite,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = SlateMuted,
    outline = Color(0xFF283655),
    error = CrimsonAlert,
    onError = Color.White
)

private val DaylightLightScheme = lightColorScheme(
    primary = CobaltPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = EmeraldSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = WarmAmber,
    onTertiary = Color.White,
    background = DaylightBg,
    onBackground = InkDark,
    surface = DaylightSurface,
    onSurface = InkDark,
    surfaceVariant = DaylightSurfaceHigh,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

private val HackerColorScheme = darkColorScheme(
    primary = MatrixGreen,
    onPrimary = HackerVoid,
    primaryContainer = Color(0xFF0A3314),
    onPrimaryContainer = MatrixGreen,
    secondary = TerminalCyan,
    onSecondary = HackerVoid,
    secondaryContainer = Color(0xFF072B36),
    onSecondaryContainer = TerminalCyan,
    tertiary = HackerAmber,
    onTertiary = HackerVoid,
    background = HackerVoid,
    onBackground = HackerText,
    surface = HackerSurface,
    onSurface = HackerText,
    surfaceVariant = HackerSurfaceHigh,
    onSurfaceVariant = HackerMuted,
    outline = Color(0xFF1B5E28),
    error = Color(0xFFFF3B30),
    onError = Color.White
)

private val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val HackerShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp)
)

@Composable
fun AlfredsGalleryTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    customPalette: AiThemePalette? = null,
    content: @Composable () -> Unit
) {
    val baseScheme = when (themeMode) {
        ThemeMode.HACKER -> HackerColorScheme
        ThemeMode.LIGHT -> DaylightLightScheme
        ThemeMode.DARK -> ObsidianDarkScheme
    }

    val colorScheme = if (customPalette != null && themeMode != ThemeMode.HACKER) {
        val bg = parseHexColorOrFallback(customPalette.backgroundHex, baseScheme.background)
        val surf = parseHexColorOrFallback(customPalette.surfaceHex, baseScheme.surface)
        val prim = parseHexColorOrFallback(customPalette.primaryHex, baseScheme.primary)
        val sec = parseHexColorOrFallback(customPalette.secondaryHex, baseScheme.secondary)
        val tert = parseHexColorOrFallback(customPalette.tertiaryHex, baseScheme.tertiary)
        val isDarkBg = bg.luminance() < 0.4f
        val onBg = if (isDarkBg) Color(0xFFF8FAFC) else Color(0xFF0F172A)
        val onPrim = if (prim.luminance() < 0.45f) Color.White else Color(0xFF071019)
        if (isDarkBg) {
            darkColorScheme(
                primary = prim,
                onPrimary = onPrim,
                primaryContainer = prim.copy(alpha = 0.2f),
                onPrimaryContainer = prim,
                secondary = sec,
                onSecondary = Color.White,
                secondaryContainer = sec.copy(alpha = 0.2f),
                onSecondaryContainer = sec,
                tertiary = tert,
                onTertiary = onBg,
                background = bg,
                onBackground = onBg,
                surface = surf,
                onSurface = onBg,
                surfaceVariant = surf.copy(alpha = 0.85f),
                onSurfaceVariant = onBg.copy(alpha = 0.72f),
                outline = prim.copy(alpha = 0.35f),
                error = CrimsonAlert,
                onError = Color.White
            )
        } else {
            lightColorScheme(
                primary = prim,
                onPrimary = onPrim,
                primaryContainer = prim.copy(alpha = 0.16f),
                onPrimaryContainer = prim,
                secondary = sec,
                onSecondary = Color.White,
                tertiary = tert,
                onTertiary = Color.White,
                background = bg,
                onBackground = onBg,
                surface = surf,
                onSurface = onBg,
                surfaceVariant = surf.copy(alpha = 0.9f),
                onSurfaceVariant = onBg.copy(alpha = 0.68f),
                outline = prim.copy(alpha = 0.3f)
            )
        }
    } else {
        baseScheme
    }

    val typography = if (themeMode == ThemeMode.HACKER) HackerTypography else StudioTypography
    val shapes = if (themeMode == ThemeMode.HACKER) HackerShapes else StudioShapes

    CompositionLocalProvider(LocalThemeMode provides themeMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
