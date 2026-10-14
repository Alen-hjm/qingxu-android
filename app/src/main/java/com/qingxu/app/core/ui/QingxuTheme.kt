package com.qingxu.app.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF285848),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F1E4),
    onPrimaryContainer = Color(0xFF243D35),
    secondary = Color(0xFF53665A),
    background = Color(0xFFFCF8F2),
    onBackground = Color(0xFF243D35),
    surface = Color(0xFFFFFDFA),
    onSurface = Color(0xFF243D35),
    surfaceVariant = Color(0xFFF2F0E9),
    onSurfaceVariant = Color(0xFF58675E),
    outline = Color(0xFF89978C),
    outlineVariant = Color(0xFFE3E7DD),
    surfaceContainer = Color(0xFFF3F3EC),
    surfaceContainerHigh = Color(0xFFEBEFE6),
    surfaceContainerHighest = Color(0xFFE6EADF),
    secondaryContainer = Color(0xFFE9EEE4),
    onSecondaryContainer = Color(0xFF243D35),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2D0B4),
    onPrimary = Color(0xFF17382A),
    primaryContainer = Color(0xFF2C4B3D),
    onPrimaryContainer = Color(0xFFD4EBDC),
    background = Color(0xFF131C17),
    surface = Color(0xFF131C17),
    onBackground = Color(0xFFE0E9DF),
    onSurface = Color(0xFFE0E9DF),
    surfaceVariant = Color(0xFF27362C),
    onSurfaceVariant = Color(0xFFB6C4B8),
    secondary = Color(0xFFAAC9B7),
    secondaryContainer = Color(0xFF2B493C),
    onSecondaryContainer = Color(0xFFDAEFE0),
    surfaceContainer = Color(0xFF1C2B22),
    surfaceContainerHigh = Color(0xFF293D30),
    surfaceContainerHighest = Color(0xFF324638),
    outlineVariant = Color(0xFF3C5143),
)

@Composable
fun QingxuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
            headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        ), content = content)
}
