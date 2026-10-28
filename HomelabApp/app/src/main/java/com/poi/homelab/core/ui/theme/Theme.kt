package com.poi.homelab.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightScheme = lightColorScheme(
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
)

private val DarkScheme = darkColorScheme(
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
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = HomelabTypography,
        shapes = HomelabShapes,
        content = content,
    )
}
