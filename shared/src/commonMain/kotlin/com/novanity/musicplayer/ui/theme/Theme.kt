package com.novanity.musicplayer.ui.theme

import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.ColorScheme
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import com.novanity.musicplayer.data.AccentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.novanity.musicplayer.platform.LocalPlatform
import com.novanity.musicplayer.resources.Res
import com.novanity.musicplayer.resources.inter
import org.jetbrains.compose.resources.Font

/** Accent used across the app (an iOS-style pink-red). */
val Accent = Color(0xFFFF375F)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4D0C1D),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFFF8FA3),
    onSecondary = Color(0xFF2B0710),
    secondaryContainer = Color(0xFF2A2A30),
    onSecondaryContainer = Color(0xFFF5F5F7),
    tertiary = Color(0xFFB79CFF),
    background = Color(0xFF0B0B0D),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF0B0B0D),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Color(0xFFA1A1A9),
    surfaceContainerLowest = Color(0xFF070708),
    surfaceContainerLow = Color(0xFF121214),
    surfaceContainer = Color(0xFF17171A),
    surfaceContainerHigh = Color(0xFF1E1E22),
    surfaceContainerHighest = Color(0xFF28282D),
    outline = Color(0xFF3C3C43),
    outlineVariant = Color(0xFF2A2A2F),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE5174B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF40000F),
    secondary = Color(0xFFB3264A),
    secondaryContainer = Color(0xFFEDEDF0),
    onSecondaryContainer = Color(0xFF111114),
    tertiary = Color(0xFF6A4CD6),
    background = Color.White,
    onBackground = Color(0xFF111114),
    surface = Color.White,
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFF0F0F2),
    onSurfaceVariant = Color(0xFF6B6B74),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8F9),
    surfaceContainer = Color(0xFFF3F3F5),
    surfaceContainerHigh = Color(0xFFEDEDF0),
    surfaceContainerHighest = Color(0xFFE6E6EA),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFE2E2E6),
)

@Composable
private fun inter(weight: Int) = Font(
    Res.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@Composable
private fun interFamily() = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

@Composable
private fun appTypography(): Typography {
    val family = interFamily()
    fun style(size: Int, weight: Int, line: Int, tracking: Double = 0.0) = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em,
    )
    // No negative letter spacing: Compose measures the line narrower than it draws, which
    // shaves the last letter (the "s" of "albums").
    return Typography(
        displayLarge = style(52, 800, 58),
        displayMedium = style(42, 800, 48),
        displaySmall = style(34, 800, 40),
        headlineLarge = style(30, 800, 36),
        headlineMedium = style(26, 700, 32),
        headlineSmall = style(22, 700, 28),
        titleLarge = style(20, 700, 26),
        titleMedium = style(16, 600, 22),
        titleSmall = style(14, 600, 20),
        bodyLarge = style(16, 400, 22),
        bodyMedium = style(14, 400, 20),
        bodySmall = style(12, 400, 16),
        labelLarge = style(14, 600, 20),
        labelMedium = style(12, 600, 16, 0.01),
        labelSmall = style(11, 600, 14, 0.03),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MusicPlayerTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    accent: AccentColor = AccentColor.ROSE,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val platform = LocalPlatform.current
    val dynamic = if (dynamicColor && platform.supportsDynamicColor) platform.dynamicColorScheme(darkTheme) else null
    val colors = when {
        // Keep the deep, high-contrast backgrounds even with wallpaper colors.
        dynamic != null && darkTheme -> dynamic.copy(background = DarkColors.background, surface = DarkColors.surface)
        dynamic != null -> dynamic
        darkTheme -> DarkColors.withAccent(Color(accent.argb), dark = true)
        else -> LightColors.withAccent(Color(accent.argb), dark = false)
    }.let { if (darkTheme && pureBlack) it.pureBlack() else it }
    val animated = colors.copy(
        // Theme changes ease in instead of snapping.
        primary = animateColorAsState(colors.primary, tween(350), label = "primary").value,
        background = animateColorAsState(colors.background, tween(350), label = "background").value,
        surface = animateColorAsState(colors.surface, tween(350), label = "surface").value,
    )
    MaterialTheme(colorScheme = animated, typography = appTypography(), shapes = AppShapes, content = content)
}

/** The same scheme with a different highlight colour (the default scheme is rose). */
private fun ColorScheme.withAccent(accent: Color, dark: Boolean): ColorScheme {
    if (accent == Accent) return this
    return if (dark) {
        copy(
            primary = accent,
            primaryContainer = lerp(accent, Color.Black, 0.72f),
            onPrimaryContainer = lerp(accent, Color.White, 0.8f),
            secondary = lerp(accent, Color.White, 0.4f),
        )
    } else {
        // A little deeper on white, so text and icons in the accent stay readable.
        val deep = lerp(accent, Color.Black, 0.18f)
        copy(
            primary = deep,
            primaryContainer = lerp(accent, Color.White, 0.8f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.7f),
            secondary = lerp(accent, Color.Black, 0.35f),
        )
    }
}

/** True-black backgrounds with slightly darker panels. */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0B),
    surfaceContainer = Color(0xFF101012),
    surfaceContainerHigh = Color(0xFF17171A),
    surfaceContainerHighest = Color(0xFF202024),
)
