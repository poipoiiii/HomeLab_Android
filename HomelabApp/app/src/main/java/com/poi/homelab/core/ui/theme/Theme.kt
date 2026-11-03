package com.poi.homelab.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 一套配色风格在设置页里的展示信息：名称 + 三个代表色。 */
data class ThemeStyleOption(
    val id: String,
    val label: String,
    val swatches: List<Color>,
)

private val TideTeal = Color(0xFF1F6F7A)
private val TideBlue = Color(0xFF4C7397)
private val TideClay = Color(0xFFC9774B)
private val DuskPlum = Color(0xFF6B4E9E)
private val DuskRose = Color(0xFFB4557A)
private val DuskGold = Color(0xFFB98A37)
private val MossGreen = Color(0xFF4E6B3A)
private val MossClay = Color(0xFF9A6844)
private val MossOlive = Color(0xFF7E8F3F)

val appThemeStyles = listOf(
    ThemeStyleOption("sand", "暖沙", listOf(Coral, Teal, Amber)),
    ThemeStyleOption("tide", "潮汐", listOf(TideTeal, TideBlue, TideClay)),
    ThemeStyleOption("dusk", "暮光", listOf(DuskPlum, DuskRose, DuskGold)),
    ThemeStyleOption("moss", "苔原", listOf(MossGreen, MossClay, MossOlive)),
)

private class StyleSchemes(
    val light: ColorScheme,
    val dark: ColorScheme,
)

private val SandSchemes = StyleSchemes(
    light = lightColorScheme(
        primary = Coral,
        onPrimary = Color.White,
        primaryContainer = SurfaceCard,
        onPrimaryContainer = Ink,
        secondary = Teal,
        onSecondary = Color.White,
        tertiary = Amber,
        onTertiary = Ink,
        background = Canvas,
        onBackground = Ink,
        surface = Canvas,
        onSurface = Ink,
        surfaceVariant = SurfaceCard,
        onSurfaceVariant = BodyText,
        outline = Hairline,
        outlineVariant = Color(0xFFEBE6DF),
        error = Danger,
        onError = Color.White,
    ),
    dark = darkColorScheme(
        primary = Color(0xFFE09A7F),
        onPrimary = Color(0xFF2B160F),
        primaryContainer = DarkElevated,
        onPrimaryContainer = OnDark,
        secondary = Teal,
        onSecondary = Color(0xFF06201B),
        tertiary = Amber,
        onTertiary = Color(0xFF2A1A00),
        background = DarkSurface,
        onBackground = OnDark,
        surface = DarkSurface,
        onSurface = OnDark,
        surfaceVariant = DarkElevated,
        onSurfaceVariant = Color(0xFFD8CFC2),
        outline = DarkBorder,
        outlineVariant = Color(0xFF2F2C28),
        error = Color(0xFFE07A7A),
        onError = Color(0xFF2B0A0A),
    ),
)

private val TideSchemes = StyleSchemes(
    light = lightColorScheme(
        primary = TideTeal,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E8EA),
        onPrimaryContainer = Color(0xFF0C2A2E),
        secondary = TideBlue,
        onSecondary = Color.White,
        tertiary = TideClay,
        onTertiary = Color.White,
        background = Color(0xFFF1F6F6),
        onBackground = Color(0xFF10201F),
        surface = Color(0xFFF1F6F6),
        onSurface = Color(0xFF10201F),
        surfaceVariant = Color(0xFFE3ECEC),
        onSurfaceVariant = Color(0xFF384C4B),
        outline = Color(0xFFD2DFDF),
        outlineVariant = Color(0xFFDEE8E8),
        error = Danger,
        onError = Color.White,
    ),
    dark = darkColorScheme(
        primary = Color(0xFF6FC3CC),
        onPrimary = Color(0xFF04262A),
        primaryContainer = Color(0xFF173337),
        onPrimaryContainer = Color(0xFFE6F5F5),
        secondary = Color(0xFF8FB4D9),
        onSecondary = Color(0xFF0A1F33),
        tertiary = Color(0xFFE3A278),
        onTertiary = Color(0xFF2B1406),
        background = Color(0xFF0E191A),
        onBackground = Color(0xFFEAF3F2),
        surface = Color(0xFF0E191A),
        onSurface = Color(0xFFEAF3F2),
        surfaceVariant = Color(0xFF1A2A2C),
        onSurfaceVariant = Color(0xFFC6D7D7),
        outline = Color(0xFF2B3F41),
        outlineVariant = Color(0xFF223436),
        error = Color(0xFFE07A7A),
        onError = Color(0xFF2B0A0A),
    ),
)

private val DuskSchemes = StyleSchemes(
    light = lightColorScheme(
        primary = DuskPlum,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9DFF5),
        onPrimaryContainer = Color(0xFF24123B),
        secondary = DuskRose,
        onSecondary = Color.White,
        tertiary = DuskGold,
        onTertiary = Color.White,
        background = Color(0xFFF7F3F8),
        onBackground = Color(0xFF1D1622),
        surface = Color(0xFFF7F3F8),
        onSurface = Color(0xFF1D1622),
        surfaceVariant = Color(0xFFEDE5F0),
        onSurfaceVariant = Color(0xFF4A3E52),
        outline = Color(0xFFE1D6E6),
        outlineVariant = Color(0xFFEAE1EE),
        error = Danger,
        onError = Color.White,
    ),
    dark = darkColorScheme(
        primary = Color(0xFFBFA3E8),
        onPrimary = Color(0xFF221238),
        primaryContainer = Color(0xFF2E2140),
        onPrimaryContainer = Color(0xFFF0E8FA),
        secondary = Color(0xFFE08CA6),
        onSecondary = Color(0xFF33101C),
        tertiary = Color(0xFFE7BE78),
        onTertiary = Color(0xFF2A1A00),
        background = Color(0xFF171219),
        onBackground = Color(0xFFF3EBF7),
        surface = Color(0xFF171219),
        onSurface = Color(0xFFF3EBF7),
        surfaceVariant = Color(0xFF241C28),
        onSurfaceVariant = Color(0xFFD2C4D9),
        outline = Color(0xFF372B3D),
        outlineVariant = Color(0xFF2D2332),
        error = Color(0xFFE07A7A),
        onError = Color(0xFF2B0A0A),
    ),
)

private val MossSchemes = StyleSchemes(
    light = lightColorScheme(
        primary = MossGreen,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDDE8D1),
        onPrimaryContainer = Color(0xFF18240E),
        secondary = MossClay,
        onSecondary = Color.White,
        tertiary = MossOlive,
        onTertiary = Color.White,
        background = Color(0xFFF6F6EF),
        onBackground = Color(0xFF1B1F16),
        surface = Color(0xFFF6F6EF),
        onSurface = Color(0xFF1B1F16),
        surfaceVariant = Color(0xFFE9E8D9),
        onSurfaceVariant = Color(0xFF41473A),
        outline = Color(0xFFDDDCCB),
        outlineVariant = Color(0xFFE5E4D6),
        error = Danger,
        onError = Color.White,
    ),
    dark = darkColorScheme(
        primary = Color(0xFFA3C484),
        onPrimary = Color(0xFF16270C),
        primaryContainer = Color(0xFF2A3520),
        onPrimaryContainer = Color(0xFFEFF3E6),
        secondary = Color(0xFFD3A177),
        onSecondary = Color(0xFF2C1608),
        tertiary = Color(0xFFC0CF9A),
        onTertiary = Color(0xFF1C2408),
        background = Color(0xFF14170F),
        onBackground = Color(0xFFEFF3E6),
        surface = Color(0xFF14170F),
        onSurface = Color(0xFFEFF3E6),
        surfaceVariant = Color(0xFF20251A),
        onSurfaceVariant = Color(0xFFC9D0BD),
        outline = Color(0xFF333B29),
        outlineVariant = Color(0xFF2A3122),
        error = Color(0xFFE07A7A),
        onError = Color(0xFF2B0A0A),
    ),
)

private val HomelabShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun HomelabTheme(
    themeMode: String = "system",
    themeStyle: String = "sand",
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val schemes = when (themeStyle) {
        "tide" -> TideSchemes
        "dusk" -> DuskSchemes
        "moss" -> MossSchemes
        else -> SandSchemes
    }
    MaterialTheme(
        colorScheme = if (dark) schemes.dark else schemes.light,
        typography = HomelabTypography,
        shapes = HomelabShapes,
        content = content,
    )
}
